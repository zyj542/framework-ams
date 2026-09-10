# 06｜进程管理：ProcessRecord 与 oom_adj

> 一句话总结：**AMS 给每个 App 进程都建了一本「户口本」（ProcessRecord），
> 并定期给每本户口本打一个「生存分」（oom_adj）；
> 内存不够时，低内存杀手（lmkd）按照分数从低到高依次清退进程 —— 分数越低越先死。**

---

## 1. ProcessRecord：进程的"户口本"

文件：`frameworks/base/services/core/java/com/android/server/am/ProcessRecord.java`

| 字段（8.1 真实代码） | 含义 |
| --- | --- |
| `final ApplicationInfo info`（第 59 行） | 这个进程里的第一个应用的包信息 |
| `final int uid`（第 61 行） | 进程的 UID（身份） |
| `final String processName`（第 63 行） | 进程名（默认包名，也可以 `android:process` 指定） |
| `int pid`（第 73 行） | 进程 ID（0 = 还没拉起来） |
| `int curAdj / setAdj`（第 91/92 行） | 当前计算的 oom_adj / 上一次实际下发的 oom_adj |
| `int curProcState / setProcState`（第 98/100 行） | 进程状态（前台/后台/缓存等） |
| `long lastPss`（第 84 行） | 最近一次统计的内存占用（PSS） |
| `ArrayList<ActivityRecord> activities`（第 163 行） | 这个进程里跑着的 Activity |
| `boolean persistent`（第 178 行） | 是否常驻进程（系统级，不可杀） |
| `boolean killed / killedByAm`（第 130/131 行） | 是否已死 / 是否被 AMS 杀的 |

**AMS 侧持有这些户口本的两本台账：**

| 台账 | 类型 | 用途 |
| --- | --- | --- |
| `mProcessNames`（AMS 第 782 行） | `ProcessMap<ProcessRecord>` | 按「进程名+uid」快速查找进程 |
| `mLruProcesses`（AMS 第 873 行） | `ArrayList<ProcessRecord>` | 按「最近使用」排序的列表，杀进程时从尾部开始杀 |

> 类比：户口本（ProcessRecord）记录每个进程的信息；台账（mLruProcesses）记录「谁最近被用过」，
> 淘汰（杀进程）时优先淘汰最久没用的。

---

## 2. oom_adj：决定"谁先死"的分数

### 2.1 分数表（AOSP 8.1，`ProcessList.java` 第 20~90 行）

**分数越小越「重要」，越大越「该死」**：

| oom_adj | 常量（8.1） | 进程身份 | 通俗理解 |
| --- | --- | --- | --- |
| -1000 | `NATIVE_ADJ` | 原生（非 Android 管理）进程 | 不受 AMS 管 |
| -900 | `SYSTEM_ADJ` | system_server 自身 | 系统核心，不可杀 |
| -800 | `PERSISTENT_PROC_ADJ` | 常驻进程（如电话、systemui） | 老板心腹，不可杀 |
| -700 | `PERSISTENT_SERVICE_ADJ` | 常驻服务 | 心腹的下属 |
| 0 | `FOREGROUND_APP_ADJ` | 前台 App（用户正在用） | 正在台上的演员 |
| 100 | `VISIBLE_APP_ADJ` | 可见但不在前台（如分屏/被遮挡部分可见） | 候场的演员 |
| 200 | `PERCEPTIBLE_APP_ADJ` | 可感知（如播放后台音乐） | 后台唱歌的歌手 |
| 300 | `BACKUP_APP_ADJ` | 正在备份 | 正在搬家的住户 |
| 400 | `HEAVY_WEIGHT_APP_ADJ` | 重量级 App（启动页过重） | 大件行李住户 |
| 500 | `SERVICE_ADJ` | 有 Service 的进程 | 干活的员工 |
| 600 | `HOME_APP_ADJ` | 桌面 Launcher | 前台接待员 |
| 700 | `PREVIOUS_APP_ADJ` | 上一个用过的 App | 刚离开的顾客 |
| 800 | `SERVICE_B_ADJ` | 老旧的 Service 进程 | 快退休的员工 |
| 900~906 | `CACHED_APP_MIN/MAX_ADJ` | 缓存进程（Activity 全退到后台） | 逛完街在商场门口发呆的顾客 |

### 2.2 关键结论

1. **oom_adj 越大越容易被杀**：系统内存不足时，从 906 开始往下杀（先杀缓存进程）。
2. **adj 是动态计算的**：一个进程的 adj 由它**当前跑着什么**决定（前台 Activity → 0，只有 Service → 500...）。
3. **adj 会传播**：A 进程被 B 进程绑定/依赖时，A 会继承 B 的「重要性」（clientAdj 传递，见
   `computeOomAdjLocked` 中大量「找 client」的逻辑）。
4. **adj 算完要下发**：`ProcessList.setOomAdj(pid, uid, amt)`（第 630 行）通过 socket 把每个进程的
   adj 告诉 lmkd 内核守护进程。

---

## 3. 谁来算？谁来杀？

### 3.1 计算方：AMS

```text
ActivityManagerService.updateOomAdjLocked()（多处调用，如第 2560 行）
   └─ computeOomAdjLocked(app, ...)（第 20838 行）  ← 逐个进程打分
        ├─ 根据进程里最"重要"的组件决定基础分
        └─ 处理依赖关系（clientAdj 传递）
   └─ ProcessList.setOomAdj(pid, uid, adj)（第 630 行）  ← 分数下发给 lmkd
```

**触发时机**：每次组件状态变化（Activity 暂停/恢复、Service 启动/停止、广播处理完）后，
AMS 都会重新算一遍受影响进程的 adj。

### 3.2 执行方：lmkd（low memory killer daemon）

- lmkd 是系统里的一个**内核态守护进程**，在 `init.rc` 里启动（`system/core/rootdir/init.rc` 第 34 行附近）
- 它监听系统内存水位；**内存紧张时，按照 AMS 下发的 oom_adj，从 adj 最大的进程开始杀**（发 SIGKILL）
- 所以「App 在后台被杀」不是随机事件，而是 lmkd 按 AMS 给的分数执行的「末位淘汰」

> 类比：AMS 是「绩效考核部」，每月给员工打分；lmkd 是「裁员执行人」，
> 公司（内存）快破产时就按分数从低往高裁。绩效表不发过去，裁员执行人不知道裁谁。

---

## 4. 进程的生死：启动与杀死

### 4.1 冷启动进程（startProcessLocked）

```text
AMS.startProcessLocked(processName, info, ...)（第 3646 行起）
   ├─ 创建/复用 ProcessRecord，登记进 mProcessNames、mLruProcesses
   ├─ Process.start("android.app.ActivityThread", ...)（Process.java 第 448 行）
   │     └─ 通过 socket 通知 Zygote fork 新进程
   └─ 新进程入口：ActivityThread.main() → attachApplication() 向 AMS 报到
```

### 4.2 杀进程

```text
AMS.killProcessLocked / killAppProcessesLocked
   └─ Process.killProcessQuiet(pid)（内核发 SIGKILL）
```

用户可感知的杀进程入口：
```bash
adb shell am force-stop <package>   # 走 AMS.forceStopPackage
adb shell am kill-all               # 杀所有后台进程
```

> 注意：**force-stop 之后，该 App 的静态广播、闹钟等也会被停**（这是 Android 的「强制停止」语义）。

---

## 5. 冷启动 vs 热启动（面试常考）

| | 冷启动（Cold Start） | 热启动（Warm Start） |
| --- | --- | --- |
| 进程 | 不存在，要 fork 新进程 | 进程还活着 |
| 过程 | fork → ActivityThread.main → attachApplication → 创建 Activity | 直接 realStartActivityLocked → scheduleLaunchActivity |
| 耗时 | 慢（可能要几百 ms 到秒级） | 快 |
| 对应文档 | 04 时序图第 5~7 步 | 04 时序图直接第 8 步 |

> 判断当前是冷还是热，可以看日志：冷启动会有 `Start proc ... for activity` 这条日志。

---

## 6. 一分钟速记卡

```
ProcessRecord = 户口本（进程名/uid/pid/adj/跑着哪些组件）
mProcessNames = 按名字找户口本
mLruProcesses = 按最近使用排序的名单
oom_adj = 生存分（0 前台 / 200 可感知 / 500 服务 / 900+ 缓存）
计算：AMS.updateOomAdjLocked → computeOomAdjLocked → ProcessList.setOomAdj → lmkd
执行：内存不足 → lmkd 从 adj 最大开始 SIGKILL
```

## 7. 动手实验（本周必做）

1. 打开 `ProcessList.java`，把 2.1 的分数表对着源码抄一遍（第 20~90 行），写清每个常量用途。
2. 找 `computeOomAdjLocked`（AMS 第 20838 行），看它是怎么给「缓存进程」和「前台进程」打分的。
3. 真机/模拟器实验：
   ```bash
   # 启动几个 App 再切回桌面
   adb shell dumpsys activity processes | grep -A3 "oom adj"
   # 观察不同 App 的 oom adj / procState 数值
   adb shell cat /proc/<pid>/oom_adj   # 内核实际看到的分数
   ```
4. 制造内存压力（开很多大 App 或 `adb shell am send-trim-memory` 相关命令），观察 lmkd 杀进程日志：
   ```bash
   adb logcat -s lowmemorykiller lmkd ActivityManager:I
   ```

## 8. 思考题

1. 为什么「可见但不在前台」的进程（adj=100）比「有 Service 的进程」（adj=500）更难被杀？
2. 一个进程既跑着前台 Activity 又跑着 Service，它的 oom_adj 取哪个？为什么？
3. `persistent=true` 的进程真的永远不会被杀吗？什么情况下会被杀？
4. lmkd 和 `Process.killProcessQuiet` 杀进程有什么区别？（一个按分裁员，一个点名枪毙）

---

*上一篇：[05-Activity生命周期与任务栈.md](05-Activity生命周期与任务栈.md)　|　下一篇：[07-Service管理.md](07-Service管理.md)*
