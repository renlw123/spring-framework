/*
 * Copyright 2002-2017 the original author or authors.
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

import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Registers an {@link org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator
 * AnnotationAwareAspectJAutoProxyCreator} against the current {@link BeanDefinitionRegistry}
 * as appropriate based on a given @{@link EnableAspectJAutoProxy} annotation.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.1
 * @see EnableAspectJAutoProxy
 */
class AspectJAutoProxyRegistrar implements ImportBeanDefinitionRegistrar {

	/**
	 * 根据导入的 {@code @Configuration} 类上的 @{@link EnableAspectJAutoProxy} 注解的属性值，
	 * 注册、升级并配置 AspectJ 自动代理创建器。
	 *
	 * <p>这是 Spring AOP 启动的核心方法，通过 @EnableAspectJAutoProxy 注解触发，
	 * 负责向容器中注册 AOP 的关键基础设施组件。
	 *
	 * @param importingClassMetadata 导入类的注解元数据（即使用了 @EnableAspectJAutoProxy 的配置类）
	 * @param registry 当前的 Bean 定义注册表
	 */
	@Override
	public void registerBeanDefinitions(
			AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {

		// ========== 步骤1：注册 AspectJ 注解自动代理创建器 ==========
		// 注册 AnnotationAwareAspectJAutoProxyCreator 的 BeanDefinition
		// 这是 Spring AOP 的核心组件，会作为 BeanPostProcessor 介入 Bean 的创建过程
		// 如果已存在则不会重复注册（xxxIfNecessary 的含义）
		AopConfigUtils.registerAspectJAnnotationAutoProxyCreatorIfNecessary(registry);

		// ========== 步骤2：获取 @EnableAspectJAutoProxy 注解的属性 ==========
		// 从导入类的注解元数据中提取 @EnableAspectJAutoProxy 注解的属性
		AnnotationAttributes enableAspectJAutoProxy =
				AnnotationConfigUtils.attributesFor(importingClassMetadata, EnableAspectJAutoProxy.class);

		if (enableAspectJAutoProxy != null) {

			// ========== 步骤3：处理 proxyTargetClass 属性 ==========
			// 如果 proxyTargetClass = true，强制使用 CGLIB 代理（基于类代理）
			// 默认为 false，使用 JDK 动态代理（基于接口代理）
			if (enableAspectJAutoProxy.getBoolean("proxyTargetClass")) {
				AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
			}

			// ========== 步骤4：处理 exposeProxy 属性 ==========
			// 如果 exposeProxy = true，将代理对象暴露到 ThreadLocal 中
			// 允许通过 AopContext.currentProxy() 获取当前代理对象
			// 用于解决内部方法调用无法被切面拦截的问题
			if (enableAspectJAutoProxy.getBoolean("exposeProxy")) {
				AopConfigUtils.forceAutoProxyCreatorToExposeProxy(registry);
			}
		}
	}

}
