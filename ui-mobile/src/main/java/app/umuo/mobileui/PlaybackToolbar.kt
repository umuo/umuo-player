package app.umuo.mobileui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 窄屏分成两排，保留完整的触控区域，避免按钮被挤出画面。 */
@Composable
internal fun PlaybackToolbar(
    speed: Float,
    scaleLabel: String,
    onSpeed: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onCycleScale: () -> Unit,
    onEpisodes: (() -> Unit)? = null,
    onNextEpisode: (() -> Unit)? = null,
    onLock: () -> Unit,
    onPip: () -> Unit,
    onFullscreen: () -> Unit,
    landscape: Boolean
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 480.dp
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onSpeed,
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    modifier = if (compact) Modifier.weight(.9f) else Modifier
                ) {
                    Text("${speed}×", color = Color.White, fontSize = 12.sp, maxLines = 1)
                }
                if (compact) {
                    TextButton(onClick = onAudio, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.GraphicEq, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("音轨", color = Color.White, fontSize = 12.sp, maxLines = 1)
                    }
                    TextButton(onClick = onSubtitles, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Subtitles, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("字幕", color = Color.White, fontSize = 12.sp, maxLines = 1)
                    }
                    TextButton(onClick = onCycleScale, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.AspectRatio, "画面比例：$scaleLabel", tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(scaleLabel, color = Color.White, fontSize = 12.sp, maxLines = 1)
                    }
                } else {
                    IconButton(onClick = onAudio) { Icon(Icons.Rounded.GraphicEq, "音轨", tint = Color.White) }
                    IconButton(onClick = onSubtitles) { Icon(Icons.Rounded.Subtitles, "字幕", tint = Color.White) }
                    TextButton(onClick = onCycleScale, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Rounded.AspectRatio, "画面比例：$scaleLabel", tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(scaleLabel, color = Color.White, fontSize = 12.sp, maxLines = 1)
                    }
                    if (onEpisodes != null) {
                        IconButton(onClick = onEpisodes) { Icon(Icons.AutoMirrored.Rounded.ViewList, "选集", tint = Color.White) }
                    }
                    Spacer(Modifier.weight(1f))
                    PlaybackActions(onNextEpisode, onLock, onPip, onFullscreen, landscape)
                }
            }
            if (compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onEpisodes != null) {
                    IconButton(onClick = onEpisodes) { Icon(Icons.AutoMirrored.Rounded.ViewList, "选集", tint = Color.White) }
                }
                if (onNextEpisode != null) {
                    IconButton(onClick = onNextEpisode) { Icon(Icons.Rounded.SkipNext, "下一集", tint = Color.White) }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onLock) { Icon(Icons.Rounded.Lock, "锁定播放器", tint = Color.White) }
                IconButton(onClick = onPip) { Icon(Icons.Rounded.PictureInPictureAlt, "画中画", tint = Color.White) }
                FullscreenButton(onFullscreen, landscape)
            }
        }
    }
}

@Composable
private fun PlaybackActions(
    onNextEpisode: (() -> Unit)?,
    onLock: () -> Unit,
    onPip: () -> Unit,
    onFullscreen: () -> Unit,
    landscape: Boolean
) {
    if (onNextEpisode != null) {
        IconButton(onClick = onNextEpisode) { Icon(Icons.Rounded.SkipNext, "下一集", tint = Color.White) }
    }
    IconButton(onClick = onLock) { Icon(Icons.Rounded.Lock, "锁定播放器", tint = Color.White) }
    IconButton(onClick = onPip) { Icon(Icons.Rounded.PictureInPictureAlt, "画中画", tint = Color.White) }
    FullscreenButton(onFullscreen, landscape)
}

@Composable
private fun FullscreenButton(onClick: () -> Unit, landscape: Boolean) {
    IconButton(onClick = onClick) {
        Icon(if(landscape) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, "切换全屏", tint = Color.White)
    }
}
