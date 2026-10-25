package app.umuo.core.metadata

import app.umuo.core.model.*
import app.umuo.core.network.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

interface MetadataProvider {
    suspend fun search(title: String, year: Int?, television: Boolean): List<MediaItem>
    suspend fun details(id: String, television: Boolean): MediaItem
}
object FilenameParser {
    fun parse(item: MediaItem): MediaItem {
        val stem = item.title.substringBeforeLast('.')
        val episode = Regex("""(?i)(?:(.+?)[ ._-]+)?s(\d{1,2})e(\d{1,3})""").find(stem)
        val year = Regex("""(?:^|[ ._(])(19\d{2}|20\d{2})(?:[ ._)]|$)""").find(stem)
        val title = (episode?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: year?.let { stem.substring(0, it.range.first) } ?: stem)
            .replace('.', ' ').replace('_', ' ').trim(' ', '-', '(')
        return item.copy(title = title.ifBlank { stem }, year = year?.groupValues?.get(1)?.toIntOrNull(),
            kind = if (episode != null) MediaKind.EPISODE else MediaKind.MOVIE,
            series = if (episode != null) title else null, season = episode?.groupValues?.get(2)?.toIntOrNull(), episode = episode?.groupValues?.get(3)?.toIntOrNull())
    }
}
object NfoParser {
    fun apply(item: MediaItem, xml: String): MediaItem {
        val root = parseXml(xml)
        require(root.name in setOf("movie", "episodedetails", "tvshow")) { "不支持的 NFO 类型" }
        fun values(name: String) = root.children.filter {it.name==name}.map {it.text.trim()}.filter {it.isNotBlank()}.distinct()
        val collections=root.children.filter {it.name=="set"}.map {it.value("name").ifBlank {it.text.trim()}}.filter {it.isNotBlank()}.distinct()
        return item.copy(metadataOrigin = MetadataOrigin.NFO,
            genres = values("genre"), tags = values("tag"),
            collection = collections.firstOrNull(),collections=collections,
            title = root.value("title").ifBlank { item.title }, overview = root.value("plot"),
            year = root.value("year").toIntOrNull() ?: item.year,
            kind = when(root.name) { "episodedetails" -> MediaKind.EPISODE; "tvshow" -> MediaKind.SERIES; else -> MediaKind.MOVIE },
            series = root.value("showtitle").ifBlank { item.series }, season = root.value("season").toIntOrNull() ?: item.season,
            episode = root.value("episode").toIntOrNull() ?: item.episode,
            metadataId = root.children.firstOrNull { it.name == "uniqueid" && it.attributes["type"] == "tmdb" }?.text?.trim()?.let { "tmdb:$it" })
    }
}
/** 仅在用户明确启用并提供 token 后构造；绝不在扫描器中隐式启用。 */
class TmdbProvider(private val client: HttpClient, private val token: String, private val language: String = "zh-CN") : MetadataProvider {
    init { require(token.isNotBlank()) }
    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JsonObject {
        val response = client.get("https://api.themoviedb.org/3/$path") {
            header("Authorization", "Bearer $token")
            parameter("language", language)
            params.forEach { (k, v) -> parameter(k, v) }
        }
        if(response.status.value !in 200..299)throw SourceFailure(response.status.value,"元数据服务暂不可用（${response.status.value}）")
        return wireJson.parseToJsonElement(response.bodyAsText()).jsonObject
    }
    override suspend fun search(title: String, year: Int?, television: Boolean): List<MediaItem> =
        get("search/${if(television) "tv" else "movie"}", buildMap { put("query", title); year?.let { put(if(television) "first_air_date_year" else "year", it.toString()) } })
            .rows("results").map { map(it, television) }
    override suspend fun details(id: String, television: Boolean) = map(get("${if(television) "tv" else "movie"}/${id.removePrefix("tmdb:")}"), television)
    private fun map(row: JsonObject, tv: Boolean): MediaItem = MediaItem(
        MediaId("tmdb", row.text("id")), row.text(if(tv) "name" else "title"), if(tv) MediaKind.SERIES else MediaKind.MOVIE,
        artwork = row.text("poster_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" },
        backdrop = row.text("backdrop_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w1280$it" },
        metadataOrigin = MetadataOrigin.ONLINE,
        genres = row.rows("genres").map {it.text("name")}.filter {it.isNotBlank()},
        collection = (row["belongs_to_collection"] as? JsonObject)?.text("name")?.takeIf {it.isNotBlank()},
        overview = row.text("overview"), year = row.text(if(tv) "first_air_date" else "release_date").take(4).toIntOrNull(), metadataId = "tmdb:${row.text("id")}")
}
