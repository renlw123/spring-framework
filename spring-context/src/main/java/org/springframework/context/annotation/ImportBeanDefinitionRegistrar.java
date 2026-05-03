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

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Interface to be implemented by types that register additional bean definitions when
 * processing @{@link Configuration} classes. Useful when operating at the bean definition
 * level (as opposed to {@code @Bean} method/instance level) is desired or necessary.
 *
 * <p>Along with {@code @Configuration} and {@link ImportSelector}, classes of this type
 * may be provided to the @{@link Import} annotation (or may also be returned from an
 * {@code ImportSelector}).
 *
 * <p>An {@link ImportBeanDefinitionRegistrar} may implement any of the following
 * {@link org.springframework.beans.factory.Aware Aware} interfaces, and their respective
 * methods will be called prior to {@link #registerBeanDefinitions}:
 * <ul>
 * <li>{@link org.springframework.context.EnvironmentAware EnvironmentAware}</li>
 * <li>{@link org.springframework.beans.factory.BeanFactoryAware BeanFactoryAware}
 * <li>{@link org.springframework.beans.factory.BeanClassLoaderAware BeanClassLoaderAware}
 * <li>{@link org.springframework.context.ResourceLoaderAware ResourceLoaderAware}
 * </ul>
 *
 * <p>Alternatively, the class may provide a single constructor with one or more of
 * the following supported parameter types:
 * <ul>
 * <li>{@link org.springframework.core.env.Environment Environment}</li>
 * <li>{@link org.springframework.beans.factory.BeanFactory BeanFactory}</li>
 * <li>{@link java.lang.ClassLoader ClassLoader}</li>
 * <li>{@link org.springframework.core.io.ResourceLoader ResourceLoader}</li>
 * </ul>
 *
 * <p>See implementations and associated unit tests for usage examples.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.1
 * @see Import
 * @see ImportSelector
 * @see Configuration
 */
public interface ImportBeanDefinitionRegistrar {

	/**
	 * 根据导入的 {@code @Configuration} 类的注解元数据，按需注册 Bean 定义。
	 *
	 * <p>注意：由于与 {@code @Configuration} 类处理相关的生命周期约束，
	 * {@link BeanDefinitionRegistryPostProcessor} 类型的 Bean 不能在此处注册。
	 *
	 * <p>默认实现委托给
	 * {@link #registerBeanDefinitions(AnnotationMetadata, BeanDefinitionRegistry)} 方法。
	 *
	 * @param importingClassMetadata 导入类的注解元数据（即使用 @Import 导入当前注册器的配置类）
	 * @param registry               当前的 Bean 定义注册表
	 * @param importBeanNameGenerator 用于导入 Bean 的 Bean 名称生成策略：
	 *                                默认为 {@link ConfigurationClassPostProcessor#IMPORT_BEAN_NAME_GENERATOR}，
	 *                                或者如果设置了 {@link ConfigurationClassPostProcessor#setBeanNameGenerator}，
	 *                                则使用用户提供的策略。在后一种情况下，传入的策略将与包含的应用程序上下文中
	 *                                用于组件扫描的策略相同（否则，默认的组件扫描命名策略是
	 *                                {@link AnnotationBeanNameGenerator#INSTANCE}）。
	 * @since 5.2
	 * @see ConfigurationClassPostProcessor#IMPORT_BEAN_NAME_GENERATOR
	 * @see ConfigurationClassPostProcessor#setBeanNameGenerator
	 */
	default void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry,
										 BeanNameGenerator importBeanNameGenerator) {

		// 默认实现：调用两参数版本以保持向后兼容性
		// 子类可以重写此方法来利用传入的 BeanNameGenerator
		registerBeanDefinitions(importingClassMetadata, registry);
	}

	/**
	 * 根据导入的 {@code @Configuration} 类的注解元数据，按需注册 Bean 定义。
	 *
	 * <p>注意：由于与 {@code @Configuration} 类处理相关的生命周期约束，
	 * {@link BeanDefinitionRegistryPostProcessor} 类型的 Bean 不能在此处注册。
	 *
	 * <p>默认实现为空，由子类根据需要重写。
	 *
	 * @param importingClassMetadata 导入类的注解元数据（即使用 @Import 导入当前注册器的配置类）
	 * @param registry 当前的 Bean 定义注册表
	 */
	default void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
		// 默认空实现，子类可重写此方法以提供具体的 Bean 注册逻辑
	}

}
