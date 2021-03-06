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
package com.alibaba.dubbo.config.utils;

import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.ReferenceConfig;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * a simple util class for cache {@link ReferenceConfig}.
 * <p>
 * {@link ReferenceConfig} is a heavy Object, it's necessary to cache these object （ReferenceConfig是很重的对象，需要进行缓存）
 * for the framework which create {@link ReferenceConfig} frequently.
 * <p>
 * You can implement and use your own {@link ReferenceConfig} cache if you need use complicate（复杂的） strategy（策略）.
 *
 * @author ding.lid
 */
public class ReferenceConfigCache {
    public static final String DEFAULT_NAME = "_DEFAULT_";
    /**
     * 缓存的key
     * Create the key with the <b>Group</b>, <b>Interface</b> and <b>version</b> attribute of {@link ReferenceConfig}.
     * <p>
     * key example: <code>group1/com.alibaba.foo.FooService:1.0.0</code>.
     */

    /**
     * 构建服务key 形式：group + "/" + interfaceName + ":" + version
     * 1）获取引用的接口名
     *    若接口名为空，尝试获取引用接口的class的名称
     * 2）若分组不为空，则拼接分组名
     * 3）若版本号不为空，则拼接版本号
     */
    public static final KeyGenerator DEFAULT_KEY_GENERATOR = new KeyGenerator() { //匿名类
        public String generateKey(ReferenceConfig<?> referenceConfig) { //组装缓存的key
            String iName = referenceConfig.getInterface();
            if (StringUtils.isBlank(iName)) {
                Class<?> clazz = referenceConfig.getInterfaceClass();
                iName = clazz.getName();
            }
            if (StringUtils.isBlank(iName)) {
                throw new IllegalArgumentException("No interface info in ReferenceConfig" + referenceConfig);
            }

            StringBuilder ret = new StringBuilder();
            if (!StringUtils.isBlank(referenceConfig.getGroup())) {
                ret.append(referenceConfig.getGroup()).append("/");
            }
            ret.append(iName);
            if (!StringUtils.isBlank(referenceConfig.getVersion())) {
                ret.append(":").append(referenceConfig.getVersion());
            }
            return ret.toString();
        }
    };
    /**
     * 使用Map做本地缓存
     */
    static final ConcurrentMap<String, ReferenceConfigCache> cacheHolder = new ConcurrentHashMap<String, ReferenceConfigCache>();
    private final String name;
    private final KeyGenerator generator;
    ConcurrentMap<String, ReferenceConfig<?>> cache = new ConcurrentHashMap<String, ReferenceConfig<?>>();

    private ReferenceConfigCache(String name, KeyGenerator generator) {
        this.name = name;
        this.generator = generator;
    }

    /**
     * Get the cache use default name and {@link #DEFAULT_KEY_GENERATOR} to generate cache key.
     * Create cache if not existed yet.
     */
    public static ReferenceConfigCache getCache() {
        return getCache(DEFAULT_NAME);
    }

    /**
     * Get the cache use specified name and {@link KeyGenerator}.
     * Create cache if not existed yet.
     */
    public static ReferenceConfigCache getCache(String name) {
        return getCache(name, DEFAULT_KEY_GENERATOR);
    }

    /**
     * Get the cache use specified {@link KeyGenerator}.
     * Create cache if not existed yet.
     */
    public static ReferenceConfigCache getCache(String name, KeyGenerator keyGenerator) {
        ReferenceConfigCache cache = cacheHolder.get(name);
        if (cache != null) {
            return cache;
        }
        cacheHolder.putIfAbsent(name, new ReferenceConfigCache(name, keyGenerator));
        return cacheHolder.get(name);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(ReferenceConfig<T> referenceConfig) {
        String key = generator.generateKey(referenceConfig);

        ReferenceConfig<?> config = cache.get(key);
        if (config != null) {
            return (T) config.get();
        }

        cache.putIfAbsent(key, referenceConfig);
        config = cache.get(key);
        return (T) config.get();
    }

    void destroyKey(String key) {
        ReferenceConfig<?> config = cache.remove(key);
        if (config == null) return;
        config.destroy();
    }

    /**
     * clear and destroy one {@link ReferenceConfig} in the cache.
     *
     * @param referenceConfig use for create key.
     */
    public <T> void destroy(ReferenceConfig<T> referenceConfig) {
        String key = generator.generateKey(referenceConfig);
        destroyKey(key);
    }

    /**
     * clear and destroy all {@link ReferenceConfig} in the cache.
     */
    public void destroyAll() {
        Set<String> set = new HashSet<String>(cache.keySet());
        for (String key : set) {
            destroyKey(key);
        }
    }

    @Override
    public String toString() {
        return "ReferenceConfigCache(name: " + name
                + ")";
    }

    public static interface KeyGenerator { //内部接口
        String generateKey(ReferenceConfig<?> referenceConfig);
    }
}
