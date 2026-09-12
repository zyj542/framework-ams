// ICompute.aidl
// 包名必须与 Android 工程里 Java 包名一致,编译后生成的 ICompute.java 会放在这个包下
package com.example.compute;

// 声明一个跨进程方法:加两个整数,返回结果
interface ICompute {
    int add(int a, int b);
}
