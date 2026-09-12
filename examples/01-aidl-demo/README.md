# AIDL 动手练习完整示例：ICompute

> 对应《01-预备知识-Binder与进程模型.md》第 3 节的动手练习。
> 目标：亲手跑通「AIDL 定义 → 编译生成 → 读 Stub/Proxy 打包解包」全流程，
> 然后带着这双眼睛去看 `IActivityManager.java`，AMS 的 Binder 骨架秒懂。

---

## 一、本目录文件清单

| 文件 | 作用 | 需要自己建吗 |
| --- | --- | --- |
| `ICompute.aidl` | AIDL 接口定义（源文件，**手写**） | ✅ 需要 |
| `ICompute.java` | AIDL 编译器自动生成的代码（**还原版**，8.1 风格） | ❌ Build 自动生成 |
| `ComputeService.java` | 服务端：继承 `ICompute.Stub` 实现业务 | ✅ 手写 |
| `MainActivity.java` | 客户端：绑定服务、`asInterface` 拿接口、调用 | ✅ 手写 |

> `ICompute.java` 是生成代码的精确还原（AOSP 8.1 aidl 工具输出格式），
> 用于「不装工程也能逐行读」。真实工程的生成代码可能因 aidl 工具版本有细微差异，以你本机为准。

---

## 二、5 步跑通全流程

### 第 1 步：新建 Android 工程

Android Studio 新建 Empty Activity 工程，包名填 `com.example.compute`，语言 Java，minSdk 21+。

### 第 2 步：新建 `ICompute.aidl`

在 `app/src/main/aidl/com/example/compute/` 目录下新建 `ICompute.aidl`（内容见本目录同名文件）：

```aidl
package com.example.compute;

interface ICompute {
    int add(int a, int b);
}
```

**注意**：`.aidl` 文件放在 `app/src/main/aidl/` 下（不是 `java/` 目录），
且目录层级 = 包名层级。

### 第 3 步：Build

```bash
# 命令行（工程根目录）
gradlew assembleDebug
# 或直接在 Android Studio 菜单 Build → Make Project
```

### 第 4 步：找到自动生成的 `ICompute.java`

生成路径随 AGP 版本不同（**资料里的 `build/generated/aidl` 是老写法，实际如下**）：

| AGP 版本 | 路径（variant 默认是 debug） |
| --- | --- |
| AGP 3.x（AS 3.x，对应 AOSP 8.1 时代） | `app/build/generated/source/aidl/debug/com/example/compute/ICompute.java` |
| AGP 4.x/7+（AS 4.2+） | `app/build/generated/aidl_source_output_dir/debug/out/com/example/compute/ICompute.java` |

> 找不到就用 AS 的搜索：菜单 Edit → Find → Find in Files，搜 `ICompute.java`。
> 文件头有 `This file is auto-generated. DO NOT MODIFY.` 注释，别手改它。

### 第 5 步：读代码（重点！）

对照本目录 `ICompute.java`，按下面第三节逐行读。
读完后做对照实验：在 `MainActivity` 里打 `Log`，分别在客户端和服务端打印
`Thread.currentThread().getName()`，验证 add() 跑在 Binder 线程池。

---

## 三、逐行拆解：add() 的一次完整跨进程旅行

### 3.1 总体结构

```
ICompute (interface, extends IInterface)
 ├── Stub (abstract class, extends Binder, implements ICompute)  ← 服务端继承它
 │     ├── DESCRIPTOR        接口唯一标识(包名+接口名),跨进程校验用
 │     ├── asInterface()     IBinder → ICompute (本地有Stub就用Stub,否则包Proxy)
 │     ├── onTransact()      【服务端】收包、解包、调真方法、打包返回值
 │     └── TRANSACTION_add   方法编号 = FIRST_CALL_TRANSACTION + 0
 └── Proxy (class, implements ICompute)  ← 客户端拿到的是它
       └── add()             【客户端】打包参数、transact、解包返回值
```

### 3.2 客户端打包：`Proxy.add()`（调用方进程执行）

```java
public int add(int a, int b) throws android.os.RemoteException
{
  android.os.Parcel _data = android.os.Parcel.obtain();   // ① 申请两个包裹
  android.os.Parcel _reply = android.os.Parcel.obtain();
  int _result;
  try {
    _data.writeInterfaceToken(DESCRIPTOR);                // ② 写"快递单":我是 com.example.compute.ICompute
    _data.writeInt(a);                                    // ③ 打包参数 a
    _data.writeInt(b);                                    // ③ 打包参数 b
    mRemote.transact(Stub.TRANSACTION_add, _data, _reply, 0); // ④ 交给 Binder 驱动:
                                                          //    方法号=TRANSACTION_add(1),
                                                          //    参数包裹=_data,回执包裹=_reply
                                                          //    ⑤ 阻塞等待驱动把回执送回来
    _reply.readException();                               // ⑥ 检查服务端有没有抛异常
    _result = _reply.readInt();                           // ⑦ 从回执里取出返回值
  }
  finally {
    _reply.recycle();                                     // ⑧ 归还两个包裹(复用内存)
    _data.recycle();
  }
  return _result;
}
```

**打包规则一句话：参数按声明顺序写进 `_data`，返回值从 `_reply` 里读。**

### 3.3 服务端解包：`Stub.onTransact()`（Binder 线程池执行）

```java
public boolean onTransact(int code, android.os.Parcel data,
                          android.os.Parcel reply, int flags) throws RemoteException
{
  java.lang.String descriptor = DESCRIPTOR;
  switch (code)                                          // ① 看方法号,分发到对应 case
  {
    case TRANSACTION_add:
    {
      data.enforceInterface(descriptor);                 // ② 验"快递单":接口身份必须匹配
      int _arg0;
      _arg0 = data.readInt();                            // ③ 按顺序解包参数 a
      int _arg1;
      _arg1 = data.readInt();                            // ③ 解包参数 b
      int _result = this.add(_arg0, _arg1);              // ④ 调用真正干活的业务方法
                                                         //    (就是 ComputeService 里匿名类实现的 add)
      reply.writeNoException();                          // ⑤ 写"无异常"标记
      reply.writeInt(_result);                           // ⑥ 把返回值打包进回执
      return true;                                       // ⑦ true = 我处理了,驱动把 reply 送回客户端
    }
    default:
    {
      return super.onTransact(code, data, reply, flags);
    }
  }
}
```

### 3.4 打包 / 解包对照表（背下这张表，AIDL 就通了）

| 环节 | 客户端 Proxy（写 _data / 读 _reply） | 服务端 Stub（读 data / 写 reply） |
| --- | --- | --- |
| 接口校验 | `writeInterfaceToken(DESCRIPTOR)` | `enforceInterface(descriptor)` |
| 参数 a | `_data.writeInt(a)` | `data.readInt()` |
| 参数 b | `_data.writeInt(b)` | `data.readInt()` |
| 方法号 | `transact(TRANSACTION_add, ...)` | `switch(code) → case TRANSACTION_add` |
| 异常标记 | `_reply.readException()` | `reply.writeNoException()` |
| 返回值 | `_result = _reply.readInt()` | `reply.writeInt(_result)` |

**规则：写和读严格对称——写入顺序 = 读取顺序，`int` ↔ `readInt/writeInt`，String ↔ `readString/writeString`，Parcelable ↔ `readParcelable/writeParcelable`。**

### 3.5 三个必须知道的细节

1. **`asInterface()` 的两条路**：先 `queryLocalInterface(DESCRIPTOR)` 查本地（同进程）有没有 Stub，
   有就直接用（本地调用，不走驱动）；没有才 new 一个 Proxy（跨进程）。
   这就是为什么同进程 bindService 时性能好——根本没过 Binder 驱动。
2. **服务端方法跑在 Binder 线程池**：`onTransact` 不在主线程执行。
   所以服务端实现里别碰 UI，需要回主线程要用 Handler；这也是 AMS 满身 `synchronized` 的原因。
3. **`TRANSACTION_add = FIRST_CALL_TRANSACTION + 0`**：AIDL 接口里第 1 个方法编号从 1 开始
   （`FIRST_CALL_TRANSACTION = 1`），第 2 个是 2……顺序即编号，**AIDL 文件里别乱调方法顺序**——
   服务端和客户端靠编号对齐，老客户端/新服务端版本错位就是这个编号对不上。

---

## 四、让示例真正"跨进程"：Manifest 关键配置

只 bindService 不配 process，Service 和 Activity 在**同一进程**，Binder 变成本地调用（走 `queryLocalInterface` 分支），看不到真正的 IPC。要演示真跨进程：

```xml
<!-- AndroidManifest.xml -->
<application ...>
    <service
        android:name=".ComputeService"
        android:process=":compute"          <!-- 关键:让 Service 跑在独立进程 -->
        android:exported="false" />
</application>
```

配好后运行，观察 logcat：

```
D/ComputeService: add(3, 4) 执行于线程: Binder:12345_1     ← 服务端:Binder 线程池!
D/MainActivity:  result=7                                    ← 客户端:主线程
```

两个进程的线程名不同，就是一次真实的跨进程 Binder 调用。

---

## 五、把 ICompute 换成 IActivityManager（进阶思考）

把本示例的每个角色换成 AMS 对应的真实类，就是系统服务的完整骨架：

| 示例 | AMS 真实对应 | 位置 |
| --- | --- | --- |
| `ICompute.aidl` | `IActivityManager.aidl` | `frameworks/base/core/java/android/app/` |
| `ICompute.java`（生成） | `IActivityManager.java`（生成） | AOSP 编译产物 |
| `ComputeService.onBind()` | `SystemServer.startBootstrapServices()` | `SystemServer.java` |
| `ICompute.Stub` 匿名实现 | `ActivityManagerService extends IActivityManager.Stub` | `ActivityManagerService.java` |
| `MainActivity.bindService()` | `ActivityManager.getService()` | `ActivityManager.java` |

客户端拿 AMS 的引用只有一行，内部就是 `asInterface`：

```java
IActivityManager am = ActivityManager.getService();
// 内部 ≈ IActivityManager.Stub.asInterface(ServiceManager.getService("activity"))
```

---

## 六、常见坑速查

| 现象 | 原因 |
| --- | --- |
| `Couldn't find ICompute.java` | 生成路径随 AGP 版本变化，用 Find in Files 搜；或没 Build 成功 |
| 服务端方法里更新 UI 崩溃 | `onTransact` 在 Binder 线程，不是主线程 |
| 改了 AIDL 但代码没更新 | AS 菜单 Build → Clean Project 后再 Make |
| 调用报 `SecurityException` | 8.0+ 静态注册/导出限制，或没配 `exported` |
| AIDL 文件报红 | 文件必须放在 `app/src/main/aidl/` 且目录=包名 |

---

*回到 [01-预备知识-Binder与进程模型.md](../../01-预备知识-Binder与进程模型.md)*
