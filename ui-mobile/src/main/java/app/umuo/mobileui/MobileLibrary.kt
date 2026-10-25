package app.umuo.mobileui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umuo.android.*
import app.umuo.core.library.CatalogFilter
import app.umuo.core.model.*
import app.umuo.core.source.mediaserver.ServerView
import coil.ImageLoader
import kotlinx.coroutines.launch

@Composable internal fun CinemaLibrary(
    model: PlayerModel, loader: ImageLoader, wide: Boolean, listState: LazyListState, homeJump: Int
) {
    val state by model.state.collectAsState()
    val scan by model.scanner.state.collectAsState()
    var recentOnly by rememberSaveable(state.connection?.id) { mutableStateOf(false) }
    var filters by rememberSaveable(state.connection?.id) { mutableStateOf(false) }
    var previousHomeJump by rememberSaveable { mutableIntStateOf(homeJump) }
    var homeIndex by rememberSaveable { mutableIntStateOf(0) }
    var homeOffset by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun returnFromRecent() {
        recentOnly = false
        scope.launch {
            withFrameNanos { }
            listState.scrollToItem(homeIndex, homeOffset)
        }
    }
    LaunchedEffect(homeJump) {
        if (previousHomeJump != homeJump) {
            recentOnly = false
            filters = false
            previousHomeJump = homeJump
            listState.requestScrollToItem(0)
        }
    }
    BackHandler(enabled = recentOnly) { returnFromRecent() }
    val directoryView = state.connection?.kind == SourceKind.WEBDAV && !state.wall && !recentOnly
    val columns = if (directoryView) { if (wide) 3 else 2 } else if (wide) 6 else 3
    val padding = if (wide) 32.dp else 20.dp
    val display = if (recentOnly) state.visibleRecent else state.items
    val rows = remember(display, columns) { display.chunked(columns) }
    val home = state.showHomeSections && !recentOnly && !directoryView
    val candidates = state.visibleRecent + state.items
    val featured = if (home) candidates.firstOrNull { it.kind == MediaKind.MOVIE && it.backdrop != null }
        ?: candidates.firstOrNull { !it.isFolder && it.backdrop != null } else null

    fun resetPosition() { recentOnly = false; scope.launch { listState.scrollToItem(0) } }
    LazyColumn(
        state = listState, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (featured == null && !recentOnly && state.series == null && !directoryView) item(key = "navigation") {
            MobileLibraryNavigation(model, state, padding, filters, { filters = it }, ::resetPosition)
        }
        if (featured != null) item(key = "featured") {
            FeaturedFilm(featured, loader, wide, onPlay = { model.play(featured) }, onDetail = { model.openItem(featured) }) {
                MobileLibraryNavigation(model, state, padding, filters, { filters = it }, ::resetPosition)
            }
        }
        if (state.visibleRecent.isNotEmpty() && home) item(key = "continue") {
            ContinueWatching(model, state.visibleRecent, loader, padding, wide) {
                if (state.connection?.kind != SourceKind.WEBDAV) model.showServerView(ServerView.RESUME)
                else {
                    homeIndex = listState.firstVisibleItemIndex
                    homeOffset = listState.firstVisibleItemScrollOffset
                    recentOnly = true
                }
                scope.launch { listState.scrollToItem(0) }
            }
        }
        item(key = "section") {
            Column(Modifier.padding(horizontal = padding)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (recentOnly || state.parent != null || state.series != null) IconButton(onClick = {
                        if (recentOnly) returnFromRecent() else model.back()
                    }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                    val title = when {
                        recentOnly -> "继续观看"
                        state.seriesTitle != null -> state.seriesTitle!!
                        state.connection?.kind != SourceKind.WEBDAV && state.parent == null ->
                            if (state.serverSearch.isNotBlank()) "搜索结果" else state.serverView.label
                        state.wall -> "影视库"
                        else -> "浏览媒体"
                    }
                    Text(title, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${display.size} 部", fontSize = 12.sp, color = MobileStyle.Muted)
                }
                if (scan.running || scan.failures > 0) Text(
                    "已索引 ${scan.indexed} · 请求 ${scan.requests} · 失败 ${scan.failures}",
                    color = MobileStyle.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        if (display.isEmpty()) item(key = "empty") {
            Column(Modifier.fillMaxWidth().padding(horizontal = padding, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.VideoLibrary, null, tint = MobileStyle.Muted, modifier = Modifier.size(32.dp))
                Text(when {
                    recentOnly -> "还没有继续观看的影片"
                    state.connection?.kind != SourceKind.WEBDAV -> serverEmptyText(state)
                    state.wall && state.catalogFilter.active -> "没有符合筛选条件的影视"
                    state.wall -> "还没有已刮削的影视"
                    else -> "这个目录暂时没有影片"
                }, modifier = Modifier.padding(top = 14.dp))
                if (state.wall && !recentOnly) {
                    Text("读取 NFO 或匹配影视资料后，会自动出现在这里。", color = MobileStyle.Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp))
                    if (state.catalogFilter.active) TextButton(onClick = { model.filterCatalog(CatalogFilter()) }) { Text("清除筛选") }
                }
                if (state.connection?.kind == SourceKind.WEBDAV && !recentOnly) TextButton(onClick = model::scan) {
                    Text(if (scan.running) "停止扫描" else "扫描本地 NFO 与海报")
                }
            }
        }
        items(rows, key = { "media:${it.first().id.value}" }) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = padding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { item ->
                    MediaPoster(model, item, loader, directoryView, Modifier.weight(1f))
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (!recentOnly && state.nextOffset != null) item(key = "more") {
            TextButton(onClick = { model.browse(more = true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
        }
    }
}

@Composable private fun MediaPoster(
    model: PlayerModel, item: MediaItem, loader: ImageLoader, directoryView: Boolean, modifier: Modifier
) {
    Column(modifier.clickable(role = Role.Button, onClickLabel = "查看 ${item.title}") { model.openItem(item) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (directoryView) 16f / 9f else 2f / 3f).clip(RoundedCornerShape(8.dp))) {
            if (item.isFolder && item.artwork == null) Box(Modifier.fillMaxSize().background(MobileStyle.Surface), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Folder, item.title, tint = MobileStyle.Accent.copy(alpha = .7f), modifier = Modifier.size(34.dp))
            } else VideoArtwork(model, item, loader, Modifier.fillMaxSize())
            if (item.kind == MediaKind.SERIES) Surface(
                Modifier.align(Alignment.TopEnd).padding(6.dp), color = Color.Black.copy(alpha = .65f), shape = RoundedCornerShape(4.dp)
            ) { Text("剧集", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)) }
        }
        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
            fontWeight = FontWeight.Medium, lineHeight = 18.sp, modifier = Modifier.padding(top = 7.dp))
        if (item.episode != null) Text("S${item.season ?: 1} · E${item.episode}", color = MobileStyle.Muted, fontSize = 11.sp)
    }
}
