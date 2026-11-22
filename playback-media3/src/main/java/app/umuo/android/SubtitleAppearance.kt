@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.umuo.android

import android.graphics.Color
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/** 手机与 TV 共用：字幕下移，描边或半透底保证亮色画面下仍可读。 */
internal fun SubtitleView.applyCinemaAppearance(
    size: SubtitleSize = SubtitleSize.NORMAL,
    bottomPercent: Int = 3,
    background: SubtitleBackground = SubtitleBackground.NONE
) {
    val style = when (background) {
        SubtitleBackground.NONE -> CaptionStyleCompat(
            Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null
        )
        SubtitleBackground.SCRIM -> CaptionStyleCompat(
            Color.WHITE, Color.argb(155, 0, 0, 0), Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, null
        )
        SubtitleBackground.WARM -> CaptionStyleCompat(
            0xFFFFE082.toInt(), Color.TRANSPARENT, Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null
        )
    }
    setStyle(style)
    // 覆盖文本自带的背景/窗口样式；保留 Cue 显式位置，避免破坏标牌字幕布局。
    setApplyEmbeddedStyles(false)
    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * size.scale)
    setBottomPaddingFraction(bottomPercent.coerceIn(1, 20) / 100f)
}
