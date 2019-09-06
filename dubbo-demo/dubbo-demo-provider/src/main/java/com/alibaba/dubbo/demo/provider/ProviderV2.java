package com.alibaba.dubbo.demo.provider;

import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * Created by ken.lj on 2017/7/31.
 */
public class ProviderV2 {

    public static void main(String[] args) throws Exception {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(new String[]{"META-INF/spring/dubbo-demo-provider2.xml"});
        context.start();

        System.in.read(); // 按任意键退出
    }

}
