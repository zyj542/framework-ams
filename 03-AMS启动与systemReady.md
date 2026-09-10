# 03｜AMS 的出生与上岗：从 Zygote 到 systemReady

> 一句话总结：**手机开机时，Linux 内核先拉起 Zygote，Zygote 孵化出 SystemServer，
> SystemServer 把 AMS 创建出来并注册为系统服务；等一切就绪后，AMS 通过 `systemReady`
> 把桌面（HOME）启动起来，宣告「系统可以用了」。**

---

## 1. 开机启动链（宏观视角）

```
内核启动
   │
   ▼
init 进程（解析 init.rc，拉起关键服务）
   │
   ▼
Zygote 进程（预加载 Java 虚拟机与框架类库）
   │   fork()
   ▼
SystemServer.main()   ← 系统服务的总入口
   │
   ├── startBootstrapServices()   启动最核心的服务（AMS 就在这一阶段）
   ├── startCoreServices()
   ├── startOtherServices()       启动其它服务，最后调 AMS.systemReady()
   ▼
BOOT_COMPLETED 广播发出 → 桌面启动 → 用户可以开始玩了
```

关键文件：`frameworks/base/services/java/com/android/server/SystemServer.java`

---

## 2. AMS 是怎么被创建出来的？

在 SystemServer 的 `startBootstrapServices()` 里（约第 521 行）：

```java
mActivityManagerService = mSystemServiceManager.startService(
        ActivityManagerService.Lifecycle.class).getService();
```

翻译成大白话：

1. SystemServer 找「服务管家」（`SystemServiceManager`）去创建 AMS；
2. `ActivityManagerService.Lifecycle` 是 AMS 的内部类（第 2647 行），它继承自 `SystemService`，
   负责按系统服务的规矩「出生」；
3. `getService()` 返回真正的 `ActivityManagerService` 对象。

再看 AMS 构造函数（`ActivityManagerService.java` 第 2703 行），它一出生就干了这些事：

| 构造里干的事 | 说明 |
| --- | --- |
| 创建自己的 Handler / Looper 线程 | AMS 大量异步消息（如「延时杀掉旧进程」）都靠它 |
| 创建 `ActiveServices`、`ActivityStackSupervisor` 等子管家 | 见文档 02 的家庭关系图 |
| 创建 `ProcessList` 并初始化 oom_adj 相关配置 | 为后续进程管理做准备 |
| 注册各种系统回调（电池、电源、AppOps 等） | 与其它服务建立联系 |
| 从 PMS 读取所有已安装应用信息 | 初始化 `mProcessNames`、`mLruProcesses` 等 |
| 恢复上次关机前的状态 | `mRecentTasks` 等（如果之前有持久化状态） |

> 注意：AMS 的构造只是「把办公室收拾好、工牌挂上」，**还不对外营业**。
> 真正开始接客（处理 App 请求）要等 `systemReady`。

---

## 3. 注册成 Binder 服务：让别人能找到它

App 想调用 AMS，得先知道它的「门牌号」。系统服务通过 `ServiceManager` 注册：

```java
// AMS.setSystemProcess() 内部（约 8.1 源码第 2220 行附近）
ServiceManager.addService(Context.ACTIVITY_SERVICE, this, /* allowIsolated= */ false);
```

- `Context.ACTIVITY_SERVICE` 就是字符串 `"activity"`
- 之后客户端 `ActivityManager.getService()` 本质上是：
  `ServiceManager.getService("activity")` → 拿到 IBinder → `IActivityManager.Stub.asInterface()`
- 验证方法：真机/模拟器执行 `adb shell service list | grep activity`

---

## 4. systemReady：正式开张营业

AMS 方法 `systemReady`（`ActivityManagerService.java` 第 14148 行）由 SystemServer 在
`startOtherServices()` 的最后调用。它干的事情可以概括为三件大事：

### 大事一：把"压了很久的请求"放行

系统启动过程中，很多 App 请求（比如开机自启的广播、后台服务）都被 AMS 先拦下来/记录着。
`systemReady` 之后才真正放行处理。

### 大事二：恢复/启动关键应用

- 恢复上次开机时处于前台的应用（如果配置了）
- **启动桌面（HOME Activity）**：通过 `mStackSupervisor.resumeHomeStackTaskLocked()` 之类的方法，
  让 Launcher 显示出来 —— 这就是你开机后看到的桌面

### 大事三：发出 BOOT_COMPLETED 广播

```java
// 8.1 里通过 mUserController / 系统相关代码发出
Intent ACTION_BOOT_COMPLETED
```

- 所有静态注册了 `BOOT_COMPLETED` 的 App 会收到通知，可以开始干活了（这就是 App 开机自启的机制）
- 之后系统状态变为「完全就绪」，`ActivityManager.isSystemReady()` 返回 true

> 类比：系统是一家商场。`systemReady` 就是开业剪彩：
> 之前装修（启动服务）、进货（读取包信息）都做完了，剪彩之后才放顾客（App）进场。

---

## 5. 一个容易混淆的点：AMS 的 main() 在哪？

很多老教程会写 `ActivityManagerService.main(context)` —— 那是 Android 5/6 时代的写法。
**在 8.1 里，AMS 已经改成 `SystemService` 框架管理**，没有独立的 `main()`，
创建入口就是第 2 节里的 `Lifecycle` + `SystemServiceManager.startService()`。

> 读网上 8.1 之前的老博客时，注意对照版本，别被过时代码带偏。

---

## 6. 动手实验（本周必做）

1. 在 `SystemServer.java` 里找到第 521~524 行，读一读 AMS 创建后的两行 `setSystemServiceManager`、`setInstaller`。
2. 找到 `SystemServer.java` 里调用 `mActivityManagerService.systemReady(...)` 的位置，看看传进去的
   `goingCallback`（Runnable）里做了什么。
3. 模拟器/真机执行：
   ```bash
   adb shell service list | grep activity
   adb shell dumpsys activity | head -20
   ```
4. 抓开机日志看 AMS 的启动痕迹：
   ```bash
   adb logcat -b all -d | grep -i "SystemServer" | head -50
   ```

## 7. 思考题

1. 为什么 AMS 要在「引导阶段」就启动，而不是和其它服务一起在第二阶段启动？
2. `systemReady` 为什么用 `Runnable goingCallback` 这种回调，而不是直接返回？
3. 如果 `systemReady` 永远不执行，App 会怎样？（提示：BOOT_COMPLETED 和桌面）
4. `setSystemProcess()` 除了注册 `"activity"` 服务，还注册了哪些别的 service？打开源码找一找。

---

*上一篇：[02-总体架构-AMS在系统中的位置.md](02-总体架构-AMS在系统中的位置.md)　|　下一篇：[04-Activity启动全流程.md](04-Activity启动全流程.md)*
