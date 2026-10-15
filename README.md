# 学习通截止提醒

LSPosed Xposed 模块，自动捕获超星学习通的**作业**与**章节任务点**截止时间。

> ## 维护范围
>
> 本 fork 只维护一条线：**把作业和章节任务点收全、把已经做完的隐藏掉**。
> 当前版本 1.19.0。此前加过又删掉的东西（课表、月历、统计、备份、悬浮窗、摘要、磁贴等）
> 不再回来，见 [CHANGELOG](CHANGELOG.md) 的 1.19.0 条目。
>
> 下游分支、二次开发与再分发请遵循 [Apache License 2.0](LICENSE)（保留署名与许可证文本即可）。
>
> - 上游原始项目：[Xposed-Modules-Repo/dev.chaoxingdeadline](https://github.com/Xposed-Modules-Repo/dev.chaoxingdeadline)
> - 已知限制：学习通若改了接口或页面结构，本模块的捕获能力可能失效。

> 本仓库 fork 自 [qingzhou704](https://github.com/qingzhou704) 的上游项目
> [Xposed-Modules-Repo/dev.chaoxingdeadline](https://github.com/Xposed-Modules-Repo/dev.chaoxingdeadline)，
> 在尊重原作者署名与 Apache-2.0 许可的前提下二次开发，改动见下文「本 fork 改动」。

## 下载

**固定直链（永远指向最新版，可直接收藏）**

- debug 签名：[Deadline-debug.apk](https://github.com/suiren0219/dev.chaoxingdeadline/releases/latest/download/Deadline-debug.apk)
- release 签名：[Deadline-release.apk](https://github.com/suiren0219/dev.chaoxingdeadline/releases/latest/download/Deadline-release.apk)

这两个地址不会随版本变化——每次发版都会用同名资产覆盖，所以存书签即可，不必每次找新链接。
手机端更省事的方式：模块内 设置 →「下载最新版」，它会自动按当前安装的签名选对应的包。

[版本改动记录（Release 页）](https://github.com/suiren0219/dev.chaoxingdeadline/releases/latest)｜
[全部版本](https://github.com/suiren0219/dev.chaoxingdeadline/releases)

> 上游仓库的 [Release](https://github.com/Xposed-Modules-Repo/dev.chaoxingdeadline/releases/latest) 只包含原作者的功能，
> 本 fork 的小组件与章节任务捕获请从上方链接下载。
>
> **签名说明**：`release` 包使用本仓库自己的签名，`debug` 包使用开发机调试签名，两者互不兼容；
> 与原作者官方包的签名也不互通。覆盖安装请选择与已装版本相同签名的包，否则需要先卸载（会清除模块数据）。

### 装不上：`INSTALL_FAILED_UPDATE_INCOMPATIBLE`

这是**已装版本与新包签名不一致**，不是包损坏。先确认手机上那一份用的是哪个签名：

```bash
adb shell pm path dev.chaoxingdeadline          # 返回形如 package:/data/app/.../base.apk
adb pull /data/app/.../base.apk installed.apk   # 用上一步的路径，去掉开头的 package:
java -jar <android-sdk>/build-tools/36.0.0/lib/apksigner.jar verify --print-certs installed.apk
```

比对 `SHA-256 digest`：

| SHA-256 前缀 | 含义 | 怎么办 |
| --- | --- | --- |
| `90ae73d9…` | GitHub Actions 构建（CI 发布的 `Deadline-debug.apk` 与 `Deadline-release.apk` 都是这个） | 与从 Release 下载的包同签名，可直接覆盖 |
| `be89aa11…` | 本仓库 release 签名（本机 `./gradlew assembleRelease` 且带 keystore.properties 时） | 与本地构建的 release 包同签名，可直接覆盖 |
| `47e8918b…` | 本机 debug 签名（本机 `./gradlew assembleDebug`） | 与本地构建的 debug 包同签名，可直接覆盖 |
| 其它 | 原作者官方包或其它机器打的 debug 包 | 无法覆盖，只能卸载重装（见下方数据迁移） |

> CI 发布包统一为 `90ae73d9…`：仓库的正式签名 Secret 尚未配置，两个变体都回退用 runner 的调试密钥签名。
> 所以从 GitHub 下载的包只会属于 `90ae73d9…` 这一行；另外两种签名只出现在自己电脑上构建的产物里。

原作者官方包的私钥不在本仓库，因此**没有**办法把新包签成官方签名。

### 卸载前抢救待办数据

卸载会清掉 `deadlines.db`。1.19.0 起**模块内已无恢复入口**（备份功能已移除），
所以有 root 的话先把数据库拖出来留存；万一需要，再用 `adb` 把数据库放回原目录并修好权限：

```bash
adb shell su -c "cp /data/data/dev.chaoxingdeadline/databases/deadlines.db /sdcard/Download/"
adb pull /data/data/dev.chaoxingdeadline/databases/deadlines.db
```

放回时（重装后）：

```bash
adb push deadlines.db /sdcard/Download/deadlines.db
adb shell su -c "cp /sdcard/Download/deadlines.db /data/data/dev.chaoxingdeadline/databases/"
adb shell su -c "chmod 660 /data/data/dev.chaoxingdeadline/databases/deadlines.db"
# 归属要跟着应用的 uid 走，装好后用 run-as 查一次再设：
adb shell "run-as dev.chaoxingdeadline id -u"          # 记下这个数字，例如 10390
adb shell su -c "chown 10390:10390 /data/data/dev.chaoxingdeadline/databases/deadlines.db"
adb shell am force-stop dev.chaoxingdeadline
```

> 表结构在 1.19.0 没有变化，所以 1.18.x 拖出来的库可以直接放回去用。
> `tools/db_to_backup.py` 仍在仓库里，可把数据库转成 JSON 便于人工查看。

## 使用

1. 安装本模块并在 LSPosed Manager 中勾选，作用域选择「学习通」
2. 打开学习通（随便逛一下即可），模块自动捕获作业与章节任务点
3. 截止前会发送系统通知提醒
4. 桌面小组件：长按桌面空白处添加「待办」（4x2，可缩放）或「待办小卡」（2x2）；⟳ 手动刷新
5. 主界面「待办」标签：搜索、按类型筛选、一键标记完成，点条目可跳学习通对应页面
6. 「我的」标签：设置、课程管理（逐课程独立开关）、关于

> **什么会显示**：未完成的**作业**与**章节任务点**，含早就过截止时间的——过期的正是最该看见的。
> **什么会隐藏**：只有学校字段标记为已提交、或你手动点过「完成」的条目。
> 详情见下文「收全与隐藏的规则」。

## 本 fork 改动

### 收全与隐藏的规则

**只收作业与章节任务点**（1.19.0 起）。考试与课堂活动不再收集，连扫描它们的请求也一起去掉了，
每门课的请求数因此减半。

**隐藏规则只有一条：只有真正做完的才隐藏。**

| 情况 | 是否显示 |
| --- | --- |
| 未到截止、未提交 | 显示 |
| **已过截止、未提交** | **显示**（过期的正是最该看见的） |
| 连续两轮扫描没扫到 | **显示**，仅标「可能已撤销」 |
| 学校字段标记为已提交 | 隐藏 |
| 手动点了「完成」 | 隐藏 |

第二条和第三条是 1.19.0 修掉的重点。在此之前规则正好相反——过了截止就隐藏、扫不到就判定完成，
结果列表越用越空。

根因更朴素：解析层原本有一句 `if (dueAt <= now) return null;`（截止时间早于现在就直接丢弃），
**任何过了截止时间的作业在解析阶段就被扔掉，从来没进过数据库**。上游一直如此。1.19.0 去掉了它。

### 保留的功能

- **桌面小组件**：「待办」小组件与 2x2「待办小卡」。纯 RemoteViews 实现、不引入额外依赖，
  深色玻璃观感（半透明底 + 大圆角 + 细描边），深浅色跟随系统。
  - **默认按 4x2 声明**，**行数按小组件自身高度自动算**（1–8 行），4x2 看 2–3 条，
    拉高自动多显示，不需要点着翻页。
  - 待办行显示类型徽标、课程、标题与剩余时间，点击跳转学习通对应页面。
  - 实现说明：行容器是 `LinearLayout` 而非 `ViewFlipper`/`StackView`——后两者一次只显示一个子视图
    或无 `RemoteViewsService` 就不能 `addView`，装不下「一次看几条」。
- **提醒增强**：「截止时提醒」（到达截止时刻再提醒一次）；免打扰时段（默认 23:00—07:00），
  时段内的提醒不再发声弹窗但不会丢失，也可选择顺延到时段结束后发出；通知拆分为「截止提醒」
  「即将截止」「免打扰提醒」三个渠道，可在系统通知设置里分别配置；通知带「稍后 30 分钟」按钮
  （只延迟这条通知，不改截止时间），也可直接「标记已完成」。
- **章节任务点**：除被动捕获外另有 `gas/clazz` 主动拉取通道，没在 App 里点开过的课程也能扫到。
- **搜索与筛选**：主界面可按标题或课程关键字搜索，按 作业 / 章节 过滤。
- **课程管理**：逐课程、逐类型独立开关，不想要的课直接关掉。
- **界面**：两标签玻璃风——**待办**（作业与章节清单）、**我的**（设置、课程管理、关于）。
  全应用统一毛玻璃观感（原生绘制，不引入第三方模糊库，API 26 起一致）。
- 不扩大 Xposed 作用域，不新增权限。

### 历史修复（仍生效）

- **1.5**：课程扫描评分不再自动屏蔽课程（此前连续多天空扫描会让该课程待办静默消失，现只降频、
  数据保留）；错过的提醒按最贴近当前的档位补发。
- **1.6**：提醒档位为「截止时刻」（提前 0 分钟）时被误判成未提供档位，导致该档位永远不触发；
  现以 `-1` 作为未提供哨兵。
- **1.11**：① 重复待办根治——被动捕获写入的是「课程名派生」的 `legacy:` 课程 id，与主动扫描的
  真实 id 属两套空间，原去重的 DELETE 按 `course_id` 精确匹配全部失配；现改为「课程名 + 课程 id」
  双重判定，upsert 命中进行身份继承，数据库升级时自动全表自愈。② 章节任务点新增主动拉取通道。
  ③ 部分小组合作作业不显示——小组作业截止字段常叫 `closeDate`/`endDate` 而非 `endTime`，
  时间兜底扫描放宽到同时接受 `date` 命名的字段。
- **1.18.4**：修掉四个让课表取不到的问题（POST 请求头顺序错误、「获取整学期」没进扫描分支、
  刷新广播没有接收者、反爬冷却自锁）。**课表功能已在 1.19.0 整体移除**，这些修复随之不再需要。
- 不扩大 Xposed 作用域，不新增权限。

### 已移除的功能（1.19.0）

课表整套（逐周抓取、周网格、JSON/ICS 导入、上课提醒、小组件的课表面）、月历与调休、统计概览、
备份导出、悬浮窗面板、每日摘要、快捷设置磁贴、手动添加待办、课表字段映射、上课期间加强检测、
课程任务总览页、课程详情页。共删除约 11,400 行。

**数据库表结构未变**，升级后原有数据仍在，只是按新规则显示。

完整版本记录见 [CHANGELOG](CHANGELOG.md)。

## 致谢与署名

- 原项目作者：[qingzhou704](https://github.com/qingzhou704)，原作者与本 fork 的修改部分均遵循 [Apache License 2.0](LICENSE)。
- 感谢原作者的学习通截止提醒；本仓库所有改动均为在其工作基础上的增量修改，原始提交历史完整保留。

## 构建

```bash
./gradlew :app:assembleRelease   # 打 release 包
./gradlew :app:assembleDebug     # 打 debug 包（调试签名，与 release 不兼容）
./gradlew :app:testDebugUnitTest # 跑单元测试（DeadlineParser / DeadlineStore / ReminderText）
```

> Windows 提示：工程需位于纯 ASCII 路径下。中文路径会踩到 JDK 展开 `@argfile` 的平台编码问题，
> 表现为 `./gradlew test` 找不到测试类；可把工程放到 ASCII 目录，或用目录联接 / 符号链接
> 把工程映射到一个 ASCII 路径再执行 Gradle。CI 使用 Linux，不受影响。

推送 `v*` 标签会自动触发 Release 工作流，构建并上传 APK（签名密钥通过仓库 Secrets
`SIGNING_KEYSTORE_BASE64` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_ALIAS` /
`SIGNING_KEY_PASSWORD` 注入；未配置时回退到调试签名）。

## 隐私与数据脱敏

本仓库的代码、文档、测试数据以及**全部 Git 历史**均已做脱敏处理：

- **不含任何真实个人数据**：示例中的课程名、教师姓名、教室、学号、curriculumUuid 等
  一概为占位内容（如「示例课程」「高等数学」「王老师」）。
- **不含设备标识**：请求头里的 User-Agent 只声明 Android 版本与学习通客户端版本，
  不含具体机型；文档中不出现真实设备型号、局域网地址或本地路径。
- **数据不出本机**：模块本身**没有 `INTERNET` 权限**，所有待办与设置都只存放在
  本机应用私有目录。1.19.0 已移除备份导出功能，数据不再有对外导出的路径。
- 模块工作时会访问学习通接口（这部分运行在学习通进程内），这是捕获数据的必要环节，
  请求与登录态均由学习通自身的会话承载，模块不额外收集或上传任何信息。

如果 fork 后二次开发，建议同样保持上述约束，避免把个人课表或设备信息带进仓库。

## 开源许可

本项目基于 [Apache License 2.0](LICENSE) 开源，SPDX 标识为 `Apache-2.0`。

分发、修改或二次开发本项目时，请保留版权声明、许可证文本以及必要的第三方组件声明。

## 第三方组件

本项目使用或引用了以下第三方组件，相关组件遵循各自的开源许可证：

- Android Gradle Plugin / Gradle Wrapper
- Android SDK APIs
- libxposed API 102
- `app/libs/service-102.0.0-patched.aar`
- `app/libs/interface-102.0.0-patched.aar`

其中 `service-102.0.0-patched.aar` 与 `interface-102.0.0-patched.aar` 为本项目构建所需的本地 patched AAR。若重新分发或继续修改，请同时保留其上游项目的许可证、版权与 NOTICE 声明。详见 [NOTICE](NOTICE)。

## 免责声明

本项目仅用于个人待办提醒与学习辅助，不隶属于超星、学习通或相关学校平台。使用本项目时请遵守所在学校、课程平台和相关服务的使用规则。
