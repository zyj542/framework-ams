# 07｜Service 管理：startService 与 bindService

> 一句话总结：**Service 是「在后台替 App 干活的员工」，由 AMS 侧的后勤部（ActiveServices）统一管理：
> startService 是「派个活就走，干完算数」；bindService 是「签合同绑定关系，甲方走了合同作废」。**

---

## 1. Service 的两种打开方式

| 方式 | 生命周期 | 特点 | 对应方法 |
| --- | --- | --- | --- |
| `startService` | `onCreate` → `onStartCommand`（可多次）→ 外部 `stopService` 才 `onDestroy` | 与调用方无关，干到被叫停 | `startService` / `stopService` |
| `bindService` | `onCreate` → `onBind` → 所有绑定方 `unbind` 后 `onDestroy` | 跟随绑定方生死 | `bindService` / `unbindService` |

> 类比：startService 像「叫外卖」（下单就不管了，商家做完了事）；
> bindService 像「签了包月合同」（甲方一直在，服务就一直续着，甲方撤了合同就终止）。

---

## 2. 管理 Service 的部门：ActiveServices

文件：`frameworks/base/services/core/java/com/android/server/am/ActiveServices.java`

AMS 自己不直接管 Service，而是把活包给 `ActiveServices`（AMS 第 1021 行字段 `mServices`）。

| 类 | 记录什么 |
| --- | --- |
| `ServiceRecord` | 一个 Service 的档案（`ServiceRecord.java`）：`name`（第 68 行）、`appInfo`（第 74 行）、`processName`（第 78 行）、`connections`（第 86 行，绑定连接列表）、`app`（第 90 行，运行在哪个进程） |
| `ConnectionRecord` | 一条绑定关系（谁绑了谁） |
| `IntentBindRecord` | Service 与 Intent 之间的绑定结果（`onBind` 返回的 Binder） |

---

## 3. startService 全流程

```
调用方App                          system_server                                目标App进程
   │                                   │                                           │
   │ Context.startService()            │                                           │
   │ AMS.startService()（Binder）       │                                           │
   │──────────────────────────────────▶│                                           │
   │                                   │ 1. ActiveServices.startServiceLocked(327) │
   │                                   │     ├─ 查 ServiceRecord（没有就建）        │
   │                                   │     └─ bringUpServiceLocked(2055)         │
   │                                   │         ├─ 目标进程在吗？                  │
   │                                   │         │   不在 → AMS.startProcessLocked  │
   │                                   │         └─ realStartServiceLocked(2201)    │
   │                                   │──────────────────────────────────────────▶│
   │                                   │ 2. app.thread.scheduleCreateService(2233)  │
   │                                   │                                           │ 3. ActivityThread.handleCreateService
   │                                   │                                           │    → Service.onCreate()
   │                                   │◀──────────────────────────────────────────│ 4. scheduleServiceArgs
   │                                   │                                           │    → Service.onStartCommand()
   │                                   │                                           │    （每次 startService 都会调一次）
```

关键点：
- `ServiceRecord` 是 AMS 侧的档案；**真实 Service 对象在目标 App 进程里**由 `ActivityThread` 创建。
- `startService` 每次都会回调 `onStartCommand`（即使 Service 已存在）。
- Service 超时（前台启动后 20s / 后台 200s 内没完成创建）→ **ANR**（详见文档 11）。

---

## 4. bindService 全流程

```
调用方App                          system_server                                目标App进程
   │                                   │                                           │
   │ Context.bindService()             │                                           │
   │ AMS.bindService()（Binder）        │                                           │
   │──────────────────────────────────▶│                                           │
   │                                   │ 1. ActiveServices.bindServiceLocked(1228)  │
   │                                   │     ├─ 找/建 ServiceRecord                  │
   │                                   │     ├─ 建 ConnectionRecord（记录绑定关系）    │
   │                                   │     └─ 进程不在 → 先拉进程                  │
   │                                   │──────────────────────────────────────────▶│
   │                                   │ 2. app.thread.scheduleCreateService        │
   │                                   │    + app.thread.scheduleBindService(857)    │
   │                                   │                                           │ 3. Service.onCreate()
   │                                   │                                           │    Service.onBind() → 返回 IBinder
   │                                   │◀──────────────────────────────────────────│ 4. 把 onBind 返回的 Binder 送回
   │                                   │   ServiceRecord.app.thread.scheduleConnected│    调用方（走 ServiceConnection.onServiceConnected）
```

关键点：
- `onBind` 只会在**第一次绑定**时调用一次；后续绑定直接复用返回的 Binder。
- 绑定关系记录在 `ServiceRecord.connections`（第 86 行）；**最后一个连接断开时 Service 被销毁**。
- 绑定可以让进程提升「重要性」（adj 传播）—— 被前台 App 绑定的进程不容易被杀。

---

## 5. Service 与进程的关系（重点）

- **Service 本身不决定进程**，`android:process` 决定 Service 跑在哪个进程（默认同包名进程）。
- **一个进程可以跑多个 Service**：`ProcessRecord` 的 `services` 列表记录着。
- Service 的**前后台状态**影响进程 adj：
  - 前台 Service（`startForeground`）：进程 adj ≈ 前台（0~200 之间，几乎不可杀）
  - 普通后台 Service：adj ≈ 500（`SERVICE_ADJ`）
- **Service 的进程被杀了，Service 会怎样？** AMS 会收到进程死亡回调（`handleAppDiedLocked`），
  如果 Service 还有「未完成的使命」（比如有调用方在等它），AMS 会**自动重启**它。

> 这就是为什么很多 App 用「前台服务 + 常驻通知」来保活 —— 让进程进「可感知」档，减少被杀概率。

---

## 6. 一分钟速记卡

```
ActiveServices = 后勤部
ServiceRecord = 员工档案（名字/进程/绑定关系）
startService：startServiceLocked → bringUpServiceLocked → realStartServiceLocked → scheduleCreateService → onCreate/onStartCommand
bindService  ：bindServiceLocked → 建 ConnectionRecord → scheduleCreateService + scheduleBindService → onBind → 回传 Binder
连接断开到底 → Service 销毁
```

## 7. 动手实验（本周必做）

1. 打开 `ActiveServices.java`，按第 3 节的时序图，把 `startServiceLocked`（327）→
   `bringUpServiceLocked`（2055）→ `realStartServiceLocked`（2201）走一遍。
2. 找 `scheduleServiceTimeoutLocked`（第 1828/1833 行），看 Service ANR 的超时时间在哪定义。
3. 写一个测试 App：
   - `startService` 启动一个后台 Service，logcat 过滤 `ActivityThread|ActivityManager`，观察 onCreate/onStartCommand；
   - 再 `bindService` 绑定同一个 Service，看 onBind 是否只执行一次；
   - 所有 unbind 后观察 onDestroy。
4. 用命令查看 Service 状态：
   ```bash
   adb shell dumpsys activity services
   ```

## 8. 思考题

1. startService 和 bindService 能同时用吗？同时用时生命周期怎么算？
2. `onStartCommand` 的返回值（START_STICKY / START_NOT_STICKY / START_REDELIVER_INTENT）对「进程被杀后重启」有什么影响？
3. 前台 Service（startForeground）为什么要发通知？不发会怎样？（提示：`FOREGROUND_SERVICE` 相关检查）
4. 为什么被前台 App 绑定的 Service 进程很难被杀？联系文档 06 的 adj 传播。

---

*上一篇：[06-进程管理-ProcessRecord与oom_adj.md](06-进程管理-ProcessRecord与oom_adj.md)　|　下一篇：[08-Broadcast广播.md](08-Broadcast广播.md)*
