/*
 * Copyright 1999-2011 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.common.extension;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.support.ActivateComparator;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Dubbo使用的扩展点获取。<p>
 * <ul>
 * <li>自动注入关联扩展点。</li>
 * <li>自动Wrap上扩展点的Wrap类。</li>
 * <li>缺省获得的的扩展点是一个Adaptive Instance。
 * </ul>
 *
 * @author william.liangf
 * @author ding.lid
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">JDK5.0的自动发现机制实现</a>
 * @see com.alibaba.dubbo.common.extension.SPI
 * @see com.alibaba.dubbo.common.extension.Adaptive
 * @see com.alibaba.dubbo.common.extension.Activate
 */

/**
 * 数据结构信息
 * 1）包含文件加载的路径，如"META-INF/services/"等
 * 2）包含扩展的相关缓存，如ConcurrentMap<Class<?>, ExtensionLoader<?>> 类与扩展加载器的缓存
 * ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES 类与接口实例的缓存
 * ConcurrentMap<Class<?>, String> cachedNames 类与扩展名的映射关系 等等
 * 3）缓存工厂ExtensionFactory、Set<Class<?>> cachedWrapperClasses 缓存的类集合
 * 4）异常缓存Map<String, IllegalStateException>等
 */
public class ExtensionLoader<T> {  //称谓：扩展类的加载器  10/30-实践点，泛型了解以及使用

    /**
     *  10/30-实践点，static、final了解以及使用
     */
    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    private static final String SERVICES_DIRECTORY = "META-INF/services/";

    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";

    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*"); //匹配任何空白字符，分隔符  10/29 待了解

    /**@c ExtensionLoader 本地缓存，将接口类型type与ExtensionLoader扩展类映射缓存起来 */
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();

    // 缓存接口Class与接口的实例类映射关系
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<Class<?>, Object>();

    // ==============================

    /**
     * Class 一些方法取值输出：
     * 例如： type = Transport.class中type= "interface **.Transport"
     * type.getName = "**.Transport"
     * type.getSimpleName = "Transport"
     */
    private final Class<?> type; //扩展接口的类型

    private final ExtensionFactory objectFactory; /**@c objectFactory用途 ：通过工厂方法获取扩展类，在创建ExtensionLoader时设置 */

    // 缓存接口Class与扩展名的映射关系
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<Class<?>, String>(); //存入例如 key=com.alibaba.dubbo.remoting.transport.netty.NettyTransporter  name=netty

    /**@c 此处用途？持有对象管理扩展名与接口Class的映射关系 */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<Map<String, Class<?>>>();

    // 扩展名与自动激活注解@Active的映射 //11/04 为啥没有存自定义@Activate，如SelfFilter，解：因为这个缓存存的时候是判断是否有@Activate注解的
    private final Map<String, Activate> cachedActivates = new ConcurrentHashMap<String, Activate>();

    // 扩展名与实例的持有对象的映射
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<String, Holder<Object>>();

    /**
     * 10/30 怎么区分是共享变量还是对象私有变量
     * 解：共享变量就是类变量（静态变量），所有对象共有； 私有变量是每个对象单独拥有
     */
    // 缓存中的实例对象，只存在一个实例
    private final Holder<Object> cachedAdaptiveInstance = new Holder<Object>(); //缓存中对象
    private volatile Class<?> cachedAdaptiveClass = null;  //缓存自适应类的class
    private String cachedDefaultName; //缓存SPI中value值，在加载配置文件时写入loadExtensionClasses
    private volatile Throwable createAdaptiveInstanceError;

    private Set<Class<?>> cachedWrapperClasses;
    // 扩展类对应的异常集合，如key=netty
    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<String, IllegalStateException>();

    private ExtensionLoader(Class<?> type) {/**@c 私有的构造方法，对外隐藏 */
        this.type = type;
        /**@c objectFactory负责所有IOC创建的对象 对象工厂  10/30 此处的递归待了解，调试下，一个非ExtensionFactory设置两次，为啥objectFactory不为空？而是一个AdaptiveExtensionFactory的实例 */
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    // 11/2 @Activate @Adaper 是怎么通过校验的？
    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);/**@c 判断接口是否包含SPI注解 */  // 10/29 Annotation注解接口了解&实践
    }

    /**
     * 获取指定接口的加载器  10/30 静态方法构造属于单例模式吗
     * 因为ExtensionLoader不提供公有构造函数，使用静态方法构造和获取
     */
    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        if (!type.isInterface()) {/**@c 扩展类型是接口类型 */
            throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
        }
        //只有加上SPI注解的才允许使用dubbo的SPI功能
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type(" + type +
                    ") is not extension, because WITHOUT @" + SPI.class.getSimpleName() + " Annotation!");
        }

        //从内存中获取SPI扩展类, EXTENSION_LOADERS何时写入 : 初始时递归调用
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {//如果为null，创建新的对象设置进去
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type)); //有两个key，一个是接口class，另一个是ExtensionFactory
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    /**
     * 获取类加载器
     */
    private static ClassLoader findClassLoader() {
        return ExtensionLoader.class.getClassLoader(); //基本类型或void类型，返回null
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    // 从缓存中获取指定接口的扩展名
    public String getExtensionName(Class<?> extensionClass) {
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to <pre>
     *     getActivateExtension(url, key, null);
     * </pre>
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String, String)
     */
    //  此方法用途： 获取url中指定key对应的扩展实例
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to <pre>
     *     getActivateExtension(url, values, null);
     * </pre>
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }


    /**
     * overwrite
     * 获取接口类型的扩展类
     * 1.判断type是否为空
     * 2.判断type是否为接口
     * 3.判断type是否带有SPI注解
     * 4.从本地缓存中EXTENSION_LOADERS获取接口的扩展类，若没有创建扩展类，并返回
     *
     * 递归调用，如type在EXTENSION_LOADERS中不存在，会先创建ExtensionFactory.class的映射 arr[0]，得到扩展类，然后在查找type的映射 arr[1]
     *
     * ExtensionLoader 没有公有的构造函数，调用getExtensionLoader获取扩展实例
     * 一开始会创建两个ExtensionLoader对象，1）type为ExtensionFactory的对象， 2）具体的ExtensionFactory的对象，比如Transport的ExtensionFactory的对象
     * 一个是工厂扩展器对象，另一个是具体想创建的扩展器对象。
     *
     */
    public static<T> ExtensionLoader<T> getExtensionLoaderOverwrite(Class<T> type) { //泛型形参声明   SPI步骤01
        if (type == null) {
            throw new IllegalArgumentException("Extension type is null"); //非法参数异常
        }
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
        }
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not SPI Annotation");
        }
        ExtensionLoader<T> loader = (ExtensionLoader<T>)EXTENSION_LOADERS.get(type);
        if (loader == null) {
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type)); //此处会递归调用
            loader = (ExtensionLoader<T>)EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    /**
     * overwrite
     * 私有的构造函数
     * 1.为当前对象的type赋值
     * 2.为当前对象的objectFactory赋值
     *   判断type是否是ExtensionFactory类型，若是置为null，若不是则type改为ExtensionFactory继续调用
     */
//    private ExtensionLoaderOverwrite(Class<?> type) { //SPI步骤02  记录下扩展接口类型以及使用的扩展工厂实例
//        this.type = type;
//        this.objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
//        //10/29 直到type == ExtensionFactory.class终止，那其它类型都做了啥，为啥用递归？待调试
//    }


    /**
     * 获取自动激活扩展点的实例列表
     * This is equivalent to <pre>
     *     getActivateExtension(url, url.getParameter(key).split(","), null);
     * </pre>
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     */

    /**
     * 从url中获取key对应的value，并且将value按分隔符进行拆分，形成值列表
     * 比如：test://localhost/test?ext=order1,default， ext对应的值为order1,default
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        String value = url.getParameter(key);
        //value == null || value.length() == 0 ? null : Constants.COMMA_SPLIT_PATTERN.split(value) 将值按分隔符分隔
        return getActivateExtension(url, value == null || value.length() == 0 ? null : Constants.COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.（获取激活的扩展列表）
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see com.alibaba.dubbo.common.extension.Activate
     */

    /**
     * 11/02 values的用途？解：从调用源头可以看出，values是从url根据指定key获取到并按分隔符进行分隔的
     * getActivateExtension 函数重载，既可以直接传入用于匹配values，也可以传入key，从url中查找values
     *
     * 思路：
     * 1）获取扩展点列表；从缓存中获取或者加载配置文件获取
     * 2）筛选扩展点列表：按传入条件筛选的values、group条件与@Activate注解上声明的值进行比对
     * 3）排序扩展点列表；对获取到的扩展点列表进行排序
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        //new String[]{"","-"} 数组会报错  - 会跳过第一个判断，""进入第二判断 getExtension(name)传入空字符会报错
        List<T> exts = new ArrayList<T>();
        List<String> names = values == null ? new ArrayList<String>(0) : Arrays.asList(values);
        /**
         * 系统的扩展点
         */
        if (!names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) {
            getExtensionClasses();
            for (Map.Entry<String, Activate> entry : cachedActivates.entrySet()) { //遍历缓存中activate的Map映射关系
                String name = entry.getKey();
                Activate activate = entry.getValue();
                if (isMatchGroup(group, activate.group())) { //判断group是否匹配
                    T ext = getExtension(name);
                    if (!names.contains(name)
                            && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)
                            && isActive(activate, url)) { //若Activate中的values不为空，则需要url需要存在value值
                        exts.add(ext);
                    }
                }
            }
            Collections.sort(exts, ActivateComparator.COMPARATOR); //按指定的比较器将列表进行排序
        }

        /**
         * 用户设置的扩展点，如SelfFilter
         * 11/04 为啥没有按注解判断？在哪里区分是用户还是系统的扩展点的？
         * 解：加载文件时loadFile，会判断是否带着Activate注解，带着的会放入cachedActivates，没在这个缓存中，表明没有注解，所以不用按注解判断
         */
        List<T> usrs = new ArrayList<T>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (!name.startsWith(Constants.REMOVE_VALUE_PREFIX)
                    && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)) {
                if (Constants.DEFAULT_KEY.equals(name)) { //{"group","order1","","order2"};
                    if (usrs.size() > 0) {
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                    T ext = getExtension(name);
                    usrs.add(ext);
                }
            }
        }
        if (usrs.size() > 0) {
            exts.addAll(usrs);
        }
        return exts;
    }

    //判断组group是否在注解中group数组里 (将传入的group值，与注解中的group[] 比较)
    private boolean isMatchGroup(String group, String[] groups) {
        //未传入group值时，与注解中的group值匹配
        if (group == null || group.length() == 0) {
            return true;
        }

        //条件：group是否在groups  匹配group参数
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    //判断注解的key数组中的值是否在url的参数中
    private boolean isActive(Activate activate, URL url) { //Active 自动激活加载扩展
        String[] keys = activate.value();
        if (keys == null || keys.length == 0) { //@Activate中values没设置值
            return true;
        }

        //条件：activate.value是否为空, 匹配value参数
        for (String key : keys) {  //遍历注解中的value数组，看value值是否存在url参数列表中
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key)) //判断url中参数列表是否包含@Activate的values值
                        && ConfigUtils.isNotEmpty(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 返回扩展点实例，如果没有指定的扩展点或是还没加载（即实例化）则返回<code>null</code>。注意：此方法不会触发扩展点的加载。
     * <p/>
     * 一般应该调用{@link #getExtension(String)}方法获得扩展，这个方法会触发扩展点加载。
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        Holder<Object> holder = cachedInstances.get(name); //Holder存储泛型对象
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        return (T) holder.get();
    }

    /**
     * 返回已经加载的扩展点的名字。
     * <p/>
     * 一般应该调用{@link #getSupportedExtensions()}方法获得扩展，这个方法会返回所有的扩展点。
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<String>(cachedInstances.keySet()));
    }

    /**
     * @overwrite 获取@Adaptivate注解标识的扩展
     * 1）将values赋值给names列表，作为加载文件的key查询
     * 2）names不包含移除前缀"-"  【匹配group以及value值】
     *    2.1）遍历缓存中@Activaty集合cachedActivaty
     *    2.2) 获取name、Activaty，获取到注解上的group数组
     *    2.3）匹配传入的group是否在注解上group数组里面 isMathGroup
     *      2.3.1）若存在，则判断value是否在url的参数中isActivaty()。
     *      2.3.2) 若传入values不为空，遍历的name不在values数组中
     *      若添加都满足，表明是需要的实例getExtension(name)，创建实例并且加入到返回列表List<T> extList
     *
     * 3）names不会空(传入的values不为空) 【传入values值，获取指定的name实例】
     *    3.1）遍历names,将name做判断，若等于DEFAULT_KEY即""时，并且看之前是否有缓存的列表，若有添加到返回列表，并清除临时列表
     *    3.2）若不等于DEFAULT_KEY，获取指定name实例，并加入到返回列表
     *
     */
    public List<T> getActivateExtensionOverwrite(URL url, String[] values, String group) {
        List<T> extList = new ArrayList<>();
        List<String> names = (values == null || values.length == 0) ? new ArrayList<>() : Arrays.asList(values);
        if (!names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) { //不包含移除前缀，只要有一个元素包含移除前缀就跳过
            getExtensionClasses();
            for (Map.Entry<String, Activate> activateMap : cachedActivates.entrySet()) {
                String name = activateMap.getKey();
                Activate activate = activateMap.getValue();
                if (isMatchGroup(group, activate.group())) {
                    T ext = getExtension(name);
                    if (!names.contains(name) //不包含在values数组中
                            && !names.contains(Constants.REMOVE_VALUE_PREFIX + name) //不包含（移除前缀 + name）
                            && isActive(activate, url)) { //判断设置values是否存在url参数中
                        extList.add(ext);
                    }
                }
            }
            Collections.sort(extList, ActivateComparator.COMPARATOR); //对列表按指定比较器排序
        }

        List<T> usr = new ArrayList<>();  //按指定value获取实例
        if (names.size() > 0) {
            for (String name : names) { //部分含有移除前缀的省略掉
                if (!name.startsWith(Constants.REMOVE_VALUE_PREFIX) //去除包含移除前缀的name
                     && !name.startsWith(Constants.REMOVE_VALUE_PREFIX + name)) {
                    if (name.equals(Constants.DEFAULT_KEY)) {
                        if (usr.size() > 0) {
                            extList.addAll(0, usr);
                            usr.clear();
                        }

                    } else {
                        T ext = getExtension(name);
                        usr.add(ext);
                    }
                }
            }
        }
        return extList;
    }

    /**
     * 返回指定名字的扩展。如果指定名字的扩展不存在，则抛异常 {@link IllegalStateException}.
     *
     * @param name
     * @return
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) { //创建指定名称的接口实例
        // 一开始时此处cachedClass的映射为
        // "spring" -> "class com.alibaba.dubbo.config.spring.extension.SpringExtensionFactory"
        // "spi" -> "class com.alibaba.dubbo.common.extension.factory.SpiExtensionFactory"
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        if ("true".equals(name)) { //取默认的扩展，即SPI注解中的扩展名对应的实例
            return getDefaultExtension();
        }

        /**
         * 从缓存中获取指定扩展名的实例
         */
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) { //双重判断 + 同步锁定：减少范围并且线程安全
                instance = holder.get();
                if (instance == null) {
                    instance = createExtension(name);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * 返回缺省的扩展，如果没有设置则返回<code>null</code>。
     */
    public T getDefaultExtension() { //cachedDefaultName 取SPI上的value值， 10/29 cachedDefaultName何时写入的？解：加载扩展类时写入，loadExtensionClasses
        getExtensionClasses();
        if (null == cachedDefaultName || cachedDefaultName.length() == 0
                || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    // 判断扩展名是否在可用的扩展中
    public boolean hasExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        try {
            return getExtensionClass(name) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public Set<String> getSupportedExtensions() { //从文件中读取支持的扩展名集合
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<String>(clazzes.keySet()));
    }

    /**
     * @overwrite 获取指定扩展名的实例
     * 1）若名称name为空，则通过findException() 报异常
     * 2）若name置为"true"，使用默认的扩展实例
     * 3）获取缓存中的实例，若没有则创建实例，并返回
     *
     */
    public T getExtensionOverwrite(String name) {
        if (name == null || name.trim().length() == 0) {
            throw new IllegalArgumentException("extension name == null");
        }
        if ("true".equals(name)) {
            return getDefaultExtension();
        }
        // 先判断持有对象，然后再判断接口实例
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }

        Object instance = holder.get();
        if (instance == null) { //需要加锁 + 双重判断解决并发安全问题
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    instance = createExtension(name);
                    holder.set(instance);
                }
            }
            //cachedInstances.put(name, holder); 此处不需要put，因为holder内容改变，cachedInstances也随之改变
        }
        return (T)instance;
    }

        /**
         * 返回缺省的扩展点名，如果没有设置缺省则返回<code>null</code>。
         */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * 编程方式添加新扩展点。
     *
     * @param name  扩展点名
     * @param clazz 扩展点类
     * @throws IllegalStateException 要添加扩展点名已经存在。
     *
     * 不用读取配置文件的值，动态添加？ 是的，直接处理缓存的值cachedClasses、cachedNames
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) { //判断扩展名是否已存在
                throw new IllegalStateException("Extension name " +
                        name + " already existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * 编程方式添加替换已有扩展点。
     *
     * @param name  扩展点名
     * @param clazz 扩展点类
     * @throws IllegalStateException 要添加扩展点名已经存在。
     * @deprecated 不推荐应用使用，一般只在测试时可以使用
     *
     * 怎么替换扩展点 : 更新缓存中的值
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) { //若缓存中没有name则报错
                throw new IllegalStateException("Extension name " +
                        name + " not existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name); //多个clazz对应一个name
            cachedClasses.get().put(name, clazz); //更新相同name的值
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension not existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    /**
     * 获取自适应的实例（不需要指定扩展名，就可以动态获取自适应的扩展实例）
     * 1）从本地缓存中获取自适应实例，若能获取则直接返回，否则创建实例
     * 2）若有异常直接抛出，不用尝试创建
     */
    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() { //SPI步骤03  adaptive 自适应 （获取自适应的实例）
        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            if (createAdaptiveInstanceError == null) { //若有异常直接抛出，不用尝试创建
                synchronized (cachedAdaptiveInstance) { // 10/30-实践点 synchronized使用的各种方式
                    instance = cachedAdaptiveInstance.get();
                    if (instance == null) { //若缓存中没有自适应实例，先创建后增加，然后是设置到缓存
                        try {
                            instance = createAdaptiveExtension(); //创建适合的对象
                            cachedAdaptiveInstance.set(instance); //返回实例并存入缓存
                        } catch (Throwable t) {
                            createAdaptiveInstanceError = t; //记录异常
                            throw new IllegalStateException("fail to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }
            } else {
                throw new IllegalStateException("fail to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }
        }

        return (T) instance;
    }

    /**
     * 构造查找扩展类的异常信息
     * IllegalStateException 非法状态异常
     */
    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) { //
                return entry.getValue();
            }
        }
        // 没有找到接口的扩展类
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);
        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    /**
     * 创建指定扩展名的实例
     * （通过反射机制的clazz.newInstance()创建实例）
     */
    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) { //没有找到扩展类，则抛出异常
            throw findException(name);
        }
        try {
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                EXTENSION_INSTANCES.putIfAbsent(clazz, (T) clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            injectExtension(instance);
            Set<Class<?>> wrapperClasses = cachedWrapperClasses; //封装类，比如ProtocolFilterWrapper对Protocol封装
            if (wrapperClasses != null && wrapperClasses.size() > 0) {
                for (Class<?> wrapperClass : wrapperClasses) {
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " +
                    type + ")  could not be instantiated: " + t.getMessage(), t);
        }
    }

    /**
     * 注入依赖（为对象实例注入属性值）
     * 1）遍历实例的所有set方法
     * 2）通过扩展工厂创建指定类型、指定名称的实例
     * 3）调用方法method.invoke，设置属性值
     */
    private T injectExtension(T instance) {
        try {
            if (objectFactory != null) {
                for (Method method : instance.getClass().getMethods()) {
                    if (method.getName().startsWith("set")
                            && method.getParameterTypes().length == 1
                            && Modifier.isPublic(method.getModifiers())) { //查找实例中的public set方法
                        // 可以是基本类型，也可以是SPI类型  11/02 基础类型可以？
                        Class<?> pt = method.getParameterTypes()[0];
                        try {
                            //截取set方法，获取属性名 ,例：instance = AdaptiveCompiler, 中setDefaultCompiler的property = defaultCompiler
                            String property = method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
                            Object object = objectFactory.getExtension(pt, property); // 11/02 获取的值是啥？
                            if (object != null) {
                                method.invoke(instance, object); // 把实例设置到哪里？ 设置到接口接口的实例类：包含set方法，是接口类型，含有SPI注解
                            }
                        } catch (Exception e) {
                            logger.error("fail to inject via method " + method.getName()
                                    + " of interface " + type.getName() + ": " + e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    /**
     * @overwrite 创建指定扩展名的实例(class.newInstance())
     * 1）从缓存中获取扩展类 getExtensionClasses() ,若没有找到class，则抛出findException异常
     * 2）从EXTENSION_INSTANCES 获取，若不存在值创建
     * 3）为接口实例注入依赖
     * 4）判断cachedWrapperClass是否存在，若存在依次为wrapper类注入依赖
     *
     */
    private T createExtensionOverrite(String name) {
        Class<?> cachedClass = getExtensionClasses().get(name);
        if (cachedClass == null) {
            throw findException(name);
        }
        Object instance = EXTENSION_INSTANCES.get(cachedClass);
        try {
            if (instance == null) {
                EXTENSION_INSTANCES.putIfAbsent(cachedClass, (T)cachedClass.newInstance());
                instance = EXTENSION_INSTANCES.get(cachedClass);
            }
            injectExtension((T)instance);
            // cachedWrapperClasses 值是否存错了，接口没有构造函数：存实例类
            Set<Class<?>> cachedWrapperSet = cachedWrapperClasses;
            if (cachedWrapperSet != null && cachedWrapperSet.size() > 0) {
                for (Class wrapper : cachedWrapperSet) {
                    instance = injectExtension((T)wrapper.getConstructor(type).newInstance(instance));
                }
            }

        } catch (Exception e) {
            logger.error("创建实例异常：type:" + type + ",name:" + name, e);
        }
        return (T)instance;
    }
    private Class<?> getExtensionClass(String name) {
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        if (name == null)
            throw new IllegalArgumentException("Extension name == null");
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null)
            throw new IllegalStateException("No such extension \"" + name + "\" for " + type.getName() + "!");
        return clazz;
    }

    /**
     * 获取当前SPI接口扩展类映射关系
     * 1）从缓存中获取扩展名与扩展类的映射关系
     * 2）若没有则去读取配置文件，并加到缓存中
     */
    private Map<String, Class<?>> getExtensionClasses() {
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) { //双重检查（单例创建）
            synchronized (cachedClasses) { /**@c history-h1 cachedClasses是类的成员变量，私有的为啥考虑线程安全？ */
                classes = cachedClasses.get();
                if (classes == null) { //若映射关系为空，加载配置文件，获取到扩展名与扩展类的映射关系，并设置到内存中cachedClasses
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * 从不同目录读取配置扩展文件中的配置，并写入缓存Map中extensionClasses
     */
    private Map<String, Class<?>> loadExtensionClasses() {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);/**@c 获取注解SPI */
        if (defaultAnnotation != null) {// @Activate、@Adaptive注解不进去
            String value = defaultAnnotation.value();/**@c 取注解的值 */
            if (value != null && (value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                if (names.length > 1) {/**@c SPI不能有多个扩展 */
                    throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }
                if (names.length == 1) cachedDefaultName = names[0]; //记录下接口中SPI内容作为默认的扩展名
            }
        }

        Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();

        /**
         * 加载文件方法
         * 11/04 是加载哪些目录下的文件？比如Filter既加载dubbo-rpc-default，也加载dubbo-rpc-api目录下的文件
         * 解：所有模块中指定目录下，执行文件都要加载
         */
        loadFile(extensionClasses, DUBBO_INTERNAL_DIRECTORY);
        loadFile(extensionClasses, DUBBO_DIRECTORY);  /**@c 加载文件中值，写到缓存变量中 */
        loadFile(extensionClasses, SERVICES_DIRECTORY);
        return extensionClasses;
    }

    /**
     * @overwrite 为接口的实例对象注入依赖 Dubbo IOC 实现，只支持setter方式
     * 1）遍历实例对象所有方法，查找set方法（set开头、长度大于3、只有一个参数）
     * 2）获取参数pt，并分析出属性
     * 3）从objectFactory获取pt中属性名对应的值，若不为空，则调用实例对象的方法，并把值通过set方法设置
     *
     */
    private T injectExtensionOverWrite(T instance) {
         if (objectFactory != null) {
             Method[] methods = instance.getClass().getMethods();
             if (methods != null && methods.length > 0) {
                 try {
                     for (Method method : methods) {
                         if (method.getName().startsWith("set")
                                 && method.getParameterTypes().length == 1
                                 && Modifier.isPublic(method.getModifiers())) {
                             Class<?> pt = method.getParameterTypes()[0];
                             String property = method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
                             Object obj = objectFactory.getExtension(pt, property);
                             if (obj != null) {
                                 method.invoke(instance, obj);
                             }
                         }
                     }
                 } catch (Exception e) {
                     logger.error("注入依赖失败：interface:" + type.getName(), e);
                 }
             }
         }
         return instance;
    }

    /**@c
     * 加载指定目录的文件，获取到扩展名与扩展类的映射
     * 比如com.alibaba.dubbo.remoting.transport.netty.NettyTransporter
     *  1）判断按实现类是否有Adaptive注解，若有记录到cachedAdaptiveClass，不对extensionClasses处理
     *  2）若没有Adaptive注解
     *    2.1）判断是否有带参数type的构造函数clazz.getConstructor(type)
     *     2.1.1 ） 若有，判断cachedWrapperClasses是否为空，若为空创建。并把实现类加入
     *     2.1.2 ） 若没有，尝试无参的构造函数
     *  3）判断文件中key是否为空，若为空，截取简单类名的前缀
     *  4）判断是否有Activate注解，若有将第一个key记录到 cachedActivates
     *  5）遍历key，记录key的值到cachedNames，记录clazz和key到extensionClasses
     *
     *  两个case：
     *  1 配置文件中没name= 情况
     *  2 配置文件中name有多个可以情况
     */

    /**
     * 功能：加载指定目录下的扩展文件，解析文件内容，构建扩展名扩展类的映射关系
     * 思路：
     * 1）通过类加载加载文件，读取文件内容
     * 2）按等号"="分隔每行数据，获取到扩展名或扩展类名称，并对扩展类做正确性检查
     * 3）处理方法参数extensionClasses，将扩展名与扩展类映射起来
     *    还处理其它的缓存，如cachedActivates（自动激活类缓存）、cachedNames、cachedAdaptiveClass（自适应类的缓存）、cachedWrapperClasses（封装类的缓存）
     *
     * 注：有个dubbo的总模块，会把所有模块组织起来。所以这里加载的文件，会对所有模块中指定目录dir、指定接口名type的文件都加载
     */
    private void loadFile(Map<String, Class<?>> extensionClasses, String dir) {   //SPI步骤08
        String fileName = dir + type.getName(); //将SPI目录 + 接口的全称作为文件名（如META-INF/dubbo/internal/com.alibaba.dubbo.rpc.Filter）
        try {
            Enumeration<java.net.URL> urls;
            ClassLoader classLoader = findClassLoader();
            if (classLoader != null) { //没有获取到类加载器
                urls = classLoader.getResources(fileName); //从资源路径中加载指定文件  10/29 待调试看数据，11/05-done
            } else {
                urls = ClassLoader.getSystemResources(fileName); //从系统资源中加载文件
            }
            if (urls != null) {
                while (urls.hasMoreElements()) { //遍历集合
                    java.net.URL url = urls.nextElement(); //url例如：file:/Users/xxx/selfPro/tuya_basic_dd/dubbo-monitor/dubbo-monitor-api/target/classes/META-INF/dubbo/internal/com.alibaba.dubbo.rpc.Filter, file:/Users/xxx/selfPro/tuya_basic_dd/dubbo-filter/dubbo-filter-validation/target/classes/META-INF/dubbo/internal/com.alibaba.dubbo.rpc.Filter等
                    try {
                        /**@c 使用底层流构建高级流（字符缓冲输入流） 将字节流转换为字符流 */
                        BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream(), "utf-8"));
                        try {
                            String line = null; //history-h1 为啥加载xxx.xx.SpringExtensionFactory这个文件
                            while ((line = reader.readLine()) != null) {
                                final int ci = line.indexOf('#'); /**@c 取出注释 */
                                if (ci >= 0) line = line.substring(0, ci); // 去掉注释  //history-h1 若#在第一个字符，不越界？
                                line = line.trim();
                                if (line.length() > 0) {
                                    try {
                                        String name = null;
                                        int i = line.indexOf('=');/**@c 取出键值对 */
                                        if (i > 0) {
                                            name = line.substring(0, i).trim(); //键
                                            line = line.substring(i + 1).trim();//值
                                        }
                                        if (line.length() > 0) {/**@c initialize = true 该类将被初始化 */
                                            Class<?> clazz = Class.forName(line, true, classLoader); //或者指定类名的Class
                                            if (!type.isAssignableFrom(clazz)) { //判断接口是否相等或者是否为子接口
                                                throw new IllegalStateException("Error when load extension class(interface: " +
                                                        type + ", class line: " + clazz.getName() + "), class "
                                                        + clazz.getName() + "is not subtype of interface.");
                                            }
                                            if (clazz.isAnnotationPresent(Adaptive.class)) {/**@c 类上有adaptive注解 */
                                                if (cachedAdaptiveClass == null) {
                                                    cachedAdaptiveClass = clazz;
                                                } else if (!cachedAdaptiveClass.equals(clazz)) {// 适配的类只有一个
                                                    throw new IllegalStateException("More than 1 adaptive class found: "
                                                            + cachedAdaptiveClass.getClass().getName()
                                                            + ", " + clazz.getClass().getName());
                                                }
                                            } else {
                                                try {
                                                    /**
                                                     * 若存在有扩展接口为参数的构造函数，则为封装类
                                                     */
                                                    clazz.getConstructor(type);
                                                    Set<Class<?>> wrappers = cachedWrapperClasses;
                                                    if (wrappers == null) {
                                                        cachedWrapperClasses = new ConcurrentHashSet<Class<?>>();
                                                        wrappers = cachedWrapperClasses;
                                                    }
                                                    wrappers.add(clazz); //此处变量是局部变量，没有使用，有啥用途？使用引用赋值，其中一个引用改变值，相关引用的值也会改变
                                                } catch (NoSuchMethodException e) {
                                                    clazz.getConstructor();  //判断是否有无参的构造函数
                                                    if (name == null || name.length() == 0) { //文件中没有key的情况下,截取实现类的名称作为key
                                                        name = findAnnotationName(clazz); //此方法已经被弃用
                                                        if (name == null || name.length() == 0) {
                                                            // 例如 clazz = com.alibaba.dubbo.remoting.transport.netty.NettyTransporter
                                                            // type = com.alibaba.dubbo.remoting.Transporter
                                                            if (clazz.getSimpleName().length() > type.getSimpleName().length()
                                                                    && clazz.getSimpleName().endsWith(type.getSimpleName())) {
                                                                name = clazz.getSimpleName().substring(0, clazz.getSimpleName().length() - type.getSimpleName().length()).toLowerCase();
                                                            } else {
                                                                throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + url);
                                                            }
                                                        }
                                                    }
                                                    String[] names = NAME_SEPARATOR.split(name);
                                                    if (names != null && names.length > 0) {
                                                        Activate activate = clazz.getAnnotation(Activate.class); //此处与if (clazz.isAnnotationPresent(Adaptive.class)) 有啥不同，应该是互斥的吧？ 注解不一样
                                                        if (activate != null) {
                                                            cachedActivates.put(names[0], activate);
                                                        }
                                                        for (String n : names) {
                                                            if (!cachedNames.containsKey(clazz)) {
                                                                cachedNames.put(clazz, n);
                                                            }
                                                            Class<?> c = extensionClasses.get(n);
                                                            if (c == null) {
                                                                extensionClasses.put(n, clazz);
                                                            } else if (c != clazz) {
                                                                throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + n + " on " + c.getName() + " and " + clazz.getName());
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Throwable t) {
                                        IllegalStateException e = new IllegalStateException("Failed to load extension class(interface: " + type + ", class line: " + line + ") in " + url + ", cause: " + t.getMessage(), t);
                                        exceptions.put(line, e); //记录下异常集合
                                    }
                                }
                            } // end of while read lines
                        } finally {
                            reader.close();
                        }
                    } catch (Throwable t) {
                        logger.error("Exception when load extension class(interface: " +
                                type + ", class file: " + url + ") in " + url, t);
                    }
                } // end of while urls
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    /**
     * @overwrite by self
     * 从dubbo指定目录加载扩展类
     * 1.获取SPI注解，获取注解值
     *   分隔value值，若有多个值，则包异常。只能指定一个扩展类，并记录扩展名到缓存中
     * 2.加载不同目录，获取到extensionClass
     */
    private Map<String, Class<?>> loadExtensionClassesOverwrite() {
        final SPI spi = type.getAnnotation(SPI.class);
        if (spi != null) {
            String value = spi.value();
            if (value != null && value.trim().length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                if (names.length > 1) {
                    throw new IllegalStateException(type + "扩展名不能超过一个");
                }
                if (names.length == 1) {
                    cachedDefaultName = names[0];
                }
            }
        }

        Map<String, Class<?>> extensionClass = new HashMap<>();
        loadFile(extensionClass, DUBBO_DIRECTORY);
        loadFile(extensionClass, DUBBO_INTERNAL_DIRECTORY);
        loadFile(extensionClass, SERVICES_DIRECTORY);
        return extensionClass;
    }


    /**
     * 此方法已被弃用
     * 在配置文件中没有配置扩展名的处理逻辑：
     * 1）看实例类是否包含Extension，若包含则取Extension的value值
     * 2）将实例类与接口比较，若实例类以接口名结尾，就解决实例类的名称，并小写
     * 3）若实例类不是以接口结尾，直接将类名小写 如："class com.alibaba.dubbo.common.extensionloader.activate.impl.ActivateExt1Impl1" -> "activateext1impl1"
     * 但这样命名比较随意，不好规范控制
     */
    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {/**@c 获取注解名 */
        com.alibaba.dubbo.common.Extension extension = clazz.getAnnotation(com.alibaba.dubbo.common.Extension.class);
        if (extension == null) {
            String name = clazz.getSimpleName();
            if (name.endsWith(type.getSimpleName())) {
                name = name.substring(0, name.length() - type.getSimpleName().length());
            }
            return name.toLowerCase();
        }
        return extension.value();
    }

    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() { //SPI步骤04
        try {
            /**
             * 1) 获取实例 getAdaptiveExtensionClass().newInstance()
             * 2）为实例注入依赖
             */
            return injectExtension((T) getAdaptiveExtensionClass().newInstance()); //newInstance() 创建类的实例，就像用new来创建对象一样
        } catch (Exception e) {
            throw new IllegalStateException("Can not create adaptive extenstion " + type + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     *
     * @overwrite by self 获取自适应扩展
     * 功能：不用在编译的时候指定扩展名，在运行的时候从url参数中动态获取相关内容，构造适合的扩展名，然后获取指定扩展名的实例，执行目标对象的方法
     *
     * 1）判断接口是否带有Adaptive注解的方法 若所有方法都没有带Adaptive注解，则抛异常
     * 2）遍历接口中方法，获取接口的返回类型、参数列表、异常类型列表
     * 3）判断参数列表是否含有URL类型，记录下URL位置，若没有看参数对象中的是否含有返回值为URL的方法，若都没有则抛出异常（加上非空检测逻辑）
     *   3.1 参数有URL类型：附加对参数判断空的代码，以及赋值语句
     *   3.2 遍历每个参数对象中所有方法，找出返回类型为URL类型，记录下URL位置以及attributeName属性名
     * 4）获取查询的key
     *    4.1 从@Adaptive注解中获取key数组
     *    4.2 若没有，则将接口名按点分隔作为key
     * 5）判断是否有Invocation参数 （若有加上非空检测逻辑）
     * 6）获取自适应的扩展名：遍历key数组，从右到左执行
     *    6.1 是第一个key
     *        6.1.1 判断key是否是protocol，若是从url.getProtocol()获取，
     *              若不是，判断是否包含invocation参数，若包含 从url.getMethodParameter(methodName,key,defaultName);获取扩展名
     *              若不包含invocation参数，则通过从url.getParameter(key,defaultName);获取扩展名
     *    6.2 不是第一个key
     *        重复上面检测，只不过非invocation，及protocol和其它会一次将右面的值作为前面的默认值，而invocation会不断覆盖key值，默认值不变
     * 7）通过构建的扩展名，创建接口的实例，然后调用目标对象的方法
     * 8）循环接口中的每一个方法，做如上判断处理，结束后加上类的、方法的声明，然后把动态生成的类以字符串形式返回
     *
     */
    private String createAdaptiveExtensionClassCodeOverwrite() {
        boolean isHasAdapter = false;
        for (Method method : type.getMethods()) {
            if (method.isAnnotationPresent(Adaptive.class)) {
                isHasAdapter = true;
                break;
            }
        }
        if (!isHasAdapter) {
            throw new IllegalStateException("接口中没有带有Adaptive注解的方法，type: " + type.getName());
        }

        //构造适配器类的声明
        StringBuilder code = new StringBuilder();
        code.append(type.getPackage() + ";");
        code.append("\nimport " + ExtensionLoader.class.getName() + ";");
        code.append("\npublic class " + type.getSimpleName() + "$Adaptive implements " + type.getName() + " {");

        //构造方法声明
        for (Method method : type.getMethods()) {
            Class<?> returnType = method.getReturnType();
            Class<?> []pts = method.getParameterTypes();
            Class<?> []exceptions = method.getExceptionTypes();

            code.append("\npublic " + returnType.getName() + " " + method.getName() + "(");
            for (int i = 0; i < pts.length; i++) {
                if (i != pts.length - 1) {
                    code.append(pts[i].getName() + " arg" + i + ",");
                } else {
                    code.append(pts[i].getName() + " arg" + i);
                }
            }
            code.append(" ) ");
            if (exceptions.length > 0) {
                code.append(" throws ");
            }
            for (int i = 0; i< exceptions.length; i++) {
                if (i != exceptions.length - 1) {
                    code.append(exceptions[i].getName() + ",");
                } else {
                    code.append(exceptions[i].getName());
                }

            }
            code.append(" {");

            boolean isHasAdative = false;
            if (method.isAnnotationPresent(Adaptive.class)) {
                isHasAdative = true;
            }

            if (!isHasAdative) {
                code.append("throw new UnsupportedOperationException(\"" + type.getName() + "中方法" + method.getName() + "没有带有@Adaptive\");");
            } else {
                int urlPos = -1;
                for (int i = 0; i < pts.length; i++) {
                    if (pts[i].equals(URL.class)) {
                        urlPos = i;
                        break;
                    }
                }

                String methodName = null;
                if (urlPos != -1) {
                    String s = String.format("\n if(arg%d == null) throw new IllegalArgumentException(\"url == null\");", urlPos);
                    String t = String.format("\n com.alibaba.dubbo.common.URL url = arg%d;", urlPos); //替换符的个数与参数个数要一致
                    code.append(s);
                    code.append(t);
                } else {
                    for (int i = 0; i < pts.length; i++) {
                        for (Method ptMethod : pts[i].getMethods()) {
                            if (ptMethod.getName().startsWith("get")
                                    && ptMethod.getName().length() > 3
                                    && Modifier.isPublic(ptMethod.getModifiers())
                                    && !Modifier.isStatic(ptMethod.getModifiers())
                                    && ptMethod.getParameterTypes().length == 0
                                    && ptMethod.getReturnType().equals(URL.class)) {
                                urlPos = i;
                                methodName = ptMethod.getName();
                            }
                        }
                    }

                    if (urlPos == -1) {
                        throw new IllegalArgumentException("方法参数列表以及参数中都没有获取url的方法");
                    }
                    String p = String.format("\n if(arg%d == null) throw new IllegalArgumentException(\"arg%d == null\");", urlPos, urlPos);
                    String s = String.format("\n if(arg%d.%s() == null) throw new IllegalArgumentException(\"arg%d.%s() == null\");", urlPos, methodName, urlPos, methodName);
                    String t = String.format("\n %s url = arg%d.%s();", URL.class.getName(), urlPos, methodName);
                    code.append(p);
                    code.append(s);
                    code.append(t);
                }

                boolean isHasInvocation = false;
                int invocationPos = -1;
                for (int i = 0; i < pts.length; i++) {
                    if (pts[i].getName().equals("com.alibaba.dubbo.rpc.Invocation")) {
                        invocationPos = i;
                        isHasInvocation = true;
                        break;
                    }
                }

                if (isHasInvocation) {
                    String s = String.format("\n if(arg%d == null) throw new IllegalArgumentException(\" invocation = null\");", invocationPos);
                    String t = String.format("String methodName = arg%d.getMethodName();", invocationPos);
                    code.append(s);
                    code.append(t);
                }

                String[] keys = method.getAnnotation(Adaptive.class).value();
                if (keys.length == 0) {
                    char[] typeNameArr = type.getSimpleName().toCharArray();
                    StringBuilder key = new StringBuilder();
                    for (int i = 0; i < typeNameArr.length; i++) {
                        if (Character.isUpperCase(typeNameArr[i])) {
                            key.append(Character.toLowerCase(typeNameArr[i]));
                            if (i != 0) {
                                key.append(".");
                            }
                        } else {
                            key.append(typeNameArr[i]);
                        }
                    }
                    keys = new String[1];
                    keys[0] = key.toString();
                }

                String getExtNameCode = "";
                String defaultName = cachedDefaultName;
                for (int i = keys.length - 1; i  >= 0; i--) {
                    if (i == keys.length - 1) {
                        if (null != defaultName) { //默认值不为空
                            if (!"protocol".equals(keys[i])) {
                                if (isHasInvocation) {
                                    getExtNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\") == null ? \"%s\" : url.getMethodParameter(methodName, \"%s\", \"%s\")", keys[i], defaultName, defaultName, keys[i], defaultName);
                                } else {
                                    getExtNameCode = String.format("url.getParameter(\"%s\", \"%s\")", keys[i], defaultName);
                                }
                            } else {
                                getExtNameCode = (String.format("(url.getProtocol() == null || url.getProtocol() == \"\") ? (\"%s\") : url.getProtocol()", defaultName));
                            }
                        } else { //默认值为空时
                            if (!"protocol".equals(keys[i])) {
                                if (isHasInvocation) {
                                    getExtNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\") == null ? \"%s\" : url.getMethodParameter(methodName, \"%s\", \"%s\")", keys[i], defaultName, defaultName, keys[i], defaultName);
                                } else {
                                    getExtNameCode = String.format("url.getParameter(\"%s\")", keys[i]);
                                }
                            } else {
                                getExtNameCode = "(url.getProtocol() == null || url.getProtocol() == \"\") ? \"\" : url.getProtocol()";
                            }
                        }
                    } else {
                        if (!"protocol".equals(keys[i])) {
                            if (isHasInvocation) {
                                getExtNameCode = (String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", keys[i], defaultName));
                            } else {
                                getExtNameCode = (String.format("url.getParameter(\"%s\", %s)", keys[i], getExtNameCode));
                            }
                        } else {
                            getExtNameCode = (String.format("(url.getProtocol() == null || url.getProtocol() == \"\")? (%s) : url.getProtocol()", getExtNameCode));
                        }
                    }
                }
                code.append("\nString extName = null; ");
                code.append("\nextName = " + getExtNameCode + ";");
                code.append("\nif (extName == null || extName.equals(\"\")) throw new IllegalStateException (\"" + type.getName() + " 的扩展名为空 \" );");
                code.append("\n" + type.getName() + " extension = ExtensionLoader.getExtensionLoader(" + type.getName() + ".class ).getExtension(extName);");
                if (returnType != void.class) {
                    code.append("\n return extension." + method.getName() + "(");
                } else {
                    code.append("\n extension." + method.getName() + "(");
                }
                for (int i = 0; i < pts.length; i++) {
                    if (i != pts.length - 1) {
                        code.append("arg" + i);
                        code.append(",");
                    } else {
                        code.append("arg" + i + ");");
                    }
                }
            }

            code.append("}");
        }
        code.append("}");
//        logger.info("自适应扩展，动态生成类class:" + type.getName() + " \n"+ code.toString());
        return code.toString();
    }


    /**
     * @overwrite by self 加载文件获取接口实现类，
     * 1）拼接文件路径
     * 2）获取类加载器ClassLoader,并且加载文件资源
     * 3）遍历Url列表，获取字节输入流，并转换为字符流
     * 4）从字符流中读取内容
     *    4.1 判断是否与# ，取注释前的内容
     *    4.2 以 =号分隔，加载等号后面的类 Class
     * 5）对Class判断
     *    5.1 type类型与clazz类型相同或者type的实现类是clazz
     *    5.2 判断clazz是否适配器注解 @Adaper
     *      5.2.1 如带有Adapter注解 写入cacheAdapterClass中
     *      5.2.2 没有尝试查有参构造函数
     *        5.2.2.1 有： 写入wrapedClass
     *        5.2.2.2 无： 调用无参构造函数
     *            判断key是否为空，如果为空，获取类名称的前缀：
     *            判断条件：实现类clazz.length > type.length ; clazz.contains(type)
     *            取值：clazz.getSimple().subString(0, clazz.getSimple.length - type.getSimple()).toLoweer();
     *
     *            判断是否含有自动激活注解Active, 若有记到cachedActiveClass
     *
     *      5.2.3  记录下cachedNames, 以及extendsLoadedClass
     *
     *
     */

    //加载配置文件，并且将文件中键值对缓存在内存中(记录的缓存内容有： cachedActivates、cachedClasses等)
    public void loadFileOverwrite(Map<String, Class<?>> extensionClasses, String dir) {
        String fileName = dir + type.getName();  //加载接口名对应文件的内容，然后按需实例文件中的对象
        try {
            Enumeration<java.net.URL> urls;
            ClassLoader classLoader = findClassLoader();
            if (classLoader != null) {
                urls = classLoader.getResources(fileName); //history-h1 文件是从哪个目录取的？相对目录还是绝对目录？
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (urls != null){
            while (urls.hasMoreElements()) {
                java.net.URL url = urls.nextElement();
                BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream(), "utf-8"));
                String line = null;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    //int pos = line.charAt('#'); charAt是获取指定索引的字符，而不是指定字符的位置，需要用indexOf()
                    String str = line;
                    int pos = line.indexOf('#');
                    if (pos != -1) {
                        if (pos != 0) {
                            line.substring(0, pos);
                        } else {
                            str = "";
                        }
                    }
                    if (str.length() > 0) {
                        try {
                            int i = str.indexOf('=');
                            String name = null;
                            String value = line; //初始整行的值
                            if (i > 0) { //若有等号，按等号分隔
                                name = str.substring(0, i);
                                value = str.substring(i + 1); //去掉等号 =
                            }
                            Class<?> clazz = Class.forName(value, true, classLoader);
                            if (!type.isAssignableFrom(clazz)) {
                                throw new IllegalStateException("接口:" + type + " 的实现类不是 " + clazz);
                            }
                            if (clazz.isAnnotationPresent(Adaptive.class)) {
                                if (cachedAdaptiveClass == null) { //适配器类为空时，重新赋值
                                    cachedAdaptiveClass = clazz;
                                } else if (!cachedAdaptiveClass.equals(clazz)) {
                                    throw new IllegalStateException("超过一个适配器类：cacheAdaptive :" + cachedAdaptiveClass.getClass().getName() + ", clazz:" + clazz.getClass().getName());
                                }
                            } else {
                                try {
                                    clazz.getConstructor(type);
                                    Set<Class<?>> wrapperSet = cachedWrapperClasses; //cachedWrapperClasses存入的是实例类
                                    if (wrapperSet == null) {
                                        cachedWrapperClasses = new ConcurrentHashSet<>(); //如何创建set : ConcurrentHashSet
                                        wrapperSet = cachedWrapperClasses;
                                    }
                                    wrapperSet.add(clazz);
                                } catch (NoSuchMethodException e) { // java.lang.NoSuchMethodException, 不能是NoSuchMethodError
                                    clazz.getConstructor();
                                    //在配置文件中没有配置扩展名key时，通过截取解析的实例类，获取获取名（实例类名称 - 接口名称 并小写）
                                    if (name == null || name.length() == 0) { //此处怎为空 ：配置文件中的key为空即可
                                        name = findAnnotationName(clazz);
                                        if (name == null || name.length() == 0) { //双重判断
                                            if (clazz.getSimpleName().length() > type.getSimpleName().length() &&
                                                    clazz.getSimpleName().contains(type.getSimpleName()) && clazz.getSimpleName().endsWith(type.getSimpleName())) {
                                                name = clazz.getSimpleName().substring(0, clazz.getSimpleName().length() - type.getSimpleName().length()).toLowerCase();
                                            } else {
                                                throw new IllegalArgumentException("扩展类：" + clazz.getSimpleName() + " 与接口 " + type + "命名不标准");
                                            }
                                        }
                                    }

                                    String[] names = NAME_SEPARATOR.split(name); //处理含有分隔符的键
                                    if (name != null && name.length() > 0) {
                                        //判断是否包含自动激活注解 clazz需要使用泛型，不然这里会报类型错误
                                        Activate activate = clazz.getAnnotation(Activate.class);
                                        if (activate != null) {
                                            cachedActivates.put(names[0], activate);
                                        }

                                        for (String s : names) { //处理cachedNames，extensionClasses 处理名称与实现类的映射关系
                                            if (!cachedNames.containsKey(clazz)) { //若本地缓存没有实现类clazz对应的映射，则加入
                                                cachedNames.put(clazz, s);
                                            }
                                            Class<?> c = extensionClasses.get(s);
                                            if (c == null) {
                                                extensionClasses.put(s, clazz);
                                            } else if (c != clazz) {
                                                throw new IllegalStateException("扩展类 缓存中的值 c:" + c.getName() + "，与文件加载中不同 clazz" + clazz.getName());
                                            }

                                        }
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("加载扩展类异常 type:" + type + "，line:" + line + ", errorMsg:" + t.getMessage(), t);
                            exceptions.put(line, e); //记录下异常集合
                        }

                    }
                }
              }
            }

        } catch (Exception e) {
            logger.error("加载SPI文件异常", e);
        }

    }

    /**
     * 获取适合的扩展Class
     * 1）从配置文件加载扩展类
     * 2）判断缓存中是否有自适应的扩展类
     *    若有直接返回，若没有则创建
     */
    private Class<?> getAdaptiveExtensionClass() { //SPI步骤05
        getExtensionClasses();
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        return cachedAdaptiveClass = createAdaptiveExtensionClass(); //设置值，并返回
    }

    /**
     * 创建自适应扩展类
     * 1）创建自适应扩展类的代码
     * 2）通过编译器编译生成Class类
     */
    private Class<?> createAdaptiveExtensionClass() { //SPI步骤09
        String code = createAdaptiveExtensionClassCode(); //获取自适应扩展类代码

        //动态编译  10/30-实践点 Compiler了解以及使用
        ClassLoader classLoader = findClassLoader();
        com.alibaba.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        return compiler.compile(code, classLoader);  //将java源代码生成class对象。
    }

    /**
     * 创建自适应扩展类的代码（功能：不显示指定扩展名，动态构建出扩展名，并获取到相应的扩展类）
     * 1）接口检查（方法中@Adaptive注解检查和URL类型的参数检查）
     * 2）构建URL变量（从URL参数类型获取或者非URL参数的方法获取）
     * 3）构建用于查找的键key列表（由注解上声明的值或由类名组成的值构成）
     * 4）获取扩展名（从url中通过key列表做逻辑处理，获取到扩展名）
     * 5）通过扩展名获取扩展类（ExtensionLoader.getExtensionLoader(T).getExtension(extName)）
     */
    private String createAdaptiveExtensionClassCode() { //SPI步骤10
        StringBuilder codeBuidler = new StringBuilder();
        Method[] methods = type.getMethods(); //获取当前接口的方法列表
        boolean hasAdaptiveAnnotation = false;
        for (Method m : methods) { //方法上是否含有适配器Adaptive注解
            if (m.isAnnotationPresent(Adaptive.class)) {
                hasAdaptiveAnnotation = true; //只要有一个方法上带有@Adaptive注解即可
                break;
            }
        }
        // 完全没有Adaptive方法，则不需要生成Adaptive类
        if (!hasAdaptiveAnnotation)
            throw new IllegalStateException("No adaptive method on extension " + type.getName() + ", refuse to create the adaptive class!");

        codeBuidler.append("package " + type.getPackage().getName() + ";");
        codeBuidler.append("\nimport " + ExtensionLoader.class.getName() + ";"); //getCanonicalName 获取规范名称
        /**
         * 自适应类名，接口名 + "$Adaptive"，如Protocol$Adaptive
         */
        codeBuidler.append("\npublic class " + type.getSimpleName() + "$Adaptive" + " implements " + type.getCanonicalName() + " {");

        for (Method method : methods) {
            Class<?> rt = method.getReturnType();
            Class<?>[] pts = method.getParameterTypes();   //方法参数类型列表
            Class<?>[] ets = method.getExceptionTypes();   //方法异常类型列表

            Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
            StringBuilder code = new StringBuilder(512);
            //判断方法上是否带有适配器注解（若方法上不带有@adaptive注解，则抛出不支持异常）
            if (adaptiveAnnotation == null) {
                code.append("throw new UnsupportedOperationException(\"method ")
                        .append(method.toString()).append(" of interface ")
                        .append(type.getName()).append(" is not adaptive method!\");");
            } else {
                int urlTypeIndex = -1;
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].equals(URL.class)) { //判断URL 参数的位置
                        urlTypeIndex = i;
                        break;
                    }
                }
                // 参数列表中有URL类型的参数
                if (urlTypeIndex != -1) {
                    // Null Point check
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"url == null\");",
                            urlTypeIndex);
                    code.append(s);
                    //%d 数字，%s 字符串
                    s = String.format("\n%s url = arg%d;", URL.class.getName(), urlTypeIndex); // java字符串格式化  https://blog.csdn.net/lonely_fireworks/article/details/7962171
                    code.append(s);
                }
                // 参数列表中没有URL类型的参数
                else {
                    String attribMethod = null;

                    // 找到参数的URL属性
                    LBL_PTS: // 10/30 是变量吗？为啥没见到类型, 调试看值，是跳出循环的标志吗
                    for (int i = 0; i < pts.length; ++i) { //对参数列表中的每个参数类型进行分析
                        Method[] ms = pts[i].getMethods();
                        for (Method m : ms) {
                            String name = m.getName();
                            if ((name.startsWith("get") || name.length() > 3)
                                    && Modifier.isPublic(m.getModifiers())
                                    && !Modifier.isStatic(m.getModifiers()) //非静态方法
                                    && m.getParameterTypes().length == 0
                                    && m.getReturnType() == URL.class) { //参数类型中分析有返回类型为URL的方法
                                urlTypeIndex = i;
                                attribMethod = name;
                                break LBL_PTS;
                            }
                        }
                    }
                    if (attribMethod == null) {
                        throw new IllegalStateException("fail to create adative class for interface " + type.getName()
                                + ": not found url parameter or url attribute in parameters of method " + method.getName());
                    }

                    // Null point check  含有url属性的参数不能为空
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");",
                            urlTypeIndex, pts[urlTypeIndex].getName());
                    code.append(s);
                    s = String.format("\nif (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");",
                            urlTypeIndex, attribMethod, pts[urlTypeIndex].getName(), attribMethod);
                    code.append(s);

                    s = String.format("%s url = arg%d.%s();", URL.class.getName(), urlTypeIndex, attribMethod);
                    code.append(s);
                }

                /**
                 * 获取扩展点的列表
                 * 1）若@Adaptive有声明，直接使用
                 * 2）若@Adaptive没有声明，取类名并按点格式化
                 */
                // 分析带有@Adaptive 注解的方法
                String[] value = adaptiveAnnotation.value(); //调用注解中的方法，value作为url获取值的key 例如：url.getParameter("transporter.self", "netty")
                // 没有设置Key，则使用“扩展点接口名的点分隔 作为Key
                if (value.length == 0) { //value值若注解有配置，可能有多个
                    char[] charArray = type.getSimpleName().toCharArray(); //接口的简单名称的数据表示，比如Transporter
                    StringBuilder sb = new StringBuilder(128);
                    for (int i = 0; i < charArray.length; i++) {
                        if (Character.isUpperCase(charArray[i])) { //判断字符是否大写字母，若是加上逗号，并将该字符转换为小写字母
                            if (i != 0) {
                                sb.append(".");
                            }
                            sb.append(Character.toLowerCase(charArray[i]));
                        } else {
                            sb.append(charArray[i]);
                        }
                    }
                    value = new String[]{sb.toString()}; //值例如：TransporterSelf，处理后的值transporter.self
                }

                /**
                 * 判断是否包含Invocation
                 * 1）遍历方法的参数列表
                 * 2）若参数列表中存在包含Invocation类型的参数
                 *    则获取方法名methodName，并且将标识hasInvocation置为true
                 * 3）若有方法名methodName，后续会通过url.getMethodParameter()获取扩展名
                 *
                 * 例如：String methodName = invocation.getMethodName();
                 * String extName = url.getMethodParameter(methodName, "transporter.self", "nettySelf");
                 */
                boolean hasInvocation = false;
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].getName().equals("com.alibaba.dubbo.rpc.Invocation")) {
                        // Null Point check
                        String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"invocation == null\");", i);
                        code.append(s);
                        // Invocation 中的method name是啥？某次调用中的执行方法
                        s = String.format("\nString methodName = arg%d.getMethodName();", i);
                        code.append(s);
                        hasInvocation = true;
                        break;
                    }
                }

                /**
                 * 自适应扩展查找法：（从右往左设置默认值，然后取值时是正常顺序，从左到右取值）
                 * 1）获取注解上的扩展值列表，并从由右到左开始执行取值
                 * 2）若SPI有声明默认值，将这个值做为最右边的默认值，依次向左传递
                 * 3）取值时，从左向右取值
                 * 例如：Transporter的bind方法的取法
                 * url.getParameter("server", url.getParameter("transporter","netty"))
                 *
                 * 注意：取值方式
                 * 1）若扩展值为"protocol"，则直接取url.getProtocol()
                 * 2）若扩展值不是为"protocol"，判断若参数若包含Invocation
                 *    2.1）若包含Invocation，则按url.getMethodParameter()取值
                 *    2.2）若不包含Invocation，否则按url.getParameter()取值
                 */
                String defaultExtName = cachedDefaultName;
                String getNameCode = null; // getNameCode的生成以及用途 : 获取扩展名
                for (int i = value.length - 1; i >= 0; --i) { //遍历查询的@Adaptive注解上的值
                    if (i == value.length - 1) { //数组最后一个值
                        if (null != defaultExtName) { //判断默认值是否为空，若不为空，url获取值时带上默认值
                            if (!"protocol".equals(value[i]))  //判断url的key是否是"protocol"，若是单独处理，用url.getProtocol获取
                                if (hasInvocation)  //url.getMethodParameter(methodName, "grizzlySelf", "nettySelf") 从右到左，键key覆盖，最终取第一个值
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                            else  // url中参数为protocol，通过url.getProtocol() 获取扩展名
                                getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                        } else {
                            if (!"protocol".equals(value[i]))
                                if (hasInvocation)
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                            else
                                getNameCode = "url.getProtocol()";
                        }
                    } else { //数组非第一个元素，若
                        if (!"protocol".equals(value[i]))
                            if (hasInvocation) // url.getMethodParameter(methodName, "grizzlySelf", "nettySelf")  ，声明的 value列表，@Adaptive(value = {"grizzlySelf", "minaSelf"})
                                getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                            else //没有invocation参数，从参数列表从由开始获取扩展名，并依次左右前一个参数的默认值，例如 url.getParameter("nettySelf", url.getParameter("minaSelf", url.getParameter("grizzlySelf", "nettySelf")))
                                getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode); //附加getNameCode
                        else //参数为protocol，也一依次把后面的扩展名作为前一个参数的默认值
                            getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                    }
                }
                code.append("\nString extName = ").append(getNameCode).append(";");
                // check extName == null?
                String s = String.format("\nif(extName == null) " +
                                "throw new IllegalStateException(\"Fail to get extension(%s) name from url(\" + url.toString() + \") use keys(%s)\");",
                        type.getName(), Arrays.toString(value));
                code.append(s); //将异常附加到代理类中

                //此处获取动态生成的扩展名对应的接口实现类
                //com.alibaba.dubbo.remoting.TransporterSelf extension = (com.alibaba.dubbo.remoting.TransporterSelf)
                // ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.remoting.TransporterSelf.class).getExtension(extName);
                s = String.format("\n%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);",
                        type.getName(), ExtensionLoader.class.getSimpleName(), type.getName());
                code.append(s);

                // return statement
                if (!rt.equals(void.class)) {
                    code.append("\nreturn ");
                }
                //执行调用扩展接口的方法
                s = String.format("extension.%s(", method.getName());
                code.append(s);
                for (int i = 0; i < pts.length; i++) { //方法中参数列表
                    if (i != 0)
                        code.append(", ");
                    code.append("arg").append(i);
                }
                code.append(");");
            } //方法内代码 构建完毕

            codeBuidler.append("\npublic " + rt.getCanonicalName() + " " + method.getName() + "("); //方法声明
            for (int i = 0; i < pts.length; i++) { //方法参数列表
                if (i > 0) {
                    codeBuidler.append(", ");
                }
                codeBuidler.append(pts[i].getCanonicalName());
                codeBuidler.append(" ");
                codeBuidler.append("arg" + i);
            }
            codeBuidler.append(")");
            if (ets.length > 0) {                 //方法上异常声明
                codeBuidler.append(" throws ");
                for (int i = 0; i < ets.length; i++) {
                    if (i > 0) {
                        codeBuidler.append(", ");
                    }
                    codeBuidler.append(ets[i].getCanonicalName());
                }
            }
            codeBuidler.append(" {");
            codeBuidler.append(code.toString());
            codeBuidler.append("\n}");
        } //方法构建完毕
        codeBuidler.append("\n}"); //加上类结束符
        if (logger.isDebugEnabled()) {
            logger.debug(codeBuidler.toString());
        }
        //logger.info("扩展类代码 开始：" + codeBuidler.toString() + "扩展类代码 结束");
        return codeBuidler.toString();
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}