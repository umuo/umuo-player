package app.umuo.core.source.mediaserver

import app.umuo.core.model.*
import app.umuo.core.library.LibraryCatalog
import app.umuo.core.network.*
import app.umuo.core.playback.*
import app.umuo.core.source.*
import io.ktor.client.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/** 只共享已确认相同的 REST 流程，认证头与服务根地址由具体适配器决定。 */
abstract class MediaServerProvider(
    initial: SourceConnection, baseClient: HttpClient, private val secrets: CredentialStore
) : MediaSourceProvider {
    final override var connection: SourceConnection = initial
        private set
    protected abstract val tokenHeader: String
    protected abstract fun resumeRoute(): String
    protected abstract fun apiPath(path: String): String
    private val auth = """MediaBrowser Client="Umuo Player", Device="Umuo", DeviceId="${initial.id}", Version="0.1.0""""
    private val transport = HttpTransport(normalizedBase(initial.baseUrl), authenticatedClient(baseClient, initial, secrets)) {
        buildMap {
            put("X-Emby-Authorization", auth)
            secrets.read("${initial.id}:token")?.let { put(tokenHeader, it) }
        }
    }
    override val capabilities = SourceCapabilities(search = true, serverProgress = true, serverTranscoding = true)
    override suspend fun authenticate(credentials: MediaCredentials): SourceConnection {
        val result = transport.json(apiPath("Users/AuthenticateByName"), HttpMethod.Post, buildJsonObject {
            put("Username", credentials.username); put("Pw", credentials.password)
        })
        val token = result.text("AccessToken")
        val user = (result["User"] as? JsonObject)?.text("Id").orEmpty()
        require(token.isNotBlank() && user.isNotBlank()) { "服务器没有返回有效的会话" }
        secrets.write("${connection.id}:token", token)
        connection = connection.copy(username = credentials.username, userId = user)
        return connection
    }
    override suspend fun browse(parent: String?, offset: Int): MediaPage {
        val endpoint = if (parent == null) "Users/${connection.userId}/Views" else "Users/${connection.userId}/Items"
        val query = listOfNotNull(parent?.let { "ParentId=${it.encodeURLParameter()}" }, "StartIndex=$offset", "Limit=60", "Fields=Overview,MediaSources,Genres,Tags", "SortBy=SortName", "SortOrder=Ascending").joinToString("&")
        val result = transport.json(apiPath(endpoint) + "?" + query)
        val items = result.rows("Items").map(::mapItem)
        val next = if (items.isNotEmpty() && offset + items.size < result.long("TotalRecordCount")) offset + items.size else null
        return MediaPage(items, next)
    }
    suspend fun catalog(type: MediaKind, offset: Int = 0): MediaPage {
        require(type==MediaKind.MOVIE || type==MediaKind.SERIES)
        return listing(ServerLibraryQuery(if(type==MediaKind.MOVIE)ServerView.MOVIES else ServerView.SERIES),offset)
    }
    suspend fun listing(query: ServerLibraryQuery, offset: Int = 0): MediaPage {
        if(query.view==ServerView.LIBRARIES && query.search.isBlank())return browse(null,offset)
        val base="Users/${connection.userId}/Items"
        val route=when(query.view) {
            ServerView.RESUME->resumeRoute()
            ServerView.NEXT_UP->"Shows/NextUp?UserId=${connection.userId.encodeURLParameter()}&EnableTotalRecordCount=true"
            else->base
        }
        val params=buildList {
            add("StartIndex=$offset");add("Limit=60");add("Fields=Overview,MediaSources,Genres,Tags");add("EnableUserData=true")
            if(query.view!=ServerView.NEXT_UP && query.view!=ServerView.RESUME) {
                add("Recursive=true")
                add("IncludeItemTypes="+when(query.view) {
                    ServerView.MOVIES->"Movie";ServerView.SERIES->"Series";ServerView.COLLECTIONS->"BoxSet";else->"Movie,Series,Episode"
                })
                if(query.view==ServerView.FAVORITES)add("Filters=IsFavorite")
                add("SortBy="+if(query.view==ServerView.LATEST)"DateCreated" else "SortName")
                add("SortOrder="+if(query.view==ServerView.LATEST)"Descending" else "Ascending")
            }
            query.search.trim().takeIf {it.isNotEmpty()}?.let {add("SearchTerm=${it.encodeURLParameter()}")}
        }.joinToString("&")
        val result=transport.json(apiPath(route)+(if('?' in route)"&" else "?")+params)
        val items=result.rows("Items").map(::mapItem)
        return MediaPage(items,if(items.isNotEmpty() && offset+items.size<result.long("TotalRecordCount"))offset+items.size else null)
    }
    suspend fun setFavorite(id: MediaId, value: Boolean) = updateUserFlag(id,"FavoriteItems",value)
    suspend fun setWatched(id: MediaId, value: Boolean) = updateUserFlag(id,"PlayedItems",value)
    private suspend fun updateUserFlag(id: MediaId, kind: String, value: Boolean) {
        require(id.sourceId==connection.id) {"媒体不属于当前来源"}
        transport.json(apiPath("Users/${connection.userId}/$kind/${id.value.encodeURLPathPart()}"),if(value)HttpMethod.Post else HttpMethod.Delete)
    }
    override suspend fun details(id: MediaId): MediaItem {
        require(id.sourceId == connection.id)
        return mapItem(transport.json(apiPath("Users/${connection.userId}/Items/${id.value.encodeURLPathPart()}")))
    }
    suspend fun seriesEpisodes(item: MediaItem): List<MediaItem> {
        require(item.id.sourceId == connection.id)
        val seriesId = item.seriesKey?.takeIf { it.isNotBlank() } ?: return emptyList()
        val route = apiPath("Users/${connection.userId}/Items") +
            "?ParentId=${seriesId.encodeURLParameter()}&Recursive=true&IncludeItemTypes=Episode" +
            "&Fields=Overview,MediaSources,Genres,Tags&EnableUserData=true" +
            "&SortBy=ParentIndexNumber,IndexNumber,SortName&SortOrder=Ascending&Limit=500"
        return transport.json(route).rows("Items").map(::mapItem)
            .sortedWith(compareBy<MediaItem> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })
    }
    suspend fun nextEpisode(item: MediaItem): MediaItem? = LibraryCatalog.findNextEpisode(item, seriesEpisodes(item))
    private fun mapItem(row: JsonObject): MediaItem {
        val id = row.text("Id")
        val userData = row["UserData"] as? JsonObject
        return MediaItem(MediaId(connection.id, id), row.text("Name"),
            when {
                row.text("Type") == "Series" -> MediaKind.SERIES
                row.flag("IsFolder") -> MediaKind.DIRECTORY
                row.text("Type") == "Movie" -> MediaKind.MOVIE
                row.text("Type") == "Episode" -> MediaKind.EPISODE
                else -> MediaKind.VIDEO
            }, path = id,
            artwork = if ((row["ImageTags"] as? JsonObject)?.text("Primary").isNullOrBlank()) null else
                resolveUrl(connection.baseUrl, apiPath("Items/$id/Images/Primary") + "?maxWidth=480"),
            backdrop = (row["BackdropImageTags"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let {
                resolveUrl(connection.baseUrl, apiPath("Items/$id/Images/Backdrop/0") + "?maxWidth=1600")
            }, overview = row.text("Overview"), year = row.long("ProductionYear").toInt().takeIf { it > 0 },
            series = row.text("SeriesName").ifBlank { null },
            seriesKey = row.text("SeriesId").ifBlank { null },
            season = row.long("ParentIndexNumber").toInt().takeIf {row.text("Type")=="Episode"},
            episode = row.long("IndexNumber").toInt().takeIf {row.text("Type")=="Episode"}, durationMs = row.long("RunTimeTicks") / 10_000,
            resumeMs = (userData?.long("PlaybackPositionTicks") ?: 0) / 10_000,
            favorite=userData?.flag("IsFavorite"),watched=userData?.flag("Played"),
            metadataOrigin = MetadataOrigin.SERVER,
            genres = (row["Genres"] as? JsonArray)?.mapNotNull {(it as? JsonPrimitive)?.contentOrNull}.orEmpty(),
            tags = (row["Tags"] as? JsonArray)?.mapNotNull {(it as? JsonPrimitive)?.contentOrNull}.orEmpty())
    }
    override suspend fun resolve(request: PlaybackRequest): PlaybackPlan {
        require(request.item.id.sourceId == connection.id)
        val result = transport.json(apiPath("Items/${request.item.id.value}/PlaybackInfo"), HttpMethod.Post,
            buildJsonObject {
                put("UserId", connection.userId); put("StartTimeTicks", request.item.resumeMs * 10_000)
                put("IsPlayback", true); put("EnableDirectPlay", !request.forceTranscode); put("EnableDirectStream", !request.forceTranscode); put("MaxStreamingBitrate", request.profile.maxBitrate)
                put("DeviceProfile", buildJsonObject {
                    put("Name", "Umuo Media3")
                    put("MaxStreamingBitrate", request.profile.maxBitrate)
                    put("DirectPlayProfiles", buildJsonArray {
                        if (!request.forceTranscode) request.profile.directPlayProfiles.forEach { p -> add(buildJsonObject {
                            put("Container", p.containers); put("Type", "Video"); put("VideoCodec", p.videoCodecs); put("AudioCodec", p.audioCodecs)
                        }) }
                    })
                    put("TranscodingProfiles", buildJsonArray { add(buildJsonObject {
                        put("Container", "ts"); put("Type", "Video"); put("VideoCodec", "h264"); put("AudioCodec", "aac")
                        put("Protocol", "hls"); put("Context", "Streaming"); put("MaxAudioChannels", "2")
                    }) })
                })
            })
        val source = result.rows("MediaSources").firstOrNull { !request.forceTranscode && (it.flag("SupportsDirectPlay") || it.flag("SupportsDirectStream")) }
            ?: result.rows("MediaSources").firstOrNull() ?: error("服务器没有可用媒体版本")
        val sourceId = source.text("Id")
        val direct = !request.forceTranscode && source.flag("SupportsDirectPlay")
        val streamUrl = if (direct) apiPath("Videos/${request.item.id.value}/stream") + "?Static=true&MediaSourceId=${sourceId.encodeURLParameter()}" else
            source.text("TranscodingUrl").ifBlank { source.text("DirectStreamUrl") }.ifBlank { error("服务器未提供兼容播放地址，请检查转码能力") }
        val parameters = Url(resolveUrl(connection.baseUrl, streamUrl)).parameters
        val copiedVideo = parameters.entries().any { (key, values) -> key.equals("VideoCodec", true) && "copy" in values }
        val mode = when {
            direct -> PlaybackMode.DIRECT
            !request.forceTranscode && (streamUrl == source.text("DirectStreamUrl") || copiedVideo) -> PlaybackMode.REMUX
            else -> PlaybackMode.TRANSCODE
        }
        val subtitles = source.rows("MediaStreams").filter { it.text("Type") == "Subtitle" && it.flag("IsExternal") }.mapNotNull { track ->
            val url = track.text("DeliveryUrl").ifBlank { null } ?: return@mapNotNull null
            val codec = track.text("Codec")
            Subtitle(track.text("Index"), track.text("DisplayTitle").ifBlank { track.text("Language") },
                resolveUrl(connection.baseUrl, url), subtitleMime(codec), track.text("Language"))
        }
        return PlaybackPlan(request.item, resolveUrl(connection.baseUrl, streamUrl), mode,
            if (streamUrl.substringBefore('?').endsWith(".m3u8")) "application/x-mpegURL" else null,
            connection.id, subtitles, result.text("PlaySessionId"), sourceId, request.item.resumeMs)
    }
    override suspend fun report(plan: PlaybackPlan, event: PlaybackEvent) {
        val endpoint = when (event.kind) { PlaybackEventKind.START -> "Sessions/Playing"; PlaybackEventKind.PROGRESS -> "Sessions/Playing/Progress"; PlaybackEventKind.STOP -> "Sessions/Playing/Stopped" }
        transport.json(apiPath(endpoint), HttpMethod.Post, buildJsonObject {
            put("ItemId", plan.item.id.value); put("MediaSourceId", plan.mediaSourceId); put("PlaySessionId", plan.sessionId)
            put("PositionTicks", event.positionMs * 10_000); put("IsPaused", event.paused)
            put("PlayMethod", when(plan.mode) { PlaybackMode.DIRECT -> "DirectPlay"; PlaybackMode.REMUX -> "DirectStream"; PlaybackMode.TRANSCODE -> "Transcode" })
            put("CanSeek", plan.seekable)
        })
    }
    override suspend fun closeSession(plan: PlaybackPlan) {
        if (plan.mode != PlaybackMode.DIRECT && !plan.sessionId.isNullOrBlank()) {
            transport.request(apiPath("Videos/ActiveEncodings") + "?DeviceId=${connection.id.encodeURLParameter()}&PlaySessionId=${plan.sessionId.encodeURLParameter()}", HttpMethod.Delete)
        }
    }
    override fun close() = transport.close()
}
fun subtitleMime(codec: String): String = when (codec.lowercase()) {
    "srt", "subrip" -> "application/x-subrip"
    "ass", "ssa" -> "text/x-ssa"
    "ttml", "dfxp" -> "application/ttml+xml"
    "vtt", "webvtt" -> "text/vtt"
    else -> "text/vtt"
}
class JellyfinProvider(c: SourceConnection, client: HttpClient, secrets: CredentialStore) : MediaServerProvider(c, client, secrets) {
    override val tokenHeader = "X-Emby-Token"
    override fun resumeRoute() = "Users/${connection.userId}/Items/Resume"
    override fun apiPath(path: String) = path
}
class EmbyProvider(c: SourceConnection, client: HttpClient, secrets: CredentialStore) : MediaServerProvider(c, client, secrets) {
    override val tokenHeader = "X-Emby-Token"
    override fun resumeRoute() = "Users/${connection.userId}/Items?Recursive=true&Filters=IsResumable&SortBy=DatePlayed&SortOrder=Descending"
    override fun apiPath(path: String) = if (connection.baseUrl.trimEnd('/').endsWith("/emby", true)) path else "emby/$path"
}
