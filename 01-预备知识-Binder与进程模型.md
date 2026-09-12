# 01｜预备知识：Binder 与 Android 进程模型

> 一句话总结：**Android 的「进程之间互相调用方法」，靠的是一种叫 Binder 的「快递系统」；
> 理解 Binder，就等于拿到了打开 AMS 大门的钥匙。**

---

## 1. 先想一个问题：进程之间怎么「打电话」？

在 Linux 上，每个进程都有自己的内存空间，**A 进程不能直接访问 B 进程的内存**。
但 Android 世界里，App（普通应用进程）要调用系统里的方法，比如「请帮我把这个 Activity 启动起来」——
这个「调用」发生在两个不同的进程里，怎么办？

常见方案有：共享内存、管道、Socket、信号……但 Android 偏偏选了自己发明的 **Binder**。
为什么？因为 Binder 有三个别人比不上的优点：

| 优点 | 通俗解释 |
| --- | --- |
| 效率高 | 一次跨进程调用只拷贝 1 次数据（别的方案要拷贝 2 次） |
| 安全 | 内核会给每次调用带上「来电显示」（调用方 PID/UID），方便系统做权限检查 |
| 面向对象 | 跨进程也能像「调用对象的方法」一样自然，不用自己拼协议 |

> 类比：Binder 就是一家**有保安把门的快递公司**。
> 你（客户端）把包裹交给快递员，快递员（Binder 驱动）负责送件并登记你的身份证号（PID/UID），
> 收件人（服务端）拆包处理，再把回执送回来。全程只跑一趟。

---

## 2. 一次 Binder 调用，拆开看有四段

以「App 进程调用 AMS 的 `startActivity`」为例：

```
┌─────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│  App 进程     │    │  Binder 驱动   │    │  system_server│    │   AMS 对象    │
│  (调用方/Client)│    │  (内核里的邮局) │    │  (承载者/Server)│    │  (真正干活的人) │
│              │    │              │    │              │    │              │
│  activityManager│──▶│  Binder      │──▶│  ActivityManager│   │ startActivity()│
│  .startActivity│   │  transact()  │    │  Stub         │──▶│  (Binder线程池里)│
│  (Proxy 代理)  │    │              │    │  onTransact() │    │              │
└─────────────┘    └──────────────┘    └──────────────┘    └──────────────┘
```

关键角色：

| 角色 | 类 | 通俗理解 |
| --- | --- | --- |
| **代理（Proxy）** | `IActivityManager.Stub.Proxy`（由 AIDL 自动生成） | 站在调用方这边的「替身」，假装自己是服务端 |
| **桩（Stub）** | `IActivityManager.Stub`，AMS 继承它 | 站在服务端这边的「门卫」，负责收包裹、验货、喊干活的人 |
| **接口** | `IActivityManager.aidl` | 双方的「服务清单」，约定了能提供哪些服务 |
| **驱动** | `/dev/binder` 内核模块 | 真正的邮局，负责传输 |
| **真正干活的** | `ActivityManagerService` 对象本身 | 收到包裹后执行 `startActivity` 的业务逻辑 |

**调用的技术细节（第一遍可以只记结论）：**
1. 客户端调用 `proxy.startActivity(...)` → 内部把参数打包（**marshall/写入 Parcel**）→ 调用 `Binder.transact()`
2. 驱动把数据送到服务端，服务端在 **Binder 线程池**的某个线程里执行 `Stub.onTransact()` → 解包 → 调用真实的 `AMS.startActivity()`
3. 返回值按同样的路送回去

> 所以 AMS 的方法其实是在 **Binder 线程**上执行的，不是 system_server 的主线程！
> 这解释了为什么 AMS 里到处是锁（`synchronized (this)`）—— 很多线程同时在干活，必须加锁保证数据安全。

---

## 3. AIDL：怎么给 Binder 写「服务清单」

AIDL（Android Interface Definition Language）就是用来定义「跨进程方法」的接口文件。
比如 AMS 对外提供的服务就定义在这个文件里（本仓库）：

```text
frameworks/base/core/java/android/app/IActivityManager.aidl
```

编译时它会自动生成 Java 代码（`IActivityManager.java`），里面包含：

- **Stub**（服务端抽象类）：包含 `onTransact()`，根据方法号分发到对应方法
- **Proxy**（客户端代理类）：把每个方法封装成「打包 → transact → 解包」

**你自己动手的练习（强烈建议第 1 周就做）：**
1. 在任意 Android 工程里新建一个 `.aidl` 文件（如 `ICompute.aidl`）
2. 里面声明一个方法 `int add(int a, int b)`
3. Build 后在生成目录里找到自动生成的 `ICompute.java`（路径随 AGP 版本变化：AGP 3.x 在
   `build/generated/source/aidl/`，AGP 7+ 在 `build/generated/aidl_source_output_dir/`，用 AS 的
   Find in Files 搜 `ICompute.java` 最稳）
4. 打开它，找到 `add()` 在 **Proxy** 里怎么打包、在 **Stub.onTransact()** 里怎么解包
5. 读完你就能秒懂 AMS 的整个 Binder 骨架

> 📁 **完整示例已备好**：`examples/01-aidl-demo/` 里有 `ICompute.aidl`、
> 还原版的 `ICompute.java`（不用建工程也能逐行读）、服务端 `ComputeService.java`、
> 客户端 `MainActivity.java`，以及 Proxy 打包 / Stub 解包的逐行拆解和对照表。
> 打开 [examples/01-aidl-demo/README.md](examples/01-aidl-demo/README.md) 直接开做。

> 在 8.1 里，客户端拿 AMS 的引用只需要一行：
> ```java
> IActivityManager am = ActivityManager.getService();  // 内部就是 IActivityManager.Stub.asInterface(...)
> ```
> 文件：`frameworks/base/core/java/android/app/ActivityManager.java` 第 4216 行附近。
> 旧代码里常见的 `ActivityManagerNative.getDefault()` 已被废弃，内部就是调 `ActivityManager.getService()`。

---

## 4. Android 的进程模型：从 Zygote 说起

Android 里几乎每个 App 都是一个**独立进程**。这些进程不是凭空冒出来的，而是由一个大管家
**Zygote（受精卵/孵化器）** 负责「复制」出来的。

```
内核启动
   │
   ▼
 init 进程（Linux 1 号进程）
   │
   ▼
 Zygote 进程（孵化器：预加载好了虚拟机、常用类库、一堆系统资源）
   │   fork()（快速复制）
   ├──▶ SystemServer 进程（系统服务的老巢，AMS 就住在这里）
   │         │   startService()
   │         ├──▶ ActivityManagerService、PackageManagerService、WindowManagerService...
   │         │
   └──▶ 各 App 进程（普通应用，被 SystemServer/AMS 要求时 fork 出来）
```

几个关键点：

| 概念 | 说明 |
| --- | --- |
| Zygote | 预加载好框架资源，App 进程用 `fork` 快速复制，节省启动时间 |
| SystemServer | 一个特权进程，运行着几百个系统服务（AMS/PMS/WMS/LocationManagerService...） |
| App 进程 | 每个应用一个，默认进程名 = 包名；可用 `android:process` 指定额外进程 |
| 进程 vs 线程 | 进程有独立内存；线程共享进程内存。AMS 里的「进程」由 `ProcessRecord` 记录 |

**为什么要多进程？**
- 隔离崩溃：一个 App 崩溃不影响系统和其它 App
- 安全：进程之间有内核级的内存隔离 + 权限检查
- 资源共享：系统服务集中在一个特权进程里，大家通过 Binder 访问

---

## 5. 把知识串起来：AMS 与 Binder 的第一次握手

现在你已经有能力理解这句话了：

> **AMS 是一个运行在 system_server 进程里、实现了 `IActivityManager.Stub` 的 Binder 服务。
> 每个 App 进程通过 `ActivityManager.getService()` 拿到它的代理（Proxy），
> 然后像调用本地方法一样跨进程请求它「启动 Activity / 启动服务 / 发广播 / 申请进程」。**

---

## 6. 动手实验（本周必做）

1. 读 `frameworks/base/core/java/android/app/IActivityManager.aidl` 的前 50 行，找到
   `startActivity`、`attachApplication`、`broadcastIntent` 三个方法声明。
2. 写一个自己的 AIDL demo（见第 3 节步骤），读生成的 Stub/Proxy 代码。
   不想从零搭工程？直接用现成的 [examples/01-aidl-demo/README.md](examples/01-aidl-demo/README.md)，
   代码、生成文件还原、逐行分析全都有。
3. 在真机/模拟器执行：
   ```bash
   # ① 确认 system_server 进程存在
   adb shell ps -A | grep system_server
   # ② 查看 AMS 对外暴露的 service 名字（应是 activity）
   adb shell service list | grep activity
   # ③ 看当前 Activity 栈状态
   adb shell dumpsys activity activities | head -30
   # ④ 如果 ③ 报 Bad activity command（部分厂商 ROM 精简了子命令），用这个兜底：
   adb shell dumpsys activity | grep -A 20 "mActivities"
   ```
   确认 system_server 进程存在，并看到 AMS 对外暴露的 service 名字是 `activity`。

> 💡 三个命令的分工：`ps` 看「进程在不在」，`service list` 看「注册的服务叫什么名」，
> `dumpsys` 看「服务内部状态」。面试问「怎么证明 AMS 是个 Binder 服务」，
> 答 `adb shell service list | grep activity` 是最短路径。

## 7. 思考题

1. 为什么 Binder 要比 Socket 快？（提示：拷贝次数、面向对象）
2. AMS 的方法为什么经常带着 `synchronized` 锁？
3. `ActivityManagerNative` 在 8.1 里还有什么用？（打开源码看一眼，它内部调了什么）
4. 如果 Binder 驱动挂了，App 与系统会怎样？

---

*下一篇：[02-总体架构-AMS在系统中的位置.md](02-总体架构-AMS在系统中的位置.md)*
