/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.context.support;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.EmbeddedValueResolver;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.ApplicationStartupAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.lang.Nullable;
import org.springframework.util.StringValueResolver;

/**
 * {@link BeanPostProcessor} implementation that supplies the {@code ApplicationContext},
 * {@link org.springframework.core.env.Environment Environment}, or
 * {@link StringValueResolver} for the {@code ApplicationContext} to beans that
 * implement the {@link EnvironmentAware}, {@link EmbeddedValueResolverAware},
 * {@link ResourceLoaderAware}, {@link ApplicationEventPublisherAware},
 * {@link MessageSourceAware}, and/or {@link ApplicationContextAware} interfaces.
 *
 * <p>Implemented interfaces are satisfied in the order in which they are
 * mentioned above.
 *
 * <p>Application contexts will automatically register this with their
 * underlying bean factory. Applications do not use this directly.
 *
 * @author Juergen Hoeller
 * @author Costin Leau
 * @author Chris Beams
 * @since 10.10.2003
 * @see org.springframework.context.EnvironmentAware
 * @see org.springframework.context.EmbeddedValueResolverAware
 * @see org.springframework.context.ResourceLoaderAware
 * @see org.springframework.context.ApplicationEventPublisherAware
 * @see org.springframework.context.MessageSourceAware
 * @see org.springframework.context.ApplicationContextAware
 * @see org.springframework.context.support.AbstractApplicationContext#refresh()
 */
class ApplicationContextAwareProcessor implements BeanPostProcessor {

	private final ConfigurableApplicationContext applicationContext;

	private final StringValueResolver embeddedValueResolver;


	/**
	 * Create a new ApplicationContextAwareProcessor for the given context.
	 */
	public ApplicationContextAwareProcessor(ConfigurableApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
		this.embeddedValueResolver = new EmbeddedValueResolver(applicationContext.getBeanFactory());
	}


	/**
	 * 实现了 BeanPostProcessor 接口的 postProcessBeforeInitialization 方法。
	 * 在 Bean 初始化之前，为实现了特定 Aware 接口的 Bean 注入相应的资源。
	 *
	 * @param bean     当前的 Bean 实例
	 * @param beanName Bean 的名称
	 * @return 处理后的 Bean 实例（通常是原实例，因为此处理器只注入资源，不包装对象）
	 * @throws BeansException 如果处理过程中发生错误
	 */
	@Override
	@Nullable
	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {

		// ============ 第一步：快速过滤 ============
		// 检查当前 Bean 是否实现了任何一个该处理器关心的 Aware 接口
		// 如果没有实现任何一个，直接返回原 Bean，不做任何处理
		// 这是一种性能优化手段，避免对每个 Bean 都进行不必要的反射或资源注入操作
		if (!(bean instanceof EnvironmentAware || bean instanceof EmbeddedValueResolverAware ||
				bean instanceof ResourceLoaderAware || bean instanceof ApplicationEventPublisherAware ||
				bean instanceof MessageSourceAware || bean instanceof ApplicationContextAware ||
				bean instanceof ApplicationStartupAware)) {
			return bean;
		}

		// ============ 第二步：处理安全管理器（SecurityManager）============
		// 获取访问控制上下文，用于在安全环境下以特权方式执行代码
		AccessControlContext acc = null;
		if (System.getSecurityManager() != null) {
			// 从 BeanFactory 中获取访问控制上下文
			acc = this.applicationContext.getBeanFactory().getAccessControlContext();
		}

		// ============ 第三步：执行 Aware 接口注入 ============
		// 根据是否有安全管理器，选择不同的执行方式
		if (acc != null) {
			// 有安全管理器：以特权方式执行，可以绕过某些权限检查
			AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
				invokeAwareInterfaces(bean);  // 实际注入逻辑
				return null;
			}, acc);
		} else {
			// 无安全管理器：直接执行
			invokeAwareInterfaces(bean);
		}

		// 返回原 Bean（该处理器不包装 Bean，只注入资源）
		return bean;
	}

	/**
	 * 实际执行 Aware 接口的注入逻辑。
	 * 按照固定的顺序检查 Bean 实现的接口，并注入对应的资源。
	 *
	 * 注意：这里使用的是 if 而不是 else if，因为一个 Bean 可能同时实现多个 Aware 接口。
	 *
	 * @param bean 当前的 Bean 实例
	 */
	private void invokeAwareInterfaces(Object bean) {

		// 1. 注入 Environment（环境配置信息，如 properties 文件中的属性）
		if (bean instanceof EnvironmentAware) {
			((EnvironmentAware) bean).setEnvironment(this.applicationContext.getEnvironment());
		}

		// 2. 注入 EmbeddedValueResolver（用于解析字符串中的占位符，如 ${xxx}）
		if (bean instanceof EmbeddedValueResolverAware) {
			((EmbeddedValueResolverAware) bean).setEmbeddedValueResolver(this.embeddedValueResolver);
		}

		// 3. 注入 ResourceLoader（用于加载资源文件，如类路径下的配置文件）
		if (bean instanceof ResourceLoaderAware) {
			((ResourceLoaderAware) bean).setResourceLoader(this.applicationContext);
		}

		// 4. 注入 ApplicationEventPublisher（用于发布应用事件，实现观察者模式）
		if (bean instanceof ApplicationEventPublisherAware) {
			((ApplicationEventPublisherAware) bean).setApplicationEventPublisher(this.applicationContext);
		}

		// 5. 注入 MessageSource（用于国际化消息，如 i18n 支持）
		if (bean instanceof MessageSourceAware) {
			((MessageSourceAware) bean).setMessageSource(this.applicationContext);
		}

		// 6. 注入 ApplicationStartup（用于收集应用启动过程中的性能指标）
		if (bean instanceof ApplicationStartupAware) {
			((ApplicationStartupAware) bean).setApplicationStartup(this.applicationContext.getApplicationStartup());
		}

		// 7. 注入完整的 ApplicationContext（Spring 上下文，功能最全）
		if (bean instanceof ApplicationContextAware) {
			((ApplicationContextAware) bean).setApplicationContext(this.applicationContext);
		}
	}

}
