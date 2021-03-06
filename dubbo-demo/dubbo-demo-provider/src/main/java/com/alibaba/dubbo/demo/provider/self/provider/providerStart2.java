package com.alibaba.dubbo.demo.provider.self.provider;

import com.alibaba.dubbo.config.*;
import com.alibaba.dubbo.demo.ApiDemo;
import com.alibaba.dubbo.rpc.RpcContext;

/**
 * @author chensy
 * @date 2019-05-29 08:13
 */
public class providerStart2 {
    public static void main(String[] args) throws Exception {
        //配置优先级别  方法级别 > 接口级别 > 全局级别 ； 消费者级别 > 提供者级别

        //应用设置
        ApplicationConfig application = new ApplicationConfig();
        //非法字符
        //application.setName(".&api_demo");
        application.setName("api_demo");


        //注册中心设置
        RegistryConfig registry = new RegistryConfig();
        registry.setAddress("localhost:2181");
        registry.setProtocol("zookeeper");

        //协议测试
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName("dubbo");
        protocol.setPort(20880);

        //提供者设置(可以被一个应用下的所有接口引用)
        ProviderConfig provider = new ProviderConfig();
        provider.setTimeout(3000);
        provider.setExport(false); //不暴露服务

        //暴露接口1
        ApiDemo apiDemo = new ApiDemoImpl();
        ServiceConfig service = new ServiceConfig();
        service.setApplication(application);
        service.setRegistry(registry);
        service.setProtocol(protocol);
        //若service和provider都配置了 export的值，以service为主
        service.setExport(true);
        service.setProvider(provider);
        //service.setDelay(5);

        service.setInterface(ApiDemo.class);
        service.setRef(apiDemo);
        //设置演示暴露
//        service.setDelay(15000);

        RpcContext rpcContext = RpcContext.getContext();
        service.export();
        RpcContext rpcContext1 = RpcContext.getContext();

        System.in.read();

    }
}
