# 08｜广播机制：Broadcast

> 一句话总结：**广播就是系统里的「大喇叭」：发送方喊一嗓子（sendBroadcast），
> AMS 的广播站（BroadcastQueue）负责把话传给所有「登记过收听」的人（Receiver），
> 传话时还区分「一起喊」（并行）和「一个传一个」（有序）。**

---

## 1. 先分清两个角色

| 角色 | 谁 | 怎么登记 |
| --- | --- | --- |
| 发送方 | 任何进程 | 调 `sendBroadcast / sendOrderedBroadcast / sendStickyBroadcast` |
| 接收方 | 任意进程里的 Receiver | **动态注册**：代码 `registerReceiver`；**静态注册**：Manifest 里 `<receiver>` |

**8.1 里两种注册的本质区别：**

| 注册方式 | 谁负责 | 生命周期 |
| --- | --- | --- |
| 动态注册 | App 进程调用 AMS.`registerReceiver`（AMS 第 18712 行） | 跟随进程/调用方，进程死了注册就没了 |
| 静态注册 | 包安装/系统启动时由 PMS 扫描 Manifest，转交给 AMS 登记 | 包没卸载就一直有效（但 8.0+ 对隐式广播有限制） |

> 注意：**静态注册的 Receiver 不一定有常驻进程** —— 广播来了 AMS 才去拉起进程（这很关键，见第 4 节）。

---

## 2. 广播站内部结构：两条队列

文件：`frameworks/base/services/core/java/com/android/server/am/BroadcastQueue.java`

| 队列（8.1 真实字段） | 用途 | 类比 |
| --- | --- | --- |
| `mParallelBroadcasts`（第 97 行） | **并行广播**：一次性同时发给所有接收者 | 群发短信，同时送达 |
| `mOrderedBroadcasts`（第 106 行） | **有序广播**：一个接一个传，可被拦截/修改 | 击鼓传花，一个传一个 |

- **普通 `sendBroadcast`** → 进并行队列
- **`sendOrderedBroadcast`** → 进有序队列（按优先级 `android:priority` 排序，接收者可 `abortBroadcast` 中断）
- 前台广播（`FLAG_RECEIVER_FOREGROUND`）处理时限约 10s；后台广播约 60s，超时 → ANR

---

## 3. 发送一条广播的全流程

```
发送方App                        system_server                                接收方App
   │                                 │                                           │
   │ sendBroadcast(intent)           │                                           │
   │ AMS.broadcastIntent()（Binder）  │                                           │
   │────────────────────────────────▶│                                           │
   │                                 │ 1. AMS.broadcastIntentLocked(19097)        │
   │                                 │     ├─ 找匹配的 Receiver（动态+静态）       │
   │                                 │     ├─ 权限检查                            │
   │                                 │     └─ 分类入队：                          │
   │                                 │        ├─ 并行 → enqueueParallelBroadcastLocked(215)
   │                                 │        └─ 有序 → enqueueOrderedBroadcastLocked(220)
   │                                 │ 2. scheduleBroadcastsLocked(383)           │
   │                                 │     （通知广播线程去处理）                    │
   │                                 │ 3. processNextBroadcast                    │
   │                                 │     ├─ 并行：一次性全部投递                 │
   │                                 │     └─ 有序：取队首，投给一个人              │
   │                                 │──────────────────────────────────────────▶│
   │                                 │ 4. app.thread.scheduleReceiver(301)        │
   │                                 │                                           │ 5. ActivityThread.handleReceiver(3141)
   │                                 │                                           │    → Receiver.onReceive()
   │                                 │◀──────────────────────────────────────────│ 6. 处理完 → finishReceiver
   │                                 │     有序广播才传给下一个人
```

关键点：
- **并行广播**：一次 Binder 调用群发，Receiver 的 `onReceive` 在各自进程执行，无先后；
- **有序广播**：`processNextBroadcast` 一次只投一个人，等它 `finishReceiver` 回执后才传下一个；
- **广播目标进程没启动**：AMS 会先 `startProcessLocked` 拉起进程再投递（静态注册 Receiver 就是这么被唤醒的）。

---

## 4. 静态广播与 8.0 的限制（面试重点）

Android 8.0 起，**大部分隐式广播（没有明确指定包名/组件的）不再支持静态注册**，
因为 Google 要打击「后台频繁唤醒」。但以下例外仍可用（常见清单）：
`BOOT_COMPLETED`、`LOCKED_BOOT_COMPLETED`、`MY_PACKAGE_REPLACED`、`PACKAGE_*_REMOVED` 等。

- **显式广播**（指定了 `setPackage` / `setComponent`）不受此限制，可用于 App 间/应用内通信。

---

## 5. 特殊广播：Sticky 与 Protected

| 类型 | 行为 |
| --- | --- |
| Sticky 广播（`sendStickyBroadcast`） | 广播「粘」在系统里，**之后**注册的 Receiver 也能立刻收到最近一次的值（如电池电量 ACTION_BATTERY_CHANGED） |
| Protected 广播 | 只有系统能发（`protected-broadcast` 白名单），防止普通 App 伪造系统事件（如 `ACTION_BOOT_COMPLETED` 不允许普通 App 发） |

> 面试易错点：普通 App 不能发 `BOOT_COMPLETED`（会被拒绝），只有系统可以。

---

## 6. 一分钟速记卡

```
注册：动态 registerReceiver（AMS 18712）/ 静态 Manifest（PMS 扫描转 AMS）
发送：broadcastIntentLocked（19097）
队列：mParallelBroadcasts（群发）/ mOrderedBroadcasts（击鼓传花）
处理：scheduleBroadcastsLocked(383) → processNextBroadcast → app.thread.scheduleReceiver(301)
App 侧：ActivityThread.handleReceiver(3141) → onReceive
```

## 7. 动手实验（本周必做）

1. 打开 `BroadcastQueue.java`，把 `enqueueParallelBroadcastLocked`（215）、`enqueueOrderedBroadcastLocked`（220）、
   `scheduleBroadcastsLocked`（383）、`processNextBroadcast` 四个方法的位置标出来，按第 3 节时序图走一遍。
2. 写测试 App：动态注册一个 Receiver + 发一条普通广播和一条有序广播，logcat 过滤
   `ActivityManager|ActivityThread|BroadcastQueue`（8.1 广播日志 tag 是 `BroadcastQueue`），
   观察两种广播的投递顺序差异。
3. 命令实验：
   ```bash
   adb shell am broadcast -a com.example.TEST -n com.example/.MyReceiver
   adb shell dumpsys activity broadcasts   # 看广播队列/历史
   ```
4. 尝试在普通 App 里发送 `ACTION_BOOT_COMPLETED`，观察 logcat 里的拒绝日志（体会 Protected 广播）。

## 8. 思考题

1. 有序广播的接收者 A 调用了 `abortBroadcast()`，后面的接收者还能收到吗？`setResult` 的作用是什么？
2. 为什么 8.0 要限制隐式广播的静态注册？如果不禁会有什么危害？
3. 动态注册的 Receiver 跑在哪个线程？`onReceive` 里能直接做耗时操作吗？
4. 广播的接收者进程被杀了，AMS 会像 Service 那样自动重启它吗？为什么？

---

*上一篇：[07-Service管理.md](07-Service管理.md)　|　下一篇：[09-ContentProvider.md](09-ContentProvider.md)*
