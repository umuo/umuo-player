package app.umuo.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VideoScaleMode(val label: String) {
    FIT("适应"), ZOOM("铺满"), STRETCH("拉伸");
    fun next(): VideoScaleMode = entries[(ordinal + 1) % entries.size]
}

enum class SubtitleSize(val label: String, val scale: Float) {
    SMALL("小", 0.85f), NORMAL("标准", 1.0f), LARGE("大", 1.25f), EXTRA_LARGE("特大", 1.5f)
}

enum class SubtitleBackground(val label: String) {
    NONE("透明描边"), SCRIM("半透黑底"), WARM("暖黄字幕")
}

data class GestureSettings(
    val seekSeconds: Int = 10,
    val sensitivity: Float = 1f,
    val holdSpeed: Float = 2f,
    val autoPlayNext: Boolean = true,
    val scaleMode: VideoScaleMode = VideoScaleMode.FIT,
    val subtitleSize: SubtitleSize = SubtitleSize.NORMAL,
    val subtitleBottomPercent: Int = 3,
    val subtitleBackground: SubtitleBackground = SubtitleBackground.NONE
)

class PlaybackPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(GestureSettings(
        preferences.getInt("seekSeconds", 10).takeIf { it in listOf(5, 10, 30) } ?: 10,
        preferences.getFloat("sensitivity", 1f).takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f,
        preferences.getFloat("holdSpeed", 2f).takeIf { it in listOf(1.5f, 2f, 3f) } ?: 2f,
        preferences.getBoolean("autoPlayNext", true),
        runCatching { VideoScaleMode.valueOf(preferences.getString("scaleMode", null).orEmpty()) }.getOrDefault(VideoScaleMode.FIT),
        runCatching { SubtitleSize.valueOf(preferences.getString("subtitleSize", null).orEmpty()) }.getOrDefault(SubtitleSize.NORMAL),
        preferences.getInt("subtitleBottomPercent", 3).coerceIn(1, 20),
        runCatching { SubtitleBackground.valueOf(preferences.getString("subtitleBackground", null).orEmpty()) }.getOrDefault(SubtitleBackground.NONE)
    ))
    val state = mutable.asStateFlow()
    fun update(settings: GestureSettings) {
        require(
            settings.seekSeconds in listOf(5, 10, 30) &&
                settings.sensitivity.isFinite() &&
                settings.sensitivity in .5f..2f &&
                settings.holdSpeed in listOf(1.5f, 2f, 3f) &&
                settings.subtitleBottomPercent in 1..20
        )
        preferences.edit()
            .putInt("seekSeconds", settings.seekSeconds)
            .putFloat("sensitivity", settings.sensitivity)
            .putFloat("holdSpeed", settings.holdSpeed)
            .putBoolean("autoPlayNext", settings.autoPlayNext)
            .putString("scaleMode", settings.scaleMode.name)
            .putString("subtitleSize", settings.subtitleSize.name)
            .putInt("subtitleBottomPercent", settings.subtitleBottomPercent)
            .putString("subtitleBackground", settings.subtitleBackground.name)
            .apply()
        mutable.value = settings
    }
}
