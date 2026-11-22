package app.umuo.mobileui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import app.umuo.android.*
import app.umuo.core.library.CatalogFilter
import app.umuo.core.model.*
import app.umuo.core.source.mediaserver.ServerView
import coil.ImageLoader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CinemaLibrary(
    model: PlayerModel, loader: ImageLoader, wide: Boolean, listState: LazyListState, homeJump: Int
) {
    val state by model.state.collectAsState()
    val scan by model.scanner.state.collectAsState()
    var recentOnly by rememberSaveable(state.connection?.id) { mutableStateOf(false) }
    var filters by rememberSaveable(state.connection?.id) { mutableStateOf(false) }
    var fileSearching by rememberSaveable(state.connection?.id) { mutableStateOf(false) }
    var fileQuery by rememberSaveable(state.connection?.id) { mutableStateOf(state.localSearch) }
    val fileSearchFocus = remember { FocusRequester() }
    LaunchedEffect(state.localSearch) {
        if (state.localSearch != fileQuery) fileQuery = state.localSearch
    }
    LaunchedEffect(fileSearching) {
        if (fileSearching) runCatching { fileSearchFocus.requestFocus() }
    }
    var selectedSeason by rememberSaveable(state.series, state.parent, state.connection?.id) { mutableStateOf<Int?>(null) }
    var previousHomeJump by rememberSaveable { mutableIntStateOf(homeJump) }
    var homeIndex by rememberSaveable { mutableIntStateOf(0) }
    var homeOffset by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
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
            fileSearching = false
            previousHomeJump = homeJump
            listState.requestScrollToItem(0)
        }
    }
    BackHandler(enabled = recentOnly) { returnFromRecent() }
    val directoryView = state.connection?.kind == SourceKind.WEBDAV && !state.wall && !recentOnly
    val columns = if (directoryView) { if (wide) 3 else 2 } else if (wide) 6 else 3
    val padding = if (wide) 32.dp else 20.dp
    val display = if (recentOnly) state.visibleRecent else state.items
    val episodeView = !recentOnly && !directoryView && (state.series != null || (display.isNotEmpty() && display.all { it.kind == MediaKind.EPISODE }))
    val seasons = remember(episodeView, display) {
        if (!episodeView) emptyList() else display.mapNotNull { it.season }.distinct().sorted()
    }
    val effectiveSeason = selectedSeason?.takeIf { it in seasons }
    val shownDisplay = remember(episodeView, display, effectiveSeason) {
        if (!episodeView || effectiveSeason == null) display else display.filter { it.season == effectiveSeason }
    }
    val rows = remember(shownDisplay, columns, episodeView) {
        if (episodeView) emptyList() else shownDisplay.chunked(columns)
    }
    val episodeRows = remember(shownDisplay, wide, episodeView) {
        if (episodeView) shownDisplay.chunked(if (wide) 2 else 1) else emptyList()
    }
    val quickResumeTarget = remember(episodeView, display) {
        if (!episodeView) null else display.firstOrNull { it.resumeMs > 0L } ?: display.firstOrNull()
    }
    val home = state.showHomeSections && !recentOnly && !directoryView
    val candidates = state.visibleRecent + state.items
    val featured = if (home) candidates.firstOrNull { it.kind == MediaKind.MOVIE && it.backdrop != null }
        ?: candidates.firstOrNull { !it.isFolder && it.backdrop != null } else null

    fun resetPosition() { recentOnly = false; scope.launch { listState.scrollToItem(0) } }
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = model::refreshCurrent,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!recentOnly && state.series == null && !directoryView) item(key = "header") {
                FeaturedFilm(featured, loader, wide, onPlay = model::play, onDetail = model::openItem) {
                    MobileLibraryNavigation(model, state, padding, filters, { filters = it }, ::resetPosition)
                }
            }
            if (state.visibleRecent.isNotEmpty() && home) item(key = "continue") {
                ContinueWatching(
                    model = model,
                    recent = state.visibleRecent,
                    loader = loader,
                    horizontalPadding = padding,
                    wide = wide,
                    allowSeriesJump = state.connection?.kind == SourceKind.WEBDAV
                ) {
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
            Column(Modifier.padding(horizontal = padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        directoryView && state.localSearch.isNotBlank() -> "文件搜索"
                        else -> state.currentFolderTitle ?: "浏览媒体"
                    }
                    Text(title, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (directoryView) IconButton(onClick = { fileSearching = !fileSearching }) {
                        Icon(Icons.Rounded.Search, "搜索文件", tint = if (fileSearching || state.localSearch.isNotBlank()) MobileStyle.Accent else MobileStyle.Muted)
                    }
                    Text("${shownDisplay.size} ${if (episodeView) "集" else "部"}", fontSize = 12.sp, color = MobileStyle.Muted)
                }
                if (directoryView && state.parent != null && state.breadcrumbs.size > 1 && state.localSearch.isBlank()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        items(state.breadcrumbs.size) { index ->
                            val crumb = state.breadcrumbs[index]
                            val current = index == state.breadcrumbs.lastIndex
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = if (current) MobileStyle.Elevated else MobileStyle.Surface,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable(enabled = !current && !state.busy) { model.jumpToDirectory(crumb.path) }
                                ) {
                                    Text(
                                        crumb.label,
                                        color = if (current) MobileStyle.Accent else MobileStyle.Muted,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                                if (!current) {
                                    Icon(Icons.Rounded.ChevronRight, null, tint = MobileStyle.Muted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                if (directoryView && (fileSearching || state.localSearch.isNotBlank())) {
                    OutlinedTextField(
                        value = fileQuery,
                        onValueChange = {
                            fileQuery = it
                            model.setLocalSearch(it)
                        },
                        label = { Text("搜索当前目录与已索引文件") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(fileSearchFocus),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        trailingIcon = {
                            IconButton(onClick = {
                                keyboard?.hide()
                                fileQuery = ""
                                fileSearching = false
                                model.clearLocalSearch()
                            }) { Icon(Icons.Rounded.Close, "清除搜索") }
                        }
                    )
                }
                if (scan.running || scan.failures > 0 || scan.pausedReason != null) {
                    Surface(
                        color = MobileStyle.Surface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(0.5.dp, MobileStyle.Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (scan.running) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MobileStyle.Accent
                                    )
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        if (scan.running) {
                                            "正在扫描" + scan.directory.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                                        } else {
                                            scan.pausedReason ?: "扫描待恢复"
                                        },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "已索引 ${scan.indexed} · 请求 ${scan.requests} · 失败 ${scan.failures}",
                                        color = MobileStyle.Muted,
                                        fontSize = 11.sp
                                    )
                                }
                                TextButton(onClick = model::scan) {
                                    Text(if (scan.running) "停止" else "恢复")
                                }
                            }
                            if (scan.running) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
                                    color = MobileStyle.Accent,
                                    trackColor = MobileStyle.Border
                                )
                            }
                        }
                    }
                }
            }
        }
        if (episodeView && quickResumeTarget != null) item(key = "series-resume") {
            Column(Modifier.padding(horizontal = padding, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { model.play(quickResumeTarget) },
                    enabled = !state.busy,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (quickResumeTarget.resumeMs > 0L) {
                            "继续观看 S${quickResumeTarget.season ?: 1}E${quickResumeTarget.episode ?: 1} · ${timeLabel(quickResumeTarget.resumeMs)}"
                        } else {
                            "播放第 ${quickResumeTarget.episode ?: 1} 集"
                        }
                    )
                }
                if (seasons.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = effectiveSeason == null,
                                onClick = { selectedSeason = null },
                                label = { Text("全部 (${display.size})") }
                            )
                        }
                        items(seasons) { s ->
                            FilterChip(
                                selected = effectiveSeason == s,
                                onClick = { selectedSeason = s },
                                label = { Text("第 $s 季") }
                            )
                        }
                    }
                }
            }
        }
        if (shownDisplay.isEmpty()) item(key = "empty") {
            Column(Modifier.fillMaxWidth().padding(horizontal = padding, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.VideoLibrary, null, tint = MobileStyle.Muted, modifier = Modifier.size(32.dp))
                Text(when {
                    recentOnly -> "还没有继续观看的影片"
                    state.localSearch.isNotBlank() -> "没有找到匹配的影视或文件"
                    state.connection?.kind != SourceKind.WEBDAV -> serverEmptyText(state)
                    state.wall && state.catalogFilter.active -> "没有符合筛选条件的影视"
                    state.wall -> "还没有已刮削的影视"
                    else -> "这个目录暂时没有影片"
                }, modifier = Modifier.padding(top = 14.dp))
                if (state.localSearch.isNotBlank()) {
                    TextButton(onClick = { fileSearching = false; model.clearLocalSearch() }) { Text("清除搜索") }
                }
                if (state.wall && !recentOnly) {
                    Text("读取 NFO 或匹配影视资料后，会自动出现在这里。", color = MobileStyle.Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp))
                    if (state.catalogFilter.active) TextButton(onClick = { model.filterCatalog(CatalogFilter()) }) { Text("清除筛选") }
                }
                if (state.connection?.kind == SourceKind.WEBDAV && !recentOnly && state.localSearch.isBlank()) TextButton(onClick = model::scan) {
                    Text(if (scan.running) "停止扫描" else "扫描本地 NFO 与海报")
                }
            }
        }
        if (episodeView) {
            items(episodeRows, key = { "ep-row:${it.first().id.value}" }) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = padding, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    row.forEach { episode ->
                        EpisodeRowCard(model, episode, loader, Modifier.weight(1f))
                    }
                    if (wide && row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            items(rows, key = { "media:${it.first().id.value}" }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = padding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item ->
                        MediaPoster(model, item, loader, directoryView, Modifier.weight(1f))
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (!recentOnly && state.nextOffset != null && state.localSearch.isBlank()) item(key = "more") {
            TextButton(onClick = { model.browse(more = true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
        }
    }
    }
}

internal fun episodeDisplayTitle(item: MediaItem): String {
    val ep = item.episode ?: return item.title
    val prefix = "第 $ep 集"
    val trimmed = item.title.trim()
    return if (
        trimmed.isEmpty() ||
        trimmed == prefix ||
        trimmed == "第${ep}集" ||
        trimmed.equals("E$ep", ignoreCase = true) ||
        trimmed.equals("Episode $ep", ignoreCase = true)
    ) prefix else "$prefix · $trimmed"
}

@Composable private fun EpisodeRowCard(
    model: PlayerModel,
    item: MediaItem,
    loader: ImageLoader,
    modifier: Modifier
) {
    Surface(
        color = MobileStyle.Surface,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.clickable(role = Role.Button, onClickLabel = "查看 ${item.title}") { model.openItem(item) }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.width(128.dp).aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .border(0.5.dp, MobileStyle.Border, RoundedCornerShape(8.dp))
            ) {
                VideoArtwork(model, item, loader, Modifier.fillMaxSize(), resume = true)
                if (item.resumeMs > 0L) {
                    val progress = if (item.durationMs > 0L) (item.resumeMs.toFloat() / item.durationMs).coerceIn(0.05f, 1f) else 0.25f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                        color = MobileStyle.Accent, trackColor = Color.White.copy(alpha = .2f), gapSize = 0.dp, drawStopIndicator = {}
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    episodeDisplayTitle(item),
                    fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                val meta = listOfNotNull(
                    item.episode?.let { "S${item.season ?: 1}E$it" },
                    item.durationMs.takeIf { it > 0L }?.let(::timeLabel),
                    if (item.resumeMs > 0L) "看到 ${timeLabel(item.resumeMs)}" else if (item.watched == true) "已看" else null
                ).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, color = MobileStyle.Muted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1)
                if (item.overview.isNotBlank()) {
                    Text(item.overview, color = MobileStyle.Muted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { model.play(item) }) {
                Icon(Icons.Rounded.PlayArrow, "播放 ${item.title}", tint = MobileStyle.Accent)
            }
        }
    }
}

@Composable private fun MediaPoster(
    model: PlayerModel, item: MediaItem, loader: ImageLoader, directoryView: Boolean, modifier: Modifier
) {
    Column(modifier.clickable(role = Role.Button, onClickLabel = "查看 ${item.title}") { model.openItem(item) }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(if (directoryView) 16f / 9f else 2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .border(0.5.dp, MobileStyle.Border, RoundedCornerShape(8.dp))
        ) {
            if (item.isFolder && item.artwork == null) Box(Modifier.fillMaxSize().background(MobileStyle.Surface), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Folder, item.title, tint = MobileStyle.Accent.copy(alpha = .7f), modifier = Modifier.size(34.dp))
            } else VideoArtwork(model, item, loader, Modifier.fillMaxSize())
            if (item.kind == MediaKind.SERIES) Surface(
                Modifier.align(Alignment.TopEnd).padding(6.dp), color = Color.Black.copy(alpha = .65f), shape = RoundedCornerShape(4.dp)
            ) { Text("剧集", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)) }
            else if (item.watched == true) Surface(
                Modifier.align(Alignment.TopEnd).padding(6.dp), color = Color.Black.copy(alpha = .65f), shape = RoundedCornerShape(4.dp)
            ) { Text("已看", color = MobileStyle.Accent, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)) }
            if (!item.isFolder && item.resumeMs > 0L) {
                val fraction = if (item.durationMs > 0L) (item.resumeMs.toFloat() / item.durationMs).coerceIn(0.05f, 1f) else 0.25f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = MobileStyle.Accent, trackColor = Color.White.copy(alpha = .2f), gapSize = 0.dp, drawStopIndicator = {}
                )
            }
        }
        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
            fontWeight = FontWeight.Medium, lineHeight = 18.sp, modifier = Modifier.padding(top = 7.dp))
        if (item.episode != null) Text("S${item.season ?: 1} · E${item.episode}", color = MobileStyle.Muted, fontSize = 11.sp)
    }
}
