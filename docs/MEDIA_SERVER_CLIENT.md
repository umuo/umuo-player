# Emby / Jellyfin 完整观看客户端

目标是完整观看客户端：影视、搜索、收藏、观看状态、续看/下一集、字幕音轨、转码、合集、音乐和直播。服务器管理不在该范围内。下面按实际实现记录，不以协议接口存在代替用户功能完成，也不以合成服务代替真实服务器验收。

## 当前实现

| 能力 | 实现状态 | 验证边界 |
|---|---|---|
| 多服务器与账号隔离、安全凭据、失败配置草稿 | 已实现 | 核心测试和合成服务；真实服务器认证策略待验收 |
| 简化连接配置 | 主机、端口、路径、HTTPS；8096/8920 和根路径自动填写；支持完整 URL 拆分 | Android 表单与地址契约 |
| 服务器影视库 | 媒体库、全部电影、全部剧集；分页、季/集浏览、详情及服务器图片 | Emby/Jellyfin 各自契约；真实版本矩阵待验收 |
| 类型、标签 | 从服务器资料读取，详情展示 | 不保证服务器所有自定义字段；全库筛选待实现 |
| 播放与会话 | 直放、重封装/转码计划、字幕、音轨、倍速、定位、进度上报、会话释放 | 合成播放和契约；设备能力协商仍不完整 |
| 继续观看 | 首页异步读取服务器续看；另有独立续看视图，失败提示回退本机记录 | Emby/Jellyfin 独立续看路径契约；合成服务浏览通过 |
| 搜索、最近添加、下一集列表、合集 | 服务器影视分页检索、影视搜索、对应浏览入口 | 下一集列表已实现，自动连播尚未完成 |
| 收藏与已看 | 收藏/取消收藏、已看/未看；服务器确认后更新，拒绝请求保留旧状态 | 两种服务器契约和合成服务；Emby 含 403 回归，TV 含遥控操作 |

电影与分集详情已有收藏/已看入口；剧集总览的完整详情与批量状态操作继续在第二阶段补齐。

## 后续必须补齐

| 阶段 | 用户能力 | 交付与验收要求 |
|---|---|---|
| 0：连接与账号 | 密码登录已实现；继续补齐会话失效重认证、用户选择、Emby Connect/Jellyfin Quick Connect 与局域网发现 | 两种服务器分别按支持能力呈现入口，禁止泄露或跨账号复用凭据 |
| 1：影视日常使用验收 | 搜索、续看/最近添加/下一集列表、收藏与已看基础入口已接入，继续完成实服兼容、排序和完整筛选 | 两种服务器分别验收接口、权限、分页和失败回滚；多账号不能串数据 |
| 2：完整影视详情 | 合集详情、演职人员、评分、相关推荐、预告片、章节、多版本、字幕样式/偏移/偏好、音频偏好、播放质量、自动下一集及服务器完整类型/标签/年份排序筛选 | 区分电影/剧集/季/集；服务端能力和账号权限决定入口 |
| 3：音乐 | 艺术家、专辑、歌曲、音乐流派、播放列表、队列、后台播放和系统媒体控制 | 独立音乐交互；不能仅把音乐当作黑色视频播放 |
| 4：直播 | 频道、节目单、正在播放、频道切换、录像浏览及账号允许的录制功能 | 逐服务器确认 EPG/时区、直播会话和回收；需真实服务与样流 |
| 5：完整回归 | 手机、平板、TV；账号/权限差异、断网恢复、格式与性能 | 以真实 Emby/Jellyfin 版本和硬件矩阵验收，不能标记为全兼容 |

离线下载、投屏和同步播放需要另外确定产品与服务端能力边界；不能仅根据“完整客户端”承诺某一官方客户端的所有平台专有或授权功能。

## 影视库与文件模式

- Emby/Jellyfin 原生按服务器媒体库组织，不显示 WebDAV 文件模式开关，也不要求客户端扫描 NFO。
- WebDAV 提供文件浏览与影视库；影视库只收录 NFO 或明确元数据匹配，类型/标签/合集来自资料，不从目录名臆造。
- NFO 支持电影、分集、`tvshow.nfo`，剧集资料向季目录中的分集继承。文件名与独立海报不足以认定已刮削。
- 来源分别保存 WebDAV 模式选择。影视库读取本机索引，可离线浏览；播放仍需连接原来源。

## 参考与复用判断

连接默认值依据 [Emby 连接文档](https://emby.media/support/articles/Connectivity.html) 和 [Jellyfin 网络文档](https://jellyfin.org/docs/general/post-install/networking/)。HTTPS 端口是可配置的服务端监听端口；反向代理通常有自己的端口，完整地址中的端口始终保留。


[Infuse Metadata 101](https://support.firecore.com/hc/en-us/articles/215090947-Metadata-101) 用于理解影视与文件组织、标签的呈现方式；它是交互参考，不复制其资源或实现。[Jellyfin NFO 文档](https://jellyfin.org/docs/general/server/metadata/nfo/) 规定多值 genre、tag 及 tvshow/episode 文件约定。[Emby 浏览接口](https://dev.emby.media/doc/restapi/Browsing-the-Library.html) 用于递归 Movie/Series 检索；两种服务器仍分别保留契约测试。

Jellyfin Android TV 是成熟客户端参考，但其 [GPL 许可](https://github.com/jellyfin/jellyfin-androidtv/blob/master/LICENSE)、服务模型和 Android 专属交互不适合整项目迁入本项目的 KMP 多源核心。这次复用现有 SQLDelight 索引、Ktor 适配器与 Compose/TV 组件，只补元数据字段、影视聚合和入口；不增加另一套客户端或依赖。

观看功能接口参考：[Emby 观看状态](https://dev.emby.media/doc/restapi/Playback-Check-ins.html)、[Emby 用户资料接口](https://dev.emby.media/reference/RestAPI/UserLibraryService.html)、[Jellyfin 官方 Python 客户端](https://github.com/jellyfin/jellyfin-apiclient-python/blob/master/jellyfin_apiclient_python/api.py)。Emby 续看使用 IsResumable 查询，Jellyfin 使用 Items/Resume；没有把两者所有端点强行视为同一协议。
