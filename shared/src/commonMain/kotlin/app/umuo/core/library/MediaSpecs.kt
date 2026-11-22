package app.umuo.core.library

import app.umuo.core.model.MediaItem
import app.umuo.core.model.MediaKind
import app.umuo.core.model.MetadataOrigin
import io.ktor.http.decodeURLPart

/** 从文件名、标签和来源元数据提取紧凑规格徽章，仅作本地视觉标注。 */
object MediaSpecs {
    private val containers = setOf("MKV", "MP4", "MOV", "WEBM", "TS", "M2TS", "M3U8", "AVI")
    private val resolutions = listOf("4K" to setOf("2160p", "4k", "uhd"), "1080P" to setOf("1080p", "fhd"), "720P" to setOf("720p"))
    private val dynamicRanges = listOf("DV" to setOf("dovi", "dv", "dolbyvision"), "HDR" to setOf("hdr", "hdr10", "hdr10+", "hlg"))
    private val codecs = listOf("AV1" to setOf("av1"), "HEVC" to setOf("hevc", "h265", "x265"), "H.264" to setOf("h264", "x264", "avc"))
    private val audioTags = listOf("Atmos" to setOf("atmos"), "DTS" to setOf("dts"), "FLAC" to setOf("flac"), "AAC" to setOf("aac"))

    private fun firstMatchingBadge(tokens: Set<String>, rules: List<Pair<String, Set<String>>>): String? =
        rules.firstOrNull { (_, aliases) -> aliases.any(tokens::contains) }?.first

    fun badges(item: MediaItem): List<String> {
        if (item.kind == MediaKind.DIRECTORY) return emptyList()
        val rawName = item.path.substringBefore('?').trimEnd('/').substringAfterLast('/')
        val decodedName = runCatching { rawName.decodeURLPart() }.getOrDefault(rawName)
        val fileTokens = decodedName.lowercase().split(Regex("[._\\-\\s\\[\\]()]+")).filter { it.isNotEmpty() }
        val tagTokens = item.tags.flatMap { it.lowercase().split(Regex("[._\\-\\s\\[\\]()]+")) }.filter { it.isNotEmpty() }
        val tokens = (fileTokens + tagTokens).toSet()

        val result = buildList {
            firstMatchingBadge(tokens, resolutions)?.let(::add)
            firstMatchingBadge(tokens, dynamicRanges)?.let(::add)
            firstMatchingBadge(tokens, codecs)?.let(::add)
            firstMatchingBadge(tokens, audioTags)?.let(::add)
            if (!item.isFolder) {
                val ext = decodedName.substringAfterLast('.', "").uppercase()
                if (ext in containers) add(ext)
            }
            when (item.metadataOrigin) {
                MetadataOrigin.NFO -> add("NFO")
                MetadataOrigin.ONLINE -> add("TMDB")
                MetadataOrigin.NONE, MetadataOrigin.SERVER -> Unit
            }
        }
        return result.distinct()
    }
}
