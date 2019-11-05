package com.alibaba.dubbo.demo.consumer;

import com.alibaba.dubbo.demo.DemoService;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * Created by ken.lj on 2017/7/31.
 */
public class Consumer2 {

    public static void main(String[] args) throws Exception {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(new String[]{"META-INF/spring/dubbo-demo-consumer2.xml"});
        context.start();

        DemoService demoService = (DemoService) context.getBean("demoService"); // 获取远程服务代理
        //循环调用3次
        for (int i = 0; i < 2; i++) {
            String hello = demoService.sayHello("world : " + i); // 执行远程方法
            System.out.println("消费者V2：" + i + "," + hello); // 显示调用结果
        }
        System.in.read();
    }
}