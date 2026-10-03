# 学习通截止提醒

LSPosed Xposed 模块，自动捕获超星学习通作业和考试截止时间。

> 本仓库 fork 自 [qingzhou704](https://github.com/qingzhou704) 的上游项目
> [Xposed-Modules-Repo/dev.chaoxingdeadline](https://github.com/Xposed-Modules-Repo/dev.chaoxingdeadline)，
> 在尊重原作者署名与 Apache-2.0 许可的前提下二次开发，新增功能见下文「本 fork 新增」。

## 下载

[最新版本](https://github.com/Xposed-Modules-Repo/dev.chaoxingdeadline/releases/latest)

## 使用

1. 安装本模块并在 LSPosed Manager 中勾选，作用域选择「学习通」
2. 打开学习通，模块自动捕获作业和考试的截止时间
3. 截止前会发送系统通知提醒
4. 桌面小组件：在桌面长按空白处添加「待办小卡」或「待办列表」，无需打开学习通即可查看未完成待办（适配澎湃OS 3.0 / Android 16，深浅色跟随系统）

## 本 fork 新增

- **桌面小组件**：2x2「待办小卡」与 4x2（可拉伸）「待办列表」，纯 RemoteViews 实现，不引入额外依赖；配色采用 [miuix](https://github.com/compose-miuix-ui/miuix) 的 HyperOS 风格色板，深浅色跟随系统；单条待办可跳转学习通对应页面。对齐《Xiaomi HyperOS 小部件设计规范》的尺寸与交互约束（无部件内滑动）。
- **强制深色模式（实验）**：学习通 7.0.4 内置了完整的深色主题（含 `(night)` 资源与隐藏设置页 `DarkSettingActivity`），但入口被隐藏、渲染被自身逻辑强制回浅色。本模块通过两个进程内 Hook 启用它：`AppCompatDelegateImpl.calculateNightMode()` 强制返回夜间 + HyperOS `ContextImpl.setResourcesWhenCreate` 让应用级资源以夜间配置创建。默认开启，可在模块设置或学习通内面板关闭（重启学习通生效）；纯进程内参数改写，不写任何文件、不碰系统分区。
- 不扩大 Xposed 作用域，不新增权限。

## 致谢与署名

- 原项目作者：[qingzhou704](https://github.com/qingzhou704)，原作者与本 fork 的修改部分均遵循 [Apache License 2.0](LICENSE)。
- 感谢原作者的学习通截止提醒；本仓库所有改动均为在其工作基础上的增量修改，原始提交历史完整保留。

## 构建

```bash
./gradlew :app:assembleRelease
```

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
