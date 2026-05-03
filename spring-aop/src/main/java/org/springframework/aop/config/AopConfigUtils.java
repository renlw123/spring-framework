/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.aop.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.aop.aspectj.autoproxy.AspectJAwareAdvisorAutoProxyCreator;
import org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Utility class for handling registration of AOP auto-proxy creators.
 *
 * <p>Only a single auto-proxy creator should be registered yet multiple concrete
 * implementations are available. This class provides a simple escalation protocol,
 * allowing a caller to request a particular auto-proxy creator and know that creator,
 * <i>or a more capable variant thereof</i>, will be registered as a post-processor.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @since 2.5
 * @see AopNamespaceUtils
 */
public abstract class AopConfigUtils {

	/**
	 * The bean name of the internally managed auto-proxy creator.
	 */
	public static final String AUTO_PROXY_CREATOR_BEAN_NAME =
			"org.springframework.aop.config.internalAutoProxyCreator";

	/**
	 * Stores the auto proxy creator classes in escalation order.
	 */
	private static final List<Class<?>> APC_PRIORITY_LIST = new ArrayList<>(3);

	static {
		// Set up the escalation list...
		APC_PRIORITY_LIST.add(InfrastructureAdvisorAutoProxyCreator.class);
		APC_PRIORITY_LIST.add(AspectJAwareAdvisorAutoProxyCreator.class);
		APC_PRIORITY_LIST.add(AnnotationAwareAspectJAutoProxyCreator.class);
	}


	@Nullable
	public static BeanDefinition registerAutoProxyCreatorIfNecessary(BeanDefinitionRegistry registry) {
		return registerAutoProxyCreatorIfNecessary(registry, null);
	}

	@Nullable
	public static BeanDefinition registerAutoProxyCreatorIfNecessary(
			BeanDefinitionRegistry registry, @Nullable Object source) {

		return registerOrEscalateApcAsRequired(InfrastructureAdvisorAutoProxyCreator.class, registry, source);
	}

	@Nullable
	public static BeanDefinition registerAspectJAutoProxyCreatorIfNecessary(BeanDefinitionRegistry registry) {
		return registerAspectJAutoProxyCreatorIfNecessary(registry, null);
	}

	@Nullable
	public static BeanDefinition registerAspectJAutoProxyCreatorIfNecessary(
			BeanDefinitionRegistry registry, @Nullable Object source) {

		return registerOrEscalateApcAsRequired(AspectJAwareAdvisorAutoProxyCreator.class, registry, source);
	}

	/**
	 * 如果需要，注册 AspectJ 注解自动代理创建器。
	 *
	 * <p>这是 Spring AOP 启动的核心方法之一。它会检查注册表中是否已存在自动代理创建器，
	 * 如果不存在则注册默认的 {@link AnnotationAwareAspectJAutoProxyCreator}。
	 *
	 * <p>该方法是 {@link #registerAspectJAnnotationAutoProxyCreatorIfNecessary(BeanDefinitionRegistry, Object)}
	 * 的简化版本，传入的 source 参数为 null。
	 *
	 * @param registry Bean 定义注册表，用于注册自动代理创建器
	 * @return 注册的 BeanDefinition，如果已存在则返回已存在的；如果注册失败则返回 null
	 * @see AnnotationAwareAspectJAutoProxyCreator
	 * @see #registerAspectJAnnotationAutoProxyCreatorIfNecessary(BeanDefinitionRegistry, Object)
	 */
	@Nullable
	public static BeanDefinition registerAspectJAnnotationAutoProxyCreatorIfNecessary(BeanDefinitionRegistry registry) {
		// 委托给两参数版本，source 参数传 null
		return registerAspectJAnnotationAutoProxyCreatorIfNecessary(registry, null);
	}

	/**
	 * 如果需要，注册 AspectJ 注解自动代理创建器。
	 *
	 * <p>这是 Spring AOP 启动的核心方法。它会检查注册表中是否已存在自动代理创建器，
	 * 如果不存在则注册 {@link AnnotationAwareAspectJAutoProxyCreator}。
	 * 如果已存在但优先级不同，则会进行升级/替换处理。
	 *
	 * @param registry Bean 定义注册表，用于注册自动代理创建器
	 * @param source 源配置信息（通常为 @Configuration 配置类的 Resource），用于日志或调试
	 * @return 注册的 BeanDefinition，如果注册失败则返回 null
	 * @see AnnotationAwareAspectJAutoProxyCreator
	 * @see #registerOrEscalateApcAsRequired(Class, BeanDefinitionRegistry, Object)
	 */
	@Nullable
	public static BeanDefinition registerAspectJAnnotationAutoProxyCreatorIfNecessary(
			BeanDefinitionRegistry registry, @Nullable Object source) {

		// 委托给核心注册方法，注册 AnnotationAwareAspectJAutoProxyCreator
		// 这个 Bean 是 Spring AOP 的支柱，负责：
		//   1. 扫描所有 Advisor（切面）
		//   2. 判断哪些 Bean 需要被代理
		//   3. 为匹配的 Bean 创建代理对象
		return registerOrEscalateApcAsRequired(AnnotationAwareAspectJAutoProxyCreator.class, registry, source);
	}

	public static void forceAutoProxyCreatorToUseClassProxying(BeanDefinitionRegistry registry) {
		if (registry.containsBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME)) {
			BeanDefinition definition = registry.getBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME);
			definition.getPropertyValues().add("proxyTargetClass", Boolean.TRUE);
		}
	}

	public static void forceAutoProxyCreatorToExposeProxy(BeanDefinitionRegistry registry) {
		if (registry.containsBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME)) {
			BeanDefinition definition = registry.getBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME);
			definition.getPropertyValues().add("exposeProxy", Boolean.TRUE);
		}
	}

	/**
	 * 注册或升级自动代理创建器（Auto Proxy Creator）。
	 *
	 * <p>这是 AOP 自动代理创建器注册的核心方法。它会检查容器中是否已经存在自动代理创建器：
	 * <ul>
	 *   <li>如果不存在，则创建并注册一个新的 BeanDefinition</li>
	 *   <li>如果已存在，则比较优先级：当新创建器的优先级更高时，会进行升级替换</li>
	 * </ul>
	 *
	 * <p>优先级规则（数值越小优先级越高）：
	 * <ul>
	 *   <li>InfrastructureAdvisorAutoProxyCreator - 基础优先级</li>
	 *   <li>AspectJAwareAdvisorAutoProxyCreator - 中等优先级</li>
	 *   <li>AnnotationAwareAspectJAutoProxyCreator - 最高优先级</li>
	 * </ul>
	 *
	 * @param cls 自动代理创建器的类型（如 AnnotationAwareAspectJAutoProxyCreator.class）
	 * @param registry Bean 定义注册表，用于注册或获取 Bean 定义
	 * @param source 源配置信息，用于记录 BeanDefinition 的来源
	 * @return 新注册的 BeanDefinition；如果已存在且无需升级，则返回 null
	 */
	@Nullable
	private static BeanDefinition registerOrEscalateApcAsRequired(
			Class<?> cls, BeanDefinitionRegistry registry, @Nullable Object source) {

		Assert.notNull(registry, "BeanDefinitionRegistry must not be null");

		// ========== 情况1：自动代理创建器已存在 ==========
		if (registry.containsBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME)) {
			// 获取已存在的 BeanDefinition
			BeanDefinition apcDefinition = registry.getBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME);

			// 如果已存在的类型与要注册的类型不同，需要进行优先级比较
			if (!cls.getName().equals(apcDefinition.getBeanClassName())) {
				// 获取已存在类型的优先级
				int currentPriority = findPriorityForClass(apcDefinition.getBeanClassName());
				// 获取要注册类型的优先级
				int requiredPriority = findPriorityForClass(cls);

				// 如果新类型的优先级更高（数值更大），则进行升级替换
				if (currentPriority < requiredPriority) {
					// 升级：将已存在的 BeanDefinition 的 className 修改为新的类型
					apcDefinition.setBeanClassName(cls.getName());
				}
			}
			// 已存在且无需升级，返回 null
			return null;
		}

		// ========== 情况2：自动代理创建器不存在 ==========
		// 创建新的 RootBeanDefinition
		RootBeanDefinition beanDefinition = new RootBeanDefinition(cls);
		// 设置源信息，便于调试
		beanDefinition.setSource(source);
		// 设置 order 属性为最高优先级（值最小），确保这个后置处理器优先执行
		beanDefinition.getPropertyValues().add("order", Ordered.HIGHEST_PRECEDENCE);
		// 设置为基础设施角色，这种 Bean 是框架内部使用的，通常不会被应用程序代码直接使用
		beanDefinition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
		// 注册到容器中
		registry.registerBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME, beanDefinition);

		return beanDefinition;
	}

	private static int findPriorityForClass(Class<?> clazz) {
		return APC_PRIORITY_LIST.indexOf(clazz);
	}

	private static int findPriorityForClass(@Nullable String className) {
		for (int i = 0; i < APC_PRIORITY_LIST.size(); i++) {
			Class<?> clazz = APC_PRIORITY_LIST.get(i);
			if (clazz.getName().equals(className)) {
				return i;
			}
		}
		throw new IllegalArgumentException(
				"Class name [" + className + "] is not a known auto-proxy creator class");
	}

}
