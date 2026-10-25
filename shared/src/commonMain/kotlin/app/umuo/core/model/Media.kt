package app.umuo.core.model

import kotlinx.serialization.Serializable

@Serializable enum class SourceKind { EMBY, JELLYFIN, WEBDAV }
@Serializable data class SourceConnection(
    val id: String, val name: String, val kind: SourceKind, val baseUrl: String,
    val username: String = "", val userId: String = ""
)
@Serializable data class MediaId(val sourceId: String, val value: String)
@Serializable enum class MediaKind { DIRECTORY, MOVIE, SERIES, EPISODE, VIDEO }
@Serializable enum class MetadataOrigin { NONE, NFO, ONLINE, SERVER }
@Serializable data class MediaItem(
    val id: MediaId, val title: String, val kind: MediaKind = MediaKind.VIDEO,
    val path: String = "", val artwork: String? = null, val overview: String = "",
    val year: Int? = null, val series: String? = null, val season: Int? = null,
    val episode: Int? = null, val durationMs: Long = 0, val resumeMs: Long = 0,
    val fingerprint: String = "", val metadataId: String? = null, val backdrop: String? = null,
    val metadataOrigin: MetadataOrigin = MetadataOrigin.NONE,
    val genres: List<String> = emptyList(), val tags: List<String> = emptyList(),
    val collection: String? = null, val collections: List<String> = emptyList(), val seriesKey: String? = null,
    val favorite: Boolean? = null, val watched: Boolean? = null
) {
    val hasMetadata: Boolean get() = metadataOrigin != MetadataOrigin.NONE || metadataId != null
    val isFolder: Boolean get() = kind == MediaKind.DIRECTORY || kind == MediaKind.SERIES }
data class MediaPage(val items: List<MediaItem>, val nextOffset: Int? = null)
data class SourceCapabilities(
    val search: Boolean = false, val serverProgress: Boolean = false,
    val serverTranscoding: Boolean = false, val fileTree: Boolean = false
)
data class MediaCredentials(val username: String, val password: String) {
    override fun toString(): String = "MediaCredentials(<redacted>)"
}
interface CredentialStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
}
