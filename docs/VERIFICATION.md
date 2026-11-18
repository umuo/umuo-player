# Spike 验证记录

日期：2026-10-24。下列“通过”只表示对应环境和用例，不等于所有服务器版本与硬件兼容。

## 构建与核心测试

| 检查 | 结果 | 证据 |
|---|---|---|
| Android 手机/平板 Debug APK | 通过 | `app-mobile/build/outputs/apk/debug/` |
| 独立 TV Debug APK | 通过 | `app-tv/build/outputs/apk/debug/` |
| JVM 核心测试 | 48 个通过 | `shared/build/test-results/jvmTest/`，包括草稿隔离、凭据提交回滚、旧数据库迁移和亮度小幅滑动 |
| 两应用 Android Lint | 通过，0 错误 | 各 app 的 `build/reports/`；仍有固定版本升级建议等警告 |
| iOS / tvOS KMP framework | 初始 Spike 通过 | arm64 Simulator Debug framework；本轮 Android 交互改动未重跑 Apple 构建 |
| iOS / tvOS SwiftUI 壳 | 初始 Spike SDK 编译通过 | Apple 验证壳，仅共享模型交互；尚未实现 Apple 播放器 |
| Python 夹具与 QA 脚本 | 语法检查通过 | fixture_server / qa_android / export_verification |

测试覆盖两源认证与服务路径、反向代理子路径、播放计划、START/PROGRESS/STOP、转码释放、同源认证限制、错误信息脱敏、WebDAV 命名空间/转义/失败属性、Basic/Digest 挑战、Range 探测、来源隔离、进度与人工匹配持久化、取消自动匹配、扫描失败保留与恢复、默认关闭元数据调用、精确自动匹配、手势恢复和播放事件顺序。

`verification/summary.json` 是去除主机名和本机路径后的结果摘要；原始设备截图与日志在忽略的 `verification/local/`，不应直接公开上传。

## 协议与界面

| 来源 | 合成契约 | UI 连接/浏览/播放/定位/退出 | 真服务兼容 |
|---|---|---|---|
| WebDAV | 通过，包括 Basic/Digest、目录及 Range | 手机通过；NFO、海报墙、字幕选择与本地位置已走通 | 不同 NAS、代理、Digest 算法仍需实服 |
| Jellyfin | 独立契约通过 | 手机合成服务通过；捕获 START/STOP | 未提供真实服务器，待验收 |
| Emby | 独立契约通过，包括 `/emby` 与代理子路径 | 手机合成服务通过；捕获 START/STOP | 未提供真实服务器，待验收 |
| TMDB | 以替身验证默认禁用、精确匹配、人工取消 | 设置/搜索入口已实现，默认关闭；匹配策略有替身测试 | 无用户 token，未做实网请求 |

模拟服务并非真实 Emby/Jellyfin 实现，不能用它证明服务器各版本的完整 API、转码权限或设备 profile 正确。周期性 PROGRESS 与停止顺序有 JVM 测试，真实服务器还需暂停/切网/转码回收验证。

## 设备边界

- 手机：Android 13 / API 33 arm64 模拟器，验证实际彩条视频解码和时间推进。
- 平板：使用同一 Android 运行时调整到平板尺寸检查布局；已检查侧栏、宽屏主视觉、继续观看及六列海报布局，截图为 `verification/local/cinema-home-tablet.png`。不代替真实 Pad、多窗和触控硬件验收。
- 画中画：入口和平台调用已实现，本次尚未完成运行验收。
- TV：真实 Android TV API 31 AVD 在本机遇到授权、磁盘余量与启动离线问题。独立 TV APK 的连接、浏览、播放、定位、确认键与返回卡片焦点闭环已通过，使用可用 Android 13 模拟器的 TV 尺寸；不将其标为 Android TV 系统或真机通过。
- Apple：本机安装 Xcode 16.4。最初默认 developer 路径指向 Command Line Tools；指定完整 Xcode 后 framework 和 SwiftUI SDK 编译成功。tvOS scheme 因缺少模拟器运行时不能使用 generic destination，直接 target + SDK 编译成功。没有完成 Apple 模拟器运行和真机播放。

## 播放样片矩阵

| 样片 | 准备情况 | 当前结论 |
|---|---|---|
| MP4 / H.264 / AAC | 已生成 40 秒合成样片 | 手机实际解码、定位、续播路径已验证 |
| SRT | 已生成中文样片 | 外部轨道列出并可选择；完整布局与不同字幕时间轴还需真机验证 |
| VTT / ASS | 已生成 | 轨道入口已接入；复杂 ASS 特效兼容待验证 |
| MKV / HEVC | 已生成 | API 33 模拟器实际播放通过，首帧 852 ms；真机仍待验证 |
| MKV 多音轨（AAC/AC3） | 已生成 | 视频与 AAC 播放通过，首帧 781 ms；本模拟器仅报告 AAC 为可用音轨，AC3 不可用 |
| HLS | 已生成，且服务器播放计划契约通过 | 通过 Emby 合成协议选择兼容播放后，实际 HLS 播放通过，首帧 665 ms；不代表真实转码器通过 |
| 1080p 高码率 | 已生成 | 合成 12 Mbps 样片实际播放通过，首帧 850 ms；持续播放与真实 TV 网络待验证 |
| HDR / 音频直通 | 无可靠真机夹具 | 明确未验证 |

首帧指标从 Media3 prepare 开始，到首帧回调结束，不包括媒体源解析前的耗时。定位指标为 seek 命令后再次 READY 的耗时，不代表逐像素画面提交延迟。具体测量记录在 `verification/summary.json`，只用于本机合成环境比较。

## 已知限制与发行前工作

- 当前是最小协议子集；设备 profile 还缺完整 level、HDR、分辨率、码率与字幕能力条件。不能承诺任意文件无转码直放。
- WebDAV 媒体请求的 Digest 当前支持 MD5/qop=auth；其他算法需要补齐和测试。浏览和资料请求的自动重定向仍拒绝；播放 Range 探测支持有界跳转，跨源不发送凭据，HTTPS 不降级为 HTTP。
- 扫描支持目录级恢复和指纹跳过；整棵删除目录的陈旧索引回收、万级库与重命名迁移未完成。XML/NFO 解析有大小和深度限制。
- 当前用户与来源之间不会自动合并媒体身份或观看记录；没有账号云与实时跨设备同步。
- 未实现连接删除、完整后台播放服务、完整字幕样式和分卷/版本选择。来源编辑已实现。项目迁移表列出未迁入的 Peach 能力。
- TMDB 官方 logo、条款/商业许可确认、SBOM、正式签名、商店发行与无障碍全矩阵属于后续工作。APK 使用调试签名，未提交、推送或发布。

## Android 交互改进：第二轮

本轮根据 Android 体验反馈调整源码，构建仅用于本机回归，未更新 `dist/` 交付安装包。

- WebDAV 默认 HTTP、80、`/dav`，主机、端口、路径分开输入；HTTPS 开关切换默认端口，完整 URL 失焦后拆分。逐字输入完整 URL 已回归，避免中途拆分打断输入。
- 错误密码仍保存同一条来源配置，表单保留并显示错误；返回来源列表编辑密码后连接成功，测试数据库中对应名称仅一条记录。密码仍只进入平台安全存储。
- 来源与设置改为独立 Tab，当前页有选中状态；媒体库位置由 Compose 保存。编辑来源为独立页面。
- 补充验证 HTTPS 默认端口在 80 / 443 间联动、自定义 8443 端口不被覆盖；编辑时留空密码可复用安全存储凭据，重连后续播仍存在。Tab 来回切换保留可见媒体与滚动位置。
- 无海报的 MP4、HEVC/MKV、多音轨 MKV 与高码率合成文件实际生成本机预览 JPEG。退出播放后，继续观看显示约 31 秒处的实际画面；最终构建暂停定位后再次验证约 11 秒处快照，与卡片上的续播时间对应。目录使用横向预览，海报墙保留竖向海报。
- 左右双击显示 ±10s；实际截图验证 +10s、亮度百分比、音量百分比及进度条。横滑目标时间和长按倍速提示已实现。
- 更改服务器、根目录或账号会清除该来源旧索引、进度、匹配与扫描队列；名称修改保留数据，其他来源不受影响，有 JVM 回归测试。预览缓存同时按连接身份隔离。
- 普通封面内存/磁盘缓存按来源与账号隔离；离开页面后停止后续提帧读取。手势提示为纯绘制层，避免阻挡下一次操作。
- 追加连续双击回归：暂停状态下，提示仍可见时两组双击累计前进约 20 秒。测试注入的相邻点击间隔为 100ms，符合 Android 双击时间阈值。
- TV 最终构建通过遥控确认、方向键定位、暂停、返回后重新取得焦点；仍使用普通 Android 模拟器的 TV 尺寸，不代表 Android TV 系统硬件验收。

证据位于 `verification/local/iteration-02/`。以上播放与提帧在 Android API 33 模拟器、Basic 认证合成服务验证；Digest 提帧、API 26 运行和不同 NAS 容器仍需设备验证。文件不支持 Range、解码失败或系统清理缓存时回退到现有图片/占位图，不保证所有视频均能生成缩略图。强制结束进程时只能使用最近一次已保存的快照。

## 第三轮：0.1.1-spike

先冻结第二轮 APK，再开始本轮改动。第二轮原包保持不变；修复版使用 versionCode 2、versionName 0.1.1-spike，数据库从版本 1 增加草稿表到版本 2。升级保留已有来源和进度，不支持直接回退旧数据库版本。

- 草稿与有效连接分表保存，候选密码/token 在安全存储中独立命名。认证和首次浏览成功后再提交；失败保留原连接、凭据、索引、进度。新尝试不会复用上次服务器的候选 token。JVM 测试覆盖失败草稿重建、账号隔离、提交失败回滚和 1→2 迁移，模拟器实际升级及错误密码草稿跨重启/放弃后续播通过。
- 手势参数可保存：5/10/30 秒定位、1.5/2/3× 长按倍率、0.5–2× 滑动灵敏度。新增播放器锁定、解锁和返回防误触。
- 修复亮度跳变：原计算在 `WindowManager.LayoutParams.apply` 中读到了窗口参数的 `height`，而非触控区域高度。现于手势开始记录触控高度，在窗口参数作用域外完成计算。模拟器测得 2% → 4.2466% → 2.1745%，没有跳至 100%；新增两项 JVM 回归测试验证小步递增、边界与未测量区域。
- 继续观看和精选仅在首页/总览显示，子目录、具体媒体库和剧集列表隐藏；手机模拟器验证进入目录后消失、返回首页后恢复。
- 媒体库 Tab 支持双击返回当前来源首页并回到顶部；单击切换继续保留目录位置。
- 404 和 403 合成故障显示对应提示及重试/编辑入口；恢复服务后原位重试，实际解码播放通过。无效 MP4 显示格式/解码提示，WebDAV 不显示无法提供的服务端转码按钮。

原生截图位于 `verification/local/iteration-03/`。真实服务器、Apple 构建及运行、本轮新增的网络断线恢复和不同品牌手机亮度行为仍需后续验证，不能用合成服务结果代替。

## 第四轮：播放器操作与窄屏适配

- 底部工具栏在可用宽度小于 360dp 时分两排，保留音轨、字幕、锁定、画中画和全屏的触控区域。API 33 模拟器实际验证常规手机与 320dp 窄屏，全部按钮位于屏幕内，字幕和锁定入口可操作。
- 倍速面板按宽度自动换行，支持 0.5/0.75/1/1.25/1.5/2/3×；选择后关闭面板。320dp 与常规手机截图已检查。空音轨和字幕增加明确提示，其中无字幕状态已通过合成视频验证。
- 修复定位上限使用错误：定位目标限制在已知总时长以内。回归发现最近关键帧定位会使 40 秒末尾落到约 31 秒，改用 Media3 精确定位，合成样片实际到达 40 秒边界。精确定位在部分大文件或低速服务器上的耗时仍需真机验证。
- 播完显示重新播放，操作后先回到开头再播放；实际合成视频完成末尾定位、重播与暂停。
- TV 遥控方向键定位到末尾、确认键重播、退出后焦点恢复通过；使用普通 Android 运行时的 TV 尺寸，不代表真实 TV 系统或硬件验收。

本轮两个 Android 应用构建及 Lint、23 项核心测试通过。截图位于 `verification/local/iteration-04/`；此前 `dist/` 安装包保持不变，本轮尚未生成新的交付包。

## 影视库、三源表单与服务器观看功能

- WebDAV 新增文件浏览 / 影视库入口。影视库仅收录 NFO 或明确元数据匹配，文件名与孤立海报不算已刮削。支持电影/剧集、类型、标签、合集组合筛选；分集继承 tvshow.nfo 的资料，按来源与剧集目录聚合、按季/集排序。旧 JSON payload 采用新增字段默认值，无需数据库迁移；重新扫描会更新旧 NFO 条目的元数据标记。
- 手机合成服务实际验证未刮削视频排除、电影与剧集筛选、类型/标签/合集组合、详情标签、跨季分集与重启后模式恢复。TV 遥控筛选截图已检查并显示选中焦点；使用普通 Android 运行时的 TV 尺寸。
- Emby/Jellyfin 使用服务器影视库，提供媒体库、全部电影、全部剧集入口，不需要客户端扫描 NFO。三源表单均使用主机、端口、路径、HTTPS，联动默认端口并保留自定义值；Emby/Jellyfin 默认 8096/8920 与根路径。手机实际验证默认值、8443 保留、完整 URL 失焦拆分及 Emby 连接。
- 观看客户端增加服务器影视搜索、续看、下一集列表、最近添加、收藏列表、合集子项，以及收藏/取消收藏、已看/未看。首页异步读取服务器续看，失败提示使用本机记录。状态操作等待服务器确认，拒绝或失败不提前修改缓存与界面。
- 两种服务器分别验证视图、分页、Unicode 搜索参数、收藏和观看状态的 POST/DELETE、跨来源拒绝，以及错误正文脱敏。Emby 用 IsResumable 查询续看，Jellyfin 用 Items/Resume。Emby 合成服务验证续看、下一集、最近添加、合集子项、搜索无结果、收藏/已看切换，以及 403 保留原状态。Jellyfin 合成服务单独验证登录、影视库、收藏/已看切换、下一集和服务器续看为空。TV 遥控连接、收藏切换、搜索及返回焦点通过。手机双击媒体库从下一集视图返回服务器首页，收藏页返回键不退出应用；TV 搜索页返回服务器媒体库并保留焦点，已实测通过。

核心测试共 36 项通过，两个 Android 应用构建和 Lint 通过。本轮未更新旧安装包；截图在 `verification/local/catalog/` 与 `verification/local/server-client/`。真实 Emby/Jellyfin 版本、权限与大库分页、真实 TV，以及 Apple 最新构建和运行仍未验收。完整观看客户端进度见 [功能矩阵](MEDIA_SERVER_CLIENT.md)，音乐、直播、自动连播等不能标为完成。

## 0.1.3：OpenList 直链、请求节流与紧凑导航

- 修复 OpenList 302 场景：旧版播放前的 Range 探测会拒绝重定向，导致落入笼统错误。现在最多跟随 5 次跳转，跨源使用无认证客户端，不把来源密码/token 带到 CDN；拒绝循环、不安全协议、URL 内凭据与 HTTPS 降级。系统 TLS 校验保持开启。区分证书、跳转及限流错误，提示不包含完整签名 URL。
- 核心测试验证 HTTPS→HTTPS 签名直链、认证不跨源、签名查询参数、相对路径、循环及跳转预算；原生 Android 合成服务实际验证 302→另一端口播放与定位、CDN 未收到认证头、不受信测试证书被拒绝，以及恢复有效地址后的重试。此结果不能代替用户公网 OpenList 实服验收。
- 扫描改为单并发，认证握手和扫描 HTTP 请求统一节流。实测发现仅按发送起点计时可能压缩服务端接收间隔，现改为响应收到后再等待。手机合成服务记录请求间隔并验证限速、429 暂停、冷却中再次点击不发送请求、原索引保留。可选择 1/3/5 秒档位，支持关闭额外的视频提帧请求。
- 筛选默认折叠，数量可见、同名同归属的类型/标签去重、多合集及剧集集合归属参与筛选。手机与 TV 遥控操作已验证默认隐藏、合集缩小结果、折叠保留条件；TV 设置页的扫描选项截图已检查。TV 使用普通 Android 模拟器的 TV 尺寸，不代表真实 TV 系统验收。
- 手机/平板移除来源 Tab；右上角切换面板保留当前来源、编辑、草稿和新增入口。手机原生界面已验证切换面板、当前来源优先及编辑入口，底部仅保留媒体库和设置；320dp 窄屏验证切换/新增按钮位于屏幕内，并实际切换至 Jellyfin 再恢复 WebDAV。

本轮核心测试 48 项通过，两端构建与 Lint 通过。原生证据在 `verification/local/0.1.3/`；真实公网证书、真实 OpenList 存储直链及真实 TV 设备仍需用户环境验收。


## 0.1.4：115 直链 UA 一致性与权限提示

用户反馈公网 OpenList 可以浏览，播放报认证/权限错误，存储为 115。检查发现 Ktor Range 探测和 Media3/OkHttp 播放使用不同的默认 User-Agent。OpenList 的 [115 UA 绑定问题记录](https://github.com/OpenListTeam/OpenList/pull/2510)及[下载 UA 排查记录](https://github.com/OpenListTeam/OpenList/issues/2502)说明此类直链可能受 UA 约束；这属于针对客户端已确认缺口的修复，尚不能证明用户实服故障只有这一个原因。

- Range 探测的每一跳、Media3 播放及 Android 预览使用同一个固定 User-Agent；跨源仍不携带来源认证，不复制来源 Cookie，不关闭证书校验。
- 权限失败区分源站文件读取和跳转后的直链 401/403，显示 HTTP 状态且不暴露 URL、签名或凭据。实际播放阶段也按失败请求与配置来源的 origin 区分，避免将网盘/CDN 拒绝访问全部提示为账号过期。
- 合成服务可开启 UA 绑定、源站拒绝、CDN 拒绝三个独立场景。旧 0.1.3 APK 在 UA 绑定场景已复现相同的笼统认证错误。

原生回归还发现同一影片重试时的画面绑定问题：引擎已重建，界面可能继续持有已释放的旧实例。Android 引擎新增播放实例流，手机/TV 共用画面组件订阅后重新绑定，避免重试时播放状态前进但无画面。

本轮核心测试 50 项通过，两个 Android 应用构建与 Lint 通过（0 Error/Fatal，手机 29 项 Warning、TV 1 项 Warning）。真实公网 OpenList/115 仍需安装新版实测。


0.1.4 原生回归通过：合成服务按生成直链时的 UA 绑定下载许可，旧版失败、新版播放和定位通过；分别模拟源站 403、探测 CDN 403、实际播放 CDN 403，提示来源正确，解除限制后重试均重新出帧。修复画面实例绑定后复测得到四次出帧及一次定位就绪事件。样片由 AVFoundation 生成，为 40 秒 H.264 合成视频，不代表 115 实际资源或音频兼容性测试。截图和原始证据在 `verification/local/0.1.4/`。


## 0.1.5：字幕下移与透明背景

手机和 TV 的 PlayerView 共用文本字幕外观：底部留白从 Media3 默认 8% 改为 3%，字幕背景/窗口透明，白字加描边；覆盖内嵌文字样式，保留显式 Cue 位置。使用 [SubtitleView 官方接口](https://developer.android.com/reference/androidx/media3/ui/SubtitleView)，不改变字幕解码或播放来源。

- 手机原生截图检查竖屏分段 SRT、带黑底 CSS 的 VTT 和横屏 SRT；黑底去除且字幕靠近视频底边。
- TV 正常播放时收起底部控制栏，避免覆盖下移字幕。模拟器 TV 尺寸下实际遥控选择 VTT，下键唤出控制栏、上键回到画面并收起、中键暂停均通过；截图已检查。真实 TV 硬件仍未验证。
- 两个应用构建及 Lint 通过（0 Error/Fatal）。本轮未修改 shared，核心测试沿用已有 50 项通过记录。证据在 `verification/local/subtitles/`。
- 格式边界：位图内自带的背景无法用文本样式去除，复杂 ASS 需要单独验证。测试还观察到从 0 秒覆盖整段视频的长单条 SRT 在续播时未出字，分段 SRT 可显示；此项保留为字幕加载/续播的后续专项，不标为兼容性通过。


## 0.1.6：TV 版 Pad 测试与字幕列表中文名称

- TV 同一 Activity 保留 LEANBACK_LAUNCHER，新增普通 LAUNCHER；leanback 声明改为可选，触屏仍非必须。采用 [Android TV 官方双端声明方式](https://developer.android.com/training/tv/get-started/create)。普通 Android 的实际应用抽屉已找到「Umuo Player TV」并点击启动，不只用显式 Activity 启动代替桌面验证。
- 仍使用原 TV 交互。非电视设备增加可收起的触控测试遥控器，不取得 TV 内容焦点，通过方向、确定、返回测试原界面；进入键盘导航模式，窗口未处理的方向由 Compose 焦点导航补齐。已实际验证连续移动、确定进入设置、返回主页、收起面板。电视识别使用 leanback feature 或系统电视 UI mode。
- 手机/TV 共用中文字幕列表名称：ISO 语言码和常见英文名称转为中文，识别简/繁体、中英双语、强制、默认、听障、解说等常见标识。WebDAV 从规范文件名后缀识别语言，保留原文件名小字作为辅助信息；不能判断语言时显示「字幕 N」，不猜测或自动翻译影视文件名，也不翻译字幕正文。
- 核心测试 50 项及 Android 字幕名称规则 7 项通过，两个应用构建/Lint 通过。规则覆盖 ISO/区域码、语言后缀和影视名误识别、用途标识、未知语言、自定义标题与 URL 隐私回退。
- Pad 验证使用 API 33 模拟器的 1920×1200 / 240dpi；真实 Pad 厂商桌面、真实 TV 系统与硬件仍需验收。证据在 `verification/local/0.1.6/`。

0.1.6 原生补充：手机字幕面板主标题已显示「英语 · SRT · 强制字幕」「简体中文 · VTT」，原文件名作为次要文字；TV 遥控选择英语强制字幕和繁体听障字幕，选中标记、上键返回画面及返回主页通过。两端未检测到语言信息的轨道保留「字幕 N」回退，不把影视名中的 English 当作语言后缀。

## 0.1.7：TV 输入与返回层级

用户在电视端反馈输入框锁住遥控焦点、返回直接退出。0.1.6 的 TV 尺寸/Pad 检查没有覆盖输入法和页面返回的完整链路，不能作为这条路径的验收。

- 来源表单、服务器搜索、匹配标题、TMDB 令牌统一使用 TV 输入组件：先选择输入项，确定后编辑；完成/返回结束编辑并恢复该项焦点。输入法可先消费一次返回以收起键盘；键盘关闭后上下键结束编辑并移动到相邻项，左右键保留给光标。
- 编辑区随键盘高度自动滚动，包含「完成输入」按钮。实际 Android TV 系统验证时发现底部用户名会被裁切，已修正后复测，不只检查顶部名称字段。
- 来源和设置页面可滚动。校验错误出现后，保存按钮保持可见且恢复焦点；失败不关闭表单。从新增/设置返回后恢复顶部入口焦点。搜索输入的返回先处理编辑/搜索草稿，避免把该次返回交给 Activity 退出。
- Pad 的测试遥控器增加键盘避让，继续只在非电视设备显示。

使用 API 31 Android TV 系统模拟器（具备 television、leanback、leanback_only 特性）和原生电视软键盘，实际按键验证六个来源字段的进入编辑、返回和焦点恢复；隐藏键盘后下键离开编辑并聚焦下一项；无效端口校验保留原表单、按钮完整可见；页面返回恢复「添加来源」焦点；搜索编辑返回、清空草稿并留在首页通过。全部使用合成文字，无真实来源或账号。

两端构建及 Lint 通过（0 Error/Fatal，手机 29、TV 1 项 Warning），50 项核心与 7 项 Android 字幕规则测试通过。本轮未修改播放引擎，不扩展此前的格式兼容结论。原生证据在 `verification/local/0.1.7/`；真实电视硬件、各厂商输入法仍需实机验收。

TV 设置补充：令牌输入返回后焦点恢复；开启 TMDB 但未提供令牌时保存，错误与保存按钮完整可见，仍停留设置页面；返回后恢复顶部设置入口焦点通过。未查询 TMDB，也未写入任何测试令牌。

Pad 补充（API 33，1920×1200 / 240dpi）：键盘上方的触控遥控器可见；遥控返回结束输入并保留表单、下键退出编辑并聚焦地址、触控「完成输入」、再次返回首页通过。在有合成影片的资料库还发现原网格的延迟焦点恢复会抢走顶部入口焦点，已统一协调入口和卡片恢复，并让触控返回进入遥控导航模式。新增/设置返回入口的焦点复测通过。

Pad 内容页补充：合成剧集单集详情中，「匹配标题」输入返回后恢复该项焦点，再返回剧集列表恢复原单集卡片焦点通过；未发送元数据查询。九个 TV 输入项（来源六项、搜索、令牌、匹配标题）均已覆盖编辑退出路径。

## 0.1.8：手机石墨影院首页

依据用户选定设计图，用原生 Compose 重做手机/Pad 的主题、文字导航、通栏精选、双卡续播、海报列表及底部导航。主题限定在 ui-mobile，未修改共享协议、数据库迁移或播放引擎，也未把生成海报当作用户媒体数据。

- API 33 原生验证：WebDAV 连接和只读扫描、精选/详情播放出帧、退出续播、电影/剧集与分集、组合筛选、全部续播返回原位置、文件子目录、设置与来源配置入口通过。筛选面板在布局切换后仍保持展开。
- Jellyfin 使用合成观看服务，验证连接、电影、收藏状态更新、合集、搜索空结果及清除。切回 WebDAV 后恢复原来源的索引和续播记录。Emby 协议本轮未单独运行 UI 回归，沿用既有契约测试；真实服务兼容结论没有扩大。
- 手机内容区域与设计图在同一画布并排检查，修复了背景硬分界、默认导航胶囊、文字行距和首排海报标题遮挡。参考与原生数据不同，详细判据和证据见根目录 `design-qa.md`。
- 320dp / 130% 字号检查来源与新增触控范围，平板 1920×1200 / 240dpi 检查宽屏布局及电影切换。原生证据位于 `verification/local/0.1.8/`，生成图仅为视觉目标，不作为运行证据。

两端构建及 Lint、50 项核心与 7 项 Android 规则测试通过（0 Error/Fatal；手机 38、TV 1 项依赖版本相关 Warning）。TV APK 在 Pad 上补测设置进入/返回及入口焦点恢复通过，TV UI 本轮保持原样。真机、厂商系统字体、HDR、复杂字幕、音频直通等仍按此前矩阵分别验收。

## 0.1.9：模式切换的可发现性

将 WebDAV「文件浏览 / 影视库」独立为固定模式行，分类入口不再替换模式入口。模式行位于滚动内容之外，详情和子目录仍显示；当前模式同时使用图标、强调色和下划线。文件模式专注目录，切换模式回到对应首页；进入影视库清除旧文件目录层级。

原生 API 33 合成 WebDAV 验证：模式往返、电影分类中切回文件、滚动后入口位置不变、详情直接切换、文件子目录直接进入影视库、文件模式重启保持通过。切换后恢复顶部的回归发现了旧条目锚点抢占位置，改用下一次布局定位后重新验证分类可见。

新建未扫描的合成来源，验证「还没有已刮削的影视」页面仍可直接切回文件浏览，不需要先扫描。切至 Jellyfin 合成来源时不显示 WebDAV 文件模式，切回原来源后模式正确。320dp / 130% 字号与平板 1920×1200 / 240dpi 的双向切换通过。证据位于 `verification/local/0.1.9/`。

两端构建和 Lint 通过，Android 规则测试 7 项通过；共享核心未修改，沿用既有 50 项通过记录。TV APK 在 API 33 Pad 模拟器中补测设置返回和入口焦点恢复通过。本轮不扩大播放格式或真实服务器兼容结论。

## 同源播放器问题修复与全量验证

- 问题修复与收敛：
  - `shared` 播放协调与数据扫描：`PlaybackState` 补充 `paused` 语义，`PlaybackCoordinator` 增加 `isUserPaused()`、`everReady`、`lastRequest` 与 `refreshPlan(positionMs)`，避免缓冲阶段误报暂停进度、停止播放时保留最后 seek 位置并在刷新播放计划时维持同一会话；`LibraryRepository` / `LibraryScanner` 增加批处理事务与 `overrideMap()` / `seriesByPath` 缓存；`WebDavProvider` 与 `MediaServerProvider` 补齐 `dfxp` / `vtt` / `webvtt` 字幕映射并将列表解析调度至后台线程。
  - `playback-media3` 与活动生命周期：`Media3Engine` 统一异常重连策略、快速 seek 合并与尾部夹紧及有限次自动恢复（`PlaybackResilience.kt` 抽出纯规则便于单测），暴露 `pipAspectRatio()`；`SourceNetwork`、`SourceImages`、`VideoThumbnails` 统一网络池复用与关闭逻辑；`PlayerModel` 将网络与重连请求移至后台分发并修正服务器搜索与清除搜索状态；移动端 `MainActivity` 补齐画中画参数构建与尺寸更新。
  - UI 安全提示与 R8 收敛：Mobile 端与 TV 端补充独立 `HTTP · 未加密` / `HTTP 未加密` 明文连接警示，错误态下保持播放控制栏不自动隐藏；启用 `app-mobile` 与 `app-tv` 的 Release 代码混淆与资源收缩（`proguard-rules.pro`）。
- 单元测试、Lint、Apple 编译边界与 R8 构建：
  - `:shared:jvmTest`：53 项通过（0 失败，0 跳过；含新增状态汇报与仓储覆盖单测）。
  - `:playback-media3:testDebugUnitTest`：12 项通过（0 失败，0 跳过；含新增弹性策略单测）。
  - `-PenableAppleTargets=false` 下的 `:app-mobile:assembleDebug`、`:app-tv:assembleDebug`、`:app-mobile:lintDebug`（0 errors, 39 warnings）、`:app-tv:lintDebug`（0 errors, 1 warning）均通过。
  - Apple KMP 编译边界：`:shared:compileKotlinIosSimulatorArm64` 与 `:shared:compileKotlinTvosSimulatorArm64` 编译通过。
  - R8 Release 检查（使用临时 JKS）：`:app-mobile:assembleRelease`（3,314,079 B，Debug 为 23,489,447 B）、`:app-tv:assembleRelease`（3,186,713 B，Debug 为 23,601,640 B）、`:app-mobile:lintRelease`（0 errors, 25 warnings）、`:app-tv:lintRelease`（0 errors, 1 warning）全部通过。
- API 33 ARM64 模拟器与合成样片运行验证（`Pixel_3a_API_33_arm64-v8a`，本地 `tools/fixture_server.py`）：
  - R8 Release 冒烟（Mobile Jellyfin 样片）：冷启直连 40s 样片正常起播（`first_frame_ms=5501`），呈现 `FixtureJELLYFIN · HTTP 未加密`，`events.jsonl` 有序上报 `Playing -> Progress(IsPaused=false) -> Stopped`，无混淆崩溃或异常日志。
  - Debug Mobile WebDAV：验证 `HTTP · 未加密` 徽标、影视库 NFO/海报扫描、外挂 SRT 字幕选择及 `+10 秒` 跳转（`first_frame_ms=2491`、`seek_ready_ms=2594`），截图检查通过。
  - Debug Mobile Jellyfin + 交互压测：验证 `HTTP · 未加密`、ASCII 搜索（`Arrival`）与清除搜索恢复、22 次快速 `±10 秒` 点击、横向滑动 seek 及 40s 尾部夹紧后恢复播放（`first_frame_ms=3767`、`seek_ready_ms=4175 / 684 / 3855`），`events.jsonl` 仅记录有序进度且无 `IsPaused: true` 误报；点击画中画按钮确认进入 16:9 `mode=pinned` 悬浮窗并在首帧渲染后正常显示视频画面（实测发现若首帧尚未解码完成即触发画中画，悬浮窗内会短暂呈黑屏）。
  - Debug TV WebDAV + 焦点压测：经由 `TvTextField` 焦点编辑流程连入合成源并呈现 `HTTP 未加密`，切换影视库扫描展示海报墙；核验详情页点击「返回媒体库」后直接按确认键即可恢复焦点重新打开详情；完成 12 次连续遥控器左右键快速 seek（`first_frame_ms=1175`），无播放错误或 `FATAL EXCEPTION`。
- 验证限制：
  - 上述运行验证均基于合成样片与本机受控服务；真实 NAS / WebDAV / Emby / Jellyfin 服务器、Apple 平台运行时实际播放、HDR 映射及音频直通均未实测，不扩大兼容结论。
  - 原始测试日志与截图存放在已忽略的 `verification/local/`（`r8-smoke/` 与 `current/`），测试后已终止模拟器与临时服务器进程。

## 第二轮性能优化、剧集连播与画中画增强

- 改动摘要：
  - `shared`：SQLDelight 数据库由 v2 升级至 v3（`2.sqm`），补充 `indexed_media(source_id, parent)` 与单调递进 `playback_position.updated_at` 降序索引；`LibraryRepository` / `LibraryScanner` 新增批量入队、目录事务提交与 `overrideIds` 预载；`WebDavProvider` 复用匿名探测客户端并在 `PROPFIND` 403/失败时退化保留正片播放；`PlaybackCoordinator` 支持注入 `ioContext` 卸载数据库写入；`LibraryCatalog.findNextEpisode` 与 `MediaServerProvider.nextEpisode` 支持同源剧集下一集解析。
  - `playback-media3`：`Media3Engine` 缓存音轨/字幕列表，并按 `seriesContinuationKey`（含来源 ID 隔离）在同剧集续集切换时延续倍速、音轨及字幕偏好；`PlaybackPreferences` 新增并持久化 `autoPlayNext`（默认开启）；`PlayerSurface` 支持动态切换 `GestureOverlay` 触控状态而不卸载 `PlayerView`；`VideoThumbnails` 增量追踪缓存体积并限制失败尝试集合大小；`PlayerModel` 将剩余详情、偏好匹配、用户状态同步移至 `Dispatchers.IO`，修正服务器续播导入排序并支持单次触发自动连播下一集。
  - `ui-mobile` / `app-mobile` / `ui-tv`：`CinemaPlayer` 与 `TvApp` 将每 500ms 变化的 `positionMs` 隔离至独立叶组件减少重绘；移动端保持单个 `CinemaPlayer` 挂载进入画中画（消除切换黑屏/重建）并在 `MainActivity` 通过非导出 `BroadcastReceiver` 注册 `RemoteAction`（快退、暂停/播放、快进）；移动端与 TV 端新增「下一集」与「剧集播完自动连播」开关；TV 端播放全屏 HUD 在起播或方向键操作 5–8 秒后自动完整隐藏（含顶部标题与 HTTP 提示条），左右快进快退仅轻量唤醒 HUD 而不抢走画面焦点。
  - CI 与工具：`.github/workflows/verify.yml` 增加临时签名密钥 R8 Release 构建与 Lint 门禁；`tools/fixture_server.py` 启动时规范化 `--root` 符号链接路径；`tools/qa_android.py` 适配 `TvTextField` 焦点编辑、移动端空库扫描按钮及控制栏自动隐藏唤醒点击。
- 已完成的自动化验证：
  - `:shared:jvmTest`：57 项通过（0 失败，0 跳过；涵盖 v1→v3 数据库迁移、同毫秒 `updated_at` 单调续看排序、`findNextEpisode`、`MediaServerProvider.nextEpisode` 与 `PROPFIND` 403 回退）。
  - `:playback-media3:testDebugUnitTest`：13 项通过（0 失败，0 跳过；含 `seriesContinuationKey` 来源隔离单测）。
  - `-PenableAppleTargets=false` Debug 构建与 Lint：`:app-mobile:assembleDebug`（23,282,516 B）、`:app-tv:assembleDebug`（23,512,269 B）、`:app-mobile:lintDebug`（0 errors, 39 warnings）、`:app-tv:lintDebug`（0 errors, 1 warning）均通过。
  - 临时密钥 R8 Release 检查：`:app-mobile:assembleRelease`（3.2M）、`:app-tv:assembleRelease`（3.0M）、`:app-mobile:lintRelease`（0 errors, 26 warnings）、`:app-tv:lintRelease`（0 errors, 1 warning）均通过，检查后已清理临时密钥与测试 Release 包。
  - Apple KMP 编译边界：`:shared:compileKotlinIosSimulatorArm64` 与 `:shared:compileKotlinTvosSimulatorArm64` 编译通过。
  - 工具链与夹具检查：`python3 -m py_compile tools/fixture_server.py tools/qa_android.py` 通过；`tools/fixture_server.py` 在 macOS `$TMPDIR` 符号链接路径下 `PROPFIND` 返回 `207`。
- 未完成的运行时验证与已知边界：
  - 按用户要求直接推送当前提交，本轮新增的画中画 `RemoteAction`、无黑屏无缝切入画中画、剧集自动/手动连播切换及 TV 端完整 HUD 自动隐藏尚未在 Android 模拟器或真机完成 UI/播放实测与截图审查，不标为运行时通过；真实 NAS / Emby / Jellyfin 服务器、Apple 平台运行时实际播放、HDR 映射及音频直通仍未实测。
