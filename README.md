# 学习通截止提醒

LSPosed Xposed 模块，自动捕获超星学习通作业和考试截止时间。

> 本仓库 fork 自 [qingzhou704](https://github.com/qingzhou704) 的上游项目
> [Xposed-Modules-Repo/dev.chaoxingdeadline](https://github.com/Xposed-Modules-Repo/dev.chaoxingdeadline)，
> 在尊重原作者署名与 Apache-2.0 许可的前提下二次开发，新增功能见下文「本 fork 新增」。

## 下载

[最新版本（本 fork 的 Release）](https://github.com/suiren0219/dev.chaoxingdeadline/releases/latest)

> 上游仓库的 [Release](https://github.com/Xposed-Modules-Repo/dev.chaoxingdeadline/releases/latest) 只包含原作者的功能，
> 本 fork 的小组件与深色模式请从上方链接下载。
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
| `be89aa11…` | 本仓库 release 签名 | 与 `Deadline-*-release.apk` 同签名，可直接覆盖；若仍报错请核对包名 |
| `47e8918b…` | 本机 debug 签名 | 改装 `Deadline-*-debug.apk`，可直接覆盖，数据保留 |
| 其它 | 原作者官方包或其它机器打的 debug 包 | 无法覆盖，只能卸载重装（见下方数据迁移） |

原作者官方包的私钥不在本仓库，因此**没有**办法把新包签成官方签名。

### 卸载前抢救待办数据

1.5.1 及更早的版本没有导出功能，卸载会清掉 `deadlines.db`。有 root 的话先把数据库拖出来：

```bash
adb shell su -c "cp /data/data/dev.chaoxingdeadline/databases/deadlines.db /sdcard/Download/"
adb pull /sdcard/Download/deadlines.db
python tools/db_to_backup.py deadlines.db        # 生成 deadlines-backup.json
adb uninstall dev.chaoxingdeadline
adb install Deadline-1.8.1-release.apk
```

装好新版后：把 `deadlines-backup.json` 传到手机，走「设置 → 备份与导出 → 从备份恢复（JSON）」即可。
脚本支持 `--pending-only`，只保留未完成且未截止的条目。

## 使用

1. 安装本模块并在 LSPosed Manager 中勾选，作用域选择「学习通」
2. 打开学习通，模块自动捕获作业和考试的截止时间
3. 截止前会发送系统通知提醒
4. 桌面小组件：在桌面长按空白处添加「待办小卡」或「待办列表」，添加时可设置是否显示课程名与显示范围，部件右上角「⟳」可手动刷新（适配澎湃OS 3.0 / Android 16，深浅色跟随系统）
5. 快捷设置磁贴：下拉通知栏编辑磁贴，添加「待办」，一键打开待办列表（桌面图标隐藏时尤其有用）
6. 备份：设置 → 备份与导出，可导出 JSON 备份或 ICS 日历文件（通过系统文件选择器保存，不申请存储权限）
7. 手动添加：主界面右上角「＋」，录入学习通抓不到的待办（如老师口头布置的作业）

## 本 fork 新增

- **更多待办类型**：章节任务点与作业、考试一样可以被捕获与提醒（章节默认关闭，可在「设置 → 通知」中开启，避免通知刷屏）；三种类型在「课程管理」里可逐课程独立开关。
- **手动添加待办**：主界面右上角「＋」录入标题、类型和截止时间；每条待办可点击后打开学习通页面、标记为已完成 / 未完成、或删除。
- **提醒增强**：新增「截止时提醒」（到达截止时刻再提醒一次）；新增免打扰时段（默认 23:00—07:00），时段内的提醒不再发声弹窗但不会丢失，也可选择顺延到时段结束后发出；通知拆分为「截止提醒」「即将截止」「免打扰提醒」三个渠道，可在系统通知设置里分别配置；通知带「稍后 30 分钟」按钮，现在不方便处理就晚点再提醒（只延迟这条通知，不改截止时间）。
- **搜索与筛选**：主界面可按标题或课程关键字搜索，按 作业 / 考试 / 章节 过滤。
- **统计概览**（设置 → 管理）：未完成 / 已过期 / 已完成数量、未来 7 天截止分布、按课程的未完成数排行。
- **备份与导出**：可导出 JSON 备份（换机 / 重装后恢复）与 ICS 日历（导入系统日历、小米日历改用日历提醒）。通过系统文件选择器读写，不申请存储权限；模块本身没有 `INTERNET` 权限，备份文件不含任何凭据。
- **快捷设置磁贴**：下拉通知栏的「待办」磁贴，一键打开待办列表并显示未完成数量。
- **每日摘要**：每天定时推送一条汇总（默认 08:00，可改时间、可关闭），列出今天和未来 3 天的截止项；触发点落在免打扰时段时自动顺延。设置页「获取更新」可直达最新版下载页，并标注当前签名类型。
- **桌面小组件**：2x2「待办小卡」与 4x2（可拉伸）「待办列表」，纯 RemoteViews 实现，不引入额外依赖；配色采用 [miuix](https://github.com/compose-miuix-ui/miuix) 的 HyperOS 风格色板，深浅色跟随系统；单条待办可跳转学习通对应页面。对齐《Xiaomi HyperOS 小部件设计规范》的尺寸与交互约束（无部件内滑动）。
- **强制深色模式（实验）**：学习通 7.0.4 内置了完整的深色主题（含 `(night)` 资源与隐藏设置页 `DarkSettingActivity`），但入口被隐藏、渲染被自身逻辑强制回浅色。本模块通过四个进程内 Hook 启用它：`AppCompatDelegateImpl.calculateNightMode()` 强制返回夜间 + HyperOS `ContextImpl.setResourcesWhenCreate` 让应用级资源以夜间配置创建 + 向宿主皮肤引擎注入 `night_mode=true` + 对 WebView 页面打开平台 Force Dark。默认开启，可在模块设置或学习通内面板关闭（重启学习通生效）；纯进程内参数改写，不写任何文件、不碰系统分区。
- **1.5 修复**：课程扫描评分不再自动屏蔽课程（此前连续多天空扫描会让该课程待办从列表/小组件/通知中静默消失，现只降频、数据保留）；错过的提醒按最贴近当前的档位补发（此前 20 分钟后截止的新条目会误报「提前 24 小时」）；修复弹层「已提交抑制」可能误杀 5 分钟内相近截止的待办；提醒/弹窗/小组件相关数据库与闹钟操作移入后台线程；通知改用应用自己的单色图标。
- **1.6 修复**：提醒档位为「截止时刻」（提前 0 分钟）时被误判成未提供档位，导致该档位永远不触发；现以 `-1` 作为未提供哨兵。设置项同步改为遍历式，新增开关会自动同步到学习通进程。
- 不扩大 Xposed 作用域，不新增权限。

完整版本记录见 [CHANGELOG](CHANGELOG.md)。

## 致谢与署名

- 原项目作者：[qingzhou704](https://github.com/qingzhou704)，原作者与本 fork 的修改部分均遵循 [Apache License 2.0](LICENSE)。
- 感谢原作者的学习通截止提醒；本仓库所有改动均为在其工作基础上的增量修改，原始提交历史完整保留。

## 构建

```bash
./gradlew :app:assembleRelease   # 打 release 包
./gradlew :app:assembleDebug     # 打 debug 包（调试签名，与 release 不兼容）
./gradlew :app:testDebugUnitTest # 跑 DeadlineParser 的单元测试
```

> Windows 提示：仓库实际位于 `D:\cxdeadline`（纯 ASCII），`D:\学习通\module` 只是指向它的
> 符号链接 —— 这样在中文路径下工作也不会踩到 JDK 展开 `@argfile` 的平台编码问题。
> 若把工程复制进一个含中文的普通目录，`./gradlew test` 会报找不到测试类，改从 ASCII 路径跑即可；
> CI 使用 Linux，不受影响。

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
