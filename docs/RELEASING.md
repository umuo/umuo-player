# APK 发布

## 日常发版

1. 在 `gradle.properties` 增加 `umuoVersionCode`（必须大于上一版），修改 `umuoVersionName`，例如 `0.1.10-spike`，同时更新 `CHANGELOG.md`。
2. 提交并推送到 `main`，确认 Verify Spike 的 Android 检查通过。
3. 创建并推送与版本完全一致的标签：

```sh
git tag -a v0.1.10-spike -m "Umuo Player 0.1.10-spike"
git push origin v0.1.10-spike
```

`Release APK` 将测试共享核心/播放模块、构建两个签名 Release APK、运行 lint、检查签名与包信息，随后创建 GitHub Release，附 APK 和 `SHA256SUMS`。含 `-` 的版本标为预发布。标签必须位于 `main` 历史中，禁止覆盖已存在的 Release；失败后可修复网络等外部问题再重跑。同一版本源码有修改时增加版本号并使用新标签，不移动已发布标签。

手动运行 Release APK 只构建并上传 Actions artifact，不创建 Release，可在打标签前预检。Apple 边界检查单独手动运行，避免把 Android 发版依赖于尚未交付的 Apple 产品。

## 固定签名

`docs/release-certificate.sha256` 保存公开证书指纹，流水线会校验两个 APK 的签名、包名、版本号与非调试状态，防止配置错密钥。

两个应用共用本项目专用的发行密钥，GitHub 仓库需配置以下 Actions Secrets：

| 名称 | 内容 |
|---|---|
| `UMUO_KEYSTORE_BASE64` | JKS 文件 Base64 内容（不是加密形式，只能存放在 Secret） |
| `UMUO_KEYSTORE_PASSWORD` | 密钥库密码 |
| `UMUO_KEY_ALIAS` | alias |
| `UMUO_KEY_PASSWORD` | 密钥密码 |

构建时密钥仅解码到 runner 临时目录，结束后删除；不进入缓存、artifact、仓库或日志。PR/debug 检查不接触这些 Secrets。发布 job 使用 GitHub 自动提供的 `GITHUB_TOKEN`，只授予必要的 `contents: write`，无需配置个人 PAT。

首次配置的本地备份在忽略的 `.signing/` 内。将整个目录离线备份到安全位置，限制读取权限，不要提交或发到聊天。GitHub Secret 无法导出原值，丢失签名密钥会影响后续升级。换电脑时应恢复同一密钥，不能重新生成替换。

本地发行构建在 shell 设置四个环境变量 `UMUO_KEYSTORE_PATH`（绝对路径）、`UMUO_KEYSTORE_PASSWORD`、`UMUO_KEY_ALIAS`、`UMUO_KEY_PASSWORD` 后运行：

```sh
./gradlew -PenableAppleTargets=false :app-mobile:assembleRelease :app-tv:assembleRelease
```

缺少签名配置时发行构建会失败，普通 debug 构建不受影响。

## 安装和校验

- 手机/平板安装 `UmuoPlayer-<版本>-mobile-release.apk`；TV 安装 `UmuoPlayer-<版本>-tv-release.apk`。
- 从历史 debug 包切换到发行签名需先卸载旧版，会清除来源和本地记录；请先记录自己的连接配置。后续 Release 使用同一密钥并增加 versionCode，可直接覆盖升级。
- 下载后可以用 `sha256sum -c SHA256SUMS` 校验文件完整性；校验和不能替代可信下载渠道及 APK 签名。
- AGPL-3.0 与复用来源见 LICENSE/NOTICE。Release 页面对应标签提供完整源码。

参考：[GitHub Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)、[Release CLI](https://cli.github.com/manual/gh_release_create)、[Android 签名](https://developer.android.com/studio/publish/app-signing)。
