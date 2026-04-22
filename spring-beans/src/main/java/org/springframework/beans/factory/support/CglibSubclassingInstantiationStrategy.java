/*
 * Copyright 2002-2021 the original author or authors.
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanInstantiationException;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.cglib.core.ClassLoaderAwareGeneratorStrategy;
import org.springframework.cglib.core.SpringNamingPolicy;
import org.springframework.cglib.proxy.Callback;
import org.springframework.cglib.proxy.CallbackFilter;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.Factory;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;
import org.springframework.cglib.proxy.NoOp;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Default object instantiation strategy for use in BeanFactories.
 *
 * <p>Uses CGLIB to generate subclasses dynamically if methods need to be
 * overridden by the container to implement <em>Method Injection</em>.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 1.1
 */
public class CglibSubclassingInstantiationStrategy extends SimpleInstantiationStrategy {

	/**
	 * Index in the CGLIB callback array for passthrough behavior,
	 * in which case the subclass won't override the original class.
	 */
	private static final int PASSTHROUGH = 0;

	/**
	 * Index in the CGLIB callback array for a method that should
	 * be overridden to provide <em>method lookup</em>.
	 */
	private static final int LOOKUP_OVERRIDE = 1;

	/**
	 * Index in the CGLIB callback array for a method that should
	 * be overridden using generic <em>method replacer</em> functionality.
	 */
	private static final int METHOD_REPLACER = 2;


	@Override
	protected Object instantiateWithMethodInjection(RootBeanDefinition bd, @Nullable String beanName, BeanFactory owner) {
		return instantiateWithMethodInjection(bd, beanName, owner, null);
	}

	@Override
	protected Object instantiateWithMethodInjection(RootBeanDefinition bd, @Nullable String beanName, BeanFactory owner,
			@Nullable Constructor<?> ctor, Object... args) {

		// Must generate CGLIB subclass...
		return new CglibSubclassCreator(bd, owner).instantiate(ctor, args);
	}


	/**
	 * 一个内部类，由于历史原因而创建，目的是在 Spring 3.2 之前的版本中避免外部 CGLIB 依赖。
	 *
	 * 这个类负责使用 CGLIB 动态创建子类，以支持 lookup-method 和 replaced-method 功能。
	 */
	private static class CglibSubclassCreator {

		// 定义 CGLIB 回调类型数组（三个回调，按索引对应）
		// 索引 0: NoOp - 不做任何处理的方法
		// 索引 1: LookupOverrideMethodInterceptor - 处理 @Lookup 方法
		// 索引 2: ReplaceOverrideMethodInterceptor - 处理 replaced-method
		private static final Class<?>[] CALLBACK_TYPES = new Class<?>[]
				{NoOp.class, LookupOverrideMethodInterceptor.class, ReplaceOverrideMethodInterceptor.class};

		private final RootBeanDefinition beanDefinition;  // Bean 定义
		private final BeanFactory owner;                   // 所属的 BeanFactory

		CglibSubclassCreator(RootBeanDefinition beanDefinition, BeanFactory owner) {
			this.beanDefinition = beanDefinition;
			this.owner = owner;
		}

		/**
		 * 创建动态生成的子类的新实例，该子类实现了所需的方法查找功能。
		 *
		 * @param ctor 要使用的构造函数。如果为 {@code null}，则使用无参构造函数
		 * @param args 用于构造函数的参数，如果 {@code ctor} 参数为 {@code null} 则忽略
		 * @return 动态生成的子类的新实例
		 */
		public Object instantiate(@Nullable Constructor<?> ctor, Object... args) {
			// ============ 步骤1：创建 CGLIB 增强子类 ============
			Class<?> subclass = createEnhancedSubclass(this.beanDefinition);

			Object instance;

			// ============ 步骤2：实例化子类 ============
			if (ctor == null) {
				// 使用无参构造函数实例化
				instance = BeanUtils.instantiateClass(subclass);
			} else {
				// 使用指定的带参构造函数实例化
				try {
					// 获取子类中对应的构造函数（参数类型相同）
					Constructor<?> enhancedSubclassConstructor = subclass.getConstructor(ctor.getParameterTypes());
					// 调用构造函数创建实例
					instance = enhancedSubclassConstructor.newInstance(args);
				} catch (Exception ex) {
					throw new BeanInstantiationException(this.beanDefinition.getBeanClass(),
							"Failed to invoke constructor for CGLIB enhanced subclass [" + subclass.getName() + "]", ex);
				}
			}

			// ============ 步骤3：设置 CGLIB 回调（方法拦截器）============
			// SPR-10785: 直接在实例上设置回调，而不是在增强类中设置（通过 Enhancer），以避免内存泄漏
			Factory factory = (Factory) instance;
			factory.setCallbacks(new Callback[] {
					NoOp.INSTANCE,                                              // 索引0：普通方法，不做拦截
					new LookupOverrideMethodInterceptor(this.beanDefinition, this.owner),  // 索引1：处理 @Lookup
					new ReplaceOverrideMethodInterceptor(this.beanDefinition, this.owner)   // 索引2：处理 replaced-method
			});

			return instance;
		}

		/**
		 * 为提供的 Bean 定义创建增强子类，使用 CGLIB。
		 */
		private Class<?> createEnhancedSubclass(RootBeanDefinition beanDefinition) {
			// 创建 CGLIB 增强器
			Enhancer enhancer = new Enhancer();

			// 设置父类（目标类）
			enhancer.setSuperclass(beanDefinition.getBeanClass());

			// 设置命名策略（Spring 自定义的命名策略）
			enhancer.setNamingPolicy(SpringNamingPolicy.INSTANCE);

			// 设置类加载器
			if (this.owner instanceof ConfigurableBeanFactory) {
				ClassLoader cl = ((ConfigurableBeanFactory) this.owner).getBeanClassLoader();
				enhancer.setStrategy(new ClassLoaderAwareGeneratorStrategy(cl));
			}

			// 设置回调过滤器（决定哪个方法使用哪个回调索引）
			enhancer.setCallbackFilter(new MethodOverrideCallbackFilter(beanDefinition));

			// 设置回调类型数组
			enhancer.setCallbackTypes(CALLBACK_TYPES);

			// 生成并返回增强的子类 Class 对象
			return enhancer.createClass();
		}
	}


	/**
	 * Class providing hashCode and equals methods required by CGLIB to
	 * ensure that CGLIB doesn't generate a distinct class per bean.
	 * Identity is based on class and bean definition.
	 */
	private static class CglibIdentitySupport {

		private final RootBeanDefinition beanDefinition;

		public CglibIdentitySupport(RootBeanDefinition beanDefinition) {
			this.beanDefinition = beanDefinition;
		}

		public RootBeanDefinition getBeanDefinition() {
			return this.beanDefinition;
		}

		@Override
		public boolean equals(@Nullable Object other) {
			return (other != null && getClass() == other.getClass() &&
					this.beanDefinition.equals(((CglibIdentitySupport) other).beanDefinition));
		}

		@Override
		public int hashCode() {
			return this.beanDefinition.hashCode();
		}
	}


	/**
	 * CGLIB callback for filtering method interception behavior.
	 */
	private static class MethodOverrideCallbackFilter extends CglibIdentitySupport implements CallbackFilter {

		private static final Log logger = LogFactory.getLog(MethodOverrideCallbackFilter.class);

		public MethodOverrideCallbackFilter(RootBeanDefinition beanDefinition) {
			super(beanDefinition);
		}

		@Override
		public int accept(Method method) {
			MethodOverride methodOverride = getBeanDefinition().getMethodOverrides().getOverride(method);
			if (logger.isTraceEnabled()) {
				logger.trace("MethodOverride for " + method + ": " + methodOverride);
			}
			if (methodOverride == null) {
				return PASSTHROUGH;
			}
			else if (methodOverride instanceof LookupOverride) {
				return LOOKUP_OVERRIDE;
			}
			else if (methodOverride instanceof ReplaceOverride) {
				return METHOD_REPLACER;
			}
			throw new UnsupportedOperationException("Unexpected MethodOverride subclass: " +
					methodOverride.getClass().getName());
		}
	}


	/**
	 * CGLIB MethodInterceptor to override methods, replacing them with an
	 * implementation that returns a bean looked up in the container.
	 */
	private static class LookupOverrideMethodInterceptor extends CglibIdentitySupport implements MethodInterceptor {

		private final BeanFactory owner;

		public LookupOverrideMethodInterceptor(RootBeanDefinition beanDefinition, BeanFactory owner) {
			super(beanDefinition);
			this.owner = owner;
		}

		@Override
		public Object intercept(Object obj, Method method, Object[] args, MethodProxy mp) throws Throwable {
			// Cast is safe, as CallbackFilter filters are used selectively.
			LookupOverride lo = (LookupOverride) getBeanDefinition().getMethodOverrides().getOverride(method);
			Assert.state(lo != null, "LookupOverride not found");
			Object[] argsToUse = (args.length > 0 ? args : null);  // if no-arg, don't insist on args at all
			if (StringUtils.hasText(lo.getBeanName())) {
				Object bean = (argsToUse != null ? this.owner.getBean(lo.getBeanName(), argsToUse) :
						this.owner.getBean(lo.getBeanName()));
				// Detect package-protected NullBean instance through equals(null) check
				return (bean.equals(null) ? null : bean);
			}
			else {
				// Find target bean matching the (potentially generic) method return type
				ResolvableType genericReturnType = ResolvableType.forMethodReturnType(method);
				return (argsToUse != null ? this.owner.getBeanProvider(genericReturnType).getObject(argsToUse) :
						this.owner.getBeanProvider(genericReturnType).getObject());
			}
		}
	}


	/**
	 * CGLIB MethodInterceptor to override methods, replacing them with a call
	 * to a generic MethodReplacer.
	 */
	private static class ReplaceOverrideMethodInterceptor extends CglibIdentitySupport implements MethodInterceptor {

		private final BeanFactory owner;

		public ReplaceOverrideMethodInterceptor(RootBeanDefinition beanDefinition, BeanFactory owner) {
			super(beanDefinition);
			this.owner = owner;
		}

		@Override
		public Object intercept(Object obj, Method method, Object[] args, MethodProxy mp) throws Throwable {
			ReplaceOverride ro = (ReplaceOverride) getBeanDefinition().getMethodOverrides().getOverride(method);
			Assert.state(ro != null, "ReplaceOverride not found");
			// TODO could cache if a singleton for minor performance optimization
			MethodReplacer mr = this.owner.getBean(ro.getMethodReplacerBeanName(), MethodReplacer.class);
			return mr.reimplement(obj, method, args);
		}
	}

}
