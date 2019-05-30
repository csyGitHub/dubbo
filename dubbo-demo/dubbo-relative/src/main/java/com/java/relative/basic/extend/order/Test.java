package com.java.relative.basic.extend.order;

/**
 *
 * 测试类加载顺序以及初始化顺序
 * @author chensy
 * @date 2019-05-30 09:33
 */
//测试类
public class Test {
    public static void main(String[] args) {
        Son son = new Son();
    }
    /**
     * 输出内容：
     *
     * 父类静态代码块 k
     * 父类静态变量 j
     * 子类静态变量l
     * 子类静态代码块
     * 父类普通变量 i
     * 父类普通代码块a
     * 父类构造方法b
     * 子类普通变量k
     * 子类普通代码块
     * 子类构造方法
     */

    /**
     * 加载类的顺序为：先加载基类，基类加载完毕后再加载子类。
     * 初始化的顺序为：先初始化基类，基类初始化完毕后再初始化子类。
     */

    /**
     * 初始化顺序：
     * 1.先按顺序初始化父类的静态变量和静态代码块，再按顺序初始化子类的静态变量和静态代码块
     * 2.再按顺序初始化父类的普通变量和普通代码块，执行父类的构造函数；
     * 3.按顺序初始化子类的普通变量和普通代码块，执行父类的构造函数
     *
     */
}
