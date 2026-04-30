/*
 * Copyright 2002-2024 the original author or authors.
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

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeansException;
import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.support.ResourceEditorRegistrar;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ApplicationStartupAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.HierarchicalMessageSource;
import org.springframework.context.LifecycleProcessor;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.ContextStartedEvent;
import org.springframework.context.event.ContextStoppedEvent;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.expression.StandardBeanExpressionResolver;
import org.springframework.context.weaving.LoadTimeWeaverAware;
import org.springframework.context.weaving.LoadTimeWeaverAwareProcessor;
import org.springframework.core.NativeDetector;
import org.springframework.core.ResolvableType;
import org.springframework.core.SpringProperties;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Abstract implementation of the {@link org.springframework.context.ApplicationContext}
 * interface. Doesn't mandate the type of storage used for configuration; simply
 * implements common context functionality. Uses the Template Method design pattern,
 * requiring concrete subclasses to implement abstract methods.
 *
 * <p>In contrast to a plain BeanFactory, an ApplicationContext is supposed
 * to detect special beans defined in its internal bean factory:
 * Therefore, this class automatically registers
 * {@link org.springframework.beans.factory.config.BeanFactoryPostProcessor BeanFactoryPostProcessors},
 * {@link org.springframework.beans.factory.config.BeanPostProcessor BeanPostProcessors},
 * and {@link org.springframework.context.ApplicationListener ApplicationListeners}
 * which are defined as beans in the context.
 *
 * <p>A {@link org.springframework.context.MessageSource} may also be supplied
 * as a bean in the context, with the name "messageSource"; otherwise, message
 * resolution is delegated to the parent context. Furthermore, a multicaster
 * for application events can be supplied as an "applicationEventMulticaster" bean
 * of type {@link org.springframework.context.event.ApplicationEventMulticaster}
 * in the context; otherwise, a default multicaster of type
 * {@link org.springframework.context.event.SimpleApplicationEventMulticaster} will be used.
 *
 * <p>Implements resource loading by extending
 * {@link org.springframework.core.io.DefaultResourceLoader}.
 * Consequently treats non-URL resource paths as class path resources
 * (supporting full class path resource names that include the package path,
 * e.g. "mypackage/myresource.dat"), unless the {@link #getResourceByPath}
 * method is overridden in a subclass.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Stephane Nicoll
 * @author Sam Brannen
 * @author Sebastien Deleuze
 * @author Brian Clozel
 * @since January 21, 2001
 * @see #refreshBeanFactory
 * @see #getBeanFactory
 * @see org.springframework.beans.factory.config.BeanFactoryPostProcessor
 * @see org.springframework.beans.factory.config.BeanPostProcessor
 * @see org.springframework.context.event.ApplicationEventMulticaster
 * @see org.springframework.context.ApplicationListener
 * @see org.springframework.context.MessageSource
 */
public abstract class AbstractApplicationContext extends DefaultResourceLoader
		implements ConfigurableApplicationContext {

	/**
	 * The name of the {@link MessageSource} bean in the context.
	 * If none is supplied, message resolution is delegated to the parent.
	 * @see org.springframework.context.MessageSource
	 * @see org.springframework.context.support.ResourceBundleMessageSource
	 * @see org.springframework.context.support.ReloadableResourceBundleMessageSource
	 * @see #getMessage(MessageSourceResolvable, Locale)
	 */
	public static final String MESSAGE_SOURCE_BEAN_NAME = "messageSource";

	/**
	 * The name of the {@link ApplicationEventMulticaster} bean in the context.
	 * If none is supplied, a {@link SimpleApplicationEventMulticaster} is used.
	 * @see org.springframework.context.event.ApplicationEventMulticaster
	 * @see org.springframework.context.event.SimpleApplicationEventMulticaster
	 * @see #publishEvent(ApplicationEvent)
	 * @see #addApplicationListener(ApplicationListener)
	 */
	public static final String APPLICATION_EVENT_MULTICASTER_BEAN_NAME = "applicationEventMulticaster";

	/**
	 * The name of the {@link LifecycleProcessor} bean in the context.
	 * If none is supplied, a {@link DefaultLifecycleProcessor} is used.
	 * @since 3.0
	 * @see org.springframework.context.LifecycleProcessor
	 * @see org.springframework.context.support.DefaultLifecycleProcessor
	 * @see #start()
	 * @see #stop()
	 */
	public static final String LIFECYCLE_PROCESSOR_BEAN_NAME = "lifecycleProcessor";


	/**
	 * Boolean flag controlled by a {@code spring.spel.ignore} system property that
	 * instructs Spring to ignore SpEL, i.e. to not initialize the SpEL infrastructure.
	 * <p>The default is "false".
	 */
	private static final boolean shouldIgnoreSpel = SpringProperties.getFlag("spring.spel.ignore");


	static {
		// Eagerly load the ContextClosedEvent class to avoid weird classloader issues
		// on application shutdown in WebLogic 8.1. (Reported by Dustin Woods.)
		ContextClosedEvent.class.getName();
	}


	/** Logger used by this class. Available to subclasses. */
	protected final Log logger = LogFactory.getLog(getClass());

	/** Unique id for this context, if any. */
	private String id = ObjectUtils.identityToString(this);

	/** Display name. */
	private String displayName = ObjectUtils.identityToString(this);

	/** Parent context. */
	@Nullable
	private ApplicationContext parent;

	/** Environment used by this context. */
	@Nullable
	private ConfigurableEnvironment environment;

	/** BeanFactoryPostProcessors to apply on refresh. */
	private final List<BeanFactoryPostProcessor> beanFactoryPostProcessors = new ArrayList<>();

	/** System time in milliseconds when this context started. */
	private long startupDate;

	/** Flag that indicates whether this context is currently active. */
	private final AtomicBoolean active = new AtomicBoolean();

	/** Flag that indicates whether this context has been closed already. */
	private final AtomicBoolean closed = new AtomicBoolean();

	/** Synchronization monitor for "refresh" and "close". */
	private final Object startupShutdownMonitor = new Object();

	/** Reference to the JVM shutdown hook, if registered. */
	@Nullable
	private Thread shutdownHook;

	/** ResourcePatternResolver used by this context. */
	private final ResourcePatternResolver resourcePatternResolver;

	/** LifecycleProcessor for managing the lifecycle of beans within this context. */
	@Nullable
	private LifecycleProcessor lifecycleProcessor;

	/** MessageSource we delegate our implementation of this interface to. */
	@Nullable
	private MessageSource messageSource;

	/** Helper class used in event publishing. */
	@Nullable
	private ApplicationEventMulticaster applicationEventMulticaster;

	/** Application startup metrics. **/
	private ApplicationStartup applicationStartup = ApplicationStartup.DEFAULT;

	/** Statically specified listeners. */
	private final Set<ApplicationListener<?>> applicationListeners = new LinkedHashSet<>();

	/** Local listeners registered before refresh. */
	@Nullable
	private Set<ApplicationListener<?>> earlyApplicationListeners;

	/** ApplicationEvents published before the multicaster setup. */
	@Nullable
	private Set<ApplicationEvent> earlyApplicationEvents;


	/**
	 * Create a new AbstractApplicationContext with no parent.
	 */
	public AbstractApplicationContext() {
		this.resourcePatternResolver = getResourcePatternResolver();
	}

	/**
	 * Create a new AbstractApplicationContext with the given parent context.
	 * @param parent the parent context
	 */
	public AbstractApplicationContext(@Nullable ApplicationContext parent) {
		this();
		setParent(parent);
	}


	//---------------------------------------------------------------------
	// Implementation of ApplicationContext interface
	//---------------------------------------------------------------------

	/**
	 * Set the unique id of this application context.
	 * <p>Default is the object id of the context instance, or the name
	 * of the context bean if the context is itself defined as a bean.
	 * @param id the unique id of the context
	 */
	@Override
	public void setId(String id) {
		this.id = id;
	}

	@Override
	public String getId() {
		return this.id;
	}

	@Override
	public String getApplicationName() {
		return "";
	}

	/**
	 * Set a friendly name for this context.
	 * Typically done during initialization of concrete context implementations.
	 * <p>Default is the object id of the context instance.
	 */
	public void setDisplayName(String displayName) {
		Assert.hasLength(displayName, "Display name must not be empty");
		this.displayName = displayName;
	}

	/**
	 * Return a friendly name for this context.
	 * @return a display name for this context (never {@code null})
	 */
	@Override
	public String getDisplayName() {
		return this.displayName;
	}

	/**
	 * Return the parent context, or {@code null} if there is no parent
	 * (that is, this context is the root of the context hierarchy).
	 */
	@Override
	@Nullable
	public ApplicationContext getParent() {
		return this.parent;
	}

	/**
	 * Set the {@code Environment} for this application context.
	 * <p>Default value is determined by {@link #createEnvironment()}. Replacing the
	 * default with this method is one option but configuration through {@link
	 * #getEnvironment()} should also be considered. In either case, such modifications
	 * should be performed <em>before</em> {@link #refresh()}.
	 * @see org.springframework.context.support.AbstractApplicationContext#createEnvironment
	 */
	@Override
	public void setEnvironment(ConfigurableEnvironment environment) {
		this.environment = environment;
	}

	/**
	 * Return the {@code Environment} for this application context in configurable
	 * form, allowing for further customization.
	 * <p>If none specified, a default environment will be initialized via
	 * {@link #createEnvironment()}.
	 */
	@Override
	public ConfigurableEnvironment getEnvironment() {
		if (this.environment == null) {
			this.environment = createEnvironment();
		}
		return this.environment;
	}

	/**
	 * Create and return a new {@link StandardEnvironment}.
	 * <p>Subclasses may override this method in order to supply
	 * a custom {@link ConfigurableEnvironment} implementation.
	 */
	protected ConfigurableEnvironment createEnvironment() {
		return new StandardEnvironment();
	}

	/**
	 * Return this context's internal bean factory as AutowireCapableBeanFactory,
	 * if already available.
	 * @see #getBeanFactory()
	 */
	@Override
	public AutowireCapableBeanFactory getAutowireCapableBeanFactory() throws IllegalStateException {
		return getBeanFactory();
	}

	/**
	 * Return the timestamp (ms) when this context was first loaded.
	 */
	@Override
	public long getStartupDate() {
		return this.startupDate;
	}

	/**
	 * Publish the given event to all listeners.
	 * <p>Note: Listeners get initialized after the MessageSource, to be able
	 * to access it within listener implementations. Thus, MessageSource
	 * implementations cannot publish events.
	 * @param event the event to publish (may be application-specific or a
	 * standard framework event)
	 */
	@Override
	public void publishEvent(ApplicationEvent event) {
		publishEvent(event, null);
	}

	/**
	 * Publish the given event to all listeners.
	 * <p>Note: Listeners get initialized after the MessageSource, to be able
	 * to access it within listener implementations. Thus, MessageSource
	 * implementations cannot publish events.
	 * @param event the event to publish (may be an {@link ApplicationEvent}
	 * or a payload object to be turned into a {@link PayloadApplicationEvent})
	 */
	@Override
	public void publishEvent(Object event) {
		publishEvent(event, null);
	}

	/**
	 * Publish the given event to all listeners.
	 * @param event the event to publish (may be an {@link ApplicationEvent}
	 * or a payload object to be turned into a {@link PayloadApplicationEvent})
	 * @param eventType the resolved event type, if known
	 * @since 4.2
	 */
	protected void publishEvent(Object event, @Nullable ResolvableType eventType) {
		Assert.notNull(event, "Event must not be null");

		// Decorate event as an ApplicationEvent if necessary
		ApplicationEvent applicationEvent;
		if (event instanceof ApplicationEvent) {
			applicationEvent = (ApplicationEvent) event;
		}
		else {
			applicationEvent = new PayloadApplicationEvent<>(this, event);
			if (eventType == null) {
				eventType = ((PayloadApplicationEvent<?>) applicationEvent).getResolvableType();
			}
		}

		// Multicast right now if possible - or lazily once the multicaster is initialized
		if (this.earlyApplicationEvents != null) {
			this.earlyApplicationEvents.add(applicationEvent);
		}
		else {
			getApplicationEventMulticaster().multicastEvent(applicationEvent, eventType);
		}

		// Publish event via parent context as well...
		if (this.parent != null) {
			if (this.parent instanceof AbstractApplicationContext) {
				((AbstractApplicationContext) this.parent).publishEvent(event, eventType);
			}
			else {
				this.parent.publishEvent(event);
			}
		}
	}

	/**
	 * Return the internal ApplicationEventMulticaster used by the context.
	 * @return the internal ApplicationEventMulticaster (never {@code null})
	 * @throws IllegalStateException if the context has not been initialized yet
	 */
	ApplicationEventMulticaster getApplicationEventMulticaster() throws IllegalStateException {
		if (this.applicationEventMulticaster == null) {
			throw new IllegalStateException("ApplicationEventMulticaster not initialized - " +
					"call 'refresh' before multicasting events via the context: " + this);
		}
		return this.applicationEventMulticaster;
	}

	@Override
	public void setApplicationStartup(ApplicationStartup applicationStartup) {
		Assert.notNull(applicationStartup, "ApplicationStartup must not be null");
		this.applicationStartup = applicationStartup;
	}

	@Override
	public ApplicationStartup getApplicationStartup() {
		return this.applicationStartup;
	}

	/**
	 * Return the internal LifecycleProcessor used by the context.
	 * @return the internal LifecycleProcessor (never {@code null})
	 * @throws IllegalStateException if the context has not been initialized yet
	 */
	LifecycleProcessor getLifecycleProcessor() throws IllegalStateException {
		if (this.lifecycleProcessor == null) {
			throw new IllegalStateException("LifecycleProcessor not initialized - " +
					"call 'refresh' before invoking lifecycle methods via the context: " + this);
		}
		return this.lifecycleProcessor;
	}

	/**
	 * Return the ResourcePatternResolver to use for resolving location patterns
	 * into Resource instances. Default is a
	 * {@link org.springframework.core.io.support.PathMatchingResourcePatternResolver},
	 * supporting Ant-style location patterns.
	 * <p>Can be overridden in subclasses, for extended resolution strategies,
	 * for example in a web environment.
	 * <p><b>Do not call this when needing to resolve a location pattern.</b>
	 * Call the context's {@code getResources} method instead, which
	 * will delegate to the ResourcePatternResolver.
	 * @return the ResourcePatternResolver for this context
	 * @see #getResources
	 * @see org.springframework.core.io.support.PathMatchingResourcePatternResolver
	 */
	protected ResourcePatternResolver getResourcePatternResolver() {
		return new PathMatchingResourcePatternResolver(this);
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableApplicationContext interface
	//---------------------------------------------------------------------

	/**
	 * Set the parent of this application context.
	 * <p>The parent {@linkplain ApplicationContext#getEnvironment() environment} is
	 * {@linkplain ConfigurableEnvironment#merge(ConfigurableEnvironment) merged} with
	 * this (child) application context environment if the parent is non-{@code null} and
	 * its environment is an instance of {@link ConfigurableEnvironment}.
	 * @see ConfigurableEnvironment#merge(ConfigurableEnvironment)
	 */
	@Override
	public void setParent(@Nullable ApplicationContext parent) {
		this.parent = parent;
		if (parent != null) {
			Environment parentEnvironment = parent.getEnvironment();
			if (parentEnvironment instanceof ConfigurableEnvironment) {
				getEnvironment().merge((ConfigurableEnvironment) parentEnvironment);
			}
		}
	}

	@Override
	public void addBeanFactoryPostProcessor(BeanFactoryPostProcessor postProcessor) {
		Assert.notNull(postProcessor, "BeanFactoryPostProcessor must not be null");
		this.beanFactoryPostProcessors.add(postProcessor);
	}

	/**
	 * Return the list of BeanFactoryPostProcessors that will get applied
	 * to the internal BeanFactory.
	 */
	public List<BeanFactoryPostProcessor> getBeanFactoryPostProcessors() {
		return this.beanFactoryPostProcessors;
	}

	@Override
	public void addApplicationListener(ApplicationListener<?> listener) {
		Assert.notNull(listener, "ApplicationListener must not be null");
		if (this.applicationEventMulticaster != null) {
			this.applicationEventMulticaster.addApplicationListener(listener);
		}
		this.applicationListeners.add(listener);
	}

	/**
	 * Return the list of statically specified ApplicationListeners.
	 */
	public Collection<ApplicationListener<?>> getApplicationListeners() {
		return this.applicationListeners;
	}

	/**
	 * Spring IoC 容器的核心方法，负责容器的初始化、刷新和启动。
	 * 整个 Spring 应用上下文的启动过程都在这一个方法中完成。
	 * 该方法使用了同步锁，确保容器刷新过程的线程安全。
	 *
	 * @throws BeansException 如果 Bean 的创建或配置过程中出现错误
	 * @throws IllegalStateException 如果容器已经处于非法状态（如已经关闭）
	 */
	@Override
	public void refresh() throws BeansException, IllegalStateException {
		// 使用同步监视器对象加锁，保证刷新过程的原子性
		// 防止在多线程环境下同时刷新或关闭同一个容器
		synchronized (this.startupShutdownMonitor) {

			// ============ 步骤1：创建启动步骤记录（用于性能监控和诊断）============
			StartupStep contextRefresh = this.applicationStartup.start("spring.context.refresh");

			// ============ 步骤2：刷新前的准备工作 ============
			// 1. 设置容器启动时间
			// 2. 设置容器为激活状态
			// 3. 初始化属性源（PropertySources）
			// 4. 验证必要属性是否存在
			prepareRefresh();

			// ============ 步骤3：获取或创建 BeanFactory ============
			// 对于 ClassPathXmlApplicationContext，这里会：
			// 1. 销毁旧的 BeanFactory（如果存在）
			// 2. 创建新的 DefaultListableBeanFactory
			// 3. 加载和解析 XML 配置文件，将 Bean 定义注册到 BeanFactory
			ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory();

			// ============ 步骤4：为 BeanFactory 进行准备工作 ============
			// 1. 设置类加载器（ClassLoader）
			// 2. 设置表达式解析器（SpEL 支持）
			// 3. 添加 PropertyEditorRegistrar（属性编辑器注册器）
			// 4. 添加 BeanPostProcessor（ApplicationContextAwareProcessor）
			// 5. 忽略某些自动装配的依赖接口
			// 6. 注册环境相关的单例 Bean（environment、systemProperties、systemEnvironment）
			prepareBeanFactory(beanFactory);

			try {
				// ============ 步骤5：BeanFactory 的后置处理（子类可覆盖）============
				// 允许子类在 Bean 实例化前对 BeanFactory 进行额外设置
				// 例如：WebApplicationContext 会添加 Servlet 相关的依赖
				postProcessBeanFactory(beanFactory);

				// ============ 步骤6：调用 BeanFactoryPostProcessor ============
				StartupStep beanPostProcess = this.applicationStartup.start("spring.context.beans.post-process");

				// 6.1 调用所有 BeanDefinitionRegistryPostProcessor 与 BeanFactoryPostProcessor 其次还包含ConfigurationClassPostProcessor.java
				// 这些处理器可以在 Bean 实例化之前修改 Bean 的定义信息
				// 例如：PropertyPlaceholderConfigurer 会替换 ${...} 占位符
				invokeBeanFactoryPostProcessors(beanFactory);

				// 6.2 注册 BeanPostProcessor
				// 【作用】将容器中所有实现了 BeanPostProcessor 接口的 Bean 提前实例化，并注册到 BeanFactory 的缓存列表中。
				// 【重要】此阶段只做"注册"，不执行任何 BeanPostProcessor 的回调逻辑：
				//   - 注册内容：将每个 BeanPostProcessor 实例按顺序存入 beanFactory 的 beanPostProcessors 列表
				//   - 注册时机：在所有普通 Bean 实例化之前（确保后续 Bean 创建时能立即使用）
				//   - 执行时机：当每个普通 Bean 创建时，才会遍历上述列表并调用：
				//       * postProcessBeforeInitialization() - Bean 初始化前调用
				//       * postProcessAfterInitialization()  - Bean 初始化后调用
				// 【类比】像是一个"安保系统"提前部署好监控设备（注册），当人员（普通 Bean）进出时才开始检查（执行）。
				registerBeanPostProcessors(beanFactory);
				beanPostProcess.end();

				// ============ 步骤7：初始化消息源（国际化支持）============
				// 注册 MessageSource Bean，用于处理国际化消息
				// 如果没有定义，则使用默认的 DelegatingMessageSource
				initMessageSource();

				// ============ 步骤8：初始化事件广播器 ============
				// 注册 ApplicationEventMulticaster Bean，用于管理事件监听器
				// 如果没有定义，则使用默认的 SimpleApplicationEventMulticaster
				initApplicationEventMulticaster();

				// ============ 步骤9：刷新时的特殊初始化（模板方法）============
				// 这是一个模板方法，留给子类扩展
				// 例如：WebApplicationContext 会在这里初始化 ThemeSource
				onRefresh();

				// ============ 步骤10：注册事件监听器 ============
				// 找出所有实现了 ApplicationListener 接口的 Bean
				// 将它们注册到事件广播器上，关联步骤8，广播器负责广播，监听器负责监听执行，观察者模式
				registerListeners();

				// ============ 步骤11：实例化所有非懒加载的单例 Bean ============
				// 这是 Spring 启动的核心步骤：
				// 1. 实例化所有非懒加载的单例 Bean
				// 2. 执行依赖注入（属性填充）
				// 3. 执行 Bean 的初始化回调（@PostConstruct、InitializingBean、init-method）
				// 4. 注册可销毁的Bean（用于容器关闭时的清理）
				finishBeanFactoryInitialization(beanFactory);

				// ============ 步骤12：完成刷新，发布相应事件 ============
				// 1. 清除上下文资源缓存（如 ResourceLoader 的缓存）
				// 2. 初始化 LifecycleProcessor（生命周期处理器）
				// 3. 调用所有 Lifecycle 组件的 start() 方法
				// 4. 发布 ContextRefreshedEvent 事件
				finishRefresh();
			}
			catch (BeansException ex) {
				// ============ 异常处理：刷新失败 ============
				if (logger.isWarnEnabled()) {
					logger.warn("Exception encountered during context initialization - " +
							"cancelling refresh attempt: " + ex);
				}

				// 销毁已经创建的单例 Bean，避免资源泄漏
				destroyBeans();

				// 重置容器的激活标志位
				cancelRefresh(ex);

				// 向上抛出异常
				throw ex;
			}
			finally {
				// ============ 最终清理：重置缓存 ============
				// 重置 Spring 核心中的公共内省缓存
				// 例如：反射缓存、注解缓存、泛型类型缓存等
				// 这样做是因为单例 Bean 可能不再需要这些元数据了
				resetCommonCaches();
				contextRefresh.end();
			}
		}
	}

	/**
	 * 准备刷新上下文，设置启动日期和活动标志，
	 * 并执行属性源的任何初始化工作。
	 */
	protected void prepareRefresh() {
		// ========== 1. 状态切换 ==========
		// 记录容器启动的时间戳（毫秒）
		this.startupDate = System.currentTimeMillis();
		// 标记容器未关闭（使用原子布尔值保证线程安全）
		this.closed.set(false);
		// 标记容器为活跃状态
		this.active.set(true);

		// ========== 2. 日志输出 ==========
		if (logger.isDebugEnabled()) {
			if (logger.isTraceEnabled()) {
				// 最详细级别：输出完整对象信息
				logger.trace("Refreshing " + this);
			} else {
				// 调试级别：输出显示名称（如：'org.springframework.context...'）
				logger.debug("Refreshing " + getDisplayName());
			}
		}

		// ========== 3. 初始化属性源 ==========
		// 初始化环境中的任何占位符属性源
		// （子类可重写此方法，例如在Web环境中添加ServletContext参数）
		initPropertySources();

		// ========== 4. 验证必需属性 ==========
		// 验证所有标记为必需的属性是否都可解析
		// 参见 ConfigurablePropertyResolver#setRequiredProperties
		// 如果缺少必需属性，会抛出 MissingRequiredPropertiesException
		getEnvironment().validateRequiredProperties();

		// ========== 5. 保存早期应用监听器 ==========
		// 用于在刷新过程中保留刷新前的监听器快照
		if (this.earlyApplicationListeners == null) {
			// 首次刷新：将当前所有监听器复制到早期监听器集合
			this.earlyApplicationListeners = new LinkedHashSet<>(this.applicationListeners);
		} else {
			// 非首次刷新（如已调用过 refresh()）：恢复刷新前的监听器状态
			// 清空当前监听器
			this.applicationListeners.clear();
			// 从早期快照中恢复所有监听器
			this.applicationListeners.addAll(this.earlyApplicationListeners);
		}

		// ========== 6. 准备早期事件收集器 ==========
		// 允许收集早期应用事件（在事件多播器可用之前产生的事件）
		// 这些事件会暂存在这里，等事件多播器初始化后再统一发布
		this.earlyApplicationEvents = new LinkedHashSet<>();
	}

	/**
	 * <p>Replace any stub property sources with actual instances.
	 * @see org.springframework.core.env.PropertySource.StubPropertySource
	 * @see org.springframework.web.context.support.WebApplicationContextUtils#initServletPropertySources
	 */
	protected void initPropertySources() {
		// For subclasses: do nothing by default.
	}

	/**
	 * 告诉子类刷新内部的 Bean 工厂。
	 * 这是一个模板方法，实际刷新操作委托给子类实现。
	 *
	 * 执行流程：
	 * 1. 调用 refreshBeanFactory() 刷新或重新创建 BeanFactory
	 * 2. 返回刷新后的 BeanFactory 实例
	 *
	 * @return 刷新后的 BeanFactory 实例（ConfigurableListableBeanFactory 类型）
	 * @see #refreshBeanFactory()      // 由子类实现的实际刷新逻辑
	 * @see #getBeanFactory()          // 由子类实现的获取 BeanFactory 的方法
	 */
	protected ConfigurableListableBeanFactory obtainFreshBeanFactory() {
		// 步骤1：刷新 BeanFactory
		// 对于 AbstractRefreshableApplicationContext（ClassPathXmlApplicationContext 的父类）：
		//   - 如果已有 BeanFactory，则先销毁所有单例 Bean，再关闭旧的 BeanFactory
		//   - 创建一个新的 DefaultListableBeanFactory 实例
		//   - 加载并解析 XML 配置文件，将 Bean 定义注册到新的 BeanFactory 中
		refreshBeanFactory();

		// 步骤2：返回刷新后的 BeanFactory
		// getBeanFactory() 返回刚刚创建好的 BeanFactory 实例
		return getBeanFactory();
	}

	/**
	 * 配置工厂的标准上下文特性，如上下文的 ClassLoader 和后处理器。
	 * @param beanFactory 要配置的 BeanFactory
	 *
	 * @Component
	 * public class MyService {
	 *     // 可以直接注入这些类型，不需要 @Bean 定义
	 *     @Autowired
	 *     private BeanFactory beanFactory;  // ← 自动注入
	 *
	 *     @Autowired
	 *     private ApplicationContext applicationContext;  // ← 自动注入
	 *
	 *     @Autowired
	 *     private ResourceLoader resourceLoader;  // ← 自动注入
	 *
	 *     @Autowired
	 *     private Environment environment // ← 自动注入
	 * }
	 *
	 */
	protected void prepareBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		// ========== 1. 设置基础配置 ==========
		// 设置 BeanFactory 的类加载器（用于加载类）
		beanFactory.setBeanClassLoader(getClassLoader());

		// 设置 Bean 表达式解析器（处理 SpEL 表达式：#{...}）
		if (!shouldIgnoreSpel) {
			beanFactory.setBeanExpressionResolver(new StandardBeanExpressionResolver(beanFactory.getBeanClassLoader()));
		}

		// 添加属性编辑器注册器（处理属性转换，如字符串转 Resource）
		beanFactory.addPropertyEditorRegistrar(new ResourceEditorRegistrar(this, getEnvironment()));

		// ========== 2. 添加 BeanPostProcessor 处理 Aware 回调 ==========
		// 这个 Processor 负责注入 ApplicationContext 相关的 Aware 接口
		beanFactory.addBeanPostProcessor(new ApplicationContextAwareProcessor(this));

		// 忽略这些 Aware 接口的依赖注入（由 ApplicationContextAwareProcessor 处理）
		// 意思：当 Bean 实现这些接口时，不要通过自动装配来注入，而是由专门的 Processor 处理
		beanFactory.ignoreDependencyInterface(EnvironmentAware.class);
		beanFactory.ignoreDependencyInterface(EmbeddedValueResolverAware.class);
		beanFactory.ignoreDependencyInterface(ResourceLoaderAware.class);
		beanFactory.ignoreDependencyInterface(ApplicationEventPublisherAware.class);
		beanFactory.ignoreDependencyInterface(MessageSourceAware.class);
		beanFactory.ignoreDependencyInterface(ApplicationContextAware.class);
		beanFactory.ignoreDependencyInterface(ApplicationStartupAware.class);

		// ========== 3. 注册可解析的依赖（用于 @Autowired 注入）==========
		// 允许 Bean 通过 @Autowired 注入这些框架类型的 Bean
		// BeanFactory 接口
		beanFactory.registerResolvableDependency(BeanFactory.class, beanFactory);
		// ResourceLoader 接口（ApplicationContext 实现了它）
		beanFactory.registerResolvableDependency(ResourceLoader.class, this);
		// ApplicationEventPublisher 接口
		beanFactory.registerResolvableDependency(ApplicationEventPublisher.class, this);
		// ApplicationContext 接口（注入自己）
		beanFactory.registerResolvableDependency(ApplicationContext.class, this);

		// ========== 4. 注册 ApplicationListener 检测器 ==========
		// 用于检测实现了 ApplicationListener 接口的 Bean
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(this));

		// ========== 5. 处理 LoadTimeWeaver（AOP 织入，通常用于 JPA）==========
		if (!NativeDetector.inNativeImage() && beanFactory.containsBean(LOAD_TIME_WEAVER_BEAN_NAME)) {
			beanFactory.addBeanPostProcessor(new LoadTimeWeaverAwareProcessor(beanFactory));
			// 设置临时类加载器用于类型匹配
			beanFactory.setTempClassLoader(new ContextTypeMatchClassLoader(beanFactory.getBeanClassLoader()));
		}

		// ========== 6. 注册默认的环境单例 Bean ==========
		// 注册 Environment 对象
		if (!beanFactory.containsLocalBean(ENVIRONMENT_BEAN_NAME)) {
			beanFactory.registerSingleton(ENVIRONMENT_BEAN_NAME, getEnvironment());
		}
		// 注册系统属性（System.getProperties()）
		if (!beanFactory.containsLocalBean(SYSTEM_PROPERTIES_BEAN_NAME)) {
			beanFactory.registerSingleton(SYSTEM_PROPERTIES_BEAN_NAME, getEnvironment().getSystemProperties());
		}
		// 注册系统环境变量（System.getenv()）
		if (!beanFactory.containsLocalBean(SYSTEM_ENVIRONMENT_BEAN_NAME)) {
			beanFactory.registerSingleton(SYSTEM_ENVIRONMENT_BEAN_NAME, getEnvironment().getSystemEnvironment());
		}
		// 注册应用启动信息
		if (!beanFactory.containsLocalBean(APPLICATION_STARTUP_BEAN_NAME)) {
			beanFactory.registerSingleton(APPLICATION_STARTUP_BEAN_NAME, getApplicationStartup());
		}
	}

	/**
	 * Modify the application context's internal bean factory after its standard
	 * initialization. The initial definition resources will have been loaded but no
	 * post-processors will have run and no derived bean definitions will have been
	 * registered, and most importantly, no beans will have been instantiated yet.
	 * <p>This template method allows for registering special BeanPostProcessors
	 * etc in certain AbstractApplicationContext subclasses.
	 * @param beanFactory the bean factory used by the application context
	 */
	protected void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
	}

	/**
	 * 实例化并调用所有注册的BeanFactoryPostProcessor Bean
	 * 如果指定了顺序，则按照顺序执行
	 *
	 * 注意：必须在单例Bean实例化之前调用此方法
	 *
	 * 这是Spring容器启动过程中的关键步骤，负责执行所有Bean工厂后置处理器
	 * 执行时机：所有BeanDefinition加载完成后，任何Bean实例化之前
	 *
	 * @param beanFactory 可配置的列表式Bean工厂
	 */
	protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory beanFactory) {
		// 委托给PostProcessorRegistrationDelegate来处理BeanFactoryPostProcessor的调用
		// 参数：
		// 1. beanFactory - 当前的Bean工厂
		// 2. getBeanFactoryPostProcessors() - 获取手动添加的BeanFactoryPostProcessor列表
		//    这些是通过applicationContext.addBeanFactoryPostProcessor()手动添加的，
		//    优先级高于通过BeanDefinition注册的后置处理器
		PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors(beanFactory, getBeanFactoryPostProcessors());

		// 检测LoadTimeWeaver（加载时织入）并准备织入
		// LoadTimeWeaver用于AspectJ的加载时织入，在类加载时修改字节码
		// 以下条件同时满足时才会执行：
		// 1. 不是原生镜像环境（Native Image）
		// 2. BeanFactory中没有临时类加载器
		// 3. BeanFactory中包含名为"loadTimeWeaver"的Bean
		if (!NativeDetector.inNativeImage() && beanFactory.getTempClassLoader() == null &&
				beanFactory.containsBean(LOAD_TIME_WEAVER_BEAN_NAME)) {

			// 添加LoadTimeWeaverAwareProcessor作为BeanPostProcessor
			// 这个处理器负责为实现了LoadTimeWeaverAware接口的Bean设置LoadTimeWeaver
			beanFactory.addBeanPostProcessor(new LoadTimeWeaverAwareProcessor(beanFactory));

			// 设置临时类加载器，用于在织入时加载类
			// ContextTypeMatchClassLoader是特殊的类加载器，用于处理类型匹配
			beanFactory.setTempClassLoader(new ContextTypeMatchClassLoader(beanFactory.getBeanClassLoader()));
		}
	}

	/**
	 * 实例化并注册所有的 BeanPostProcessor Bean
	 * 如果指定了顺序，则按照顺序执行
	 *
	 * 注意：必须在任何应用 Bean 实例化之前调用此方法
	 *
	 * BeanPostProcessor 是 Spring 容器中非常重要的扩展接口，
	 * 它允许在 Bean 初始化前后进行自定义处理（如代理生成、依赖注入等）
	 *
	 * 与 BeanFactoryPostProcessor 的区别：
	 * - BeanFactoryPostProcessor：在 Bean 实例化之前执行，操作 BeanDefinition
	 * - BeanPostProcessor：在 Bean 实例化之后执行，操作 Bean 实例
	 *
	 * 执行时机：在 invokeBeanFactoryPostProcessors 之后，在 finishBeanFactoryInitialization 之前
	 *
	 * @param beanFactory 可配置的列表式 Bean 工厂
	 */
	protected void registerBeanPostProcessors(ConfigurableListableBeanFactory beanFactory) {
		// 委托给 PostProcessorRegistrationDelegate 来处理 BeanPostProcessor 的注册
		// 参数：
		// 1. beanFactory - 当前的 Bean 工厂
		// 2. this - 当前的 ApplicationContext（用于获取 BeanPostProcessor 的依赖）
		PostProcessorRegistrationDelegate.registerBeanPostProcessors(beanFactory, this);
	}

	/**
	 * 初始化 MessageSource（国际化消息源）
	 *
	 * 作用：为容器提供国际化（i18n）支持，用于根据不同语言环境解析文本消息
	 *
	 * 执行逻辑：
	 * 1. 判断容器中是否有用户自定义的 messageSource Bean
	 *    - 有：使用自定义的 MessageSource
	 *    - 无：创建默认的 DelegatingMessageSource 作为兜底
	 * 2. 处理父子容器的消息源继承关系
	 * 3. 将 MessageSource 保存到 ApplicationContext 中供后续使用
	 *
	 * 注意：MessageSource 的 Bean 名称固定为 "messageSource"
	 *
	 * @see #MESSAGE_SOURCE_BEAN_NAME
	 */
	protected void initMessageSource() {
		// 获取 BeanFactory（用于操作 Bean）
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();

		// 情况1：容器中已存在自定义的 messageSource Bean
		// containsLocalBean：只检查当前容器，不检查父容器
		if (beanFactory.containsLocalBean(MESSAGE_SOURCE_BEAN_NAME)) {
			// 获取用户自定义的 MessageSource 实例
			this.messageSource = beanFactory.getBean(MESSAGE_SOURCE_BEAN_NAME, MessageSource.class);

			// 处理父子容器的 MessageSource 继承关系
			// 作用：如果当前容器找不到某个消息，可以委托给父容器查找
			if (this.parent != null && this.messageSource instanceof HierarchicalMessageSource) {
				HierarchicalMessageSource hms = (HierarchicalMessageSource) this.messageSource;
				// 仅当用户没有手动设置父级 MessageSource 时，才自动设置
				if (hms.getParentMessageSource() == null) {
					// 将父容器的 MessageSource 设置为当前消息源的父级
					hms.setParentMessageSource(getInternalParentMessageSource());
				}
			}

			// 日志记录（trace 级别）
			if (logger.isTraceEnabled()) {
				logger.trace("Using MessageSource [" + this.messageSource + "]");
			}
		}
		// 情况2：容器中没有自定义的 messageSource Bean
		else {
			// 创建一个空的 MessageSource（什么都不做，但能接受 getMessage 调用而不报错）
			// 设计模式：空对象模式（Null Object Pattern）
			DelegatingMessageSource dms = new DelegatingMessageSource();

			// 同样设置父容器的 MessageSource（如果有的话）
			dms.setParentMessageSource(getInternalParentMessageSource());

			// 保存到当前 ApplicationContext 中
			this.messageSource = dms;

			// 将默认的 MessageSource 注册为单例 Bean
			// 作用：让其他组件也可以通过依赖注入获取它
			beanFactory.registerSingleton(MESSAGE_SOURCE_BEAN_NAME, this.messageSource);

			// 日志记录（trace 级别）
			if (logger.isTraceEnabled()) {
				logger.trace("No '" + MESSAGE_SOURCE_BEAN_NAME + "' bean, using [" + this.messageSource + "]");
			}
		}
	}

	/**
	 * 初始化应用程序事件广播器（ApplicationEventMulticaster）。
	 * <p>如果上下文中没有定义自定义的事件广播器，则使用 {@link SimpleApplicationEventMulticaster}。
	 * <p>事件广播器负责将 Spring 事件分发给所有注册的监听器。
	 *
	 * @see #APPLICATION_EVENT_MULTICASTER_BEAN_NAME  // 默认的 Bean 名称："applicationEventMulticaster"
	 * @see org.springframework.context.event.SimpleApplicationEventMulticaster
	 */
	protected void initApplicationEventMulticaster() {
		// 1. 获取可配置的 Bean 工厂（底层容器）
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();

		// 2. 检查容器中是否已经存在自定义的事件广播器
		if (beanFactory.containsLocalBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME)) {
			// 2.1 如果存在，直接从容器中获取（可能是用户自定义的实现）
			this.applicationEventMulticaster = beanFactory.getBean(
					APPLICATION_EVENT_MULTICASTER_BEAN_NAME,
					ApplicationEventMulticaster.class
			);
			if (logger.isTraceEnabled()) {
				logger.trace("Using ApplicationEventMulticaster [" + this.applicationEventMulticaster + "]");
			}
		}
		else {
			// 2.2 如果不存在，创建默认的 SimpleApplicationEventMulticaster
			this.applicationEventMulticaster = new SimpleApplicationEventMulticaster(beanFactory);

			// 2.3 将默认事件广播器注册为单例到容器中，供其他地方使用
			beanFactory.registerSingleton(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, this.applicationEventMulticaster);

			if (logger.isTraceEnabled()) {
				logger.trace("No '" + APPLICATION_EVENT_MULTICASTER_BEAN_NAME + "' bean, using " +
						"[" + this.applicationEventMulticaster.getClass().getSimpleName() + "]");
			}
		}
	}

	/**
	 * Initialize the {@link LifecycleProcessor}.
	 * <p>Uses {@link DefaultLifecycleProcessor} if none defined in the context.
	 * @since 3.0
	 * @see #LIFECYCLE_PROCESSOR_BEAN_NAME
	 * @see org.springframework.context.support.DefaultLifecycleProcessor
	 */
	protected void initLifecycleProcessor() {
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();
		if (beanFactory.containsLocalBean(LIFECYCLE_PROCESSOR_BEAN_NAME)) {
			this.lifecycleProcessor = beanFactory.getBean(LIFECYCLE_PROCESSOR_BEAN_NAME, LifecycleProcessor.class);
			if (logger.isTraceEnabled()) {
				logger.trace("Using LifecycleProcessor [" + this.lifecycleProcessor + "]");
			}
		}
		else {
			DefaultLifecycleProcessor defaultProcessor = new DefaultLifecycleProcessor();
			defaultProcessor.setBeanFactory(beanFactory);
			this.lifecycleProcessor = defaultProcessor;
			beanFactory.registerSingleton(LIFECYCLE_PROCESSOR_BEAN_NAME, this.lifecycleProcessor);
			if (logger.isTraceEnabled()) {
				logger.trace("No '" + LIFECYCLE_PROCESSOR_BEAN_NAME + "' bean, using " +
						"[" + this.lifecycleProcessor.getClass().getSimpleName() + "]");
			}
		}
	}

	/**
	 * Template method which can be overridden to add context-specific refresh work.
	 * Called on initialization of special beans, before instantiation of singletons.
	 * <p>This implementation is empty.
	 * @throws BeansException in case of errors
	 * @see #refresh()
	 */
	protected void onRefresh() throws BeansException {
		// For subclasses: do nothing by default.
	}

	/**
	 * 将实现了 ApplicationListener 接口的 Bean 添加为事件监听器
	 *
	 * 这个方法负责注册 Spring 的事件监听器，不影晌其他可以非 Bean 方式添加的监听器
	 *
	 * 执行时机：在 refresh() 方法中，位于 registerBeanPostProcessors() 之后，
	 *           finishBeanFactoryInitialization() 之前
	 *
	 * 重要特性：
	 * 1. 只注册监听器，不实例化普通 Bean
	 * 2. 支持提前发布早期事件（在监听器注册前发生的事件）
	 * 3. 延迟实例化 FactoryBean 类型的监听器
	 *
	 * @see ApplicationListener
	 * @see ApplicationEventMulticaster
	 */
	protected void registerListeners() {
		// ========== 第一阶段：注册静态指定的监听器 ==========
		// 这些是通过 applicationContext.addApplicationListener() 手动添加的监听器
		// 优先级最高，立即注册
		for (ApplicationListener<?> listener : getApplicationListeners()) {
			// 获取事件广播器并添加监听器
			getApplicationEventMulticaster().addApplicationListener(listener);
		}

		// ========== 第二阶段：注册 Bean 定义中的监听器 ==========
		// 注意：不初始化 FactoryBean，保持所有普通 Bean 未初始化状态
		// 让后置处理器能够应用于它们

		// 获取所有实现了 ApplicationListener 接口的 Bean 名称
		// 参数说明：
		// - ApplicationListener.class：要查找的类型
		// - true：包括非单例（原型作用域的监听器）
		// - false：不提前初始化 FactoryBean
		String[] listenerBeanNames = getBeanNamesForType(ApplicationListener.class, true, false);

		for (String listenerBeanName : listenerBeanNames) {
			// 注意：这里只注册 Bean 名称，不实际获取 Bean 实例
			// 监听器 Bean 会在后续需要时通过 getBean() 延迟实例化
			getApplicationEventMulticaster().addApplicationListenerBean(listenerBeanName);
		}

		// ========== 第三阶段：发布早期事件 ==========
		// 现在终于有了事件广播器，可以处理在注册监听器之前发生的早期事件了

		// 获取早期事件集合（在监听器注册前发布的事件）
		Set<ApplicationEvent> earlyEventsToProcess = this.earlyApplicationEvents;
		// 清空早期事件缓存，避免重复处理
		this.earlyApplicationEvents = null;

		// 如果有早期事件，现在广播它们
		if (!CollectionUtils.isEmpty(earlyEventsToProcess)) {
			for (ApplicationEvent earlyEvent : earlyEventsToProcess) {
				// 使用事件广播器发布事件
				// 此时所有监听器已经注册，事件能被正确处理
				getApplicationEventMulticaster().multicastEvent(earlyEvent);
			}
		}
	}

	/**
	 * 完成此上下文的 Bean 工厂的初始化，
	 * 初始化所有剩余的单例 Bean。
	 */
	protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory beanFactory) {

		// ============ 步骤1：初始化转换服务（ConversionService）============
		// 如果 BeanFactory 中存在名为 "conversionService" 的 Bean，且类型匹配 ConversionService
		// 则将其设置为 BeanFactory 的转换服务
		// ConversionService 用于类型转换，如 String → Date、String → 自定义类型等
		if (beanFactory.containsBean(CONVERSION_SERVICE_BEAN_NAME) &&
				beanFactory.isTypeMatch(CONVERSION_SERVICE_BEAN_NAME, ConversionService.class)) {
			beanFactory.setConversionService(
					beanFactory.getBean(CONVERSION_SERVICE_BEAN_NAME, ConversionService.class));
		}

		// ============ 步骤2：注册默认的嵌入式值解析器 ============
		// 如果没有 BeanFactoryPostProcessor（如 PropertySourcesPlaceholderConfigurer）注册过值解析器
		// 则添加一个默认的，主要用于解析注解属性值中的占位符（如 ${xxx}）
		if (!beanFactory.hasEmbeddedValueResolver()) {
			beanFactory.addEmbeddedValueResolver(strVal -> getEnvironment().resolvePlaceholders(strVal));
		}

		// ============ 步骤3：提前初始化 LoadTimeWeaverAware 类型的 Bean ============
		// LoadTimeWeaver 用于类加载时的字节码增强（如 AOP 的织入）
		// 提前初始化这些 Bean，以便尽早注册它们的转换器
		String[] weaverAwareNames = beanFactory.getBeanNamesForType(LoadTimeWeaverAware.class, false, false);
		for (String weaverAwareName : weaverAwareNames) {
			getBean(weaverAwareName);
		}

		// ============ 步骤4：清理临时类加载器 ============
		// 停止使用临时 ClassLoader 进行类型匹配，后续使用正式的 ClassLoader
		beanFactory.setTempClassLoader(null);

		// ============ 步骤5：冻结配置 ============
		// 冻结所有 Bean 定义的元数据，标记它们不再被修改
		// 冻结后，Bean 定义不能被修改或覆盖，可以提高后续操作的性能
		beanFactory.freezeConfiguration();

		// ============ 步骤6：实例化所有剩余的非懒加载单例 Bean ============
		// 这是 Spring 启动的核心步骤！
		// 会实例化所有尚未创建的非懒加载单例 Bean，执行依赖注入和初始化回调
		beanFactory.preInstantiateSingletons();
	}

	/**
	 * Finish the refresh of this context, invoking the LifecycleProcessor's
	 * onRefresh() method and publishing the
	 * {@link org.springframework.context.event.ContextRefreshedEvent}.
	 */
	@SuppressWarnings("deprecation")
	protected void finishRefresh() {
		// Clear context-level resource caches (such as ASM metadata from scanning).
		clearResourceCaches();

		// Initialize lifecycle processor for this context.
		initLifecycleProcessor();

		// Propagate refresh to lifecycle processor first.
		getLifecycleProcessor().onRefresh();

		// Publish the final event.
		publishEvent(new ContextRefreshedEvent(this));

		// Participate in LiveBeansView MBean, if active.
		if (!NativeDetector.inNativeImage()) {
			LiveBeansView.registerApplicationContext(this);
		}
	}

	/**
	 * Cancel this context's refresh attempt, resetting the {@code active} flag
	 * after an exception got thrown.
	 * @param ex the exception that led to the cancellation
	 */
	protected void cancelRefresh(BeansException ex) {
		this.active.set(false);
	}

	/**
	 * Reset Spring's common reflection metadata caches, in particular the
	 * {@link ReflectionUtils}, {@link AnnotationUtils}, {@link ResolvableType}
	 * and {@link CachedIntrospectionResults} caches.
	 * @since 4.2
	 * @see ReflectionUtils#clearCache()
	 * @see AnnotationUtils#clearCache()
	 * @see ResolvableType#clearCache()
	 * @see CachedIntrospectionResults#clearClassLoader(ClassLoader)
	 */
	protected void resetCommonCaches() {
		ReflectionUtils.clearCache();
		AnnotationUtils.clearCache();
		ResolvableType.clearCache();
		CachedIntrospectionResults.clearClassLoader(getClassLoader());
	}


	/**
	 * Register a shutdown hook {@linkplain Thread#getName() named}
	 * {@code SpringContextShutdownHook} with the JVM runtime, closing this
	 * context on JVM shutdown unless it has already been closed at that time.
	 * <p>Delegates to {@code doClose()} for the actual closing procedure.
	 * @see Runtime#addShutdownHook
	 * @see ConfigurableApplicationContext#SHUTDOWN_HOOK_THREAD_NAME
	 * @see #close()
	 * @see #doClose()
	 */
	@Override
	public void registerShutdownHook() {
		if (this.shutdownHook == null) {
			// No shutdown hook registered yet.
			this.shutdownHook = new Thread(SHUTDOWN_HOOK_THREAD_NAME) {
				@Override
				public void run() {
					synchronized (startupShutdownMonitor) {
						doClose();
					}
				}
			};
			Runtime.getRuntime().addShutdownHook(this.shutdownHook);
		}
	}

	/**
	 * Callback for destruction of this instance, originally attached
	 * to a {@code DisposableBean} implementation (not anymore in 5.0).
	 * <p>The {@link #close()} method is the native way to shut down
	 * an ApplicationContext, which this method simply delegates to.
	 * @deprecated as of Spring Framework 5.0, in favor of {@link #close()}
	 */
	@Deprecated
	public void destroy() {
		close();
	}

	/**
	 * Close this application context, destroying all beans in its bean factory.
	 * <p>Delegates to {@code doClose()} for the actual closing procedure.
	 * Also removes a JVM shutdown hook, if registered, as it's not needed anymore.
	 * @see #doClose()
	 * @see #registerShutdownHook()
	 */
	@Override
	public void close() {
		synchronized (this.startupShutdownMonitor) {
			doClose();
			// If we registered a JVM shutdown hook, we don't need it anymore now:
			// We've already explicitly closed the context.
			if (this.shutdownHook != null) {
				try {
					Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
				}
				catch (IllegalStateException ex) {
					// ignore - VM is already shutting down
				}
			}
		}
	}

	/**
	 * Actually performs context closing: publishes a ContextClosedEvent and
	 * destroys the singletons in the bean factory of this application context.
	 * <p>Called by both {@code close()} and a JVM shutdown hook, if any.
	 * @see org.springframework.context.event.ContextClosedEvent
	 * @see #destroyBeans()
	 * @see #close()
	 * @see #registerShutdownHook()
	 */
	@SuppressWarnings("deprecation")
	protected void doClose() {
		// Check whether an actual close attempt is necessary...
		if (this.active.get() && this.closed.compareAndSet(false, true)) {
			if (logger.isDebugEnabled()) {
				logger.debug("Closing " + this);
			}

			if (!NativeDetector.inNativeImage()) {
				LiveBeansView.unregisterApplicationContext(this);
			}

			try {
				// Publish shutdown event.
				publishEvent(new ContextClosedEvent(this));
			}
			catch (Throwable ex) {
				logger.warn("Exception thrown from ApplicationListener handling ContextClosedEvent", ex);
			}

			// Stop all Lifecycle beans, to avoid delays during individual destruction.
			if (this.lifecycleProcessor != null) {
				try {
					this.lifecycleProcessor.onClose();
				}
				catch (Throwable ex) {
					logger.warn("Exception thrown from LifecycleProcessor on context close", ex);
				}
			}

			// Destroy all cached singletons in the context's BeanFactory.
			destroyBeans();

			// Close the state of this context itself.
			closeBeanFactory();

			// Let subclasses do some final clean-up if they wish...
			onClose();

			// Reset common introspection caches to avoid class reference leaks.
			resetCommonCaches();

			// Reset local application listeners to pre-refresh state.
			if (this.earlyApplicationListeners != null) {
				this.applicationListeners.clear();
				this.applicationListeners.addAll(this.earlyApplicationListeners);
			}

			// Switch to inactive.
			this.active.set(false);
		}
	}

	/**
	 * Template method for destroying all beans that this context manages.
	 * The default implementation destroy all cached singletons in this context,
	 * invoking {@code DisposableBean.destroy()} and/or the specified
	 * "destroy-method".
	 * <p>Can be overridden to add context-specific bean destruction steps
	 * right before or right after standard singleton destruction,
	 * while the context's BeanFactory is still active.
	 * @see #getBeanFactory()
	 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#destroySingletons()
	 */
	protected void destroyBeans() {
		getBeanFactory().destroySingletons();
	}

	/**
	 * Template method which can be overridden to add context-specific shutdown work.
	 * The default implementation is empty.
	 * <p>Called at the end of {@link #doClose}'s shutdown procedure, after
	 * this context's BeanFactory has been closed. If custom shutdown logic
	 * needs to execute while the BeanFactory is still active, override
	 * the {@link #destroyBeans()} method instead.
	 */
	protected void onClose() {
		// For subclasses: do nothing by default.
	}

	@Override
	public boolean isActive() {
		return this.active.get();
	}

	/**
	 * Assert that this context's BeanFactory is currently active,
	 * throwing an {@link IllegalStateException} if it isn't.
	 * <p>Invoked by all {@link BeanFactory} delegation methods that depend
	 * on an active context, i.e. in particular all bean accessor methods.
	 * <p>The default implementation checks the {@link #isActive() 'active'} status
	 * of this context overall. May be overridden for more specific checks, or for a
	 * no-op if {@link #getBeanFactory()} itself throws an exception in such a case.
	 */
	protected void assertBeanFactoryActive() {
		if (!this.active.get()) {
			if (this.closed.get()) {
				throw new IllegalStateException(getDisplayName() + " has been closed already");
			}
			else {
				throw new IllegalStateException(getDisplayName() + " has not been refreshed yet");
			}
		}
	}


	//---------------------------------------------------------------------
	// Implementation of BeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public Object getBean(String name) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(name);
	}

	@Override
	public <T> T getBean(String name, Class<T> requiredType) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(name, requiredType);
	}

	@Override
	public Object getBean(String name, Object... args) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(name, args);
	}

	@Override
	public <T> T getBean(Class<T> requiredType) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(requiredType);
	}

	@Override
	public <T> T getBean(Class<T> requiredType, Object... args) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(requiredType, args);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType);
	}

	@Override
	public boolean containsBean(String name) {
		return getBeanFactory().containsBean(name);
	}

	@Override
	public boolean isSingleton(String name) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isSingleton(name);
	}

	@Override
	public boolean isPrototype(String name) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isPrototype(name);
	}

	@Override
	public boolean isTypeMatch(String name, ResolvableType typeToMatch) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isTypeMatch(name, typeToMatch);
	}

	@Override
	public boolean isTypeMatch(String name, Class<?> typeToMatch) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isTypeMatch(name, typeToMatch);
	}

	@Override
	@Nullable
	public Class<?> getType(String name) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().getType(name);
	}

	@Override
	@Nullable
	public Class<?> getType(String name, boolean allowFactoryBeanInit) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().getType(name, allowFactoryBeanInit);
	}

	@Override
	public String[] getAliases(String name) {
		return getBeanFactory().getAliases(name);
	}


	//---------------------------------------------------------------------
	// Implementation of ListableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public boolean containsBeanDefinition(String beanName) {
		return getBeanFactory().containsBeanDefinition(beanName);
	}

	@Override
	public int getBeanDefinitionCount() {
		return getBeanFactory().getBeanDefinitionCount();
	}

	@Override
	public String[] getBeanDefinitionNames() {
		return getBeanFactory().getBeanDefinitionNames();
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType, allowEagerInit);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType, allowEagerInit);
	}

	@Override
	public String[] getBeanNamesForType(ResolvableType type) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type);
	}

	@Override
	public String[] getBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
	}

	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type);
	}

	/**
	 * 获取指定类型的所有 Bean 名称
	 *
	 * 这是 ApplicationContext 中获取 Bean 名称的核心方法，委托给底层的 BeanFactory 执行
	 *
	 * @param type 要匹配的 Bean 类型（可以是接口、类或 null）
	 * @param includeNonSingletons 是否包含非单例 Bean（原型作用域、请求作用域等）
	 * @param allowEagerInit 是否允许提前初始化 Bean（包括 FactoryBean 的初始化）
	 * @return 匹配的 Bean 名称数组（可能为空数组）
	 * @throws IllegalStateException 如果 BeanFactory 尚未激活（容器未启动）
	 */
	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type,
										boolean includeNonSingletons,
										boolean allowEagerInit) {
		// 1. 断言 BeanFactory 处于激活状态
		//    确保容器已经启动，能够安全地访问 BeanFactory
		assertBeanFactoryActive();

		// 2. 委托给底层的 BeanFactory 执行实际的类型查找
		//    getBeanFactory() 返回 ConfigurableListableBeanFactory
		//    实际执行的是 DefaultListableBeanFactory.getBeanNamesForType()
		return getBeanFactory().getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
	}

	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBeansOfType(type);
	}

	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type, boolean includeNonSingletons, boolean allowEagerInit)
			throws BeansException {

		assertBeanFactoryActive();
		return getBeanFactory().getBeansOfType(type, includeNonSingletons, allowEagerInit);
	}

	@Override
	public String[] getBeanNamesForAnnotation(Class<? extends Annotation> annotationType) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForAnnotation(annotationType);
	}

	@Override
	public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType)
			throws BeansException {

		assertBeanFactoryActive();
		return getBeanFactory().getBeansWithAnnotation(annotationType);
	}

	@Override
	@Nullable
	public <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType)
			throws NoSuchBeanDefinitionException {

		assertBeanFactoryActive();
		return getBeanFactory().findAnnotationOnBean(beanName, annotationType);
	}

	@Override
	@Nullable
	public <A extends Annotation> A findAnnotationOnBean(
			String beanName, Class<A> annotationType, boolean allowFactoryBeanInit)
			throws NoSuchBeanDefinitionException {

		assertBeanFactoryActive();
		return getBeanFactory().findAnnotationOnBean(beanName, annotationType, allowFactoryBeanInit);
	}


	//---------------------------------------------------------------------
	// Implementation of HierarchicalBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	@Nullable
	public BeanFactory getParentBeanFactory() {
		return getParent();
	}

	@Override
	public boolean containsLocalBean(String name) {
		return getBeanFactory().containsLocalBean(name);
	}

	/**
	 * Return the internal bean factory of the parent context if it implements
	 * ConfigurableApplicationContext; else, return the parent context itself.
	 * @see org.springframework.context.ConfigurableApplicationContext#getBeanFactory
	 */
	@Nullable
	protected BeanFactory getInternalParentBeanFactory() {
		return (getParent() instanceof ConfigurableApplicationContext ?
				((ConfigurableApplicationContext) getParent()).getBeanFactory() : getParent());
	}


	//---------------------------------------------------------------------
	// Implementation of MessageSource interface
	//---------------------------------------------------------------------

	@Override
	public String getMessage(String code, @Nullable Object[] args, @Nullable String defaultMessage, Locale locale) {
		return getMessageSource().getMessage(code, args, defaultMessage, locale);
	}

	@Override
	public String getMessage(String code, @Nullable Object[] args, Locale locale) throws NoSuchMessageException {
		return getMessageSource().getMessage(code, args, locale);
	}

	@Override
	public String getMessage(MessageSourceResolvable resolvable, Locale locale) throws NoSuchMessageException {
		return getMessageSource().getMessage(resolvable, locale);
	}

	/**
	 * Return the internal MessageSource used by the context.
	 * @return the internal MessageSource (never {@code null})
	 * @throws IllegalStateException if the context has not been initialized yet
	 */
	private MessageSource getMessageSource() throws IllegalStateException {
		if (this.messageSource == null) {
			throw new IllegalStateException("MessageSource not initialized - " +
					"call 'refresh' before accessing messages via the context: " + this);
		}
		return this.messageSource;
	}

	/**
	 * Return the internal message source of the parent context if it is an
	 * AbstractApplicationContext too; else, return the parent context itself.
	 */
	@Nullable
	protected MessageSource getInternalParentMessageSource() {
		return (getParent() instanceof AbstractApplicationContext ?
				((AbstractApplicationContext) getParent()).messageSource : getParent());
	}


	//---------------------------------------------------------------------
	// Implementation of ResourcePatternResolver interface
	//---------------------------------------------------------------------

	@Override
	public Resource[] getResources(String locationPattern) throws IOException {
		return this.resourcePatternResolver.getResources(locationPattern);
	}


	//---------------------------------------------------------------------
	// Implementation of Lifecycle interface
	//---------------------------------------------------------------------

	@Override
	public void start() {
		getLifecycleProcessor().start();
		publishEvent(new ContextStartedEvent(this));
	}

	@Override
	public void stop() {
		getLifecycleProcessor().stop();
		publishEvent(new ContextStoppedEvent(this));
	}

	@Override
	public boolean isRunning() {
		return (this.lifecycleProcessor != null && this.lifecycleProcessor.isRunning());
	}


	//---------------------------------------------------------------------
	// Abstract methods that must be implemented by subclasses
	//---------------------------------------------------------------------

	/**
	 * Subclasses must implement this method to perform the actual configuration load.
	 * The method is invoked by {@link #refresh()} before any other initialization work.
	 * <p>A subclass will either create a new bean factory and hold a reference to it,
	 * or return a single BeanFactory instance that it holds. In the latter case, it will
	 * usually throw an IllegalStateException if refreshing the context more than once.
	 * @throws BeansException if initialization of the bean factory failed
	 * @throws IllegalStateException if already initialized and multiple refresh
	 * attempts are not supported
	 */
	protected abstract void refreshBeanFactory() throws BeansException, IllegalStateException;

	/**
	 * Subclasses must implement this method to release their internal bean factory.
	 * This method gets invoked by {@link #close()} after all other shutdown work.
	 * <p>Should never throw an exception but rather log shutdown failures.
	 */
	protected abstract void closeBeanFactory();

	/**
	 * Subclasses must return their internal bean factory here. They should implement the
	 * lookup efficiently, so that it can be called repeatedly without a performance penalty.
	 * <p>Note: Subclasses should check whether the context is still active before
	 * returning the internal bean factory. The internal factory should generally be
	 * considered unavailable once the context has been closed.
	 * @return this application context's internal bean factory (never {@code null})
	 * @throws IllegalStateException if the context does not hold an internal bean factory yet
	 * (usually if {@link #refresh()} has never been called) or if the context has been
	 * closed already
	 * @see #refreshBeanFactory()
	 * @see #closeBeanFactory()
	 */
	@Override
	public abstract ConfigurableListableBeanFactory getBeanFactory() throws IllegalStateException;


	/**
	 * Return information about this context.
	 */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(getDisplayName());
		sb.append(", started on ").append(new Date(getStartupDate()));
		ApplicationContext parent = getParent();
		if (parent != null) {
			sb.append(", parent: ").append(parent.getDisplayName());
		}
		return sb.toString();
	}

}
