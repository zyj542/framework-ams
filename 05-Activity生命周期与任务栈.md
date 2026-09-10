# 05｜Activity 生命周期与任务栈

> 一句话总结：**Activity 的生命周期不是它自己说了算，而是 system_server 里的「导演」
> （ActivityStack）通过 Binder 一集一集往下发指令；Activity 在哪个「片场」（任务栈）、
> 以什么「人设」出现（launchMode），也全由 AMS 侧的栈管理决定。**

---

## 1. 谁是导演？—— AMS 侧的状态机

在 8.1 中，AMS 为每个 Activity 记录一个**官方状态**，定义在：

```text
frameworks/base/services/core/java/com/android/server/am/ActivityStack.java  第 218 行
```

```java
enum ActivityState {
    INITIALIZING,   // 刚创建档案，还没真正启动
    RESUMED,        // 正在显示、能跟用户交互
    PAUSING,        // 正在暂停（过渡状态）
    PAUSED,         // 已暂停（不可见但可能还占着屏幕下的位置）
    STOPPING,       // 正在停止（过渡状态）
    STOPPED,        // 已停止（完全不可见）
    FINISHING,      // 正在退出
    DESTROYING,     // 正在销毁
    DESTROYED       // 已销毁
}
```

**对照表（务必背下来）：**

| AMS 侧状态 | App 侧回调 | 含义 |
| --- | --- | --- |
| INITIALIZING | （准备中） | 档案建好了，还没发创建命令 |
| RESUMED | `onResume` 之后 | 前台可见可交互 |
| PAUSING / PAUSED | `onPause` | 被半遮挡/让位，但还「活着」 |
| STOPPING / STOPPED | `onStop` | 完全不可见，资源可回收 |
| FINISHING / DESTROYING | `onDestroy` | 正在/已经销毁 |

> 经典面试题：**为什么 onPause 之后不一定马上 onStop？**
> 因为「暂停」是让位，「停止」是退场，两者之间可能隔着很久（比如被半透明的弹窗挡住）。
> 同理，AMS 的 PAUSED 状态可以保持很久。

---

## 2. 生命周期由谁驱动？—— resumeTopActivityInnerLocked

栈里每一次「换人上台」，都走同一个入口：

```text
ActivityStack.resumeTopActivityInnerLocked(prev, options)   // ActivityStack.java 第 2286 行
```

它做的事概括为三步（伪代码）：

```java
// 1. 找到"下一个该上台"的 Activity
ActivityRecord next = topRunningActivityLocked();
// 2. 让"正在台上"的下去：schedulePauseActivity（走 Binder 通知 App）
// 3. 让"下一个"上台：如果它还没创建 → realStartActivityLocked；
//    如果已创建 → app.thread.scheduleResumeActivity（通知 App 执行 onResume）
```

**核心结论**：App 侧看到的所有生命周期回调，都是 AMS 侧状态切换后的「通知」。
AMS 才是最终裁判，App 侧的 `onPause/onStop/onResume` 只是「接到通知后执行的善后动作」。

> 类比：综艺节目录制现场。导演（AMS）喊「嘉宾 B 上台」（scheduleResumeActivity），
> 主持人（App）才执行「欢迎 B 上场」的台词。导演不喊，主持人不能自己换人。

---

## 3. 任务栈：Activity 的"楼层与办公室"

### 3.1 三层结构（8.1）

```
ActivityStack（一栋楼，可以有多栋）
   └── mTaskHistory：List<TaskRecord>（楼层列表）
          └── TaskRecord.mActivities：List<ActivityRecord>（该层办公室列表）
                 └── ActivityRecord（每个 Activity 的档案）
```

| 类 | 是什么 | 类比 |
| --- | --- | --- |
| `ActivityStack` | 管理一组任务栈 | 一栋写字楼（普通栈/分屏栈/全屏栈等） |
| `TaskRecord` | 一个任务（Task），一堆 Activity 的集合 | 楼里的一层 |
| `ActivityRecord` | 一个 Activity | 一层里的一间办公室 |
| `ActivityStackSupervisor` | 管所有栈 | 物业总部的楼层经理 |

> 关键文件：`ActivityStack.java`、`TaskRecord.java`（第 255 行 `mActivities`）、`ActivityRecord.java`。

### 3.2 返回键的真相

用户按返回键（Back）：

```
Activity.finish() → AMS.finishActivity() → ActivityStack.finishActivityLocked()
   → 把栈顶 ActivityRecord 标记 FINISHING → resumeTopActivityInnerLocked()
   → 让栈里下一个 Activity 上台（执行 onResume）
```

所以「返回上一个页面」不是系统自动记忆的，而是**任务栈（TaskRecord.mActivities）里的顺序**决定的。

---

## 4. launchMode：Activity 的四种"人设"

在 Manifest 里给 Activity 声明 `android:launchMode`，或者用 Intent flags 临时指定。
定义在 `core/java/android/content/pm/ActivityInfo.java`（第 52~67 行）：

| launchMode | 值 | 行为 | 类比 |
| --- | --- | --- | --- |
| standard | 0（`LAUNCH_MULTIPLE`） | 每次启动都新建实例 | 每次进餐厅都重新点一份 |
| singleTop | 1（`LAUNCH_SINGLE_TOP`） | 如果它已在栈顶，不新建，回调 `onNewIntent` | 已经在台上的演员不重喊上台，只换个台词 |
| singleTask | 2（`LAUNCH_SINGLE_TASK`） | 如果任务栈里已有该实例，清掉它上面的所有 Activity 把它调到栈顶 | 把楼上的人都请出去，让指定的人到顶层 |
| singleInstance | 3（`LAUNCH_SINGLE_INSTANCE`） | 独占一个任务栈，全局唯一 | 整栋楼只给他一个人用 |

**配套的 Intent flags**（`Intent` 类里）：

| flag | 作用 |
| --- | --- |
| `FLAG_ACTIVITY_NEW_TASK` | 把目标 Activity 放到新任务栈（或已有匹配任务的栈顶） |
| `FLAG_ACTIVITY_CLEAR_TOP` | 清掉目标上面的所有 Activity |
| `FLAG_ACTIVITY_SINGLE_TOP` | 等价于 singleTop |
| `FLAG_ACTIVITY_NO_HISTORY` | 退出后不留记录 |

**还有 `taskAffinity`**（`ActivityRecord.java` 第 226 行 `taskAffinity` 字段）：
它决定 Activity 更愿意加入哪个 Task。默认取包名；跨应用指定相同 affinity 可以把
Activity 拉进同一个任务栈（比如从浏览器跳转的「查看原图」页）。

> 面试高频题：standard 和 singleTop 的区别？singleTask 和 singleInstance 的区别？
> 答法：先说「新建/复用」的决策，再说「是否清栈」，最后补一句「决策在 ActivityStarter/ActivityStack 里实现」。

---

## 5. 配置变更（旋转屏幕）时发生了什么

- App 的 Activity 可以声明 `android:configChanges` 决定「哪些变化我自己处理」；
- 如果没声明（或没包含 `orientation`），系统旋转屏幕会**销毁重建** Activity：
  1. AMS 发 `scheduleRelaunchActivity` / 走 `ActivityThread.handleRelaunchActivity`
  2. 旧 Activity `onSaveInstanceState` → `onDestroy`
  3. 新 Activity `onCreate`（携带着保存的 Bundle）→ `onRestoreInstanceState`
- 如果声明了 `configChanges="orientation|screenSize"`，则只回调 `onConfigurationChanged`，不销毁。

> 在 8.1 里，配置变更的决策在 `ActivityStack`/WMS 侧，最终通知走 `IApplicationThread`。

---

## 6. 一分钟速记卡

```
导演：ActivityStack.resumeTopActivityInnerLocked（谁上台、谁下台）
剧本：ActivityState 状态机（INITIALIZING → RESUMED → PAUSING → PAUSED → STOPPING → STOPPED → FINISHING → DESTROYED）
片场：ActivityStack → TaskRecord(mActivities) → ActivityRecord
人设：standard / singleTop / singleTask / singleInstance + FLAG_* + taskAffinity
```

## 7. 动手实验（本周必做）

1. 打开 `ActivityStack.java` 第 218 行，把 ActivityState 九个值抄一遍并写注释。
2. 找到 `resumeTopActivityInnerLocked`（第 2286 行），找出它调用 `schedulePauseActivity`
   和 `scheduleResumeActivity` 的位置（用 grep 找 `schedulePause` / `scheduleResume`）。
3. 写一个测试 App：A 启动 B，B `finish` 返回。在 logcat 过滤 `ActivityTaskManager|ActivityManager|ActivityThread`，
   观察 A/B 的 onCreate/onPause/onStop/onResume 顺序。
4. 验证任务栈：
   ```bash
   adb shell dumpsys activity activities
   ```
   看 `Hist #`、`TaskRecord`、`isFinishing` 字段，对照本文 3.1 的结构。

## 8. 思考题

1. 为什么说「onPause 后不一定 onStop」？给出一种真实场景。
2. singleTask 启动时，被清掉的 Activity 会回调什么？（onDestroy？onStop？）
3. `FLAG_ACTIVITY_NEW_TASK` + `FLAG_ACTIVITY_CLEAR_TOP` 组合起来等价于哪种 launchMode？
4. 如果两个 App 的 Activity 声明了相同 `taskAffinity`，会发生什么？这有什么风险？

---

*上一篇：[04-Activity启动全流程.md](04-Activity启动全流程.md)　|　下一篇：[06-进程管理-ProcessRecord与oom_adj.md](06-进程管理-ProcessRecord与oom_adj.md)*
