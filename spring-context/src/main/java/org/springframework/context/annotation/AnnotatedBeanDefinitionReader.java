/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.context.annotation;

import java.lang.annotation.Annotation;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionCustomizer;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.core.env.Environment;
import org.springframework.core.env.EnvironmentCapable;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Convenient adapter for programmatic registration of bean classes.
 *
 * <p>This is an alternative to {@link ClassPathBeanDefinitionScanner}, applying
 * the same resolution of annotations but for explicitly registered classes only.
 *
 * @author Juergen Hoeller
 * @author Chris Beams
 * @author Sam Brannen
 * @author Phillip Webb
 * @since 3.0
 * @see AnnotationConfigApplicationContext#register
 */
public class AnnotatedBeanDefinitionReader {

	private final BeanDefinitionRegistry registry;

	private BeanNameGenerator beanNameGenerator = AnnotationBeanNameGenerator.INSTANCE;

	private ScopeMetadataResolver scopeMetadataResolver = new AnnotationScopeMetadataResolver();

	private ConditionEvaluator conditionEvaluator;


	/**
	 * 为给定的注册表创建一个新的 {@code AnnotatedBeanDefinitionReader}（注解式 Bean 定义读取器）。
	 * <p>如果该注册表是 {@link EnvironmentCapable} 的实现类（例如是 {@code ApplicationContext}），
	 * 则会继承其内部的 {@link Environment} 环境对象；否则，将创建一个新的
	 * {@link StandardEnvironment} 标准环境对象并使用它。
	 *
	 * @param registry 用于加载 Bean 定义的 {@code BeanFactory}，以 {@code BeanDefinitionRegistry} 的形式传入
	 * @see #AnnotatedBeanDefinitionReader(BeanDefinitionRegistry, Environment)
	 * @see #setEnvironment(Environment)
	 */
	public AnnotatedBeanDefinitionReader(BeanDefinitionRegistry registry) {
		// 调用另一个构造器，传入注册表以及通过 getOrCreateEnvironment 方法获取或创建的环境对象
		this(registry, getOrCreateEnvironment(registry));
	}

	/**
	 * 使用给定的 {@link Environment} 环境对象，为指定的注册表创建一个新的
	 * {@code AnnotatedBeanDefinitionReader}（注解式 Bean 定义读取器）。
	 *
	 * @param registry   用于加载 Bean 定义的 {@code BeanFactory}，以 {@code BeanDefinitionRegistry} 的形式传入
	 * @param environment 在评估 Bean 定义中的配置条件（如 {@code @Profile}）时所使用的环境对象
	 * @since 3.1
	 */
	public AnnotatedBeanDefinitionReader(BeanDefinitionRegistry registry, Environment environment) {
		// 断言：注册表参数不能为空
		Assert.notNull(registry, "BeanDefinitionRegistry must not be null");
		// 断言：环境参数不能为空
		Assert.notNull(environment, "Environment must not be null");

		// 保存注册表引用，后续用于注册 Bean 定义
		this.registry = registry;

		// 创建条件评估器，用于处理 @Conditional 等条件注解，判断 Bean 是否应被注册
		this.conditionEvaluator = new ConditionEvaluator(registry, environment, null);

		// 向当前的注册表中注册处理注解配置所需的内置处理器
		// 例如：ConfigurationClassPostProcessor、AutowiredAnnotationBeanPostProcessor 等
		// 这些处理器支持 @Configuration、@Autowired、@Required 等注解的功能
		AnnotationConfigUtils.registerAnnotationConfigProcessors(this.registry);
	}


	/**
	 * Get the BeanDefinitionRegistry that this reader operates on.
	 */
	public final BeanDefinitionRegistry getRegistry() {
		return this.registry;
	}

	/**
	 * Set the {@code Environment} to use when evaluating whether
	 * {@link Conditional @Conditional}-annotated component classes should be registered.
	 * <p>The default is a {@link StandardEnvironment}.
	 * @see #registerBean(Class, String, Class...)
	 */
	public void setEnvironment(Environment environment) {
		this.conditionEvaluator = new ConditionEvaluator(this.registry, environment, null);
	}

	/**
	 * Set the {@code BeanNameGenerator} to use for detected bean classes.
	 * <p>The default is a {@link AnnotationBeanNameGenerator}.
	 */
	public void setBeanNameGenerator(@Nullable BeanNameGenerator beanNameGenerator) {
		this.beanNameGenerator =
				(beanNameGenerator != null ? beanNameGenerator : AnnotationBeanNameGenerator.INSTANCE);
	}

	/**
	 * Set the {@code ScopeMetadataResolver} to use for registered component classes.
	 * <p>The default is an {@link AnnotationScopeMetadataResolver}.
	 */
	public void setScopeMetadataResolver(@Nullable ScopeMetadataResolver scopeMetadataResolver) {
		this.scopeMetadataResolver =
				(scopeMetadataResolver != null ? scopeMetadataResolver : new AnnotationScopeMetadataResolver());
	}


	/**
	 * 注册一个或多个需要被处理的组件类。
	 * <p>对 {@code register} 方法的调用是幂等的；重复添加同一个组件类不会产生额外效果。
	 *
	 * @param componentClasses 一个或多个组件类，例如 {@link Configuration @Configuration} 注解的配置类
	 */
	public void register(Class<?>... componentClasses) {
		// 遍历所有传入的组件类，逐个进行注册
		for (Class<?> componentClass : componentClasses) {
			// 调用 registerBean 方法，完成实际的注册逻辑
			// registerBean 方法会：
			// 1. 根据 componentClass 创建一个 AnnotatedGenericBeanDefinition
			// 2. 解析类上的注解（如 @Scope、@Lazy、@Primary 等）
			// 3. 将解析后的 BeanDefinition 注册到 BeanFactory 中
			registerBean(componentClass);
		}
	}

	/**
	 * 根据给定的 bean 类型注册一个 bean，其元数据从类声明的注解中派生而来。
	 * 这是使用注解驱动的 Bean 注册的核心方法之一。
	 *
	 * @param beanClass 待注册 bean 的类型（例如 AppConfig.class、UserService.class）
	 *
	 * 实际注册逻辑会：
	 * 1. 创建 AnnotatedGenericBeanDefinition（封装 beanClass 的元数据）
	 * 2. 解析类上的条件注解 @Conditional，判断是否应该注册
	 * 3. 解析作用域注解 @Scope，决定是单例还是原型等
	 * 4. 解析其他注解：@Lazy、@Primary、@DependsOn、@Role、@Description
	 * 5. 将构建好的 BeanDefinition 注册到 BeanFactory 中
	 */
	public void registerBean(Class<?> beanClass) {
		// 调用核心的 doRegisterBean 方法，传入 beanClass 和一系列可选的参数（此处均为 null）
		// 参数列表说明：
		// - beanClass  : 要注册的 bean 的类型
		// - beanName    : null 表示自动生成 bean 名称
		// - customizers : null 表示不需要额外的 BeanDefinition 定制器
		// - supplier    : null 表示没有自定义的实例供应器（通常用于工厂方法场景）
		// - qualifiers  : null 表示不需要额外的 @Qualifier 限定符
		doRegisterBean(beanClass, null, null, null, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations.
	 * @param beanClass the class of the bean
	 * @param name an explicit name for the bean
	 * (or {@code null} for generating a default bean name)
	 * @since 5.2
	 */
	public void registerBean(Class<?> beanClass, @Nullable String name) {
		doRegisterBean(beanClass, name, null, null, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations.
	 * @param beanClass the class of the bean
	 * @param qualifiers specific qualifier annotations to consider,
	 * in addition to qualifiers at the bean class level
	 */
	@SuppressWarnings("unchecked")
	public void registerBean(Class<?> beanClass, Class<? extends Annotation>... qualifiers) {
		doRegisterBean(beanClass, null, qualifiers, null, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations.
	 * @param beanClass the class of the bean
	 * @param name an explicit name for the bean
	 * (or {@code null} for generating a default bean name)
	 * @param qualifiers specific qualifier annotations to consider,
	 * in addition to qualifiers at the bean class level
	 */
	@SuppressWarnings("unchecked")
	public void registerBean(Class<?> beanClass, @Nullable String name,
			Class<? extends Annotation>... qualifiers) {

		doRegisterBean(beanClass, name, qualifiers, null, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations, using the given supplier for obtaining a new
	 * instance (possibly declared as a lambda expression or method reference).
	 * @param beanClass the class of the bean
	 * @param supplier a callback for creating an instance of the bean
	 * (may be {@code null})
	 * @since 5.0
	 */
	public <T> void registerBean(Class<T> beanClass, @Nullable Supplier<T> supplier) {
		doRegisterBean(beanClass, null, null, supplier, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations, using the given supplier for obtaining a new
	 * instance (possibly declared as a lambda expression or method reference).
	 * @param beanClass the class of the bean
	 * @param name an explicit name for the bean
	 * (or {@code null} for generating a default bean name)
	 * @param supplier a callback for creating an instance of the bean
	 * (may be {@code null})
	 * @since 5.0
	 */
	public <T> void registerBean(Class<T> beanClass, @Nullable String name, @Nullable Supplier<T> supplier) {
		doRegisterBean(beanClass, name, null, supplier, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations.
	 * @param beanClass the class of the bean
	 * @param name an explicit name for the bean
	 * (or {@code null} for generating a default bean name)
	 * @param supplier a callback for creating an instance of the bean
	 * (may be {@code null})
	 * @param customizers one or more callbacks for customizing the factory's
	 * {@link BeanDefinition}, e.g. setting a lazy-init or primary flag
	 * @since 5.2
	 */
	public <T> void registerBean(Class<T> beanClass, @Nullable String name, @Nullable Supplier<T> supplier,
			BeanDefinitionCustomizer... customizers) {

		doRegisterBean(beanClass, name, null, supplier, customizers);
	}

	/**
	 * 根据给定的 Bean 类注册一个 Bean，其元数据从类声明的注解中派生而来。
	 * 这是注解驱动 Bean 注册的核心私有方法，提供了完整的定制能力。
	 *
	 * @param beanClass   待注册 Bean 的类型（例如 AppConfig.class、UserService.class）
	 * @param name        Bean 的显式名称（为 null 时将由 beanNameGenerator 自动生成）
	 * @param qualifiers  特定的限定符注解（例如 @Qualifier("xxx")），会与类级别的限定符合并
	 * @param supplier    创建 Bean 实例的回调函数（工厂模式），可以为 null
	 * @param customizers 一个或多个用于定制工厂中 {@link BeanDefinition} 的回调，
	 *                    例如设置懒加载（lazy-init）或首选（primary）标志
	 * @since 5.0
	 */
	private <T> void doRegisterBean(Class<T> beanClass, @Nullable String name,
									@Nullable Class<? extends Annotation>[] qualifiers, @Nullable Supplier<T> supplier,
									@Nullable BeanDefinitionCustomizer[] customizers) {

		// ========== 步骤 1：创建 BeanDefinition 并应用条件注解检查 ==========
		// 创建基于注解的通用 BeanDefinition 对象，封装 beanClass 的元数据
		AnnotatedGenericBeanDefinition abd = new AnnotatedGenericBeanDefinition(beanClass);

		// 评估 @Conditional 条件注解，判断是否应该跳过此 Bean 的注册
		// 如果条件不满足，直接返回，不进行后续注册
		if (this.conditionEvaluator.shouldSkip(abd.getMetadata())) {
			return;
		}

		// ========== 步骤 2：设置实例供应器并解析作用域 ==========
		// 设置实例供应器（如果提供了 Supplier 工厂）
		abd.setInstanceSupplier(supplier);

		// 解析作用域注解（@Scope），获取作用域名称（如 singleton、prototype）和代理模式
		ScopeMetadata scopeMetadata = this.scopeMetadataResolver.resolveScopeMetadata(abd);
		// 设置作用域
		abd.setScope(scopeMetadata.getScopeName());

		// 生成或使用指定的 Bean 名称
		String beanName = (name != null ? name : this.beanNameGenerator.generateBeanName(abd, this.registry));

		// ========== 步骤 3：处理通用注解和限定符 ==========
		// 处理 Bean 定义中的通用注解：@Lazy、@Primary、@DependsOn、@Role、@Description
		AnnotationConfigUtils.processCommonDefinitionAnnotations(abd);

		// 处理额外的限定符注解
		if (qualifiers != null) {
			for (Class<? extends Annotation> qualifier : qualifiers) {
				if (Primary.class == qualifier) {
					// @Primary：设置为首选 Bean
					abd.setPrimary(true);
				}
				else if (Lazy.class == qualifier) {
					// @Lazy：设置为懒加载
					abd.setLazyInit(true);
				}
				else {
					// 其他限定符注解（如自定义的 @Qualifier）
					abd.addQualifier(new AutowireCandidateQualifier(qualifier));
				}
			}
		}

		// ========== 步骤 4：应用自定义定制器 ==========
		// 允许调用方通过 BeanDefinitionCustomizer 进一步修改 BeanDefinition
		if (customizers != null) {
			for (BeanDefinitionCustomizer customizer : customizers) {
				customizer.customize(abd);
			}
		}

		// ========== 步骤 5：创建 BeanDefinitionHolder 并处理作用域代理 ==========
		// 使用 BeanDefinition 和 beanName 创建持有者对象
		BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(abd, beanName);

		// 根据作用域元数据判断是否需要创建作用域代理（如 scoped proxy）
		// 例如：@Scope 中 proxyMode = ScopedProxyMode.TARGET_CLASS 时，会创建代理
		definitionHolder = AnnotationConfigUtils.applyScopedProxyMode(scopeMetadata, definitionHolder, this.registry);

		// ========== 步骤 6：注册到容器 ==========
		// 将最终的 BeanDefinitionHolder 注册到 BeanDefinitionRegistry
		BeanDefinitionReaderUtils.registerBeanDefinition(definitionHolder, this.registry);
	}


	/**
	 * Get the Environment from the given registry if possible, otherwise return a new
	 * StandardEnvironment.
	 */
	private static Environment getOrCreateEnvironment(BeanDefinitionRegistry registry) {
		Assert.notNull(registry, "BeanDefinitionRegistry must not be null");
		if (registry instanceof EnvironmentCapable) {
			return ((EnvironmentCapable) registry).getEnvironment();
		}
		return new StandardEnvironment();
	}

}
