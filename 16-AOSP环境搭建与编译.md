# 16｜AOSP 环境搭建与编译：把文档里的代码跑起来

> 一句话总结：**这套资料的所有源码引用都基于 AOSP 8.1.0，光看不练等于纸上谈兵——
> 本章教你从零下载源码、编译出系统镜像、刷进模拟器/真机，最后用「加日志」验证 AMS 的真实行为。**

> 前置说明：本系列文档里的源码路径（如 `frameworks/base/core/java/android/app/ActivityManager.java`）
> 都以本机 AOSP 8.1.0 仓库根目录 `/home/zyijian/bin/aosp810` 为基准。本章默认你也要在 Linux 上搭一套同样的环境。

---

## 1. 准备工作：先看看你的机器够不够格

| 项目 | 最低要求 | 推荐配置 | 说明 |
| --- | --- | --- | --- |
| 操作系统 | Ubuntu 16.04/18.04（64 位） | Ubuntu 18.04 LTS | 8.1 时代官方支持；Mac 也能编但坑多 |
| 磁盘 | 150 GB 可用 | 300 GB（SSD 更快） | 源码 ~40G + 编译产物 ~100G + 缓存 |
| 内存 | 16 GB | 32 GB | 8G 会卡到怀疑人生 |
| CPU | 8 核 | 16 核 | `make -j$(nproc)` 全核并行 |
| Java | **OpenJDK 8** | 同左 | 8.1 必须 JDK 8，9+ 编不过（后面有坑） |

> 💡 公司电脑如果装不了 Linux：**用虚拟机（VMware/VirtualBox）装 Ubuntu** 也能跑通全流程，
> 只是编译时间翻倍；或者用云主机（按小时计费）临时租一台 32G 内存的机器专门编译。

---

## 2. 第一步：下载源码（repo + manifest）

AOSP 由上千个 git 仓库组成，Google 用 `repo` 工具统一管理。

```bash
# ① 安装 repo（需要先有 git 和 python2/python3）
mkdir -p ~/bin
curl https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo
chmod a+x ~/bin/repo
export PATH=~/bin:$PATH

# ② 建目录并初始化（-b 指定分支，android-8.1.0_r1 就是本系列对应的版本）
mkdir -p ~/bin/aosp810
cd ~/bin/aosp810
repo init -u https://android.googlesource.com/platform/manifest -b android-8.1.0_r1

# ③ 同步全部代码（-j 并行下载，第一次要几小时，取决于网速）
#    国内网络建议加 --repo-url 和镜像，或用清华/中科大的 AOSP 镜像
repo sync -j8
```

> ⏳ 下载完成后仓库约 **35~40 GB**，请确保磁盘够。断网了可以反复执行 `repo sync` 续传，不会重复下载。

**国内镜像（公司网络连不上 googlesource 时用）：**
```bash
# 清华镜像（在 repo init 时替换 manifest 地址）
repo init -u https://mirrors.tuna.tsinghua.edu.cn/git/AOSP/platform/manifest -b android-8.1.0_r1
# 中科大镜像
repo init -u https://mirrors.ustc.edu.cn/aosp/platform/manifest -b android-8.1.0_r1
```

---

## 3. 第二步：初始化编译环境（envsetup + lunch）

```bash
# ① 加载编译环境（一次性配置，新终端都要先执行）
source build/envsetup.sh

# ② 选择编译目标 —— 这就是文档里说的 eng 版来源
lunch aosp_arm64-eng          # 模拟器用（eng = 有 root、可调试，本系列推荐）
# lunch aosp_angler-userdebug  # 真机 Nexus 6P 用（userdebug = 接近正式版但有 root）
# lunch aosp_blueline-userdebug # Pixel 3 用
```

三种构建类型区别（面试也可能问）：

| 类型 | root | 可调试 | 性能 | 用途 |
| --- | --- | --- | --- | --- |
| `eng` | ✅ | ✅ | 差 | 开发调试，本系列推荐 |
| `userdebug` | ✅ | ✅ | 中 | 接近正式版 + 可调试 |
| `user` | ❌ | ❌ | 好 | 正式发布版 |

> 查看所有可选目标：`lunch` 直接回车会列出完整菜单。
> 模拟器编译建议选 **aosp_arm64-eng**（新版 Mac/无 KVM 的 Windows 虚拟机选 arm64 比 x86 兼容性稳）。

---

## 4. 第三步：编译（make / m，以及它的亲戚们）

```bash
# 全量编译系统镜像（8.1 首次全编约 2~4 小时，看机器）
make -j$(nproc)

# —— 常用变体（都是 make 的快捷方式，8.1 已支持 soong 的 m 系列）——
m                   # 全量编译（等价 make -j$(nproc)）
mma                 # 编译当前模块（在某个模块目录下执行，如 frameworks/base/services/core/java/...）
mm                  # 老式"编译当前目录模块"
banchan <target>    # 编译单个镜像（如 banchan systemimage）
```

**编译产物在哪？**
```bash
# 所有产物都在 out 目录下（默认自动创建，也可 OUT_DIR 指定）
ls out/target/product/generic_arm64/
#   system.img       系统镜像（我们的 AMS 改动都在这）
#   boot.img         内核 + ramdisk
#   vendor.img       厂商分区
#   userdata.img     数据分区
#   ramdisk.img / system/bin/... 等
```

---

## 5. 加日志验证 AMS（本系列最常用的套路）

> 文档 11 第 8 节的实验 4：给 `startActivityAsUser` 加一行日志。
> 具体操作如下，全程不用重编整个系统！

```bash
# ① 修改源码（路径以本机仓库为准）
vim frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java
#    找到 startActivityAsUser（第 4525 行附近），加一行：
#    Slog.i(TAG_AM, "startActivityAsUser: " + intent);

# ② 只重编 services 模块并打包 systemimage（几分钟搞定，不用全量）
cd frameworks/base/services
m services        # 或 mma,只编译 services 相关
m systemimage     # 重新打包 system.img
```

**刷进模拟器（开发最爱）：**
```bash
# 模拟器直接跑新镜像（等价于"刷机"，自动重启）
emulator -wipe-data
```

**刷进真机（真机党）:**
```bash
# ① 手机开启 USB 调试 + 解锁 bootloader
# ② 连上后一键刷入全部镜像（会清空数据，注意备份！）
fastboot flashall
# 或只刷 system 分区（改动都在 system.img 时更快）
fastboot flash system out/target/product/<device>/system.img
fastboot reboot
```

**验证：**
```bash
adb logcat -s ActivityManager | grep "startActivityAsUser"
# 随便打开一个 App，就能看到你加的日志 —— 这就是"源码 → 编译 → 刷入 → 验证"的闭环
```

---

## 6. 常见坑与对策（都是过来人的血泪）

| 坑 | 现象 | 对策 |
| --- | --- | --- |
| **JDK 版本不对** | 编译报 `Unsupported class file major version` / Jack 错误 | 8.1 必须 JDK 8：`sudo apt install openjdk-8-jdk`，`java -version` 确认 |
| **磁盘满了** | `No space left on device` | `du -sh out/` 检查；`make clean` 后重编；或换大分区 |
| **内存不够** | 编译进程被杀 / 卡死 | 关掉 `jack` 并行数：`export JACK_SERVER_VM_ARGUMENTS="-Xmx4g"`；或加 swap |
| **repo sync 卡住** | 网络中断、同步失败 | 换国内镜像（见第 2 节）；`repo sync -j4` 降低并行重试 |
| **lunch 后 make 找不到命令** | `make: command not found` | 忘了 `source build/envsetup.sh`，每个新终端都要执行一次 |
| **模拟器起不来** | 黑屏 / 无响应 | 试试 `emulator -no-window -no-accel` 先看日志；或换 arm64 镜像 |
| **改了代码没生效** | 刷入后行为没变 | 确认编译模块对：改动在 framework 层要 `m systemimage`，别只 `m services` 就刷；`make clean` 一次兜底 |
| **真机刷不进** | `fastboot: command not found` | 装 fastboot：`sudo apt install android-tools-fastboot` |

---

## 7. 不想编译？那也要会「看源码」的姿势

编译是手段，看懂源码才是目的。就算暂时不编译，也建议这样看代码：

| 方式 | 适合场景 | 要点 |
| --- | --- | --- |
| **VS Code + Java 扩展** | 本系列推荐 | README 里说的「Ctrl+Click 跳转方法定义」，轻量、启动快 |
| **Android Studio 打开 AOSP 根目录** | 需要断点调试 | 打开后等 Indexing 完成（几十分钟）；配合第 11 篇的 Attach to Process |
| **AOSPXref / cs.android.com** | 没源码时救急 | 网页版源码浏览器，搜类名/方法名很方便 |

> 在线快速搜代码：`cs.android.com` 搜索 `ActivityManagerService.java`，
> 或直接看本仓库文档里给出的类名 + 行号，在本地源码里定位。

---

## 8. 本章小结

```
下载  : repo init -b android-8.1.0_r1 && repo sync -j8   （国内用镜像）
编译  : source build/envsetup.sh && lunch aosp_arm64-eng && make -j$(nproc)
产物  : out/target/product/generic_arm64/*.img
加日志: 改 AMS 源码 → m services → m systemimage → emulator/刷机 → logcat 验证
```

---

## 9. 动手实验（本周必做）

1. 建好 AOSP 8.1.0 环境（下载 + 全量编译通过），记录你机器的编译耗时。
2. 完成第 5 节的「加日志」闭环：改 `startActivityAsUser` → 编译 → 运行 → 在 logcat 里看到你的日志。
3. 用 `lunch` 菜单浏览一遍所有构建目标，说出 `eng / userdebug / user` 三者的区别。
4. 在源码里找到文档 01 引用的 `ActivityManager.java` 第 4216 行的 `getService()`，用 IDE 跳转看它内部实现。

---

*上一篇：[15-面试题答案与解析-下篇.md](15-面试题答案与解析-下篇.md)　|　回到 [README.md](README.md)*
