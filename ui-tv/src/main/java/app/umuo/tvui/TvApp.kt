package app.umuo.tvui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import app.umuo.android.*
import app.umuo.core.library.*
import app.umuo.core.source.mediaserver.ServerView
import app.umuo.core.network.ServerAddress
import app.umuo.core.model.*
import app.umuo.core.playback.PlaybackState
import coil.ImageLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable fun TvApp(model:PlayerModel) {
    val state by model.state.collectAsState()
    val scan by model.scanner.state.collectAsState()
    var connect by remember{mutableStateOf(false)}
    var editingConnectionId by remember{mutableStateOf<String?>(null)}
    var settings by remember{mutableStateOf(false)}
    var recentMenuTarget by remember { mutableStateOf<MediaItem?>(null) }
    var lastFocused by remember{mutableStateOf<String?>(null)}
    val addSourceFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    var topReturnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val grid=rememberLazyGridState()
    val requesters=remember{mutableMapOf<String,FocusRequester>()}
    LaunchedEffect(connect, settings, state.playing, state.items, state.selected, recentMenuTarget) {
        if (connect || settings || state.playing != null || recentMenuTarget != null) return@LaunchedEffect
        val entry = topReturnFocus
        if (entry != null) {
            withFrameNanos { }
            entry.requestFocus()
            topReturnFocus = null
        } else if (state.selected == null) {
            delay(300)
            lastFocused?.let { key -> runCatching { requesters[key]?.requestFocus() } }
        }
    }
    val loader=rememberSourceImageLoader(state.connection,model.secrets)
    CinemaTheme { MaterialTheme(colorScheme=darkColorScheme(primary=Cinema.Accent,onPrimary=Cinema.Background,background=Cinema.Background,surface=Cinema.Surface,onSurface=Cinema.Text,secondaryContainer=Cinema.Elevated,onSecondaryContainer=Cinema.Text)) {
        CompositionLocalProvider(LocalContentColor provides Cinema.Text) {
        Box(Modifier.fillMaxSize().background(Cinema.Background).imePadding()) {
            BackHandler(enabled=connect || settings || state.playing!=null || state.selected!=null || state.parent!=null || state.wall || state.inServerSubview || state.localSearch.isNotBlank()) {
                if(connect) { connect=false; editingConnectionId=null } else if(settings)settings=false else model.back()
            }
            when {
                state.playing!=null->TvCinemaPlayer(model)
                connect->Box(Modifier.fillMaxSize().padding(40.dp)){TvConnection(model, editingConnectionId?.let(model::editableConnection)){connect=false;editingConnectionId=null}}
                settings->Box(Modifier.fillMaxSize().padding(40.dp)){TvSettings(model){settings=false}}
                else->Column(Modifier.fillMaxSize().padding(horizontal=44.dp,vertical=28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                        CinemaWordmark();Spacer(Modifier.width(24.dp))
                        Text("你的私人影院",color=Cinema.Muted,fontSize=12.sp)
                        if(state.connection?.baseUrl?.startsWith("http://",true)==true)Text("HTTP 未加密",color=Color(0xFFFFB74D),fontSize=11.sp)
                        Spacer(Modifier.weight(1f))
                        if(state.connection != null) {
                            Button(onClick={topReturnFocus=addSourceFocus;editingConnectionId=state.connection?.id;connect=true}){Icon(Icons.Rounded.Edit,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text("编辑来源",fontSize=12.sp)}
                        }
                        Button(onClick={topReturnFocus=addSourceFocus;editingConnectionId=null;connect=true},modifier=Modifier.focusRequester(addSourceFocus)){Icon(Icons.Rounded.Add,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text("添加来源",fontSize=12.sp)}
                        Button(onClick={topReturnFocus=settingsFocus;settings=true},modifier=Modifier.focusRequester(settingsFocus)){Icon(Icons.Rounded.Tune,"设置",Modifier.size(18.dp))}
                    }
                    state.error?.let{Text(it,color=Color(0xFFFFB4AB),fontSize=12.sp)}
                    state.warning?.let{Text(it,color=Cinema.Muted,fontSize=11.sp)}
                    if(state.busy)Text("正在连接…",color=Cinema.Accent,fontSize=11.sp)
                    if(state.selected!=null) TvDetail(model,state.selected!!,loader)
                    else {
                        LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(5.dp)) {
                            items(state.connections,key={it.id}){c->Button(onClick={model.selectConnection(c)}){Text((if(state.connection?.id==c.id)"●  " else "")+c.name,fontSize=11.sp)}}
                            if(state.connection?.kind==SourceKind.WEBDAV) {
                                item{Button(onClick={model.showWall()}){Text(if(state.wall)"● 影视库" else "影视库",fontSize=11.sp)}}
                                item{Button(onClick={model.showFiles()}){Text(if(!state.wall)"● 文件浏览" else "文件浏览",fontSize=11.sp)}}
                                item{Button(onClick=model::scan){Text(if(scan.running)"停止扫描" else "扫描 / 恢复",fontSize=11.sp)}}
                            }
                            if(state.connection?.kind!=SourceKind.WEBDAV)items(ServerView.entries) {view->
                                Button(onClick={model.showServerView(view)}){Text((if(state.serverView==view)"✓ " else "")+view.label,fontSize=11.sp)}
                            }
                            if(state.parent!=null || state.series!=null)item{Button(onClick=model::back){Text("返回",fontSize=11.sp)}}
                        }
                        if(state.connection?.kind!=SourceKind.WEBDAV) {
                            TvServerSearch(model,state)
                        } else {
                            Row(horizontalArrangement=Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                TvTextField(state.localSearch, { model.setLocalSearch(it) }, "搜索本地影视", Modifier.width(360.dp))
                                if (state.localSearch.isNotBlank()) {
                                    Button(onClick = model::clearLocalSearch) { Text("清除搜索") }
                                }
                            }
                        }
                        if(state.wall && state.series==null) TvCatalogFilters(state,model::filterCatalog,model::setCatalogSort)
                        if(state.connection?.kind==SourceKind.WEBDAV && !state.wall && state.parent != null && state.breadcrumbs.size > 1 && state.localSearch.isBlank()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(4.dp)) {
                                items(state.breadcrumbs.size) { index ->
                                    val crumb = state.breadcrumbs[index]
                                    val isCurrent = index == state.breadcrumbs.lastIndex
                                    Button(enabled = !isCurrent && !state.busy, onClick = { model.jumpToDirectory(crumb.path) }) {
                                        Text((if (isCurrent) "● " else "") + crumb.label, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        val featured=(state.items+state.visibleRecent).firstOrNull{!it.isFolder && it.backdrop!=null}
                        LazyVerticalGrid(columns=GridCells.Adaptive(145.dp),state=grid,modifier=Modifier.weight(1f),contentPadding=PaddingValues(8.dp),
                            horizontalArrangement=Arrangement.spacedBy(20.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
                            if(featured!=null && state.showHomeSections)item(span={GridItemSpan(maxLineSpan)}) {
                                Box(Modifier.fillMaxWidth().height(245.dp).clip(RoundedCornerShape(14.dp))) {
                                    CinemaArtwork(featured.backdrop,featured.title,loader,Modifier.fillMaxSize(),true)
                                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Cinema.Background.copy(alpha=.8f),Color.Transparent))))
                                    Column(Modifier.align(Alignment.BottomStart).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                        Text("FEATURED  /  为你精选",color=Cinema.Accent,fontSize=10.sp,letterSpacing=2.sp)
                                        Text(featured.title,fontSize=30.sp,fontWeight=FontWeight.SemiBold)
                                        Text(listOfNotNull(featured.year?.toString(),"私人媒体库").joinToString("  ·  "),color=Cinema.Muted,fontSize=11.sp)
                                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                            Button(onClick={model.play(featured)}){Icon(Icons.Rounded.PlayArrow,null,Modifier.size(20.dp));Text(if(featured.resumeMs>0)"继续观看" else "立即播放",fontSize=12.sp)}
                                            Button(onClick={model.openItem(featured)}){Text("影片详情",fontSize=12.sp)}
                                        }
                                    }
                                }
                            }
                            if(state.visibleRecent.isNotEmpty() && state.showHomeSections)item(span={GridItemSpan(maxLineSpan)}) {
                                Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                                    Text("继续观看",fontSize=19.sp,fontWeight=FontWeight.Medium)
                                    LazyRow(horizontalArrangement=Arrangement.spacedBy(18.dp),contentPadding=PaddingValues(6.dp)) {
                                        items(state.visibleRecent,key={it.id.value}){item->Card(
                                            onClick={model.play(item)},
                                            onLongClick={recentMenuTarget=item},
                                            shape=tvCardShape(),
                                            scale=tvCardScale(),
                                            border=tvCardBorder(),
                                            colors=CardDefaults.colors(containerColor=Cinema.Surface,contentColor=Cinema.Text,focusedContainerColor=Cinema.Elevated,focusedContentColor=Cinema.Text),
                                            modifier=Modifier.width(220.dp).onPreviewKeyEvent { e ->
                                                if (e.type == KeyEventType.KeyDown && e.key == Key.Menu) {
                                                    recentMenuTarget = item
                                                    true
                                                } else false
                                            }
                                        ) {
                                            Box(Modifier.fillMaxWidth().aspectRatio(16f/9f)) {
                                                VideoArtwork(model,item,loader,Modifier.fillMaxSize(),resume=true)
                                                androidx.compose.material3.LinearProgressIndicator(
                                                    progress = { if (item.durationMs > 0L) (item.resumeMs.toFloat() / item.durationMs).coerceIn(0.05f, 1f) else 0.25f },
                                                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                                                    color = Cinema.Accent,
                                                    trackColor = Color.White.copy(alpha = .2f)
                                                )
                                            }
                                            Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                                Text(if(item.kind==MediaKind.EPISODE)item.series ?: item.title else item.title,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                                Text(
                                                    when {
                                                        item.kind == MediaKind.EPISODE && item.episode != null -> "第${item.season ?: 1}季 · 第${item.episode}集 · ${timeLabel(item.resumeMs)}"
                                                        item.durationMs > item.resumeMs -> "剩余 ${((item.durationMs - item.resumeMs + 59_999) / 60_000)} 分钟"
                                                        else -> "已观看 ${timeLabel(item.resumeMs)}"
                                                    },
                                                    fontSize=10.sp,color=Cinema.Muted,maxLines=1,overflow=TextOverflow.Ellipsis
                                                )
                                            }
                                        }}
                                    }
                                }
                            }
                            item(span={GridItemSpan(maxLineSpan)}){
                                val quickResume = if (state.series != null) state.items.firstOrNull { it.resumeMs > 0L } ?: state.items.firstOrNull() else null
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                                    Text(state.seriesTitle ?: if(state.connection?.kind!=SourceKind.WEBDAV && state.parent==null){if(state.serverSearch.isNotBlank())"搜索结果" else state.serverView.label} else if(state.wall)"影视库" else if(state.localSearch.isNotBlank()) "本地搜索" else state.currentFolderTitle ?: "媒体库",fontSize=19.sp,fontWeight=FontWeight.Medium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (quickResume != null) {
                                            Button(onClick = { model.play(quickResume) }) {
                                                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(
                                                    if (quickResume.resumeMs > 0L) "继续观看 S${quickResume.season ?: 1}E${quickResume.episode ?: 1}"
                                                    else "播放第 ${quickResume.episode ?: 1} 集",
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                        Text(if(scan.running)"正在扫描 ${scan.indexed}" else "${state.items.size} 部内容",color=Cinema.Muted,fontSize=11.sp)
                                    }
                                }
                            }
                            if(state.items.isEmpty())item(span={GridItemSpan(maxLineSpan)}) {
                                Column(Modifier.fillMaxWidth().padding(vertical=50.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                    Icon(Icons.Rounded.VideoLibrary,null,tint=Cinema.Accent,modifier=Modifier.size(44.dp))
                                    Text(when {state.connection==null->"连接你的媒体，让客厅成为影院。";state.localSearch.isNotBlank()->"没有找到匹配的影视或文件";state.connection?.kind==SourceKind.WEBDAV->"扫描本地 NFO 与海报，建立你的影视库。";state.serverSearch.isNotBlank()->"没有找到匹配的影视";state.serverView==ServerView.FAVORITES->"还没有收藏";else->"这个影视库暂时没有内容"},color=Cinema.Muted,fontSize=16.sp,modifier=Modifier.padding(top=20.dp))
                                }
                            }
                            items(state.items,key={it.id.value}){item->
                                val focus=requesters.getOrPut(item.id.value){FocusRequester()}
                                Card(
                                    onClick={model.openItem(item)},
                                    shape=tvCardShape(),
                                    scale=tvCardScale(),
                                    border=tvCardBorder(),
                                    colors=CardDefaults.colors(containerColor=Cinema.Surface,contentColor=Cinema.Text,focusedContainerColor=Cinema.Elevated,focusedContentColor=Cinema.Text),
                                    modifier=Modifier.focusRequester(focus).onFocusChanged{if(it.hasFocus)lastFocused=item.id.value}
                                ) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(2f/3f)) {
                                        VideoArtwork(model,item,loader,Modifier.fillMaxSize())
                                        if(item.isFolder && item.artwork==null)Icon(Icons.Rounded.Folder,null,tint=Cinema.Accent,modifier=Modifier.align(Alignment.Center).size(36.dp))
                                        if(!item.isFolder && item.resumeMs > 0L) {
                                            androidx.compose.material3.LinearProgressIndicator(
                                                progress = { if (item.durationMs > 0L) (item.resumeMs.toFloat() / item.durationMs).coerceIn(0.05f, 1f) else 0.25f },
                                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                                                color = Cinema.Accent,
                                                trackColor = Color.White.copy(alpha = .2f)
                                            )
                                        }
                                    }
                                    Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                        Text(item.title,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp)
                                        Text(
                                            when {
                                                item.episode != null -> "S${item.season ?: 1} · E${item.episode}"
                                                item.year != null -> item.year.toString()
                                                item.isFolder -> "媒体合集"
                                                else -> "视频"
                                            },
                                            fontSize=10.sp,color=Cinema.Muted
                                        )
                                    }
                                }
                            }
                            if(state.nextOffset!=null && state.localSearch.isBlank())item(span={GridItemSpan(maxLineSpan)}){Button(onClick={model.browse(more=true)},enabled=!state.busy){Text("加载更多")}}
                        }
                    }
                }
            }
            recentMenuTarget?.let { target ->
                TvRecentQuickMenu(
                    target = target,
                    onDismiss = { recentMenuTarget = null },
                    onPlayResume = { recentMenuTarget = null; model.play(target) },
                    onPlayRestart = { recentMenuTarget = null; model.play(target, resumeOverride = 0L) },
                    onOpenDetail = { recentMenuTarget = null; model.openItem(target) },
                    onClearProgress = { recentMenuTarget = null; model.clearProgress(target) }
                )
            }
        }
    }}}
}

@Composable private fun tvCardShape() = CardDefaults.shape(shape = RoundedCornerShape(12.dp))

@Composable private fun tvCardScale() = CardDefaults.scale(focusedScale = 1.04f)

@Composable private fun tvCardBorder() = CardDefaults.border(
    border = Border(BorderStroke(1.dp, Cinema.Border), shape = RoundedCornerShape(12.dp)),
    focusedBorder = Border(BorderStroke(2.dp, Cinema.Accent), shape = RoundedCornerShape(12.dp))
)

@Composable private fun TvRecentQuickMenu(
    target: MediaItem,
    onDismiss: () -> Unit,
    onPlayResume: () -> Unit,
    onPlayRestart: () -> Unit,
    onOpenDetail: () -> Unit,
    onClearProgress: () -> Unit
) {
    BackHandler(enabled = true) { onDismiss() }
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(target.id) {
        withFrameNanos { }
        runCatching { primaryFocus.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.76f))
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Cinema.Elevated)
                .border(1.dp, Cinema.Border, RoundedCornerShape(16.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                if (target.kind == MediaKind.EPISODE) target.series ?: target.title else target.title,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(
                    target.episode?.let { "第${target.season ?: 1}季 · 第${it}集" },
                    if (target.resumeMs > 0L) "已观看 ${timeLabel(target.resumeMs)}" else null
                ).joinToString(" · ").ifEmpty { "续看快捷操作" },
                fontSize = 12.sp,
                color = Cinema.Muted
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = onPlayResume, modifier = Modifier.fillMaxWidth().focusRequester(primaryFocus)) {
                Text(if (target.resumeMs > 0L) "继续播放 ${timeLabel(target.resumeMs)}" else "继续播放")
            }
            Button(onClick = onPlayRestart, modifier = Modifier.fillMaxWidth()) { Text("从头播放") }
            Button(onClick = onOpenDetail, modifier = Modifier.fillMaxWidth()) { Text("影片详情") }
            Button(onClick = onClearProgress, modifier = Modifier.fillMaxWidth()) { Text("移除续看记录") }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
}
@Composable private fun TvDetail(model:PlayerModel,item:MediaItem,loader:ImageLoader) {
    val state by model.state.collectAsState()
    var query by remember(item.id){mutableStateOf(item.series ?: item.title)}
    val badges = remember(item) { MediaSpecs.badges(item) }
    Row(Modifier.fillMaxSize().padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(36.dp)) {
        CinemaArtwork(
            item.artwork,
            item.title,
            loader,
            Modifier.width(240.dp).aspectRatio(2f/3f)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Cinema.Border, RoundedCornerShape(12.dp))
        )
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            item{Text(item.title,fontSize=32.sp,fontWeight=FontWeight.SemiBold)}
            item{Text(listOfNotNull(item.year?.toString(),item.series,item.season?.let{"第 $it 季 · 第 ${item.episode ?: 0} 集"},item.durationMs.takeIf { it > 0L }?.let { "时长 ${timeLabel(it)}" }).joinToString(" · "),color=Cinema.Muted,fontSize=12.sp)}
            if (badges.isNotEmpty()) {
                item {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        badges.forEach { badge ->
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Cinema.Elevated)
                                    .border(1.dp, Cinema.Border, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
                            ) {
                                Text(badge, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Cinema.Text)
                            }
                        }
                    }
                }
            }
            if (item.resumeMs > 0L) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.durationMs > 0L) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { (item.resumeMs.toFloat() / item.durationMs).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = Cinema.Accent,
                                trackColor = Cinema.Border
                            )
                        }
                        Text(
                            buildString {
                                append("上次观看至 ${timeLabel(item.resumeMs)}")
                                if (item.durationMs > item.resumeMs) {
                                    append(" · 剩余 ${((item.durationMs - item.resumeMs + 59_999) / 60_000)} 分钟")
                                }
                            },
                            color = Cinema.Accent,
                            fontSize = 13.sp
                        )
                    }
                }
            }
            item{Row(horizontalArrangement=Arrangement.spacedBy(14.dp)){
                Button(onClick={model.play(item)},enabled=!state.busy){Icon(Icons.Rounded.PlayArrow,null,Modifier.size(20.dp));Text(if(item.resumeMs > 0L) "继续播放 ${timeLabel(item.resumeMs)}" else "播放 / 续播")}
                if(item.resumeMs > 0L) Button(onClick={model.play(item, resumeOverride = 0L)},enabled=!state.busy){Text("从头播放")}
                if(item.resumeMs > 0L) Button(onClick={model.clearProgress(item)},enabled=!state.busy){Text("清除进度")}
                if(state.connection?.kind!=SourceKind.WEBDAV)Button(onClick={model.play(item,true)},enabled=!state.busy){Text("兼容播放")}
                Button(onClick=model::back){Text("返回媒体库")}
            }}
            if(state.connection?.kind!=SourceKind.WEBDAV)item {
                Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Button(enabled=!state.userActionBusy,onClick={model.setFavorite(item)}){Text(if(item.favorite==true)"已收藏" else "收藏")}
                    Button(enabled=!state.userActionBusy,onClick={model.setWatched(item)}){Text(if(item.watched==true)"已看" else "标记已看")}
                }
            }
            if(item.genres.isNotEmpty() || item.tags.isNotEmpty() || item.collection!=null)item {
                Text((item.genres+item.tags+LibraryCatalog.collections(item)).distinct().joinToString("  ·  "),color=Cinema.Accent,fontSize=13.sp)
            }
            if(item.overview.isNotBlank())item{Text(item.overview,color=Cinema.Muted,fontSize=14.sp,lineHeight=23.sp)}
            if(state.connection?.kind==SourceKind.WEBDAV) {
                item{TvTextField(query,{query=it},"匹配标题",Modifier.fillMaxWidth())}
                item{Row(horizontalArrangement=Arrangement.spacedBy(14.dp)){Button(onClick={model.searchMetadata(query)},enabled=!state.busy){Text("查询 TMDB")};Button(onClick={model.match(null)}){Text("取消匹配")}}}
                items(state.metadataCandidates,key={it.id.value}){m->Button(onClick={model.match(m)}){Text("${m.title} ${m.year ?: ""}")}}
            }
        }
    }
}
@Composable private fun TvPlaybackProgress(engine: Media3Engine) {
    val state by engine.state.collectAsState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(timeLabel(state.positionMs), fontSize = 12.sp)
        Text(timeLabel(state.durationMs), fontSize = 12.sp, color = Cinema.Muted)
    }
    androidx.compose.material3.LinearProgressIndicator(
        progress = { if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
        modifier = Modifier.fillMaxWidth().height(3.dp),
        color = Cinema.Accent,
        trackColor = Color.White.copy(alpha = .2f)
    )
}

@Composable private fun TvCinemaPlayer(model:PlayerModel) {
    val browser by model.state.collectAsState()
    val state by remember(model.engine) {
        model.engine.state.map { it.copy(positionMs = 0L) }.distinctUntilChanged()
    }.collectAsState(initial = PlaybackState())
    val gestureSettings by model.playbackPreferences.state.collectAsState()
    val activeError = state.error ?: browser.error
    val videoFocus=remember{FocusRequester()}
    val controlsFocus=remember{FocusRequester()}
    val subRowFocus=remember{FocusRequester()}
    val episodesToggleFocus=remember{FocusRequester()}
    val audioToggleFocus=remember{FocusRequester()}
    val subtitlesToggleFocus=remember{FocusRequester()}
    val recoveryFocus=remember{FocusRequester()}
    var surfaceFocused by remember{mutableStateOf(false)}
    var hudVisible by remember(browser.playing?.id){mutableStateOf(true)}
    var controlsInteractive by remember(browser.playing?.id){mutableStateOf(false)}
    var activeSubRow by remember(browser.playing?.id){mutableStateOf<String?>(null)}
    var hudInteractionTick by remember{mutableIntStateOf(0)}
    fun collapseSubRow(restoreToggle: Boolean = true) {
        val previous = activeSubRow ?: return
        activeSubRow = null
        hudInteractionTick++
        if (restoreToggle) {
            val target = when (previous) {
                "episodes" -> if (browser.seriesEpisodes.size > 1) episodesToggleFocus else controlsFocus
                "audio" -> if (state.audioTracks.isNotEmpty()) audioToggleFocus else controlsFocus
                "subtitles" -> subtitlesToggleFocus
                else -> controlsFocus
            }
            runCatching { target.requestFocus() }
        }
    }
    BackHandler(enabled = activeSubRow != null) {
        collapseSubRow(restoreToggle = true)
    }
    LaunchedEffect(activeError){
        if(activeError!=null) {
            hudVisible = true
            activeSubRow = null
            recoveryFocus.requestFocus()
        } else {
            controlsInteractive = false
            activeSubRow = null
            hudVisible = true
            videoFocus.requestFocus()
        }
    }
    LaunchedEffect(controlsInteractive){
        if(controlsInteractive) {
            withFrameNanos { }
            controlsFocus.requestFocus()
        } else {
            activeSubRow = null
        }
    }
    LaunchedEffect(activeSubRow) {
        if (activeSubRow != null) {
            withFrameNanos { }
            runCatching { subRowFocus.requestFocus() }
        }
    }
    LaunchedEffect(browser.playing?.id){
        if(browser.playing != null) {
            hudVisible = true
            controlsInteractive = false
            activeSubRow = null
            videoFocus.requestFocus()
        }
    }
    LaunchedEffect(hudVisible, controlsInteractive, state.playing, activeError, hudInteractionTick) {
        if ((hudVisible || controlsInteractive) && state.playing && activeError == null) {
            delay(if (controlsInteractive) 8_000L else 5_000L)
            if (controlsInteractive) {
                controlsInteractive = false
                activeSubRow = null
                videoFocus.requestFocus()
            }
            hudVisible = false
        }
    }
    val showHud = hudVisible || controlsInteractive || !state.playing || activeError != null
    Box(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.MediaPlayPause -> { model.engine.toggle(); hudVisible = true; hudInteractionTick++; true }
            Key.MediaPlay -> { model.engine.play(); hudVisible = true; hudInteractionTick++; true }
            Key.MediaPause -> { model.engine.pause(); hudVisible = true; hudInteractionTick++; true }
            Key.MediaRewind -> {
                if (state.seekable) model.engine.seekBy(-tvSeekDeltaMs(gestureSettings.seekSeconds, e.nativeKeyEvent.repeatCount))
                hudVisible = true; hudInteractionTick++; true
            }
            Key.MediaFastForward -> {
                if (state.seekable) model.engine.seekBy(tvSeekDeltaMs(gestureSettings.seekSeconds, e.nativeKeyEvent.repeatCount))
                hudVisible = true; hudInteractionTick++; true
            }
            Key.MediaNext -> {
                if (browser.nextEpisode != null) model.playNextEpisode()
                hudVisible = true; hudInteractionTick++; true
            }
            else -> false
        }
    }) {
        Box(Modifier.fillMaxSize().focusRequester(videoFocus).onFocusChanged{surfaceFocused=it.isFocused}.onPreviewKeyEvent {e->
            if(!surfaceFocused || e.type!=KeyEventType.KeyDown)false else when(e.key) {
                Key.DirectionLeft->{model.engine.seekBy(-tvSeekDeltaMs(gestureSettings.seekSeconds, e.nativeKeyEvent.repeatCount));hudVisible=true;hudInteractionTick++;true}
                Key.DirectionRight->{model.engine.seekBy(tvSeekDeltaMs(gestureSettings.seekSeconds, e.nativeKeyEvent.repeatCount));hudVisible=true;hudInteractionTick++;true}
                Key.Enter,Key.DirectionCenter->{model.engine.toggle();hudVisible=true;hudInteractionTick++;true}
                Key.DirectionDown->{
                    hudVisible = true
                    hudInteractionTick++
                    if(activeError!=null)recoveryFocus.requestFocus() else controlsInteractive=true
                    true
                }
                else->false
            }
        }.focusable()){PlayerSurface(model,Modifier.fillMaxSize(),false)}
        if (showHud) {
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.7f),Color.Transparent))).padding(32.dp)) {
                Text(browser.playing?.title.orEmpty(),fontSize=22.sp,fontWeight=FontWeight.Medium)
                val httpWarning = if (browser.connection?.baseUrl?.startsWith("http://", true) == true) " · HTTP 未加密" else ""
                Text(browser.connection?.name.orEmpty() + httpWarning,fontSize=11.sp,color=if (httpWarning.isNotEmpty()) Color(0xFFFFB74D) else Cinema.Muted,modifier=Modifier.padding(top=5.dp))
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.9f)))).padding(32.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                activeError?.let{
                    Text(it,color=Color(0xFFFFB4AB))
                    Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        Button(onClick={model.retryPlayback()},enabled=!browser.busy,modifier=Modifier.focusRequester(recoveryFocus)){Text("重试播放")}
                        if(browser.connection?.kind!=SourceKind.WEBDAV)Button(onClick={model.retryPlayback(true)},enabled=!browser.busy){Text("兼容播放")}
                    }
                }
                TvPlaybackProgress(model.engine)
                LazyRow(horizontalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(5.dp),modifier=Modifier.onPreviewKeyEvent{
                    if(it.type==KeyEventType.KeyDown && it.key==Key.DirectionUp){
                        if(activeError!=null)recoveryFocus.requestFocus() else {controlsInteractive=false;activeSubRow=null;hudVisible=false;videoFocus.requestFocus()}
                        true
                    } else {
                        if(it.type==KeyEventType.KeyDown) hudInteractionTick++
                        false
                    }
                }) {
                    item{Button(onClick={hudInteractionTick++;model.engine.toggle()},modifier=Modifier.focusRequester(controlsFocus)){Icon(when {state.ended->Icons.Rounded.Replay;state.playing->Icons.Rounded.Pause;else->Icons.Rounded.PlayArrow},null,Modifier.size(20.dp));Text(when {state.ended->"重新播放";state.playing->"暂停";else->"播放"})}}
                    if(browser.seriesEpisodes.size > 1)item(key="episodes"){Button(modifier=Modifier.focusRequester(episodesToggleFocus),onClick={hudInteractionTick++;activeSubRow=if(activeSubRow=="episodes") null else "episodes"}){Text(if(activeSubRow=="episodes")"收起选集" else "选集 (${browser.seriesEpisodes.size})")}}
                    if(browser.nextEpisode != null)item(key="next_episode"){Button(onClick={hudInteractionTick++;model.playNextEpisode()}){Icon(Icons.Rounded.SkipNext,null,Modifier.size(20.dp));Text("下一集")}}
                    item{Button(onClick={hudInteractionTick++;val nextScale = gestureSettings.scaleMode.next();model.playbackPreferences.update(gestureSettings.copy(scaleMode = nextScale))}){Text("画面：${gestureSettings.scaleMode.label}")}}
                    item{Button(onClick={hudInteractionTick++;model.engine.setSpeed(if(state.speed>=2f)1f else state.speed+.25f)}){Text("${state.speed}×")}}
                    if(state.audioTracks.isNotEmpty())item(key="audio_toggle"){Button(modifier=Modifier.focusRequester(audioToggleFocus),onClick={hudInteractionTick++;activeSubRow=if(activeSubRow=="audio") null else "audio"}){Text(if(activeSubRow=="audio")"收起音轨" else "音轨 (${state.audioTracks.size})")}}
                    item(key="subtitles_toggle"){Button(modifier=Modifier.focusRequester(subtitlesToggleFocus),onClick={hudInteractionTick++;activeSubRow=if(activeSubRow=="subtitles") null else "subtitles"}){Text(if(activeSubRow=="subtitles")"收起字幕" else "字幕" + if(state.subtitles.isNotEmpty()) " (${state.subtitles.size})" else "")}}
                    item{Button(onClick=model::stop){Text("结束播放")}}
                }
                if (activeSubRow != null) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(4.dp),
                        modifier = Modifier.onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp) {
                                collapseSubRow(restoreToggle = true)
                                true
                            } else {
                                if (it.type == KeyEventType.KeyDown) hudInteractionTick++
                                false
                            }
                        }
                    ) {
                        when (activeSubRow) {
                            "episodes" -> if (browser.seriesEpisodes.isNotEmpty()) {
                                val activeEpisodeId = browser.playing?.id
                                val fallbackIndex = browser.seriesEpisodes.indexOfFirst { it.id == activeEpisodeId }.takeIf { it >= 0 } ?: 0
                                items(browser.seriesEpisodes.size, key = { browser.seriesEpisodes[it].id.value }) { index ->
                                    val ep = browser.seriesEpisodes[index]
                                    val epMod = if (index == fallbackIndex) Modifier.focusRequester(subRowFocus) else Modifier
                                    Button(
                                        modifier = epMod,
                                        onClick = {
                                            collapseSubRow(restoreToggle = false)
                                            if (ep.id != activeEpisodeId) model.play(ep)
                                        }
                                    ) {
                                        Text(
                                            (if (ep.id == activeEpisodeId) "● " else "") +
                                                (ep.episode?.let { "E$it · ${ep.title}" } ?: ep.title),
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            "audio" -> if (state.audioTracks.isNotEmpty()) {
                                val fallbackIdx = state.audioTracks.indexOfFirst { it.selected }.takeIf { it >= 0 } ?: 0
                                items(state.audioTracks.size, key = { "audio:" + state.audioTracks[it].id }) { index ->
                                    val t = state.audioTracks[index]
                                    val mod = if (index == fallbackIdx) Modifier.focusRequester(subRowFocus) else Modifier
                                    Button(modifier = mod, onClick = { hudInteractionTick++; model.engine.selectAudio(t.id) }) {
                                        Text((if (t.selected) "✓ " else "") + t.label, fontSize = 11.sp)
                                    }
                                }
                            }
                            "subtitles" -> {
                                val selectedIdx = state.subtitles.indexOfFirst { it.selected }
                                item(key = "sub:off") {
                                    val mod = if (selectedIdx < 0) Modifier.focusRequester(subRowFocus) else Modifier
                                    Button(modifier = mod, onClick = { hudInteractionTick++; model.engine.selectSubtitle(null) }) {
                                        Text((if (selectedIdx < 0) "✓ " else "") + "关闭字幕", fontSize = 11.sp)
                                    }
                                }
                                items(state.subtitles.size, key = { "text:" + state.subtitles[it].id }) { index ->
                                    val t = state.subtitles[index]
                                    val mod = if (index == selectedIdx) Modifier.focusRequester(subRowFocus) else Modifier
                                    Button(modifier = mod, onClick = { hudInteractionTick++; model.engine.selectSubtitle(t.id) }) {
                                        Column {
                                            Text((if (t.selected) "✓ " else "") + t.label, fontSize = 11.sp)
                                            t.detail?.let { Text(it, fontSize = 9.sp, color = Cinema.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        }
                                    }
                                }
                                item(key = "sub:style") {
                                    Button(onClick = {
                                        hudInteractionTick++
                                        val entries = SubtitleBackground.entries
                                        val nextBg = entries[(entries.indexOf(gestureSettings.subtitleBackground) + 1) % entries.size]
                                        model.playbackPreferences.update(gestureSettings.copy(subtitleBackground = nextBg))
                                    }) {
                                        Text("样式：${gestureSettings.subtitleBackground.label}", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun TvSettings(model:PlayerModel,done:()->Unit) {
    val state by model.state.collectAsState()
    val scan by model.scanner.state.collectAsState()
    val scanSpeed by model.scanPreferences.speed.collectAsState()
    val autoPreviews by model.scanPreferences.autoPreviews.collectAsState()
    val gestureSettings by model.playbackPreferences.state.collectAsState()
    var enabled by remember{mutableStateOf(state.tmdbEnabled)};var token by remember{mutableStateOf("")}
    val saveFocus = remember { FocusRequester() }
    val actions = remember { BringIntoViewRequester() }
    var saveAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.error, saveAttempt) {
        if (saveAttempt > 0 && state.error != null) {
            withFrameNanos { }
            saveFocus.requestFocus()
            actions.bringIntoView()
        }
    }
    Column(Modifier.widthIn(max=680.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Text("设置与隐私",fontSize=28.sp,fontWeight=FontWeight.SemiBold)
        Text("WebDAV 扫描：单并发，限流时暂停并保留进度",color=Cinema.Muted,fontSize=12.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            ScanSpeed.entries.forEach {value->Button(enabled=!scan.running,onClick={model.scanPreferences.setSpeed(value)}){Text((if(scanSpeed==value)"✓ " else "")+value.label,fontSize=11.sp)}}
        }
        Button(onClick={model.scanPreferences.setAutoPreviews(!autoPreviews)}) {Text(if(autoPreviews)"视频预览提帧：开启" else "视频预览提帧：关闭")}
        Button(onClick={model.playbackPreferences.update(gestureSettings.copy(autoPlayNext=!gestureSettings.autoPlayNext))}) {
            Text(if(gestureSettings.autoPlayNext)"剧集自动连播：开启" else "剧集自动连播：关闭")
        }
        Text("单次快进间隔与字幕大小", color = Cinema.Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(5, 10, 30).forEach { seconds ->
                Button(onClick = { model.playbackPreferences.update(gestureSettings.copy(seekSeconds = seconds)) }) {
                    Text((if (gestureSettings.seekSeconds == seconds) "✓ " else "") + "${seconds} 秒", fontSize = 11.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SubtitleSize.entries.forEach { size ->
                Button(onClick = { model.playbackPreferences.update(gestureSettings.copy(subtitleSize = size)) }) {
                    Text((if (gestureSettings.subtitleSize == size) "✓ " else "") + "字幕 ${size.label}", fontSize = 11.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SubtitleBackground.entries.forEach { bg ->
                Button(onClick = { model.playbackPreferences.update(gestureSettings.copy(subtitleBackground = bg)) }) {
                    Text((if (gestureSettings.subtitleBackground == bg) "✓ " else "") + bg.label, fontSize = 11.sp)
                }
            }
        }
        Text("本地 NFO 优先。开启后扫描与手动匹配会查询影视标题，不上传媒体地址和账号。",color=Cinema.Muted,fontSize=14.sp)
        Button(onClick={enabled=!enabled}){Text(if(enabled)"TMDB：开启" else "TMDB：关闭")}
        TvTextField(token,{token=it},"TMDB 访问令牌",Modifier.fillMaxWidth(),password=true)
        state.error?.let {Text(it,color=Color(0xFFFFB4AB))}
        Row(Modifier.bringIntoViewRequester(actions),horizontalArrangement=Arrangement.spacedBy(16.dp)){Button(modifier=Modifier.focusRequester(saveFocus),onClick={saveAttempt++;if(model.configureTmdb(enabled,token))done()}){Text("保存")};Button(onClick=done){Text("返回")}}
        Text("This product uses the TMDB API but is not endorsed or certified by TMDB.",fontSize=11.sp,color=Cinema.Muted)
    }
}
@Composable private fun TvConnection(model: PlayerModel, existing: SourceConnection? = null, done: () -> Unit) {
    val keyboard=LocalSoftwareKeyboardController.current
    val initialAddress = remember(existing?.id) { existing?.let { runCatching { ServerAddress.parse(it.baseUrl) }.getOrNull() } }
    val draftId=remember(existing?.id) { existing?.id ?: java.util.UUID.randomUUID().toString() }
    val connectionState by model.state.collectAsState()
    var kind by remember(existing?.id) {mutableStateOf(existing?.kind ?: SourceKind.JELLYFIN)}
    var name by remember(existing?.id) {mutableStateOf(existing?.name.orEmpty())}
    var host by remember(existing?.id) {mutableStateOf(initialAddress?.host.orEmpty())}
    var port by remember(existing?.id) {mutableStateOf(initialAddress?.port ?: ServerAddress.defaultPort(kind))}
    var path by remember(existing?.id) {mutableStateOf(initialAddress?.path ?: ServerAddress.defaultPath(kind))}
    var https by remember(existing?.id) {mutableStateOf(initialAddress?.https ?: false)}
    var addressError by remember(existing?.id) {mutableStateOf<String?>(null)}
    var user by remember(existing?.id) {mutableStateOf(existing?.username.orEmpty())}
    var password by remember(existing?.id) {mutableStateOf("")}
    var confirmDelete by remember(existing?.id) { mutableStateOf(false) }
    val saveFocus = remember { FocusRequester() }
    val actions = remember { BringIntoViewRequester() }
    LaunchedEffect(addressError, connectionState.error, connectionState.busy) {
        if (host.isNotBlank() && !connectionState.busy && (addressError != null || connectionState.error != null)) {
            withFrameNanos { }
            saveFocus.requestFocus()
            actions.bringIntoView()
        }
    }
    Column(Modifier.widthIn(max=680.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(if (existing == null) "连接媒体来源" else "编辑媒体来源",style=MaterialTheme.typography.headlineMedium)
        Text("方向键选择，确定后输入；完成或返回后继续选择。",fontSize=12.sp,color=Cinema.Muted)
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){SourceKind.entries.forEach {k->Button(enabled = existing == null && !connectionState.busy, onClick={
            if(port==ServerAddress.defaultPort(kind,https))port=ServerAddress.defaultPort(k,https)
            if(path.trimEnd('/')==ServerAddress.defaultPath(kind).trimEnd('/'))path=ServerAddress.defaultPath(k)
            kind=k
        }){Text((if(kind==k)"● " else "")+k.name)}}}
        TvTextField(name,{name=it},"名称",Modifier.fillMaxWidth())
        TvTextField(host,{host=it;addressError=null},"服务器地址",modifier=Modifier.fillMaxWidth().onFocusChanged {
            if(!it.isFocused && (host.startsWith("http://") || host.startsWith("https://")))runCatching {ServerAddress.parse(host)}.onSuccess {a->host=a.host;port=a.port;path=a.path;https=a.https}
        },keyboardType=KeyboardType.Uri)
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            TvTextField(port,{port=it.filter(Char::isDigit).take(5)},"端口",Modifier.width(180.dp),keyboardType=KeyboardType.Number)
            TvTextField(path,{path=it},"路径",Modifier.weight(1f),keyboardType=KeyboardType.Uri)
        }
        Button(onClick={val secure=!https;if(port==ServerAddress.defaultPort(kind,https))port=ServerAddress.defaultPort(kind,secure);https=secure}) {Text(if(https)"HTTPS：开启" else "HTTPS：关闭")}
        addressError?.let {Text(it)}
        TvTextField(user,{user=it},"用户名",Modifier.fillMaxWidth())
        TvTextField(password,{password=it},if (existing == null) "密码" else "密码（留空保留原值）",Modifier.fillMaxWidth(),password=true)
        Text("HTTP 为未加密连接；建议使用 HTTPS。")
        connectionState.error?.let { Text(it) }
        Row(Modifier.bringIntoViewRequester(actions),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Button(modifier=Modifier.focusRequester(saveFocus),onClick={keyboard?.hide();runCatching {if(host.startsWith("http://") || host.startsWith("https://"))ServerAddress.parse(host).baseUrl() else ServerAddress(host,port,path,https).baseUrl()}
                .onSuccess {address->model.connect(name,kind,address,user,password,draftId){password="";done()}}
                .onFailure {addressError=it.message}},enabled=host.isNotBlank() && !connectionState.busy){Text("连接并保存")}
            if (existing != null) {
                if (!confirmDelete) {
                    Button(enabled = !connectionState.busy, onClick = { confirmDelete = true }) { Text("删除来源") }
                } else {
                    Button(enabled = !connectionState.busy, onClick = { model.removeConnection(existing.id) { confirmDelete = false; done() } }) {
                        Text("确认删除此来源")
                    }
                    Button(onClick = { confirmDelete = false }) { Text("取消删除") }
                }
            }
            Button(onClick=done){Text("返回")}
        }
    }
}
