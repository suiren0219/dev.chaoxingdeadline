# 学习通截止提醒

LSPosed Xposed 模块，自动捕获超星学习通作业和考试截止时间。

> 本仓库 fork 自 [qingzhou704](https://github.com/qingzhou704) 的上游项目
> [Xposed-Modules-Repo/dev.chaoxingdeadline](https://github.com/Xposed-Modules-Repo/dev.chaoxingdeadline)，
> 在尊重原作者署名与 Apache-2.0 许可的前提下二次开发，新增功能见下文「本 fork 新增」。

## 下载

**固定直链（永远指向最新版，可直接收藏）**

- debug 签名：[Deadline-debug.apk](https://github.com/suiren0219/dev.chaoxingdeadline/releases/latest/download/Deadline-debug.apk)
- release 签名：[Deadline-release.apk](https://github.com/suiren0219/dev.chaoxingdeadline/releases/latest/download/Deadline-release.apk)

这两个地址不会随版本变化——每次发版都会用同名资产覆盖，所以存书签即可，不必每次找新链接。
手机端更省事的方式：模块内 设置 →「下载最新版」，它会自动按当前安装的签名选对应的包。

[版本改动记录（Release 页）](https://github.com/suiren0219/dev.chaoxingdeadline/releases/latest)｜
[全部版本](https://github.com/suiren0219/dev.chaoxingdeadline/releases)

> 上游仓库的 [Release](https://github.com/Xposed-Modules-Repo/dev.chaoxingdeadline/releases/latest) 只包含原作者的功能，
> 本 fork 的小组件、课堂活动等新增功能请从上方链接下载。
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

1.5.1 及更早的版本没有导出功能，卸载会清掉 `deadlines.db`。有 root 的话先把数据库拖出来：

```bash
adb shell su -c "cp /data/data/dev.chaoxingdeadline/databases/deadlines.db /sdcard/Download/"
adb pull /sdcard/Download/deadlines.db
python tools/db_to_backup.py deadlines.db        # 生成 deadlines-backup.json
adb uninstall dev.chaoxingdeadline
adb install Deadline-release.apk
```

装好新版后：把 `deadlines-backup.json` 传到手机，走「设置 → 备份与导出 → 从备份恢复（JSON）」即可。
脚本支持 `--pending-only`，只保留未完成且未截止的条目。

## 使用

1. 安装本模块并在 LSPosed Manager 中勾选，作用域选择「学习通」
2. 打开学习通，模块自动捕获作业和考试的截止时间
3. 截止前会发送系统通知提醒
4. 桌面小组件：长按桌面空白处添加「课程 / 待办」（4x4，可缩放）或「待办小卡」（2x2）。合并小组件是一块面板两面——标题右侧的 ⇄ 切换「今天的课程」与「Deadline 待办」，⟳ 手动刷新（适配澎湃OS 3.0 / Android 16）
5. 快捷设置磁贴：下拉通知栏编辑磁贴，添加「待办」，一键打开待办列表（桌面图标隐藏时尤其有用）
6. 备份：设置 → 备份与导出，可导出 JSON 备份或 ICS 日历文件（通过系统文件选择器保存，不申请存储权限）
7. 手动添加：主界面右上角「＋」，录入学习通抓不到的待办（如老师口头布置的作业）
8. 课表：首页「课程表」标签查看周课表、或进完整课表页；桌面小组件切到课表面即可看今天课程（上课中的课高亮）。上课前 15 分钟会自动加强当期课程课堂活动的检测（签到、投票不错过），可在设置 → 通知里关闭

## 本 fork 新增

- **更多待办类型**：章节任务点与课堂活动（签到、投票、抢答、讨论、问卷等）和作业、考试一样可以被捕获与提醒（默认关闭，可在「设置 → 通知」中开启，避免通知刷屏）；四种类型在「课程管理」里可逐课程独立开关。
- **手动添加待办**：主界面右上角「＋」录入标题、类型和截止时间；每条待办可点击后打开学习通页面、标记为已完成 / 未完成、或删除。
- **提醒增强**：新增「截止时提醒」（到达截止时刻再提醒一次）；新增免打扰时段（默认 23:00—07:00），时段内的提醒不再发声弹窗但不会丢失，也可选择顺延到时段结束后发出；通知拆分为「截止提醒」「即将截止」「免打扰提醒」三个渠道，可在系统通知设置里分别配置；通知带「稍后 30 分钟」按钮，现在不方便处理就晚点再提醒（只延迟这条通知，不改截止时间），也可直接「标记已完成」。
- **已撤销检测**：连续两次完整刷新都没再出现的待办（老师撤题、改期）会停止提醒并标记「可能已撤销」，主列表里可一键恢复或确认删除；只对当次确实扫到的课程生效，手动添加与章节任务不参与判定。
- **搜索与筛选**：主界面可按标题或课程关键字搜索，按 作业 / 考试 / 章节 / 课堂活动 过滤。
- **统计概览**（设置 → 管理）：未完成 / 已过期 / 已完成数量、未来 7 天截止分布、按课程的未完成数排行。
- **备份与导出**：可导出 JSON 备份（换机 / 重装后恢复，含待办与设置）与 ICS 日历（导入系统日历、小米日历改用日历提醒）。通过系统文件选择器读写，不申请存储权限；模块本身没有 `INTERNET` 权限，备份文件不含任何凭据（桥接令牌刻意不导出）。
- **快捷设置磁贴**：下拉通知栏的「待办」磁贴，一键打开待办列表并显示未完成数量。
- **每日摘要**：每天定时推送一条汇总（默认 08:00，可改时间、可关闭），列出今天和未来 3 天的截止项；触发点落在免打扰时段时自动顺延。设置页「下载最新版」按当前签名直接下载对应安装包，并显示当前版本号与签名类型。
- **桌面小组件**：**一块面板两面**——4x4 的「课程 / 待办」小组件，标题右侧 ⇄ 在「今天的课程」与「Deadline 待办」间切换，两个面各自记住状态（多个实例互不干扰）；另有 2x2「待办小卡」。纯 RemoteViews 实现、不引入额外依赖，深色玻璃观感（半透明底 + 大圆角 + 细描边），深浅色跟随系统；课表面一节课一张卡（时间段 / 课名 / 地点教师 / `3/3` 页码 / 上课中），点卡片翻下一节；待办行显示类型徽标、课程、标题与剩余时间，点击跳转学习通对应页面。对齐《Xiaomi HyperOS 小部件设计规范》的交互约束（翻面靠点击，不做部件内滑动）。
- **界面**：主界面为三标签玻璃风——**今日**（日期、当天课程卡片流、待办列表与筛选）、**课程表**（周网格与获取入口）、**我的**（激活状态、设置、统计、月历、添加待办、下载最新版）。全应用统一毛玻璃观感（原生绘制，不引入第三方模糊库，API 26 起一致）。
- **课表与上课增强**：捕获学习通课表，按「第 1 周周一 + 星期 + 学校真实节次表」推算每节课的真实时间；课表页为周网格（节次行 × 星期列、课程色块跨行、今天高亮、上课中描边、周次切换），**课表按周累积**——逐周取回的课表按「课名+星期+节次」合并、周次取并集，互不覆盖；「**获取整学期**」一次请求即可取回整学期（宿主按接口返回的 maxWeek 逐周串行拉取、连续两周为空才停）；空周会区分「没拉过」与「这周确实没课」；上课前 15 分钟对当期课程的课堂活动列表每 2 分钟刷新一次，签到、投票、抢答不错过（设置 → 通知可关）。
- **命令行拉取整学期**：`python tools/fetch_semester.py "<schedule.html 链接>"`，输出课程 JSON，`--ics` 可再导出日历；合并规则与 App 内一致。
- **1.5 修复**：课程扫描评分不再自动屏蔽课程（此前连续多天空扫描会让该课程待办从列表/小组件/通知中静默消失，现只降频、数据保留）；错过的提醒按最贴近当前的档位补发（此前 20 分钟后截止的新条目会误报「提前 24 小时」）；修复弹层「已提交抑制」可能误杀 5 分钟内相近截止的待办；提醒/弹窗/小组件相关数据库与闹钟操作移入后台线程；通知改用应用自己的单色图标。
- **1.6 修复**：提醒档位为「截止时刻」（提前 0 分钟）时被误判成未提供档位，导致该档位永远不触发；现以 `-1` 作为未提供哨兵。设置项同步改为遍历式，新增开关会自动同步到学习通进程。
- **1.11 修复**：① 重复待办根治——被动捕获写入的是「课程名派生」的 `legacy:` 课程 id，与主动扫描的真实 id 属两套空间，原去重的三条 DELETE 按 `course_id` 精确匹配全部失配；现改为「课程名 + 课程 id」双重判定（两边都是真实 id 时必须相等，任一侧为派生/缺失时退化按课程名比），upsert 命中进行身份继承，数据库升级时自动全表自愈，设置 → 管理可手动「清理重复待办」。② 章节任务点此前只能被动捕获（没在 App 里点开过的课程永远不显示），新增 `gas/clazz` 主动拉取通道。③ 待办列表小组件默认 4x4（可缩回 4x2）。④ 打开学习通连弹多个待办弹窗——拦截路径、主动刷新完成后、关闭后 retry 三条路径都会触发，新增时间节流（两次弹窗至少间隔 2 分钟，通知栏提醒不受影响）。⑤ 部分小组合作作业不显示——小组作业截止字段常叫 `closeDate`/`endDate` 而非 `endTime`，时间兜底扫描放宽到同时接受 `date` 命名的字段。⑥ 课表主动拉取补同源 Referer（`kb.chaoxing.com` 无 Referer 会被拒；该接口是 PC 网页接口，App 原生 Cookie 未必适配，拿不到时打开一次学习通课表页即可走被动解析通道补数据）。
- **本周视图与月历**：主界面「列表 / 本周」切换——本周视图把未来 7 天待办按天分组（今天/明天/周X）；标题栏 ▦ 进入待办月历，整月截止分布一角标计数呈现，点某天展开当天待办。
- **小组件按课程筛选**：待办小卡与合并小组件的待办面可选「全部课程 / 指定课程」，课多时只看某几门。
- **按类型提前量**：作业 / 考试 / 章节 / 课堂活动可分别设置提前提醒小时数，未设置的跟随「提醒时间（默认）」。
- **超级岛 · 最近截止**：常驻显示最近截止的待办（标题 + 课程 + 剩余时间 + 24 小时倒计时进度条），小米 HyperOS 2 会渲染进超级岛；已提交 / 已撤销 / 已过期的条目不占位，点按打开列表。设置 → 通知可关。
- 不扩大 Xposed 作用域，不新增权限。

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
