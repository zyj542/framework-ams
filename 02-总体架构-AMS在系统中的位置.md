# 02｜总体架构：AMS 在系统中的位置

> 一句话总结：**AMS 是 Android 系统的「进程大管家 + 组件调度中心」——
> 谁可以启动、谁先显示、谁该被杀、进程不够用了优先杀谁，全听它的。**

---

## 1. 一张图看懂 AMS 的位置

```
                        ┌─────────────────────────────────────────┐
                        │              system_server 进程          │
                        │  （所有系统服务的"老巢"，一个有特权的进程）      │
                        │                                         │
                        │   ┌───────────────────────────────┐     │
   App 进程              │   │        ActivityManagerService │     │
 ┌──────────────┐        │   │   （AMS：进程大管家 + 组件调度） │     │
 │ Activity     │        │   │                               │     │
 │ (界面)        │        │   │   ├─ mStackSupervisor (栈管理) │     │
 │ Service      │        │   │   ├─ mActivityStarter (启动器) │     │
 │ Receiver     │ Binder │   │   ├─ mProcessList (进程清单)   │     │
 │ ContentProvider│ ◀────┼──▶ │   ├─ mServices (服务管理)     │     │
 │ ActivityThread│        │   │   ├─ mBroadcastQueues (广播)  │     │
 └──────────────┘        │   │   └─ mProviderMap (Provider)  │     │
                         │   └───────────────┬───────────────┘     │
                         │                   │ 互相调用（本地方法）    │
                         │   ┌───────────────▼───────────────┐     │
                         │   │ PackageManagerService (PMS)    │     │
                         │   │ WindowManagerService (WMS)     │     │
                         │   │ ... 其它上百个系统服务            │     │
                         │   └───────────────────────────────┘     │
                         └─────────────────────────────────────────┘
```

**一句话记忆**：
- App 进程通过 Binder 找到 AMS；
- AMS 住在 system_server 里，管着所有 App 进程；
- AMS 干活时还需要 PMS（查谁能响应这个 Intent）、WMS（把窗口放上屏幕）等兄弟服务配合。

---

## 2. AMS 到底负责哪些事？

| 职责 | 通俗解释 | 对应 8.1 源码类（都在 `com.android.server.am` 包） |
| --- | --- | --- |
| **Activity 调度** | 决定哪个 Activity 该显示、谁该暂停/停止 | `ActivityStack`、`ActivityStackSupervisor`、`ActivityStarter` |
| **进程管理** | 记录每个进程、计算优先级（oom_adj）、杀进程 | `ProcessList`、`ProcessRecord` |
| **Service 管理** | 启动/绑定服务、服务超时（ANR）检测 | `ActiveServices`、`ServiceRecord` |
| **广播分发** | 把广播送给所有感兴趣的人 | `BroadcastQueue`、`BroadcastRecord` |
| **ContentProvider** | 提供跨进程的数据访问能力 | `ProviderMap`、`ContentProviderRecord` |
| **权限/安全** | 检查调用方是否有权做某事 | AMS 内大量 `enforce*Permission` 方法 |
| **系统状态** | ANR、崩溃处理、内存压力通知、电源/电池统计等 | `AppErrors`、`ProcessStatsService` 等 |

> 类比：把 Android 系统想象成一家**大商场**。
> AMS 是「物业+总调度」：谁入驻（进程）、谁开业（Activity 上屏）、谁该歇业（暂停/停止）、
> 消防不过关要清退（杀进程），全都归它管。

---

## 3. AMS 的"家庭关系"：内部核心类地图

下面这些类**全都在**本仓库这个目录下，请现在就打开文件夹熟悉一遍：

```text
frameworks/base/services/core/java/com/android/server/am/
```

### 3.1 组件"档案"类（记录某个东西是谁、什么状态）

| 类 | 记录的是 | 通俗类比 |
| --- | --- | --- |
| `ActivityRecord` | 一个 Activity 的档案（包名、进程名、状态、所在栈） | 某间商铺的登记册 |
| `ProcessRecord` | 一个 App 进程的档案（pid、oom_adj、跑着哪些组件） | 某家公司的营业执照+人员名单 |
| `ServiceRecord` | 一个 Service 的档案 | 商场里的一个服务台登记 |
| `BroadcastRecord` | 一次广播的档案（发给谁、带什么数据） | 一次群发短信的底单 |
| `ContentProviderRecord` | 一个 Provider 的档案 | 图书馆里一个书架的索引卡 |

### 3.2 流程"执行"类（真正干活的调度器）

| 类 | 干什么 | 通俗类比 |
| --- | --- | --- |
| `ActivityStarter` | 承接 startActivity 请求，做解析、校验、决定怎么启动 | 招商部的「入驻审批窗口」 |
| `ActivityStackSupervisor` | 统筹所有 ActivityStack、协调启动/恢复/暂停 | 楼层经理（管所有楼层） |
| `ActivityStack` | 管理一个栈里的 Activity（任务栈） | 某一层的商铺排布表 |
| `ActiveServices` | 管理所有 Service 的启动/绑定/销毁 | 后勤部（管所有服务台） |
| `BroadcastQueue` | 排队派发广播 | 广播站（按顺序喊话） |
| `ProcessList` | 计算、更新每个进程的 oom_adj | 绩效考核部门（算谁该优先活） |

> 在 8.1 里这些类都是 `final` / 包私有类，由 AMS 持有引用并统一调度：
> `ActivityManagerService.java` 第 613、616、774、1021 行附近可以看到
> `mStackSupervisor`、`mActivityStarter`、`mProcessList`、`mServices` 等字段。

---

## 4. AMS 与 App 进程的"双向通信"

这是最容易绕晕的地方，先记住结论：

| 方向 | 走哪个接口 | 举例 |
| --- | --- | --- |
| **App → 系统**（上行请求） | `IActivityManager`（App 持有 AMS 的 Proxy） | 请求启动 Activity、注册广播 |
| **系统 → App**（下行通知） | `IApplicationThread`（AMS 持有 App 的 Proxy） | 通知 App 创建 Activity、暂停 Activity |

> App 进程在启动时，会通过 `attachApplication` 把自己这边的「电话线」`IApplicationThread`
> 报给 AMS（详见文档 10）。从此 AMS 想指挥 App，就直接拨这条线，不需要 App 先来问。

---

## 5. 为什么要搞这么复杂？

简单回答：**把「管人的权力」集中在唯一一个系统进程里**。

- 所有 App 的生死存亡由一个权威机构（AMS）统一裁决 → 系统稳定、公平；
- 权限检查在系统侧做（谁在调用、有没有权限由内核提供身份）→ 安全；
- App 崩溃、被清理，AMS 都能感知并善后（回收任务栈、杀父进程、提示用户）→ 体验可控。

---

## 6. 动手实验（本周必做）

1. 打开 `frameworks/base/services/core/java/com/android/server/am/` 目录，
   数一数有多少个 `.java` 文件，找到 02 文档表格里提到的每一个类。
2. 在 `ActivityManagerService.java` 里用「查找」分别定位 `mStackSupervisor`、`mActivityStarter`、
   `mProcessList`、`mServices` 四个字段的定义行，感受它们被 AMS 统一持有。
3. 运行：`adb shell dumpsys activity`，观察输出里有没有 activities / processes / services 这几个分区。

## 7. 思考题

1. 为什么 AMS 的锁（`synchronized (ActivityManagerService.this)`）几乎到处都是？如果去掉会怎样？
2. `ActivityStackSupervisor` 和 `ActivityStack` 是什么关系？（谁管理谁？）
3. 猜一猜：`ProcessRecord` 里应该有哪些字段？打开源码验证一下你的猜测。

---

*上一篇：[01-预备知识-Binder与进程模型.md](01-预备知识-Binder与进程模型.md)　|　下一篇：[03-AMS启动与systemReady.md](03-AMS启动与systemReady.md)*
