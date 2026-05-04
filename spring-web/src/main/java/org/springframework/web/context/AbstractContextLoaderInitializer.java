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

package org.springframework.web.context;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.lang.Nullable;
import org.springframework.web.WebApplicationInitializer;

/**
 * {@link WebApplicationInitializer} 实现的便捷基类，
 * 用于在 Servlet 上下文中注册 {@link ContextLoaderListener}。
 *
 * <p>这个类是 Spring MVC 父子容器体系中**父容器创建**的核心模板类。
 * 它封装了创建根 WebApplicationContext 并注册 ContextLoaderListener 的标准流程。
 *
 * <p>子类唯一需要实现的方法是 {@link #createRootApplicationContext()}，
 * 该方法会在 {@link #registerContextLoaderListener(ServletContext)} 中被调用。
 *
 * <p>继承体系中的位置：
 * <pre>
 * WebApplicationInitializer (接口)
 *     ↑
 * AbstractContextLoaderInitializer (此类)
 *     ↑
 * AbstractDispatcherServletInitializer
 *     ↑
 * AbstractAnnotationConfigDispatcherServletInitializer (最终常用实现)
 * </pre>
 *
 * <p>工作流程：
 * <ol>
 *   <li>Servlet 容器启动时，通过 SPI 机制发现并调用 {@link #onStartup(ServletContext)}</li>
 *   <li>调用 {@link #registerContextLoaderListener(ServletContext)} 注册监听器</li>
 *   <li>调用子类实现的 {@link #createRootApplicationContext()} 创建父容器</li>
 *   <li>创建 {@link ContextLoaderListener} 实例并添加到 ServletContext</li>
 *   <li>ContextLoaderListener 会在后续触发 {@code contextInitialized()} 方法，
 *       完成父容器的实际刷新和启动</li>
 * </ol>
 *
 * <p><b>注意：</b>父容器（Root WebApplicationContext）通常包含：
 * <ul>
 *   <li>数据源（DataSource）</li>
 *   <li>事务管理器（TransactionManager）</li>
 *   <li>Service 层组件（@Service）</li>
 *   <li>Repository 层组件（@Repository）</li>
 *   <li>其他非 Web 层的基础设施</li>
 * </ul>
 *
 * @author Arjen Poutsma
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.2
 */
public abstract class AbstractContextLoaderInitializer implements WebApplicationInitializer {

	/** 可供子类使用的日志记录器 */
	protected final Log logger = LogFactory.getLog(getClass());


	/**
	 * Servlet 容器启动时的入口方法。
	 *
	 * <p>此方法由 Servlet 容器通过 SPI 自动调用。
	 * 委托给 {@link #registerContextLoaderListener(ServletContext)}
	 * 来完成实际的父容器创建和监听器注册工作。
	 *
	 * @param servletContext Servlet 上下文，由 Servlet 容器提供
	 * @throws ServletException 如果初始化过程中发生错误
	 */
	@Override
	public void onStartup(ServletContext servletContext) throws ServletException {
		// 注册 ContextLoaderListener，这是创建父容器的核心步骤
		registerContextLoaderListener(servletContext);
	}

	/**
	 * 向给定的 Servlet 上下文注册 {@link ContextLoaderListener}。
	 *
	 * <p>此方法执行以下操作：
	 * <ol>
	 *   <li>调用 {@link #createRootApplicationContext()} 创建父容器
	 *       （由子类实现具体的创建逻辑）</li>
	 *   <li>如果父容器创建成功，则创建 {@link ContextLoaderListener} 实例</li>
	 *   <li>设置应用上下文初始化器（如果存在）</li>
	 *   <li>将监听器添加到 ServletContext 中</li>
	 * </ol>
	 *
	 * <p><b>关键点：</b>
	 * <ul>
	 *   <li>父容器此时只创建了实例和注册了配置类，但尚未刷新（refresh）</li>
	 *   <li>实际的容器刷新发生在 ContextLoaderListener 的
	 *       {@code contextInitialized()} 方法被 Servlet 容器回调时</li>
	 *   <li>父容器会被存储在 ServletContext 的属性中，key 为
	 *       {@code WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE}</li>
	 * </ul>
	 *
	 * @param servletContext 要注册监听器的 Servlet 上下文
	 */
	protected void registerContextLoaderListener(ServletContext servletContext) {
		// 1. 创建父容器（Root WebApplicationContext）
		//    子类实现此方法，通常返回 AnnotationConfigWebApplicationContext 实例
		WebApplicationContext rootAppContext = createRootApplicationContext();

		// 2. 如果父容器创建成功，则注册 ContextLoaderListener
		if (rootAppContext != null) {
			// 创建监听器实例，传入父容器
			ContextLoaderListener listener = new ContextLoaderListener(rootAppContext);

			// 设置应用上下文初始化器（可选，用于在容器刷新前进行额外配置）
			listener.setContextInitializers(getRootApplicationContextInitializers());

			// 将监听器添加到 ServletContext
			// Servlet 容器会在适当时机回调 listener.contextInitialized() 方法
			servletContext.addListener(listener);

			// 可选：记录日志
			if (logger.isDebugEnabled()) {
				logger.debug("Registered ContextLoaderListener for root application context: " + rootAppContext);
			}
		}
		else {
			// 没有父容器的情况（不常见，所有 Bean 都会放在子容器中）
			logger.debug("No ContextLoaderListener registered, as " +
					"createRootApplicationContext() did not return an application context");
		}
	}

	/**
	 * 创建用于提供给 {@code ContextLoaderListener} 的 "<strong>根</strong>" 应用上下文。
	 *
	 * <p>返回的上下文会被传递给
	 * {@link ContextLoaderListener#ContextLoaderListener(WebApplicationContext)}，
	 * 并会作为任何 {@code DispatcherServlet} 应用上下文的父容器。
	 *
	 * <p>因此，此上下文通常包含：
	 * <ul>
	 *   <li>中间层服务（Middle-tier services，即 Service 层）</li>
	 *   <li>数据源（DataSources）</li>
	 *   <li>数据访问对象（Repositories/DAOs）</li>
	 *   <li>事务管理器（TransactionManagers）</li>
	 *   <li>其他非 Web 层的基础设施</li>
	 * </ul>
	 *
	 * <p><b>注意：</b>
	 * <ul>
	 *   <li>如果返回 {@code null}，则不会注册 ContextLoaderListener，
	 *       这意味着没有父容器，所有 Bean 都在子容器中</li>
	 *   <li>返回的容器实例不应该已经刷新（refreshed），
	 *       刷新操作会在 ContextLoaderListener 中被触发</li>
	 * </ul>
	 *
	 * @return 根应用上下文（父容器），如果不需要根上下文则返回 {@code null}
	 * @see org.springframework.web.servlet.support.AbstractDispatcherServletInitializer
	 */
	@Nullable
	protected abstract WebApplicationContext createRootApplicationContext();

	/**
	 * 指定应用上下文初始化器，这些初始化器将应用于
	 * {@code ContextLoaderListener} 创建的根应用上下文。
	 *
	 * <p>初始化器可以在容器 {@code refresh()} 之前对容器进行额外配置，
	 * 例如：
	 * <ul>
	 *   <li>添加 Bean 后置处理器</li>
	 *   <li>设置环境变量</li>
	 *   <li>添加属性源（PropertySource）</li>
	 *   <li>注册自定义的作用域（Scope）</li>
	 * </ul>
	 *
	 * <p>如果有多个初始化器，它们会按照数组顺序依次执行。
	 *
	 * @return 初始化器数组，如果没有则返回 {@code null}
	 * @since 4.2
	 * @see #createRootApplicationContext()
	 * @see ContextLoaderListener#setContextInitializers
	 */
	@Nullable
	protected ApplicationContextInitializer<?>[] getRootApplicationContextInitializers() {
		return null;
	}

}