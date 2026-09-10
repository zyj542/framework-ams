# 09｜ContentProvider：跨进程的数据访问

> 一句话总结：**ContentProvider 是 Android 的「公共数据库窗口」：某个 App 把数据开一扇窗
> （ContentProvider），其它 App 通过 AMS 拿到这扇窗的钥匙（IContentProvider 的 Binder），
> 就能跨进程增删改查。AMS 是这扇窗的「物业登记处」。**

---

## 1. 它解决了什么问题

App A 的数据默认只能自己访问（进程隔离）。想让 App B 读 A 的数据，有两条路：
- 把数据暴露成 ContentProvider（安全、标准、可加权限）← Android 推荐
- 直接把数据库文件共享出去（危险、不标准）

所以 ContentProvider 的本质是：**进程 A 提供一套统一的增删改查接口（ContentResolver 风格），
进程 B 通过 Binder 远程调用这套接口**。

---

## 2. 管理 Provider 的"登记处"

| 类 | 记录什么 |
| --- | --- |
| `ProviderMap`（AMS 第 1067 行 `mProviderMap`） | 按「authority（如 com.example.provider）」索引所有 Provider 的档案 |
| `ContentProviderRecord` | 一个 Provider 的档案：`info`（第 37 行）、`appInfo`（第 39 行）、`provider`（第 42 行，真实 IContentProvider Binder）、`canRunHere`（第 86 行，能否直接在本进程跑） |
| `ContentProviderConnection` | 一次「谁在使用哪个 Provider」的连接记录 |

> 类比：ContentProvider 是商场里开的一家「书店」（数据窗口），authority 是书店门牌号，
> `ProviderMap` 是商场物业的门牌登记表，谁要用（ContentProviderConnection）物业都有记录。

---

## 3. 访问一个 Provider 的完整流程

以「App B 通过 `contentResolver.query(...)` 读 App A 的数据」为例：

```
App B                                  system_server                              App A（Provider 宿主）
   │                                         │                                        │
   │ ContentResolver.query(uri)              │                                        │
   │   → ContentProvider.acquireProvider()   │                                        │
   │   → AMS.getContentProvider(...)（Binder）│                                        │
   │────────────────────────────────────────▶│                                        │
   │                                         │ 1. getContentProviderImpl(11434)       │
   │                                         │     ├─ 按 authority 查 mProviderMap     │
   │                                         │     ├─ 没登记？                         │
   │                                         │     │   ├─ 建 ContentProviderRecord      │
   │                                         │     │   └─ 目标进程没启动 → 拉进程        │
   │                                         │───────────────────────────────────────▶│
   │                                         │ 2. scheduleInstallProvider(1374)        │
   │                                         │                                        │ 3. ActivityThread.handleInstallProvider(3055)
   │                                         │                                        │    → ContentProvider.onCreate()
   │                                         │◀───────────────────────────────────────│ 4. 把真实 IContentProvider 的 Binder 交回
   │◀────────────────────────────────────────│ 5. 拿到 ContentProviderHolder            │
   │   内部保存：ContentProviderClient        │                                        │
   │   → IContentProvider.query(...)（Binder 直达 App A）                            │
   │───────────────────────────────────────────────────────────────────────────────▶│
   │                                        （后续查询不再经过 AMS，直接调 App A 的 Binder）
```

关键点：
1. **第一次访问最重**：要查登记表、可能拉进程、通知宿主创建 Provider；
2. **之后访问轻**：App B 缓存了 `IContentProvider` 的 Binder，直接跨进程调 App A；
3. **Provider 只创建一次**：`onCreate` 在宿主进程里只执行一次（除非进程被杀后重建）。

---

## 4. 两个容易忽略的细节

### 4.1 权限
- Provider 可以声明 `android:readPermission` / `writePermission`；
- AMS 在 `getContentProviderImpl` 里做权限检查（`checkContentProviderPermission`，第 11482 行附近）；
- 没权限的进程访问会被拒绝并抛异常。

### 4.2 进程与 Provider 的相互影响
- Provider 的宿主进程被杀了 → AMS 收到死亡回调，**下次有人访问时会重新拉进程 + 重建 Provider**；
- Provider 的「重要性」会传给宿主进程（被频繁使用的 Provider 宿主不容易被杀）—— 这也是 adj 传播的一种。

---

## 5. 一分钟速记卡

```
登记处：ProviderMap（按 authority 索引）
档案  ：ContentProviderRecord（info/appInfo/真实 IContentProvider Binder）
首次访问：getContentProviderImpl(11434) → 拉进程 → scheduleInstallProvider → onCreate → 回传 Binder
后续访问：App 缓存 IContentProvider Binder，直接跨进程调用
权限  ：readPermission / writePermission，AMS 统一检查
```

## 6. 动手实验（本周必做）

1. 打开 `ActivityManagerService.java` 第 11434 行 `getContentProviderImpl`，看它「查不到 → 拉进程」的分支。
2. 找 `scheduleInstallProvider`（ActivityThread 第 1374 行）和 `handleInstallProvider`（第 3055 行），
   看 Provider 在宿主进程里是怎么被创建出来的。
3. 写一个 Demo：
   - App A 实现一个 ContentProvider（authority 如 `com.example.a.provider`）；
   - App B 通过 `contentResolver.query` 访问；
   - logcat 过滤 `ActivityManager|ActivityThread`，观察 `scheduleInstallProvider` / `onCreate` 日志。
4. 命令查看已安装的 Provider：
   ```bash
   adb shell dumpsys activity providers
   ```

## 7. 思考题

1. 为什么第二次访问 Provider 时不用再经过 AMS？它缓存了什么？
2. 一个 Provider 的宿主进程被杀后，正在使用它的客户端会怎样？（`DeadObjectException`？）
3. `ContentProviderConnection` 存在的意义是什么？（提示：AMS 需要知道谁在用，好决定杀不杀宿主进程）
4. 跨进程 Provider 调用和本地（同进程）Provider 调用在性能上有什么区别？

---

*上一篇：[08-Broadcast广播.md](08-Broadcast广播.md)　|　下一篇：[10-Binder客户端-ActivityThread与ActivityManager.md](10-Binder客户端-ActivityThread与ActivityManager.md)*
