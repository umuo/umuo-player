package app.umuo.android

import java.util.Locale

internal data class SubtitleName(val label: String, val detail: String?)
private val subtitleExtension = Regex("\\.(srt|vtt|ass|ssa|ttml|sup|sub)$", RegexOption.IGNORE_CASE)
private val subtitleFlags = mapOf("forced" to "强制字幕", "default" to "默认", "sdh" to "听障辅助", "cc" to "听障辅助", "commentary" to "解说", "external" to "外置")
private val languageAliases = mapOf(
    "chs" to "zh-Hans", "zhs" to "zh-Hans", "cht" to "zh-Hant", "zht" to "zh-Hant",
    "chi" to "zh", "zho" to "zh", "chinese" to "zh", "eng" to "en", "english" to "en",
    "jpn" to "ja", "japanese" to "ja", "kor" to "ko", "korean" to "ko", "fre" to "fr", "fra" to "fr",
    "french" to "fr", "ger" to "de", "deu" to "de", "german" to "de", "spa" to "es", "spanish" to "es", "russian" to "ru", "portuguese" to "pt", "italian" to "it", "arabic" to "ar",
    "thai" to "th", "vietnamese" to "vi", "indonesian" to "id", "hindi" to "hi", "dutch" to "nl", "turkish" to "tr"
)
private val validLanguageTag = Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})*", RegexOption.IGNORE_CASE)
private val filenameParts = Regex("[._\\s]+")
private val labelWords = Regex("[^\\p{L}0-9-]+")
private fun chineseLanguage(raw: String?): String? {
    val value = raw?.trim()?.replace('_', '-')?.lowercase().orEmpty()
    if (value in setOf("", "und", "unknown", "zxx")) return null
    if (value == "mul") return "多语言"
    val tag = languageAliases[value] ?: value
    if (tag.startsWith("zh-Hans", true)) return "简体中文"
    if (tag.startsWith("zh-Hant", true)) return "繁体中文"
    return when (tag.lowercase()) {
        "zh-hans", "zh-cn", "zh-sg" -> "简体中文"
        "zh-hant", "zh-tw", "zh-hk", "zh-mo" -> "繁体中文"
        "zh" -> "中文"
        else -> {
            if (!validLanguageTag.matches(tag)) return null
            val locale = Locale.forLanguageTag(tag)
            locale.getDisplayLanguage(Locale.SIMPLIFIED_CHINESE).takeIf {
                it.isNotBlank() && it != locale.language && it.any { c -> c.code in 0x3400..0x9fff }
            }
        }
    }
}

/** 中文主标题描述语言/用途；原文件名作为辅助信息，不猜测或翻译影视名称。 */
internal fun subtitleName(language: String?, rawLabel: String?, mimeType: String?, index: Int,
                          external: Boolean, forced: Boolean, default: Boolean, hearingImpaired: Boolean): SubtitleName {
    val raw = rawLabel?.trim().orEmpty().takeUnless { it.contains("://") || it.startsWith("application/") || it.startsWith("text/") }.orEmpty()
    val file = subtitleExtension.containsMatchIn(raw)
    val flags = linkedSetOf<String>()
    val hint = if (file) {
        val parts = raw.substringBeforeLast('.').split(filenameParts).toMutableList()
        while (parts.isNotEmpty() && parts.last().lowercase() in subtitleFlags) {
            flags.add(subtitleFlags.getValue(parts.removeAt(parts.lastIndex).lowercase()))
        }
        val last = parts.lastOrNull().orEmpty()
        if (parts.size >= 2 && parts[parts.lastIndex - 1].equals("zh", true) &&
            last.lowercase() in setOf("cn", "tw", "hk", "sg", "mo", "hans", "hant")) "zh-$last" else last
    } else raw
    val lower = hint.lowercase().replace('_', '-')
    val words = lower.split(labelWords).filter { it.isNotBlank() }
    if (!file) subtitleFlags.forEach { (key, value) -> if (key in words) flags.add(value) }
    if (!file && ("hearing impaired" in lower || "hearing-impaired" in lower || "closed captions" in lower)) flags.add("听障辅助")
    val inferred = when {
        !file && ("chinese" in lower || "chi" in words || "zho" in words) && ("english" in lower || "eng" in words) -> "中英双语"
        lower.contains("简体") || lower.contains("simplified") -> "简体中文"
        lower.contains("繁體") || lower.contains("繁体") || lower.contains("traditional") -> "繁体中文"
        else -> chineseLanguage(hint) ?: words.firstNotNullOfOrNull { languageAliases[it]?.let(::chineseLanguage) }
    }
    val declared = chineseLanguage(language)
    val name = if (declared == "中文" && inferred in setOf("简体中文", "繁体中文", "中英双语")) inferred else declared ?: inferred
    if (forced) flags.add("强制字幕")
    if (default) flags.add("默认")
    if (hearingImpaired) flags.add("听障辅助")
    val format = when (mimeType) {
        "application/x-subrip" -> "SRT"
        "text/vtt" -> "VTT"
        "text/x-ssa" -> "ASS/SSA"
        "application/ttml+xml" -> "TTML"
        "application/pgs" -> "图形字幕"
        "application/dvbsubs", "application/vobsub" -> "图形字幕"
        else -> subtitleExtension.find(raw)?.groupValues?.get(1)?.uppercase()
    }
    val label = (listOf(name ?: "字幕 ${index + 1}") + listOfNotNull(format) + flags + if (external) listOf("外置") else emptyList()).distinct().joinToString(" · ")
    val descriptorWords = languageAliases.keys + subtitleFlags.keys + setOf("subtitles", "subtitle", "track", "simplified", "traditional", "closed", "captions", "hearing", "impaired", "srt", "vtt", "ass", "ssa", "pgs", "pgssub")
    val customTitle = !words.all { it in descriptorWords || chineseLanguage(it) != null }
    val detail = raw.takeIf { it.isNotEmpty() && (file || name == null || customTitle) && it != name }
    return SubtitleName(label, detail)
}
