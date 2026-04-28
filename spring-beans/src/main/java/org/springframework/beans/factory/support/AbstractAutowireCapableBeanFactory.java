/*
 * Copyright 2002-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyAccessorUtils;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.AutowiredPropertyMarker;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.NativeDetector;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodCallback;
import org.springframework.util.StringUtils;

/**
 * Abstract bean factory superclass that implements default bean creation,
 * with the full capabilities specified by the {@link RootBeanDefinition} class.
 * Implements the {@link org.springframework.beans.factory.config.AutowireCapableBeanFactory}
 * interface in addition to AbstractBeanFactory's {@link #createBean} method.
 *
 * <p>Provides bean creation (with constructor resolution), property population,
 * wiring (including autowiring), and initialization. Handles runtime bean
 * references, resolves managed collections, calls initialization methods, etc.
 * Supports autowiring constructors, properties by name, and properties by type.
 *
 * <p>The main template method to be implemented by subclasses is
 * {@link #resolveDependency(DependencyDescriptor, String, Set, TypeConverter)}, used for
 * autowiring. In case of a {@link org.springframework.beans.factory.ListableBeanFactory}
 * which is capable of searching its bean definitions, matching beans will typically be
 * implemented through such a search. Otherwise, simplified matching can be implemented.
 *
 * <p>Note that this class does <i>not</i> assume or implement bean definition
 * registry capabilities. See {@link DefaultListableBeanFactory} for an implementation
 * of the {@link org.springframework.beans.factory.ListableBeanFactory} and
 * {@link BeanDefinitionRegistry} interfaces, which represent the API and SPI
 * view of such a factory, respectively.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Chris Beams
 * @author Sam Brannen
 * @author Phillip Webb
 * @since 13.02.2004
 * @see RootBeanDefinition
 * @see DefaultListableBeanFactory
 * @see BeanDefinitionRegistry
 */
public abstract class AbstractAutowireCapableBeanFactory extends AbstractBeanFactory
		implements AutowireCapableBeanFactory {

	/** Strategy for creating bean instances. */
	private InstantiationStrategy instantiationStrategy;

	/** Resolver strategy for method parameter names. */
	@Nullable
	private ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

	/** Whether to automatically try to resolve circular references between beans. */
	private boolean allowCircularReferences = true;

	/**
	 * Whether to resort to injecting a raw bean instance in case of circular reference,
	 * even if the injected bean eventually got wrapped.
	 */
	private boolean allowRawInjectionDespiteWrapping = false;

	/**
	 * Dependency types to ignore on dependency check and autowire, as Set of
	 * Class objects: for example, String. Default is none.
	 */
	private final Set<Class<?>> ignoredDependencyTypes = new HashSet<>();

	/**
	 * Dependency interfaces to ignore on dependency check and autowire, as Set of
	 * Class objects. By default, only the BeanFactory interface is ignored.
	 */
	private final Set<Class<?>> ignoredDependencyInterfaces = new HashSet<>();

	/**
	 * The name of the currently created bean, for implicit dependency registration
	 * on getBean etc invocations triggered from a user-specified Supplier callback.
	 */
	private final NamedThreadLocal<String> currentlyCreatedBean = new NamedThreadLocal<>("Currently created bean");

	/** Cache of unfinished FactoryBean instances: FactoryBean name to BeanWrapper. */
	private final ConcurrentMap<String, BeanWrapper> factoryBeanInstanceCache = new ConcurrentHashMap<>();

	/** Cache of candidate factory methods per factory class. */
	private final ConcurrentMap<Class<?>, Method[]> factoryMethodCandidateCache = new ConcurrentHashMap<>();

	/** Cache of filtered PropertyDescriptors: bean Class to PropertyDescriptor array. */
	private final ConcurrentMap<Class<?>, PropertyDescriptor[]> filteredPropertyDescriptorsCache =
			new ConcurrentHashMap<>();


	/**
	 * Create a new AbstractAutowireCapableBeanFactory.
	 */
	public AbstractAutowireCapableBeanFactory() {
		super();
		ignoreDependencyInterface(BeanNameAware.class);
		ignoreDependencyInterface(BeanFactoryAware.class);
		ignoreDependencyInterface(BeanClassLoaderAware.class);
		if (NativeDetector.inNativeImage()) {
			this.instantiationStrategy = new SimpleInstantiationStrategy();
		}
		else {
			this.instantiationStrategy = new CglibSubclassingInstantiationStrategy();
		}
	}

	/**
	 * Create a new AbstractAutowireCapableBeanFactory with the given parent.
	 * @param parentBeanFactory parent bean factory, or {@code null} if none
	 */
	public AbstractAutowireCapableBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		this();
		setParentBeanFactory(parentBeanFactory);
	}


	/**
	 * Set the instantiation strategy to use for creating bean instances.
	 * Default is CglibSubclassingInstantiationStrategy.
	 * @see CglibSubclassingInstantiationStrategy
	 */
	public void setInstantiationStrategy(InstantiationStrategy instantiationStrategy) {
		this.instantiationStrategy = instantiationStrategy;
	}

	/**
	 * Return the instantiation strategy to use for creating bean instances.
	 */
	protected InstantiationStrategy getInstantiationStrategy() {
		return this.instantiationStrategy;
	}

	/**
	 * Set the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed (e.g. for constructor names).
	 * <p>Default is a {@link DefaultParameterNameDiscoverer}.
	 */
	public void setParameterNameDiscoverer(@Nullable ParameterNameDiscoverer parameterNameDiscoverer) {
		this.parameterNameDiscoverer = parameterNameDiscoverer;
	}

	/**
	 * Return the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed.
	 */
	@Nullable
	protected ParameterNameDiscoverer getParameterNameDiscoverer() {
		return this.parameterNameDiscoverer;
	}

	/**
	 * Set whether to allow circular references between beans - and automatically
	 * try to resolve them.
	 * <p>Note that circular reference resolution means that one of the involved beans
	 * will receive a reference to another bean that is not fully initialized yet.
	 * This can lead to subtle and not-so-subtle side effects on initialization;
	 * it does work fine for many scenarios, though.
	 * <p>Default is "true". Turn this off to throw an exception when encountering
	 * a circular reference, disallowing them completely.
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans. Refactor your application logic to have the two beans
	 * involved delegate to a third bean that encapsulates their common logic.
	 */
	public void setAllowCircularReferences(boolean allowCircularReferences) {
		this.allowCircularReferences = allowCircularReferences;
	}

	/**
	 * Return whether to allow circular references between beans.
	 * @since 5.3.10
	 * @see #setAllowCircularReferences
	 */
	public boolean isAllowCircularReferences() {
		return this.allowCircularReferences;
	}

	/**
	 * Set whether to allow the raw injection of a bean instance into some other
	 * bean's property, despite the injected bean eventually getting wrapped
	 * (for example, through AOP auto-proxying).
	 * <p>This will only be used as a last resort in case of a circular reference
	 * that cannot be resolved otherwise: essentially, preferring a raw instance
	 * getting injected over a failure of the entire bean wiring process.
	 * <p>Default is "false", as of Spring 2.0. Turn this on to allow for non-wrapped
	 * raw beans injected into some of your references, which was Spring 1.2's
	 * (arguably unclean) default behavior.
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans, in particular with auto-proxying involved.
	 * @see #setAllowCircularReferences
	 */
	public void setAllowRawInjectionDespiteWrapping(boolean allowRawInjectionDespiteWrapping) {
		this.allowRawInjectionDespiteWrapping = allowRawInjectionDespiteWrapping;
	}

	/**
	 * Return whether to allow the raw injection of a bean instance.
	 * @since 5.3.10
	 * @see #setAllowRawInjectionDespiteWrapping
	 */
	public boolean isAllowRawInjectionDespiteWrapping() {
		return this.allowRawInjectionDespiteWrapping;
	}

	/**
	 * Ignore the given dependency type for autowiring:
	 * for example, String. Default is none.
	 */
	public void ignoreDependencyType(Class<?> type) {
		this.ignoredDependencyTypes.add(type);
	}

	/**
	 * Ignore the given dependency interface for autowiring.
	 * <p>This will typically be used by application contexts to register
	 * dependencies that are resolved in other ways, like BeanFactory through
	 * BeanFactoryAware or ApplicationContext through ApplicationContextAware.
	 * <p>By default, only the BeanFactoryAware interface is ignored.
	 * For further types to ignore, invoke this method for each type.
	 * @see org.springframework.beans.factory.BeanFactoryAware
	 * @see org.springframework.context.ApplicationContextAware
	 */
	public void ignoreDependencyInterface(Class<?> ifc) {
		this.ignoredDependencyInterfaces.add(ifc);
	}

	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		super.copyConfigurationFrom(otherFactory);
		if (otherFactory instanceof AbstractAutowireCapableBeanFactory) {
			AbstractAutowireCapableBeanFactory otherAutowireFactory =
					(AbstractAutowireCapableBeanFactory) otherFactory;
			this.instantiationStrategy = otherAutowireFactory.instantiationStrategy;
			this.allowCircularReferences = otherAutowireFactory.allowCircularReferences;
			this.ignoredDependencyTypes.addAll(otherAutowireFactory.ignoredDependencyTypes);
			this.ignoredDependencyInterfaces.addAll(otherAutowireFactory.ignoredDependencyInterfaces);
		}
	}


	//-------------------------------------------------------------------------
	// Typical methods for creating and populating external bean instances
	//-------------------------------------------------------------------------

	@Override
	@SuppressWarnings("unchecked")
	public <T> T createBean(Class<T> beanClass) throws BeansException {
		// Use prototype bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass);
		bd.setScope(SCOPE_PROTOTYPE);
		bd.allowCaching = ClassUtils.isCacheSafe(beanClass, getBeanClassLoader());
		return (T) createBean(beanClass.getName(), bd, null);
	}

	@Override
	public void autowireBean(Object existingBean) {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(ClassUtils.getUserClass(existingBean));
		bd.setScope(SCOPE_PROTOTYPE);
		bd.allowCaching = ClassUtils.isCacheSafe(bd.getBeanClass(), getBeanClassLoader());
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	@Override
	public Object configureBean(Object existingBean, String beanName) throws BeansException {
		markBeanAsCreated(beanName);
		BeanDefinition mbd = getMergedBeanDefinition(beanName);
		RootBeanDefinition bd = null;
		if (mbd instanceof RootBeanDefinition) {
			RootBeanDefinition rbd = (RootBeanDefinition) mbd;
			bd = (rbd.isPrototype() ? rbd : rbd.cloneBeanDefinition());
		}
		if (bd == null) {
			bd = new RootBeanDefinition(mbd);
		}
		if (!bd.isPrototype()) {
			bd.setScope(SCOPE_PROTOTYPE);
			bd.allowCaching = ClassUtils.isCacheSafe(ClassUtils.getUserClass(existingBean), getBeanClassLoader());
		}
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(beanName, bd, bw);
		return initializeBean(beanName, existingBean, bd);
	}


	//-------------------------------------------------------------------------
	// Specialized methods for fine-grained control over the bean lifecycle
	//-------------------------------------------------------------------------

	@Override
	public Object createBean(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		return createBean(beanClass.getName(), bd, null);
	}

	@Override
	public Object autowire(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		if (bd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR) {
			return autowireConstructor(beanClass.getName(), bd, null, null).getWrappedInstance();
		}
		else {
			Object bean;
			if (System.getSecurityManager() != null) {
				bean = AccessController.doPrivileged(
						(PrivilegedAction<Object>) () -> getInstantiationStrategy().instantiate(bd, null, this),
						getAccessControlContext());
			}
			else {
				bean = getInstantiationStrategy().instantiate(bd, null, this);
			}
			populateBean(beanClass.getName(), bd, new BeanWrapperImpl(bean));
			return bean;
		}
	}

	@Override
	public void autowireBeanProperties(Object existingBean, int autowireMode, boolean dependencyCheck)
			throws BeansException {

		if (autowireMode == AUTOWIRE_CONSTRUCTOR) {
			throw new IllegalArgumentException("AUTOWIRE_CONSTRUCTOR not supported for existing bean instance");
		}
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd =
				new RootBeanDefinition(ClassUtils.getUserClass(existingBean), autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	@Override
	public void applyBeanPropertyValues(Object existingBean, String beanName) throws BeansException {
		markBeanAsCreated(beanName);
		BeanDefinition bd = getMergedBeanDefinition(beanName);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		applyPropertyValues(beanName, bd, bw, bd.getPropertyValues());
	}

	@Override
	public Object initializeBean(Object existingBean, String beanName) {
		return initializeBean(beanName, existingBean, null);
	}

	/**
	 * 应用所有已注册的 BeanPostProcessor 的 postProcessBeforeInitialization 方法。
	 *
	 * <p><b>执行时机：</b>Bean 实例化完成、依赖注入完成后，在 Bean 初始化方法之前调用。
	 *
	 * <p><b>调用链：</b>
	 * <pre>
	 * doCreateBean
	 *   → 实例化对象
	 *   → 依赖注入
	 *   → populateBean
	 *   → initializeBean (当前方法在此被调用)
	 *       → applyBeanPostProcessorsBeforeInitialization  ← 当前位置
	 *       → invokeInitMethods (PostConstruct, afterPropertiesSet, init-method)
	 *       → applyBeanPostProcessorsAfterInitialization
	 * </pre>
	 *
	 * <p><b>核心作用：</b>
	 * <ul>
	 *   <li>允许在 Bean 初始化方法执行前对 Bean 实例进行修改、包装或增强</li>
	 *   <li>可以返回代理对象，替代原始 Bean 实例</li>
	 *   <li>多个 BeanPostProcessor 会按顺序链式处理，每个处理器的输出作为下一个的输入</li>
	 * </ul>
	 *
	 * <p><b>返回值规则：</b>
	 * <ul>
	 *   <li>返回非 null：继续传递到下一个处理器，最终作为 Bean 的最终实例</li>
	 *   <li>返回 null：<b>立即终止处理</b>，直接返回之前的处理结果，后续处理器不再执行</li>
	 * </ul>
	 *
	 * <p><b>典型应用场景：</b>
	 * <ul>
	 *   <li><b>ApplicationContextAware 注入</b>：ApplicationContextAwareProcessor 在此设置 ApplicationContext</li>
	 *   <li><b>自定义注解处理</b>：处理 @PostConstruct 以外的初始化注解</li>
	 *   <li><b>Bean 验证</b>：在初始化前检查 Bean 状态</li>
	 *   <li><b>代理生成</b>：某些场景下在此生成代理对象（通常 AOP 在 after 阶段更常见）</li>
	 * </ul>
	 *
	 * <p><b>注意事项：</b>
	 * <ul>
	 *   <li>此时 Bean 的属性已经注入完成（@Autowired、@Resource 等已生效）</li>
	 *   <li>Bean 的初始化方法（@PostConstruct、afterPropertiesSet、init-method）尚未执行</li>
	 *   <li>如果多个处理器，任何一个返回 null 都会导致后续处理器被跳过</li>
	 *   <li>这个方法属于标准的 BeanPostProcessor 接口，区别于 InstantiationAwareBeanPostProcessor</li>
	 * </ul>
	 *
	 * @param existingBean 现有的 bean 实例（已经完成依赖注入）
	 * @param beanName bean 的名称
	 * @return 处理后的 bean 实例（可能被包装、增强或替换），如果任何处理器返回 null 则返回之前的结果
	 * @throws BeansException 如果处理失败
	 */
	@Override
	public Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName)
			throws BeansException {

		Object result = existingBean;

		// 遍历所有已注册的 BeanPostProcessor（按优先级排序）
		// 常见实现类：ApplicationContextAwareProcessor、ConfigurationPropertiesBindingPostProcessor 等
		for (BeanPostProcessor processor : getBeanPostProcessors()) {
			// 调用当前处理器的 postProcessBeforeInitialization 方法
			// 处理器此时可以：
			// 1. 修改传入的 bean 实例的任意属性
			// 2. 包装 bean 实例（如返回代理对象）
			// 3. 完全替换 bean 实例
			// 4. 对 bean 进行验证或额外初始化
			Object current = processor.postProcessBeforeInitialization(result, beanName);

			// 返回 null 是一种"短路"机制
			// 通常用于：处理器遇到错误、或者某个条件不满足需要终止后续处理
			// 注意：实际 Spring 源码中很少返回 null，但接口允许这样做
			if (current == null) {
				// 立即返回当前结果，不再执行剩余的处理器
				return result;
			}
			// 更新 result，作为下一个处理器的输入
			// 这样链式调用可以让多个处理器对同一个 bean 进行多次增强
			result = current;
		}
		return result;
	}

	/**
	 * 应用所有已注册的 BeanPostProcessor 的 postProcessAfterInitialization 方法。
	 *
	 * <p><b>执行时机：</b>
	 * 在 Bean 的所有初始化方法完成之后调用，是 Bean 生命周期的最后一个处理阶段。
	 *
	 * <p><b>调用链路：</b>
	 * <pre>
	 * AbstractAutowireCapableBeanFactory.initializeBean()
	 *   → 1. applyBeanPostProcessorsBeforeInitialization()  ← 第5个扩展点
	 *   → 2. invokeInitMethods()                            ← 执行初始化方法
	 *        → @PostConstruct
	 *        → afterPropertiesSet()
	 *        → init-method
	 *   → 3. <b>applyBeanPostProcessorsAfterInitialization()</b> ← 第6个扩展点（当前位置）
	 * </pre>
	 *
	 * <p><b>核心作用：</b>
	 * <ul>
	 *   <li><b>AOP 代理生成</b>：这是 Spring AOP 创建代理对象的默认位置。
	 *       {@link AbstractAutoProxyCreator} 在此方法中检查并创建代理</li>
	 *   <li><b>最终包装</b>：对完全初始化的 Bean 进行最后的包装或增强</li>
	 *   <li><b>验证与记录</b>：在 Bean 完全就绪后进行最终验证或日志记录</li>
	 * </ul>
	 *
	 * <p><b>与 postProcessBeforeInitialization 的区别：</b>
	 * <ul>
	 *   <li>Before：在初始化方法执行前调用，此时 Bean 属性已注入但未初始化</li>
	 *   <li>After：在初始化方法执行后调用，此时 Bean 已经完全就绪</li>
	 * </ul>
	 *
	 * <p><b>返回值规则：</b>
	 * <ul>
	 *   <li>返回非 null：继续传递到下一个处理器，最终作为 Bean 的最终实例</li>
	 *   <li>返回 null：<b>立即终止处理</b>，直接返回之前的处理结果，后续处理器不再执行</li>
	 * </ul>
	 *
	 * <p><b>典型实现示例：</b>
	 * <pre>{@code
	 * // Spring AOP 的核心实现（简化版）
	 * public class AbstractAutoProxyCreator extends ProxyProcessorSupport
	 *         implements BeanPostProcessor {
	 *
	 *     @Override
	 *     public Object postProcessAfterInitialization(Object bean, String beanName) {
	 *         if (isInfrastructureClass(bean.getClass())) {
	 *             return bean;
	 *         }
	 *
	 *         // 检查是否需要代理
	 *         Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(bean.getClass(), beanName, null);
	 *         if (specificInterceptors != DO_NOT_PROXY) {
	 *             // 创建代理对象
	 *             return createProxy(bean.getClass(), beanName, specificInterceptors);
	 *         }
	 *         return bean;
	 *     }
	 * }
	 * }</pre>
	 *
	 * <p><b>注意事项：</b>
	 * <ul>
	 *   <li>此方法是 Bean 生命周期中<b>最后一个扩展点</b>（第6个）</li>
	 *   <li>在此之后，Bean 就会被放入一级缓存（singletonObjects）中供应用程序使用</li>
	 *   <li>如果多个处理器，任何一个返回 null 都会导致后续处理器被跳过</li>
	 *   <li>返回的最终结果将作为 Bean 的实际实例（可能是原始对象，也可能是代理对象）</li>
	 * </ul>
	 *
	 * @param existingBean 现有的 Bean 实例（已完成属性注入和初始化方法调用）
	 * @param beanName Bean 的名称
	 * @return 处理后的 Bean 实例，可能是原始对象或包装后的对象（如代理）
	 * @throws BeansException 如果处理过程中发生错误
	 */
	@Override
	public Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName)
			throws BeansException {

		Object result = existingBean;

		// 遍历所有已注册的 BeanPostProcessor
		// 注意：getBeanPostProcessors() 返回的是按优先级排序的列表
		// 优先级高的处理器会先执行
		for (BeanPostProcessor processor : getBeanPostProcessors()) {
			// 调用后处理器的 postProcessAfterInitialization 方法
			// 对于 AOP 场景，此时会判断是否需要创建代理对象
			Object current = processor.postProcessAfterInitialization(result, beanName);

			// 短路机制：如果某个处理器返回 null，立即停止处理
			// 返回 null 通常表示发生错误或需要终止后续处理
			// 注意：标准实现中极少返回 null，但接口允许这样做
			if (current == null) {
				return result;
			}
			// 链式处理：将当前处理器的输出作为下一个处理器的输入
			// 这样的设计允许多个处理器对同一个 Bean 进行多次增强
			result = current;
		}

		// 返回最终处理结果
		// 对于普通的 Bean，返回原始对象
		// 对于需要 AOP 的 Bean，返回代理对象（由 AbstractAutoProxyCreator 创建）
		return result;
	}

	@Override
	public void destroyBean(Object existingBean) {
		new DisposableBeanAdapter(
				existingBean, getBeanPostProcessorCache().destructionAware, getAccessControlContext()).destroy();
	}


	//-------------------------------------------------------------------------
	// Delegate methods for resolving injection points
	//-------------------------------------------------------------------------

	@Override
	public Object resolveBeanByName(String name, DependencyDescriptor descriptor) {
		InjectionPoint previousInjectionPoint = ConstructorResolver.setCurrentInjectionPoint(descriptor);
		try {
			return getBean(name, descriptor.getDependencyType());
		}
		finally {
			ConstructorResolver.setCurrentInjectionPoint(previousInjectionPoint);
		}
	}

	@Override
	@Nullable
	public Object resolveDependency(DependencyDescriptor descriptor, @Nullable String requestingBeanName) throws BeansException {
		return resolveDependency(descriptor, requestingBeanName, null, null);
	}


	//---------------------------------------------------------------------
	// Implementation of relevant AbstractBeanFactory template methods
	//---------------------------------------------------------------------

	/**
	 * 此类中的核心方法：创建 bean 实例、
	 * 填充 bean 实例、应用后处理器等。
	 *
	 * @see #doCreateBean
	 */
	@Override
	protected Object createBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException {

		if (logger.isTraceEnabled()) {
			logger.trace("Creating instance of bean '" + beanName + "'");
		}

		RootBeanDefinition mbdToUse = mbd;

		// ============ 步骤1：解析 bean 的 Class ============
		// 确保此时 bean 的类已经被解析
		// 如果类是动态解析的（不能存储在共享的合并 BeanDefinition 中），则克隆一份 BeanDefinition
		Class<?> resolvedClass = resolveBeanClass(mbd, beanName);
		if (resolvedClass != null && !mbd.hasBeanClass() && mbd.getBeanClassName() != null) {
			// 克隆 BeanDefinition，因为动态解析的类不能存储在共享的合并 BeanDefinition 中
			mbdToUse = new RootBeanDefinition(mbd);
			mbdToUse.setBeanClass(resolvedClass);
		}

		// ============ 步骤2：准备方法覆盖（lookup-method 和 replaced-method）============
		try {
			// 验证并准备 @Lookup 注解或 XML 中定义的 method-override
			mbdToUse.prepareMethodOverrides();
		} catch (BeanDefinitionValidationException ex) {
			throw new BeanDefinitionStoreException(mbdToUse.getResourceDescription(),
					beanName, "Validation of method overrides failed", ex);
		}

		// ============ 步骤3：BeanPostProcessor 前置处理（短路机制）============
		try {
			// 给 BeanPostProcessor 一个机会返回代理对象而不是目标 bean 实例
			// 这就是 AOP 创建代理的入口点之一
			Object bean = resolveBeforeInstantiation(beanName, mbdToUse);
			if (bean != null) {
				// 如果后置处理器返回了对象（如代理），则直接返回，不再走常规创建流程
				return bean;
			}
		} catch (Throwable ex) {
			throw new BeanCreationException(mbdToUse.getResourceDescription(), beanName,
					"BeanPostProcessor before instantiation of bean failed", ex);
		}

		// ============ 步骤4：常规创建 bean（核心流程）============
		try {
			Object beanInstance = doCreateBean(beanName, mbdToUse, args);
			if (logger.isTraceEnabled()) {
				logger.trace("Finished creating instance of bean '" + beanName + "'");
			}
			return beanInstance;
		} catch (BeanCreationException | ImplicitlyAppearedSingletonException ex) {
			// 之前检测到的异常，带有正确的 bean 创建上下文，直接向上抛出
			throw ex;
		} catch (Throwable ex) {
			throw new BeanCreationException(
					mbdToUse.getResourceDescription(), beanName, "Unexpected exception during bean creation", ex);
		}
	}

	/**
	 * 实际创建指定的 bean。此时前置处理（如检查 {@code postProcessBeforeInstantiation} 回调）已经完成。
	 * <p>区分三种实例化方式：默认 bean 实例化、使用工厂方法、构造函数自动装配。
	 *
	 * @param beanName bean 的名称
	 * @param mbd 合并后的 bean 定义
	 * @param args 用于构造函数或工厂方法调用的显式参数
	 * @return bean 的新实例
	 * @throws BeanCreationException 如果 bean 无法创建
	 * @see #instantiateBean
	 * @see #instantiateUsingFactoryMethod
	 * @see #autowireConstructor
	 */
	protected Object doCreateBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException {

		// ============ 步骤1：创建 Bean 实例 ============
		BeanWrapper instanceWrapper = null;
		// 如果是单例，尝试从缓存中获取 FactoryBean 的实例包装器
		if (mbd.isSingleton()) {
			instanceWrapper = this.factoryBeanInstanceCache.remove(beanName);
		}
		if (instanceWrapper == null) {
			// 真正创建 Bean 实例：通过构造函数或工厂方法
			instanceWrapper = createBeanInstance(beanName, mbd, args);
		}
		Object bean = instanceWrapper.getWrappedInstance();  // 原始 Bean 实例
		Class<?> beanType = instanceWrapper.getWrappedClass();
		if (beanType != NullBean.class) {
			mbd.resolvedTargetType = beanType;
		}

		// ============ 步骤2：允许后处理器修改合并后的 BeanDefinition ============
		synchronized (mbd.postProcessingLock) {
			if (!mbd.postProcessed) {
				try {
					// 调用 MergedBeanDefinitionPostProcessor
					// 例如：AutowiredAnnotationBeanPostProcessor 在这里收集 @Autowired 的元数据
					applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);
				} catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Post-processing of merged bean definition failed", ex);
				}
				mbd.postProcessed = true;
			}
		}

		// ============ 步骤3：提前暴露 Bean（用于解决循环依赖）============
		// 条件：单例 && 允许循环依赖 && 当前 Bean 正在创建中
		boolean earlySingletonExposure = (mbd.isSingleton() && this.allowCircularReferences &&
				isSingletonCurrentlyInCreation(beanName));
		if (earlySingletonExposure) {
			if (logger.isTraceEnabled()) {
				logger.trace("Eagerly caching bean '" + beanName +
						"' to allow for resolving potential circular references");
			}
			// 将 Bean 的早期引用（一个 ObjectFactory）注册到三级缓存
			// 这样其他 Bean 在循环依赖时可以先拿到这个半成品引用
			addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean));
		}

		// ============ 步骤4：初始化 Bean 实例 ============
		Object exposedObject = bean;
		try {
			// 4.1 填充属性（依赖注入）
			// 这里会处理 @Autowired、@Resource、@Value 等注解
			populateBean(beanName, mbd, instanceWrapper);

			// 4.2 执行初始化
			// 包括：Aware 方法回调、@PostConstruct、init-method、BeanPostProcessor 等
			exposedObject = initializeBean(beanName, exposedObject, mbd);
		} catch (Throwable ex) {
			if (ex instanceof BeanCreationException && beanName.equals(((BeanCreationException) ex).getBeanName())) {
				throw (BeanCreationException) ex;
			} else {
				throw new BeanCreationException(
						mbd.getResourceDescription(), beanName, "Initialization of bean failed", ex);
			}
		}

		// ============ 步骤5：循环依赖的最终处理 ============
		if (earlySingletonExposure) {
			// 获取早期暴露的 Bean 引用（从二级缓存中）
			Object earlySingletonReference = getSingleton(beanName, false);
			if (earlySingletonReference != null) {
				// 如果早期引用存在，说明发生了循环依赖
				if (exposedObject == bean) {
					// 当前 Bean 没有被后处理器包装（如 AOP 代理），直接使用早期引用
					exposedObject = earlySingletonReference;
				} else if (!this.allowRawInjectionDespiteWrapping && hasDependentBean(beanName)) {
					// 当前 Bean 被后处理器包装了（如产生了代理），但其他依赖它的 Bean 可能已经注入了原始版本
					// 这种情况下需要检查并抛出异常，避免不一致的状态
					String[] dependentBeans = getDependentBeans(beanName);
					Set<String> actualDependentBeans = new LinkedHashSet<>(dependentBeans.length);
					for (String dependentBean : dependentBeans) {
						if (!removeSingletonIfCreatedForTypeCheckOnly(dependentBean)) {
							actualDependentBeans.add(dependentBean);
						}
					}
					if (!actualDependentBeans.isEmpty()) {
						throw new BeanCurrentlyInCreationException(beanName,
								"Bean with name '" + beanName + "' has been injected into other beans [" +
										StringUtils.collectionToCommaDelimitedString(actualDependentBeans) +
										"] in its raw version as part of a circular reference, but has eventually been " +
										"wrapped. This means that said other beans do not use the final version of the " +
										"bean. This is often the result of over-eager type matching - consider using " +
										"'getBeanNamesForType' with the 'allowEagerInit' flag turned off, for example.");
					}
				}
			}
		}

		// ============ 步骤6：注册可销毁的 Bean ============
		try {
			// 如果 Bean 实现了 DisposableBean 或定义了 destroy-method，注册到销毁列表中
			registerDisposableBeanIfNecessary(beanName, bean, mbd);
		} catch (BeanDefinitionValidationException ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Invalid destruction signature", ex);
		}

		return exposedObject;
	}

	@Override
	@Nullable
	protected Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		// 1. 先确定基础的目标类型
		Class<?> targetType = determineTargetType(beanName, mbd, typesToMatch);

		// 2. 如果有后置处理器，应用它们来预测最终类型
		if (targetType != null && !mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			boolean matchingOnlyFactoryBean = (typesToMatch.length == 1 && typesToMatch[0] == FactoryBean.class);

			// 3. 👇 这里遍历所有 SmartInstantiationAwareBeanPostProcessor
			for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
				// 4. 👇 调用 Smart 接口的 predictBeanType 方法
				Class<?> predicted = bp.predictBeanType(targetType, beanName);
				if (predicted != null &&
						(!matchingOnlyFactoryBean || FactoryBean.class.isAssignableFrom(predicted))) {
					return predicted;  // 返回预测的类型
				}
			}
		}
		return targetType;
	}

	/**
	 * Determine the target type for the given bean definition.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 */
	@Nullable
	protected Class<?> determineTargetType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		Class<?> targetType = mbd.getTargetType();
		if (targetType == null) {
			targetType = (mbd.getFactoryMethodName() != null ?
					getTypeForFactoryMethod(beanName, mbd, typesToMatch) :
					resolveBeanClass(mbd, beanName, typesToMatch));
			if (ObjectUtils.isEmpty(typesToMatch) || getTempClassLoader() == null) {
				mbd.resolvedTargetType = targetType;
			}
		}
		return targetType;
	}

	/**
	 * Determine the target type for the given bean definition which is based on
	 * a factory method. Only called if there is no singleton instance registered
	 * for the target bean already.
	 * <p>This implementation determines the type matching {@link #createBean}'s
	 * different creation strategies. As far as possible, we'll perform static
	 * type checking to avoid creation of the target bean.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 * @see #createBean
	 */
	@Nullable
	protected Class<?> getTypeForFactoryMethod(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		ResolvableType cachedReturnType = mbd.factoryMethodReturnType;
		if (cachedReturnType != null) {
			return cachedReturnType.resolve();
		}

		Class<?> commonType = null;
		Method uniqueCandidate = mbd.factoryMethodToIntrospect;

		if (uniqueCandidate == null) {
			Class<?> factoryClass;
			boolean isStatic = true;

			String factoryBeanName = mbd.getFactoryBeanName();
			if (factoryBeanName != null) {
				if (factoryBeanName.equals(beanName)) {
					throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
							"factory-bean reference points back to the same bean definition");
				}
				// Check declared factory method return type on factory class.
				factoryClass = getType(factoryBeanName);
				isStatic = false;
			}
			else {
				// Check declared factory method return type on bean class.
				factoryClass = resolveBeanClass(mbd, beanName, typesToMatch);
			}

			if (factoryClass == null) {
				return null;
			}
			factoryClass = ClassUtils.getUserClass(factoryClass);

			// If all factory methods have the same return type, return that type.
			// Can't clearly figure out exact method due to type converting / autowiring!
			int minNrOfArgs =
					(mbd.hasConstructorArgumentValues() ? mbd.getConstructorArgumentValues().getArgumentCount() : 0);
			Method[] candidates = this.factoryMethodCandidateCache.computeIfAbsent(factoryClass,
					clazz -> ReflectionUtils.getUniqueDeclaredMethods(clazz, ReflectionUtils.USER_DECLARED_METHODS));

			for (Method candidate : candidates) {
				if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate) &&
						candidate.getParameterCount() >= minNrOfArgs) {
					// Declared type variables to inspect?
					if (candidate.getTypeParameters().length > 0) {
						try {
							// Fully resolve parameter names and argument values.
							Class<?>[] paramTypes = candidate.getParameterTypes();
							String[] paramNames = null;
							ParameterNameDiscoverer pnd = getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
							ConstructorArgumentValues cav = mbd.getConstructorArgumentValues();
							Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
							Object[] args = new Object[paramTypes.length];
							for (int i = 0; i < args.length; i++) {
								ConstructorArgumentValues.ValueHolder valueHolder = cav.getArgumentValue(
										i, paramTypes[i], (paramNames != null ? paramNames[i] : null), usedValueHolders);
								if (valueHolder == null) {
									valueHolder = cav.getGenericArgumentValue(null, null, usedValueHolders);
								}
								if (valueHolder != null) {
									args[i] = valueHolder.getValue();
									usedValueHolders.add(valueHolder);
								}
							}
							Class<?> returnType = AutowireUtils.resolveReturnTypeForFactoryMethod(
									candidate, args, getBeanClassLoader());
							uniqueCandidate = (commonType == null && returnType == candidate.getReturnType() ?
									candidate : null);
							commonType = ClassUtils.determineCommonAncestor(returnType, commonType);
							if (commonType == null) {
								// Ambiguous return types found: return null to indicate "not determinable".
								return null;
							}
						}
						catch (Throwable ex) {
							if (logger.isDebugEnabled()) {
								logger.debug("Failed to resolve generic return type for factory method: " + ex);
							}
						}
					}
					else {
						uniqueCandidate = (commonType == null ? candidate : null);
						commonType = ClassUtils.determineCommonAncestor(candidate.getReturnType(), commonType);
						if (commonType == null) {
							// Ambiguous return types found: return null to indicate "not determinable".
							return null;
						}
					}
				}
			}

			mbd.factoryMethodToIntrospect = uniqueCandidate;
			if (commonType == null) {
				return null;
			}
		}

		// Common return type found: all factory methods return same type. For a non-parameterized
		// unique candidate, cache the full type declaration context of the target factory method.
		cachedReturnType = (uniqueCandidate != null ?
				ResolvableType.forMethodReturnType(uniqueCandidate) : ResolvableType.forClass(commonType));
		mbd.factoryMethodReturnType = cachedReturnType;
		return cachedReturnType.resolve();
	}

	/**
	 * This implementation attempts to query the FactoryBean's generic parameter metadata
	 * if present to determine the object type. If not present, i.e. the FactoryBean is
	 * declared as a raw type, it checks the FactoryBean's {@code getObjectType} method
	 * on a plain instance of the FactoryBean, without bean properties applied yet.
	 * If this doesn't return a type yet and {@code allowInit} is {@code true}, full
	 * creation of the FactoryBean is attempted as fallback (through delegation to the
	 * superclass implementation).
	 * <p>The shortcut check for a FactoryBean is only applied in case of a singleton
	 * FactoryBean. If the FactoryBean instance itself is not kept as singleton,
	 * it will be fully created to check the type of its exposed object.
	 */
	@Override
	protected ResolvableType getTypeForFactoryBean(String beanName, RootBeanDefinition mbd, boolean allowInit) {
		// Check if the bean definition itself has defined the type with an attribute
		ResolvableType result = getTypeForFactoryBeanFromAttributes(mbd);
		if (result != ResolvableType.NONE) {
			return result;
		}

		ResolvableType beanType =
				(mbd.hasBeanClass() ? ResolvableType.forClass(mbd.getBeanClass()) : ResolvableType.NONE);

		// For instance supplied beans try the target type and bean class
		if (mbd.getInstanceSupplier() != null) {
			result = getFactoryBeanGeneric(mbd.targetType);
			if (result.resolve() != null) {
				return result;
			}
			result = getFactoryBeanGeneric(beanType);
			if (result.resolve() != null) {
				return result;
			}
		}

		// Consider factory methods
		String factoryBeanName = mbd.getFactoryBeanName();
		String factoryMethodName = mbd.getFactoryMethodName();

		// Scan the factory bean methods
		if (factoryBeanName != null) {
			if (factoryMethodName != null) {
				// Try to obtain the FactoryBean's object type from its factory method
				// declaration without instantiating the containing bean at all.
				BeanDefinition factoryBeanDefinition = getBeanDefinition(factoryBeanName);
				Class<?> factoryBeanClass;
				if (factoryBeanDefinition instanceof AbstractBeanDefinition &&
						((AbstractBeanDefinition) factoryBeanDefinition).hasBeanClass()) {
					factoryBeanClass = ((AbstractBeanDefinition) factoryBeanDefinition).getBeanClass();
				}
				else {
					RootBeanDefinition fbmbd = getMergedBeanDefinition(factoryBeanName, factoryBeanDefinition);
					factoryBeanClass = determineTargetType(factoryBeanName, fbmbd);
				}
				if (factoryBeanClass != null) {
					result = getTypeForFactoryBeanFromMethod(factoryBeanClass, factoryMethodName);
					if (result.resolve() != null) {
						return result;
					}
				}
			}
			// If not resolvable above and the referenced factory bean doesn't exist yet,
			// exit here - we don't want to force the creation of another bean just to
			// obtain a FactoryBean's object type...
			if (!isBeanEligibleForMetadataCaching(factoryBeanName)) {
				return ResolvableType.NONE;
			}
		}

		// If we're allowed, we can create the factory bean and call getObjectType() early
		if (allowInit) {
			FactoryBean<?> factoryBean = (mbd.isSingleton() ?
					getSingletonFactoryBeanForTypeCheck(beanName, mbd) :
					getNonSingletonFactoryBeanForTypeCheck(beanName, mbd));
			if (factoryBean != null) {
				// Try to obtain the FactoryBean's object type from this early stage of the instance.
				Class<?> type = getTypeForFactoryBean(factoryBean);
				if (type != null) {
					return ResolvableType.forClass(type);
				}
				// No type found for shortcut FactoryBean instance:
				// fall back to full creation of the FactoryBean instance.
				return super.getTypeForFactoryBean(beanName, mbd, true);
			}
		}

		if (factoryBeanName == null && mbd.hasBeanClass() && factoryMethodName != null) {
			// No early bean instantiation possible: determine FactoryBean's type from
			// static factory method signature or from class inheritance hierarchy...
			return getTypeForFactoryBeanFromMethod(mbd.getBeanClass(), factoryMethodName);
		}
		result = getFactoryBeanGeneric(beanType);
		if (result.resolve() != null) {
			return result;
		}
		return ResolvableType.NONE;
	}

	private ResolvableType getFactoryBeanGeneric(@Nullable ResolvableType type) {
		if (type == null) {
			return ResolvableType.NONE;
		}
		return type.as(FactoryBean.class).getGeneric();
	}

	/**
	 * Introspect the factory method signatures on the given bean class,
	 * trying to find a common {@code FactoryBean} object type declared there.
	 * @param beanClass the bean class to find the factory method on
	 * @param factoryMethodName the name of the factory method
	 * @return the common {@code FactoryBean} object type, or {@code null} if none
	 */
	private ResolvableType getTypeForFactoryBeanFromMethod(Class<?> beanClass, String factoryMethodName) {
		// CGLIB subclass methods hide generic parameters; look at the original user class.
		Class<?> factoryBeanClass = ClassUtils.getUserClass(beanClass);
		FactoryBeanMethodTypeFinder finder = new FactoryBeanMethodTypeFinder(factoryMethodName);
		ReflectionUtils.doWithMethods(factoryBeanClass, finder, ReflectionUtils.USER_DECLARED_METHODS);
		return finder.getResult();
	}

	/**
	 * This implementation attempts to query the FactoryBean's generic parameter metadata
	 * if present to determine the object type. If not present, i.e. the FactoryBean is
	 * declared as a raw type, checks the FactoryBean's {@code getObjectType} method
	 * on a plain instance of the FactoryBean, without bean properties applied yet.
	 * If this doesn't return a type yet, a full creation of the FactoryBean is
	 * used as fallback (through delegation to the superclass's implementation).
	 * <p>The shortcut check for a FactoryBean is only applied in case of a singleton
	 * FactoryBean. If the FactoryBean instance itself is not kept as singleton,
	 * it will be fully created to check the type of its exposed object.
	 */
	@Override
	@Deprecated
	@Nullable
	protected Class<?> getTypeForFactoryBean(String beanName, RootBeanDefinition mbd) {
		return getTypeForFactoryBean(beanName, mbd, true).resolve();
	}

	/**
	 * Obtain a reference for early access to the specified bean,
	 * typically for the purpose of resolving a circular reference.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param bean the raw bean instance
	 * @return the object to expose as bean reference
	 */
	protected Object getEarlyBeanReference(String beanName, RootBeanDefinition mbd, Object bean) {
		Object exposedObject = bean;
		if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
				exposedObject = bp.getEarlyBeanReference(exposedObject, beanName);
			}
		}
		return exposedObject;
	}


	//---------------------------------------------------------------------
	// Implementation methods
	//---------------------------------------------------------------------

	/**
	 * Obtain a "shortcut" singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization of the FactoryBean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	@Nullable
	private FactoryBean<?> getSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		synchronized (getSingletonMutex()) {
			BeanWrapper bw = this.factoryBeanInstanceCache.get(beanName);
			if (bw != null) {
				return (FactoryBean<?>) bw.getWrappedInstance();
			}
			Object beanInstance = getSingleton(beanName, false);
			if (beanInstance instanceof FactoryBean) {
				return (FactoryBean<?>) beanInstance;
			}
			if (isSingletonCurrentlyInCreation(beanName) ||
					(mbd.getFactoryBeanName() != null && isSingletonCurrentlyInCreation(mbd.getFactoryBeanName()))) {
				return null;
			}

			Object instance;
			try {
				// Mark this bean as currently in creation, even if just partially.
				beforeSingletonCreation(beanName);
				// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
				instance = resolveBeforeInstantiation(beanName, mbd);
				if (instance == null) {
					bw = createBeanInstance(beanName, mbd, null);
					instance = bw.getWrappedInstance();
				}
			}
			catch (UnsatisfiedDependencyException ex) {
				// Don't swallow, probably misconfiguration...
				throw ex;
			}
			catch (BeanCreationException ex) {
				// Don't swallow a linkage error since it contains a full stacktrace on
				// first occurrence... and just a plain NoClassDefFoundError afterwards.
				if (ex.contains(LinkageError.class)) {
					throw ex;
				}
				// Instantiation failure, maybe too early...
				if (logger.isDebugEnabled()) {
					logger.debug("Bean creation exception on singleton FactoryBean type check: " + ex);
				}
				onSuppressedException(ex);
				return null;
			}
			finally {
				// Finished partial creation of this bean.
				afterSingletonCreation(beanName);
			}

			FactoryBean<?> fb = getFactoryBean(beanName, instance);
			if (bw != null) {
				this.factoryBeanInstanceCache.put(beanName, bw);
			}
			return fb;
		}
	}

	/**
	 * Obtain a "shortcut" non-singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization of the FactoryBean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	@Nullable
	private FactoryBean<?> getNonSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		if (isPrototypeCurrentlyInCreation(beanName)) {
			return null;
		}

		Object instance;
		try {
			// Mark this bean as currently in creation, even if just partially.
			beforePrototypeCreation(beanName);
			// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
			instance = resolveBeforeInstantiation(beanName, mbd);
			if (instance == null) {
				BeanWrapper bw = createBeanInstance(beanName, mbd, null);
				instance = bw.getWrappedInstance();
			}
		}
		catch (UnsatisfiedDependencyException ex) {
			// Don't swallow, probably misconfiguration...
			throw ex;
		}
		catch (BeanCreationException ex) {
			// Instantiation failure, maybe too early...
			if (logger.isDebugEnabled()) {
				logger.debug("Bean creation exception on non-singleton FactoryBean type check: " + ex);
			}
			onSuppressedException(ex);
			return null;
		}
		finally {
			// Finished partial creation of this bean.
			afterPrototypeCreation(beanName);
		}

		return getFactoryBean(beanName, instance);
	}

	/**
	 * 应用所有 MergedBeanDefinitionPostProcessor 到指定的 bean 定义上，
	 * 调用它们的 {@code postProcessMergedBeanDefinition} 方法。
	 *
	 * <p><b>调用时机：</b>在 Bean 实例化之前，但在 BeanDefinition 合并完成之后
	 * （位于 doCreateBean 方法内，实例化代码执行之前）
	 *
	 * <p><b>主要用途：</b>
	 * <ul>
	 *   <li>允许 PostProcessor 在 Bean 实例化之前，对合并后的 BeanDefinition 进行元数据的检查和修改
	 *   <li>可以提前解析并缓存 Bean 的某些元信息（如依赖的注解、需要注入的属性等）
	 *   <li>典型的应用：@Autowired、@Resource、@Value 等注解的解析就发生在这里
	 * </ul>
	 *
	 * <p><b>与其他扩展点的顺序：</b>
	 * <ol>
	 *   <li>postProcessBeforeInstantiation (最早，在 resolveBeforeInstantiation 中调用)</li>
	 *   <li><b>postProcessMergedBeanDefinition (当前方法，在 doCreateBean 开头调用)</b></li>
	 *   <li>实例化构造函数执行</li>
	 *   <li>依赖注入（属性填充）</li>
	 *   <li>postProcessBeforeInitialization</li>
	 *   <li>初始化方法</li>
	 *   <li>postProcessAfterInitialization</li>
	 * </ol>
	 *
	 * <p><b>注意事项：</b>
	 * <ul>
	 *   <li>这里的 BeanDefinition 是合并后的 RootBeanDefinition（已经处理了父定义）
	 *   <li>此时 Bean 实例尚未创建，只能操作 Bean 的元数据定义，不能操作实例
	 *   <li>同一个 bean 定义只会被处理一次（在第一次实例化时）
	 * </ul>
	 *
	 * @param mbd 合并后的 bean 定义（RootBeanDefinition 类型）
	 * @param beanType 实际管理的 bean 实例类型（可能是一个 Class，也可能是 null）
	 * @param beanName bean 的名称
	 * @see MergedBeanDefinitionPostProcessor#postProcessMergedBeanDefinition
	 */
	protected void applyMergedBeanDefinitionPostProcessors(RootBeanDefinition mbd, Class<?> beanType, String beanName) {
		// 从缓存中获取所有 MergedBeanDefinitionPostProcessor 类型的处理器
		// getBeanPostProcessorCache().mergedDefinition 是在容器启动时就分类缓存好的
		for (MergedBeanDefinitionPostProcessor processor : getBeanPostProcessorCache().mergedDefinition) {
			// 依次调用每个处理器的 postProcessMergedBeanDefinition 方法
			// 常见的实现类包括：
			// - AutowiredAnnotationBeanPostProcessor：解析 @Autowired、@Value
			// - CommonAnnotationBeanPostProcessor：解析 @Resource、@PostConstruct、@PreDestroy
			// - ScheduledAnnotationBeanPostProcessor：解析 @Scheduled
			processor.postProcessMergedBeanDefinition(mbd, beanType, beanName);
		}
	}

	/**
	 * Apply before-instantiation post-processors, resolving whether there is a
	 * before-instantiation shortcut for the specified bean.
	 * 应用实例化前的后处理器，判断指定 bean 是否存在实例化前快捷方式。
	 * <p>
	 * 这个方法允许 BeanPostProcessor 在 Bean 实际实例化之前返回一个代理对象，
	 * 从而跳过正常的实例化、属性注入和初始化流程。这是 AOP 等高级特性的关键扩展点。
	 *
	 * @param beanName the name of the bean
	 *              bean 的名称
	 * @param mbd the bean definition for the bean
	 *              bean 的 BeanDefinition 对象
	 * @return the shortcut-determined bean instance, or {@code null} if none
	 *         快捷方式确定的 bean 实例，如果没有则返回 {@code null}
	 */
	@Nullable
	protected Object resolveBeforeInstantiation(String beanName, RootBeanDefinition mbd) {
		Object bean = null;

		// 检查 beforeInstantiationResolved 标志
		// 这个标志用于缓存该 bean 是否已经尝试过实例化前处理
		//  - null: 未尝试过
		//  - true: 之前已经成功创建了快捷方式对象
		//  - false: 之前尝试过但没有创建快捷方式对象
		if (!Boolean.FALSE.equals(mbd.beforeInstantiationResolved)) {
			// 条件1: bean 不是合成 bean（合成 bean 是框架内部生成的，如 Spring AOP 相关的基础设施）
			// 条件2: 存在实现了 InstantiationAwareBeanPostProcessor 的后处理器
			if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
				// 确定目标类型（可能经过 FactoryBean 等处理后的实际类型）
				Class<?> targetType = determineTargetType(beanName, mbd);
				if (targetType != null) {
					// 步骤1: 应用实例化前的后处理器（postProcessBeforeInstantiation）
					// 这是最早期的扩展点，允许完全替换掉 Bean 的实例
					bean = applyBeanPostProcessorsBeforeInstantiation(targetType, beanName);

					if (bean != null) {
						// 步骤2: 如果前置处理器返回了对象（非 null），
						// 立即应用初始化后的后处理器（postProcessAfterInitialization）
						// 注意：此时会跳过正常的实例化、属性注入、初始化阶段
						bean = applyBeanPostProcessorsAfterInitialization(bean, beanName);
					}
				}
			}
			// 缓存结果：如果 bean 不为 null，说明快捷方式已创建，设置为 true
			// 如果 bean 为 null，说明没有快捷方式，设置为 false，避免重复尝试
			mbd.beforeInstantiationResolved = (bean != null);
		}
		return bean;
	}

	/**
	 * Apply InstantiationAwareBeanPostProcessors to the specified bean definition
	 * (by class and name), invoking their {@code postProcessBeforeInstantiation} methods.
	 * <p>Any returned object will be used as the bean instead of actually instantiating
	 * the target bean. A {@code null} return value from the post-processor will
	 * result in the target bean being instantiated.
	 * 将 InstantiationAwareBeanPostProcessor 应用于指定的 bean 定义（通过类和名称），
	 * 调用它们的 {@code postProcessBeforeInstantiation} 方法。
	 * <p>任何返回的非空对象将被用作 bean 实例，而不会实际实例化目标 bean。
	 * 后处理器返回 {@code null} 值将导致目标 bean 被正常实例化。
	 *
	 * @param beanClass the class of the bean to be instantiated
	 *                  要实例化的 bean 的 Class 对象
	 * @param beanName the name of the bean
	 *                  bean 的名称
	 * @return the bean object to use instead of a default instance of the target bean, or {@code null}
	 *         用于替代目标 bean 默认实例的 bean 对象，如果没有则返回 {@code null}
	 * @see InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation
	 */
	@Nullable
	protected Object applyBeanPostProcessorsBeforeInstantiation(Class<?> beanClass, String beanName) {
		// 遍历所有 InstantiationAwareBeanPostProcessor（实例化感知的 Bean 后处理器）
		// 从后处理器缓存中获取，避免每次重新获取，提升性能
		for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
			// 调用后处理器的 postProcessBeforeInstantiation 方法
			// 这是 Bean 生命周期中最早的扩展点，在实例化之前触发
			Object result = bp.postProcessBeforeInstantiation(beanClass, beanName);

			if (result != null) {
				// 一旦某个后处理器返回了非空对象，立即返回该对象
				// 注意：后续的后处理器不会再被调用（短路逻辑）
				// 返回的对象将完全替代 Spring 正常的 Bean 创建流程
				return result;
			}
		}
		// 所有后处理器都返回 null，继续正常的 Bean 创建流程
		return null;
	}

	/**
	 * 为指定的 bean 创建新实例，使用适当的实例化策略：
	 * 工厂方法、构造函数自动装配或简单实例化。
	 *
	 * @param beanName bean 的名称
	 * @param mbd bean 的 bean 定义
	 * @param args 用于构造函数或工厂方法调用的显式参数
	 * @return 新实例的 BeanWrapper
	 * @see #obtainFromSupplier
	 * @see #instantiateUsingFactoryMethod
	 * @see #autowireConstructor
	 * @see #instantiateBean
	 */
	protected BeanWrapper createBeanInstance(String beanName, RootBeanDefinition mbd, @Nullable Object[] args) {

		// ============ 步骤1：解析并验证 Bean 类 ============
		// 确保此时 bean 的类已经被解析
		Class<?> beanClass = resolveBeanClass(mbd, beanName);

		// 检查类的访问权限：如果不是 public 类，且不允许访问非 public 类，则抛异常
		if (beanClass != null && !Modifier.isPublic(beanClass.getModifiers()) && !mbd.isNonPublicAccessAllowed()) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean class isn't public, and non-public access not allowed: " + beanClass.getName());
		}

		// ============ 步骤2：使用 Supplier（函数式创建）============
		Supplier<?> instanceSupplier = mbd.getInstanceSupplier();
		if (instanceSupplier != null) {
			// 通过 Supplier 函数式接口创建 Bean 实例
			// 例如：context.registerBean(MyBean.class, () -> new MyBean())
			return obtainFromSupplier(instanceSupplier, beanName);
		}

		// ============ 步骤3：使用工厂方法创建 ============
		if (mbd.getFactoryMethodName() != null) {
			// 处理 @Bean 方法或 XML 中配置的 factory-method
			return instantiateUsingFactoryMethod(beanName, mbd, args);
		}

		// ============ 步骤4：快捷路径（重新创建同一个 Bean）============
		// 当之前已经解析过构造函数时，可以跳过重复解析
		boolean resolved = false;
		boolean autowireNecessary = false;
		if (args == null) {
			synchronized (mbd.constructorArgumentLock) {
				if (mbd.resolvedConstructorOrFactoryMethod != null) {
					resolved = true;                    // 构造函数/工厂方法已解析
					autowireNecessary = mbd.constructorArgumentsResolved;  // 参数是否已解析
				}
			}
		}
		if (resolved) {
			if (autowireNecessary) {
				// 需要构造函数自动装配（带参构造）
				return autowireConstructor(beanName, mbd, null, null);
			} else {
				// 无需自动装配（无参构造）
				return instantiateBean(beanName, mbd);
			}
		}

		// ============ 步骤5：通过 BeanPostProcessor 确定候选构造函数 ============
		// 调用 SmartInstantiationAwareBeanPostProcessor.determineCandidateConstructors()
		// 例如：@Autowired 注解的构造函数会被识别出来
		Constructor<?>[] ctors = determineConstructorsFromBeanPostProcessors(beanClass, beanName);
		if (ctors != null || mbd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR ||
				mbd.hasConstructorArgumentValues() || !ObjectUtils.isEmpty(args)) {
			// 存在候选构造函数 或 需要构造函数自动装配 或 有构造参数值 或 传入了显式参数
			return autowireConstructor(beanName, mbd, ctors, args);
		}

		// ============ 步骤6：使用首选构造函数（如果有）============
		ctors = mbd.getPreferredConstructors();
		if (ctors != null) {
			return autowireConstructor(beanName, mbd, ctors, null);
		}

		// ============ 步骤7：默认方式 - 无参构造函数 ============
		// 最简单的情况：通过无参构造函数实例化
		return instantiateBean(beanName, mbd);
	}

	/**
	 * Obtain a bean instance from the given supplier.
	 * @param instanceSupplier the configured supplier
	 * @param beanName the corresponding bean name
	 * @return a BeanWrapper for the new instance
	 * @since 5.0
	 * @see #getObjectForBeanInstance
	 */
	protected BeanWrapper obtainFromSupplier(Supplier<?> instanceSupplier, String beanName) {
		Object instance;

		String outerBean = this.currentlyCreatedBean.get();
		this.currentlyCreatedBean.set(beanName);
		try {
			instance = instanceSupplier.get();
		}
		finally {
			if (outerBean != null) {
				this.currentlyCreatedBean.set(outerBean);
			}
			else {
				this.currentlyCreatedBean.remove();
			}
		}

		if (instance == null) {
			instance = new NullBean();
		}
		BeanWrapper bw = new BeanWrapperImpl(instance);
		initBeanWrapper(bw);
		return bw;
	}

	/**
	 * Overridden in order to implicitly register the currently created bean as
	 * dependent on further beans getting programmatically retrieved during a
	 * {@link Supplier} callback.
	 * @since 5.0
	 * @see #obtainFromSupplier
	 */
	@Override
	protected Object getObjectForBeanInstance(
			Object beanInstance, String name, String beanName, @Nullable RootBeanDefinition mbd) {

		String currentlyCreatedBean = this.currentlyCreatedBean.get();
		if (currentlyCreatedBean != null) {
			registerDependentBean(beanName, currentlyCreatedBean);
		}

		return super.getObjectForBeanInstance(beanInstance, name, beanName, mbd);
	}

	/**
	 * Determine candidate constructors to use for the given bean, checking all registered
	 * {@link SmartInstantiationAwareBeanPostProcessor SmartInstantiationAwareBeanPostProcessors}.
	 * @param beanClass the raw class of the bean
	 * @param beanName the name of the bean
	 * @return the candidate constructors, or {@code null} if none specified
	 * @throws org.springframework.beans.BeansException in case of errors
	 * @see org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor#determineCandidateConstructors
	 */
	@Nullable
	protected Constructor<?>[] determineConstructorsFromBeanPostProcessors(@Nullable Class<?> beanClass, String beanName)
			throws BeansException {

		if (beanClass != null && hasInstantiationAwareBeanPostProcessors()) {
			for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
				Constructor<?>[] ctors = bp.determineCandidateConstructors(beanClass, beanName);
				if (ctors != null) {
					return ctors;
				}
			}
		}
		return null;
	}

	/**
	 * 使用默认构造函数实例化给定的 bean。
	 *
	 * @param beanName bean 的名称
	 * @param mbd bean 的 bean 定义
	 * @return 新实例的 BeanWrapper
	 */
	protected BeanWrapper instantiateBean(String beanName, RootBeanDefinition mbd) {
		try {
			Object beanInstance;

			// ============ 步骤1：通过实例化策略创建 Bean 实例 ============
			// 检查是否有安全管理器（启用安全模式）
			if (System.getSecurityManager() != null) {
				// 有安全管理器时，使用特权方式执行实例化
				// 这允许在受保护的环境中执行反射操作
				beanInstance = AccessController.doPrivileged(
						(PrivilegedAction<Object>) () -> getInstantiationStrategy().instantiate(mbd, beanName, this),
						getAccessControlContext());
			} else {
				// 普通情况：直接使用实例化策略创建 Bean
				beanInstance = getInstantiationStrategy().instantiate(mbd, beanName, this);
			}

			// ============ 步骤2：将实例包装成 BeanWrapper ============
			// BeanWrapper 提供了对 Bean 属性的操作能力（如属性设置、类型转换等）
			BeanWrapper bw = new BeanWrapperImpl(beanInstance);

			// ============ 步骤3：初始化 BeanWrapper ============
			// 设置类型转换器、属性编辑器等
			initBeanWrapper(bw);

			return bw;
		} catch (Throwable ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Instantiation of bean failed", ex);
		}
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * mbd parameter specifies a class, rather than a factoryBean, or an instance variable
	 * on a factory object itself configured using Dependency Injection.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (implying the use of constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 * @see #getBean(String, Object[])
	 */
	protected BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {

		return new ConstructorResolver(this).instantiateUsingFactoryMethod(beanName, mbd, explicitArgs);
	}

	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param ctors the chosen candidate constructors
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (implying the use of constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	protected BeanWrapper autowireConstructor(
			String beanName, RootBeanDefinition mbd, @Nullable Constructor<?>[] ctors, @Nullable Object[] explicitArgs) {

		return new ConstructorResolver(this).autowireConstructor(beanName, mbd, ctors, explicitArgs);
	}

	/**
	 * 填充Bean实例的属性值，将Bean定义中的属性值应用到BeanWrapper包装的实例上
	 * 这是Spring IoC依赖注入的核心方法之一
	 *
	 * @param beanName bean的名称
	 * @param mbd bean的合并定义（包含所有配置信息）
	 * @param bw 包装了Bean实例的BeanWrapper，用于设置属性值
	 */
	@SuppressWarnings("deprecation")  // 为了兼容postProcessPropertyValues这个已过时的方法
	protected void populateBean(String beanName, RootBeanDefinition mbd, @Nullable BeanWrapper bw) {
		// 1. 校验BeanWrapper是否存在
		if (bw == null) {
			if (mbd.hasPropertyValues()) {
				throw new BeanCreationException(
						mbd.getResourceDescription(), beanName, "Cannot apply property values to null instance");
			}
			else {
				// 没有需要设置的属性，直接返回
				return;
			}
		}

		// 2. 调用InstantiationAwareBeanPostProcessors的postProcessAfterInstantiation方法
		// 这给了后置处理器在属性填充之前修改Bean状态的机会，支持字段注入等高级特性
		// 例如：@Autowired注解的注入实际上是在这个阶段之后通过其他方式处理的
		if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
				// 如果后置处理器返回false，表示不需要进行属性填充，直接返回
				if (!bp.postProcessAfterInstantiation(bw.getWrappedInstance(), beanName)) {
					return;
				}
			}
		}

		// 3. 获取需要设置的属性值
		PropertyValues pvs = (mbd.hasPropertyValues() ? mbd.getPropertyValues() : null);

		// 4. 处理自动装配（byName或byType）
		int resolvedAutowireMode = mbd.getResolvedAutowireMode();
		if (resolvedAutowireMode == AUTOWIRE_BY_NAME || resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
			// 创建可变的PropertyValues，用于添加自动装配发现的属性
			MutablePropertyValues newPvs = new MutablePropertyValues(pvs);

			// 4.1 按名称自动装配：根据属性名在容器中查找匹配的Bean
			// 例如：类中有属性userService，容器中有名为userService的Bean，则自动注入
			if (resolvedAutowireMode == AUTOWIRE_BY_NAME) {
				autowireByName(beanName, mbd, bw, newPvs);
			}

			// 4.2 按类型自动装配：根据属性类型在容器中查找匹配的Bean
			// 例如：类中有属性UserService，容器中有UserService类型的Bean，则自动注入
			if (resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
				autowireByType(beanName, mbd, bw, newPvs);
			}
			pvs = newPvs;
		}

		// 5. 准备后置处理器和依赖检查相关变量
		boolean hasInstAwareBpps = hasInstantiationAwareBeanPostProcessors();
		boolean needsDepCheck = (mbd.getDependencyCheck() != AbstractBeanDefinition.DEPENDENCY_CHECK_NONE);

		PropertyDescriptor[] filteredPds = null;

		// 6. 调用InstantiationAwareBeanPostProcessors进行属性值的后置处理
		// 这是Spring中@Autowired、@Resource、@Value等注解注入的关键扩展点
		if (hasInstAwareBpps) {
			if (pvs == null) {
				pvs = mbd.getPropertyValues();
			}
			for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
				// 优先使用postProcessProperties（新方法，Spring 5.1+推荐）
				PropertyValues pvsToUse = bp.postProcessProperties(pvs, bw.getWrappedInstance(), beanName);
				if (pvsToUse == null) {
					// 降级使用postProcessPropertyValues（已过时的方法，为了向后兼容）
					if (filteredPds == null) {
						filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
					}
					pvsToUse = bp.postProcessPropertyValues(pvs, filteredPds, bw.getWrappedInstance(), beanName);
					if (pvsToUse == null) {
						return;
					}
				}
				pvs = pvsToUse;
			}
		}

		// 7. 执行依赖检查（验证所有依赖是否都已被注入）
		if (needsDepCheck) {
			if (filteredPds == null) {
				filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
			}
			checkDependencies(beanName, mbd, filteredPds, pvs);
		}

		// 8. 最终应用属性值到Bean实例中
		// 这一步真正执行属性设置，包括类型转换、嵌套属性处理等
		if (pvs != null) {
			applyPropertyValues(beanName, mbd, bw, pvs);
		}
	}

	/**
	 * Fill in any missing property values with references to
	 * other beans in this factory if autowire is set to "byName".
	 * @param beanName the name of the bean we're wiring up.
	 * Useful for debugging messages; not used functionally.
	 * @param mbd bean definition to update through autowiring
	 * @param bw the BeanWrapper from which we can obtain information about the bean
	 * @param pvs the PropertyValues to register wired objects with
	 */
	protected void autowireByName(
			String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {

		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		for (String propertyName : propertyNames) {
			if (containsBean(propertyName)) {
				Object bean = getBean(propertyName);
				pvs.add(propertyName, bean);
				registerDependentBean(propertyName, beanName);
				if (logger.isTraceEnabled()) {
					logger.trace("Added autowiring by name from bean name '" + beanName +
							"' via property '" + propertyName + "' to bean named '" + propertyName + "'");
				}
			}
			else {
				if (logger.isTraceEnabled()) {
					logger.trace("Not autowiring property '" + propertyName + "' of bean '" + beanName +
							"' by name: no matching bean found");
				}
			}
		}
	}

	/**
	 * Abstract method defining "autowire by type" (bean properties by type) behavior.
	 * <p>This is like PicoContainer default, in which there must be exactly one bean
	 * of the property type in the bean factory. This makes bean factories simple to
	 * configure for small namespaces, but doesn't work as well as standard Spring
	 * behavior for bigger applications.
	 * @param beanName the name of the bean to autowire by type
	 * @param mbd the merged bean definition to update through autowiring
	 * @param bw the BeanWrapper from which we can obtain information about the bean
	 * @param pvs the PropertyValues to register wired objects with
	 */
	protected void autowireByType(
			String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {

		TypeConverter converter = getCustomTypeConverter();
		if (converter == null) {
			converter = bw;
		}

		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		Set<String> autowiredBeanNames = new LinkedHashSet<>(propertyNames.length * 2);
		for (String propertyName : propertyNames) {
			try {
				PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
				// Don't try autowiring by type for type Object: never makes sense,
				// even if it technically is an unsatisfied, non-simple property.
				if (Object.class != pd.getPropertyType()) {
					MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
					// Do not allow eager init for type matching in case of a prioritized post-processor.
					boolean eager = !(bw.getWrappedInstance() instanceof PriorityOrdered);
					DependencyDescriptor desc = new AutowireByTypeDependencyDescriptor(methodParam, eager);
					Object autowiredArgument = resolveDependency(desc, beanName, autowiredBeanNames, converter);
					if (autowiredArgument != null) {
						pvs.add(propertyName, autowiredArgument);
					}
					for (String autowiredBeanName : autowiredBeanNames) {
						registerDependentBean(autowiredBeanName, beanName);
						if (logger.isTraceEnabled()) {
							logger.trace("Autowiring by type from bean name '" + beanName + "' via property '" +
									propertyName + "' to bean named '" + autowiredBeanName + "'");
						}
					}
					autowiredBeanNames.clear();
				}
			}
			catch (BeansException ex) {
				throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, propertyName, ex);
			}
		}
	}


	/**
	 * Return an array of non-simple bean properties that are unsatisfied.
	 * These are probably unsatisfied references to other beans in the
	 * factory. Does not include simple properties like primitives or Strings.
	 * @param mbd the merged bean definition the bean was created with
	 * @param bw the BeanWrapper the bean was created with
	 * @return an array of bean property names
	 * @see org.springframework.beans.BeanUtils#isSimpleProperty
	 */
	protected String[] unsatisfiedNonSimpleProperties(AbstractBeanDefinition mbd, BeanWrapper bw) {
		Set<String> result = new TreeSet<>();
		PropertyValues pvs = mbd.getPropertyValues();
		PropertyDescriptor[] pds = bw.getPropertyDescriptors();
		for (PropertyDescriptor pd : pds) {
			if (pd.getWriteMethod() != null && !isExcludedFromDependencyCheck(pd) && !pvs.contains(pd.getName()) &&
					!BeanUtils.isSimpleProperty(pd.getPropertyType())) {
				result.add(pd.getName());
			}
		}
		return StringUtils.toStringArray(result);
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 * @param bw the BeanWrapper the bean was created with
	 * @param cache whether to cache filtered PropertyDescriptors for the given bean Class
	 * @return the filtered PropertyDescriptors
	 * @see #isExcludedFromDependencyCheck
	 * @see #filterPropertyDescriptorsForDependencyCheck(org.springframework.beans.BeanWrapper)
	 */
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw, boolean cache) {
		PropertyDescriptor[] filtered = this.filteredPropertyDescriptorsCache.get(bw.getWrappedClass());
		if (filtered == null) {
			filtered = filterPropertyDescriptorsForDependencyCheck(bw);
			if (cache) {
				PropertyDescriptor[] existing =
						this.filteredPropertyDescriptorsCache.putIfAbsent(bw.getWrappedClass(), filtered);
				if (existing != null) {
					filtered = existing;
				}
			}
		}
		return filtered;
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 * @param bw the BeanWrapper the bean was created with
	 * @return the filtered PropertyDescriptors
	 * @see #isExcludedFromDependencyCheck
	 */
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw) {
		List<PropertyDescriptor> pds = new ArrayList<>(Arrays.asList(bw.getPropertyDescriptors()));
		pds.removeIf(this::isExcludedFromDependencyCheck);
		return pds.toArray(new PropertyDescriptor[0]);
	}

	/**
	 * Determine whether the given bean property is excluded from dependency checks.
	 * <p>This implementation excludes properties defined by CGLIB and
	 * properties whose type matches an ignored dependency type or which
	 * are defined by an ignored dependency interface.
	 * @param pd the PropertyDescriptor of the bean property
	 * @return whether the bean property is excluded
	 * @see #ignoreDependencyType(Class)
	 * @see #ignoreDependencyInterface(Class)
	 */
	protected boolean isExcludedFromDependencyCheck(PropertyDescriptor pd) {
		return (AutowireUtils.isExcludedFromDependencyCheck(pd) ||
				this.ignoredDependencyTypes.contains(pd.getPropertyType()) ||
				AutowireUtils.isSetterDefinedInInterface(pd, this.ignoredDependencyInterfaces));
	}

	/**
	 * Perform a dependency check that all properties exposed have been set,
	 * if desired. Dependency checks can be objects (collaborating beans),
	 * simple (primitives and String), or all (both).
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition the bean was created with
	 * @param pds the relevant property descriptors for the target bean
	 * @param pvs the property values to be applied to the bean
	 * @see #isExcludedFromDependencyCheck(java.beans.PropertyDescriptor)
	 */
	protected void checkDependencies(
			String beanName, AbstractBeanDefinition mbd, PropertyDescriptor[] pds, @Nullable PropertyValues pvs)
			throws UnsatisfiedDependencyException {

		int dependencyCheck = mbd.getDependencyCheck();
		for (PropertyDescriptor pd : pds) {
			if (pd.getWriteMethod() != null && (pvs == null || !pvs.contains(pd.getName()))) {
				boolean isSimple = BeanUtils.isSimpleProperty(pd.getPropertyType());
				boolean unsatisfied = (dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_ALL) ||
						(isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_SIMPLE) ||
						(!isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_OBJECTS);
				if (unsatisfied) {
					throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, pd.getName(),
							"Set this property value or disable dependency checking for this bean.");
				}
			}
		}
	}

	/**
	 * 应用给定的属性值到Bean实例中，解析其中对其他Bean的运行时引用
	 * 必须使用深拷贝，以避免永久修改原始属性值
	 *
	 * 这是Spring依赖注入的最终执行环节，负责：
	 * 1. 解析属性值中的运行时引用（如ref、bean引用）
	 * 2. 进行类型转换（如String转Date、Integer等）
	 * 3. 通过反射调用setter方法或直接设置字段值
	 *
	 * @param beanName bean名称，用于更好的异常信息
	 * @param mbd 合并的bean定义
	 * @param bw 包装目标对象的BeanWrapper
	 * @param pvs 待应用的属性值集合
	 */
	protected void applyPropertyValues(String beanName, BeanDefinition mbd, BeanWrapper bw, PropertyValues pvs) {
		// 如果没有属性需要设置，直接返回
		if (pvs.isEmpty()) {
			return;
		}

		// 安全管理器相关设置（用于特权操作）
		if (System.getSecurityManager() != null && bw instanceof BeanWrapperImpl) {
			((BeanWrapperImpl) bw).setSecurityContext(getAccessControlContext());
		}

		MutablePropertyValues mpvs = null;
		List<PropertyValue> original;

		// 1. 预处理：获取原始属性值列表
		if (pvs instanceof MutablePropertyValues) {
			mpvs = (MutablePropertyValues) pvs;
			// 如果属性值已经被转换过，直接设置并返回（性能优化）
			if (mpvs.isConverted()) {
				try {
					bw.setPropertyValues(mpvs);
					return;
				}
				catch (BeansException ex) {
					throw new BeanCreationException(
							mbd.getResourceDescription(), beanName, "Error setting property values", ex);
				}
			}
			original = mpvs.getPropertyValueList();
		}
		else {
			original = Arrays.asList(pvs.getPropertyValues());
		}

		// 2. 准备类型转换器和值解析器
		TypeConverter converter = getCustomTypeConverter();
		if (converter == null) {
			converter = bw;  // BeanWrapper本身也实现了TypeConverter接口
		}
		// 值解析器用于解析Bean引用、属性占位符、集合等复杂类型
		BeanDefinitionValueResolver valueResolver = new BeanDefinitionValueResolver(this, beanName, mbd, converter);

		// 3. 创建深拷贝，解析所有引用值
		List<PropertyValue> deepCopy = new ArrayList<>(original.size());
		boolean resolveNecessary = false;  // 标记是否需要重新解析

		for (PropertyValue pv : original) {
			// 3.1 如果属性值已经被转换过，直接添加到深拷贝列表
			if (pv.isConverted()) {
				deepCopy.add(pv);
			}
			else {
				String propertyName = pv.getName();
				Object originalValue = pv.getValue();

				// 3.2 处理自动装配标记（@Autowired注解的特殊标记）
				// 这个分支处理通过AutowiredAnnotationBeanPostProcessor标记的依赖
				if (originalValue == AutowiredPropertyMarker.INSTANCE) {
					Method writeMethod = bw.getPropertyDescriptor(propertyName).getWriteMethod();
					if (writeMethod == null) {
						throw new IllegalArgumentException("Autowire marker for property without write method: " + pv);
					}
					// 创建依赖描述符，用于后续的依赖解析
					originalValue = new DependencyDescriptor(new MethodParameter(writeMethod, 0), true);
				}

				// 3.3 核心：解析属性值（如果必要）
				// 这里会解析：Bean引用(runtime reference)、类型转换、集合、数组、Map等
				Object resolvedValue = valueResolver.resolveValueIfNecessary(pv, originalValue);

				// 3.4 类型转换：将解析后的值转换为目标属性的类型
				Object convertedValue = resolvedValue;
				// 检查属性是否可写且不是嵌套属性（如user.name这种点分隔的属性）
				boolean convertible = bw.isWritableProperty(propertyName) &&
						!PropertyAccessorUtils.isNestedOrIndexedProperty(propertyName);
				if (convertible) {
					// 执行类型转换，例如：String "2024-01-01" -> Date类型
					convertedValue = convertForProperty(resolvedValue, propertyName, bw, converter);
				}

				// 3.5 缓存转换后的值（性能优化）
				// 如果解析后的值与原始值相同，且可以转换，则缓存转换结果
				if (resolvedValue == originalValue) {
					if (convertible) {
						pv.setConvertedValue(convertedValue);
					}
					deepCopy.add(pv);
				}
				// 如果解析后的值不同，且原始值是静态的TypedStringValue，转换后的值不是集合或数组
				else if (convertible && originalValue instanceof TypedStringValue &&
						!((TypedStringValue) originalValue).isDynamic() &&
						!(convertedValue instanceof Collection || ObjectUtils.isArray(convertedValue))) {
					pv.setConvertedValue(convertedValue);
					deepCopy.add(pv);
				}
				else {
					// 需要重新解析的场景（如包含运行时表达式的值）
					resolveNecessary = true;
					deepCopy.add(new PropertyValue(pv, convertedValue));
				}
			}
		}

		// 4. 标记为已转换（下次可以直接使用缓存）
		if (mpvs != null && !resolveNecessary) {
			mpvs.setConverted();
		}

		// 5. 最终应用属性值到Bean实例
		// 这里会通过反射调用setter方法或直接设置字段值
		try {
			bw.setPropertyValues(new MutablePropertyValues(deepCopy));
		}
		catch (BeansException ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Error setting property values", ex);
		}
	}
	/**
	 * Convert the given value for the specified target property.
	 */
	@Nullable
	private Object convertForProperty(
			@Nullable Object value, String propertyName, BeanWrapper bw, TypeConverter converter) {

		if (converter instanceof BeanWrapperImpl) {
			return ((BeanWrapperImpl) converter).convertForProperty(value, propertyName);
		}
		else {
			PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
			MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
			return converter.convertIfNecessary(value, pd.getPropertyType(), methodParam);
		}
	}


	/**
	 * 初始化给定的 bean 实例，应用工厂回调以及初始化方法和 bean 后处理器。
	 * <p>从 {@link #createBean} 方法调用（用于传统定义的 bean），
	 * 以及从 {@link #initializeBean} 方法调用（用于已有的 bean 实例）。
	 *
	 * @param beanName factory 中的 bean 名称（用于调试目的）
	 * @param bean 可能需要初始化的新 bean 实例
	 * @param mbd 创建 bean 时使用的 bean 定义（如果给定的是已有的 bean 实例，则可以为 {@code null}）
	 * @return 初始化后的 bean 实例（可能被包装过）
	 * @see BeanNameAware
	 * @see BeanClassLoaderAware
	 * @see BeanFactoryAware
	 * @see #applyBeanPostProcessorsBeforeInitialization
	 * @see #invokeInitMethods
	 * @see #applyBeanPostProcessorsAfterInitialization
	 */
	protected Object initializeBean(String beanName, Object bean, @Nullable RootBeanDefinition mbd) {

		// ============ 步骤1：调用 Aware 方法 ============
		// 处理 BeanNameAware、BeanClassLoaderAware、BeanFactoryAware 回调
		if (System.getSecurityManager() != null) {
			// 有安全管理器时，使用特权方式执行
			AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
				invokeAwareMethods(beanName, bean);
				return null;
			}, getAccessControlContext());
		} else {
			// 普通情况：直接调用 Aware 方法
			invokeAwareMethods(beanName, bean);
		}

		// ============ 步骤2：初始化前置处理 ============
		// 调用 BeanPostProcessor 的 postProcessBeforeInitialization 方法
		Object wrappedBean = bean;
		if (mbd == null || !mbd.isSynthetic()) {
			// 如果不是合成的 Bean，则应用前置处理器
			wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
		}

		// ============ 步骤3：调用初始化方法 ============
		try {
			// 调用 @PostConstruct、InitializingBean、init-method 等
			invokeInitMethods(beanName, wrappedBean, mbd);
		} catch (Throwable ex) {
			throw new BeanCreationException(
					(mbd != null ? mbd.getResourceDescription() : null),
					beanName, "Invocation of init method failed", ex);
		}

		// ============ 步骤4：初始化后置处理 ============
		if (mbd == null || !mbd.isSynthetic()) {
			// 调用 BeanPostProcessor 的 postProcessAfterInitialization 方法
			// 这是 AOP 创建代理的关键步骤！
			wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
		}

		return wrappedBean;
	}

	private void invokeAwareMethods(String beanName, Object bean) {
		if (bean instanceof Aware) {
			if (bean instanceof BeanNameAware) {
				((BeanNameAware) bean).setBeanName(beanName);
			}
			if (bean instanceof BeanClassLoaderAware) {
				ClassLoader bcl = getBeanClassLoader();
				if (bcl != null) {
					((BeanClassLoaderAware) bean).setBeanClassLoader(bcl);
				}
			}
			if (bean instanceof BeanFactoryAware) {
				((BeanFactoryAware) bean).setBeanFactory(AbstractAutowireCapableBeanFactory.this);
			}
		}
	}

	/**
	 * 在 Bean 的所有属性都被设置之后，给予 Bean 一个反应的机会，
	 * 并让它有机会了解其所属的 BeanFactory。
	 *
	 * <p><b>调用时机：</b>
	 * 在 {@link #applyBeanPostProcessorsBeforeInitialization} 之后，
	 * {@link #applyBeanPostProcessorsAfterInitialization} 之前执行。
	 *
	 * <p><b>这是第6个扩展点的触发位置</b>，但注意：这个方法本身不是扩展点，
	 * 而是 Spring 内部调用初始化方法的地方。真正的第6个扩展点是
	 * {@link BeanPostProcessor#postProcessAfterInitialization}。
	 *
	 * <p><b>初始化方法的调用顺序：</b>
	 * <ol>
	 *   <li>{@link javax.annotation.PostConstruct} 注解的方法（通过 CommonAnnotationBeanPostProcessor）</li>
	 *   <li>{@link InitializingBean#afterPropertiesSet()} 方法（当前方法处理）</li>
	 *   <li>自定义 init-method（当前方法处理）</li>
	 * </ol>
	 *
	 * <p><b>执行流程详解：</b>
	 * <pre>
	 * initializeBean
	 *   → applyBeanPostProcessorsBeforeInitialization  ← 第5个扩展点
	 *   → invokeInitMethods（当前位置）                ← 执行初始化方法
	 *       → 1. 检查是否是 InitializingBean
	 *          → 如果是，调用 afterPropertiesSet()
	 *       → 2. 检查是否有自定义 init-method
	 *          → 如果有且与 afterPropertiesSet 不重复，调用自定义初始化方法
	 *   → applyBeanPostProcessorsAfterInitialization   ← 第6个扩展点
	 * </pre>
	 *
	 * <p><b>方法内部逻辑：</b>
	 * <ul>
	 *   <li><b>第一阶段</b>：处理 InitializingBean 接口的 afterPropertiesSet() 方法
	 *       <ul>
	 *         <li>只有实现了 InitializingBean 接口的 Bean 才会执行</li>
	 *         <li>需要检查该初始化方法是否被外部管理（避免重复执行）</li>
	 *         <li>支持 SecurityManager 环境下的特权执行</li>
	 *       </ul>
	 *   </li>
	 *   <li><b>第二阶段</b>：处理自定义 init-method
	 *       <ul>
	 *         <li>从 BeanDefinition 中获取配置的 init-method 名称</li>
	 *         <li>如果 init-method 就是 afterPropertiesSet，则跳过（避免重复调用）</li>
	 *         <li>检查该方法是否已被外部管理（如通过注解处理器）</li>
	 *         <li>通过反射调用自定义初始化方法</li>
	 *       </ul>
	 *   </li>
	 * </ul>
	 *
	 * <p><b>典型示例：</b>
	 * <pre>{@code
	 * // 方式1：实现 InitializingBean 接口
	 * @Component
	 * public class MyBean implements InitializingBean {
	 *     @Override
	 *     public void afterPropertiesSet() throws Exception {
	 *         System.out.println("1. afterPropertiesSet 执行");
	 *     }
	 * }
	 *
	 * // 方式2：配置 init-method
	 * @Component
	 * public class AnotherBean {
	 *     public void customInit() {
	 *         System.out.println("2. 自定义 init-method 执行");
	 *     }
	 * }
	 * // XML 配置：<bean id="anotherBean" class="AnotherBean" init-method="customInit"/>
	 *
	 * // 方式3：@PostConstruct（优先级最高）
	 * @Component
	 * public class ThirdBean {
	 *     @PostConstruct
	 *     public void postConstruct() {
	 *         System.out.println("0. @PostConstruct 最先执行");
	 *     }
	 * }
	 * }</pre>
	 *
	 * <p><b>注意事项：</b>
	 * <ul>
	 *   <li>@PostConstruct 的调用不在此方法中，而是在 CommonAnnotationBeanPostProcessor 的
	 *       postProcessBeforeInitialization 中执行（第5个扩展点）</li>
	 *   <li>此方法中抛出的任何异常都会导致 Bean 创建失败</li>
	 *   <li>如果 Bean 的 afterPropertiesSet() 和 init-method 配置了相同的方法名，不会重复调用</li>
	 *   <li>mbd 参数可能为 null（当传入的是已存在的 Bean 实例时）</li>
	 * </ul>
	 *
	 * @param beanName 工厂中的 bean 名称（用于调试）
	 * @param bean 需要初始化的新 bean 实例
	 * @param mbd 创建 bean 时使用的合并后的 bean 定义（如果传入的是已存在的 bean 实例，可能为 null）
	 * @throws Throwable 如果初始化方法或调用过程抛出异常
	 * @see #invokeCustomInitMethod
	 */
	protected void invokeInitMethods(String beanName, Object bean, @Nullable RootBeanDefinition mbd)
			throws Throwable {

		// ========== 第一阶段：处理 InitializingBean 接口 ==========
		boolean isInitializingBean = (bean instanceof InitializingBean);
		if (isInitializingBean && (mbd == null || !mbd.hasAnyExternallyManagedInitMethod("afterPropertiesSet"))) {
			if (logger.isTraceEnabled()) {
				logger.trace("Invoking afterPropertiesSet() on bean with name '" + beanName + "'");
			}

			// 处理 SecurityManager 环境
			if (System.getSecurityManager() != null) {
				try {
					AccessController.doPrivileged((PrivilegedExceptionAction<Object>) () -> {
						((InitializingBean) bean).afterPropertiesSet();
						return null;
					}, getAccessControlContext());
				}
				catch (PrivilegedActionException pae) {
					throw pae.getException();
				}
			}
			else {
				// 正常情况：直接调用 afterPropertiesSet 方法
				((InitializingBean) bean).afterPropertiesSet();
			}
		}

		// ========== 第二阶段：处理自定义 init-method ==========
		if (mbd != null && bean.getClass() != NullBean.class) {
			String initMethodName = mbd.getInitMethodName();
			if (StringUtils.hasLength(initMethodName) &&
					// 避免与 afterPropertiesSet 重复调用
					!(isInitializingBean && "afterPropertiesSet".equals(initMethodName)) &&
					// 检查该方法是否已经被外部管理（如通过注解）
					!mbd.hasAnyExternallyManagedInitMethod(initMethodName)) {
				// 通过反射调用自定义初始化方法
				invokeCustomInitMethod(beanName, bean, mbd);
			}
		}
	}

	/**
	 * Invoke the specified custom init method on the given bean.
	 * Called by invokeInitMethods.
	 * <p>Can be overridden in subclasses for custom resolution of init
	 * methods with arguments.
	 * @see #invokeInitMethods
	 */
	protected void invokeCustomInitMethod(String beanName, Object bean, RootBeanDefinition mbd)
			throws Throwable {

		String initMethodName = mbd.getInitMethodName();
		Assert.state(initMethodName != null, "No init method set");
		Method initMethod = (mbd.isNonPublicAccessAllowed() ?
				BeanUtils.findMethod(bean.getClass(), initMethodName) :
				ClassUtils.getMethodIfAvailable(bean.getClass(), initMethodName));

		if (initMethod == null) {
			if (mbd.isEnforceInitMethod()) {
				throw new BeanDefinitionValidationException("Could not find an init method named '" +
						initMethodName + "' on bean with name '" + beanName + "'");
			}
			else {
				if (logger.isTraceEnabled()) {
					logger.trace("No default init method named '" + initMethodName +
							"' found on bean with name '" + beanName + "'");
				}
				// Ignore non-existent default lifecycle methods.
				return;
			}
		}

		if (logger.isTraceEnabled()) {
			logger.trace("Invoking init method  '" + initMethodName + "' on bean with name '" + beanName + "'");
		}
		Method methodToInvoke = ClassUtils.getInterfaceMethodIfPossible(initMethod, bean.getClass());

		if (System.getSecurityManager() != null) {
			AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
				ReflectionUtils.makeAccessible(methodToInvoke);
				return null;
			});
			try {
				AccessController.doPrivileged((PrivilegedExceptionAction<Object>)
						() -> methodToInvoke.invoke(bean), getAccessControlContext());
			}
			catch (PrivilegedActionException pae) {
				InvocationTargetException ex = (InvocationTargetException) pae.getException();
				throw ex.getTargetException();
			}
		}
		else {
			try {
				ReflectionUtils.makeAccessible(methodToInvoke);
				methodToInvoke.invoke(bean);
			}
			catch (InvocationTargetException ex) {
				throw ex.getTargetException();
			}
		}
	}


	/**
	 * Applies the {@code postProcessAfterInitialization} callback of all
	 * registered BeanPostProcessors, giving them a chance to post-process the
	 * object obtained from FactoryBeans (for example, to auto-proxy them).
	 * @see #applyBeanPostProcessorsAfterInitialization
	 */
	@Override
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) {
		return applyBeanPostProcessorsAfterInitialization(object, beanName);
	}

	/**
	 * Overridden to clear FactoryBean instance cache as well.
	 */
	@Override
	protected void removeSingleton(String beanName) {
		synchronized (getSingletonMutex()) {
			super.removeSingleton(beanName);
			this.factoryBeanInstanceCache.remove(beanName);
		}
	}

	/**
	 * Overridden to clear FactoryBean instance cache as well.
	 */
	@Override
	protected void clearSingletonCache() {
		synchronized (getSingletonMutex()) {
			super.clearSingletonCache();
			this.factoryBeanInstanceCache.clear();
		}
	}

	/**
	 * Expose the logger to collaborating delegates.
	 * @since 5.0.7
	 */
	Log getLogger() {
		return logger;
	}


	/**
	 * Special DependencyDescriptor variant for Spring's good old autowire="byType" mode.
	 * Always optional; never considering the parameter name for choosing a primary candidate.
	 */
	@SuppressWarnings("serial")
	private static class AutowireByTypeDependencyDescriptor extends DependencyDescriptor {

		public AutowireByTypeDependencyDescriptor(MethodParameter methodParameter, boolean eager) {
			super(methodParameter, false, eager);
		}

		@Override
		public String getDependencyName() {
			return null;
		}
	}


	/**
	 * {@link MethodCallback} used to find {@link FactoryBean} type information.
	 */
	private static class FactoryBeanMethodTypeFinder implements MethodCallback {

		private final String factoryMethodName;

		private ResolvableType result = ResolvableType.NONE;

		FactoryBeanMethodTypeFinder(String factoryMethodName) {
			this.factoryMethodName = factoryMethodName;
		}

		@Override
		public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
			if (isFactoryBeanMethod(method)) {
				ResolvableType returnType = ResolvableType.forMethodReturnType(method);
				ResolvableType candidate = returnType.as(FactoryBean.class).getGeneric();
				if (this.result == ResolvableType.NONE) {
					this.result = candidate;
				}
				else {
					Class<?> resolvedResult = this.result.resolve();
					Class<?> commonAncestor = ClassUtils.determineCommonAncestor(candidate.resolve(), resolvedResult);
					if (!ObjectUtils.nullSafeEquals(resolvedResult, commonAncestor)) {
						this.result = ResolvableType.forClass(commonAncestor);
					}
				}
			}
		}

		private boolean isFactoryBeanMethod(Method method) {
			return (method.getName().equals(this.factoryMethodName) &&
					FactoryBean.class.isAssignableFrom(method.getReturnType()));
		}

		ResolvableType getResult() {
			Class<?> resolved = this.result.resolve();
			boolean foundResult = resolved != null && resolved != Object.class;
			return (foundResult ? this.result : ResolvableType.NONE);
		}
	}

}
