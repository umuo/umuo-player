package app.umuo.mobileui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umuo.android.PlayerModel
import app.umuo.android.VideoArtwork
import app.umuo.android.timeLabel
import app.umuo.core.model.MediaItem
import app.umuo.core.model.MediaKind
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable internal fun FeaturedFilm(
    item: MediaItem?, loader: ImageLoader, wide: Boolean, onPlay: (MediaItem) -> Unit, onDetail: (MediaItem) -> Unit,
    navigation: @Composable () -> Unit
) {
    var imageFailed by remember(item?.id, item?.backdrop) { mutableStateOf(false) }
    val density = LocalDensity.current
    var navigationHeight by remember { mutableStateOf(48.dp) }
    Box(
        Modifier.fillMaxWidth().let { base ->
            if (item != null) base.height(navigationHeight + if (imageFailed) 170.dp else if (wide) 300.dp else 220.dp) else base
        }
    ) {
        if (item != null) {
            AsyncImage(
                model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(item.backdrop)
                    .crossfade(240)
                    .build(),
                contentDescription = null, imageLoader = loader,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                onError = { imageFailed = true }, onSuccess = { imageFailed = false }
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to MobileStyle.Background,
                .3f to Color.Transparent,
                .6f to MobileStyle.Background.copy(alpha = .5f),
                1f to MobileStyle.Background
            )))
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                listOf(MobileStyle.Background.copy(alpha = .22f), Color.Transparent)
            )))
        }
        Box(Modifier.align(Alignment.TopStart).fillMaxWidth().onSizeChanged {
            navigationHeight = with(density) { it.height.toDp() }
        }) { navigation() }
        if (item != null) {
            Column(
                Modifier.align(Alignment.BottomStart).padding(horizontal = if (wide) 32.dp else 20.dp)
                    .padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(
                    item.title, color = MobileStyle.Text, fontSize = if (wide) 36.sp else 32.sp,
                    lineHeight = if (wide) 42.sp else 38.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "查看影片详情", onClick = { onDetail(item) })
                )
                val details = listOfNotNull(
                    item.year?.toString(), item.genres.firstOrNull(),
                    item.durationMs.takeIf { it > 0 }?.let(::durationText)
                )
                if (details.isNotEmpty()) Text(
                    details.joinToString(" · "), color = MobileStyle.Text.copy(alpha = .7f), fontSize = 12.sp, lineHeight = 18.sp
                )
                Button(
                    onClick = { onPlay(item) }, shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    modifier = Modifier.height(48.dp).semantics { contentDescription = "播放精选影片" }
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(23.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("播放", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable internal fun HomeSectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(start = 8.dp)) {
            Text(action, color = MobileStyle.Muted, fontSize = 12.sp)
            Icon(Icons.Rounded.ChevronRight, null, tint = MobileStyle.Muted, modifier = Modifier.size(17.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun ContinueWatching(
    model: PlayerModel, recent: List<MediaItem>, loader: ImageLoader, horizontalPadding: Dp,
    wide: Boolean, allowSeriesJump: Boolean = false, onShowAll: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.padding(horizontal = horizontalPadding)) {
            HomeSectionTitle("继续观看", "查看全部", onShowAll)
        }
        BoxWithConstraints {
            // 手机上两张卡完整呈现；宽屏保持横向队列，不把缩略图无限拉宽。
            val cardWidth = if (wide) 220.dp else ((maxWidth - horizontalPadding * 2 - 12.dp) / 2)
                .coerceAtLeast(132.dp)
            LazyRow(contentPadding = PaddingValues(horizontal = horizontalPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recent, key = { it.id.value }) { item ->
                    var menuOpen by remember(item.id) { mutableStateOf(false) }
                    Box(Modifier.width(cardWidth)) {
                        Column(
                            Modifier.fillMaxWidth().combinedClickable(
                                role = Role.Button,
                                onClickLabel = "继续播放 ${item.title}",
                                onClick = { model.play(item) },
                                onLongClick = { menuOpen = true }
                            )
                        ) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(0.5.dp, MobileStyle.Border.copy(alpha = .75f), RoundedCornerShape(8.dp))
                            ) {
                                VideoArtwork(model, item, loader, Modifier.fillMaxSize(), resume = true)
                                Box(
                                    Modifier.align(Alignment.BottomStart).padding(7.dp).size(26.dp)
                                        .background(Color.Black.copy(alpha = .55f), CircleShape), contentAlignment = Alignment.Center
                                ) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(21.dp)) }
                                Box(
                                    Modifier.align(Alignment.TopEnd).size(44.dp)
                                        .clickable(role = Role.Button, onClickLabel = "更多操作 ${item.title}") { menuOpen = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        Modifier.size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = .5f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.MoreVert, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { if (item.durationMs > 0) (item.resumeMs.toFloat() / item.durationMs).coerceIn(0f, 1f) else 0f },
                                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                                    color = MobileStyle.Accent, trackColor = Color.White.copy(alpha = .2f), gapSize = 0.dp, drawStopIndicator = {}
                                )
                            }
                            Text(if (item.kind == MediaKind.EPISODE) item.series ?: item.title else item.title,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, lineHeight = 18.sp,
                                fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 7.dp))
                            Text(
                                when {
                                    item.kind == MediaKind.EPISODE && item.episode != null -> "第${item.season ?: 1}季 · 第${item.episode}集"
                                    item.durationMs > item.resumeMs -> "剩余 ${((item.durationMs - item.resumeMs + 59_999) / 60_000)} 分钟"
                                    else -> "已观看 ${timeLabel(item.resumeMs)}"
                                }, color = MobileStyle.Muted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("继续播放") }, onClick = { menuOpen = false; model.play(item) })
                            DropdownMenuItem(text = { Text("从头播放") }, onClick = { menuOpen = false; model.play(item, resumeOverride = 0L) })
                            DropdownMenuItem(text = { Text("影片详情") }, onClick = { menuOpen = false; model.browse(item) })
                            val seriesTarget = item.seriesKey ?: item.series
                            if (allowSeriesJump && item.kind == MediaKind.EPISODE && !seriesTarget.isNullOrBlank()) {
                                DropdownMenuItem(text = { Text("查看整部剧集") }, onClick = { menuOpen = false; model.showWall(seriesTarget) })
                            }
                            DropdownMenuItem(text = { Text("移除续看记录") }, onClick = { menuOpen = false; model.clearProgress(item) })
                        }
                    }
                }
            }
        }
    }
}

private fun durationText(milliseconds: Long): String {
    val minutes = milliseconds / 60_000
    return when {
        minutes >= 60 -> "${minutes / 60}小时${minutes % 60}分"
        minutes > 0 -> "${minutes}分钟"
        else -> "少于1分钟"
    }
}
