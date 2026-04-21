/*
 * Copyright 2002-2015 the original author or authors.
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

import org.w3c.dom.Document;

import org.springframework.beans.factory.BeanDefinitionStoreException;

/**
 * SPI for parsing an XML document that contains Spring bean definitions.
 * Used by {@link XmlBeanDefinitionReader} for actually parsing a DOM document.
 *
 * <p>Instantiated per document to parse: implementations can hold
 * state in instance variables during the execution of the
 * {@code registerBeanDefinitions} method &mdash; for example, global
 * settings that are defined for all bean definitions in the document.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @since 18.12.2003
 * @see XmlBeanDefinitionReader#setDocumentReaderClass
 */
public interface BeanDefinitionDocumentReader {

	/**
	 * 从给定的 DOM 文档中读取 Bean 定义，并使用给定的读取器上下文将它们注册到注册表中。
	 *
	 * 这是 BeanDefinitionDocumentReader 接口的核心方法，定义了如何解析 XML 文档
	 * 并将其中的 Bean 定义注册到 Spring 容器中的规范。
	 *
	 * 实现类（如 DefaultBeanDefinitionDocumentReader）会：
	 * 1. 获取 DOM 文档的根元素（通常是 <beans>）
	 * 2. 解析根元素上的默认属性（如 default-lazy-init、default-autowire 等）
	 * 3. 遍历根元素的所有子元素
	 * 4. 对每个子元素进行解析：
	 *    - <import>：递归加载其他配置文件
	 *    - <alias>：为 Bean 注册别名
	 *    - <bean>：解析并注册 Bean 定义
	 *    - 自定义标签：委托给对应的 NamespaceHandler 处理
	 * 5. 处理嵌套的 <beans> 标签（支持配置分组）
	 * 6. 将解析过程中遇到的问题报告给 readerContext 中的问题报告器
	 *
	 * @param doc DOM 文档对象，由 XML 解析器生成
	 *            - 根元素通常是 <beans>
	 *            - 可能包含多个命名空间（如 context、aop、tx 等）
	 *            - 支持嵌套的 <beans> 标签
	 * @param readerContext 读取器当前上下文对象，包含：
	 *                      - registry：Bean 定义注册表（BeanFactory）
	 *                      - resource：正在解析的资源（用于错误定位）
	 *                      - problemReporter：问题报告器（记录警告和错误）
	 *                      - eventListener：事件监听器（发布解析事件）
	 *                      - sourceExtractor：源码提取器（提取位置信息）
	 *                      - namespaceHandlerResolver：命名空间处理器解析器
	 * @throws BeanDefinitionStoreException 如果解析过程中发生错误
	 *                                      - XML 结构错误
	 *                                      - Bean 定义格式错误
	 *                                      - 必需的属性缺失
	 *                                      - Bean 定义重复且不允许覆盖
	 */
	void registerBeanDefinitions(Document doc, XmlReaderContext readerContext)
			throws BeanDefinitionStoreException;

}
