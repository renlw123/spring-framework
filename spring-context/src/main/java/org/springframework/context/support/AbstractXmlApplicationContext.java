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

package org.springframework.context.support;

import java.io.IOException;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.BeanDefinitionDocumentReader;
import org.springframework.beans.factory.xml.ResourceEntityResolver;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;

/**
 * Convenient base class for {@link org.springframework.context.ApplicationContext}
 * implementations, drawing configuration from XML documents containing bean definitions
 * understood by an {@link org.springframework.beans.factory.xml.XmlBeanDefinitionReader}.
 *
 * <p>Subclasses just have to implement the {@link #getConfigResources} and/or
 * the {@link #getConfigLocations} method. Furthermore, they might override
 * the {@link #getResourceByPath} hook to interpret relative paths in an
 * environment-specific fashion, and/or {@link #getResourcePatternResolver}
 * for extended pattern resolution.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see #getConfigResources
 * @see #getConfigLocations
 * @see org.springframework.beans.factory.xml.XmlBeanDefinitionReader
 */
public abstract class AbstractXmlApplicationContext extends AbstractRefreshableConfigApplicationContext {

	private boolean validating = true;


	/**
	 * Create a new AbstractXmlApplicationContext with no parent.
	 */
	public AbstractXmlApplicationContext() {
	}

	/**
	 * Create a new AbstractXmlApplicationContext with the given parent context.
	 * @param parent the parent context
	 */
	public AbstractXmlApplicationContext(@Nullable ApplicationContext parent) {
		super(parent);
	}


	/**
	 * Set whether to use XML validation. Default is {@code true}.
	 */
	public void setValidating(boolean validating) {
		this.validating = validating;
	}


	/**
	 * 通过 XmlBeanDefinitionReader 加载 Bean 定义。
	 *
	 * 这是 AbstractXmlApplicationContext 中的实现，
	 * ClassPathXmlApplicationContext 通过继承链使用此方法。
	 *
	 * 核心流程：
	 * 1. 创建 XmlBeanDefinitionReader（XML Bean 定义读取器）
	 * 2. 配置读取器的环境、资源加载器、实体解析器
	 * 3. 初始化读取器（子类扩展点）
	 * 4. 执行实际的 Bean 定义加载
	 *
	 * @see org.springframework.beans.factory.xml.XmlBeanDefinitionReader
	 * @see #initBeanDefinitionReader
	 * @see #loadBeanDefinitions(org.springframework.beans.factory.xml.XmlBeanDefinitionReader)
	 */
	@Override
	protected void loadBeanDefinitions(DefaultListableBeanFactory beanFactory) throws BeansException, IOException {
		// ============ 步骤1：创建 XML Bean 定义读取器 ============
		// XmlBeanDefinitionReader 是专门用于解析 XML 配置文件的读取器
		// 它负责：
		//   - 读取 XML 配置文件（支持多种资源：类路径、文件系统、URL 等）
		//   - 解析 <beans>、<bean>、<import>、<alias> 等标签
		//   - 将 XML 中的每个 <bean> 元素转换为 BeanDefinition 对象
		//   - 将 BeanDefinition 注册到 BeanFactory 中
		XmlBeanDefinitionReader beanDefinitionReader = new XmlBeanDefinitionReader(beanFactory);

		// ============ 步骤2：配置 Bean 定义读取器 ============

		// 2.1 设置环境（Environment）
		// Environment 包含了：
		//   - profiles（激活的配置文件，如 dev、prod）
		//   - properties（系统属性、环境变量、自定义属性源）
		// 这样在解析 XML 时可以根据环境决定是否加载某些 Bean
		beanDefinitionReader.setEnvironment(getEnvironment());

		// 2.2 设置资源加载器（ResourceLoader）
		// 将当前 ApplicationContext 自身作为资源加载器
		// 因为 ApplicationContext 实现了 ResourceLoader 接口
		// 支持以下资源路径格式：
		//   - 类路径：classpath:applicationContext.xml
		//   - 文件系统：file:/path/to/config.xml
		//   - URL：http://example.com/config.xml
		beanDefinitionReader.setResourceLoader(this);

		// 2.3 设置实体解析器（EntityResolver）
		// ResourceEntityResolver 用于解析 XML 中的 DTD 和 Schema 声明
		// 例如：<!DOCTYPE beans PUBLIC "-//SPRING//DTD BEAN 2.0//EN" "http://www.springframework.org/dtd/spring-beans-2.0.dtd">
		//
		// 作用：优先从类路径下查找 spring.schemas 和 spring.tlds 文件
		// 避免在解析 XML 时去外网下载 DTD/Schema 文件，提高解析效率
		// 同时也支持离线解析（不需要网络连接）
		beanDefinitionReader.setEntityResolver(new ResourceEntityResolver(this));

		// ============ 步骤3：初始化 Bean 定义读取器（模板方法）============
		// 这是一个扩展点，允许子类对 XmlBeanDefinitionReader 进行自定义初始化
		// 例如：
		//   - 设置验证模式（DTD 还是 XSD）
		//   - 设置是否允许 Bean 定义覆盖
		//   - 设置是否忽略 XML 中的注释
		// 默认实现为空方法
		initBeanDefinitionReader(beanDefinitionReader);

		// ============ 步骤4：执行实际的 Bean 定义加载 ============
		// 这个方法会根据当前 ApplicationContext 的配置位置（configLocations）
		// 加载所有 XML 配置文件，并注册所有 Bean 定义
		//
		// 加载过程：
		//   1. 遍历 configLocations（配置文件路径数组）
		//   2. 将每个路径解析为 Resource 对象
		//   3. 对每个 Resource 调用 XmlBeanDefinitionReader 的 loadBeanDefinitions 方法
		//   4. 解析 XML 文件，生成 BeanDefinition
		//   5. 将 BeanDefinition 注册到 BeanFactory
		loadBeanDefinitions(beanDefinitionReader);
	}

	/**
	 * Initialize the bean definition reader used for loading the bean definitions
	 * of this context. The default implementation sets the validating flag.
	 * <p>Can be overridden in subclasses, e.g. for turning off XML validation
	 * or using a different {@link BeanDefinitionDocumentReader} implementation.
	 * @param reader the bean definition reader used by this context
	 * @see XmlBeanDefinitionReader#setValidating
	 * @see XmlBeanDefinitionReader#setDocumentReaderClass
	 */
	protected void initBeanDefinitionReader(XmlBeanDefinitionReader reader) {
		reader.setValidating(this.validating);
	}

	/**
	 * 使用给定的 XmlBeanDefinitionReader 加载 Bean 定义。
	 *
	 * 核心流程：
	 * 1. 优先加载通过编程方式设置的 Resource 数组（直接配置的资源对象）
	 * 2. 然后加载通过字符串路径指定的配置文件位置
	 *
	 * 设计说明：
	 * - 这个方法只负责加载和注册 Bean 定义，不负责 BeanFactory 的生命周期管理
	 * - BeanFactory 的生命周期（创建、销毁）由 refreshBeanFactory() 方法管理
	 * - 支持两种配置来源：Resource 对象数组 和 配置文件路径字符串数组
	 *
	 * @param reader 已经配置好的 XmlBeanDefinitionReader（用于解析 XML 并注册 Bean 定义）
	 * @throws BeansException 如果 Bean 定义注册过程中发生错误
	 * @throws IOException 如果找不到指定的 XML 文档或读取失败
	 * @see #refreshBeanFactory      // BeanFactory 的生命周期管理
	 * @see #getConfigLocations      // 获取配置文件路径数组
	 * @see #getResources            // 获取 Resource 资源数组（子类可覆盖）
	 * @see #getResourcePatternResolver  // 获取资源模式解析器（支持通配符）
	 */
	protected void loadBeanDefinitions(XmlBeanDefinitionReader reader) throws BeansException, IOException {

		// ============ 方式1：从 Resource 数组加载 Bean 定义 ============
		// getConfigResources() 是一个模板方法，默认返回 null
		// 子类可以覆盖这个方法，直接返回 Resource 对象数组
		//
		// 使用场景：
		//   1. 编程式创建 ApplicationContext 时直接传入 Resource 对象
		//   2. 需要从非标准位置（如数据库、网络）加载配置时
		//   3. 需要对 Resource 进行预处理（解密、转换等）时
		//
		// 示例：
		//   Resource[] resources = new Resource[] {
		//       new ClassPathResource("beans1.xml"),
		//       new FileSystemResource("/path/to/beans2.xml")
		//   };
		Resource[] configResources = getConfigResources();
		if (configResources != null) {
			// 批量加载多个 Resource 资源
			// reader.loadBeanDefinitions() 会：
			//   1. 遍历每个 Resource
			//   2. 解析 XML 内容
			//   3. 将 Bean 定义注册到 BeanFactory
			//   4. 返回总共加载的 Bean 定义数量（返回值被忽略）
			reader.loadBeanDefinitions(configResources);
		}

		// ============ 方式2：从配置文件路径数组加载 Bean 定义 ============
		// getConfigLocations() 返回在构造函数中设置的配置文件路径
		// 例如：new ClassPathXmlApplicationContext("beans.xml", "services.xml")
		//
		// 支持的路径格式：
		//   - 类路径：    "classpath:applicationContext.xml"
		//   - 文件系统：  "file:/home/config/beans.xml"
		//   - 相对路径：  "beans.xml"（默认从类路径加载）
		//   - 通配符：    "classpath*:config/**/*.xml"（匹配多个文件）
		//   - URL：       "http://example.com/config.xml"
		String[] configLocations = getConfigLocations();
		if (configLocations != null) {
			// 批量加载多个配置文件
			// reader.loadBeanDefinitions() 内部会：
			//   1. 将每个路径字符串解析为 Resource 对象
			//   2. 支持 Ant 风格的通配符（如 "classpath*:config/**/*.xml"）
			//   3. 解析每个 XML 文件并注册 Bean 定义
			reader.loadBeanDefinitions(configLocations);
		}

		// 注意：两种方式可以同时使用！
		// 如果同时设置了 configResources 和 configLocations，
		// 那么两者都会被加载，且 configResources 优先加载
	}

	/**
	 * Return an array of Resource objects, referring to the XML bean definition
	 * files that this context should be built with.
	 * <p>The default implementation returns {@code null}. Subclasses can override
	 * this to provide pre-built Resource objects rather than location Strings.
	 * @return an array of Resource objects, or {@code null} if none
	 * @see #getConfigLocations()
	 */
	@Nullable
	protected Resource[] getConfigResources() {
		return null;
	}

}
