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

package org.springframework.beans.factory.support;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Utility methods that are useful for bean definition reader implementations.
 * Mainly intended for internal use.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @since 1.1
 * @see PropertiesBeanDefinitionReader
 * @see org.springframework.beans.factory.xml.DefaultBeanDefinitionDocumentReader
 */
public abstract class BeanDefinitionReaderUtils {

	/**
	 * Separator for generated bean names. If a class name or parent name is not
	 * unique, "#1", "#2" etc will be appended, until the name becomes unique.
	 */
	public static final String GENERATED_BEAN_NAME_SEPARATOR = BeanFactoryUtils.GENERATED_BEAN_NAME_SEPARATOR;


	/**
	 * Create a new GenericBeanDefinition for the given parent name and class name,
	 * eagerly loading the bean class if a ClassLoader has been specified.
	 * @param parentName the name of the parent bean, if any
	 * @param className the name of the bean class, if any
	 * @param classLoader the ClassLoader to use for loading bean classes
	 * (can be {@code null} to just register bean classes by name)
	 * @return the bean definition
	 * @throws ClassNotFoundException if the bean class could not be loaded
	 */
	public static AbstractBeanDefinition createBeanDefinition(
			@Nullable String parentName, @Nullable String className, @Nullable ClassLoader classLoader) throws ClassNotFoundException {

		GenericBeanDefinition bd = new GenericBeanDefinition();
		bd.setParentName(parentName);
		if (className != null) {
			if (classLoader != null) {
				bd.setBeanClass(ClassUtils.forName(className, classLoader));
			}
			else {
				bd.setBeanClassName(className);
			}
		}
		return bd;
	}

	/**
	 * Generate a bean name for the given top-level bean definition,
	 * unique within the given bean factory.
	 * @param beanDefinition the bean definition to generate a bean name for
	 * @param registry the bean factory that the definition is going to be
	 * registered with (to check for existing bean names)
	 * @return the generated bean name
	 * @throws BeanDefinitionStoreException if no unique name can be generated
	 * for the given bean definition
	 * @see #generateBeanName(BeanDefinition, BeanDefinitionRegistry, boolean)
	 */
	public static String generateBeanName(BeanDefinition beanDefinition, BeanDefinitionRegistry registry)
			throws BeanDefinitionStoreException {

		return generateBeanName(beanDefinition, registry, false);
	}

	/**
	 * Generate a bean name for the given bean definition, unique within the
	 * given bean factory.
	 * @param definition the bean definition to generate a bean name for
	 * @param registry the bean factory that the definition is going to be
	 * registered with (to check for existing bean names)
	 * @param isInnerBean whether the given bean definition will be registered
	 * as inner bean or as top-level bean (allowing for special name generation
	 * for inner beans versus top-level beans)
	 * @return the generated bean name
	 * @throws BeanDefinitionStoreException if no unique name can be generated
	 * for the given bean definition
	 */
	public static String generateBeanName(
			BeanDefinition definition, BeanDefinitionRegistry registry, boolean isInnerBean)
			throws BeanDefinitionStoreException {

		String generatedBeanName = definition.getBeanClassName();
		if (generatedBeanName == null) {
			if (definition.getParentName() != null) {
				generatedBeanName = definition.getParentName() + "$child";
			}
			else if (definition.getFactoryBeanName() != null) {
				generatedBeanName = definition.getFactoryBeanName() + "$created";
			}
		}
		if (!StringUtils.hasText(generatedBeanName)) {
			throw new BeanDefinitionStoreException("Unnamed bean definition specifies neither " +
					"'class' nor 'parent' nor 'factory-bean' - can't generate bean name");
		}

		if (isInnerBean) {
			// Inner bean: generate identity hashcode suffix.
			return generatedBeanName + GENERATED_BEAN_NAME_SEPARATOR + ObjectUtils.getIdentityHexString(definition);
		}

		// Top-level bean: use plain class name with unique suffix if necessary.
		return uniqueBeanName(generatedBeanName, registry);
	}

	/**
	 * Turn the given bean name into a unique bean name for the given bean factory,
	 * appending a unique counter as suffix if necessary.
	 * @param beanName the original bean name
	 * @param registry the bean factory that the definition is going to be
	 * registered with (to check for existing bean names)
	 * @return the unique bean name to use
	 * @since 5.1
	 */
	public static String uniqueBeanName(String beanName, BeanDefinitionRegistry registry) {
		String id = beanName;
		int counter = -1;

		// Increase counter until the id is unique.
		String prefix = beanName + GENERATED_BEAN_NAME_SEPARATOR;
		while (counter == -1 || registry.containsBeanDefinition(id)) {
			counter++;
			id = prefix + counter;
		}
		return id;
	}

	/**
	 * 使用给定的 Bean 工厂注册给定的 Bean 定义。
	 *
	 * 这个方法负责将解析完成的 BeanDefinition 注册到 BeanFactory 中，
	 * 同时处理 Bean 的别名注册。
	 *
	 * 核心流程：
	 * 1. 获取 Bean 的主名称
	 * 2. 将 BeanDefinition 注册到注册表（使用主名称）
	 * 3. 遍历并注册所有别名（如果存在）
	 *
	 * @param definitionHolder Bean 定义持有者，包含：
	 *                         - beanDefinition：Bean 的元数据定义
	 *                         - beanName：Bean 的主名称（通常是 id 属性或生成的名称）
	 *                         - aliases：Bean 的别名数组（来自 name 属性）
	 * @param registry Bean 定义注册表，通常是 DefaultListableBeanFactory 实例
	 * @throws BeanDefinitionStoreException 如果注册失败
	 *         - Bean 名称重复且不允许覆盖
	 *         - Bean 定义格式错误
	 *         - 循环别名（A 是 B 的别名，B 又是 A 的别名）
	 */
	public static void registerBeanDefinition(
			BeanDefinitionHolder definitionHolder, BeanDefinitionRegistry registry)
			throws BeanDefinitionStoreException {

		// ============ 步骤1：获取 Bean 的主名称 ============
		// beanName 来源优先级：
		//   1. id 属性（如果明确指定）
		//   2. name 属性中的第一个名称（如果指定了多个）
		//   3. 自动生成的名称（如 "com.example.UserService#0"）
		//
		// 示例：
		//   <bean id="userService" name="service,svc" class="..."/>
		//   → beanName = "userService"
		//   → aliases = ["service", "svc"]
		String beanName = definitionHolder.getBeanName();

		// ============ 步骤2：注册主 Bean 定义 ============
		// registry.registerBeanDefinition() 内部会做：
		//   1. 检查是否已存在同名的 BeanDefinition
		//   2. 如果存在且不允许覆盖（allowBeanDefinitionOverriding = false），抛出异常
		//   3. 如果存在且允许覆盖，则替换旧的 BeanDefinition
		//   4. 将 BeanDefinition 存入 beanDefinitionMap（ConcurrentHashMap）
		//   5. 将 beanName 添加到 beanDefinitionNames（List）中
		//   6. 清除相关的缓存（如 mergedBeanDefinition 缓存）
		//   7. 重置手动注册的单例标记
		//
		// 注意：此时只是注册了 Bean 的定义（元数据），
		//       Bean 的实例化还没有发生！
		//       真正的实例化在 finishBeanFactoryInitialization() 阶段
		registry.registerBeanDefinition(beanName, definitionHolder.getBeanDefinition());

		// ============ 步骤3：注册别名 ============
		// 别名的作用：
		//   1. 可以通过多个名称获取同一个 Bean
		//   2. 便于在不同模块中使用不同的命名约定
		//   3. 兼容旧代码中的 Bean 名称
		//   4. 实现配置的灵活性
		//
		// 别名注册规则：
		//   - 一个别名只能指向一个 Bean（不能重复映射）
		//   - 一个 Bean 可以有多个别名
		//   - 别名不能与现有的 Bean 名称重复（有优先顺序）
		//   - 不能形成循环别名（A→B, B→A）
		//   - 别名注册不会触发 Bean 实例化
		String[] aliases = definitionHolder.getAliases();
		if (aliases != null) {
			// 遍历所有别名，逐个注册
			for (String alias : aliases) {
				// registry.registerAlias() 内部会做：
				//   1. 检查别名是否已被其他 Bean 使用
				//   2. 检查是否形成循环别名
				//   3. 将 alias → beanName 的映射存入 aliasMap（ConcurrentHashMap）
				//   4. 如果别名已被使用且不允许覆盖，抛出异常
				registry.registerAlias(beanName, alias);
			}
		}
	}

	/**
	 * Register the given bean definition with a generated name,
	 * unique within the given bean factory.
	 * @param definition the bean definition to generate a bean name for
	 * @param registry the bean factory to register with
	 * @return the generated bean name
	 * @throws BeanDefinitionStoreException if no unique name can be generated
	 * for the given bean definition or the definition cannot be registered
	 */
	public static String registerWithGeneratedName(
			AbstractBeanDefinition definition, BeanDefinitionRegistry registry)
			throws BeanDefinitionStoreException {

		String generatedName = generateBeanName(definition, registry, false);
		registry.registerBeanDefinition(generatedName, definition);
		return generatedName;
	}

}
