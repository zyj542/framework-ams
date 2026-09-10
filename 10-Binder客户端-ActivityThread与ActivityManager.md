# 10｜Binder 客户端视角：ActivityThread 与 ApplicationThread

> 一句话总结：**每个 App 进程里都有一个「总执行者」ActivityThread 和一部「对讲机」ApplicationThread：
> ActivityThread 负责把 AMS 发来的命令翻译成真实代码执行（创建 Activity、起 Service...），
> ApplicationThread 是 App 进程暴露给 AMS 的反向 Binder 接口，AMS 靠它指挥 App。**

---

## 1. 为什么必须讲客户端？—— 双视角地图

前面 04~09 讲的是 system_server 侧（AMS 怎么决策），这一篇补上**另一半**：
App 进程侧到底是谁在接收命令、怎么执行。

```
            App 进程                                system_server
┌───────────────────────────┐                 ┌─────────────────────────┐
│  ActivityThread           │                 │  ActivityManagerService  │
│   （主线程的总执行者）         │                 │   （决策者）                │
│                           │                 │                          │
│  ApplicationThread        │◀═══ Binder ═══▶ │  （持有 App 的对讲机：    │
│  （内部类，IApplicationThread │  下行命令      │   app.thread 字段）         │
│   .Stub，是对讲机）          │                 │                          │
│                           │                 │                          │
│  Handler H（主线程消息泵）    │                 │  ActivityStackSupervisor  │
│   LAUNCH_ACTIVITY         │                 │   realStartActivityLocked │
│   CREATE_SERVICE          │◀═══ 触发 ═══════│    app.thread.scheduleXxx │
│   RECEIVER                │                 │                          │
└───────────────────────────┘                 └─────────────────────────┘
```

**两条 Binder 通道，方向相反：**
| 通道 | 定义 | 方向 |
| --- | --- | --- |
| `IActivityManager` | App 持有 AMS 的代理 | App → 系统（请求） |
| `IApplicationThread` | AMS 持有 App 的对讲机 | 系统 → App（命令） |

---

## 2. ActivityThread：App 进程的主线程入口

文件：`frameworks/base/core/java/android/app/ActivityThread.java`

新进程被 Zygote fork 出来后，入口方法就是它（第 6459 行 `main`）：

```java
public static void main(String[] args) {
    // 1. 创建主线程的 Looper（主线程消息循环）→ Looper.prepareMainLooper()
    // 2. 创建 ActivityThread 对象，并 new ApplicationThread() —— 对讲机出厂
    ActivityThread thread = new ActivityThread();
    thread.attach(false);   // 3. 向 AMS 报到（关键！见下）
    // 4. 开启主线程消息循环 → Looper.loop()
}
```

**ActivityThread 的本质**：它不是"Activity"线程，而是**主线程上的总执行者**——
所有 AMS 发来的命令，最终都变成主线程 Handler（内部类 `H`）上的消息，由它调度执行。

---

## 3. 报到：attachApplication（App 与 AMS 的第一次握手）

```
ActivityThread.attach(false)                     // ActivityThread.java
  └─ ActivityManager.getService().attachApplication(mAppThread)   // 第 6330 行
        │   （mAppThread 就是 ApplicationThread 对象，通过 Binder 传给 AMS）
        ▼
     AMS.attachApplication(thread)               // AMS 第 7215 行
        └─ attachApplicationLocked(thread, pid)  // AMS 第 6911 行
              ├─ 记录：这个 pid 对应这个 ApplicationThread（app.thread = thread）
              ├─ 创建 Application：app.thread.scheduleCreateApplication
              │     → App 进程 ActivityThread.handleCreateApplication → Application.onCreate()
              └─ 恢复该进程"欠着"的 Activity / Service / Receiver
                    （进程启动前 AMS 答应过要做的活，现在补做）
```

> 大白话：新员工入职第一天要「报到」——把对讲机（ApplicationThread）交给经理（AMS）。
> 经理确认身份（pid）、发工牌（Application），再把之前欠的活儿（创建 Activity 等）补上。

---

## 4. ApplicationThread：App 这边的"对讲机"

文件：`ActivityThread.java` 第 690 行（内部类）：

```java
private class ApplicationThread extends IApplicationThread.Stub {
    // 上面有几十个 scheduleXxx 方法，全部由 AMS 通过 Binder 调用
    public final void scheduleLaunchActivity(Intent intent, IBinder token, ...) {
        // 把参数打包成消息，发到主线程 Handler H
        sendMessage(H.LAUNCH_ACTIVITY, ...);
    }
    public final void scheduleCreateService(...) { sendMessage(H.CREATE_SERVICE, ...); }
    public final void scheduleReceiver(...)       { sendMessage(H.RECEIVER, ...); }
    ...
}
```

**关键设计（面试常考）**：
- `ApplicationThread` 实现 `IApplicationThread.Stub`，运行在 **Binder 线程**上（谁调它谁派线程）；
- 但 App 侧真正干活的是**主线程**，所以 `ApplicationThread` 的每个 `scheduleXxx` 只做一件事：
  **把参数封装成消息丢给主线程的 Handler `H`**，然后立刻返回；
- 主线程 `H.handleMessage` 里再调 `handleLaunchActivity`、`handleCreateService`、`handleReceiver` 等真正干活的方法。

> 类比：对讲机（ApplicationThread）只负责「收口令」，收到后贴在主线程的公告栏（Handler H）上，
> 主线程忙完手头的活再来看公告执行。这样主线程不会被 Binder 调用打断而乱套（单线程模型）。

---

## 5. 主线程 Handler H：命令到执行的"翻译器"

```text
H（ActivityThread 内部 Handler）收到消息：
  LAUNCH_ACTIVITY   → handleLaunchActivity   (2833) → Activity.onCreate/onStart
  PAUSE_ACTIVITY    → handlePauseActivity    (3812) → Activity.onPause
  RESUME_ACTIVITY   → handleResumeActivity   (3608) → Activity.onResume
  STOP_ACTIVITY     → handleStopActivity     (1616) → Activity.onStop
  CREATE_SERVICE    → handleCreateService           → Service.onCreate
  RECEIVER          → handleReceiver         (3141) → BroadcastReceiver.onReceive
  CREATE_PROVIDER   → handleInstallProvider  (3055) → ContentProvider.onCreate
```

> 这就是为什么 **Activity 的生命周期回调永远发生在主线程**：
> 它们是主线程 Handler 处理的，不是 Binder 线程。

---

## 6. 客户端与系统侧的"档案对照表"

| 系统侧（AMS 视角） | App 侧（ActivityThread 视角） |
| --- | --- |
| `ActivityRecord`（决策档案） | `ActivityClientRecord`（执行档案） |
| `ServiceRecord` | 运行中的 Service 对象 |
| `BroadcastRecord` | `ReceiverData`（内含 Intent + ActivityInfo） |
| `ContentProviderRecord` | `ProviderClientRecord` |
| `ProcessRecord` | `ActivityThread` + `Application` |

记住一句话：**系统侧管「决策与档案」，App 侧管「实例与执行」**，两者通过两条 Binder 通道同步。

---

## 7. 一分钟速记卡

```
App 进程入口：ActivityThread.main（6459）
报到      ：attach → AMS.attachApplication → attachApplicationLocked（6911）→ 补做欠的活
对讲机    ：ApplicationThread（内部类，690，IApplicationThread.Stub）
翻译器    ：主线程 Handler H（LAUNCH_ACTIVITY / CREATE_SERVICE / RECEIVER ...）
铁律      ：ApplicationThread 只收口令；执行永远在主线程 Handler
```

## 8. 动手实验（本周必做）

1. 打开 `ActivityThread.java`：
   - 第 6459 行 `main`，找到 `thread.attach(false)`；
   - 第 6330 行附近，找到 `mgr.attachApplication(mAppThread)`；
   - 第 690 行 `ApplicationThread`，数一数里面有多少个 `scheduleXxx` 方法。
2. 找主线程 Handler `H`（`handleMessage`），列出它处理的消息 case，跟第 5 节表格对照。
3. 抓 App 启动日志，验证「报到」过程：
   ```bash
   adb logcat -s ActivityManager:I ActivityThread:I | grep -iE "Start proc|attachApplication|Displayed"
   ```
   启动任意 App，观察 `Start proc ... for activity`（进程拉起）和 `Displayed ...`（首帧完成）。
4. 在 `IActivityManager.aidl` 里找 `attachApplication`（第 110 行），在 `IApplicationThread.aidl`
   里找 `scheduleLaunchActivity`（第 63 行），体会两条通道的对称关系。

## 9. 思考题

1. 为什么 `ApplicationThread` 的 `scheduleXxx` 要把活丢回主线程 Handler，而不是直接执行？
2. `attachApplication` 里「补做欠的活」指的是什么场景？（提示：冷启动时进程还没起来，但 Activity 的启动请求已经来了）
3. 如果把 App 的 `onCreate` 里做大量耗时操作，会卡住谁？（提示：主线程 Handler）
4. 结合文档 04：冷启动时，`attachApplication` 和 `scheduleLaunchActivity` 谁先谁后？

---

*上一篇：[09-ContentProvider.md](09-ContentProvider.md)　|　下一篇：[11-调试与实战.md](11-调试与实战.md)*
