# Android Framework AMS 系统学习手册（AOSP 8.1.0）

> 从「会用」到「看得懂源码」再到「能给别人讲清楚」，手把手带你啃下 ActivityManagerService。
> 所有代码引用都基于本机仓库 **AOSP 8.1.0（android-8.1.0_r1）**，路径以仓库根目录 `/home/zyijian/bin/aosp810` 为基准。

---

## 一、这套资料是给谁看的

| 画像 | 是否适合 |
| --- | --- |
| 刚接触 Android 底层，想系统了解 Framework | ✅ 强烈推荐 |
| 做应用开发，想搞懂 startActivity 背后发生了什么 | ✅ 推荐 |
| 准备 Android 底层/Framework 岗位面试 | ✅ 推荐 |
| 需要读/改 system_server 源码（ROM、系统优化） | ✅ 必读 |

**前置知识（不需要精通，能查能用即可）：**
- Java 基础、面向对象（泛型、接口、抽象类）
- Android 四大组件的基本用法（会写 Activity/Service/广播/ContentProvider）
- 知道什么是进程（Process）、线程（Thread）、Handler/Looper 消息机制
- 会用 `adb` 和 `logcat`

> 如果 Handler/Looper 还不太熟，建议先花 1~2 天把 Handler 机制补上，它是理解 ApplicationThread 的钥匙。

---

## 二、学习目标（分四层，逐层升级）

1. **会用**：知道 `am start`、`dumpsys activity`、常见 tag 日志怎么看
2. **看懂**：能把 `startActivity`、`startService` 的一条调用链在源码里走通
3. **能改**：能在 AMS 里加日志、改 oom_adj 策略、理解/修改某个流程
4. **能讲**：不看代码，能画图给别人讲清楚「Activity 是怎么被启动的」「进程是怎么被杀的」

每一篇文档末尾都给了「动手实验」和「思考题」，请务必做完再进入下一篇。

---

## 三、目录导航

| 篇 | 内容 | 对应知识点 |
| --- | --- | --- |
| [01-预备知识-Binder与进程模型.md](01-预备知识-Binder与进程模型.md) | Binder 原理 + Android 进程模型 | 一切通信的基础 |
| [02-总体架构-AMS在系统中的位置.md](02-总体架构-AMS在系统中的位置.md) | AMS 是什么、负责什么、内部有哪些类 | 全局地图 |
| [03-AMS启动与systemReady.md](03-AMS启动与systemReady.md) | Zygote→SystemServer→AMS→systemReady | AMS 从出生到上岗 |
| [04-Activity启动全流程.md](04-Activity启动全流程.md) | startActivity 一条龙 | 必考重点 |
| [05-Activity生命周期与任务栈.md](05-Activity生命周期与任务栈.md) | 生命周期状态机 + launchMode + 任务栈 | 重点 |
| [06-进程管理-ProcessRecord与oom_adj.md](06-进程管理-ProcessRecord与oom_adj.md) | 进程记录、oom_adj、lmkd、杀进程 | 重点 |
| [07-Service管理.md](07-Service管理.md) | startService / bindService 全流程 | 重点 |
| [08-Broadcast广播.md](08-Broadcast广播.md) | 动态/静态注册、有序/无序广播 | 重点 |
| [09-ContentProvider.md](09-ContentProvider.md) | Provider 安装与访问流程 | 了解即可 |
| [10-Binder客户端-ActivityThread与ActivityManager.md](10-Binder客户端-ActivityThread与ActivityManager.md) | 应用进程侧的真相 | 打通双视角 |
| [11-调试与实战.md](11-调试与实战.md) | dumpsys / logcat / am 命令 / 断点 | 方法论 |
| [12-术语表与FAQ.md](12-术语表与FAQ.md) | 术语、答疑、参考资料 | 查漏补缺 |
| [13-面试题库与自测清单.md](13-面试题库与自测清单.md) | 48 道 AMS 面试题：按主题分组、难度分级（无答案，先自测） | 练中学 |
| [14-面试题答案与解析-上篇.md](14-面试题答案与解析-上篇.md) | 题 1~21 答案解析（Binder/架构/启动/Activity/生命周期栈） | 练中学 |
| [15-面试题答案与解析-下篇.md](15-面试题答案与解析-下篇.md) | 题 22~48 答案解析（进程/Service/广播/Provider/客户端/实战/综合） | 练中学 |

**推荐阅读顺序**：01 → 02 → 03 → 04 → 05 → 10 → 06 → 07 → 08 → 09 → 11 → 12。
学完任意一篇，随手做 [13-面试题库与自测清单.md](13-面试题库与自测清单.md) 里对应分组的题，
再对照 [14-面试题答案与解析-上篇.md](14-面试题答案与解析-上篇.md) / [15-面试题答案与解析-下篇.md](15-面试题答案与解析-下篇.md) 查漏 —— 练中学，记得最牢。
（04/05 必须结合 10 一起看，因为 App 进程那一半在 ActivityThread 里。）

---

## 四、12 周学习计划

> 每周约 6~10 小时。可压缩为 6 周（每周 2 篇），也可拉长到 3 个月细读。
> 每篇文档末尾有「动手实验」，做完才算完成当周。

| 周次 | 主题 | 对应文档 | 核心产出/验收 |
| --- | --- | --- | --- |
| 第 1 周 | Binder 与 Android 进程模型 | 01 | 能画出一次 Binder 调用的完整路径；自己写一个 AIDL demo |
| 第 2 周 | 全局架构：AMS 在系统中的位置 | 02 | 能默写 AMS 内部 10+ 个核心类各自的职责 |
| 第 3 周 | AMS 启动与 systemReady | 03 | 能说出 Zygote→SystemServer→AMS 的启动顺序，看懂 `am start` 前系统已就绪 |
| 第 4 周 | Activity 启动全流程（上） | 04 | 能在源码中走通 startActivity 到 scheduleLaunchActivity |
| 第 5 周 | Activity 启动全流程（下）+ 生命周期 | 05 | 能画出生命周期状态机；说清四种 launchMode |
| 第 6 周 | 客户端视角：ActivityThread/ApplicationThread | 10 | 能讲清「应用进程侧到底发生了什么」 |
| 第 7 周 | 进程管理与 oom_adj | 06 | 能背出 8.1 的 oom_adj 表；解释「为什么后台进程会被杀」 |
| 第 8 周 | Service 管理 | 07 | 能画出 startService/bindService 时序图 |
| 第 9 周 | 广播机制 | 08 | 能说清动态/静态注册、有序/无序广播的区别与流程 |
| 第 10 周 | ContentProvider | 09 | 能画出 Provider 安装与访问流程 |
| 第 11 周 | 调试与实战 | 11 | 熟练使用 dumpsys activity 系列命令定位问题 |
| 第 12 周 | 复盘 + 面试题 | 12 | 能不看资料，给朋友讲一遍 AMS 全貌 |

**建议节奏**：
- 每天 1~1.5 小时：先读文档 → 再跟着文档在源码里找到对应代码 → 最后做实验。
- 周末花 2 小时：把本周主题「画图复述」一遍（画不出来 = 没学会）。

---

## 五、怎么读 24000 行的 ActivityManagerService.java

这份文件在本仓库有 **24666 行**，千万别从头读到尾，正确姿势：

1. **先读文档 02 的地图**，知道有哪些类、谁负责什么。
2. **从「入口函数」切入**：看一个流程，先找它的 public 方法（如 `startActivityAsUser`），
   顺着它一层层往下跟（`startActivityMayWait` → `startActivityLocked` → ...），只跟主链路。
3. **遇到大段不相关的分支就跳过**：AMS 里有大量权限校验、兼容性处理、调试分支，
   第一遍只需要抓住主干，之后再补细节。
4. **日志是最好的路标**：`Slog.i(TAG_AM, ...)` 附近的代码就是流程的关键节点，
   先看日志字符串就能猜出这步在干什么。
5. **配合 dumpsys 验证**：代码读完后用 `adb shell dumpsys activity` 看真实状态，反哺理解。

> 推荐在 VS Code 里给本仓库装 Java 扩展，`Ctrl+Click` 跳转方法定义，跟调用链会快很多。

---

## 六、与其它 Android 版本的区别（重要！）

| 版本 | 特点 |
| --- | --- |
| 8.1（本仓库） | AMS 在 system_server 进程内；`ActivityStack` 直接管理 Activity 栈；任务栈用 `TaskRecord` |
| 9.0 | 引入 `ActivityStackSupervisor` 重构，栈管理更细 |
| 10.0+ | 拆出 `ActivityTaskManagerService`（ATMS），AMS 只管进程，Activity 栈交给 ATMS；加入 `ActivityTaskManagerService`/WMS 合并的 `ActivityTaskManager` |
| 12+ | `ActivityTaskManager` 进一步拆出 `ActivityStarter`、`TaskOrganizer` 等 |

**结论**：8.1 是「单一大 AMS」的经典形态，结构清晰、代码量适中，非常适合学习；
学会 8.1 再去读 10/11 会非常快，因为核心概念（ActivityRecord、ProcessRecord、任务栈、oom_adj）全部沿用。

---

## 七、学习纪律（三不要）

1. **不要**只看文档不看源码 —— 文档是指南针，源码才是地图。
2. **不要**追求一次全懂 —— 第一遍 60 分及格，第二遍 80，第三遍 90。
3. **不要**跳过动手实验 —— 亲手抓一次日志，胜过读十遍书。

祝你啃下 AMS！遇到卡壳的地方，回到 [12-术语表与FAQ.md](12-术语表与FAQ.md) 查漏补缺。
