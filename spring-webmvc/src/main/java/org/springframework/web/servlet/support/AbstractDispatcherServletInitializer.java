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

package org.springframework.web.servlet.support;

import java.util.EnumSet;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterRegistration;
import javax.servlet.FilterRegistration.Dynamic;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.core.Conventions;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.web.context.AbstractContextLoaderInitializer;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.FrameworkServlet;

/**
 * {@link org.springframework.web.WebApplicationInitializer WebApplicationInitializer}
 * 的基类，用于在 Servlet 容器中注册 {@link DispatcherServlet}。
 *
 * <p>这是一个模板类，封装了注册 DispatcherServlet 的标准流程。
 * 大多数应用应该考虑继承其子类 {@link AbstractAnnotationConfigDispatcherServletInitializer}，
 * 该类提供了基于注解的配置支持。
 *
 * <p>工作流程：
 * <ol>
 *   <li>继承此类并实现抽象方法</li>
 *   <li>Servlet 容器启动时自动调用 {@link #onStartup(ServletContext)}</li>
 *   <li>先调用父类方法创建根容器（ContextLoaderListener）</li>
 *   <li>再调用 {@link #registerDispatcherServlet(ServletContext)} 创建子容器和 DispatcherServlet</li>
 *   <li>注册 Servlet 并设置映射路径</li>
 *   <li>可选：注册过滤器并映射到该 Servlet</li>
 * </ol>
 *
 * @author Arjen Poutsma
 * @author Chris Beams
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @since 3.2
 */
public abstract class AbstractDispatcherServletInitializer extends AbstractContextLoaderInitializer {

	/**
	 * 默认的 Servlet 名称。可以通过重写 {@link #getServletName()} 来自定义。
	 */
	public static final String DEFAULT_SERVLET_NAME = "dispatcher";


	/**
	 * Servlet 容器启动时的入口方法。
	 *
	 * <p>调用链：Servlet 容器 → SPI 发现 WebApplicationInitializer → 调用此方法
	 *
	 * <p>执行顺序：
	 * <ol>
	 *   <li>调用父类 {@link AbstractContextLoaderInitializer#onStartup(ServletContext)}，
	 *       创建根容器（父容器）并注册 ContextLoaderListener</li>
	 *   <li>调用 {@link #registerDispatcherServlet(ServletContext)}，
	 *       创建子容器和 DispatcherServlet</li>
	 * </ol>
	 *
	 * @param servletContext Servlet 上下文，由 Servlet 容器提供
	 * @throws ServletException 如果初始化过程中发生错误
	 *
	 * ## 注意这两部只是创建了容器以及相关的监听器和容器的关联关系，
	 * ## 容器初始化分别发生在：
	 * ## 父容器的 refresh() 是在 ContextLoaderListener 被 Servlet 容器回调时才执行的 org.springframework.web.context.ContextLoaderListener#contextInitialized(javax.servlet.ServletContextEvent)
	 * ## 子容器的 refresh() 是在 DispatcherServlet 被 Servlet 容器初始化时才执行的 org.springframework.web.servlet.HttpServletBean#init()
	 */
	@Override
	public void onStartup(ServletContext servletContext) throws ServletException {
		// 1. 创建父容器（Root WebApplicationContext）并注册 ContextLoaderListener
		super.onStartup(servletContext);

		// 2. 创建子容器（Servlet WebApplicationContext）并注册 DispatcherServlet
		registerDispatcherServlet(servletContext);
	}

	/**
	 * 向给定的 Servlet 上下文注册 {@link DispatcherServlet}。
	 *
	 * <p>此方法会：
	 * <ul>
	 *   <li>通过 {@link #getServletName()} 获取 Servlet 名称</li>
	 *   <li>通过 {@link #createServletApplicationContext()} 创建子容器</li>
	 *   <li>通过 {@link #createDispatcherServlet(WebApplicationContext)} 创建 DispatcherServlet 实例</li>
	 *   <li>通过 {@link #getServletMappings()} 获取映射路径</li>
	 *   <li>将 Servlet 注册到 ServletContext</li>
	 *   <li>通过 {@link #getServletFilters()} 获取过滤器并注册</li>
	 *   <li>通过 {@link #customizeRegistration(ServletRegistration.Dynamic)} 提供自定义扩展点</li>
	 * </ul>
	 *
	 * <p>可以通过重写以下方法进行自定义：
	 * <ul>
	 *   <li>{@link #customizeRegistration(ServletRegistration.Dynamic)}</li>
	 *   <li>{@link #createDispatcherServlet(WebApplicationContext)}</li>
	 *   <li>{@link #getServletFilters()}</li>
	 * </ul>
	 *
	 * @param servletContext 要注册 Servlet 的上下文
	 */
	protected void registerDispatcherServlet(ServletContext servletContext) {
		// 1. 获取 Servlet 名称（默认 "dispatcher"）
		String servletName = getServletName();
		Assert.hasLength(servletName, "getServletName() must not return null or empty");

		// 2. 创建 Servlet 专用的 WebApplicationContext（子容器）
		//    这个容器将包含 Controller、ViewResolver 等 Web 层组件
		WebApplicationContext servletAppContext = createServletApplicationContext();
		Assert.notNull(servletAppContext, "createServletApplicationContext() must not return null");

		// 3. 创建 DispatcherServlet 实例，传入子容器
		//    🔗 父子关系的建立发生在 FrameworkServlet.initWebApplicationContext() 中
		//    该方法会调用 wac.setParent(rootContext) 建立父子关系
		FrameworkServlet dispatcherServlet = createDispatcherServlet(servletAppContext);
		Assert.notNull(dispatcherServlet, "createDispatcherServlet(WebApplicationContext) must not return null");

		// 4. 设置应用上下文初始化器（可选，用于在容器刷新前进行额外配置）
		dispatcherServlet.setContextInitializers(getServletApplicationContextInitializers());

		// 5. 向 ServletContext 注册 Servlet
		ServletRegistration.Dynamic registration = servletContext.addServlet(servletName, dispatcherServlet);
		if (registration == null) {
			throw new IllegalStateException("Failed to register servlet with name '" + servletName + "'. " +
					"Check if there is another servlet registered under the same name.");
		}

		// 6. 配置 Servlet：启动时加载、映射路径、异步支持
		registration.setLoadOnStartup(1);           // 应用启动时立即初始化
		registration.addMapping(getServletMappings()); // 设置 URL 映射，如 "/"
		registration.setAsyncSupported(isAsyncSupported()); // 是否支持异步请求

		// 7. 注册过滤器（可选），并自动映射到当前 DispatcherServlet
		Filter[] filters = getServletFilters();
		if (!ObjectUtils.isEmpty(filters)) {
			for (Filter filter : filters) {
				registerServletFilter(servletContext, filter);
			}
		}

		// 8. 自定义注册的扩展点（子类可重写）
		customizeRegistration(registration);
	}

	/**
	 * 返回 {@link DispatcherServlet} 注册时使用的名称。
	 *
	 * <p>默认返回 {@link #DEFAULT_SERVLET_NAME} ("dispatcher")。
	 * 如果有多个 DispatcherServlet，可以重写此方法为每个 Servlet 指定不同的名称。
	 *
	 * @return Servlet 名称
	 * @see #registerDispatcherServlet(ServletContext)
	 */
	protected String getServletName() {
		return DEFAULT_SERVLET_NAME;
	}

	/**
	 * 创建 Servlet 应用上下文，用于提供给 {@code DispatcherServlet}。
	 *
	 * <p>返回的上下文会被传递给 Spring 的
	 * {@link DispatcherServlet#DispatcherServlet(WebApplicationContext)} 构造器。
	 * 因此，此上下文通常包含控制器、视图解析器、本地化解析器以及其他 Web 相关的 Bean。
	 *
	 * <p><b>注意：</b>此方法返回的容器与父容器（由 ContextLoaderListener 创建）是独立的，
	 * 但会在 FrameworkServlet 中通过 {@code setParent()} 建立父子关系。
	 *
	 * @return Servlet WebApplicationContext（子容器）
	 * @see #registerDispatcherServlet(ServletContext)
	 */
	protected abstract WebApplicationContext createServletApplicationContext();

	/**
	 * 使用指定的 {@link WebApplicationContext} 创建 {@link DispatcherServlet}
	 * （或其他 {@link FrameworkServlet} 子类的分发器）。
	 *
	 * <p>默认实现创建标准的 {@link DispatcherServlet}。
	 * 如果需要自定义 DispatcherServlet 的行为，可以重写此方法返回子类实例。
	 *
	 * <p>注意：从 Spring 4.2.3 开始，此方法允许返回任何 {@link FrameworkServlet} 子类。
	 * 之前版本强制要求返回 {@link DispatcherServlet} 或其子类。
	 *
	 * @param servletAppContext 子容器
	 * @return 分发器 Servlet 实例
	 */
	protected FrameworkServlet createDispatcherServlet(WebApplicationContext servletAppContext) {
		return new DispatcherServlet(servletAppContext);
	}

	/**
	 * 指定应用上下文初始化器，这些初始化器将应用于为 {@code DispatcherServlet} 创建的
	 * Servlet 专用应用上下文。
	 *
	 * <p>初始化器可以在容器 {@code refresh()} 之前对容器进行额外配置，
	 * 例如添加 Bean 后置处理器、设置环境变量等。
	 *
	 * @return 初始化器数组，如果没有则返回 {@code null}
	 * @since 4.2
	 * @see #createServletApplicationContext()
	 * @see DispatcherServlet#setContextInitializers
	 * @see #getRootApplicationContextInitializers()
	 */
	@Nullable
	protected ApplicationContextInitializer<?>[] getServletApplicationContextInitializers() {
		return null;
	}

	/**
	 * 指定 {@code DispatcherServlet} 的 Servlet 映射路径。
	 *
	 * <p>例如：{@code "/"} 表示处理所有请求，
	 * {@code "/app/*"} 表示处理以 /app/ 开头的请求。
	 *
	 * @return URL 映射模式数组
	 * @see #registerDispatcherServlet(ServletContext)
	 */
	protected abstract String[] getServletMappings();

	/**
	 * 指定要添加并映射到 {@code DispatcherServlet} 的过滤器。
	 *
	 * <p>这些过滤器会自动被注册并映射到当前 DispatcherServlet，
	 * 无需在 web.xml 中额外配置。
	 *
	 * @return 过滤器数组，如果没有过滤器则返回 {@code null}
	 * @see #registerServletFilter(ServletContext, Filter)
	 */
	@Nullable
	protected Filter[] getServletFilters() {
		return null;
	}

	/**
	 * 将给定的过滤器添加到 ServletContext，并将其映射到 {@code DispatcherServlet}。
	 *
	 * <p>默认行为：
	 * <ul>
	 *   <li>根据过滤器的具体类型自动生成默认的名称（基于类名的驼峰命名）</li>
	 *   <li>根据 {@link #isAsyncSupported() asyncSupported} 的返回值设置 {@code asyncSupported} 标志</li>
	 *   <li>创建过滤器映射，默认包含 {@code REQUEST}、{@code FORWARD}、{@code INCLUDE}
	 *       分发器类型，如果支持异步则额外包含 {@code ASYNC}</li>
	 * </ul>
	 *
	 * <p>如果上述默认行为不符合需求，可以重写此方法，
	 * 直接使用 {@code ServletContext} 注册过滤器。
	 *
	 * @param servletContext 用于注册过滤器的 Servlet 上下文
	 * @param filter         要注册的过滤器
	 * @return 过滤器注册对象
	 */
	protected FilterRegistration.Dynamic registerServletFilter(ServletContext servletContext, Filter filter) {
		// 根据过滤器类型生成默认名称（如 CharacterEncodingFilter → characterEncodingFilter）
		String filterName = Conventions.getVariableName(filter);
		Dynamic registration = servletContext.addFilter(filterName, filter);

		// 如果名称已被占用，尝试添加后缀 "#0", "#1", ...
		if (registration == null) {
			int counter = 0;
			while (registration == null) {
				if (counter == 100) {
					throw new IllegalStateException("Failed to register filter with name '" + filterName + "'. " +
							"Check if there is another filter registered under the same name.");
				}
				registration = servletContext.addFilter(filterName + "#" + counter, filter);
				counter++;
			}
		}

		// 设置异步支持和映射
		registration.setAsyncSupported(isAsyncSupported());
		registration.addMappingForServletNames(getDispatcherTypes(), false, getServletName());
		return registration;
	}

	/**
	 * 获取过滤器映射的分发器类型集合。
	 *
	 * <p>根据是否支持异步，返回不同的分发器类型集合：
	 * <ul>
	 *   <li>支持异步：REQUEST, FORWARD, INCLUDE, ASYNC</li>
	 *   <li>不支持异步：REQUEST, FORWARD, INCLUDE</li>
	 * </ul>
	 *
	 * @return 分发器类型的枚举集合
	 */
	private EnumSet<DispatcherType> getDispatcherTypes() {
		return (isAsyncSupported() ?
				EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD, DispatcherType.INCLUDE, DispatcherType.ASYNC) :
				EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD, DispatcherType.INCLUDE));
	}

	/**
	 * 统一控制 {@code DispatcherServlet} 和所有通过 {@link #getServletFilters()}
	 * 添加的过滤器的 {@code asyncSupported} 标志。
	 *
	 * <p>默认值为 {@code true}，表示支持异步请求处理。
	 * 如果应用不需要异步支持，可以重写此方法返回 {@code false}。
	 *
	 * @return 是否支持异步处理
	 */
	protected boolean isAsyncSupported() {
		return true;
	}

	/**
	 * 可选的扩展点：在 {@link #registerDispatcherServlet(ServletContext)} 完成后，
	 * 进一步进行注册自定义。
	 *
	 * <p>子类可以重写此方法来设置额外的 Servlet 初始化参数、安全约束等。
	 *
	 * @param registration 可进行自定义的 Servlet 注册对象
	 * @see #registerDispatcherServlet(ServletContext)
	 */
	protected void customizeRegistration(ServletRegistration.Dynamic registration) {
	}
}