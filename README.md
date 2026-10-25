# Umuo Player

独立、多来源、多端播放器的架构 Spike。Android 手机/平板和 Android TV 使用同一套 Kotlin Multiplatform 业务核心，Android 使用 Media3；Apple 平台提供 SwiftUI 编译验证壳。

**这是可运行的技术验证版本，不是已完成实服和硬件兼容验收的正式产品。** 功能状态与限制见 [验证记录](docs/VERIFICATION.md)。

## 下载与发布

前往 [Releases](https://github.com/umuo/umuo-player/releases) 下载：`mobile-release.apk` 用于手机/平板，`tv-release.apk` 用于电视（也可在 Pad 测试）。`spike` 标签属于预发布版本，限制见验证记录。

推送与 `gradle.properties` 版本一致的 `v*` 标签后，GitHub Actions 自动测试、构建、验签并上传两个 APK 和 SHA-256 校验清单。操作步骤与签名备份说明见 [发布指南](docs/RELEASING.md)，执行状态见 [Actions](https://github.com/umuo/umuo-player/actions)。

## 构建与运行

需要 JDK 17/21、Android SDK Platform 36、Build Tools 36；配置 `ANDROID_HOME`，或在忽略的 `local.properties` 填写 `sdk.dir`。所有依赖版本集中在 `gradle/libs.versions.toml`。

```sh
./gradlew -PenableAppleTargets=false :shared:jvmTest :app-mobile:assembleDebug :app-tv:assembleDebug
./gradlew -PenableAppleTargets=false :app-mobile:lintDebug :app-tv:lintDebug
adb -s <手机序列号> install -r app-mobile/build/outputs/apk/debug/app-mobile-debug.apk
adb -s <TV序列号> install -r app-tv/build/outputs/apk/debug/app-tv-debug.apk
```

手机和平板日常使用安装 `app.umuo.player`，TV 安装 `app.umuo.player.tv`，可并存。TV 版也提供普通安卓桌面图标，方便在 Pad 上测试；非电视设备右下角有可收起的「测试遥控器」，使用方向、确定、返回操作原 TV 界面。电视设备不显示测试面板；最低 Android 8.0/API 26。开发构建使用调试签名；GitHub Release 提供固定发行签名的手机/平板和 TV APK。首次从 debug 版迁移需先卸载旧版（会清除本地来源与记录），后续 Release 可覆盖升级。

启动后添加 Emby、Jellyfin 或 WebDAV。Emby/Jellyfin 填服务根地址，支持反向代理子路径；WebDAV 填需要访问的根目录。不同服务器、不同账号分别添加来源。密码和 token 只保存到本机 Keystore 加密存储，关闭系统备份；HTTP 连接不加密，界面有提示，HTTPS 保持系统证书校验。

- Emby/Jellyfin：默认使用服务器影视库，提供媒体库、全部电影、全部剧集入口；连接表单自动填入 8096/8920、根路径，支持反向代理；服务器搜索、收藏、已看状态、续看/下一集列表、最近添加与合集；进入详情、播放/续播；遇到设备格式限制可选择兼容播放，依赖服务器转码配置。
- WebDAV：文件浏览与影视库两种模式；影视库只展示 NFO 或明确元数据匹配的影视，支持电影/剧集、类型、标签与合集筛选；点击扫描更新索引；同目录 NFO、海报、字幕优先，本地记录续播位置。扫描可停止和恢复，失败目录保留重试队列。
- 手机：单击显隐控制栏、中央双击播放暂停、左右双击快进快退、长按临时 2 倍、横拖定位、左右竖拖亮度/音量；全屏、音轨、字幕和系统画中画。
- 手机首页采用石墨影院主题：来源切换和新增位于右上角；WebDAV 的「文件浏览 / 影视库」固定在媒体页面顶部，滚动或进入详情后仍可直接切换。影视库内提供精选/电影/剧集，类型/标签/合集按筛选图标展开；扫描位于「媒体库选项」。Emby/Jellyfin 使用服务器影视库，收藏、合集、最近添加等从该菜单进入。
- TV：方向键移动焦点，确认进入/操作；输入项按确定才编辑，输入法的完成结束编辑，返回先收起输入法/结束编辑，再返回上一页；返回恢复卡片焦点；播放画面聚焦时左右定位、确认播放暂停。
- 元数据设置默认关闭联网。主动启用 TMDB 并填写自己的 Read Access Token 后，扫描可自动匹配标题/年份均明确的电影，其他结果需手动选择；剧集可手动匹配系列。不会上传服务地址、路径或账号。

当前不提供自有账号云、跨设备同步、下载、投屏和动态插件市场。WebDAV 完全只读；不修改媒体、NFO 或目录。实际容器、字幕、HDR 和音频兼容性需按设备验证。

## 合成服务验证

夹具只绑定回环地址，目录必须包含脚本生成的标记。服务模拟三种协议所需子集，不能代替真实 Emby/Jellyfin 验收。

```sh
python3 tools/fixture_server.py --root "$TMPDIR/umuo-fixture" --generate --extended --ffmpeg "${FFMPEG_EXECUTABLE:-ffmpeg}"
# 另一个终端，使用专用测试模拟器；脚本会添加合成来源。
python3 tools/qa_android.py --serial <序列号> --kind WEBDAV
python3 tools/qa_android.py --serial <序列号> --kind JELLYFIN
python3 tools/qa_android.py --serial <序列号> --kind EMBY
python3 tools/qa_android.py --serial <TV序列号> --kind WEBDAV --tv
```

测试账号与密码均为 `fixture`，WebDAV 根目录为 `http://127.0.0.1:18921/dav/`，媒体服务器地址为 `http://127.0.0.1:18921/`。通过 `adb reverse` 连接本机服务。`--auth digest` 可单独验证 Digest。已有合成来源后，可用 `python3 tools/qa_media_matrix.py --serial <序列号>` 检查 HEVC、多音轨、高码率与兼容 HLS；此脚本需要名为 `FixtureDAV`、`FixtureEMBY` 的合成来源。截图与设备日志默认保存在忽略的 `verification/local/`，公开前检查内容。

## Apple 编译验证

Apple Silicon Mac、完整 Xcode 和对应 SDK：

```sh
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 :shared:linkDebugFrameworkTvosSimulatorArm64
xcodebuild -project apple-probe/UmuoProbe.xcodeproj -target UmuoProbe-iOS -sdk iphonesimulator CODE_SIGNING_ALLOWED=NO build
xcodebuild -project apple-probe/UmuoProbe.xcodeproj -target UmuoProbe-tvOS -sdk appletvsimulator CODE_SIGNING_ALLOWED=NO build
```

验证壳显示共享模型解析后的片名；尚未接入 Apple 播放引擎。设备 framework 可通过 `:shared:linkDebugFrameworkIosArm64` / `:shared:linkDebugFrameworkTvosArm64` 构建，签名与设备运行另行验收。Xcode 工程本身不隐式运行 Gradle，修改共享代码后先重建 framework。Android 开发可关闭 Apple targets，避免下载 Native 工具链。

## 项目导航

- [Emby/Jellyfin 完整观看客户端范围与进度](docs/MEDIA_SERVER_CLIENT.md)
- [扫描速度、限流与流量边界](docs/SCANNING.md)
- [影院风设计与交互](docs/DESIGN.md)
- [架构决策与扩展方式](docs/ARCHITECTURE.md)
- [Peach 迁移对照](docs/MIGRATION.md)
- [验证矩阵与真实限制](docs/VERIFICATION.md)
- [分阶段实施清单](docs/ROADMAP.md)
- [依赖、许可与来源](docs/DEPENDENCIES.md)

沿用 AGPL-3.0，复用来源见 [NOTICE](NOTICE)。新工程不包含 Peach 品牌资源、账号或服务器配置。
