# 依赖、许可证与来源

版本以 `gradle/libs.versions.toml` 和模块构建文件为准，使用固定版本，不使用动态 `+` 或 SNAPSHOT。

| 依赖 | 固定基线 | 许可证 / 用途 |
|---|---|---|
| Kotlin / Compose compiler | 2.1.20 | Apache-2.0，共享逻辑和 Android 编译 |
| Android Gradle Plugin | 8.9.2 | Apache-2.0 |
| Gradle wrapper | 8.11.1 | Apache-2.0 |
| Ktor | 3.1.2 | Apache-2.0，HTTP 与认证 |
| kotlinx.coroutines | 1.10.1 | Apache-2.0 |
| kotlinx.serialization | 1.8.0 | Apache-2.0 |
| SQLDelight | 2.0.2 | Apache-2.0，多平台 SQLite |
| xmlutil | 0.90.3 | Apache-2.0，XML 解析 |
| Media3 | 1.6.1 | Apache-2.0，Android 播放与会话 |
| Compose BOM | 2025.04.01 | AndroidX Apache-2.0 |
| Material Icons | 随 Compose BOM | Apache-2.0，统一界面图标 |
| TV Material | 1.0.0 | AndroidX Apache-2.0 |
| Activity / Lifecycle | 1.10.1 / 2.8.7 | AndroidX Apache-2.0 |
| OkHttp | 4.12.0 | Apache-2.0，媒体请求与图片认证 |
| Coil | 2.7.0 | Apache-2.0 |
| Peach 手势与播放配置 | 源码基线 ba7ee7b | AGPL-3.0，见 NOTICE |

TMDB 是可选外部 API，不是随项目分发的开源组件。使用者主动配置 Read Access Token，默认禁用；生产发行需遵循其署名和商业许可要求：[官方 FAQ](https://developer.themoviedb.org/docs/faq)。App 当前包含署名文案；官方 logo 和实网验收属于发行前工作。

本项目未集成 mpv、FFmpeg 或 VLC 到 APK。FFmpeg 只用于生成本机合成夹具，其构建的许可证需按工具发行来源检查，不随应用分发。Jellyfin SDK、Swiftfin 等仅作为调研参考，没有整体复制。

此表是直接依赖清单。生产发行需进一步生成传递依赖 SBOM 和完整 NOTICE，并核对最终制品，不将本表当作正式法律审查。
