/*
 * Copyright 2002-2023 the original author or authors.
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

package org.springframework.beans.factory.xml;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.parsing.EmptyReaderEventListener;
import org.springframework.beans.factory.parsing.FailFastProblemReporter;
import org.springframework.beans.factory.parsing.NullSourceExtractor;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.parsing.ReaderEventListener;
import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.beans.factory.support.AbstractBeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.Constants;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.io.DescriptiveResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.xml.SimpleSaxErrorHandler;
import org.springframework.util.xml.XmlValidationModeDetector;

/**
 * Bean definition reader for XML bean definitions.
 * Delegates the actual XML document reading to an implementation
 * of the {@link BeanDefinitionDocumentReader} interface.
 *
 * <p>Typically applied to a
 * {@link org.springframework.beans.factory.support.DefaultListableBeanFactory}
 * or a {@link org.springframework.context.support.GenericApplicationContext}.
 *
 * <p>This class loads a DOM document and applies the BeanDefinitionDocumentReader to it.
 * The document reader will register each bean definition with the given bean factory,
 * talking to the latter's implementation of the
 * {@link org.springframework.beans.factory.support.BeanDefinitionRegistry} interface.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Chris Beams
 * @since 26.11.2003
 * @see #setDocumentReaderClass
 * @see BeanDefinitionDocumentReader
 * @see DefaultBeanDefinitionDocumentReader
 * @see BeanDefinitionRegistry
 * @see org.springframework.beans.factory.support.DefaultListableBeanFactory
 * @see org.springframework.context.support.GenericApplicationContext
 */
public class XmlBeanDefinitionReader extends AbstractBeanDefinitionReader {

	/**
	 * Indicates that the validation should be disabled.
	 */
	public static final int VALIDATION_NONE = XmlValidationModeDetector.VALIDATION_NONE;

	/**
	 * Indicates that the validation mode should be detected automatically.
	 */
	public static final int VALIDATION_AUTO = XmlValidationModeDetector.VALIDATION_AUTO;

	/**
	 * Indicates that DTD validation should be used.
	 */
	public static final int VALIDATION_DTD = XmlValidationModeDetector.VALIDATION_DTD;

	/**
	 * Indicates that XSD validation should be used.
	 */
	public static final int VALIDATION_XSD = XmlValidationModeDetector.VALIDATION_XSD;


	/** Constants instance for this class. */
	private static final Constants constants = new Constants(XmlBeanDefinitionReader.class);

	private int validationMode = VALIDATION_AUTO;

	private boolean namespaceAware = false;

	private Class<? extends BeanDefinitionDocumentReader> documentReaderClass =
			DefaultBeanDefinitionDocumentReader.class;

	private ProblemReporter problemReporter = new FailFastProblemReporter();

	private ReaderEventListener eventListener = new EmptyReaderEventListener();

	private SourceExtractor sourceExtractor = new NullSourceExtractor();

	@Nullable
	private NamespaceHandlerResolver namespaceHandlerResolver;

	private DocumentLoader documentLoader = new DefaultDocumentLoader();

	@Nullable
	private EntityResolver entityResolver;

	private ErrorHandler errorHandler = new SimpleSaxErrorHandler(logger);

	private final XmlValidationModeDetector validationModeDetector = new XmlValidationModeDetector();

	private final ThreadLocal<Set<EncodedResource>> resourcesCurrentlyBeingLoaded =
			new NamedThreadLocal<Set<EncodedResource>>("XML bean definition resources currently being loaded"){
				@Override
				protected Set<EncodedResource> initialValue() {
					return new HashSet<>(4);
				}
			};


	/**
	 * Create new XmlBeanDefinitionReader for the given bean factory.
	 * @param registry the BeanFactory to load bean definitions into,
	 * in the form of a BeanDefinitionRegistry
	 */
	public XmlBeanDefinitionReader(BeanDefinitionRegistry registry) {
		super(registry);
	}


	/**
	 * Set whether to use XML validation. Default is {@code true}.
	 * <p>This method switches namespace awareness on if validation is turned off,
	 * in order to still process schema namespaces properly in such a scenario.
	 * @see #setValidationMode
	 * @see #setNamespaceAware
	 */
	public void setValidating(boolean validating) {
		this.validationMode = (validating ? VALIDATION_AUTO : VALIDATION_NONE);
		this.namespaceAware = !validating;
	}

	/**
	 * Set the validation mode to use by name. Defaults to {@link #VALIDATION_AUTO}.
	 * @see #setValidationMode
	 */
	public void setValidationModeName(String validationModeName) {
		setValidationMode(constants.asNumber(validationModeName).intValue());
	}

	/**
	 * Set the validation mode to use. Defaults to {@link #VALIDATION_AUTO}.
	 * <p>Note that this only activates or deactivates validation itself.
	 * If you are switching validation off for schema files, you might need to
	 * activate schema namespace support explicitly: see {@link #setNamespaceAware}.
	 */
	public void setValidationMode(int validationMode) {
		this.validationMode = validationMode;
	}

	/**
	 * Return the validation mode to use.
	 */
	public int getValidationMode() {
		return this.validationMode;
	}

	/**
	 * Set whether the XML parser should be XML namespace aware.
	 * Default is "false".
	 * <p>This is typically not needed when schema validation is active.
	 * However, without validation, this has to be switched to "true"
	 * in order to properly process schema namespaces.
	 */
	public void setNamespaceAware(boolean namespaceAware) {
		this.namespaceAware = namespaceAware;
	}

	/**
	 * Return whether the XML parser should be XML namespace aware.
	 */
	public boolean isNamespaceAware() {
		return this.namespaceAware;
	}

	/**
	 * Specify which {@link org.springframework.beans.factory.parsing.ProblemReporter} to use.
	 * <p>The default implementation is {@link org.springframework.beans.factory.parsing.FailFastProblemReporter}
	 * which exhibits fail fast behaviour. External tools can provide an alternative implementation
	 * that collates errors and warnings for display in the tool UI.
	 */
	public void setProblemReporter(@Nullable ProblemReporter problemReporter) {
		this.problemReporter = (problemReporter != null ? problemReporter : new FailFastProblemReporter());
	}

	/**
	 * Specify which {@link ReaderEventListener} to use.
	 * <p>The default implementation is EmptyReaderEventListener which discards every event notification.
	 * External tools can provide an alternative implementation to monitor the components being
	 * registered in the BeanFactory.
	 */
	public void setEventListener(@Nullable ReaderEventListener eventListener) {
		this.eventListener = (eventListener != null ? eventListener : new EmptyReaderEventListener());
	}

	/**
	 * Specify the {@link SourceExtractor} to use.
	 * <p>The default implementation is {@link NullSourceExtractor} which simply returns {@code null}
	 * as the source object. This means that - during normal runtime execution -
	 * no additional source metadata is attached to the bean configuration metadata.
	 */
	public void setSourceExtractor(@Nullable SourceExtractor sourceExtractor) {
		this.sourceExtractor = (sourceExtractor != null ? sourceExtractor : new NullSourceExtractor());
	}

	/**
	 * Specify the {@link NamespaceHandlerResolver} to use.
	 * <p>If none is specified, a default instance will be created through
	 * {@link #createDefaultNamespaceHandlerResolver()}.
	 */
	public void setNamespaceHandlerResolver(@Nullable NamespaceHandlerResolver namespaceHandlerResolver) {
		this.namespaceHandlerResolver = namespaceHandlerResolver;
	}

	/**
	 * Specify the {@link DocumentLoader} to use.
	 * <p>The default implementation is {@link DefaultDocumentLoader}
	 * which loads {@link Document} instances using JAXP.
	 */
	public void setDocumentLoader(@Nullable DocumentLoader documentLoader) {
		this.documentLoader = (documentLoader != null ? documentLoader : new DefaultDocumentLoader());
	}

	/**
	 * Set a SAX entity resolver to be used for parsing.
	 * <p>By default, {@link ResourceEntityResolver} will be used. Can be overridden
	 * for custom entity resolution, for example relative to some specific base path.
	 */
	public void setEntityResolver(@Nullable EntityResolver entityResolver) {
		this.entityResolver = entityResolver;
	}

	/**
	 * Return the EntityResolver to use, building a default resolver
	 * if none specified.
	 */
	protected EntityResolver getEntityResolver() {
		if (this.entityResolver == null) {
			// Determine default EntityResolver to use.
			ResourceLoader resourceLoader = getResourceLoader();
			if (resourceLoader != null) {
				this.entityResolver = new ResourceEntityResolver(resourceLoader);
			}
			else {
				this.entityResolver = new DelegatingEntityResolver(getBeanClassLoader());
			}
		}
		return this.entityResolver;
	}

	/**
	 * Set an implementation of the {@code org.xml.sax.ErrorHandler}
	 * interface for custom handling of XML parsing errors and warnings.
	 * <p>If not set, a default SimpleSaxErrorHandler is used that simply
	 * logs warnings using the logger instance of the view class,
	 * and rethrows errors to discontinue the XML transformation.
	 * @see SimpleSaxErrorHandler
	 */
	public void setErrorHandler(ErrorHandler errorHandler) {
		this.errorHandler = errorHandler;
	}

	/**
	 * Specify the {@link BeanDefinitionDocumentReader} implementation to use,
	 * responsible for the actual reading of the XML bean definition document.
	 * <p>The default is {@link DefaultBeanDefinitionDocumentReader}.
	 * @param documentReaderClass the desired BeanDefinitionDocumentReader implementation class
	 */
	public void setDocumentReaderClass(Class<? extends BeanDefinitionDocumentReader> documentReaderClass) {
		this.documentReaderClass = documentReaderClass;
	}


	/**
	 * 从指定的 XML 文件中加载 Bean 定义。
	 *
	 * 这是 XmlBeanDefinitionReader 中 loadBeanDefinitions 方法的具体实现。
	 *
	 * 核心作用：
	 * 1. 将 Resource 资源包装为 EncodedResource（支持编码配置）
	 * 2. 委托给重载方法进行实际加载
	 *
	 * 为什么要包装成 EncodedResource？
	 * - 普通的 Resource 只提供了资源的位置和输入流，没有编码信息
	 * - XML 文件可能使用不同的字符编码（如 UTF-8、GBK、ISO-8859-1 等）
	 * - EncodedResource 允许在 XML 中通过 <?xml encoding="GBK"?> 或显式指定编码
	 * - 确保正确解析中文字符等非 ASCII 字符
	 *
	 * @param resource XML 文件的资源描述符
	 *                 可以是多种类型：
	 *                 - ClassPathResource：类路径下的文件（最常用）
	 *                 - FileSystemResource：文件系统中的绝对或相对路径
	 *                 - UrlResource：网络上的资源（http://、file:// 等）
	 *                 - ByteArrayResource：内存中的 XML 内容
	 * @return 在 XML 文件中找到并成功注册的 Bean 定义数量（即 <bean> 标签的数量）
	 * @throws BeanDefinitionStoreException 如果加载或解析过程中发生错误
	 *         - 文件不存在
	 *         - XML 格式错误（如标签未闭合）
	 *         - Bean 定义重复且不允许覆盖
	 *         - I/O 读取异常
	 */
	@Override
	public int loadBeanDefinitions(Resource resource) throws BeanDefinitionStoreException {
		// ============ 关键步骤：包装为 EncodedResource ============
		// EncodedResource 是一个装饰器（Decorator Pattern）
		// 它在原始 Resource 的基础上增加了字符编码的支持
		//
		// 为什么要这样设计？
		// 1. Resource 接口本身没有编码信息
		// 2. XML 文件可能有不同的编码声明
		// 3. EncodedResource 可以从以下来源获取编码：
		//    a) 显式传入的编码参数
		//    b) XML 文件头部的声明：<?xml version="1.0" encoding="UTF-8"?>
		//    c) 使用默认编码（UTF-8）
		//
		// 包装后，调用重载方法 loadBeanDefinitions(EncodedResource)
		// 该方法会处理编码问题并执行实际的解析逻辑
		return loadBeanDefinitions(new EncodedResource(resource));
	}

	/**
	 * 从指定的 XML 文件中加载 Bean 定义。
	 *
	 * 这是 XmlBeanDefinitionReader 中最核心的方法之一，负责：
	 * 1. 处理 XML 文件的字符编码
	 * 2. 检测并防止循环导入（<import> 标签导致的循环依赖）
	 * 3. 解析 XML 文件并注册 Bean 定义
	 *
	 * @param encodedResource XML 文件的资源描述符，允许指定解析文件时使用的编码
	 *                        - 可以从 XML 头声明中自动获取编码
	 *                        - 也可以显式指定编码（如 UTF-8、GBK 等）
	 * @return 在 XML 文件中找到并成功注册的 Bean 定义数量
	 * @throws BeanDefinitionStoreException 如果加载或解析过程中发生错误
	 *         - 循环导入错误
	 *         - 文件不存在或无法读取
	 *         - XML 格式错误
	 *         - Bean 定义重复且不允许覆盖
	 */
	public int loadBeanDefinitions(EncodedResource encodedResource) throws BeanDefinitionStoreException {
		// ============ 步骤1：参数校验 ============
		// 确保传入的 EncodedResource 不为 null
		Assert.notNull(encodedResource, "EncodedResource must not be null");

		// ============ 步骤2：日志记录（跟踪级别）============
		// 在 TRACE 级别记录正在加载的 XML 文件路径
		// 用于调试和监控容器启动过程
		if (logger.isTraceEnabled()) {
			logger.trace("Loading XML bean definitions from " + encodedResource);
		}

		// ============ 步骤3：获取当前线程正在加载的资源集合 ============
		// resourcesCurrentlyBeingLoaded 是 ThreadLocal<Set<EncodedResource>>
		// 用于跟踪当前线程正在处理的所有 XML 资源
		//
		// 为什么需要这个集合？
		// - 防止循环导入：例如 A.xml import B.xml，B.xml 又 import A.xml
		// - 每个线程维护自己的集合，避免多线程环境下的干扰
		Set<EncodedResource> currentResources = this.resourcesCurrentlyBeingLoaded.get();

		// 如果当前线程还没有资源集合，则创建一个新的 HashSet
		if (currentResources == null) {
			currentResources = new HashSet<>(4);
			this.resourcesCurrentlyBeingLoaded.set(currentResources);
		}

		// ============ 步骤4：循环导入检测 ============
		// 尝试将当前资源添加到正在加载的资源集合中
		// 如果返回 false，说明这个资源已经在集合中（即已经被加载过）
		// 这意味着出现了循环导入！
		if (!currentResources.add(encodedResource)) {
			// 抛出异常，明确告知用户哪个资源导致了循环导入
			throw new BeanDefinitionStoreException(
					"Detected cyclic loading of " + encodedResource +
							" - check your import definitions!");
		}

		// ============ 步骤5：使用 try-with-resources 获取输入流 ============
		// try-with-resources 语法确保输入流在最后被自动关闭
		// 防止资源泄漏
		try (InputStream inputStream = encodedResource.getResource().getInputStream()) {

			// 5.1 创建 SAX 解析器需要的 InputSource 对象
			// InputSource 是 XML 解析的标准输入源，可以包含：
			//   - 输入流（InputStream）
			//   - 字符流（Reader）
			//   - 系统 ID（SystemId，通常是文件路径）
			//   - 公共 ID（PublicId）
			InputSource inputSource = new InputSource(inputStream);

			// 5.2 设置编码（如果显式指定了）
			// 如果 EncodedResource 有显式的编码设置（通过构造参数传入）
			// 则将该编码设置到 InputSource 中
			// 这样 XML 解析器就会使用指定的编码来读取文件
			if (encodedResource.getEncoding() != null) {
				inputSource.setEncoding(encodedResource.getEncoding());
			}

			// 5.3 执行真正的加载逻辑
			// doLoadBeanDefinitions 是实际解析 XML 并注册 Bean 定义的方法
			// 传入：
			//   - inputSource：包含输入流和编码信息的源
			//   - encodedResource.getResource()：原始 Resource，用于错误日志
			// 返回本次加载的 Bean 定义数量
			return doLoadBeanDefinitions(inputSource, encodedResource.getResource());
		}
		catch (IOException ex) {
			// ============ 步骤6：处理 I/O 异常 ============
			// 将 IOException 转换为 Spring 的 BeanDefinitionStoreException
			// 包括：文件不存在、权限不足、网络中断等
			throw new BeanDefinitionStoreException(
					"IOException parsing XML document from " + encodedResource.getResource(), ex);
		}
		finally {
			// ============ 步骤7：清理资源集合 ============
			// 无论成功还是失败，都要从当前线程的资源集合中移除当前资源
			currentResources.remove(encodedResource);

			// 如果集合变空了，则从 ThreadLocal 中移除该集合
			// 避免 ThreadLocal 内存泄漏
			if (currentResources.isEmpty()) {
				this.resourcesCurrentlyBeingLoaded.remove();
			}
		}
	}

	/**
	 * Load bean definitions from the specified XML file.
	 * @param inputSource the SAX InputSource to read from
	 * @return the number of bean definitions found
	 * @throws BeanDefinitionStoreException in case of loading or parsing errors
	 */
	public int loadBeanDefinitions(InputSource inputSource) throws BeanDefinitionStoreException {
		return loadBeanDefinitions(inputSource, "resource loaded through SAX InputSource");
	}

	/**
	 * Load bean definitions from the specified XML file.
	 * @param inputSource the SAX InputSource to read from
	 * @param resourceDescription a description of the resource
	 * (can be {@code null} or empty)
	 * @return the number of bean definitions found
	 * @throws BeanDefinitionStoreException in case of loading or parsing errors
	 */
	public int loadBeanDefinitions(InputSource inputSource, @Nullable String resourceDescription)
			throws BeanDefinitionStoreException {

		return doLoadBeanDefinitions(inputSource, new DescriptiveResource(resourceDescription));
	}


	/**
	 * 实际从指定的 XML 文件中加载 Bean 定义。
	 *
	 * 这是 XML 解析的核心方法，负责：
	 * 1. 调用 doLoadDocument() 将 XML 文件解析为 DOM Document 对象
	 * 2. 调用 registerBeanDefinitions() 解析 Document 并注册 Bean 定义
	 * 3. 对各种异常进行精细化处理和转换
	 *
	 * @param inputSource SAX 输入源，包含 XML 的输入流和编码信息
	 * @param resource    原始资源描述符，用于错误日志和定位
	 * @return 成功加载并注册的 Bean 定义数量
	 * @throws BeanDefinitionStoreException 如果加载或解析过程中发生错误
	 * @see #doLoadDocument      // 将 XML 解析为 Document 对象
	 * @see #registerBeanDefinitions  // 解析 Document 并注册 Bean 定义
	 */
	protected int doLoadBeanDefinitions(InputSource inputSource, Resource resource)
			throws BeanDefinitionStoreException {

		try {
			// ============ 步骤1：将 XML 文件解析为 Document 对象 ============
			// doLoadDocument 内部会：
			//   1. 获取 XML 验证模式（DTD 或 XSD）
			//   2. 创建 DocumentBuilder（使用配置好的 EntityResolver）
			//   3. 解析 XML 文件，生成 DOM Document 对象
			//   4. 处理 XML 中的命名空间
			//
			// 参数说明：
			//   - inputSource：包含输入流和编码信息
			//   - resource：用于错误信息定位
			Document doc = doLoadDocument(inputSource, resource);

			// ============ 步骤2：解析 Document 并注册 Bean 定义 ============
			// registerBeanDefinitions 是真正的"翻译"过程：
			//   1. 创建 BeanDefinitionDocumentReader
			//   2. 遍历 Document 的根元素 <beans>
			//   3. 处理 <import>、<alias>、<bean> 等子标签
			//   4. 为每个 <bean> 创建 BeanDefinitionHolder
			//   5. 将 BeanDefinition 注册到 BeanFactory
			//   6. 返回注册的 Bean 定义数量
			int count = registerBeanDefinitions(doc, resource);

			// ============ 步骤3：DEBUG 级别日志 ============
			// 记录成功加载的 Bean 定义数量，便于调试
			if (logger.isDebugEnabled()) {
				logger.debug("Loaded " + count + " bean definitions from " + resource);
			}

			return count;
		}
		// ============ 异常处理：精细化异常转换 ============

		// 情况1：已经是 BeanDefinitionStoreException 类型，直接抛出
		// 避免重复包装
		catch (BeanDefinitionStoreException ex) {
			throw ex;
		}

		// 情况2：SAX 解析异常（带行号信息）
		// SAXParseException 是 XML 语法错误，例如：
		//   - 标签未闭合：<bean id="user" class="...">
		//   - 属性值未加引号：id=user
		//   - 非法字符
		//   - 命名空间错误
		// 这类异常会包含出错的行号和列号，非常有用！
		catch (SAXParseException ex) {
			throw new XmlBeanDefinitionStoreException(resource.getDescription(),
					"Line " + ex.getLineNumber() + " in XML document from " + resource + " is invalid", ex);
		}

		// 情况3：其他 SAX 异常（没有行号信息）
		// 例如：XML 结构错误、未预期的标签等
		catch (SAXException ex) {
			throw new XmlBeanDefinitionStoreException(resource.getDescription(),
					"XML document from " + resource + " is invalid", ex);
		}

		// 情况4：解析器配置异常
		// 例如：DocumentBuilderFactory 配置错误、JAXP 实现缺失等
		catch (ParserConfigurationException ex) {
			throw new BeanDefinitionStoreException(resource.getDescription(),
					"Parser configuration exception parsing XML from " + resource, ex);
		}

		// 情况5：I/O 异常
		// 例如：文件读取中断、网络超时、文件被删除等
		catch (IOException ex) {
			throw new BeanDefinitionStoreException(resource.getDescription(),
					"IOException parsing XML document from " + resource, ex);
		}

		// 情况6：兜底处理 - 捕获所有其他异常（包括运行时异常）
		// 例如：ClassCastException、NullPointerException 等
		// 确保任何异常都被转换为 BeanDefinitionStoreException
		catch (Throwable ex) {
			throw new BeanDefinitionStoreException(resource.getDescription(),
					"Unexpected exception parsing XML document from " + resource, ex);
		}
	}

	/**
	 * Actually load the specified document using the configured DocumentLoader.
	 * @param inputSource the SAX InputSource to read from
	 * @param resource the resource descriptor for the XML file
	 * @return the DOM Document
	 * @throws Exception when thrown from the DocumentLoader
	 * @see #setDocumentLoader
	 * @see DocumentLoader#loadDocument
	 */
	protected Document doLoadDocument(InputSource inputSource, Resource resource) throws Exception {
		return this.documentLoader.loadDocument(inputSource, getEntityResolver(), this.errorHandler,
				getValidationModeForResource(resource), isNamespaceAware());
	}

	/**
	 * Determine the validation mode for the specified {@link Resource}.
	 * If no explicit validation mode has been configured, then the validation
	 * mode gets {@link #detectValidationMode detected} from the given resource.
	 * <p>Override this method if you would like full control over the validation
	 * mode, even when something other than {@link #VALIDATION_AUTO} was set.
	 * @see #detectValidationMode
	 */
	protected int getValidationModeForResource(Resource resource) {
		int validationModeToUse = getValidationMode();
		if (validationModeToUse != VALIDATION_AUTO) {
			return validationModeToUse;
		}
		int detectedMode = detectValidationMode(resource);
		if (detectedMode != VALIDATION_AUTO) {
			return detectedMode;
		}
		// Hmm, we didn't get a clear indication... Let's assume XSD,
		// since apparently no DTD declaration has been found up until
		// detection stopped (before finding the document's root tag).
		return VALIDATION_XSD;
	}

	/**
	 * Detect which kind of validation to perform on the XML file identified
	 * by the supplied {@link Resource}. If the file has a {@code DOCTYPE}
	 * definition then DTD validation is used otherwise XSD validation is assumed.
	 * <p>Override this method if you would like to customize resolution
	 * of the {@link #VALIDATION_AUTO} mode.
	 */
	protected int detectValidationMode(Resource resource) {
		if (resource.isOpen()) {
			throw new BeanDefinitionStoreException(
					"Passed-in Resource [" + resource + "] contains an open stream: " +
					"cannot determine validation mode automatically. Either pass in a Resource " +
					"that is able to create fresh streams, or explicitly specify the validationMode " +
					"on your XmlBeanDefinitionReader instance.");
		}

		InputStream inputStream;
		try {
			inputStream = resource.getInputStream();
		}
		catch (IOException ex) {
			throw new BeanDefinitionStoreException(
					"Unable to determine validation mode for [" + resource + "]: cannot open InputStream. " +
					"Did you attempt to load directly from a SAX InputSource without specifying the " +
					"validationMode on your XmlBeanDefinitionReader instance?", ex);
		}

		try {
			return this.validationModeDetector.detectValidationMode(inputStream);
		}
		catch (IOException ex) {
			throw new BeanDefinitionStoreException("Unable to determine validation mode for [" +
					resource + "]: an error occurred whilst reading from the InputStream.", ex);
		}
	}

	/**
	 * 注册给定 DOM 文档中包含的 Bean 定义。
	 * 由 {@code loadBeanDefinitions} 方法调用。
	 *
	 * 核心流程：
	 * 1. 创建 BeanDefinitionDocumentReader 实例（默认是 DefaultBeanDefinitionDocumentReader）
	 * 2. 记录注册前的 Bean 定义数量
	 * 3. 委托给 DocumentReader 解析 Document 并注册 Bean 定义
	 * 4. 计算并返回本次新增的 Bean 定义数量
	 *
	 * @param doc  DOM 文档对象（由 XML 解析而来）
	 * @param resource 资源描述符（用于上下文信息，如日志和错误定位）
	 * @return 在文档中找到并成功注册的 Bean 定义数量
	 * @throws BeanDefinitionStoreException 如果解析过程中发生错误
	 * @see #loadBeanDefinitions
	 * @see #setDocumentReaderClass
	 * @see BeanDefinitionDocumentReader#registerBeanDefinitions
	 */
	public int registerBeanDefinitions(Document doc, Resource resource) throws BeanDefinitionStoreException {
		// ============ 步骤1：创建 Bean 定义文档读取器 ============
		// createBeanDefinitionDocumentReader() 会：
		//   1. 通过反射实例化 documentReaderClass（默认是 DefaultBeanDefinitionDocumentReader）
		//   2. 可以通过 setDocumentReaderClass() 方法替换为自定义实现
		//
		// BeanDefinitionDocumentReader 的作用：
		//   - 解析 DOM Document 中的 <beans> 根元素
		//   - 遍历并处理 <import>、<alias>、<bean> 等子元素
		//   - 将每个 <bean> 元素转换为 BeanDefinition 对象
		//   - 将 BeanDefinition 注册到 BeanFactory
		BeanDefinitionDocumentReader documentReader = createBeanDefinitionDocumentReader();

		// ============ 步骤2：记录注册前的 Bean 定义数量 ============
		// getRegistry() 返回 BeanDefinitionRegistry（通常是 DefaultListableBeanFactory）
		// getBeanDefinitionCount() 返回当前已注册的 Bean 定义数量
		//
		// 为什么要记录注册前的数量？
		//   - 用于计算本次新增的 Bean 定义数量
		//   - 因为 Document 中可能包含 <import> 标签，会递归加载其他配置文件
		//   - 单纯统计当前 XML 中的 <bean> 数量不准确
		//   - 通过前后差值可以准确知道本次加载新增了多少 Bean 定义
		int countBefore = getRegistry().getBeanDefinitionCount();

		// ============ 步骤3：解析并注册 Bean 定义 ============
		// createReaderContext(resource) 创建读取器上下文，包含：
		//   - resource：当前资源（用于错误定位）
		//   - problemReporter：问题报告器（用于记录警告和错误）
		//   - eventListener：事件监听器（用于解析事件）
		//   - sourceExtractor：源码提取器（用于提取源代码位置）
		//
		// documentReader.registerBeanDefinitions() 会：
		//   1. 获取 Document 的根元素（通常是 <beans>）
		//   2. 处理根元素的属性（default-lazy-init、default-autowire 等）
		//   3. 遍历子元素：
		//      - 遇到 <import>：递归加载其他配置文件
		//      - 遇到 <alias>：注册 Bean 别名
		//      - 遇到 <bean>：解析并注册 Bean 定义
		//   4. 处理嵌套的 <beans> 标签（支持分组配置）
		documentReader.registerBeanDefinitions(doc, createReaderContext(resource));

		// ============ 步骤4：返回本次新增的 Bean 定义数量 ============
		// 计算差值 = 注册后的数量 - 注册前的数量
		// 这个差值可能大于当前 XML 中的 <bean> 数量（因为 <import> 会加载更多）
		return getRegistry().getBeanDefinitionCount() - countBefore;
	}

	/**
	 * Create the {@link BeanDefinitionDocumentReader} to use for actually
	 * reading bean definitions from an XML document.
	 * <p>The default implementation instantiates the specified "documentReaderClass".
	 * @see #setDocumentReaderClass
	 */
	protected BeanDefinitionDocumentReader createBeanDefinitionDocumentReader() {
		return BeanUtils.instantiateClass(this.documentReaderClass);
	}

	/**
	 * Create the {@link XmlReaderContext} to pass over to the document reader.
	 */
	public XmlReaderContext createReaderContext(Resource resource) {
		return new XmlReaderContext(resource, this.problemReporter, this.eventListener,
				this.sourceExtractor, this, getNamespaceHandlerResolver());
	}

	/**
	 * Lazily create a default NamespaceHandlerResolver, if not set before.
	 * @see #createDefaultNamespaceHandlerResolver()
	 */
	public NamespaceHandlerResolver getNamespaceHandlerResolver() {
		if (this.namespaceHandlerResolver == null) {
			this.namespaceHandlerResolver = createDefaultNamespaceHandlerResolver();
		}
		return this.namespaceHandlerResolver;
	}

	/**
	 * Create the default implementation of {@link NamespaceHandlerResolver} used if none is specified.
	 * <p>The default implementation returns an instance of {@link DefaultNamespaceHandlerResolver}.
	 * @see DefaultNamespaceHandlerResolver#DefaultNamespaceHandlerResolver(ClassLoader)
	 */
	protected NamespaceHandlerResolver createDefaultNamespaceHandlerResolver() {
		ResourceLoader resourceLoader = getResourceLoader();
		ClassLoader cl = (resourceLoader != null ? resourceLoader.getClassLoader() : getBeanClassLoader());
		return new DefaultNamespaceHandlerResolver(cl);
	}

}
