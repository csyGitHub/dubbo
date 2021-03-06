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
package com.alibaba.dubbo.remoting.transport;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.store.DataStore;
import com.alibaba.dubbo.common.utils.ExecutorUtil;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Server;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * AbstractServer
 *
 * @author qian.lei
 * @author ding.lid
 */
public abstract class AbstractServer extends AbstractEndpoint implements Server {
    /**
     * 数据结构
     * 类继承关系
     * 1）AbstractServer类继承了AbstractEndpoint，并且实现了Server接口
     * 2）AbstractEndpoint类继承AbstractPeer类，并实现了Resetable接口
     * 3）AbstractPeer类实现了Endpoint, ChannelHandler接口
     * 4）Server接口继承了Endpoint、Resetable接口
     *
     * 功能点：
     * 1）实现了Server、Endpoint、Resetable接口，具有相关的功能点
     * 2）继承了AbstractEndpoint抽象类，能使用该抽象类提供的公有功能点，如getChannelCodec()获取通道编码
     * 3）间接继承了AbstractPeer，具体该抽象类，抽象的公有功能，如getChannelHandler()获取通道处理器，sent()发送消息等
     *
     * 数据点：
     * 1）当前维护：线程池ExecutorService，本地地址和远程地址InetSocketAddress、accepts可接收的请求数，idleTimeout空闲时间
     * 2）AbstractEndpoint维护：Codec2编码方式，timeout超时时间，connectTimeout连接超时时间
     * 3）AbstractPeer维护：ChannelHandler（通道处理器）、URL（处理的url）、closing（是否在关闭中）、closed（是否已关闭）
     */

    protected static final String SERVER_THREAD_POOL_NAME = "DubboServerHandler";
    private static final Logger logger = LoggerFactory.getLogger(AbstractServer.class);
    ExecutorService executor;
    private InetSocketAddress localAddress;
    private InetSocketAddress bindAddress;
    private int accepts;
    private int idleTimeout = 600; //600 seconds

    /**
     * 构建AbstractServer -- 代码流程
     * 1）掉用父类的构造函数
     *   1.1）构建AbstractPeer，设置ChannelHandler、URL属性值
     *   1.2）构建AbstractEndpoint，并设置Codec2、timeout、connectTimeout的属性值
     * 2）设置localAddress：调用AbstractPeer的getUrl()获取到URL 并调用toInetSocketAddress
     * 3）获取host：
     *   3.1）判断是否为任意主机（url中anyhost值）或是无效的主机（NetUtils.isInvalidLocalHost）
     *   3.2）若是，则host值为"0.0.0.0"，否则取URL中的getHost()
     * 4）设置accepts：从url中获取accepts并设置
     * 5）设置accepts， 从url中获取idle.timeout并设置
     * 6）打开服务：默认打开NettyServer中的doOpen()方法
     */
    public AbstractServer(URL url, ChannelHandler handler) throws RemotingException {
        super(url, handler);
        localAddress = getUrl().toInetSocketAddress();
        String host = url.getParameter(Constants.ANYHOST_KEY, false)
                || NetUtils.isInvalidLocalHost(getUrl().getHost())
                ? NetUtils.ANYHOST : getUrl().getHost();
        bindAddress = new InetSocketAddress(host, getUrl().getPort()); //构建socket地址
        this.accepts = url.getParameter(Constants.ACCEPTS_KEY, Constants.DEFAULT_ACCEPTS); //history-h2 accepts默认值为0，表示不接受新的请求？
        this.idleTimeout = url.getParameter(Constants.IDLE_TIMEOUT_KEY, Constants.DEFAULT_IDLE_TIMEOUT); // history-v1 空闲时间的使用场景
        try {
            doOpen(); //打开服务
            if (logger.isInfoEnabled()) { /**@c 启动服务成功*/
                logger.info("Start " + getClass().getSimpleName() + " bind " + getBindAddress() + ", export " + getLocalAddress());
            }
        } catch (Throwable t) {
            throw new RemotingException(url.toInetSocketAddress(), null, "Failed to bind " + getClass().getSimpleName()
                    + " on " + getLocalAddress() + ", cause: " + t.getMessage(), t);
        }
        DataStore dataStore = ExtensionLoader.getExtensionLoader(DataStore.class).getDefaultExtension(); //是在哪里设置的？ 在WrappedChannelHandler构造函数中设置
        executor = (ExecutorService) dataStore.get(Constants.EXECUTOR_SERVICE_COMPONENT_KEY, Integer.toString(url.getPort())); //从缓存中指定的线程池
    }

    protected abstract void doOpen() throws Throwable;

    protected abstract void doClose() throws Throwable;

    public void reset(URL url) {
        if (url == null) {
            return;
        }
        try {
            if (url.hasParameter(Constants.ACCEPTS_KEY)) { //可接收的线程数
                int a = url.getParameter(Constants.ACCEPTS_KEY, 0);
                if (a > 0) {
                    this.accepts = a;
                }
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
        try {
            if (url.hasParameter(Constants.IDLE_TIMEOUT_KEY)) { //空闲时间
                int t = url.getParameter(Constants.IDLE_TIMEOUT_KEY, 0);
                if (t > 0) {
                    this.idleTimeout = t;
                }
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
        try {
            /**
             * ThreadPoolExecutor参数
             * 深入理解java线程池—ThreadPoolExecutor https://www.jianshu.com/p/ade771d2c9c0
             * ThreadPoolExecutor 参数详解（图解） https://blog.csdn.net/Jack_SivenChen/article/details/53394058
             *
             * 当执行execute()时，线程池做如下判断
             * 1）若正在运行的线程数小于corePoolSize，那么马上创建线程运行这个任务
             * 2）若正在运行的线程数大于或等于corePoolSize，那么将这个任务放入队列
             * 3）若队列满了，若正在运行的线程数小于maximumPoolSize，那么还是要创建线程运行任务
             * 4）若队列满了，且正在运行的线程数大于或等于maximumPoolSize，则线程池会抛出异常
             */
            if (url.hasParameter(Constants.THREADS_KEY)
                    && executor instanceof ThreadPoolExecutor && !executor.isShutdown()) { //原生的线程池使用
                ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) executor;
                int threads = url.getParameter(Constants.THREADS_KEY, 0);
                int max = threadPoolExecutor.getMaximumPoolSize();
                int core = threadPoolExecutor.getCorePoolSize();
                if (threads > 0 && (threads != max || threads != core)) { //在线程数大于0 并且不等于最大线程数max或核心线程数core
                    if (threads < core) {
                        threadPoolExecutor.setCorePoolSize(threads);
                        if (core == max) {
                            threadPoolExecutor.setMaximumPoolSize(threads);
                        }
                    } else {
                        threadPoolExecutor.setMaximumPoolSize(threads);
                        if (core == max) {
                            threadPoolExecutor.setCorePoolSize(threads);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
        super.setUrl(getUrl().addParameters(url.getParameters()));
    }

    public void send(Object message, boolean sent) throws RemotingException {
        Collection<Channel> channels = getChannels();
        for (Channel channel : channels) {
            if (channel.isConnected()) {
                channel.send(message, sent);
            }
        }
    }

    public void close() {
        if (logger.isInfoEnabled()) {
            logger.info("Close " + getClass().getSimpleName() + " bind " + getBindAddress() + ", export " + getLocalAddress());
        }
        ExecutorUtil.shutdownNow(executor, 100);
        try {
            super.close();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            doClose();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    public void close(int timeout) {
        ExecutorUtil.gracefulShutdown(executor, timeout);
        close();
    }

    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    public InetSocketAddress getBindAddress() {
        return bindAddress;
    }

    public int getAccepts() {
        return accepts;
    }

    public int getIdleTimeout() {
        return idleTimeout;
    }

    @Override
    public void connected(Channel ch) throws RemotingException {
        // 如果server已进入关闭流程，拒绝新的连接
        if (this.isClosing() || this.isClosed()) {
            logger.warn("Close new channel " + ch + ", cause: server is closing or has been closed. For example, receive a new connect request while in shutdown process.");
            ch.close();
            return;
        }

        Collection<Channel> channels = getChannels();
        if (accepts > 0 && channels.size() > accepts) {
            logger.error("Close channel " + ch + ", cause: The server " + ch.getLocalAddress() + " connections greater than max config " + accepts);
            ch.close();
            return;
        }
        super.connected(ch);
    }

    @Override
    public void disconnected(Channel ch) throws RemotingException {
        Collection<Channel> channels = getChannels();
        if (channels.size() == 0) {
            logger.warn("All clients has discontected from " + ch.getLocalAddress() + ". You can graceful shutdown now.");
        }
        super.disconnected(ch);
    }

}