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

package org.springframework.beans.factory.xml;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * Default implementation of the {@link BeanDefinitionDocumentReader} interface that
 * reads bean definitions according to the "spring-beans" DTD and XSD format
 * (Spring's default XML bean definition format).
 *
 * <p>The structure, elements, and attribute names of the required XML document
 * are hard-coded in this class. (Of course a transform could be run if necessary
 * to produce this format). {@code <beans>} does not need to be the root
 * element of the XML document: this class will parse all bean definition elements
 * in the XML file, regardless of the actual root element.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Erik Wiersma
 * @since 18.12.2003
 */
public class DefaultBeanDefinitionDocumentReader implements BeanDefinitionDocumentReader {

	public static final String BEAN_ELEMENT = BeanDefinitionParserDelegate.BEAN_ELEMENT;

	public static final String NESTED_BEANS_ELEMENT = "beans";

	public static final String ALIAS_ELEMENT = "alias";

	public static final String NAME_ATTRIBUTE = "name";

	public static final String ALIAS_ATTRIBUTE = "alias";

	public static final String IMPORT_ELEMENT = "import";

	public static final String RESOURCE_ATTRIBUTE = "resource";

	public static final String PROFILE_ATTRIBUTE = "profile";


	protected final Log logger = LogFactory.getLog(getClass());

	@Nullable
	private XmlReaderContext readerContext;

	@Nullable
	private BeanDefinitionParserDelegate delegate;


	/**
	 * 此实现根据 "spring-beans" XSD（或历史上的 DTD）解析 Bean 定义。
	 *
	 * 核心流程：
	 * 1. 保存读取器上下文（用于后续的日志、错误报告等）
	 * 2. 获取 DOM 文档的根元素（通常是 <beans>）
	 * 3. 调用实际注册方法开始解析
	 *
	 * 解析过程包括：
	 * - 打开 DOM 文档
	 * - 初始化在 <beans/> 级别指定的默认设置（如 default-lazy-init、default-autowire 等）
	 * - 解析包含的 Bean 定义
	 *
	 * @param doc DOM 文档对象，由 XML 解析器生成
	 * @param readerContext 读取器当前上下文，包含注册表、资源、问题报告器等
	 */
	@Override
	public void registerBeanDefinitions(Document doc, XmlReaderContext readerContext) {
		// ============ 步骤1：保存读取器上下文 ============
		// 将 readerContext 保存为成员变量，供后续所有方法使用
		// readerContext 包含：
		//   - getRegistry()：Bean 定义注册表（DefaultListableBeanFactory）
		//   - getResource()：当前正在解析的资源
		//   - getProblemReporter()：问题报告器（用于记录警告和错误）
		//   - getEventListener()：事件监听器
		//   - getSourceExtractor()：源码提取器
		//   - getNamespaceHandlerResolver()：命名空间处理器解析器
		this.readerContext = readerContext;

		// ============ 步骤2：获取根元素并开始解析 ============
		// doc.getDocumentElement() 返回 DOM 文档的根元素
		// 对于 Spring 配置文件，根元素通常是 <beans>
		//
		// 示例 XML 结构：
		// <?xml version="1.0" encoding="UTF-8"?>
		// <beans xmlns="http://www.springframework.org/schema/beans"
		//        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
		//        xmlns:context="http://www.springframework.org/schema/context"
		//        xsi:schemaLocation="...">
		//     <bean id="..." class="..."/>
		//     <context:component-scan base-package="..."/>
		// </beans>
		//
		// 根元素就是 <beans> 标签对应的 Element 对象
		doRegisterBeanDefinitions(doc.getDocumentElement());
	}

	/**
	 * Return the descriptor for the XML resource that this parser works on.
	 */
	protected final XmlReaderContext getReaderContext() {
		Assert.state(this.readerContext != null, "No XmlReaderContext available");
		return this.readerContext;
	}

	/**
	 * Invoke the {@link org.springframework.beans.factory.parsing.SourceExtractor}
	 * to pull the source metadata from the supplied {@link Element}.
	 */
	@Nullable
	protected Object extractSource(Element ele) {
		return getReaderContext().extractSource(ele);
	}


	/**
	 * 注册给定根 {@code <beans/>} 元素中的每个 Bean 定义。
	 *
	 * 核心功能：
	 * 1. 支持嵌套的 <beans> 标签（通过递归处理）
	 * 2. 处理和传播 <beans> 标签的 default-* 属性
	 * 3. 根据 profile 属性决定是否跳过解析
	 * 4. 提供前置和后置处理扩展点
	 *
	 * @param root DOM 文档的根元素，通常是 <beans> 标签
	 */
	@SuppressWarnings("deprecation")  // 抑制 Environment.acceptsProfiles(String...) 的过时警告
	protected void doRegisterBeanDefinitions(Element root) {
		// ============ 步骤1：处理嵌套 <beans> 标签的委托栈 ============
		// 任何嵌套的 <beans> 元素都会导致此方法的递归调用。
		// 为了正确传播和保留 <beans> 的 default-* 属性（如 default-lazy-init、default-autowire 等），
		// 需要跟踪当前的（父级）委托对象，它可能为 null。
		//
		// 工作原理：
		// - 创建一个新的（子级）委托对象，并引用父级委托作为后备（fallback）
		// - 这样当子级 <beans> 没有定义某个 default-* 属性时，会从父级继承
		// - 最终将 this.delegate 重置回原来的（父级）引用
		// - 这种行为模拟了一个委托栈，而不需要实际维护一个栈结构
		//
		// 示例：
		// <beans default-lazy-init="true">
		//     <bean id="bean1" class="..."/>        <!-- 会继承 lazy-init="true" -->
		//     <beans default-lazy-init="false">
		//         <bean id="bean2" class="..."/>    <!-- 会使用 lazy-init="false" -->
		//     </beans>
		//     <bean id="bean3" class="..."/>        <!-- 又会继承 lazy-init="true" -->
		// </beans>
		BeanDefinitionParserDelegate parent = this.delegate;
		this.delegate = createDelegate(getReaderContext(), root, parent);

		// ============ 步骤2：处理 profile 属性（环境激活）============
		// 检查根元素是否属于默认命名空间（http://www.springframework.org/schema/beans）
		if (this.delegate.isDefaultNamespace(root)) {
			// 获取 profile 属性值，例如：<beans profile="dev,test">
			String profileSpec = root.getAttribute(PROFILE_ATTRIBUTE);
			if (StringUtils.hasText(profileSpec)) {
				// 将 profile 值拆分为数组（支持逗号、空格、分号等分隔符）
				// 例如："dev,test" → ["dev", "test"]
				String[] specifiedProfiles = StringUtils.tokenizeToStringArray(
						profileSpec, BeanDefinitionParserDelegate.MULTI_VALUE_ATTRIBUTE_DELIMITERS);

				// 注意：XML 配置中不支持 profile 表达式（如 !dev、dev & test 等）
				// 详情见 SPR-12458
				//
				// 检查当前激活的环境是否接受这些 profile
				// 例如：如果当前激活的 profile 是 "prod"，而配置文件要求 "dev"，
				//       则 acceptsProfiles 返回 false，整个配置文件被跳过
				if (!getReaderContext().getEnvironment().acceptsProfiles(specifiedProfiles)) {
					// 如果 profile 不匹配，记录 DEBUG 日志并跳过解析
					if (logger.isDebugEnabled()) {
						logger.debug("Skipped XML bean definition file due to specified profiles [" + profileSpec +
								"] not matching: " + getReaderContext().getResource());
					}
					return;  // 提前返回，不解析当前配置文件
				}
			}
		}

		// ============ 步骤3：前置处理（模板方法）============
		// 这是一个扩展点，允许子类在解析 Bean 定义之前做一些预处理
		// 例如：
		//   - 注册自定义的命名空间处理器
		//   - 添加自定义的 BeanDefinitionPostProcessor
		//   - 设置一些上下文变量
		// 默认实现为空方法
		preProcessXml(root);

		// ============ 步骤4：核心解析逻辑 ============
		// 遍历根元素的所有子节点，解析并注册 Bean 定义
		// 包括处理：<bean>、<import>、<alias>、自定义标签等
		parseBeanDefinitions(root, this.delegate);

		// ============ 步骤5：后置处理（模板方法）============
		// 另一个扩展点，允许子类在解析 Bean 定义之后做一些后处理
		// 例如：
		//   - 清理临时资源
		//   - 记录解析统计信息
		//   - 验证 Bean 定义的正确性
		// 默认实现为空方法
		postProcessXml(root);

		// ============ 步骤6：恢复委托对象 ============
		// 将 delegate 恢复为父级委托对象
		// 这样当递归返回到上层时，又能使用正确的委托对象
		this.delegate = parent;
	}

	protected BeanDefinitionParserDelegate createDelegate(
			XmlReaderContext readerContext, Element root, @Nullable BeanDefinitionParserDelegate parentDelegate) {

		BeanDefinitionParserDelegate delegate = new BeanDefinitionParserDelegate(readerContext);
		delegate.initDefaults(root, parentDelegate);
		return delegate;
	}

	/**
	 * 解析文档中根级别的元素：
	 * "import"、 "alias"、 "bean"。
	 *
	 * 这个方法负责遍历 XML 文档根元素的所有子节点，
	 * 并根据元素所属的命名空间进行不同的处理。
	 *
	 * @param root DOM 文档的根元素（通常是 <beans> 标签）
	 * @param delegate Bean 定义解析委托对象，负责实际的解析工作
	 */
	protected void parseBeanDefinitions(Element root, BeanDefinitionParserDelegate delegate) {
		// ============ 情况1：根元素属于默认命名空间 ============
		// 默认命名空间：http://www.springframework.org/schema/beans
		//
		// 如果根元素是 <beans>（默认命名空间），则需要遍历其所有子元素
		if (delegate.isDefaultNamespace(root)) {
			// 获取根元素的所有子节点
			NodeList nl = root.getChildNodes();

			// 遍历每个子节点
			for (int i = 0; i < nl.getLength(); i++) {
				Node node = nl.item(i);

				// 只处理元素节点（忽略文本节点、注释节点等）
				if (node instanceof Element) {
					Element ele = (Element) node;

					// 判断子元素是否属于默认命名空间
					if (delegate.isDefaultNamespace(ele)) {
						// ============ 默认命名空间的元素 ============
						// 处理 <import>、<alias>、<bean>、<beans> 标签
						parseDefaultElement(ele, delegate);
					}
					else {
						// ============ 自定义命名空间的元素 ============
						// 处理自定义标签，例如：
						//   <context:component-scan ...>
						//   <tx:annotation-driven ...>
						//   <aop:aspectj-autoproxy ...>
						//   <mvc:annotation-driven ...>
						//
						// 这些标签的处理会委托给对应的 NamespaceHandler
						delegate.parseCustomElement(ele);
					}
				}
			}
		}
		// ============ 情况2：根元素属于自定义命名空间 ============
		else {
			// 整个根元素就是一个自定义标签
			// 例如：配置文件直接以 <tx:annotation-driven> 作为根元素
			// 这种情况比较少见，但也是支持的
			delegate.parseCustomElement(root);
		}
	}

	/**
	 * 解析默认命名空间中的元素。
	 *
	 * 默认命名空间包含四种类型的元素：
	 * 1. <import>   - 导入其他配置文件
	 * 2. <alias>    - 为 Bean 定义别名
	 * 3. <bean>     - 定义 Bean
	 * 4. <beans>    - 嵌套的 beans 标签（支持递归）
	 *
	 * @param ele 当前要解析的元素
	 * @param delegate Bean 定义解析委托对象
	 */
	private void parseDefaultElement(Element ele, BeanDefinitionParserDelegate delegate) {
		// ============ 1. 处理 <import> 标签 ============
		// <import resource="classpath:applicationContext-datasource.xml"/>
		//
		// 作用：导入其他配置文件，类似于 C/C++ 的 #include
		// 支持递归导入，Spring 会递归解析被导入的文件
		if (delegate.nodeNameEquals(ele, IMPORT_ELEMENT)) {
			importBeanDefinitionResource(ele);
		}

		// ============ 2. 处理 <alias> 标签 ============
		// <alias name="userService" alias="userServiceAlias"/>
		//
		// 作用：为已有的 Bean 定义一个或多个别名
		// 注意：alias 必须在对应的 Bean 定义之后才会生效
		else if (delegate.nodeNameEquals(ele, ALIAS_ELEMENT)) {
			processAliasRegistration(ele);
		}

		// ============ 3. 处理 <bean> 标签 ============
		// <bean id="userService" class="com.example.UserService">
		//     <property name="userDao" ref="userDao"/>
		// </bean>
		//
		// 作用：定义 Bean 的元数据，包括：
		//   - Bean 的类名（class）
		//   - Bean 的作用域（scope）
		//   - 依赖关系（ref、depends-on）
		//   - 初始化/销毁方法（init-method、destroy-method）
		//   - 属性值（property、constructor-arg）
		//   - 等等...
		//
		// 这是 Spring IoC 容器最核心的配置元素！
		else if (delegate.nodeNameEquals(ele, BEAN_ELEMENT)) {
			processBeanDefinition(ele, delegate);
		}

		// ============ 4. 处理嵌套的 <beans> 标签 ============
		// <beans default-lazy-init="true">
		//     <bean id="..." class="..."/>
		//     <beans default-lazy-init="false">
		//         <bean id="..." class="..."/>
		//     </beans>
		// </beans>
		//
		// 作用：支持配置分组和属性继承
		// 递归调用 doRegisterBeanDefinitions，形成嵌套解析
		// 嵌套的 <beans> 可以覆盖父级的 default-* 属性
		else if (delegate.nodeNameEquals(ele, NESTED_BEANS_ELEMENT)) {
			// 递归调用，进入更深一层的解析
			doRegisterBeanDefinitions(ele);
		}
	}

	/**
	 * Parse an "import" element and load the bean definitions
	 * from the given resource into the bean factory.
	 */
	protected void importBeanDefinitionResource(Element ele) {
		String location = ele.getAttribute(RESOURCE_ATTRIBUTE);
		if (!StringUtils.hasText(location)) {
			getReaderContext().error("Resource location must not be empty", ele);
			return;
		}

		// Resolve system properties: e.g. "${user.dir}"
		location = getReaderContext().getEnvironment().resolveRequiredPlaceholders(location);

		Set<Resource> actualResources = new LinkedHashSet<>(4);

		// Discover whether the location is an absolute or relative URI
		boolean absoluteLocation = false;
		try {
			absoluteLocation = ResourcePatternUtils.isUrl(location) || ResourceUtils.toURI(location).isAbsolute();
		}
		catch (URISyntaxException ex) {
			// cannot convert to an URI, considering the location relative
			// unless it is the well-known Spring prefix "classpath*:"
		}

		// Absolute or relative?
		if (absoluteLocation) {
			try {
				int importCount = getReaderContext().getReader().loadBeanDefinitions(location, actualResources);
				if (logger.isTraceEnabled()) {
					logger.trace("Imported " + importCount + " bean definitions from URL location [" + location + "]");
				}
			}
			catch (BeanDefinitionStoreException ex) {
				getReaderContext().error(
						"Failed to import bean definitions from URL location [" + location + "]", ele, ex);
			}
		}
		else {
			// No URL -> considering resource location as relative to the current file.
			try {
				int importCount;
				Resource relativeResource = getReaderContext().getResource().createRelative(location);
				if (relativeResource.exists()) {
					importCount = getReaderContext().getReader().loadBeanDefinitions(relativeResource);
					actualResources.add(relativeResource);
				}
				else {
					String baseLocation = getReaderContext().getResource().getURL().toString();
					importCount = getReaderContext().getReader().loadBeanDefinitions(
							StringUtils.applyRelativePath(baseLocation, location), actualResources);
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Imported " + importCount + " bean definitions from relative location [" + location + "]");
				}
			}
			catch (IOException ex) {
				getReaderContext().error("Failed to resolve current resource location", ele, ex);
			}
			catch (BeanDefinitionStoreException ex) {
				getReaderContext().error(
						"Failed to import bean definitions from relative location [" + location + "]", ele, ex);
			}
		}
		Resource[] actResArray = actualResources.toArray(new Resource[0]);
		getReaderContext().fireImportProcessed(location, actResArray, extractSource(ele));
	}

	/**
	 * Process the given alias element, registering the alias with the registry.
	 */
	protected void processAliasRegistration(Element ele) {
		String name = ele.getAttribute(NAME_ATTRIBUTE);
		String alias = ele.getAttribute(ALIAS_ATTRIBUTE);
		boolean valid = true;
		if (!StringUtils.hasText(name)) {
			getReaderContext().error("Name must not be empty", ele);
			valid = false;
		}
		if (!StringUtils.hasText(alias)) {
			getReaderContext().error("Alias must not be empty", ele);
			valid = false;
		}
		if (valid) {
			try {
				getReaderContext().getRegistry().registerAlias(name, alias);
			}
			catch (Exception ex) {
				getReaderContext().error("Failed to register alias '" + alias +
						"' for bean with name '" + name + "'", ele, ex);
			}
			getReaderContext().fireAliasRegistered(name, alias, extractSource(ele));
		}
	}

	/**
	 * 处理给定的 bean 元素，解析 bean 定义并将其注册到注册表中。
	 *
	 * 这是 Spring XML 配置解析中最核心的方法之一，负责：
	 * 1. 将 <bean> 标签解析为 BeanDefinitionHolder 对象
	 * 2. 对 BeanDefinition 进行装饰（处理自定义标签和属性）
	 * 3. 将 BeanDefinition 注册到 BeanFactory
	 * 4. 触发组件注册事件
	 *
	 * @param ele 要解析的 <bean> 元素
	 * @param delegate Bean 定义解析委托对象，负责实际的解析工作
	 */
	protected void processBeanDefinition(Element ele, BeanDefinitionParserDelegate delegate) {
		// ============ 步骤1：解析 <bean> 元素为 BeanDefinitionHolder ============
		// parseBeanDefinitionElement 方法会：
		//   1. 解析 id 属性（Bean 的唯一标识）
		//   2. 解析 name 属性（可以包含多个别名，支持逗号、分号、空格分隔）
		//   3. 解析 class 属性（Bean 的全限定类名）
		//   4. 解析 parent 属性（父 Bean 定义）
		//   5. 解析 scope 属性（作用域：singleton、prototype、request、session 等）
		//   6. 解析 abstract 属性（是否是抽象 Bean）
		//   7. 解析 lazy-init 属性（是否懒加载）
		//   8. 解析 autowire 属性（自动装配模式：byName、byType、constructor 等）
		//   9. 解析 depends-on 属性（依赖的 Bean）
		//   10. 解析 init-method 属性（初始化方法）
		//   11. 解析 destroy-method 属性（销毁方法）
		//   12. 解析 factory-method 属性（工厂方法）
		//   13. 解析 factory-bean 属性（工厂 Bean）
		//   14. 解析 <property> 子元素（属性注入）
		//   15. 解析 <constructor-arg> 子元素（构造器注入）
		//   16. 解析 <qualifier> 子元素（限定符）
		//   17. 解析 <meta> 子元素（元数据）
		//   18. 解析 <lookup-method> 子元素（方法注入）
		//   19. 解析 <replaced-method> 子元素（方法替换）
		//
		// 返回值 BeanDefinitionHolder 包含：
		//   - beanDefinition：Bean 定义对象（存储所有元数据）
		//   - beanName：Bean 的名称
		//   - aliases：Bean 的别名数组
		BeanDefinitionHolder bdHolder = delegate.parseBeanDefinitionElement(ele);

		// 如果解析失败（例如缺少必需的 class 或 factory-bean 属性），bdHolder 为 null
		if (bdHolder != null) {
			// ============ 步骤2：装饰 BeanDefinition（如果需要）============
			// decorateBeanDefinitionIfRequired 方法会：
			//   1. 处理 <bean> 标签上的自定义属性（非默认命名空间的属性）
			//   2. 处理 <bean> 标签内部的子元素中的自定义标签
			//
			// 例如：
			//   <bean id="userService" class="com.example.UserService"
			//         p:userDao-ref="userDao"           ← 自定义属性（p 命名空间）
			//         util:properties="/config.properties"/>  ← 自定义属性
			//   </bean>
			//
			// 以及：
			//   <bean id="dataSource" class="...">
			//       <property name="url" value="..."/>
			//       <aop:scoped-proxy/>                 ← 自定义子元素
			//   </bean>
			bdHolder = delegate.decorateBeanDefinitionIfRequired(ele, bdHolder);

			try {
				// ============ 步骤3：注册 BeanDefinition 到注册表 ============
				// registerBeanDefinition 方法会：
				//   1. 检查是否存在同名的 Bean 定义
				//   2. 如果存在且不允许覆盖，则抛出异常
				//   3. 如果存在且允许覆盖，则替换旧的 Bean 定义
				//   4. 将 Bean 定义放入 beanDefinitionMap 中
				//   5. 将 Bean 名称放入 beanDefinitionNames 列表中
				//
				// 注意：此时只是注册了 Bean 的定义（元数据），
				//       Bean 的实例化还没有发生！
				//       真正的实例化在 finishBeanFactoryInitialization() 阶段
				BeanDefinitionReaderUtils.registerBeanDefinition(bdHolder, getReaderContext().getRegistry());
			}
			catch (BeanDefinitionStoreException ex) {
				// 注册失败时，记录错误信息
				// 错误可能的原因：
				//   - Bean 名称重复且不允许覆盖
				//   - Bean 定义格式错误
				//   - 循环依赖（在注册阶段就能检测到的）
				getReaderContext().error("Failed to register bean definition with name '" +
						bdHolder.getBeanName() + "'", ele, ex);
			}

			// ============ 步骤4：触发组件注册事件 ============
			// fireComponentRegistered 方法会通知所有注册的监听器
			// 这个事件通常用于：
			//   - Spring IDE 插件刷新视图
			//   - 自定义监听器跟踪 Bean 注册过程
			//   - 调试和监控工具
			//
			// BeanComponentDefinition 包含了：
			//   - Bean 定义信息
			//   - 来源（XML 文件位置）
			//   - 内部的嵌套 Bean 定义
			getReaderContext().fireComponentRegistered(new BeanComponentDefinition(bdHolder));
		}
	}


	/**
	 * Allow the XML to be extensible by processing any custom element types first,
	 * before we start to process the bean definitions. This method is a natural
	 * extension point for any other custom pre-processing of the XML.
	 * <p>The default implementation is empty. Subclasses can override this method to
	 * convert custom elements into standard Spring bean definitions, for example.
	 * Implementors have access to the parser's bean definition reader and the
	 * underlying XML resource, through the corresponding accessors.
	 * @see #getReaderContext()
	 */
	protected void preProcessXml(Element root) {
	}

	/**
	 * Allow the XML to be extensible by processing any custom element types last,
	 * after we finished processing the bean definitions. This method is a natural
	 * extension point for any other custom post-processing of the XML.
	 * <p>The default implementation is empty. Subclasses can override this method to
	 * convert custom elements into standard Spring bean definitions, for example.
	 * Implementors have access to the parser's bean definition reader and the
	 * underlying XML resource, through the corresponding accessors.
	 * @see #getReaderContext()
	 */
	protected void postProcessXml(Element root) {
	}

}
