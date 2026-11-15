package app.umuo.core.source.webdav

import app.umuo.core.model.*
import app.umuo.core.library.*
import app.umuo.core.network.*
import app.umuo.core.playback.*
import app.umuo.core.source.*
import app.umuo.core.source.mediaserver.subtitleMime
import app.umuo.core.metadata.parseXml
import io.ktor.client.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val videoExtensions = setOf("mp4", "mkv", "m4v", "avi", "mov", "ts", "m2ts", "webm")
data class DavEntry(val url: String, val name: String, val directory: Boolean, val fingerprint: String) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}
class WebDavProvider(
    initial: SourceConnection, private val baseClient: HttpClient, private val secrets: CredentialStore,
    private val ownsBaseClient: Boolean = false
) : MediaSourceProvider {
    override var connection = initial
        private set
    private var transport = createTransport(initial)
    private fun createTransport(c: SourceConnection) = HttpTransport(normalizedBase(c.baseUrl), authenticatedClient(baseClient, c, secrets))
    override val capabilities = SourceCapabilities(fileTree = true)
    override suspend fun authenticate(credentials: MediaCredentials): SourceConnection {
        secrets.write("${connection.id}:password", credentials.password)
        transport.close()
        connection = connection.copy(username = credentials.username)
        transport = createTransport(connection)
        listEntries(connection.baseUrl)
        return connection
    }
    private fun validate(url: String) {
        val root = Url(normalizedBase(connection.baseUrl))
        val target = Url(url)
        require(origin(url) == origin(connection.baseUrl) && target.encodedPath.startsWith(root.encodedPath)) { "WebDAV 路径超出配置目录" }
        require(target.encodedPath.split('/').none { it.decodeURLPart() in setOf("..", ".") || it.decodeURLPart().contains('/') }) { "WebDAV 路径含无效片段" }
    }
    suspend fun listEntries(directory: String): List<DavEntry> {
        validate(normalizedBase(directory))
        val xml = transport.request(directory, HttpMethod("PROPFIND"),
            """<d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getetag/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>""",
            mapOf("Depth" to "1", "Content-Type" to "application/xml; charset=utf-8")).bodyAsText()
        return withContext(Dispatchers.Default) {
            parseListing(xml, directory).filter { entry ->
                runCatching { validate(entry.url) }.isSuccess && entry.url.trimEnd('/') != directory.trimEnd('/')
            }
        }
    }
    internal fun parseListing(xml: String, directory: String): List<DavEntry> {
        val root = parseXml(xml)
        require(root.name == "multistatus" && root.ns == "DAV:") { "不是 WebDAV 多状态响应" }
        return root.children.filter { it.name == "response" && it.ns == "DAV:" }.mapNotNull { row ->
            val prop = row.children.filter { it.name == "propstat" }.firstOrNull { it.value("status").contains(" 200 ") }?.child("prop") ?: return@mapNotNull null
            val href = row.value("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = resolveUrl(directory, href)
            val name = prop.value("displayname").ifBlank { Url(url).encodedPath.trimEnd('/').substringAfterLast('/').decodeURLPart() }
            val folder = prop.child("resourcetype")?.child("collection") != null
            DavEntry(if (folder) url.trimEnd('/') + "/" else url, name, folder,
                listOf(prop.value("getetag"), prop.value("getcontentlength"), prop.value("getlastmodified")).joinToString("|"))
        }.distinctBy { it.url }
    }
    fun item(entry: DavEntry) = MediaItem(MediaId(connection.id, entry.url), entry.name,
        if (entry.directory) MediaKind.DIRECTORY else MediaKind.VIDEO, path = entry.url, fingerprint = entry.fingerprint)
    override suspend fun browse(parent: String?, offset: Int): MediaPage {
        val all = listEntries(parent ?: normalizedBase(connection.baseUrl)).filter { it.directory || it.extension in videoExtensions }
            .sortedWith(compareBy<DavEntry> { !it.directory }.thenBy { it.name.lowercase() })
        return MediaPage(all.drop(offset).take(60).map(::item), (offset + 60).takeIf { it < all.size })
    }
    override suspend fun details(id: MediaId): MediaItem {
        require(id.sourceId == connection.id); validate(id.value)
        return item(listEntries(id.value.substringBeforeLast('/') + "/").first { it.url == id.value })
    }
    suspend fun readText(url: String): String {
        validate(url)
        return transport.text(url)
    }

    override suspend fun resolve(request: PlaybackRequest): PlaybackPlan {
        require(request.item.id.sourceId == connection.id)
        val url = request.item.path
        validate(url)
        val anonymous=HttpClient(baseClient.engine) {
            followRedirects=false;expectSuccess=false
            install(HttpTimeout) {requestTimeoutMillis=30_000;connectTimeoutMillis=15_000}
        }
        val probe=try {transport.probeMedia(url,anonymous)} finally {anonymous.close()}
        val seekable=probe.seekable
        val stem = Url(url).encodedPath.substringAfterLast('/').substringBeforeLast('.').decodeURLPart()
        val subtitles = listEntries(url.substringBeforeLast('/') + "/").filter {
            !it.directory && it.extension in setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp") &&
                (it.name.substringBeforeLast('.') == stem || it.name.startsWith("$stem.", true))
        }.map { Subtitle(it.url, it.name, it.url, subtitleMime(it.extension)) }
        return PlaybackPlan(request.item, probe.uri, credentialKey = connection.id, subtitles = subtitles,
            startPositionMs = if (seekable) request.item.resumeMs else 0, seekable = seekable)
    }
    override suspend fun report(plan: PlaybackPlan, event: PlaybackEvent) = Unit
    fun forScan(guard: ScanTrafficGuard): WebDavProvider = WebDavProvider(connection,
        baseClient.config {install(ScanTrafficPlugin) {this.guard=guard}},secrets,true)
    override fun close() {
        transport.close()
        if(ownsBaseClient)baseClient.close()
    }
}
