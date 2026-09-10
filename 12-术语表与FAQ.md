# 12｜术语表、FAQ 与参考资料

> 一句话总结：**这一篇是「查漏补缺手册」：术语忘了随时查，疑问想不通先看 FAQ，
> 想深入再看参考资料。学完整套系列后，也可以用它做快速复盘。**

---

## 一、术语表（按字母/拼音混排）

### A
| 术语 | 全称/含义 | 一句话解释 |
| --- | --- | --- |
| AMS | ActivityManagerService | 系统进程里的「大管家」，管组件与进程 |
| ActivityRecord | Activity 记录 | AMS 里一个 Activity 的档案 |
| ActivityStack | Activity 栈 | 管理一组任务栈（Task）的"楼" |
| ActivityStackSupervisor | 栈总监理 | 管所有 ActivityStack 的调度者 |
| ActivityStarter | 启动审批器 | 解析/校验/决定怎么启动 Activity |
| ActivityThread | Activity 线程 | App 进程主线程上的总执行者 |
| ANR | Application Not Responding | 应用无响应（输入/广播/服务超时） |

### B
| 术语 | 全称/含义 | 一句话解释 |
| --- | --- | --- |
| Binder | Android IPC 机制 | Android 的跨进程调用「快递系统」 |
| BroadcastQueue | 广播队列 | 排队投递广播的部门（并行/有序两条队列） |

### C
| 术语 | 全称/含义 | 一句话解释 |
| --- | --- | --- |
| ContentProviderRecord | Provider 档案 | AMS 里一个 ContentProvider 的记录 |
| ConnectionRecord | 绑定连接记录 | 一次 Service 绑定关系的记录 |

### H
| 术语 | 全称/含义 | 一句话解释 |
| --- | --- | --- |
| Handler/Looper | 消息机制 | 主线程的「公告栏 + 消息泵」，App 侧所有生命周期回调都靠它执行 |

### I
| 术语 | 全称/含义 | 一句话解释 |
| --- | --- | --- |
| IActivityManager | AMS 的 AIDL 接口 | App → 系统的请求通道 |
| IApplicationThread | App 的对讲机接口 | 系统 → App 的命令通道 |
| IBinder | Binder 对象接口 | 跨进程引用的统一类型 |

### L
| 术语 | 全称/含义 | 一句话解释 |
| --- | --- | --- |
| launchMode | 启动模式 | standard/singleTop/singleTask/singleInstance |
| lmkd | Low Memory Killer Daemon | 按 oom_adj 执行「末位淘汰」的守护进程 |
| Looper | 见 Handler | — |

### O
| 术语 | 全称/含义 | 一句话解释 |
| --- | --- | --- |
| oom_adj | OOM Adjustment | 进程「生存分」，越大越先被杀 |

### P
| 术语 | 全称/含义 | 一句话解释 |
| --- | --- | --- |
| PMS | PackageManagerService | 包管理服务（谁装了哪些 App、谁能响应 Intent） |
| ProcessRecord | 进程档案 | AMS 里一个 App 进程的「户口本」 |
| ProcessList | 进程管理表 | 计算/下发 oom_adj 的部门 |
| ProviderMap | Provider 登记表 | 按 authority 索引 Provider |

### S
| 术语 | 全称/含义 | 一句话解释 |
| --- | --- | --- |
| ServiceRecord | Service 档案 | AMS 里一个 Service 的记录 |
| ServiceManager | 服务登记处 | 全局 Binder 服务「门牌号」注册表 |
| SystemServer | 系统服务进程 | 跑着 AMS/PMS/WMS 等所有核心服务的特权进程 |
| systemReady | 系统就绪 | AMS 开张营业的剪彩时刻（启动桌面、发 BOOT_COMPLETED） |

### T / W / Z
| 术语 | 全称/含义 | 一句话解释 |
| --- | --- | --- |
| TaskRecord | 任务记录 | 一组 Activity 的集合（返回栈的真相） |
| WMS | WindowManagerService | 窗口管理服务（Activity 上屏靠它） |
| Zygote | 孵化器 | 预加载好虚拟机/类库的进程，fork 出所有 App 进程 |

---

## 二、FAQ（高频疑问）

**Q1：AMS 和 ATMS 是什么关系？**
A：8.1 里没有 ATMS，一切都在 AMS 里。Android 10+ 把「Activity 任务栈」拆成
`ActivityTaskManagerService`（ATMS），AMS 专注进程管理。学习 8.1 后再看 10+ 会很容易。

**Q2：App 进程和 system_server 进程谁先启动？**
A：开机时 Zygote 先 fork 出 system_server；App 进程是**之后**由 AMS 按需拉起的。
所以 system_server 永远先于任何 App 进程存在。

**Q3：为什么说「AMS 只是决策者，不是执行者」？**
A：创建 Activity、跑 Service 这些真实对象都在 App 进程里；AMS 只负责决策
（谁该启动、放哪个栈、给什么 adj），然后通过 IApplicationThread 通知 App 执行。

**Q4：进程被杀后，Service 会自动重启吗？**
A：视情况。如果 Service 是 `START_STICKY` 或还有绑定方/未完成使命，
AMS 收到进程死亡回调后会重新拉起进程并重建 Service；
如果是 `START_NOT_STICKY` 且没有绑定方，则不重启。

**Q5：`onPause` 之后一定会 `onStop` 吗？**
A：不一定。被半透明界面遮挡只触发 onPause；完全不可见才触发 onStop，
两者之间可能隔着很久甚至不触发（例如快速返回）。

**Q6：为什么主线程不能做耗时操作？**
A：所有生命周期回调、输入事件都在主线程执行（由 Handler 驱动）。
主线程被占住 → 队列里的消息无法处理 → 系统判定 ANR。

**Q7：`force-stop` 和「杀后台进程」有什么区别？**
A：后台进程被 lmkd 杀只是「暂停」，进程被再次使用时重新冷启动即可；
`force-stop` 是 AMS 主动终止，还会**取消该包的静态广播、闹钟、Job 等所有待办**，
直到用户再次手动打开 App。

**Q8：动态注册的 Receiver 和静态注册的，进程被杀了会怎样？**
A：动态注册随进程死亡自动失效（进程都没了）；静态注册由 AMS/PMS 登记在册，
广播到来时会**重新拉起进程**再投递（这也是静态广播耗电/扰民的根源）。

**Q9：怎么区分冷启动和热启动？**
A：看日志。有 `Start proc ... for activity` = 冷启动（进程被拉起）；
没有 = 热启动（进程还在，直接创建 Activity）。

**Q10：为什么 8.0 要限制隐式广播的静态注册？**
A：为了省电和流畅度 —— 否则每个 App 都能被任意广播唤醒，后台进程满天飞。

---

## 三、参考资料（按推荐顺序）

### 官方与权威
- AOSP 源码（本仓库）：`frameworks/base/services/core/java/com/android/server/am/`
- Android 官方文档：Activity / Service / Broadcast / ContentProvider 的开发者指南
- AOSP 官方博客：Android Performance（关于进程与内存）

### 书籍（中文）
| 书 | 特点 |
| --- | --- |
| 《深入理解 Android 卷 1/卷 3》（邓凡平） | 卷 1 讲 Binder/AMS 底层，卷 3 讲 SystemServer 等，与 8.1 时代接近 |
| 《Android 内核剖析》（柯元旦） | 系统级原理，适合做背景阅读 |
| 《Android Framework 揭秘》（老罗等） | 流程讲解通俗，但注意版本差异 |

### 博客/社区
- Gityuan 的博客（gityuan.com）：AMS 系列图解非常经典（基于 6/7/8，与 8.1 高度吻合）
- CSDN / 掘金搜「AMS 启动流程」：找**带源码行号**的文章，注意核对版本

### 工具
- `adb shell dumpsys activity`（第 11 篇详解）
- Android Studio Profiler / Traceview：分析启动耗时
- `systrace`：抓取启动/渲染 trace

---

## 四、最后：12 周学完后的"毕业自检"

能**不看任何资料**完成以下三件事，就算毕业：
1. 画出 `startActivity` 从 App 到 AMS 再到 App 的完整时序图（含冷/热启动分支）；
2. 讲清 oom_adj 是怎么算的、lmkd 怎么用它杀进程，并解释"为什么音乐 App 在后台不容易被杀"；
3. 用 `dumpsys activity` + `logcat` 定位一个「App 启动慢」的问题并给出结论。

---

*上一篇：[11-调试与实战.md](11-调试与实战.md)　|　回到 [README.md](README.md)*
