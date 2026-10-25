package app.umuo.android

import kotlin.test.*

class SubtitleLabelsTest {
    private fun name(language: String? = null, title: String? = null, forced: Boolean = false,
                     default: Boolean = false, hearing: Boolean = false, external: Boolean = false) =
        subtitleName(language, title, "application/x-subrip", 0, external, forced, default, hearing)

    @Test fun translatesIsoAndRegionalLanguageNames() {
        assertTrue(name("eng").label.startsWith("英语"))
        assertTrue(name("zh-TW").label.startsWith("繁体中文"))
        assertTrue(name("zh-Hans-CN").label.startsWith("简体中文"))
        assertTrue(name("chi", "Chinese Simplified").label.startsWith("简体中文"))
        assertTrue(name("jpn").label.startsWith("日语"))
    }
    @Test fun translatesPurposeAndSelectionFlags() {
        val result = name("eng", "English SDH", forced = true, default = true)
        listOf("英语", "听障辅助", "强制字幕", "默认", "SRT").forEach {assertTrue(it in result.label)}
        assertTrue("听障辅助" in name("en", hearing = true).label)
    }
    @Test fun infersLanguageOnlyFromFilenameSuffix() {
        val result = name(title = "Movie.zh-Hant.forced.srt", external = true)
        assertTrue(result.label.startsWith("繁体中文"));assertTrue("强制字幕" in result.label)
        assertTrue("外置" in result.label);assertEquals("Movie.zh-Hant.forced.srt", result.detail)
        assertTrue(name(title = "The.English.Patient.1996.srt").label.startsWith("字幕 1"))
        assertTrue(name(title = "Movie_zh_CN_forced.srt").label.startsWith("简体中文"))
    }
    @Test fun unknownLanguageHasHonestFallbackAndKeepsCustomTitle() {
        val result = name("und", "Director commentary")
        assertTrue(result.label.startsWith("字幕 1"));assertEquals("Director commentary", result.detail)
        assertTrue(name("en", "Director commentary").label.startsWith("英语"))
    }
    @Test fun preservesCustomNamesWhileTranslatingKnownPurpose() {
        val result = name("en", "Director commentary")
        assertTrue("解说" in result.label);assertEquals("Director commentary", result.detail)
        assertNull(name("eng", "English").detail)
    }
    @Test fun recognizesBilingualAndHearingImpairedLabels() {
        assertTrue(name("chi", "Chinese / English").label.startsWith("中英双语"))
        assertTrue("听障辅助" in name("eng", "English Hearing Impaired").label)
    }
    @Test fun signedUrlsCannotAppearInLabelsOrDetails() {
        val result = name(title = "https://private.test/file.srt?token=secret")
        assertNull(result.detail);assertFalse("secret" in result.label);assertFalse("private" in result.label)
    }
}
