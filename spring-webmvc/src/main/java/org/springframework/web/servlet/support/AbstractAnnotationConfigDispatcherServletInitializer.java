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

import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * {@link org.springframework.web.WebApplicationInitializer WebApplicationInitializer}
 * 的实现类，用于注册 {@code DispatcherServlet} 并使用基于 Java 的 Spring 配置。
 *
 * <p>这是一个模板类，封装了基于注解配置的 Spring MVC 应用的初始化逻辑。
 * 使用 Servlet 3.0+ 容器时，无需 web.xml，只需继承此类并实现两个抽象方法。
 *
 * <p>实现类需要提供以下两个方法：
 * <ul>
 * <li>{@link #getRootConfigClasses()} —— 用于配置"根"应用上下文（非 Web 层基础设施，如 Service、Repository、DataSource 等）
 * <li>{@link #getServletConfigClasses()} —— 用于配置 {@code DispatcherServlet} 的应用上下文（Spring MVC 基础设施，如 Controller、ViewResolver 等）
 * </ul>
 *
 * <p>如果不需要应用上下文层级结构（即父子容器），应用程序可以通过
 * {@link #getRootConfigClasses()} 返回所有配置，并让 {@link #getServletConfigClasses()}
 * 返回 {@code null}。此时只有一个容器（类似 Spring Boot 的做法）。
 *
 * <p>工作流程：
 * <ol>
 *   <li>Servlet 容器启动时，通过 SPI 机制自动发现并实例化该类的实现类</li>
 *   <li>调用 {@link #onStartup(ServletContext)} 方法（继承自父类）</li>
 *   <li>创建父容器（Root WebApplicationContext），用于管理 Service、DAO 等业务层组件</li>
 *   <li>创建子容器（Servlet WebApplicationContext），用于管理 Controller、HandlerMapping 等 Web 层组件</li>
 *   <li>建立父子关系（子容器可访问父容器的 Bean，反之不行）</li>
 *   <li>注册 DispatcherServlet 并映射到指定路径</li>
 * </ol>
 *
 * @author Arjen Poutsma
 * @author Chris Beams
 * @since 3.2
 */
public abstract class AbstractAnnotationConfigDispatcherServletInitializer
		extends AbstractDispatcherServletInitializer {

	/**
	 * {@inheritDoc}
	 * <p>此实现创建一个 {@link AnnotationConfigWebApplicationContext}，
	 * 并为其提供 {@link #getRootConfigClasses()} 返回的带注解的类。
	 * 如果 {@link #getRootConfigClasses()} 返回 {@code null}，则返回 {@code null}。
	 *
	 * <p>注意：这里只创建了容器实例并注册了配置类，并没有调用 refresh()。
	 * 实际的刷新操作会在 ContextLoaderListener 的 contextInitialized() 方法中执行。
	 *
	 * @return 根应用上下文（父容器），如果不需要则返回 {@code null}
	 */
	@Override
	@Nullable
	protected WebApplicationContext createRootApplicationContext() {
		// 获取开发者提供的根配置类（如 RootConfig.class，包含 @Service、@Repository 等）
		Class<?>[] configClasses = getRootConfigClasses();

		// 如果配置类数组不为空，则创建基于注解的 WebApplicationContext
		if (!ObjectUtils.isEmpty(configClasses)) {
			// 创建 AnnotationConfigWebApplicationContext 实例
			// 这个容器将作为父容器（Root WebApplicationContext）
			AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();

			// 注册配置类（例如：@Configuration 标注的类，或 @Component 标注的组件）
			context.register(configClasses);

			// 返回已注册配置类但尚未刷新的容器实例
			// 注意：容器此时还没有加载 Bean 定义，真正的加载发生在 ContextLoaderListener 中
			return context;
		}
		else {
			// 没有提供根配置类，不创建父容器
			// 这种情况下所有 Bean 都会放在子容器中
			return null;
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>此实现创建一个 {@link AnnotationConfigWebApplicationContext}，
	 * 并为其提供 {@link #getServletConfigClasses()} 返回的带注解的类。
	 *
	 * <p>这个容器将作为 DispatcherServlet 的子容器，专门管理 Web 层组件。
	 * 注意：即使 getServletConfigClasses() 返回 null，也会创建空容器。
	 *
	 * @return Servlet 应用上下文（子容器），永远不为 null
	 */
	@Override
	protected WebApplicationContext createServletApplicationContext() {
		// 创建基于注解的 WebApplicationContext 实例
		// 这个容器将作为子容器（Servlet WebApplicationContext），
		// 专门管理 Web 层组件（如 @Controller、@ControllerAdvice 等）
		AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();

		// 获取开发者提供的 Servlet 配置类（如 WebConfig.class，包含 @Controller、@EnableWebMvc 等）
		Class<?>[] configClasses = getServletConfigClasses();

		// 如果配置类数组不为空，则注册这些配置类
		// 如果为空，则创建一个空的容器（可能仍然会通过其他方式注册组件，如包扫描）
		if (!ObjectUtils.isEmpty(configClasses)) {
			context.register(configClasses);
		}

		// 返回已注册配置类但尚未刷新的容器实例
		// 注意：父子关系的建立发生在 FrameworkServlet 的 initWebApplicationContext() 方法中，
		// 通过调用 wac.setParent(rootContext) 来完成
		return context;
	}

	/**
	 * 指定用于 {@linkplain #createRootApplicationContext() 根应用上下文} 的
	 * {@code @Configuration} 和/或 {@code @Component} 类。
	 *
	 * <p>这些配置类通常包含：
	 * <ul>
	 *   <li>数据源配置（DataSource）</li>
	 *   <li>事务管理器配置（TransactionManager）</li>
	 *   <li>MyBatis 或 JPA 配置</li>
	 *   <li>使用 @Service、@Repository 标注的业务层组件</li>
	 * </ul>
	 *
	 * <p>这些配置类会被注册到父容器（Root WebApplicationContext）中。
	 * 子容器（Servlet WebApplicationContext）可以访问这些 Bean。
	 *
	 * @return 根应用上下文的配置类数组，如果不需要创建和注册根上下文则返回 {@code null}
	 */
	@Nullable
	protected abstract Class<?>[] getRootConfigClasses();

	/**
	 * 指定用于 {@linkplain #createServletApplicationContext() Servlet 应用上下文} 的
	 * {@code @Configuration} 和/或 {@code @Component} 类。
	 *
	 * <p>这些配置类通常包含：
	 * <ul>
	 *   <li>使用 @EnableWebMvc 启用 Spring MVC</li>
	 *   <li>视图解析器配置（ViewResolver）</li>
	 *   <li>消息转换器配置（MessageConverter）</li>
	 *   <li>拦截器配置（Interceptor）</li>
	 *   <li>使用 @Controller、@RestController 标注的 Web 层组件</li>
	 * </ul>
	 *
	 * <p>这些配置类会被注册到子容器（Servlet WebApplicationContext）中。
	 *
	 * <p><b>注意：</b>如果所有配置都通过根配置类指定（即不需要父子容器层级），
	 * 此方法可以返回 {@code null}。此时，DispatcherServlet 会直接使用根容器，
	 * 相当于只有一个容器（类似 Spring Boot 的简化模式）。
	 *
	 * @return Servlet 应用上下文的配置类数组，如果所有配置都通过根配置类指定则返回 {@code null}
	 */
	@Nullable
	protected abstract Class<?>[] getServletConfigClasses();

}