package app.umuo.tvui

import androidx.activity.compose.BackHandler
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
import coil.ImageLoader
import kotlinx.coroutines.delay

@Composable fun TvApp(model:PlayerModel) {
    val state by model.state.collectAsState()
    val scan by model.scanner.state.collectAsState()
    var connect by remember{mutableStateOf(false)}
    var settings by remember{mutableStateOf(false)}
    var lastFocused by remember{mutableStateOf<String?>(null)}
    val addSourceFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    var topReturnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val grid=rememberLazyGridState()
    val requesters=remember{mutableMapOf<String,FocusRequester>()}
    LaunchedEffect(connect, settings, state.playing, state.items, state.selected) {
        if (connect || settings || state.playing != null) return@LaunchedEffect
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
    val context=androidx.compose.ui.platform.LocalContext.current
    val loader=remember(state.connection){sourceImages(context,state.connection,model.secrets)}
    DisposableEffect(loader){onDispose{loader.shutdown()}}
    CinemaTheme { MaterialTheme(colorScheme=darkColorScheme(primary=Cinema.Accent,onPrimary=Cinema.Background,background=Cinema.Background,surface=Cinema.Surface,onSurface=Cinema.Text,secondaryContainer=Cinema.Elevated,onSecondaryContainer=Cinema.Text)) {
        CompositionLocalProvider(LocalContentColor provides Cinema.Text) {
        Box(Modifier.fillMaxSize().background(Cinema.Background).imePadding()) {
            BackHandler(enabled=connect || settings || state.playing!=null || state.selected!=null || state.parent!=null || state.wall || state.inServerSubview) {
                if(connect)connect=false else if(settings)settings=false else model.back()
            }
            when {
                state.playing!=null->TvCinemaPlayer(model)
                connect->Box(Modifier.fillMaxSize().padding(40.dp)){TvConnection(model){connect=false}}
                settings->Box(Modifier.fillMaxSize().padding(40.dp)){TvSettings(model){settings=false}}
                else->Column(Modifier.fillMaxSize().padding(horizontal=44.dp,vertical=28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                        CinemaWordmark();Spacer(Modifier.width(24.dp))
                        Text("你的私人影院",color=Cinema.Muted,fontSize=12.sp)
                        Spacer(Modifier.weight(1f))
                        Button(onClick={topReturnFocus=addSourceFocus;connect=true},modifier=Modifier.focusRequester(addSourceFocus)){Icon(Icons.Rounded.Add,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text("添加来源",fontSize=12.sp)}
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
                        if(state.connection?.kind!=SourceKind.WEBDAV)TvServerSearch(model,state)
                        if(state.wall && state.series==null) TvCatalogFilters(state,model::filterCatalog)
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
                                        items(state.visibleRecent,key={it.id.value}){item->Card(onClick={model.play(item)},modifier=Modifier.width(210.dp)) {
                                            VideoArtwork(model,item,loader,Modifier.fillMaxWidth().aspectRatio(16f/9f),resume=true)
                                            Text(item.title,fontSize=12.sp,maxLines=1,modifier=Modifier.padding(10.dp))
                                        }}
                                    }
                                }
                            }
                            item(span={GridItemSpan(maxLineSpan)}){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(state.seriesTitle ?: if(state.connection?.kind!=SourceKind.WEBDAV && state.parent==null){if(state.serverSearch.isNotBlank())"搜索结果" else state.serverView.label} else if(state.wall)"影视库" else "媒体库",fontSize=19.sp,fontWeight=FontWeight.Medium);Text(if(scan.running)"正在扫描 ${scan.indexed}" else "${state.items.size} 部内容",color=Cinema.Muted,fontSize=11.sp)}}
                            if(state.items.isEmpty())item(span={GridItemSpan(maxLineSpan)}) {
                                Column(Modifier.fillMaxWidth().padding(vertical=50.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                    Icon(Icons.Rounded.VideoLibrary,null,tint=Cinema.Accent,modifier=Modifier.size(44.dp))
                                    Text(when {state.connection==null->"连接你的媒体，让客厅成为影院。";state.connection?.kind==SourceKind.WEBDAV->"扫描本地 NFO 与海报，建立你的影视库。";state.serverSearch.isNotBlank()->"没有找到匹配的影视";state.serverView==ServerView.FAVORITES->"还没有收藏";else->"这个影视库暂时没有内容"},color=Cinema.Muted,fontSize=16.sp,modifier=Modifier.padding(top=20.dp))
                                }
                            }
                            items(state.items,key={it.id.value}){item->
                                val focus=requesters.getOrPut(item.id.value){FocusRequester()}
                                Card(onClick={model.openItem(item)},colors=CardDefaults.colors(containerColor=Cinema.Surface,contentColor=Cinema.Text,focusedContainerColor=Cinema.Elevated,focusedContentColor=Cinema.Text),modifier=Modifier.focusRequester(focus).onFocusChanged{if(it.hasFocus)lastFocused=item.id.value}) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(2f/3f)) {
                                        VideoArtwork(model,item,loader,Modifier.fillMaxSize())
                                        if(item.isFolder && item.artwork==null)Icon(Icons.Rounded.Folder,null,tint=Cinema.Accent,modifier=Modifier.align(Alignment.Center).size(36.dp))
                                    }
                                    Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                        Text(item.title,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp)
                                        Text(item.year?.toString() ?: if(item.isFolder)"媒体合集" else "视频",fontSize=10.sp,color=Cinema.Muted)
                                    }
                                }
                            }
                            if(state.nextOffset!=null)item(span={GridItemSpan(maxLineSpan)}){Button(onClick={model.browse(more=true)},enabled=!state.busy){Text("加载更多")}}
                        }
                    }
                }
            }
        }
    }}}
}
@Composable private fun TvDetail(model:PlayerModel,item:MediaItem,loader:ImageLoader) {
    val state by model.state.collectAsState()
    var query by remember(item.id){mutableStateOf(item.series ?: item.title)}
    Row(Modifier.fillMaxSize().padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(36.dp)) {
        CinemaArtwork(item.artwork,item.title,loader,Modifier.width(240.dp).aspectRatio(2f/3f).clip(RoundedCornerShape(12.dp)))
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            item{Text(item.title,fontSize=32.sp,fontWeight=FontWeight.SemiBold)}
            item{Text(listOfNotNull(item.year?.toString(),item.series).joinToString(" · "),color=Cinema.Muted,fontSize=12.sp)}
            item{Row(horizontalArrangement=Arrangement.spacedBy(14.dp)){
                Button(onClick={model.play(item)},enabled=!state.busy){Icon(Icons.Rounded.PlayArrow,null,Modifier.size(20.dp));Text("播放 / 续播")}
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
@Composable private fun TvCinemaPlayer(model:PlayerModel) {
    val browser by model.state.collectAsState()
    val state by model.engine.state.collectAsState()
    val videoFocus=remember{FocusRequester()}
    val controlsFocus=remember{FocusRequester()}
    val recoveryFocus=remember{FocusRequester()}
    LaunchedEffect(state.error){if(state.error!=null)recoveryFocus.requestFocus() else videoFocus.requestFocus()}
    var surfaceFocused by remember{mutableStateOf(false)}
    var controlsVisible by remember{mutableStateOf(false)}
    LaunchedEffect(controlsVisible){if(controlsVisible)controlsFocus.requestFocus()}
    LaunchedEffect(browser.playing?.id){videoFocus.requestFocus()}
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().focusRequester(videoFocus).onFocusChanged{surfaceFocused=it.isFocused}.onPreviewKeyEvent {e->
            if(!surfaceFocused || e.type!=KeyEventType.KeyDown)false else when(e.key) {
                Key.DirectionLeft->{model.engine.seekTo(state.positionMs-10_000);true}
                Key.DirectionRight->{model.engine.seekTo(state.positionMs+10_000);true}
                Key.Enter,Key.DirectionCenter,Key.MediaPlayPause->{model.engine.toggle();true}
                Key.DirectionDown->{if(state.error!=null)recoveryFocus.requestFocus() else controlsVisible=true;true}
                else->false
            }
        }.focusable()){PlayerSurface(model,Modifier.fillMaxSize(),false)}
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.7f),Color.Transparent))).padding(32.dp)) {
            Text(browser.playing?.title.orEmpty(),fontSize=22.sp,fontWeight=FontWeight.Medium)
            Text(browser.connection?.name.orEmpty(),fontSize=11.sp,color=Cinema.Muted,modifier=Modifier.padding(top=5.dp))
        }
        if(controlsVisible || !state.playing || state.error!=null)Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.9f)))).padding(32.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            state.error?.let{
                Text(it,color=Color(0xFFFFB4AB))
                Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Button(onClick={model.retryPlayback()},enabled=!browser.busy,modifier=Modifier.focusRequester(recoveryFocus)){Text("重试播放")}
                    if(browser.connection?.kind!=SourceKind.WEBDAV)Button(onClick={model.retryPlayback(true)},enabled=!browser.busy){Text("兼容播放")}
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(timeLabel(state.positionMs),fontSize=12.sp);Text(timeLabel(state.durationMs),fontSize=12.sp,color=Cinema.Muted)}
            androidx.compose.material3.LinearProgressIndicator(progress={if(state.durationMs>0)(state.positionMs.toFloat()/state.durationMs).coerceIn(0f,1f) else 0f},modifier=Modifier.fillMaxWidth().height(3.dp),color=Cinema.Accent,trackColor=Color.White.copy(alpha=.2f))
            LazyRow(horizontalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(5.dp),modifier=Modifier.onPreviewKeyEvent{if(it.type==KeyEventType.KeyDown && it.key==Key.DirectionUp){if(state.error!=null)recoveryFocus.requestFocus() else {videoFocus.requestFocus();controlsVisible=false};true}else false}) {
                item{Button(onClick={model.engine.toggle()},modifier=Modifier.focusRequester(controlsFocus)){Icon(when {state.ended->Icons.Rounded.Replay;state.playing->Icons.Rounded.Pause;else->Icons.Rounded.PlayArrow},null,Modifier.size(20.dp));Text(when {state.ended->"重新播放";state.playing->"暂停";else->"播放"})}}
                item{Button(onClick={model.engine.setSpeed(if(state.speed>=2f)1f else state.speed+.25f)}){Text("${state.speed}×")}}
                item{Button(onClick={model.engine.selectSubtitle(null)}){Text("关闭字幕")}}
                items(state.audioTracks,key={"audio:"+it.id}){t->Button(onClick={model.engine.selectAudio(t.id)}){Text((if(t.selected)"✓ " else "")+t.label,fontSize=11.sp)}}
                items(state.subtitles,key={"text:"+it.id}){t->Button(onClick={model.engine.selectSubtitle(t.id)}){Column {Text((if(t.selected)"✓ " else "")+t.label,fontSize=11.sp);t.detail?.let{Text(it,fontSize=9.sp,color=Cinema.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)}}}
                }
                item{Button(onClick=model::stop){Text("结束播放")}}
            }
        }
    }
}
@Composable private fun TvSettings(model:PlayerModel,done:()->Unit) {
    val state by model.state.collectAsState()
    val scan by model.scanner.state.collectAsState()
    val scanSpeed by model.scanPreferences.speed.collectAsState()
    val autoPreviews by model.scanPreferences.autoPreviews.collectAsState()
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
        Text("本地 NFO 优先。开启后扫描与手动匹配会查询影视标题，不上传媒体地址和账号。",color=Cinema.Muted,fontSize=14.sp)
        Button(onClick={enabled=!enabled}){Text(if(enabled)"TMDB：开启" else "TMDB：关闭")}
        TvTextField(token,{token=it},"TMDB 访问令牌",Modifier.fillMaxWidth(),password=true)
        state.error?.let {Text(it,color=Color(0xFFFFB4AB))}
        Row(Modifier.bringIntoViewRequester(actions),horizontalArrangement=Arrangement.spacedBy(16.dp)){Button(modifier=Modifier.focusRequester(saveFocus),onClick={saveAttempt++;if(model.configureTmdb(enabled,token))done()}){Text("保存")};Button(onClick=done){Text("返回")}}
        Text("This product uses the TMDB API but is not endorsed or certified by TMDB.",fontSize=11.sp,color=Cinema.Muted)
    }
}
@Composable private fun TvConnection(model: PlayerModel, done: () -> Unit) {
    val keyboard=LocalSoftwareKeyboardController.current
    val draftId=remember {java.util.UUID.randomUUID().toString()}
    val connectionState by model.state.collectAsState()
    var kind by remember {mutableStateOf(SourceKind.JELLYFIN)}
    var name by remember {mutableStateOf("")};var host by remember {mutableStateOf("")}
    var port by remember {mutableStateOf(ServerAddress.defaultPort(kind))}
    var path by remember {mutableStateOf(ServerAddress.defaultPath(kind))}
    var https by remember {mutableStateOf(false)}
    var addressError by remember {mutableStateOf<String?>(null)}
    var user by remember {mutableStateOf("")};var password by remember {mutableStateOf("")}
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
        Text("连接媒体来源",style=MaterialTheme.typography.headlineMedium)
        Text("方向键选择，确定后输入；完成或返回后继续选择。",fontSize=12.sp,color=Cinema.Muted)
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){SourceKind.entries.forEach {k->Button(onClick={
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
        TvTextField(password,{password=it},"密码",Modifier.fillMaxWidth(),password=true)
        Text("HTTP 为未加密连接；建议使用 HTTPS。")
        connectionState.error?.let { Text(it) }
        Row(Modifier.bringIntoViewRequester(actions),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Button(modifier=Modifier.focusRequester(saveFocus),onClick={keyboard?.hide();runCatching {if(host.startsWith("http://") || host.startsWith("https://"))ServerAddress.parse(host).baseUrl() else ServerAddress(host,port,path,https).baseUrl()}
                .onSuccess {address->model.connect(name,kind,address,user,password,draftId){password="";done()}}
                .onFailure {addressError=it.message}},enabled=host.isNotBlank() && !connectionState.busy){Text("连接并保存")}
            Button(onClick=done){Text("返回")}
        }
    }
}
