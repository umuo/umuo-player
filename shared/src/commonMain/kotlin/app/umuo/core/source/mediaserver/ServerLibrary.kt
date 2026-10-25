package app.umuo.core.source.mediaserver

/** 面向观看者的服务器视图，不依赖客户端本地扫描结果。 */
enum class ServerView(val label: String) {
    LIBRARIES("媒体库"), MOVIES("电影"), SERIES("剧集"),
    RESUME("继续观看"), NEXT_UP("下一集"), LATEST("最近添加"),
    FAVORITES("我的收藏"), COLLECTIONS("合集")
}
data class ServerLibraryQuery(val view: ServerView = ServerView.LIBRARIES, val search: String = "")
