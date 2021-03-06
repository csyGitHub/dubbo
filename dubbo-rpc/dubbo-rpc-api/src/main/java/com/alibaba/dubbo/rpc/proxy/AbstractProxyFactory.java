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
package com.alibaba.dubbo.rpc.proxy;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.service.EchoService;

/**
 * AbstractProxyFactory
 *
 * @author william.liangf
 */
public abstract class AbstractProxyFactory implements ProxyFactory {//10.1 代理工厂以及代理流程

    /**
     * 在调用子类具体的getProxy()实现前，组装参数interfaces
     * 具体的实现交由子类完成
     */
    public <T> T getProxy(Invoker<T> invoker) throws RpcException {
        Class<?>[] interfaces = null;
        /**
         * 11/23 此处需要调试，看下interfaces里面可能是啥内容？怎么会有多个interface，看下标签配置
         * 解：目前没看到往url中设置interfaces地方，调试中看到为空
         * 若设置的接口不为空，将实际调用的接口invoker.getInterface() + EchoService + 参数中设置的interfaces
         * 不管有没有设置interfaces，都会为EchoService生成代理
         */
        String config = invoker.getUrl().getParameter("interfaces");
        if (config != null && config.length() > 0) {
            String[] types = Constants.COMMA_SPLIT_PATTERN.split(config);
            if (types != null && types.length > 0) {//逗号分隔
                interfaces = new Class<?>[types.length + 2];
                interfaces[0] = invoker.getInterface();
                interfaces[1] = EchoService.class;
                for (int i = 0; i < types.length; i++) {
                    interfaces[i + 1] = ReflectUtils.forName(types[i]);
                }
            }
        }
        // 若url中没有指定interfaces，则接口包含当前接口和EchoService
        if (interfaces == null) {
            interfaces = new Class<?>[]{invoker.getInterface(), EchoService.class};
        }
        return getProxy(invoker, interfaces);/**@c 构造接口类型 */
    }

    //代理待学习实践 Java以及Javassist
    public abstract <T> T getProxy(Invoker<T> invoker, Class<?>[] types);

    // 抽象类可以不全部实现接口的方法，交由实现类来处理？
}