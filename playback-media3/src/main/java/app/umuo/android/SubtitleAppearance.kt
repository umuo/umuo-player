@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.umuo.android

import android.graphics.Color
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/** 手机与 TV 共用：字幕下移，透明底，描边保证亮色画面下仍可读。 */
internal fun SubtitleView.applyCinemaAppearance() {
    setStyle(CaptionStyleCompat(
        Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
        CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null
    ))
    // 覆盖文本自带的背景/窗口样式；保留 Cue 显式位置，避免破坏标牌字幕布局。
    setApplyEmbeddedStyles(false)
    setBottomPaddingFraction(0.03f)
}
