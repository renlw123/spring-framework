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

package org.springframework.context.annotation;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.event.DefaultEventListenerFactory;
import org.springframework.context.event.EventListenerMethodProcessor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

/**
 * Utility class that allows for convenient registration of common
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} and
 * {@link org.springframework.beans.factory.config.BeanFactoryPostProcessor}
 * definitions for annotation-based configuration. Also registers a common
 * {@link org.springframework.beans.factory.support.AutowireCandidateResolver}.
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @author Chris Beams
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @since 2.5
 * @see ContextAnnotationAutowireCandidateResolver
 * @see ConfigurationClassPostProcessor
 * @see CommonAnnotationBeanPostProcessor
 * @see org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor
 * @see org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor
 */
public abstract class AnnotationConfigUtils {

	/**
	 * The bean name of the internally managed Configuration annotation processor.
	 */
	public static final String CONFIGURATION_ANNOTATION_PROCESSOR_BEAN_NAME =
			"org.springframework.context.annotation.internalConfigurationAnnotationProcessor";

	/**
	 * The bean name of the internally managed BeanNameGenerator for use when processing
	 * {@link Configuration} classes. Set by {@link AnnotationConfigApplicationContext}
	 * and {@code AnnotationConfigWebApplicationContext} during bootstrap in order to make
	 * any custom name generation strategy available to the underlying
	 * {@link ConfigurationClassPostProcessor}.
	 * @since 3.1.1
	 */
	public static final String CONFIGURATION_BEAN_NAME_GENERATOR =
			"org.springframework.context.annotation.internalConfigurationBeanNameGenerator";

	/**
	 * The bean name of the internally managed Autowired annotation processor.
	 */
	public static final String AUTOWIRED_ANNOTATION_PROCESSOR_BEAN_NAME =
			"org.springframework.context.annotation.internalAutowiredAnnotationProcessor";

	/**
	 * The bean name of the internally managed Required annotation processor.
	 * @deprecated as of 5.1, since no Required processor is registered by default anymore
	 */
	@Deprecated
	public static final String REQUIRED_ANNOTATION_PROCESSOR_BEAN_NAME =
			"org.springframework.context.annotation.internalRequiredAnnotationProcessor";

	/**
	 * The bean name of the internally managed JSR-250 annotation processor.
	 */
	public static final String COMMON_ANNOTATION_PROCESSOR_BEAN_NAME =
			"org.springframework.context.annotation.internalCommonAnnotationProcessor";

	/**
	 * The bean name of the internally managed JPA annotation processor.
	 */
	public static final String PERSISTENCE_ANNOTATION_PROCESSOR_BEAN_NAME =
			"org.springframework.context.annotation.internalPersistenceAnnotationProcessor";

	private static final String PERSISTENCE_ANNOTATION_PROCESSOR_CLASS_NAME =
			"org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor";

	/**
	 * The bean name of the internally managed @EventListener annotation processor.
	 */
	public static final String EVENT_LISTENER_PROCESSOR_BEAN_NAME =
			"org.springframework.context.event.internalEventListenerProcessor";

	/**
	 * The bean name of the internally managed EventListenerFactory.
	 */
	public static final String EVENT_LISTENER_FACTORY_BEAN_NAME =
			"org.springframework.context.event.internalEventListenerFactory";

	private static final boolean jsr250Present;

	private static final boolean jpaPresent;

	static {
		ClassLoader classLoader = AnnotationConfigUtils.class.getClassLoader();
		jsr250Present = ClassUtils.isPresent("javax.annotation.Resource", classLoader);
		jpaPresent = ClassUtils.isPresent("javax.persistence.EntityManagerFactory", classLoader) &&
				ClassUtils.isPresent(PERSISTENCE_ANNOTATION_PROCESSOR_CLASS_NAME, classLoader);
	}


	/**
	 * 在给定的注册表中注册所有相关的注解后置处理器。
	 * <p>此方法会注册处理 Spring 注解配置核心功能所需的内置 Bean 后置处理器和 Bean 工厂后置处理器，
	 * 例如 {@code ConfigurationClassPostProcessor}、{@code AutowiredAnnotationBeanPostProcessor} 等。
	 *
	 * @param registry 要操作的目标注册表（通常为 {@link BeanDefinitionRegistry} 的实现类）
	 */
	public static void registerAnnotationConfigProcessors(BeanDefinitionRegistry registry) {
		// 调用重载方法，传入 registry 和 null 作为容器内部使用的 BeanFactory
		// 第二个参数为容器内部的 BeanFactory，传 null 表示将尝试从 registry 中获取或后续创建
		registerAnnotationConfigProcessors(registry, null);
	}

	/**
	 * 在给定的注册表中注册所有相关的注解后置处理器。
	 * <p>此方法会注册处理 Spring 注解配置核心功能所需的内置后置处理器，包括：
	 * <ul>
	 *   <li>{@link ConfigurationClassPostProcessor} - 处理 {@code @Configuration} 配置类</li>
	 *   <li>{@link AutowiredAnnotationBeanPostProcessor} - 处理 {@code @Autowired} 和 {@code @Value}</li>
	 *   <li>{@link CommonAnnotationBeanPostProcessor} - 处理 JSR-250 注解（如 {@code @Resource}、{@code @PostConstruct}）</li>
	 *   <li>{@link PersistenceAnnotationBeanPostProcessor} - 处理 JPA 相关注解</li>
	 *   <li>{@link EventListenerMethodProcessor} - 处理 {@code @EventListener} 注解</li>
	 *   <li>{@link DefaultEventListenerFactory} - 默认事件监听器工厂</li>
	 * </ul>
	 *
	 * @param registry 要操作的目标注册表（通常为 {@link BeanDefinitionRegistry} 的实现类）
	 * @param source   触发此次注册的配置源元素（已提取），可能为 {@code null}
	 * @return 包含所有通过本次调用实际注册的 Bean 定义的 {@code Set<BeanDefinitionHolder>} 集合
	 */
	public static Set<BeanDefinitionHolder> registerAnnotationConfigProcessors(
			BeanDefinitionRegistry registry, @Nullable Object source) {

		// 1. 尝试将 registry 解包为 DefaultListableBeanFactory（如果可能）
		DefaultListableBeanFactory beanFactory = unwrapDefaultListableBeanFactory(registry);
		if (beanFactory != null) {
			// 1.1 设置依赖比较器：支持 @Order 和 @Priority 注解
			if (!(beanFactory.getDependencyComparator() instanceof AnnotationAwareOrderComparator)) {
				beanFactory.setDependencyComparator(AnnotationAwareOrderComparator.INSTANCE);
			}
			// 1.2 设置自动注入候选解析器：支持 @Qualifier 和 @Value 注解
			if (!(beanFactory.getAutowireCandidateResolver() instanceof ContextAnnotationAutowireCandidateResolver)) {
				beanFactory.setAutowireCandidateResolver(new ContextAnnotationAutowireCandidateResolver());
			}
		}

		// 2. 创建集合并存放注册的 Bean 定义持有者（初始容量 8）
		Set<BeanDefinitionHolder> beanDefs = new LinkedHashSet<>(8);

		// 3. 注册 ConfigurationClassPostProcessor（处理 @Configuration 配置类）
		//    这是最重要的后置处理器之一，负责解析 @Configuration、@Import、@ComponentScan 等注解
		if (!registry.containsBeanDefinition(CONFIGURATION_ANNOTATION_PROCESSOR_BEAN_NAME)) {
			RootBeanDefinition def = new RootBeanDefinition(ConfigurationClassPostProcessor.class);
			def.setSource(source);
			beanDefs.add(registerPostProcessor(registry, def, CONFIGURATION_ANNOTATION_PROCESSOR_BEAN_NAME));
		}

		// 4. 注册 AutowiredAnnotationBeanPostProcessor（处理 @Autowired 和 @Value 注解）
		if (!registry.containsBeanDefinition(AUTOWIRED_ANNOTATION_PROCESSOR_BEAN_NAME)) {
			RootBeanDefinition def = new RootBeanDefinition(AutowiredAnnotationBeanPostProcessor.class);
			def.setSource(source);
			beanDefs.add(registerPostProcessor(registry, def, AUTOWIRED_ANNOTATION_PROCESSOR_BEAN_NAME));
		}

		// 5. 注册 CommonAnnotationBeanPostProcessor（处理 JSR-250 注解：@Resource、@PostConstruct、@PreDestroy）
		//    检查 JSR-250 支持（jsr250Present 标识是否在 classpath 中检测到相关类）
		if (jsr250Present && !registry.containsBeanDefinition(COMMON_ANNOTATION_PROCESSOR_BEAN_NAME)) {
			RootBeanDefinition def = new RootBeanDefinition(CommonAnnotationBeanPostProcessor.class);
			def.setSource(source);
			beanDefs.add(registerPostProcessor(registry, def, COMMON_ANNOTATION_PROCESSOR_BEAN_NAME));
		}

		// 6. 注册 PersistenceAnnotationBeanPostProcessor（处理 JPA 相关注解：@PersistenceContext、@PersistenceUnit）
		//    检查 JPA 支持（jpaPresent 标识是否在 classpath 中检测到相关类）
		if (jpaPresent && !registry.containsBeanDefinition(PERSISTENCE_ANNOTATION_PROCESSOR_BEAN_NAME)) {
			RootBeanDefinition def = new RootBeanDefinition();
			try {
				// 动态加载 JPA 后置处理器类（可选依赖，避免强制引入 JPA）
				def.setBeanClass(ClassUtils.forName(PERSISTENCE_ANNOTATION_PROCESSOR_CLASS_NAME,
						AnnotationConfigUtils.class.getClassLoader()));
			}
			catch (ClassNotFoundException ex) {
				throw new IllegalStateException(
						"Cannot load optional framework class: " + PERSISTENCE_ANNOTATION_PROCESSOR_CLASS_NAME, ex);
			}
			def.setSource(source);
			beanDefs.add(registerPostProcessor(registry, def, PERSISTENCE_ANNOTATION_PROCESSOR_BEAN_NAME));
		}

		// 7. 注册 EventListenerMethodProcessor（处理 @EventListener 注解）
		if (!registry.containsBeanDefinition(EVENT_LISTENER_PROCESSOR_BEAN_NAME)) {
			RootBeanDefinition def = new RootBeanDefinition(EventListenerMethodProcessor.class);
			def.setSource(source);
			beanDefs.add(registerPostProcessor(registry, def, EVENT_LISTENER_PROCESSOR_BEAN_NAME));
		}

		// 8. 注册 DefaultEventListenerFactory（默认事件监听器工厂，配合 @EventListener 使用）
		if (!registry.containsBeanDefinition(EVENT_LISTENER_FACTORY_BEAN_NAME)) {
			RootBeanDefinition def = new RootBeanDefinition(DefaultEventListenerFactory.class);
			def.setSource(source);
			beanDefs.add(registerPostProcessor(registry, def, EVENT_LISTENER_FACTORY_BEAN_NAME));
		}

		// 9. 返回所有已注册的 BeanDefinitionHolder 集合
		return beanDefs;
	}

	private static BeanDefinitionHolder registerPostProcessor(
			BeanDefinitionRegistry registry, RootBeanDefinition definition, String beanName) {

		definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
		registry.registerBeanDefinition(beanName, definition);
		return new BeanDefinitionHolder(definition, beanName);
	}

	@Nullable
	private static DefaultListableBeanFactory unwrapDefaultListableBeanFactory(BeanDefinitionRegistry registry) {
		if (registry instanceof DefaultListableBeanFactory) {
			return (DefaultListableBeanFactory) registry;
		}
		else if (registry instanceof GenericApplicationContext) {
			return ((GenericApplicationContext) registry).getDefaultListableBeanFactory();
		}
		else {
			return null;
		}
	}

	/**
	 * 处理 Bean 定义中的通用注解，将注解属性应用到 AnnotatedBeanDefinition 中。
	 * 这是 Spring 注解驱动的核心工具方法，处理所有 Bean 都通用的基础注解。
	 *
	 * 处理的注解包括：
	 * - @Lazy：是否懒加载
	 * - @Primary：是否为首选 Bean
	 * - @DependsOn：依赖的其他 Bean 名称
	 * - @Role：Bean 的角色（应用组件/基础设施组件等）
	 * - @Description：Bean 的描述信息
	 *
	 * @param abd AnnotatedBeanDefinition 实例，需要应用注解配置
	 */
	// 公共 API：使用 BeanDefinition 自身的元数据
	public static void processCommonDefinitionAnnotations(AnnotatedBeanDefinition abd) {
		// 调用内部方法，传入 BeanDefinition 的元数据
		processCommonDefinitionAnnotations(abd, abd.getMetadata());
	}

	// 内部静态方法：允许传入额外的元数据源（用于处理父类/接口的注解）
	static void processCommonDefinitionAnnotations(AnnotatedBeanDefinition abd, AnnotatedTypeMetadata metadata) {

		// ========== 1. 处理 @Lazy 注解 ==========
		// 尝试从传入的 metadata 中获取 @Lazy 注解属性
		AnnotationAttributes lazy = attributesFor(metadata, Lazy.class);
		if (lazy != null) {
			// 如果存在 @Lazy 注解，根据其 value 属性设置懒加载标志
			abd.setLazyInit(lazy.getBoolean("value"));
		}
		else if (abd.getMetadata() != metadata) {
			// 如果传入的 metadata 和 BeanDefinition 自身的元数据不是同一个对象
			// （例如传入的是父类或接口的元数据），则再从 BeanDefinition 自身的元数据中查找
			lazy = attributesFor(abd.getMetadata(), Lazy.class);
			if (lazy != null) {
				abd.setLazyInit(lazy.getBoolean("value"));
			}
		}
		// 注意：else 分支表示既没有配置 @Lazy，或者 @Lazy 不存在，则保持默认值（false）

		// ========== 2. 处理 @Primary 注解 ==========
		// 检查元数据上是否存在 @Primary 注解
		if (metadata.isAnnotated(Primary.class.getName())) {
			// 如果存在，设置为首选 Bean
			abd.setPrimary(true);
		}

		// ========== 3. 处理 @DependsOn 注解 ==========
		// 获取 @DependsOn 注解属性
		AnnotationAttributes dependsOn = attributesFor(metadata, DependsOn.class);
		if (dependsOn != null) {
			// 设置依赖的其他 Bean 名称数组
			// Spring 会在初始化当前 Bean 之前，先初始化这些依赖的 Bean
			abd.setDependsOn(dependsOn.getStringArray("value"));
		}

		// ========== 4. 处理 @Role 注解 ==========
		// 获取 @Role 注解属性
		AnnotationAttributes role = attributesFor(metadata, Role.class);
		if (role != null) {
			// 设置 Bean 的角色：
			// - BeanDefinition.ROLE_APPLICATION (0)：应用组件（默认）
			// - BeanDefinition.ROLE_SUPPORT (1)：支持组件
			// - BeanDefinition.ROLE_INFRASTRUCTURE (2)：基础设施组件
			abd.setRole(role.getNumber("value").intValue());
		}

		// ========== 5. 处理 @Description 注解 ==========
		// 获取 @Description 注解属性
		AnnotationAttributes description = attributesFor(metadata, Description.class);
		if (description != null) {
			// 设置 Bean 的描述信息（用于监控或文档目的）
			abd.setDescription(description.getString("value"));
		}
	}

	static BeanDefinitionHolder applyScopedProxyMode(
			ScopeMetadata metadata, BeanDefinitionHolder definition, BeanDefinitionRegistry registry) {

		ScopedProxyMode scopedProxyMode = metadata.getScopedProxyMode();
		if (scopedProxyMode.equals(ScopedProxyMode.NO)) {
			return definition;
		}
		boolean proxyTargetClass = scopedProxyMode.equals(ScopedProxyMode.TARGET_CLASS);
		return ScopedProxyCreator.createScopedProxy(definition, registry, proxyTargetClass);
	}

	@Nullable
	static AnnotationAttributes attributesFor(AnnotatedTypeMetadata metadata, Class<?> annotationClass) {
		return attributesFor(metadata, annotationClass.getName());
	}

	@Nullable
	static AnnotationAttributes attributesFor(AnnotatedTypeMetadata metadata, String annotationClassName) {
		return AnnotationAttributes.fromMap(metadata.getAnnotationAttributes(annotationClassName));
	}

	static Set<AnnotationAttributes> attributesForRepeatable(AnnotationMetadata metadata,
			Class<?> containerClass, Class<?> annotationClass) {

		return attributesForRepeatable(metadata, containerClass.getName(), annotationClass.getName());
	}

	@SuppressWarnings("unchecked")
	static Set<AnnotationAttributes> attributesForRepeatable(
			AnnotationMetadata metadata, String containerClassName, String annotationClassName) {

		Set<AnnotationAttributes> result = new LinkedHashSet<>();

		// Direct annotation present?
		addAttributesIfNotNull(result, metadata.getAnnotationAttributes(annotationClassName));

		// Container annotation present?
		Map<String, Object> container = metadata.getAnnotationAttributes(containerClassName);
		if (container != null && container.containsKey("value")) {
			for (Map<String, Object> containedAttributes : (Map<String, Object>[]) container.get("value")) {
				addAttributesIfNotNull(result, containedAttributes);
			}
		}

		// Return merged result
		return Collections.unmodifiableSet(result);
	}

	private static void addAttributesIfNotNull(
			Set<AnnotationAttributes> result, @Nullable Map<String, Object> attributes) {

		if (attributes != null) {
			result.add(AnnotationAttributes.fromMap(attributes));
		}
	}

}
