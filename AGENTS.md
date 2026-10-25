# Umuo Player 开发约定

- `shared` 是 KMP 业务核心，禁止引入 Android Context、Media3、JSONObject 或 UI 类型。新来源通过 `MediaSourceProvider` 与 `SourceRegistry` 扩展。
- 媒体身份必须包含来源实例 ID；不同服务器与账号的缓存、凭据、进度不能混用。公开日志和持久化配置不包含口令、token 或完整签名 URL；播放计划中的临时资源地址不能写入日志。
- Android 播放、Keystore 与应用生命周期位于 `playback-media3`。手机/平板与 TV 交互分开，手机使用石墨影院主题，TV 保留适合遥控焦点的 `CinemaStyle` 视觉 token。
- WebDAV 只读。扫描、匹配和播放记录存储在本机，不修改远程文件。默认关闭 TMDB；仅用户主动启用后查询标题。
- 依赖固定在版本目录。修改共享逻辑后运行 `:shared:jvmTest`；Android 检查使用 `-PenableAppleTargets=false`、两个 app 的 `assembleDebug` 与 `lintDebug`。
- 播放引擎修改需用合成样片验证，不能只以编译成功验收。UI 修改需检查实际 APK 截图、核心交互和 TV 焦点，不用网页截图冒充原生界面。
- `tools/fixture_server.py` 只服务带标记的合成目录。测试不能对真实媒体库执行删除、改名或凭据修改。设备原始日志保留在忽略的 `verification/local/`，公开摘要用导出脚本生成。
- Apple framework 与 SwiftUI 壳只代表编译边界；Apple 实际播放、真实服务器、HDR 和音频直通要分别记录结果。未完成的能力写入验证记录，不标为通过。
- 不执行未授权的提交、推送或发布。
