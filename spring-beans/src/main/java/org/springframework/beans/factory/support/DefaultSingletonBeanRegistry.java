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

package org.springframework.beans.factory.support;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCreationNotAllowedException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.core.SimpleAliasRegistry;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Generic registry for shared bean instances, implementing the
 * {@link org.springframework.beans.factory.config.SingletonBeanRegistry}.
 * Allows for registering singleton instances that should be shared
 * for all callers of the registry, to be obtained via bean name.
 *
 * <p>Also supports registration of
 * {@link org.springframework.beans.factory.DisposableBean} instances,
 * (which might or might not correspond to registered singletons),
 * to be destroyed on shutdown of the registry. Dependencies between
 * beans can be registered to enforce an appropriate shutdown order.
 *
 * <p>This class mainly serves as base class for
 * {@link org.springframework.beans.factory.BeanFactory} implementations,
 * factoring out the common management of singleton bean instances. Note that
 * the {@link org.springframework.beans.factory.config.ConfigurableBeanFactory}
 * interface extends the {@link SingletonBeanRegistry} interface.
 *
 * <p>Note that this class assumes neither a bean definition concept
 * nor a specific creation process for bean instances, in contrast to
 * {@link AbstractBeanFactory} and {@link DefaultListableBeanFactory}
 * (which inherit from it). Can alternatively also be used as a nested
 * helper to delegate to.
 *
 * @author Juergen Hoeller
 * @since 2.0
 * @see #registerSingleton
 * @see #registerDisposableBean
 * @see org.springframework.beans.factory.DisposableBean
 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory
 */
public class DefaultSingletonBeanRegistry extends SimpleAliasRegistry implements SingletonBeanRegistry {

	/**
	 * Maximum number of suppressed exceptions to preserve.
	 * 要保留的被抑制异常的最大数量
	 */
	private static final int SUPPRESSED_EXCEPTIONS_LIMIT = 100;

	/**
	 * Cache of singleton objects: bean name to bean instance.
	 * 单例对象缓存：bean名称 -> bean实例
	 * 一级缓存：存放已经完全创建好的单例对象（已完成实例化、属性注入、初始化）
	 * 使用 ConcurrentHashMap 保证线程安全
	 */
	private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

	/**
	 * Cache of singleton factories: bean name to ObjectFactory.
	 * 单例工厂缓存：bean名称 -> ObjectFactory（对象工厂）
	 * 三级缓存：存放用于生成 bean 早期引用的对象工厂
	 * 主要用于解决循环依赖，在 bean 实例化后、初始化前将工厂放入此缓存
	 * 注意：此缓存仅在单例模式下使用，且不是线程安全的（配合锁机制使用）
	 */
	private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);

	/**
	 * Cache of early singleton objects: bean name to bean instance.
	 * 早期单例对象缓存：bean名称 -> bean实例
	 * 二级缓存：存放提前暴露的早期引用对象（已完成实例化，但尚未完成属性注入和初始化）
	 * 当从三级缓存获取工厂并创建对象后，会移入此缓存
	 * 使用 ConcurrentHashMap 保证线程安全
	 */
	private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);

	/**
	 * Set of registered singletons, containing the bean names in registration order.
	 * 已注册的单例名称集合，按注册顺序排列
	 * 用于维护单例的注册顺序，支持有序遍历
	 * 使用 LinkedHashSet 保持插入顺序
	 */
	private final Set<String> registeredSingletons = new LinkedHashSet<>(256);

	/**
	 * Names of beans that are currently in creation.
	 * 当前正在创建中的 bean 名称集合
	 * 用于标记哪些 bean 正在创建过程中，用于检测循环依赖
	 * 使用 ConcurrentHashMap 实现的线程安全 Set
	 */
	private final Set<String> singletonsCurrentlyInCreation =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/**
	 * Names of beans currently excluded from in creation checks.
	 * 当前从创建检查中排除的 bean 名称集合
	 * 用于标记某些 bean 不需要进行循环依赖检查（如某些内部 bean）
	 * 使用 ConcurrentHashMap 实现的线程安全 Set
	 */
	private final Set<String> inCreationCheckExclusions =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/**
	 * Collection of suppressed Exceptions, available for associating related causes.
	 * 被抑制的异常集合，用于关联相关原因
	 * 在创建 bean 过程中遇到多个异常时，可将次要异常抑制并记录于此
	 * 主要用于调试和错误报告，记录创建失败时的相关异常信息
	 */
	@Nullable
	private Set<Exception> suppressedExceptions;

	/**
	 * Flag that indicates whether we're currently within destroySingletons.
	 * 标记当前是否正在执行 destroySingletons 方法
	 * 当为 true 时，表示容器正在销毁所有单例对象
	 * 用于在销毁过程中避免某些操作（如注册新的销毁回调）
	 */
	private boolean singletonsCurrentlyInDestruction = false;

	/**
	 * Disposable bean instances: bean name to disposable instance.
	 * 可销毁的 bean 实例映射：bean名称 -> DisposableBean 实例
	 * 存放实现了 DisposableBean 接口或配置了 destroy-method 的 bean
	 * 在容器关闭时需要调用这些 bean 的销毁方法
	 * 使用 LinkedHashMap 维护注册顺序
	 */
	private final Map<String, DisposableBean> disposableBeans = new LinkedHashMap<>();

	/**
	 * Map between containing bean names: bean name to Set of bean names that the bean contains.
	 * 包含关系映射：bean名称 -> 该 bean 包含的其他 bean 名称集合
	 * 记录一个 bean 内部包含的其他 bean（如内部类、内部 bean 定义）
	 * 用于在销毁时级联销毁包含的子 bean
	 * 使用 ConcurrentHashMap 保证线程安全
	 */
	private final Map<String, Set<String>> containedBeanMap = new ConcurrentHashMap<>(16);

	/**
	 * Map between dependent bean names: bean name to Set of dependent bean names.
	 * 依赖关系映射：bean名称 -> 依赖于该 bean 的其他 bean 名称集合
	 * 记录哪些 bean 依赖于当前 bean
	 * 用于在销毁当前 bean 时，先销毁所有依赖它的 bean
	 * 使用 ConcurrentHashMap（初始容量64）减少哈希冲突
	 */
	private final Map<String, Set<String>> dependentBeanMap = new ConcurrentHashMap<>(64);

	/**
	 * Map between depending bean names: bean name to Set of bean names for the bean's dependencies.
	 * 被依赖关系映射：bean名称 -> 该 bean 所依赖的其他 bean 名称集合
	 * 记录当前 bean 依赖于哪些其他 bean
	 * 是 dependentBeanMap 的逆向关系，用于循环依赖检测和依赖关系分析
	 * 使用 ConcurrentHashMap（初始容量64）减少哈希冲突
	 */
	private final Map<String, Set<String>> dependenciesForBeanMap = new ConcurrentHashMap<>(64);


	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		Assert.notNull(beanName, "Bean name must not be null");
		Assert.notNull(singletonObject, "Singleton object must not be null");
		synchronized (this.singletonObjects) {
			Object oldObject = this.singletonObjects.get(beanName);
			if (oldObject != null) {
				throw new IllegalStateException("Could not register object [" + singletonObject +
						"] under bean name '" + beanName + "': there is already object [" + oldObject + "] bound");
			}
			addSingleton(beanName, singletonObject);
		}
	}

	/**
	 * 将给定的单例对象添加到工厂的单例缓存中。
	 * <p>用于单例的提前注册（eager registration）。
	 *
	 * <p><b>调用时机：</b>
	 * 在 Bean 完全创建完成后（包括实例化、属性填充、初始化、所有后置处理器执行完毕），
	 * 且 afterSingletonCreation 执行之后，finally 块的最后一步调用。
	 *
	 * <p><b>核心作用：</b>
	 * 将完全初始化好的 Bean 放入一级缓存（singletonObjects），
	 * 同时清理其他各级缓存中的临时数据。
	 *
	 * <p><b>Spring 三级缓存机制：</b>
	 * <pre>
	 * 1. singletonObjects       （一级缓存）Map：完全初始化好的单例 Bean
	 * 2. earlySingletonObjects  （二级缓存）Map：提前暴露的早期 Bean（未完成属性填充）
	 * 3. singletonFactories     （三级缓存）Map：单例工厂，用于生成早期引用
	 * </pre>
	 *
	 * <p><b>操作方法：</b>
	 * <ul>
	 *   <li>{@link #getSingleton(String)} - 从一级缓存获取 Bean</li>
	 *   <li>{@link #addSingleton(String, Object)} - 将完成的 Bean 放入一级缓存（当前方法）</li>
	 *   <li>{@link #addSingletonFactory(String, ObjectFactory)} - 添加三级缓存工厂</li>
	 *   <li>{@link #getSingleton(String, boolean)} - 允许检查早期引用的获取</li>
	 * </ul>
	 *
	 * <p><b>缓存状态转换：</b>
	 * <pre>
	 * 创建前：所有缓存中都没有该 Bean
	 *   ↓
	 * 实例化后：addSingletonFactory → 放入三级缓存（singletonFactories）
	 *   ↓
	 * 循环依赖检测到时：从三级缓存移至二级缓存（earlySingletonObjects）
	 *   ↓
	 * 完全创建完成后：<b>addSingleton → 放入一级缓存，清理二、三级缓存</b>
	 * </pre>
	 *
	 * <p><b>为什么要清理其他缓存：</b>
	 * <ul>
	 *   <li><b>singletonFactories.remove()</b>：工厂已完成使命，不再需要提前生成代理</li>
	 *   <li><b>earlySingletonObjects.remove()</b>：早期引用已被最终版本替代</li>
	 *   <li><b>registeredSingletons.add()</b>：记录已注册的单例名称，用于管理</li>
	 * </ul>
	 *
	 * <p><b>线程安全：</b>
	 * 使用 synchronized 锁住 singletonObjects，确保在多线程环境下的缓存操作原子性。
	 * 这是防止并发问题的重要保障。
	 *
	 * @param beanName Bean 的名称
	 * @param singletonObject 完全初始化完成的单例对象（最终版本）
	 * @see #getSingleton
	 * @see #addSingletonFactory
	 * @see DefaultSingletonBeanRegistry#singletonObjects
	 */
	protected void addSingleton(String beanName, Object singletonObject) {
		// 使用 synchronized 确保缓存操作的原子性
		// 锁对象是 singletonObjects（一级缓存）
		synchronized (this.singletonObjects) {
			// 1️⃣ 将完全初始化好的 Bean 放入一级缓存
			// 这是最终给 getBean() 返回的对象
			this.singletonObjects.put(beanName, singletonObject);

			// 2️⃣ 移除三级缓存中的工厂
			// 工厂的作用是提前生成 Bean 的早期引用，现在已经完成使命
			// 清理可以释放内存，避免不必要的引用
			this.singletonFactories.remove(beanName);

			// 3️⃣ 移除二级缓存中的早期引用
			// 早期引用是在循环依赖时从三级缓存提升过来的
			// 现在已经有了最终版本，需要移除早期引用
			this.earlySingletonObjects.remove(beanName);

			// 4️⃣ 记录已注册的单例名称
			// 用于管理和追踪所有已注册的单例 Bean
			this.registeredSingletons.add(beanName);
		}
	}

	/**
	 * Add the given singleton factory for building the specified singleton
	 * if necessary.
	 * <p>To be called for eager registration of singletons, e.g. to be able to
	 * resolve circular references.
	 * @param beanName the name of the bean
	 * @param singletonFactory the factory for the singleton object
	 */
	protected void addSingletonFactory(String beanName, ObjectFactory<?> singletonFactory) {
		Assert.notNull(singletonFactory, "Singleton factory must not be null");
		synchronized (this.singletonObjects) {
			if (!this.singletonObjects.containsKey(beanName)) {
				this.singletonFactories.put(beanName, singletonFactory);
				this.earlySingletonObjects.remove(beanName);
				this.registeredSingletons.add(beanName);
			}
		}
	}

	/**
	 * 根据 bean 名称获取单例对象（允许早期引用）
	 * 这是 SingletonBeanRegistry 接口方法的实现
	 *
	 * @param beanName bean的名称
	 * @return 注册的单例对象，如果不存在则返回 null
	 */
	@Override
	@Nullable
	public Object getSingleton(String beanName) {
		// 调用重载方法，allowEarlyReference 参数设为 true，允许获取正在创建中的 bean 的早期引用
		// 这样可以在循环依赖场景下，提前暴露尚未完全初始化的 bean 引用
		return getSingleton(beanName, true);
	}

	/**
	 * 根据给定的名称返回（原始）单例对象
	 * <p>检查已实例化的单例，并允许对正在创建的单例进行早期引用（用于解决循环依赖）
	 *
	 * @param beanName 要查找的 bean 名称
	 * @param allowEarlyReference 是否允许创建早期引用
	 * @return 注册的单例对象，如果未找到则返回 {@code null}
	 */
	@Nullable
	protected Object getSingleton(String beanName, boolean allowEarlyReference) {
		// 第一步：快速检查现有实例，避免加完整的单例锁（性能优化）
		// 从一级缓存（singletonObjects）获取：存放已经完全创建好的单例对象
		Object singletonObject = this.singletonObjects.get(beanName);// 循环依赖2.单例池中没有A // 14单例池中没有B // 26 获取A还是没有 // 33 获取B还是没有 // 41 获取A还是没有

		// 如果一级缓存中没有，并且当前 bean 正在创建中（说明可能发生了循环依赖）
		if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {// 循环依赖3.正在创建池中也没有A // 15 正在创建池中没有B // 27 正在创建池中有A  // 34 但是B确实正在创建 // 42 正在创建池中有A

			// 第二步：从二级缓存（earlySingletonObjects）获取
			// 二级缓存存放的是提前暴露的早期引用对象（尚未完成属性注入和初始化）
			singletonObject = this.earlySingletonObjects.get(beanName);// 28 获取到早期的A实例，但是这时候还没有A实例 // 35 获取B但是这里有A没有B(注意这里不允许循环引用，所以下面if进不去) // 43 早期池里面有A（注意这里不允许循环引用）

			// 如果二级缓存中也没有，并且允许创建早期引用
			if (singletonObject == null && allowEarlyReference) {// 29 以下代码允许早期引用，从三级工厂中获取A的那个lambda表达式获取A实例并从三级缓存中移动到二级缓存（早期引用池）

				// 第三步：加锁同步，保证线程安全
				// 使用 singletonObjects 作为锁对象，与其他单例操作保持一致性
				synchronized (this.singletonObjects) {

					// 双重检查锁定（Double-Check Locking）
					// 再次从一级缓存获取（可能其他线程已经创建好了）
					singletonObject = this.singletonObjects.get(beanName);
					if (singletonObject == null) {
						// 从二级缓存获取（可能其他线程已经提前暴露了）
						singletonObject = this.earlySingletonObjects.get(beanName);
						if (singletonObject == null) {

							// 第四步：从三级缓存（singletonFactories）获取对象工厂
							// 三级缓存存放的是 ObjectFactory，用于生成 bean 的早期引用
							ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);
							if (singletonFactory != null) {
								// 通过工厂获取对象（此时对象尚未完成完整初始化）
								singletonObject = singletonFactory.getObject();

								// 将早期引用对象放入二级缓存
								this.earlySingletonObjects.put(beanName, singletonObject);

								// 从三级缓存中移除（因为已经用于创建早期引用，不需要再保留）
								this.singletonFactories.remove(beanName);
							}
						}
					}
				}
			}
		}

		// 返回找到的单例对象，可能为 null
		return singletonObject;// 循环依赖16.正在创建池中没有B，直接返回 // 30 返回早期的A实例 // 36 获取不到早期的B实例并且不允许循环引用，返回null // 44 返回早期池的A实例
	}

	/**
	 * 返回在给定名称下注册的（原始）单例对象，
	 * 如果尚未注册，则创建并注册一个新的单例对象。
	 *
	 * @param beanName bean 的名称
	 * @param singletonFactory 用于在需要时懒加载创建单例的 ObjectFactory
	 * @return 注册的单例对象
	 */
	public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
		Assert.notNull(beanName, "Bean name must not be null");

		// 使用单例对象缓存作为锁，保证线程安全
		synchronized (this.singletonObjects) {
			// ============ 步骤1：再次检查缓存（双重检查锁模式）============
			Object singletonObject = this.singletonObjects.get(beanName);// 循环依赖6. 再次判断单例池中有没有A，没有A // 19 再次判断单例池中有没有B
			if (singletonObject == null) {

				// ============ 步骤2：检查容器是否正在销毁 ============
				if (this.singletonsCurrentlyInDestruction) {
					throw new BeanCreationNotAllowedException(beanName,
							"Singleton bean creation not allowed while singletons of this factory are in destruction " +
									"(Do not request a bean from a BeanFactory in a destroy method implementation!)");
				}

				if (logger.isDebugEnabled()) {
					logger.debug("Creating shared instance of singleton bean '" + beanName + "'");
				}

				// ============ 步骤3：创建前回调 ============
				// 将 beanName 添加到 singletonsCurrentlyInCreation 集合中
				// 标记这个单例正在创建中（用于检测循环依赖）
				beforeSingletonCreation(beanName);// 循环依赖7. 标记A单实例正在创建 // 20 标记B单实例正在创建

				boolean newSingleton = false;
				boolean recordSuppressedExceptions = (this.suppressedExceptions == null);
				if (recordSuppressedExceptions) {
					this.suppressedExceptions = new LinkedHashSet<>();
				}

				try {
					// ============ 步骤4：调用工厂方法创建 bean ============
					// 这里的 singletonFactory.getObject() 实际会调用 createBean()
					singletonObject = singletonFactory.getObject();// 循环依赖8. 执行lambda表达式创建A实例 // 21执行lambda表达式创建B实例
					newSingleton = true;
				} catch (IllegalStateException ex) {
					// 并发情况：其他线程可能已经创建了该单例
					// 再次从缓存中获取，如果存在则返回，否则抛出异常
					singletonObject = this.singletonObjects.get(beanName);
					if (singletonObject == null) {
						throw ex;
					}
				} catch (BeanCreationException ex) {
					// 记录所有被抑制的异常，用于调试
					if (recordSuppressedExceptions) {
						for (Exception suppressedException : this.suppressedExceptions) {
							ex.addRelatedCause(suppressedException);
						}
					}
					throw ex;
				} finally {
					// ============ 步骤5：创建后回调 ============
					// 从 singletonsCurrentlyInCreation 集合中移除 beanName
					afterSingletonCreation(beanName);// 循环依赖 37. 移除B的单实例创建状态 // 45移除A的单实例创建状态
					if (recordSuppressedExceptions) {
						this.suppressedExceptions = null;
					}
				}

				// ============ 步骤6：注册新创建的单例 ============
				if (newSingleton) {
					addSingleton(beanName, singletonObject);// 循环依赖 38.向单例池中放入B实例并且在工厂池（三级缓存）移除B //46 向单例池中放入A实例并移除其他池中的A实例
				}
			}
			return singletonObject;
		}
	}

	/**
	 * Register an exception that happened to get suppressed during the creation of a
	 * singleton bean instance, e.g. a temporary circular reference resolution problem.
	 * <p>The default implementation preserves any given exception in this registry's
	 * collection of suppressed exceptions, up to a limit of 100 exceptions, adding
	 * them as related causes to an eventual top-level {@link BeanCreationException}.
	 * @param ex the Exception to register
	 * @see BeanCreationException#getRelatedCauses()
	 */
	protected void onSuppressedException(Exception ex) {
		synchronized (this.singletonObjects) {
			if (this.suppressedExceptions != null && this.suppressedExceptions.size() < SUPPRESSED_EXCEPTIONS_LIMIT) {
				this.suppressedExceptions.add(ex);
			}
		}
	}

	/**
	 * Remove the bean with the given name from the singleton cache of this factory,
	 * to be able to clean up eager registration of a singleton if creation failed.
	 * @param beanName the name of the bean
	 * @see #getSingletonMutex()
	 */
	protected void removeSingleton(String beanName) {
		synchronized (this.singletonObjects) {
			this.singletonObjects.remove(beanName);
			this.singletonFactories.remove(beanName);
			this.earlySingletonObjects.remove(beanName);
			this.registeredSingletons.remove(beanName);
		}
	}

	@Override
	public boolean containsSingleton(String beanName) {
		return this.singletonObjects.containsKey(beanName);
	}

	@Override
	public String[] getSingletonNames() {
		synchronized (this.singletonObjects) {
			return StringUtils.toStringArray(this.registeredSingletons);
		}
	}

	@Override
	public int getSingletonCount() {
		synchronized (this.singletonObjects) {
			return this.registeredSingletons.size();
		}
	}


	public void setCurrentlyInCreation(String beanName, boolean inCreation) {
		Assert.notNull(beanName, "Bean name must not be null");
		if (!inCreation) {
			this.inCreationCheckExclusions.add(beanName);
		}
		else {
			this.inCreationCheckExclusions.remove(beanName);
		}
	}

	public boolean isCurrentlyInCreation(String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		return (!this.inCreationCheckExclusions.contains(beanName) && isActuallyInCreation(beanName));
	}

	protected boolean isActuallyInCreation(String beanName) {
		return isSingletonCurrentlyInCreation(beanName);
	}

	/**
	 * 判断指定的单例 bean 当前是否正在创建过程中（在整个 BeanFactory 范围内）。
	 * <p>该检查用于检测循环依赖场景：当检测到 bean 正在被创建时再次被请求，
	 * 通常意味着存在构造器循环依赖（无法通过提前暴露来解决的情况）。
	 *
	 * @param beanName bean 的名称，可以为 null（如果为 null 则直接返回 false）
	 * @return true 表示该 bean 当前正在被创建，false 表示未在创建或不存在
	 *
	 * @see #registerSingletonCurrentlyInCreation(String)
	 * @see #removeSingletonCurrentlyInCreation(String)
	 * @see DefaultSingletonBeanRegistry#singletonsCurrentlyInCreation
	 */
	public boolean isSingletonCurrentlyInCreation(@Nullable String beanName) {
		// singletonsCurrentlyInCreation 是一个 Set<String>，用于记录当前正在创建的单例 bean 名称
		// 若 beanName 为 null，contains 方法会正常返回 false（集合通常不支持 null 元素）
		return this.singletonsCurrentlyInCreation.contains(beanName);
	}

	/**
	 * Callback before singleton creation.
	 * <p>The default implementation register the singleton as currently in creation.
	 * 单例创建之前的回调方法。
	 * <p>默认实现将单例注册为正在创建中。
	 *
	 * @param beanName the name of the singleton about to be created
	 *             即将创建的单例的名称
	 * @see #isSingletonCurrentlyInCreation
	 */
	protected void beforeSingletonCreation(String beanName) {
		// 检查两点：
		// 1. 当前 bean 没有被排除在创建检查之外（inCreationCheckExclusions 集合中不包含该 beanName）
		// 2. 尝试将 beanName 添加到 singletonsCurrentlyInCreation 集合中，如果添加失败（说明已经存在）
		//    - singletonsCurrentlyInCreation.add(beanName) 返回 false 表示该 bean 已经在创建中
		if (!this.inCreationCheckExclusions.contains(beanName)
				&& !this.singletonsCurrentlyInCreation.add(beanName)) {

			// 如果 bean 没有被排除检查，但已经在创建中（重复创建尝试）
			// 则抛出 BeanCurrentlyInCreationException 异常，表示存在循环依赖或重复创建
			throw new BeanCurrentlyInCreationException(beanName);
		}
	}

	/**
	 * 单例 Bean 创建完成后的回调方法。
	 * <p>默认实现将单例标记为不再处于创建中状态。
	 *
	 * <p><b>调用时机：</b>
	 * 在 Bean 实例完全创建完成后（包括实例化、属性填充、初始化、所有后置处理器执行完毕），
	 * 即将放入一级缓存（singletonObjects）之前调用。
	 *
	 * <p><b>核心作用：</b>
	 * 清理创建状态标记，将当前 Bean 从"正在创建中"的集合中移除。
	 * 这是 Spring 解决循环依赖的关键机制之一。
	 *
	 * <p><b>与 beforeSingletonCreation 的对应关系：</b>
	 * <pre>
	 * beforeSingletonCreation(beanName)  ← 开始创建时标记
	 *   → 创建 Bean 的各种操作
	 *   → afterSingletonCreation(beanName)  ← 创建完成后清除标记
	 * </pre>
	 *
	 * <p><b>状态管理机制：</b>
	 * <ul>
	 *   <li>singletonsCurrentlyInCreation：Set 集合，记录当前正在创建中的单例 Bean 名称</li>
	 *   <li>开始创建时：singletonsCurrentlyInCreation.add(beanName)</li>
	 *   <li>创建完成时：singletonsCurrentlyInCreation.remove(beanName) ← 当前方法</li>
	 * </ul>
	 *
	 * <p><b>异常场景：</b>
	 * 如果尝试移除一个不在创建中的 Bean 名称，会抛出 IllegalStateException，
	 * 这通常表示出现了不正常的创建流程（如重复移除或未正确开始）
	 *
	 * <p><b>与循环依赖的关系：</b>
	 * <pre>
	 * 循环依赖场景下的状态变化：
	 * 1. 创建 A → beforeSingletonCreation 标记 A（A 在创建中）
	 * 2. A 依赖 B → 开始创建 B → beforeSingletonCreation 标记 B
	 * 3. B 依赖 A → 发现 A 在创建中 → 通过早期引用解决循环依赖
	 * 4. B 创建完成 → afterSingletonCreation 移除 B
	 * 5. A 继续完成 → afterSingletonCreation 移除 A
	 * </pre>
	 *
	 * <p><b>注意事项：</b>
	 * <ul>
	 *   <li>此方法在 finally 块中被调用，确保即使创建失败也会清理状态</li>
	 *   <li>通过 inCreationCheckExclusions 可以排除某些 Bean 的创建状态检查</li>
	 *   <li>这是容器内部方法，不是扩展点，开发者无法直接干预</li>
	 * </ul>
	 *
	 * @param beanName 已经创建完成的单例 Bean 名称
	 * @see #beforeSingletonCreation
	 * @see #isSingletonCurrentlyInCreation
	 * @see #singletonsCurrentlyInCreation
	 */
	protected void afterSingletonCreation(String beanName) {
		// 检查排除列表：某些 Bean 不需要进行创建状态检查（如通过 getBean 获取的已存在 Bean）
		// 然后尝试从"正在创建中"的集合中移除当前 Bean 名称
		// 如果移除失败（集合中不存在），说明状态不一致，抛出异常
		if (!this.inCreationCheckExclusions.contains(beanName)
				&& !this.singletonsCurrentlyInCreation.remove(beanName)) {
			throw new IllegalStateException("Singleton '" + beanName + "' isn't currently in creation");
		}
	}


	/**
	 * Add the given bean to the list of disposable beans in this registry.
	 * <p>Disposable beans usually correspond to registered singletons,
	 * matching the bean name but potentially being a different instance
	 * (for example, a DisposableBean adapter for a singleton that does not
	 * naturally implement Spring's DisposableBean interface).
	 * @param beanName the name of the bean
	 * @param bean the bean instance
	 */
	public void registerDisposableBean(String beanName, DisposableBean bean) {
		synchronized (this.disposableBeans) {
			this.disposableBeans.put(beanName, bean);
		}
	}

	/**
	 * Register a containment relationship between two beans,
	 * e.g. between an inner bean and its containing outer bean.
	 * <p>Also registers the containing bean as dependent on the contained bean
	 * in terms of destruction order.
	 * @param containedBeanName the name of the contained (inner) bean
	 * @param containingBeanName the name of the containing (outer) bean
	 * @see #registerDependentBean
	 */
	public void registerContainedBean(String containedBeanName, String containingBeanName) {
		synchronized (this.containedBeanMap) {
			Set<String> containedBeans =
					this.containedBeanMap.computeIfAbsent(containingBeanName, k -> new LinkedHashSet<>(8));
			if (!containedBeans.add(containedBeanName)) {
				return;
			}
		}
		registerDependentBean(containedBeanName, containingBeanName);
	}

	/**
	 * Register a dependent bean for the given bean,
	 * to be destroyed before the given bean is destroyed.
	 * @param beanName the name of the bean
	 * @param dependentBeanName the name of the dependent bean
	 */
	public void registerDependentBean(String beanName, String dependentBeanName) {
		String canonicalName = canonicalName(beanName);

		synchronized (this.dependentBeanMap) {
			Set<String> dependentBeans =
					this.dependentBeanMap.computeIfAbsent(canonicalName, k -> new LinkedHashSet<>(8));
			if (!dependentBeans.add(dependentBeanName)) {
				return;
			}
		}

		synchronized (this.dependenciesForBeanMap) {
			Set<String> dependenciesForBean =
					this.dependenciesForBeanMap.computeIfAbsent(dependentBeanName, k -> new LinkedHashSet<>(8));
			dependenciesForBean.add(canonicalName);
		}
	}

	/**
	 * Determine whether the specified dependent bean has been registered as
	 * dependent on the given bean or on any of its transitive dependencies.
	 * @param beanName the name of the bean to check
	 * @param dependentBeanName the name of the dependent bean
	 * @since 4.0
	 */
	protected boolean isDependent(String beanName, String dependentBeanName) {
		synchronized (this.dependentBeanMap) {
			return isDependent(beanName, dependentBeanName, null);
		}
	}

	private boolean isDependent(String beanName, String dependentBeanName, @Nullable Set<String> alreadySeen) {
		if (alreadySeen != null && alreadySeen.contains(beanName)) {
			return false;
		}
		String canonicalName = canonicalName(beanName);
		Set<String> dependentBeans = this.dependentBeanMap.get(canonicalName);
		if (dependentBeans == null || dependentBeans.isEmpty()) {
			return false;
		}
		if (dependentBeans.contains(dependentBeanName)) {
			return true;
		}
		if (alreadySeen == null) {
			alreadySeen = new HashSet<>();
		}
		alreadySeen.add(beanName);
		for (String transitiveDependency : dependentBeans) {
			if (isDependent(transitiveDependency, dependentBeanName, alreadySeen)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Determine whether a dependent bean has been registered for the given name.
	 * @param beanName the name of the bean to check
	 */
	protected boolean hasDependentBean(String beanName) {
		return this.dependentBeanMap.containsKey(beanName);
	}

	/**
	 * Return the names of all beans which depend on the specified bean, if any.
	 * @param beanName the name of the bean
	 * @return the array of dependent bean names, or an empty array if none
	 */
	public String[] getDependentBeans(String beanName) {
		Set<String> dependentBeans = this.dependentBeanMap.get(beanName);
		if (dependentBeans == null) {
			return new String[0];
		}
		synchronized (this.dependentBeanMap) {
			return StringUtils.toStringArray(dependentBeans);
		}
	}

	/**
	 * Return the names of all beans that the specified bean depends on, if any.
	 * @param beanName the name of the bean
	 * @return the array of names of beans which the bean depends on,
	 * or an empty array if none
	 */
	public String[] getDependenciesForBean(String beanName) {
		Set<String> dependenciesForBean = this.dependenciesForBeanMap.get(beanName);
		if (dependenciesForBean == null) {
			return new String[0];
		}
		synchronized (this.dependenciesForBeanMap) {
			return StringUtils.toStringArray(dependenciesForBean);
		}
	}

	public void destroySingletons() {
		if (logger.isTraceEnabled()) {
			logger.trace("Destroying singletons in " + this);
		}
		synchronized (this.singletonObjects) {
			this.singletonsCurrentlyInDestruction = true;
		}

		String[] disposableBeanNames;
		synchronized (this.disposableBeans) {
			disposableBeanNames = StringUtils.toStringArray(this.disposableBeans.keySet());
		}
		for (int i = disposableBeanNames.length - 1; i >= 0; i--) {
			destroySingleton(disposableBeanNames[i]);
		}

		this.containedBeanMap.clear();
		this.dependentBeanMap.clear();
		this.dependenciesForBeanMap.clear();

		clearSingletonCache();
	}

	/**
	 * Clear all cached singleton instances in this registry.
	 * @since 4.3.15
	 */
	protected void clearSingletonCache() {
		synchronized (this.singletonObjects) {
			this.singletonObjects.clear();
			this.singletonFactories.clear();
			this.earlySingletonObjects.clear();
			this.registeredSingletons.clear();
			this.singletonsCurrentlyInDestruction = false;
		}
	}

	/**
	 * Destroy the given bean. Delegates to {@code destroyBean}
	 * if a corresponding disposable bean instance is found.
	 * @param beanName the name of the bean
	 * @see #destroyBean
	 */
	public void destroySingleton(String beanName) {
		// Remove a registered singleton of the given name, if any.
		removeSingleton(beanName);

		// Destroy the corresponding DisposableBean instance.
		DisposableBean disposableBean;
		synchronized (this.disposableBeans) {
			disposableBean = this.disposableBeans.remove(beanName);
		}
		destroyBean(beanName, disposableBean);
	}

	/**
	 * Destroy the given bean. Must destroy beans that depend on the given
	 * bean before the bean itself. Should not throw any exceptions.
	 * @param beanName the name of the bean
	 * @param bean the bean instance to destroy
	 */
	protected void destroyBean(String beanName, @Nullable DisposableBean bean) {
		// Trigger destruction of dependent beans first...
		Set<String> dependentBeanNames;
		synchronized (this.dependentBeanMap) {
			// Within full synchronization in order to guarantee a disconnected Set
			dependentBeanNames = this.dependentBeanMap.remove(beanName);
		}
		if (dependentBeanNames != null) {
			if (logger.isTraceEnabled()) {
				logger.trace("Retrieved dependent beans for bean '" + beanName + "': " + dependentBeanNames);
			}
			for (String dependentBeanName : dependentBeanNames) {
				destroySingleton(dependentBeanName);
			}
		}

		// Actually destroy the bean now...
		if (bean != null) {
			try {
				bean.destroy();
			}
			catch (Throwable ex) {
				if (logger.isWarnEnabled()) {
					logger.warn("Destruction of bean with name '" + beanName + "' threw an exception", ex);
				}
			}
		}

		// Trigger destruction of contained beans...
		Set<String> containedBeans;
		synchronized (this.containedBeanMap) {
			// Within full synchronization in order to guarantee a disconnected Set
			containedBeans = this.containedBeanMap.remove(beanName);
		}
		if (containedBeans != null) {
			for (String containedBeanName : containedBeans) {
				destroySingleton(containedBeanName);
			}
		}

		// Remove destroyed bean from other beans' dependencies.
		synchronized (this.dependentBeanMap) {
			for (Iterator<Map.Entry<String, Set<String>>> it = this.dependentBeanMap.entrySet().iterator(); it.hasNext();) {
				Map.Entry<String, Set<String>> entry = it.next();
				Set<String> dependenciesToClean = entry.getValue();
				dependenciesToClean.remove(beanName);
				if (dependenciesToClean.isEmpty()) {
					it.remove();
				}
			}
		}

		// Remove destroyed bean's prepared dependency information.
		this.dependenciesForBeanMap.remove(beanName);
	}

	/**
	 * Exposes the singleton mutex to subclasses and external collaborators.
	 * <p>Subclasses should synchronize on the given Object if they perform
	 * any sort of extended singleton creation phase. In particular, subclasses
	 * should <i>not</i> have their own mutexes involved in singleton creation,
	 * to avoid the potential for deadlocks in lazy-init situations.
	 */
	@Override
	public final Object getSingletonMutex() {
		return this.singletonObjects;
	}

}
