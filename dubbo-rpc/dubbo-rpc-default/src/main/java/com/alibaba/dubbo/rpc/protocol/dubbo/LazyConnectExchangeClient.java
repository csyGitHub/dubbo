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
package com.alibaba.dubbo.rpc.protocol.dubbo;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.Parameters;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.ExchangeClient;
import com.alibaba.dubbo.remoting.exchange.ExchangeHandler;
import com.alibaba.dubbo.remoting.exchange.Exchangers;
import com.alibaba.dubbo.remoting.exchange.ResponseFuture;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * dubbo protocol support class.
 *
 * @author chao.liuc
 */
@SuppressWarnings("deprecation")
//延迟连接，延迟多久？
final class LazyConnectExchangeClient implements ExchangeClient {

    //当调用时warning，出现这个warning，表示程序可能存在bug.
    static final String REQUEST_WITH_WARNING_KEY = "lazyclient_request_with_warning";
    private final static Logger logger = LoggerFactory.getLogger(LazyConnectExchangeClient.class);
    protected final boolean requestWithWarning;
    private final URL url;
    private final ExchangeHandler requestHandler;
    private final Lock connectLock = new ReentrantLock();
    //lazy connect 如果没有初始化时的连接状态
    private final boolean initialState;
    private volatile ExchangeClient client;
    private AtomicLong warningcount = new AtomicLong(0);

    /**
     * 构建延迟Client
     * 1）url中添加send.reconnect 是否重连的参数，默认为true
     *    （此处体现：是将url做为总线，数据的承载体，往url获取数据或设置数据）
     * 2）接收入参ExchangeHandler，赋值到当前对象的属性requestHandler
     * 3）从url中获取lazy连接的初始状态，默认是连接状态，并设置当前属性initialState
     * 4）从url中获取requestWithWarning，默认为false，并设置当前属性requestWithWarning
     */
    public LazyConnectExchangeClient(URL url, ExchangeHandler requestHandler) {
        //lazy connect ,need set send.reconnect = true, to avoid channel bad status. 
        this.url = url.addParameter(Constants.SEND_RECONNECT_KEY, Boolean.TRUE.toString());
        this.requestHandler = requestHandler;
        this.initialState = url.getParameter(Constants.LAZY_CONNECT_INITIAL_STATE_KEY, Constants.DEFAULT_LAZY_CONNECT_INITIAL_STATE);
        this.requestWithWarning = url.getParameter(REQUEST_WITH_WARNING_KEY, false);
    }


    private void initClient() throws RemotingException {
        //在client为空时，初始化
        if (client != null)
            return;
        if (logger.isInfoEnabled()) {
            logger.info("Lazy connect to " + url);
        }
        connectLock.lock();//加锁处理
        try {
            if (client != null)
                return;
            /**@c 连接 */
            this.client = Exchangers.connect(url, requestHandler);
        } finally {
            connectLock.unlock();
        }
    }

    //此处用途？
    public ResponseFuture request(Object request) throws RemotingException {
        warning(request);
        initClient();
        return client.request(request);
    }

    public URL getUrl() {
        return url;
    }

    public InetSocketAddress getRemoteAddress() {
        if (client == null) {
            //createUnresolved 从主机名和端口号创建未解析的套接字地址，不会尝试将主机名解析为InetAddress。 该地址将被标记为未解决
            return InetSocketAddress.createUnresolved(url.getHost(), url.getPort());
        } else {
            return client.getRemoteAddress();
        }
    }

    public ResponseFuture request(Object request, int timeout) throws RemotingException {
        warning(request);
        initClient();
        return client.request(request, timeout);
    }

    /**
     * 如果配置了调用warning，则每调用5000次warning一次.
     *
     * @param request
     */
    private void warning(Object request) {
        if (requestWithWarning) {
            if (warningcount.get() % 5000 == 0) {
                logger.warn(new IllegalStateException("safe guard client , should not be called ,must have a bug."));
            }
            warningcount.incrementAndGet();
        }
    }

    //获取处理类
    public ChannelHandler getChannelHandler() {
        checkClient();
        return client.getChannelHandler();
    }

    public boolean isConnected() {
        if (client == null) {
            return initialState;
        } else {
            return client.isConnected();
        }
    }

    public InetSocketAddress getLocalAddress() {
        if (client == null) {
            return InetSocketAddress.createUnresolved(NetUtils.getLocalHost(), 0);
        } else {
            return client.getLocalAddress();
        }
    }

    public ExchangeHandler getExchangeHandler() {
        return requestHandler;
    }

    //发送消息？
    public void send(Object message) throws RemotingException {
        initClient();
        client.send(message);
    }

    public void send(Object message, boolean sent) throws RemotingException {
        initClient();
        client.send(message, sent);
    }

    public boolean isClosed() {
        if (client != null)
            return client.isClosed();
        else
            return true;
    }

    public void close() {
        if (client != null)
            client.close();
    }

    //关闭客户端连接
    public void close(int timeout) {
        if (client != null)
            client.close(timeout);
    }

    @Override
    public void startClose() {
        if (client != null) {
            client.startClose();
        }
    }

    //reset ?
    public void reset(URL url) {
        checkClient();
        client.reset(url);
    }

    @Deprecated
    public void reset(Parameters parameters) {
        reset(getUrl().addParameters(parameters.getParameters()));
    }

    public void reconnect() throws RemotingException {
        checkClient();
        client.reconnect();
    }

    public Object getAttribute(String key) {
        if (client == null) {
            return null;
        } else {
            return client.getAttribute(key);
        }
    }

    public void setAttribute(String key, Object value) {
        checkClient();
        client.setAttribute(key, value);
    }

    public void removeAttribute(String key) {
        checkClient();
        client.removeAttribute(key);
    }

    public boolean hasAttribute(String key) {
        if (client == null) {
            return false;
        } else {
            return client.hasAttribute(key);
        }
    }

    private void checkClient() {
        if (client == null) {
            throw new IllegalStateException(
                    "LazyConnectExchangeClient state error. the client has not be init .url:" + url);
        }
    }
}