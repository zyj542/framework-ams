# 04｜Activity 启动全流程：从 startActivity 到 onResume

> 一句话总结：**一次 startActivity 调用 = App 进程把「请求」通过 Binder 交给 system_server 里的 AMS，
> AMS 经过「解析 → 检查 → 找栈 → 找进程 → 派活」五步处理，最后通过 IApplicationThread
> 通知目标 App 进程创建 Activity。整个流程在 8.1 里跨越 4 个文件、2 个进程、1 次 Binder 往返。**

---

## 0. 先记住四个"演员"

| 演员 | 所在进程 | 角色 |
| --- | --- | --- |
| `Activity`（App 侧） | 调用方 App | 提出请求的「顾客」 |
| `ActivityManagerService` | system_server | 受理请求的「总台」 |
| `ActivityStarter` | system_server | 总台里的「审批专员」：解析、校验、定方案 |
| `ActivityThread`（App 侧） | 目标 App | 真正创建 Activity 的「施工队」 |

---

## 1. 全流程时序图（先看大局，再逐段拆解）

```
调用方App进程                        system_server进程                      目标App进程
     │                                    │                                    │
     │ 1. Activity.startActivity()        │                                    │
     │───────────────────────────────────▶│                                    │
     │   (经 Instrumentation.execStartActivity)                              │
     │                                    │                                    │
     │ 2. IActivityManager.startActivity()（Binder 跨进程）                   │
     │───────────────────────────────────▶│                                    │
     │                                    │ 3. AMS.startActivityAsUser()        │
     │                                    │ 4. ActivityStarter.startActivityMayWait()│
     │                                    │    ├ 解析 Intent → ActivityInfo     │
     │                                    │    ├ 权限/用户/安全校验              │
     │                                    │    ├ 任务栈决策（launchMode）        │
     │                                    │    ├ 暂停当前前台 Activity           │
     │                                    │    └ 目标进程存在吗？─── 不存在 ──▶ 5. 请求 Zygote fork 新进程
     │                                    │                                    │ 6. 新进程启动 ActivityThread.main()
     │                                    │◀───────────────────────────────────│ 7. attachApplication（自报家门）
     │                                    │ 8. realStartActivityLocked          │
     │                                    │───────────────────────────────────▶│ 9. IApplicationThread.scheduleLaunchActivity
     │                                    │                                    │10. handleLaunchActivity → onCreate/onStart
     │                                    │◀───────────────────────────────────│11. handleResumeActivity → onResume（上屏）
     │                                    │                                    │
```

> 注意第 6~8 步：**如果目标 App 进程还没启动，AMS 会先拉一个进程起来，再走创建 Activity 的流程**。
> 这就是「冷启动」和「热启动」的本质区别（详见文档 06）。

---

## 2. 第一段：App 侧发起请求

文件：`frameworks/base/core/java/android/app/Activity.java`、`Instrumentation.java`（第 1578 行 `execStartActivity`）

调用链：

```java
Activity.startActivityForResult(intent, requestCode)
  └─▶ Instrumentation.execStartActivity(...)          // 加了一层"手术室无菌检查"（权限、监控）
        └─▶ ActivityManager.getService().startActivity(...)   // 跨进程，走 Binder
              └─▶ IActivityManager.Stub.Proxy.startActivity(...)  // 打包参数 → transact()
```

这一段你要掌握的**只有两个结论**：
1. App 侧所有启动请求都会经过 `Instrumentation`（它还能拦截/监控 Activity 生命周期，测试框架就靠它）；
2. 真正发出跨进程调用的是 `ActivityManager.getService()` 拿到的 Binder 代理。

---

## 3. 第二段：AMS 受理

文件：`frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java`

第 4516 行 `startActivity` → 第 4525 行 `startActivityAsUser`：

```java
@Override
public final int startActivityAsUser(IApplicationThread caller, String callingPackage,
        Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
        int startFlags, ProfilerInfo profilerInfo, Bundle bOptions, int userId) {
    enforceNotIsolatedCaller("startActivity");                       // 安全检查①
    userId = mUserController.handleIncomingUser(...);                // 用户/多用户检查②
    return mActivityStarter.startActivityMayWait(caller, -1, callingPackage, intent,
            resolvedType, null, null, resultTo, resultWho, requestCode, startFlags,
            profilerInfo, null, null, bOptions, false, userId, null, "startActivityAsUser");
}
```

**这段的考点**：AMS 自己不干细活，检查完基本安全后**立刻把活外包给 `ActivityStarter`**。
这就是 8.1 代码里的「单一职责」设计：AMS 是大管家，具体审批在 `ActivityStarter`。

---

## 4. 第三段：ActivityStarter 审批（系统侧的核心）

文件：`frameworks/base/services/core/java/com/android/server/am/ActivityStarter.java`

方法链：`startActivityMayWait`(673) → `startActivityLocked`(263) → `startActivity`(294，私有)

它依次做了这些事：

| 步骤 | 干什么 | 大白话 |
| --- | --- | --- |
| ① 拒绝危险 Intent | `intent.hasFileDescriptors()` 直接抛异常 | 防止跨进程传文件描述符（安全漏洞） |
| ② 解析 Intent | `mSupervisor.resolveIntent()` → `resolveActivity()` | 根据 action/category/data 找到具体是哪个 Activity，拿不到就报 ActivityNotFoundException |
| ③ 权限/用户校验 | 检查调用方是否有权、目标是否在当前用户可用 | 别让普通 App 启动系统隐藏界面 |
| ④ 任务栈决策 | 根据 `launchMode`、`Intent flags`、`taskAffinity` 决定：新建任务栈？复用已有 Activity？ | 决定这个 Activity 放在哪一层楼、要不要把旧的叫上来 |
| ⑤ 暂停前台 | 让当前显示中的 Activity 走 `onPause`（`ActivityStack.startPausingLocked` → App 侧 `schedulePauseActivity`） | 前台让位，给新 Activity 腾地方 |
| ⑥ 找进程 | 目标 Activity 所在进程是否存活？（`ActivityStackSupervisor.startSpecificActivityLocked`，第 1560 行） | 看"施工队"在不在 |
| ⑦ 派活 | 进程在 → `realStartActivityLocked`（第 1313 行）；进程不在 → 先 `AMS.startProcessLocked` 拉进程 | 施工队在就直接开工，不在先叫人 |

> 第④步是 Android 面试**最高频**的考点，详细展开请看文档 05。

---

## 5. 第四段：把"创建 Activity"的命令送进目标进程

关键代码在 `ActivityStackSupervisor.realStartActivityLocked`（第 1313 行起，第 1457 行附近）：

```java
// 伪代码示意（8.1 源码第 1457 行附近）
app.thread.scheduleLaunchActivity(new Intent(r.intent), r.appToken,
        r.ident, r.info, mergedConfiguration, ...);
```

- `app.thread` 就是**目标 App 进程暴露给 AMS 的 `IApplicationThread` Binder 引用**（文档 10 会细讲）
- `scheduleLaunchActivity` 通过 Binder 跨进程进入目标 App，让它自己创建 Activity

如果进程不存在，AMS 会先走 `startProcessLocked`（`ActivityManagerService.java` 第 3646 行起）：
1. 构造 `ProcessRecord`（进程户口本）
2. 调用 `Process.start(`android.app.ActivityThread`, ...)`（`core/java/android/os/Process.java` 第 448 行）
3. `Process.start` 通过 socket 通知 Zygote `fork` 一个新进程，新进程入口就是 `ActivityThread.main()`
4. 新进程跑起来后调用 `attachApplication` 向 AMS 报到（这就是时序图第 7 步）

---

## 6. 第五段：App 侧施工（ActivityThread）

文件：`frameworks/base/core/java/android/app/ActivityThread.java`

新进程起来后，ActivityThread 的 Handler（内部类 `H`）收到 `LAUNCH_ACTIVITY` 消息：

```java
// ActivityThread.java 第 1589 行附近
case LAUNCH_ACTIVITY:
    handleLaunchActivity(r, null, "LAUNCH_ACTIVITY");
    break;
```

`handleLaunchActivity`（第 2833 行）主要做：

| 步骤 | 说明 |
| --- | --- |
| `performLaunchActivity` | ① 用 ClassLoader 反射创建 Activity 对象 → ② `activity.attach()`（绑定 Window、Token 等）→ ③ 调 `onCreate` → ④ 调 `onStart` |
| `handleResumeActivity`（第 3608 行） | 调 `onResume`，把窗口交给 WMS 真正显示到屏幕上 |

> 到这里，一个 Activity 才算真正「上屏」。整个流程中 **AMS 只负责决策，App 进程负责执行**。

---

## 7. 返回结果：startActivityForResult 怎么把结果送回去

- 发起方启动时把 `resultTo`（自己的 Binder token）和 `requestCode` 交给 AMS；
- 目标 Activity 调 `setResult` 后 `finish`，AMS 通过 `finishActivity` 找到 `resultTo`，
  把结果包装成 `ActivityResult` 送回发起方；
- 发起方 Activity 恢复（onResume）时，`onActivityResult` 被回调。

> 记住结论：**跨进程的「回调」本质是反向 Binder 调用**，方向是 App → AMS → 另一个 App。

---

## 8. 一分钟速记卡

```
App.startActivity
  → Instrumentation（安检）
  → AMS.startActivityAsUser（总台收件）
  → ActivityStarter.startActivityMayWait（审批：解析→校验→定栈→暂停前台→找进程）
  → 进程不在? → Zygote fork 新进程 → ActivityThread.main → attachApplication
  → ActivityStackSupervisor.realStartActivityLocked
  → app.thread.scheduleLaunchActivity（Binder 下行）
  → ActivityThread.handleLaunchActivity → onCreate/onStart/onResume
```

## 9. 动手实验（本周必做）

1. 打开 `ActivityStarter.java`，把 `startActivityMayWait` 从头读到 `startActivityLocked` 入口，
   数一数它做了几层校验（看不懂的分支跳过，只找主干）。
2. 在 `ActivityStackSupervisor.java` 里定位 `startSpecificActivityLocked`（第 1560 行），
   看它判断「进程存在/不存在」的两个分支长什么样。
3. 真机/模拟器执行：
   ```bash
   adb shell am start -W -n com.android.settings/.Settings
   ```
   观察 `TotalTime` 等输出；再用 `adb logcat -s ActivityManager:I` 抓启动日志，找
   `START u0 {...}` 和 `Displayed ...` 两条日志。
4. 思考：`am start -W` 的 `-W` 是等什么？（提示：等 Activity 首次绘制完成）

## 10. 思考题

1. 如果目标 Activity 所在进程已经存在（热启动），流程会跳过哪些步骤？
2. `startActivityMayWait` 名字里的 `MayWait` 是什么意思？（看方法参数里的 `WaitResult outResult`）
3. Activity 的 `onPause` 是谁通知的、在哪个进程执行？对应源码哪一行？
4. 为什么说 AMS 是「决策者」而不是「执行者」？举出文中三个证据。

---

*上一篇：[03-AMS启动与systemReady.md](03-AMS启动与systemReady.md)　|　下一篇：[05-Activity生命周期与任务栈.md](05-Activity生命周期与任务栈.md)*
