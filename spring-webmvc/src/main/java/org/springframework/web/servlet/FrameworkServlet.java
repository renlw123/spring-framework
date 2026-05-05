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

package org.springframework.web.servlet;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import javax.servlet.DispatcherType;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.SourceFilteringListener;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.context.ConfigurableWebApplicationContext;
import org.springframework.web.context.ConfigurableWebEnvironment;
import org.springframework.web.context.ContextLoader;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.context.support.ServletRequestHandledEvent;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.springframework.web.context.support.XmlWebApplicationContext;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.util.NestedServletException;
import org.springframework.web.util.WebUtils;

/**
 * Base servlet for Spring's web framework. Provides integration with
 * a Spring application context, in a JavaBean-based overall solution.
 *
 * <p>This class offers the following functionality:
 * <ul>
 * <li>Manages a {@link org.springframework.web.context.WebApplicationContext
 * WebApplicationContext} instance per servlet. The servlet's configuration is determined
 * by beans in the servlet's namespace.
 * <li>Publishes events on request processing, whether or not a request is
 * successfully handled.
 * </ul>
 *
 * <p>Subclasses must implement {@link #doService} to handle requests. Because this extends
 * {@link HttpServletBean} rather than HttpServlet directly, bean properties are
 * automatically mapped onto it. Subclasses can override {@link #initFrameworkServlet()}
 * for custom initialization.
 *
 * <p>Detects a "contextClass" parameter at the servlet init-param level,
 * falling back to the default context class,
 * {@link org.springframework.web.context.support.XmlWebApplicationContext
 * XmlWebApplicationContext}, if not found. Note that, with the default
 * {@code FrameworkServlet}, a custom context class needs to implement the
 * {@link org.springframework.web.context.ConfigurableWebApplicationContext
 * ConfigurableWebApplicationContext} SPI.
 *
 * <p>Accepts an optional "contextInitializerClasses" servlet init-param that
 * specifies one or more {@link org.springframework.context.ApplicationContextInitializer
 * ApplicationContextInitializer} classes. The managed web application context will be
 * delegated to these initializers, allowing for additional programmatic configuration,
 * e.g. adding property sources or activating profiles against the {@linkplain
 * org.springframework.context.ConfigurableApplicationContext#getEnvironment() context's
 * environment}. See also {@link org.springframework.web.context.ContextLoader} which
 * supports a "contextInitializerClasses" context-param with identical semantics for
 * the "root" web application context.
 *
 * <p>Passes a "contextConfigLocation" servlet init-param to the context instance,
 * parsing it into potentially multiple file paths which can be separated by any
 * number of commas and spaces, like "test-servlet.xml, myServlet.xml".
 * If not explicitly specified, the context implementation is supposed to build a
 * default location from the namespace of the servlet.
 *
 * <p>Note: In case of multiple config locations, later bean definitions will
 * override ones defined in earlier loaded files, at least when using Spring's
 * default ApplicationContext implementation. This can be leveraged to
 * deliberately override certain bean definitions via an extra XML file.
 *
 * <p>The default namespace is "'servlet-name'-servlet", e.g. "test-servlet" for a
 * servlet-name "test" (leading to a "/WEB-INF/test-servlet.xml" default location
 * with XmlWebApplicationContext). The namespace can also be set explicitly via
 * the "namespace" servlet init-param.
 *
 * <p>As of Spring 3.1, {@code FrameworkServlet} may now be injected with a web
 * application context, rather than creating its own internally. This is useful in Servlet
 * 3.0+ environments, which support programmatic registration of servlet instances. See
 * {@link #FrameworkServlet(WebApplicationContext)} Javadoc for details.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @author Chris Beams
 * @author Rossen Stoyanchev
 * @author Phillip Webb
 * @see #doService
 * @see #setContextClass
 * @see #setContextConfigLocation
 * @see #setContextInitializerClasses
 * @see #setNamespace
 */
@SuppressWarnings("serial")
public abstract class FrameworkServlet extends HttpServletBean implements ApplicationContextAware {

	/**
	 * Suffix for WebApplicationContext namespaces. If a servlet of this class is
	 * given the name "test" in a context, the namespace used by the servlet will
	 * resolve to "test-servlet".
	 */
	public static final String DEFAULT_NAMESPACE_SUFFIX = "-servlet";

	/**
	 * Default context class for FrameworkServlet.
	 * @see org.springframework.web.context.support.XmlWebApplicationContext
	 */
	public static final Class<?> DEFAULT_CONTEXT_CLASS = XmlWebApplicationContext.class;

	/**
	 * Prefix for the ServletContext attribute for the WebApplicationContext.
	 * The completion is the servlet name.
	 */
	public static final String SERVLET_CONTEXT_PREFIX = FrameworkServlet.class.getName() + ".CONTEXT.";

	/**
	 * Any number of these characters are considered delimiters between
	 * multiple values in a single init-param String value.
	 */
	private static final String INIT_PARAM_DELIMITERS = ",; \t\n";


	/** ServletContext attribute to find the WebApplicationContext in. */
	@Nullable
	private String contextAttribute;

	/** WebApplicationContext implementation class to create. */
	private Class<?> contextClass = DEFAULT_CONTEXT_CLASS;

	/** WebApplicationContext id to assign. */
	@Nullable
	private String contextId;

	/** Namespace for this servlet. */
	@Nullable
	private String namespace;

	/** Explicit context config location. */
	@Nullable
	private String contextConfigLocation;

	/** Actual ApplicationContextInitializer instances to apply to the context. */
	private final List<ApplicationContextInitializer<ConfigurableApplicationContext>> contextInitializers =
			new ArrayList<>();

	/** Comma-delimited ApplicationContextInitializer class names set through init param. */
	@Nullable
	private String contextInitializerClasses;

	/** Should we publish the context as a ServletContext attribute?. */
	private boolean publishContext = true;

	/** Should we publish a ServletRequestHandledEvent at the end of each request?. */
	private boolean publishEvents = true;

	/** Expose LocaleContext and RequestAttributes as inheritable for child threads?. */
	private boolean threadContextInheritable = false;

	/** Should we dispatch an HTTP OPTIONS request to {@link #doService}?. */
	private boolean dispatchOptionsRequest = false;

	/** Should we dispatch an HTTP TRACE request to {@link #doService}?. */
	private boolean dispatchTraceRequest = false;

	/** Whether to log potentially sensitive info (request params at DEBUG + headers at TRACE). */
	private boolean enableLoggingRequestDetails = false;

	/** WebApplicationContext for this servlet. */
	@Nullable
	private WebApplicationContext webApplicationContext;

	/** If the WebApplicationContext was injected via {@link #setApplicationContext}. */
	private boolean webApplicationContextInjected = false;

	/** Flag used to detect whether onRefresh has already been called. */
	private volatile boolean refreshEventReceived;

	/** Monitor for synchronized onRefresh execution. */
	private final Object onRefreshMonitor = new Object();


	/**
	 * Create a new {@code FrameworkServlet} that will create its own internal web
	 * application context based on defaults and values provided through servlet
	 * init-params. Typically used in Servlet 2.5 or earlier environments, where the only
	 * option for servlet registration is through {@code web.xml} which requires the use
	 * of a no-arg constructor.
	 * <p>Calling {@link #setContextConfigLocation} (init-param 'contextConfigLocation')
	 * will dictate which XML files will be loaded by the
	 * {@linkplain #DEFAULT_CONTEXT_CLASS default XmlWebApplicationContext}
	 * <p>Calling {@link #setContextClass} (init-param 'contextClass') overrides the
	 * default {@code XmlWebApplicationContext} and allows for specifying an alternative class,
	 * such as {@code AnnotationConfigWebApplicationContext}.
	 * <p>Calling {@link #setContextInitializerClasses} (init-param 'contextInitializerClasses')
	 * indicates which {@link ApplicationContextInitializer} classes should be used to
	 * further configure the internal application context prior to refresh().
	 * @see #FrameworkServlet(WebApplicationContext)
	 */
	public FrameworkServlet() {
	}

	/**
	 * Create a new {@code FrameworkServlet} with the given web application context. This
	 * constructor is useful in Servlet 3.0+ environments where instance-based registration
	 * of servlets is possible through the {@link ServletContext#addServlet} API.
	 * <p>Using this constructor indicates that the following properties / init-params
	 * will be ignored:
	 * <ul>
	 * <li>{@link #setContextClass(Class)} / 'contextClass'</li>
	 * <li>{@link #setContextConfigLocation(String)} / 'contextConfigLocation'</li>
	 * <li>{@link #setContextAttribute(String)} / 'contextAttribute'</li>
	 * <li>{@link #setNamespace(String)} / 'namespace'</li>
	 * </ul>
	 * <p>The given web application context may or may not yet be {@linkplain
	 * ConfigurableApplicationContext#refresh() refreshed}. If it (a) is an implementation
	 * of {@link ConfigurableWebApplicationContext} and (b) has <strong>not</strong>
	 * already been refreshed (the recommended approach), then the following will occur:
	 * <ul>
	 * <li>If the given context does not already have a {@linkplain
	 * ConfigurableApplicationContext#setParent parent}, the root application context
	 * will be set as the parent.</li>
	 * <li>If the given context has not already been assigned an {@linkplain
	 * ConfigurableApplicationContext#setId id}, one will be assigned to it</li>
	 * <li>{@code ServletContext} and {@code ServletConfig} objects will be delegated to
	 * the application context</li>
	 * <li>{@link #postProcessWebApplicationContext} will be called</li>
	 * <li>Any {@link ApplicationContextInitializer ApplicationContextInitializers} specified through the
	 * "contextInitializerClasses" init-param or through the {@link
	 * #setContextInitializers} property will be applied.</li>
	 * <li>{@link ConfigurableApplicationContext#refresh refresh()} will be called</li>
	 * </ul>
	 * If the context has already been refreshed or does not implement
	 * {@code ConfigurableWebApplicationContext}, none of the above will occur under the
	 * assumption that the user has performed these actions (or not) per his or her
	 * specific needs.
	 * <p>See {@link org.springframework.web.WebApplicationInitializer} for usage examples.
	 * @param webApplicationContext the context to use
	 * @see #initWebApplicationContext
	 * @see #configureAndRefreshWebApplicationContext
	 * @see org.springframework.web.WebApplicationInitializer
	 */
	public FrameworkServlet(WebApplicationContext webApplicationContext) {
		this.webApplicationContext = webApplicationContext;
	}


	/**
	 * Set the name of the ServletContext attribute which should be used to retrieve the
	 * {@link WebApplicationContext} that this servlet is supposed to use.
	 */
	public void setContextAttribute(@Nullable String contextAttribute) {
		this.contextAttribute = contextAttribute;
	}

	/**
	 * Return the name of the ServletContext attribute which should be used to retrieve the
	 * {@link WebApplicationContext} that this servlet is supposed to use.
	 */
	@Nullable
	public String getContextAttribute() {
		return this.contextAttribute;
	}

	/**
	 * Set a custom context class. This class must be of type
	 * {@link org.springframework.web.context.WebApplicationContext}.
	 * <p>When using the default FrameworkServlet implementation,
	 * the context class must also implement the
	 * {@link org.springframework.web.context.ConfigurableWebApplicationContext}
	 * interface.
	 * @see #createWebApplicationContext
	 */
	public void setContextClass(Class<?> contextClass) {
		this.contextClass = contextClass;
	}

	/**
	 * Return the custom context class.
	 */
	public Class<?> getContextClass() {
		return this.contextClass;
	}

	/**
	 * Specify a custom WebApplicationContext id,
	 * to be used as serialization id for the underlying BeanFactory.
	 */
	public void setContextId(@Nullable String contextId) {
		this.contextId = contextId;
	}

	/**
	 * Return the custom WebApplicationContext id, if any.
	 */
	@Nullable
	public String getContextId() {
		return this.contextId;
	}

	/**
	 * Set a custom namespace for this servlet,
	 * to be used for building a default context config location.
	 */
	public void setNamespace(String namespace) {
		this.namespace = namespace;
	}

	/**
	 * Return the namespace for this servlet, falling back to default scheme if
	 * no custom namespace was set: e.g. "test-servlet" for a servlet named "test".
	 */
	public String getNamespace() {
		return (this.namespace != null ? this.namespace : getServletName() + DEFAULT_NAMESPACE_SUFFIX);
	}

	/**
	 * Set the context config location explicitly, instead of relying on the default
	 * location built from the namespace. This location string can consist of
	 * multiple locations separated by any number of commas and spaces.
	 */
	public void setContextConfigLocation(@Nullable String contextConfigLocation) {
		this.contextConfigLocation = contextConfigLocation;
	}

	/**
	 * Return the explicit context config location, if any.
	 */
	@Nullable
	public String getContextConfigLocation() {
		return this.contextConfigLocation;
	}

	/**
	 * Specify which {@link ApplicationContextInitializer} instances should be used
	 * to initialize the application context used by this {@code FrameworkServlet}.
	 * @see #configureAndRefreshWebApplicationContext
	 * @see #applyInitializers
	 */
	@SuppressWarnings("unchecked")
	public void setContextInitializers(@Nullable ApplicationContextInitializer<?>... initializers) {
		if (initializers != null) {
			for (ApplicationContextInitializer<?> initializer : initializers) {
				this.contextInitializers.add((ApplicationContextInitializer<ConfigurableApplicationContext>) initializer);
			}
		}
	}

	/**
	 * Specify the set of fully-qualified {@link ApplicationContextInitializer} class
	 * names, per the optional "contextInitializerClasses" servlet init-param.
	 * @see #configureAndRefreshWebApplicationContext(ConfigurableWebApplicationContext)
	 * @see #applyInitializers(ConfigurableApplicationContext)
	 */
	public void setContextInitializerClasses(String contextInitializerClasses) {
		this.contextInitializerClasses = contextInitializerClasses;
	}

	/**
	 * Set whether to publish this servlet's context as a ServletContext attribute,
	 * available to all objects in the web container. Default is "true".
	 * <p>This is especially handy during testing, although it is debatable whether
	 * it's good practice to let other application objects access the context this way.
	 */
	public void setPublishContext(boolean publishContext) {
		this.publishContext = publishContext;
	}

	/**
	 * Set whether this servlet should publish a ServletRequestHandledEvent at the end
	 * of each request. Default is "true"; can be turned off for a slight performance
	 * improvement, provided that no ApplicationListeners rely on such events.
	 * @see org.springframework.web.context.support.ServletRequestHandledEvent
	 */
	public void setPublishEvents(boolean publishEvents) {
		this.publishEvents = publishEvents;
	}

	/**
	 * Set whether to expose the LocaleContext and RequestAttributes as inheritable
	 * for child threads (using an {@link java.lang.InheritableThreadLocal}).
	 * <p>Default is "false", to avoid side effects on spawned background threads.
	 * Switch this to "true" to enable inheritance for custom child threads which
	 * are spawned during request processing and only used for this request
	 * (that is, ending after their initial task, without reuse of the thread).
	 * <p><b>WARNING:</b> Do not use inheritance for child threads if you are
	 * accessing a thread pool which is configured to potentially add new threads
	 * on demand (e.g. a JDK {@link java.util.concurrent.ThreadPoolExecutor}),
	 * since this will expose the inherited context to such a pooled thread.
	 */
	public void setThreadContextInheritable(boolean threadContextInheritable) {
		this.threadContextInheritable = threadContextInheritable;
	}

	/**
	 * Set whether this servlet should dispatch an HTTP OPTIONS request to
	 * the {@link #doService} method.
	 * <p>Default in the {@code FrameworkServlet} is "false", applying
	 * {@link javax.servlet.http.HttpServlet}'s default behavior (i.e.enumerating
	 * all standard HTTP request methods as a response to the OPTIONS request).
	 * Note however that as of 4.3 the {@code DispatcherServlet} sets this
	 * property to "true" by default due to its built-in support for OPTIONS.
	 * <p>Turn this flag on if you prefer OPTIONS requests to go through the
	 * regular dispatching chain, just like other HTTP requests. This usually
	 * means that your controllers will receive those requests; make sure
	 * that those endpoints are actually able to handle an OPTIONS request.
	 * <p>Note that HttpServlet's default OPTIONS processing will be applied
	 * in any case if your controllers happen to not set the 'Allow' header
	 * (as required for an OPTIONS response).
	 */
	public void setDispatchOptionsRequest(boolean dispatchOptionsRequest) {
		this.dispatchOptionsRequest = dispatchOptionsRequest;
	}

	/**
	 * Set whether this servlet should dispatch an HTTP TRACE request to
	 * the {@link #doService} method.
	 * <p>Default is "false", applying {@link javax.servlet.http.HttpServlet}'s
	 * default behavior (i.e. reflecting the message received back to the client).
	 * <p>Turn this flag on if you prefer TRACE requests to go through the
	 * regular dispatching chain, just like other HTTP requests. This usually
	 * means that your controllers will receive those requests; make sure
	 * that those endpoints are actually able to handle a TRACE request.
	 * <p>Note that HttpServlet's default TRACE processing will be applied
	 * in any case if your controllers happen to not generate a response
	 * of content type 'message/http' (as required for a TRACE response).
	 */
	public void setDispatchTraceRequest(boolean dispatchTraceRequest) {
		this.dispatchTraceRequest = dispatchTraceRequest;
	}

	/**
	 * Whether to log request params at DEBUG level, and headers at TRACE level.
	 * Both may contain sensitive information.
	 * <p>By default set to {@code false} so that request details are not shown.
	 * @param enable whether to enable or not
	 * @since 5.1
	 */
	public void setEnableLoggingRequestDetails(boolean enable) {
		this.enableLoggingRequestDetails = enable;
	}

	/**
	 * Whether logging of potentially sensitive, request details at DEBUG and
	 * TRACE level is allowed.
	 * @since 5.1
	 */
	public boolean isEnableLoggingRequestDetails() {
		return this.enableLoggingRequestDetails;
	}

	/**
	 * Called by Spring via {@link ApplicationContextAware} to inject the current
	 * application context. This method allows FrameworkServlets to be registered as
	 * Spring beans inside an existing {@link WebApplicationContext} rather than
	 * {@link #findWebApplicationContext() finding} a
	 * {@link org.springframework.web.context.ContextLoaderListener bootstrapped} context.
	 * <p>Primarily added to support use in embedded servlet containers.
	 * @since 4.0
	 */
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) {
		if (this.webApplicationContext == null && applicationContext instanceof WebApplicationContext) {
			this.webApplicationContext = (WebApplicationContext) applicationContext;
			this.webApplicationContextInjected = true;
		}
	}


	/**
	 * 重写自 {@link HttpServletBean} 的方法，在 Bean 属性设置完成后被调用。
	 * 创建此 Servlet 的 WebApplicationContext。
	 *
	 * <p>这是 FrameworkServlet 的核心初始化方法，负责创建和刷新 Spring Web 容器。
	 */
	@Override
	protected final void initServletBean() throws ServletException {
		// ========== 1. 日志记录：初始化开始 ==========
		getServletContext().log("Initializing Spring " + getClass().getSimpleName() + " '" + getServletName() + "'");
		if (logger.isInfoEnabled()) {
			logger.info("Initializing Servlet '" + getServletName() + "'");
		}
		long startTime = System.currentTimeMillis();

		try {
			// ========== 2. ★ 核心：初始化 WebApplicationContext ==========
			// 创建或刷新 Spring 容器（即 IoC 容器）
			this.webApplicationContext = initWebApplicationContext();

			// ========== 3. 模板方法：留给子类扩展 ==========
			// FrameworkServlet 中为空实现，DispatcherServlet 会重写此方法
			initFrameworkServlet();
		}
		catch (ServletException | RuntimeException ex) {
			logger.error("Context initialization failed", ex);
			throw ex;
		}

		// ========== 4. 日志：请求参数/头信息处理策略 ==========
		if (logger.isDebugEnabled()) {
			String value = this.enableLoggingRequestDetails ?
					"shown which may lead to unsafe logging of potentially sensitive data" :
					"masked to prevent unsafe logging of potentially sensitive data";
			logger.debug("enableLoggingRequestDetails='" + this.enableLoggingRequestDetails +
					"': request parameters and headers will be " + value);
		}

		// ========== 5. 日志记录：初始化完成 ==========
		if (logger.isInfoEnabled()) {
			logger.info("Completed initialization in " + (System.currentTimeMillis() - startTime) + " ms");
		}
	}

	/**
	 * 初始化并发布当前Servlet的WebApplicationContext。
	 * <p>实际创建context的工作委托给 {@link #createWebApplicationContext} 方法。
	 * 子类可以重写此方法以提供自定义行为。
	 * @return WebApplicationContext实例
	 * @see #FrameworkServlet(WebApplicationContext)
	 * @see #setContextClass
	 * @see #setContextConfigLocation
	 */
	protected WebApplicationContext initWebApplicationContext() {
		// 1. 获取根WebApplicationContext（通常由ContextLoaderListener创建，存在于ServletContext中）
		WebApplicationContext rootContext =
				WebApplicationContextUtils.getWebApplicationContext(getServletContext());
		WebApplicationContext wac = null;

		// 2. 情况一：当前Servlet在构造时已经被注入了WebApplicationContext实例（通过构造方法注入）
		if (this.webApplicationContext != null) {
			// 直接使用注入的实例
			wac = this.webApplicationContext;
			if (wac instanceof ConfigurableWebApplicationContext) {
				ConfigurableWebApplicationContext cwac = (ConfigurableWebApplicationContext) wac;
				// 如果context尚未刷新（未激活），则进行配置和刷新操作
				if (!cwac.isActive()) {
					// 若没有设置父context，则将根rootContext设置为父context
					//┌─────────────────────────────────────┐
					//│    父容器 (Root WebApplicationContext)  │
					//│  - Service, Repository, DataSource   │
					//│  - @Service, @Repository, @Component │
					//└─────────────────┬───────────────────┘
					//                  │ 继承（父子关系）
					//                  ▼
					//┌─────────────────────────────────────┐
					//│  子容器 (Servlet WebApplicationContext) │
					//│  - Controller, HandlerMapping        │
					//│  - @Controller, @RequestMapping      │
					//└─────────────────────────────────────┘
					//// 父容器配置
					//@Configuration
					//@ComponentScan(basePackages = "com.example.service")
					//public class RootConfig { }
					//
					//// 子容器配置
					//@Configuration
					//@ComponentScan(basePackages = "com.example.controller")
					//@EnableWebMvc
					//public class ServletConfig { }
					//
					//// 初始化
					//public class MyWebAppInitializer implements WebApplicationInitializer {
					//    @Override
					//    public void onStartup(ServletContext container) {
					//        // 父容器
					//        AnnotationConfigWebApplicationContext rootContext = new AnnotationConfigWebApplicationContext();
					//        rootContext.register(RootConfig.class);
					//        container.addListener(new ContextLoaderListener(rootContext));
					//
					//        // 子容器
					//        AnnotationConfigWebApplicationContext servletContext = new AnnotationConfigWebApplicationContext();
					//        servletContext.register(ServletConfig.class);
					//        servletContext.setParent(rootContext);  // 设置父子关系
					//
					//        DispatcherServlet servlet = new DispatcherServlet(servletContext);
					//        ServletRegistration.Dynamic dynamic = container.addServlet("dispatcher", servlet);
					//        dynamic.addMapping("/");
					//    }
					//}
					if (cwac.getParent() == null) {
						cwac.setParent(rootContext);
					}
					// 配置并刷新这个WebApplicationContext
					configureAndRefreshWebApplicationContext(cwac);
				}
			}
		}

		// 3. 情况二：通过构造方法未注入context，则尝试从ServletContext中查找是否有已注册的context（按属性名查找）
		if (wac == null) {
			wac = findWebApplicationContext();
		}

		// 4. 情况三：未找到任何现有的context，则创建一个本地的WebApplicationContext
		if (wac == null) {
			wac = createWebApplicationContext(rootContext);
		}

		// 5. 如果尚未接收到刷新事件（refreshEventReceived为false）
		//    （可能是context不是ConfigurableApplicationContext，或者注入的context在注入时已经刷新过）
		//    则手动触发onRefresh回调方法，用于执行Spring MVC组件的初始化（如HandlerMapping、HandlerAdapter、ViewResolver等）
		if (!this.refreshEventReceived) {
			synchronized (this.onRefreshMonitor) {
				onRefresh(wac);
			}
		}

		// 6. 如果需要将当前WebApplicationContext发布到ServletContext的属性中（通常是用于其他组件获取）
		if (this.publishContext) {
			String attrName = getServletContextAttributeName();  // 属性名通常为：org.springframework.web.servlet.FrameworkServlet.CONTEXT.当前Servlet名称
			getServletContext().setAttribute(attrName, wac);
		}

		return wac;
	}

	/**
	 * Retrieve a {@code WebApplicationContext} from the {@code ServletContext}
	 * attribute with the {@link #setContextAttribute configured name}. The
	 * {@code WebApplicationContext} must have already been loaded and stored in the
	 * {@code ServletContext} before this servlet gets initialized (or invoked).
	 * <p>Subclasses may override this method to provide a different
	 * {@code WebApplicationContext} retrieval strategy.
	 * @return the WebApplicationContext for this servlet, or {@code null} if not found
	 * @see #getContextAttribute()
	 */
	@Nullable
	protected WebApplicationContext findWebApplicationContext() {
		String attrName = getContextAttribute();
		if (attrName == null) {
			return null;
		}
		WebApplicationContext wac =
				WebApplicationContextUtils.getWebApplicationContext(getServletContext(), attrName);
		if (wac == null) {
			throw new IllegalStateException("No WebApplicationContext found: initializer not registered?");
		}
		return wac;
	}

	/**
	 * 为当前 Servlet 实例化 WebApplicationContext（子容器）。
	 *
	 * <p>这是 Spring MVC 父子容器体系中**子容器创建的核心实现**。
	 * 父容器（Root WebApplicationContext）由 ContextLoaderListener 创建，
	 * 而子容器（Servlet WebApplicationContext）由此方法创建。
	 *
	 * <p>容器类型可以是：
	 * <ul>
	 *   <li>默认的 {@link org.springframework.web.context.support.XmlWebApplicationContext}</li>
	 *   <li>通过 {@link #setContextClass} 设置的自定义上下文类</li>
	 * </ul>
	 *
	 * <p>此实现要求自定义上下文类必须实现
	 * {@link org.springframework.web.context.ConfigurableWebApplicationContext} 接口。
	 * 子类可以重写此方法以提供自定义的创建逻辑。
	 *
	 * <p><b>重要：</b>
	 * <ul>
	 *   <li>不要忘记将当前 Servlet 实例注册为创建的应用上下文的监听器
	 *       （用于触发生命周期回调 {@link #onRefresh}）</li>
	 *   <li>在返回上下文实例之前，必须调用
	 *       {@link org.springframework.context.ConfigurableApplicationContext#refresh()}</li>
	 * </ul>
	 *
	 * @param parent 要使用的父 ApplicationContext（即根容器），如果没有则为 {@code null}
	 * @return 为当前 Servlet 创建的 WebApplicationContext（子容器）
	 * @see org.springframework.web.context.support.XmlWebApplicationContext
	 */
	protected WebApplicationContext createWebApplicationContext(@Nullable ApplicationContext parent) {

		// ========== 1. 获取容器类型 ==========
		// 通过 getContextClass() 获取配置的上下文类
		// 默认返回 XmlWebApplicationContext.class
		// 可通过 setContextClass() 方法自定义，如 AnnotationConfigWebApplicationContext
		Class<?> contextClass = getContextClass();

		// ========== 2. 类型检查 ==========
		// 确保容器类实现了 ConfigurableWebApplicationContext 接口
		// 该接口提供了 Web 环境所需的核心能力：
		//   - setServletContext(): 设置 Servlet 上下文
		//   - setConfigLocation(): 设置配置文件位置
		//   - refresh(): 刷新容器
		if (!ConfigurableWebApplicationContext.class.isAssignableFrom(contextClass)) {
			throw new ApplicationContextException(
					"Fatal initialization error in servlet with name '" + getServletName() +
							"': custom WebApplicationContext class [" + contextClass.getName() +
							"] is not of type ConfigurableWebApplicationContext");
		}

		// ========== 3. 实例化容器 ==========
		// 通过 BeanUtils 的 instantiateClass 方法创建容器实例
		// 这类似于调用 contextClass.newInstance()，但处理了异常情况
		ConfigurableWebApplicationContext wac =
				(ConfigurableWebApplicationContext) BeanUtils.instantiateClass(contextClass);

		// ========== 4. 设置环境 ==========
		// 将当前 Servlet 的环境配置（如属性源、Profile 等）传递给子容器
		// 这样子容器可以继承父容器的环境配置
		wac.setEnvironment(getEnvironment());

		// ========== 5. 🔗 建立父子关系（关键步骤） ==========
		// 这是父子容器体系的核心：将父容器设置为子容器的父级
		// 作用：
		//   - 子容器可以访问父容器中的所有 Bean（如 Service、Repository）
		//   - 父容器无法访问子容器中的 Bean（如 Controller）
		//   - 子容器中找不到的 Bean 会委托给父容器查找
		wac.setParent(parent);

		// ========== 6. 设置配置文件位置 ==========
		// 从 Servlet 的 init-param 中获取 contextConfigLocation
		// 例如 web.xml 中的配置：
		//   <init-param>
		//       <param-name>contextConfigLocation</param-name>
		//       <param-value>/WEB-INF/dispatcher-servlet.xml</param-value>
		//   </init-param>
		String configLocation = getContextConfigLocation();
		if (configLocation != null) {
			wac.setConfigLocation(configLocation);
		}

		// ========== 7. 配置并刷新容器 ==========
		// 这个方法内部会：
		//   - 设置 ServletContext
		//   - 应用任何自定义的配置
		//   - 🔥 调用 wac.refresh() 启动容器
		//     refresh() 会触发：
		//       * 加载 Bean 定义（扫描 @Controller、@Component 等）
		//       * 实例化单例 Bean
		//       * 初始化 MVC 组件（HandlerMapping、ViewResolver 等）
		configureAndRefreshWebApplicationContext(wac);

		// ========== 8. 返回创建好的容器 ==========
		return wac;
	}

	/**
	 * 配置并刷新当前 Servlet 的 WebApplicationContext（子容器）。
	 *
	 * <p>这是子容器启动流程中的关键方法，负责在容器刷新（refresh()）之前
	 * 完成所有必要的配置工作。
	 *
	 * <p>主要职责：
	 * <ul>
	 *   <li>设置容器的唯一标识 ID</li>
	 *   <li>绑定 ServletContext 和 ServletConfig</li>
	 *   <li>设置命名空间（用于默认配置文件路径）</li>
	 *   <li>注册上下文刷新监听器（触发 onRefresh 回调）</li>
	 *   <li>初始化 Web 环境属性源</li>
	 *   <li>执行后置处理和初始化器</li>
	 *   <li>刷新容器（加载 Bean、实例化单例等）</li>
	 * </ul>
	 *
	 * <p><b>注意：</b>此方法与父容器配置方法类似，但增加了 ServletConfig 的绑定
	 * 和 ContextRefreshListener 的注册，这是子容器特有的。
	 *
	 * @param wac 需要配置和刷新的 WebApplicationContext（子容器）
	 */
	protected void configureAndRefreshWebApplicationContext(ConfigurableWebApplicationContext wac) {

		// ========== 1. 设置容器的唯一标识 ID ==========
		// 检查当前容器的 ID 是否是默认的（ObjectUtils.identityToString(wac) 返回如 "org.springframework...@1234" 格式）
		if (ObjectUtils.identityToString(wac).equals(wac.getId())) {
			// 如果设置了自定义 contextId，使用自定义值
			if (this.contextId != null) {
				wac.setId(this.contextId);
			}
			else {
				// 生成默认 ID：org.springframework.web.context.WebApplicationContext: + 应用路径 + / + servlet名称
				// 例如：org.springframework.web.context.WebApplicationContext:/myapp/dispatcher
				wac.setId(ConfigurableWebApplicationContext.APPLICATION_CONTEXT_ID_PREFIX +
						ObjectUtils.getDisplayString(getServletContext().getContextPath()) + '/' + getServletName());
			}
		}

		// ========== 2. 绑定 Servlet 相关对象 ==========
		// 设置 ServletContext，让容器可以访问 Servlet 环境
		wac.setServletContext(getServletContext());

		// 设置 ServletConfig，让容器可以访问 Servlet 配置信息
		// 例如 web.xml 中配置的 <init-param>
		wac.setServletConfig(getServletConfig());

		// 设置命名空间，用于确定默认的配置文件路径
		// 默认命名空间是 servlet 名称，如 "dispatcher"
		// 默认配置文件路径：/WEB-INF/[servlet-name]-servlet.xml
		wac.setNamespace(getNamespace());

		// ========== 3. 注册上下文刷新监听器 ==========
		// 这是关键！添加一个 SourceFilteringListener 包装的 ContextRefreshListener
		//
		// ContextRefreshListener 的作用：
		//   - 监听容器的刷新完成事件（ContextRefreshedEvent）
		//   - 当容器刷新完成后，触发 FrameworkServlet 的 onRefresh() 方法
		//   - onRefresh() 会初始化 DispatcherServlet 的核心组件（HandlerMapping、ViewResolver 等）
		//
		// 为什么需要 SourceFilteringListener？
		//   - 确保只有当前容器（而非父容器）的事件才会触发回调
		//   - 避免父容器刷新时错误地触发子 Servlet 的 onRefresh
		wac.addApplicationListener(new SourceFilteringListener(wac, new ContextRefreshListener()));

		// ========== 4. 初始化 Web 环境属性源 ==========
		// 提前初始化属性源，确保在刷新前的后置处理中能使用 Servlet 属性源
		//
		// 这样做的好处：
		//   - 可以在 Bean 定义加载阶段就使用 ServletContext 和 ServletConfig 中的属性
		//   - 支持在 @Value 注解中使用 web.xml 配置的参数
		//   - 支持在配置类中使用 ${...} 占位符引用 Servlet 初始化参数
		ConfigurableEnvironment env = wac.getEnvironment();
		if (env instanceof ConfigurableWebEnvironment) {
			// 初始化 Web 环境属性源，会添加：
			//   - servletContextInitParams: web.xml 中的 <context-param>
			//   - servletConfigInitParams: web.xml 中的 <init-param>
			//   - systemProperties: System.getProperties()
			//   - systemEnvironment: System.getenv()
			((ConfigurableWebEnvironment) env).initPropertySources(getServletContext(), getServletConfig());
		}

		// ========== 5. 执行后置处理（扩展点） ==========
		// 这是一个模板方法，允许在当前类或子类中对容器进行额外的配置
		// 例如：注册自定义的 BeanPostProcessor、添加属性源等
		postProcessWebApplicationContext(wac);

		// ========== 6. 应用应用上下文初始化器 ==========
		// 执行所有注册的 ApplicationContextInitializer
		// 这些初始化器可以通过 setContextInitializers() 设置
		// 用于在容器刷新前进行额外的配置
		applyInitializers(wac);

		// ========== 7. 🔥 核心：刷新容器 ==========
		// 这是 Spring IoC 容器的启动入口，会执行以下关键步骤：
		//
		// 【refresh() 内部核心流程】
		// ┌─────────────────────────────────────────────────────────────┐
		// │ ① prepareRefresh()          - 初始化属性源、验证环境        │
		// │ ② obtainFreshBeanFactory()  - 获取/创建 BeanFactory        │
		// │ ③ prepareBeanFactory()      - 配置 BeanFactory（ClassLoader等）│
		// │ ④ postProcessBeanFactory()  - BeanFactory 后置处理         │
		// │ ⑤ invokeBeanFactoryPostProcessors() - 执行 BeanFactoryPostProcessor │
		// │ ⑥ registerBeanPostProcessors() - 注册 BeanPostProcessor    │
		// │ ⑦ initMessageSource()       - 初始化国际化消息源            │
		// │ ⑧ initApplicationEventMulticaster() - 初始化事件广播器     │
		// │ ⑨ onRefresh()               - 子类扩展点（Web容器特有）    │
		// │ ⑩ registerListeners()       - 注册事件监听器               │
		// │ ⑪ finishBeanFactoryInitialization() - 实例化所有单例 Bean │
		// │ ⑫ finishRefresh()           - 完成刷新，发布事件           │
		// └─────────────────────────────────────────────────────────────┘
		//
		// 对于子容器而言，这一步会：
		//   - 扫描并加载 @Controller、@RestController 等 Web 层组件
		//   - 实例化 HandlerMapping、HandlerAdapter、ViewResolver 等 MVC 组件
		//   - 执行依赖注入（注入父容器中的 Service）
		//   - 容器刷新完成后，会发布 ContextRefreshedEvent 事件
		//   - 注册的 ContextRefreshListener 会捕获该事件，触发 onRefresh()
		wac.refresh();
	}

	/**
	 * 为当前 Servlet 实例化 WebApplicationContext（子容器）。
	 *
	 * <p>这是 Spring MVC 父子容器体系中**子容器创建的核心入口**。
	 * 该方法会创建一个新的 WebApplicationContext 实例，并建立与父容器的父子关系。
	 *
	 * <p>创建的容器类型可以是：
	 * <ul>
	 *   <li>默认的 {@link org.springframework.web.context.support.XmlWebApplicationContext}</li>
	 *   <li>通过 {@link #setContextClass} 设置的自定义上下文类</li>
	 * </ul>
	 *
	 * <p>调用位置：在 {@link FrameworkServlet#initWebApplicationContext()} 方法中，
	 * 当没有现成的 WebApplicationContext 可用时（即 wac == null），
	 * 会调用此方法来创建新的子容器。
	 *
	 * <p>方法作用：
	 * <ol>
	 *   <li>创建 WebApplicationContext 实例</li>
	 *   <li>设置父容器（建立父子关系，这是关键！）</li>
	 *   <li>配置并刷新容器（加载 Controller、HandlerMapping 等 Web 层组件）</li>
	 * </ol>
	 *
	 * @param parent 要使用的父 WebApplicationContext（即根容器），如果没有则为 {@code null}
	 * @return 为当前 Servlet 创建的 WebApplicationContext（子容器）
	 * @see org.springframework.web.context.support.XmlWebApplicationContext
	 * @see #createWebApplicationContext(ApplicationContext)
	 */
	protected WebApplicationContext createWebApplicationContext(@Nullable WebApplicationContext parent) {
		// 这是一个简单的委托方法，将参数类型从 WebApplicationContext 转换为 ApplicationContext
		// 然后调用真正的创建方法
		//
		// 为什么需要这个转换？
		// 因为父容器可能是任何类型的 ApplicationContext，
		// 而不仅仅是 WebApplicationContext（虽然在实际场景中都是 WebApplicationContext）
		return createWebApplicationContext((ApplicationContext) parent);
	}

	/**
	 * Post-process the given WebApplicationContext before it is refreshed
	 * and activated as context for this servlet.
	 * <p>The default implementation is empty. {@code refresh()} will
	 * be called automatically after this method returns.
	 * <p>Note that this method is designed to allow subclasses to modify the application
	 * context, while {@link #initWebApplicationContext} is designed to allow
	 * end-users to modify the context through the use of
	 * {@link ApplicationContextInitializer ApplicationContextInitializers}.
	 * @param wac the configured WebApplicationContext (not refreshed yet)
	 * @see #createWebApplicationContext
	 * @see #initWebApplicationContext
	 * @see ConfigurableWebApplicationContext#refresh()
	 */
	protected void postProcessWebApplicationContext(ConfigurableWebApplicationContext wac) {
	}

	/**
	 * Delegate the WebApplicationContext before it is refreshed to any
	 * {@link ApplicationContextInitializer} instances specified by the
	 * "contextInitializerClasses" servlet init-param.
	 * <p>See also {@link #postProcessWebApplicationContext}, which is designed to allow
	 * subclasses (as opposed to end-users) to modify the application context, and is
	 * called immediately before this method.
	 * @param wac the configured WebApplicationContext (not refreshed yet)
	 * @see #createWebApplicationContext
	 * @see #postProcessWebApplicationContext
	 * @see ConfigurableApplicationContext#refresh()
	 */
	protected void applyInitializers(ConfigurableApplicationContext wac) {
		String globalClassNames = getServletContext().getInitParameter(ContextLoader.GLOBAL_INITIALIZER_CLASSES_PARAM);
		if (globalClassNames != null) {
			for (String className : StringUtils.tokenizeToStringArray(globalClassNames, INIT_PARAM_DELIMITERS)) {
				this.contextInitializers.add(loadInitializer(className, wac));
			}
		}

		if (this.contextInitializerClasses != null) {
			for (String className : StringUtils.tokenizeToStringArray(this.contextInitializerClasses, INIT_PARAM_DELIMITERS)) {
				this.contextInitializers.add(loadInitializer(className, wac));
			}
		}

		AnnotationAwareOrderComparator.sort(this.contextInitializers);
		for (ApplicationContextInitializer<ConfigurableApplicationContext> initializer : this.contextInitializers) {
			initializer.initialize(wac);
		}
	}

	@SuppressWarnings("unchecked")
	private ApplicationContextInitializer<ConfigurableApplicationContext> loadInitializer(
			String className, ConfigurableApplicationContext wac) {
		try {
			Class<?> initializerClass = ClassUtils.forName(className, wac.getClassLoader());
			Class<?> initializerContextClass =
					GenericTypeResolver.resolveTypeArgument(initializerClass, ApplicationContextInitializer.class);
			if (initializerContextClass != null && !initializerContextClass.isInstance(wac)) {
				throw new ApplicationContextException(String.format(
						"Could not apply context initializer [%s] since its generic parameter [%s] " +
						"is not assignable from the type of application context used by this " +
						"framework servlet: [%s]", initializerClass.getName(), initializerContextClass.getName(),
						wac.getClass().getName()));
			}
			return BeanUtils.instantiateClass(initializerClass, ApplicationContextInitializer.class);
		}
		catch (ClassNotFoundException ex) {
			throw new ApplicationContextException(String.format("Could not load class [%s] specified " +
					"via 'contextInitializerClasses' init-param", className), ex);
		}
	}

	/**
	 * Return the ServletContext attribute name for this servlet's WebApplicationContext.
	 * <p>The default implementation returns
	 * {@code SERVLET_CONTEXT_PREFIX + servlet name}.
	 * @see #SERVLET_CONTEXT_PREFIX
	 * @see #getServletName
	 */
	public String getServletContextAttributeName() {
		return SERVLET_CONTEXT_PREFIX + getServletName();
	}

	/**
	 * Return this servlet's WebApplicationContext.
	 */
	@Nullable
	public final WebApplicationContext getWebApplicationContext() {
		return this.webApplicationContext;
	}


	/**
	 * This method will be invoked after any bean properties have been set and
	 * the WebApplicationContext has been loaded. The default implementation is empty;
	 * subclasses may override this method to perform any initialization they require.
	 * @throws ServletException in case of an initialization exception
	 */
	protected void initFrameworkServlet() throws ServletException {
	}

	/**
	 * Refresh this servlet's application context, as well as the
	 * dependent state of the servlet.
	 * @see #getWebApplicationContext()
	 * @see org.springframework.context.ConfigurableApplicationContext#refresh()
	 */
	public void refresh() {
		WebApplicationContext wac = getWebApplicationContext();
		if (!(wac instanceof ConfigurableApplicationContext)) {
			throw new IllegalStateException("WebApplicationContext does not support refresh: " + wac);
		}
		((ConfigurableApplicationContext) wac).refresh();
	}

	/**
	 * Callback that receives refresh events from this servlet's WebApplicationContext.
	 * <p>The default implementation calls {@link #onRefresh},
	 * triggering a refresh of this servlet's context-dependent state.
	 * @param event the incoming ApplicationContext event
	 */
	public void onApplicationEvent(ContextRefreshedEvent event) {
		this.refreshEventReceived = true;
		synchronized (this.onRefreshMonitor) {
			onRefresh(event.getApplicationContext());
		}
	}

	/**
	 * Template method which can be overridden to add servlet-specific refresh work.
	 * Called after successful context refresh.
	 * <p>This implementation is empty.
	 * @param context the current WebApplicationContext
	 * @see #refresh()
	 */
	protected void onRefresh(ApplicationContext context) {
		// For subclasses: do nothing by default.
	}

	/**
	 * Close the WebApplicationContext of this servlet.
	 * @see org.springframework.context.ConfigurableApplicationContext#close()
	 */
	@Override
	public void destroy() {
		getServletContext().log("Destroying Spring FrameworkServlet '" + getServletName() + "'");
		// Only call close() on WebApplicationContext if locally managed...
		if (this.webApplicationContext instanceof ConfigurableApplicationContext && !this.webApplicationContextInjected) {
			((ConfigurableApplicationContext) this.webApplicationContext).close();
		}
	}


	/**
	 * 重写父类的 service 方法，目的是拦截 PATCH 请求。
	 * 在标准的 HttpServlet 中，默认的 service 方法会根据 HTTP 方法分发到 doGet、doPost 等方法，
	 * 但默认不处理 PATCH 方法。此方法将 PATCH 请求（以及无法解析的 HTTP 方法）直接交给 processRequest 处理，
	 * 其他方法则回退到父类的标准处理流程。
	 *
	 * @param request  HttpServletRequest 对象，包含客户端请求信息
	 * @param response HttpServletResponse 对象，用于返回响应
	 * @throws ServletException 如果请求处理过程中发生 Servlet 相关错误
	 * @throws IOException      如果发生输入输出错误
	 */
	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		HttpMethod httpMethod = HttpMethod.resolve(request.getMethod());
		if (httpMethod == HttpMethod.PATCH || httpMethod == null) {
			// 处理 PATCH 请求或无法解析的 HTTP 方法（如自定义扩展方法）
			processRequest(request, response);
		} else {
			// 对于 GET、POST、PUT、DELETE 等标准方法，调用父类的 service 进行标准分发
			super.service(request, response);
		}
	}

	/**
	 * 将 GET 请求委托给 processRequest/doService 处理。
	 * <p>此方法还会被 HttpServlet 中 doHead 的默认实现调用，
	 * 此时 response 会被包装为 NoBodyResponse，仅用于捕获内容长度（Content-Length），
	 * 而不实际返回响应体。
	 *
	 * @see #doService
	 * @see #doHead
	 */
	@Override
	protected final void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		processRequest(request, response);
	}

	/**
	 * 将 POST 请求委托给 {@link #processRequest} 处理。
	 *
	 * @see #doService
	 */
	@Override
	protected final void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		processRequest(request, response);
	}

	/**
	 * 将 PUT 请求委托给 {@link #processRequest} 处理。
	 *
	 * @see #doService
	 */
	@Override
	protected final void doPut(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		processRequest(request, response);
	}

	/**
	 * 将 DELETE 请求委托给 {@link #processRequest} 处理。
	 *
	 * @see #doService
	 */
	@Override
	protected final void doDelete(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		processRequest(request, response);
	}

	/**
	 * Delegate OPTIONS requests to {@link #processRequest}, if desired.
	 * <p>Applies HttpServlet's standard OPTIONS processing otherwise,
	 * and also if there is still no 'Allow' header set after dispatching.
	 * @see #doService
	 */
	@Override
	protected void doOptions(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		if (this.dispatchOptionsRequest || CorsUtils.isPreFlightRequest(request)) {
			processRequest(request, response);
			if (response.containsHeader("Allow")) {
				// Proper OPTIONS response coming from a handler - we're done.
				return;
			}
		}

		// Use response wrapper in order to always add PATCH to the allowed methods
		super.doOptions(request, new HttpServletResponseWrapper(response) {
			@Override
			public void setHeader(String name, String value) {
				if ("Allow".equals(name)) {
					value = (StringUtils.hasLength(value) ? value + ", " : "") + HttpMethod.PATCH.name();
				}
				super.setHeader(name, value);
			}
		});
	}

	/**
	 * Delegate TRACE requests to {@link #processRequest}, if desired.
	 * <p>Applies HttpServlet's standard TRACE processing otherwise.
	 * @see #doService
	 */
	@Override
	protected void doTrace(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		if (this.dispatchTraceRequest) {
			processRequest(request, response);
			if ("message/http".equals(response.getContentType())) {
				// Proper TRACE response coming from a handler - we're done.
				return;
			}
		}
		super.doTrace(request, response);
	}

	/**
	 * 处理当前请求，无论处理结果如何（成功或失败），都会发布一个事件。
	 * <p>实际的事件处理由抽象的 {@link #doService} 模板方法执行。
	 * 此方法负责请求处理的前置准备（如上下文设置、异步管理器初始化）和
	 * 后置清理（如重置上下文、记录日志、发布事件）。
	 *
	 * @param request  HttpServletRequest 对象，包含客户端请求信息
	 * @param response HttpServletResponse 对象，用于返回响应
	 * @throws ServletException 如果请求处理过程中发生 Servlet 相关错误
	 * @throws IOException      如果发生输入输出错误
	 */
	protected final void processRequest(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		// 记录请求处理的开始时间（毫秒），用于后续计算处理耗时
		long startTime = System.currentTimeMillis();
		// 记录失败原因，默认为 null；如果处理过程中发生异常，则在此保存
		Throwable failureCause = null;

		// ========== 1. 保存并设置本地化上下文 ==========
		// 获取当前线程中已有的 LocaleContext（可能为 null）
		LocaleContext previousLocaleContext = LocaleContextHolder.getLocaleContext();
		// 根据当前请求构建对应的 LocaleContext（从请求中解析语言/地区信息）
		LocaleContext localeContext = buildLocaleContext(request);

		// ========== 2. 保存并设置请求属性上下文 ==========
		// 获取当前线程中已有的 RequestAttributes（可能为 null）
		RequestAttributes previousAttributes = RequestContextHolder.getRequestAttributes();
		// 构建 ServletRequestAttributes 对象，封装 request、response 以及之前的 attributes
		ServletRequestAttributes requestAttributes = buildRequestAttributes(request, response, previousAttributes);

		// ========== 3. 获取并配置 WebAsyncManager 异步管理器 ==========
		// 从请求中获取 WebAsyncManager（用于处理异步请求）
		WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);
		// 注册一个拦截器到 Callable 上，用于在异步请求开始时绑定当前请求上下文
		// 这样在异步线程中也能访问到正确的 RequestAttributes 和 LocaleContext
		asyncManager.registerCallableInterceptor(FrameworkServlet.class.getName(), new RequestBindingInterceptor());

		// ========== 4. 初始化上下文持有者 ==========
		// 将新构建的 localeContext 和 requestAttributes 绑定到当前线程
		// （通过 LocaleContextHolder 和 RequestContextHolder）
		// 这样在请求处理过程中，Spring 组件可以通过这些 Holder 类访问当前请求的上下文信息
		initContextHolders(request, localeContext, requestAttributes);

		try {
			// ========== 5. 执行实际的业务处理 ==========
			// 调用抽象方法 doService，由子类实现具体的请求处理逻辑
			// （例如 DispatcherServlet 在此处进行 MVC 分发）
			doService(request, response);
		}
		catch (ServletException | IOException ex) {
			// 捕获 ServletException 或 IOException，记录失败原因后重新抛出
			failureCause = ex;
			throw ex;
		}
		catch (Throwable ex) {
			// 捕获其他所有异常（包括 RuntimeException、Error 等），
			// 包装成 NestedServletException 后重新抛出
			failureCause = ex;
			throw new NestedServletException("Request processing failed", ex);
		}

		finally {
			// ========== 6. 清理和善后工作（无论是否发生异常都会执行） ==========

			// 6.1 恢复线程原有的 LocaleContext 和 RequestAttributes
			// 避免对后续请求（或在同一个线程上执行的其他任务）造成上下文污染
			resetContextHolders(request, previousLocaleContext, previousAttributes);

			// 6.2 标记请求已完成
			// 如果 requestAttributes 不为 null，则执行清理工作（如释放资源、移除异步监听器等）
			if (requestAttributes != null) {
				requestAttributes.requestCompleted();
			}

			// 6.3 记录请求处理结果日志（包括成功/失败、耗时、是否异步启动等信息）
			logResult(request, response, failureCause, asyncManager);

			// 6.4 发布 RequestHandledEvent 事件
			// 该事件会被 Spring 的 ApplicationListener（如日志监听器、性能监控监听器）接收处理
			publishRequestHandledEvent(request, response, startTime, failureCause);
		}
	}

	/**
	 * Build a LocaleContext for the given request, exposing the request's
	 * primary locale as current locale.
	 * @param request current HTTP request
	 * @return the corresponding LocaleContext, or {@code null} if none to bind
	 * @see LocaleContextHolder#setLocaleContext
	 */
	@Nullable
	protected LocaleContext buildLocaleContext(HttpServletRequest request) {
		return new SimpleLocaleContext(request.getLocale());
	}

	/**
	 * Build ServletRequestAttributes for the given request (potentially also
	 * holding a reference to the response), taking pre-bound attributes
	 * (and their type) into consideration.
	 * @param request current HTTP request
	 * @param response current HTTP response
	 * @param previousAttributes pre-bound RequestAttributes instance, if any
	 * @return the ServletRequestAttributes to bind, or {@code null} to preserve
	 * the previously bound instance (or not binding any, if none bound before)
	 * @see RequestContextHolder#setRequestAttributes
	 */
	@Nullable
	protected ServletRequestAttributes buildRequestAttributes(HttpServletRequest request,
			@Nullable HttpServletResponse response, @Nullable RequestAttributes previousAttributes) {

		if (previousAttributes == null || previousAttributes instanceof ServletRequestAttributes) {
			return new ServletRequestAttributes(request, response);
		}
		else {
			return null;  // preserve the pre-bound RequestAttributes instance
		}
	}

	private void initContextHolders(HttpServletRequest request,
			@Nullable LocaleContext localeContext, @Nullable RequestAttributes requestAttributes) {

		if (localeContext != null) {
			LocaleContextHolder.setLocaleContext(localeContext, this.threadContextInheritable);
		}
		if (requestAttributes != null) {
			RequestContextHolder.setRequestAttributes(requestAttributes, this.threadContextInheritable);
		}
	}

	private void resetContextHolders(HttpServletRequest request,
			@Nullable LocaleContext prevLocaleContext, @Nullable RequestAttributes previousAttributes) {

		LocaleContextHolder.setLocaleContext(prevLocaleContext, this.threadContextInheritable);
		RequestContextHolder.setRequestAttributes(previousAttributes, this.threadContextInheritable);
	}

	private void logResult(HttpServletRequest request, HttpServletResponse response,
			@Nullable Throwable failureCause, WebAsyncManager asyncManager) {

		if (!logger.isDebugEnabled()) {
			return;
		}

		DispatcherType dispatchType = request.getDispatcherType();
		boolean initialDispatch = (dispatchType == DispatcherType.REQUEST);

		if (failureCause != null) {
			if (!initialDispatch) {
				// FORWARD/ERROR/ASYNC: minimal message (there should be enough context already)
				if (logger.isDebugEnabled()) {
					logger.debug("Unresolved failure from \"" + dispatchType + "\" dispatch: " + failureCause);
				}
			}
			else if (logger.isTraceEnabled()) {
				logger.trace("Failed to complete request", failureCause);
			}
			else {
				logger.debug("Failed to complete request: " + failureCause);
			}
			return;
		}

		if (asyncManager.isConcurrentHandlingStarted()) {
			logger.debug("Exiting but response remains open for further handling");
			return;
		}

		int status = response.getStatus();
		String headers = "";  // nothing below trace

		if (logger.isTraceEnabled()) {
			Collection<String> names = response.getHeaderNames();
			if (this.enableLoggingRequestDetails) {
				headers = names.stream().map(name -> name + ":" + response.getHeaders(name))
						.collect(Collectors.joining(", "));
			}
			else {
				headers = names.isEmpty() ? "" : "masked";
			}
			headers = ", headers={" + headers + "}";
		}

		if (!initialDispatch) {
			logger.debug("Exiting from \"" + dispatchType + "\" dispatch, status " + status + headers);
		}
		else {
			HttpStatus httpStatus = HttpStatus.resolve(status);
			logger.debug("Completed " + (httpStatus != null ? httpStatus : status) + headers);
		}
	}

	private void publishRequestHandledEvent(HttpServletRequest request, HttpServletResponse response,
			long startTime, @Nullable Throwable failureCause) {

		if (this.publishEvents && this.webApplicationContext != null) {
			// Whether or not we succeeded, publish an event.
			long processingTime = System.currentTimeMillis() - startTime;
			this.webApplicationContext.publishEvent(
					new ServletRequestHandledEvent(this,
							request.getRequestURI(), request.getRemoteAddr(),
							request.getMethod(), getServletConfig().getServletName(),
							WebUtils.getSessionId(request), getUsernameForRequest(request),
							processingTime, failureCause, response.getStatus()));
		}
	}

	/**
	 * Determine the username for the given request.
	 * <p>The default implementation takes the name of the UserPrincipal, if any.
	 * Can be overridden in subclasses.
	 * @param request current HTTP request
	 * @return the username, or {@code null} if none found
	 * @see javax.servlet.http.HttpServletRequest#getUserPrincipal()
	 */
	@Nullable
	protected String getUsernameForRequest(HttpServletRequest request) {
		Principal userPrincipal = request.getUserPrincipal();
		return (userPrincipal != null ? userPrincipal.getName() : null);
	}


	/**
	 * 子类必须实现此方法来完成实际的请求处理工作，
	 * 该方法作为 GET、POST、PUT、DELETE 等请求的统一回调入口。
	 * <p>该方法的契约本质上与 HttpServlet 中常见的 {@code doGet} 或 {@code doPost}
	 * 方法的重写版本相同，即接收 HTTP 请求和响应对象，并执行相应的业务逻辑。
	 * <p>父类（FrameworkServlet）会拦截对此方法的调用，以确保异常处理机制
	 * 和事件发布机制能够正常进行。processRequest 方法中已经完成了上下文初始化、
	 * 异步管理、日志记录等横切关注点，而此方法专注于具体的请求处理逻辑。
	 *
	 * @param request  当前的 HTTP 请求对象，包含客户端发送的请求信息
	 *                 （如请求参数、请求头、请求体等）
	 * @param response 当前的 HTTP 响应对象，用于向客户端返回处理结果
	 *                 （如设置状态码、响应头、响应体等）
	 * @throws Exception 任何类型的处理失败异常（ServletException、IOException
	 *                   或其他运行时异常），将由上层 processRequest 方法统一捕获、
	 *                   包装并发布事件
	 * @see javax.servlet.http.HttpServlet#doGet
	 * @see javax.servlet.http.HttpServlet#doPost
	 */
	protected abstract void doService(HttpServletRequest request, HttpServletResponse response)
			throws Exception;


	/**
	 * ApplicationListener endpoint that receives events from this servlet's WebApplicationContext
	 * only, delegating to {@code onApplicationEvent} on the FrameworkServlet instance.
	 */
	private class ContextRefreshListener implements ApplicationListener<ContextRefreshedEvent> {

		@Override
		public void onApplicationEvent(ContextRefreshedEvent event) {
			FrameworkServlet.this.onApplicationEvent(event);
		}
	}


	/**
	 * CallableProcessingInterceptor implementation that initializes and resets
	 * FrameworkServlet's context holders, i.e. LocaleContextHolder and RequestContextHolder.
	 */
	private class RequestBindingInterceptor implements CallableProcessingInterceptor {

		@Override
		public <T> void preProcess(NativeWebRequest webRequest, Callable<T> task) {
			HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
			if (request != null) {
				HttpServletResponse response = webRequest.getNativeResponse(HttpServletResponse.class);
				initContextHolders(request, buildLocaleContext(request),
						buildRequestAttributes(request, response, null));
			}
		}
		@Override
		public <T> void postProcess(NativeWebRequest webRequest, Callable<T> task, Object concurrentResult) {
			HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
			if (request != null) {
				resetContextHolders(request, null, null);
			}
		}
	}

}
