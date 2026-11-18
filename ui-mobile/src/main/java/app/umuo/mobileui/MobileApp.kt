package app.umuo.mobileui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umuo.android.*
import app.umuo.core.model.*
import coil.ImageLoader
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MobileApp(model:PlayerModel,inPip:Boolean,onPip:()->Unit) {
    val state by model.state.collectAsState()
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("媒体库") }
    var sourceSheet by androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(tab) {if(tab=="来源"){tab="媒体库";sourceSheet=true}}
    var editor by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var editingId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val holder=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val libraryScroll=rememberLazyListState()
    val navigationScope=rememberCoroutineScope()
    var lastLibraryTap by remember {mutableLongStateOf(-1L)}
    var homeJump by remember {mutableIntStateOf(0)}
    fun navigate(route:String) {
        if(route=="媒体库") {
            val now=android.os.SystemClock.uptimeMillis()
            if(lastLibraryTap>=0 && now-lastLibraryTap<=android.view.ViewConfiguration.getDoubleTapTimeout()) {
                homeJump++
                model.home()
                navigationScope.launch {libraryScroll.scrollToItem(0)}
                lastLibraryTap=-1L
            } else lastLibraryTap=now
        } else lastLibraryTap=-1L
        tab=route
    }
    val loader=rememberSourceImageLoader(state.connection,model.secrets)
    fun addSource() { sourceSheet=false;editingId=null; editor=true; model.clearMessage() }
    fun editSource(c:SourceConnection) { sourceSheet=false;editingId=c.id; editor=true; model.clearMessage() }
    MobileCinemaTheme {
        if (state.playing != null) {
            CinemaPlayer(
                model = model,
                inPip = inPip,
                onPip = onPip,
                onEditConnection = { editingId = state.connection?.id; editor = true; model.stop() }
            )
        } else Surface(Modifier.fillMaxSize(),color=MobileStyle.Background) {
            BackHandler(enabled=editor || tab!="媒体库" || state.selected!=null || state.wall || state.parent!=null || state.inServerSubview) {
                when { editor->editor=false; tab!="媒体库"->tab="媒体库"; else->model.back() }
            }
            BoxWithConstraints {
                val wide=maxWidth>=840.dp
                val sourceWidth=if(wide)190.dp else (maxWidth-170.dp * maxOf(1f,androidx.compose.ui.platform.LocalDensity.current.fontScale)).coerceIn(80.dp,190.dp)
                Row(Modifier.fillMaxSize()) {
                    if(wide && !editor) NavigationRail(Modifier.fillMaxHeight().width(88.dp).statusBarsPadding(),containerColor=MobileStyle.Surface) {
                        Spacer(Modifier.height(20.dp)); Icon(Icons.Rounded.PlayCircle,null,tint=MobileStyle.Accent,modifier=Modifier.size(30.dp));Spacer(Modifier.height(40.dp))
                        listOf("媒体库","设置").forEach { route ->
                            if(route=="设置")Spacer(Modifier.weight(1f))
                            NavigationRailItem(selected=tab==route,onClick={navigate(route)},icon={Icon(when(route){"设置"->Icons.Rounded.Settings;else->Icons.Rounded.VideoLibrary},null)},label={Text(route)})
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=if(wide)32.dp else 20.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            if(editor) {
                                IconButton(onClick={editor=false}){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"返回媒体库")}
                                Text(if(editingId==null)"添加媒体来源" else "编辑媒体来源",fontSize=21.sp,fontWeight=FontWeight.SemiBold)
                            } else {
                                if(tab=="媒体库")Text("umuo",fontSize=27.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1).sp) else Text(tab,fontSize=27.sp,fontWeight=FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                if(tab=="媒体库")TextButton(onClick={sourceSheet=true},modifier=Modifier.widthIn(max=sourceWidth).semantics {contentDescription="切换来源"}) {
                                    Icon(Icons.Rounded.Storage,null,Modifier.size(17.dp),tint=MobileStyle.Accent);Spacer(Modifier.width(7.dp))
                                    Text(state.connection?.name ?: "连接媒体库",color=MobileStyle.Text.copy(alpha=.82f),maxLines=1,overflow=TextOverflow.Ellipsis)
                                    Icon(Icons.Rounded.ExpandMore,null,Modifier.size(18.dp))
                                }
                                if(tab!="设置")IconButton(onClick=::addSource){Icon(Icons.Rounded.Add,"添加来源")}
                            }
                        }
                        if(!editor && state.connection?.baseUrl?.startsWith("http://",true)==true) {
                            Text("HTTP · 未加密",Modifier.padding(horizontal=if(wide)32.dp else 20.dp,vertical=2.dp),color=androidx.compose.ui.graphics.Color(0xFFFFB74D),fontSize=11.sp)
                        }
                        if(!editor && tab=="媒体库" && state.connection?.kind==SourceKind.WEBDAV) {
                            WebDavModeSwitcher(model,state.wall,state.busy,wide) { wall ->
                                homeJump++
                                if(wall)model.showWall() else model.showFiles()
                                // 模式切换会替换列表结构，下一次布局时回到顶部，避免旧内容锚点把分类留在屏幕外。
                                libraryScroll.requestScrollToItem(0)
                            }
                        }
                        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp),color=MobileStyle.Accent,trackColor=MobileStyle.Surface)
                        if(!editor) {
                            state.error?.let { error ->
                                if(state.failedItem!=null)Box(Modifier.padding(horizontal=24.dp)){PlaybackRecovery(error,state.failureKind,state.connection?.kind!=SourceKind.WEBDAV,state.busy,{model.retryPlayback()},{model.retryPlayback(true)},{state.connection?.let(::editSource)})}
                                else TextButton(onClick=model::clearMessage,modifier=Modifier.padding(horizontal=16.dp)){Text(error)}
                            }
                            state.warning?.let {Text(it,Modifier.padding(horizontal=24.dp,vertical=6.dp),color=MobileStyle.Muted,fontSize=12.sp)}
                        }
                        Box(Modifier.weight(1f)) {
                            if(editor) ConnectionEditor(model,editingId?.let(model::editableConnection)) {editor=false;tab="媒体库"}
                            else holder.SaveableStateProvider(tab) {
                                when(tab) {
                                    "设置"->MetadataSettings(model)
                                    else->when {
                                        state.connection==null -> Welcome(onConnect=::addSource)
                                        state.selected!=null -> CinemaDetail(model,state.selected!!,loader,wide)
                                        else -> CinemaLibrary(model,loader,wide,libraryScroll,homeJump)
                                    }
                                }
                            }
                        }
                        if(!wide && !editor) MobileBottomNavigation(tab, ::navigate)
                    }
                }
            }
        }
        if(sourceSheet && state.playing==null)ModalBottomSheet(onDismissRequest={sourceSheet=false},
            sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MobileStyle.Surface) {
            Text("切换来源",fontSize=22.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(horizontal=24.dp,vertical=8.dp))
            SourceChoices(model,onAdd=::addSource,onEdit=::editSource,onSelect={connection->
                if(model.hasActiveConnection(connection.id)) {
                    sourceSheet=false
                    if(state.connection?.id!=connection.id)model.selectConnection(connection)
                    tab="媒体库"
                } else editSource(connection)
            })
        }
    }
}

@Composable private fun SourceChoices(model:PlayerModel,onAdd:()->Unit,onEdit:(SourceConnection)->Unit,onSelect:(SourceConnection)->Unit) {
    val state by model.state.collectAsState()
    LazyColumn(Modifier.fillMaxWidth().heightIn(max=520.dp),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Text("管理你的服务器。连接失败时，可直接编辑已有配置。",color=MobileStyle.Muted,fontSize=13.sp)}
        items(state.connections.sortedByDescending {it.id==state.connection?.id},key={it.id}) {c ->
            Surface(color=MobileStyle.Surface,shape=RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable(enabled=!state.busy){onSelect(c)}.padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Dns,null,tint=MobileStyle.Accent);Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name)
                            if(state.drafts.any{it.id==c.id})Text(if(model.hasActiveConnection(c.id))"修改待验证 · 原配置保留" else "待验证草稿",color=MobileStyle.Accent,fontSize=11.sp)
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(c.kind.name,color=MobileStyle.Muted,fontSize=11.sp)
                                val http=c.baseUrl.startsWith("http://",true)
                                Text(if(http)"HTTP 未加密" else "HTTPS",color=if(http)androidx.compose.ui.graphics.Color(0xFFFFB74D) else MobileStyle.Accent,fontSize=11.sp)
                            }
                            Text(c.baseUrl,color=MobileStyle.Muted,fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                        if(state.connection?.id==c.id)Icon(Icons.Rounded.CheckCircle,"当前来源",tint=MobileStyle.Accent,modifier=Modifier.size(19.dp))
                    }
                    IconButton(onClick={onEdit(c)},enabled=!state.busy){Icon(Icons.Rounded.Edit,"编辑 ${c.name}")}
                }
            }
        }
        item {OutlinedButton(onClick=onAdd,enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Icon(Icons.Rounded.Add,null);Text("添加来源")}}
    }
}
@Composable private fun Welcome(onConnect:()->Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.PlayCircle,null,Modifier.size(64.dp),tint=MobileStyle.Accent)
        Spacer(Modifier.height(26.dp))
        Text("好电影，值得好好看。",fontSize=28.sp,fontWeight=FontWeight.SemiBold)
        Text("连接你的私人媒体库，\n让每一块屏幕都成为影院。",color=MobileStyle.Muted,lineHeight=24.sp,modifier=Modifier.padding(vertical=18.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        Button(onClick=onConnect,shape=RoundedCornerShape(10.dp),contentPadding=PaddingValues(horizontal=28.dp,vertical=14.dp)){Icon(Icons.Rounded.Add,null);Spacer(Modifier.width(8.dp));Text("添加媒体来源")}
        Text("EMBY   ·   JELLYFIN   ·   WEBDAV",color=MobileStyle.Muted.copy(alpha=.65f),fontSize=10.sp,letterSpacing=2.sp,modifier=Modifier.padding(top=24.dp))
    }
}
@Composable private fun CinemaDetail(model:PlayerModel,item:MediaItem,loader:ImageLoader,wide:Boolean) {
    val state by model.state.collectAsState()
    var matching by remember(item.id){mutableStateOf(false)}
    var query by remember(item.id){mutableStateOf(item.series ?: item.title)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(if(wide)340.dp else 280.dp)) {
                VideoArtwork(model,item,loader,Modifier.fillMaxSize(),hero=true)
                IconButton(onClick=model::back,modifier=Modifier.padding(16.dp).background(Color.Black.copy(alpha=.4f),CircleShape)){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"返回媒体库")}
                Text(item.title,fontSize=30.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.align(Alignment.BottomStart).padding(24.dp))
            }
        }
        item {
            Column(Modifier.padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Text(listOfNotNull(item.year?.toString(),item.series,item.season?.let{"第 $it 季 · 第 ${item.episode ?: 0} 集"}).joinToString("  ·  "),color=MobileStyle.Muted,fontSize=12.sp)
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Button(onClick={model.play(item)},enabled=!state.busy,shape=RoundedCornerShape(10.dp),modifier=Modifier.weight(1f)){Icon(Icons.Rounded.PlayArrow,null);Spacer(Modifier.width(6.dp));Text("播放 / 续播")}
                    if(state.connection?.kind!=SourceKind.WEBDAV)OutlinedButton(onClick={model.play(item,true)},enabled=!state.busy,shape=RoundedCornerShape(10.dp)){Text("兼容播放")}
                }
                if(state.connection?.kind!=SourceKind.WEBDAV)ServerUserActions(model,state,item)
                MetadataLabels(item)
                if(item.overview.isNotBlank()) {Text("剧情简介",fontSize=17.sp,fontWeight=FontWeight.Medium);Text(item.overview,color=MobileStyle.Muted,lineHeight=23.sp,fontSize=14.sp)}
                if(state.connection?.kind==SourceKind.WEBDAV)TextButton(onClick={matching=!matching}){Icon(Icons.Rounded.Edit,null,Modifier.size(15.dp));Spacer(Modifier.width(6.dp));Text("匹配影片资料")}
                if(matching) {
                    OutlinedTextField(query,{query=it},singleLine=true,label={Text("匹配标题")},modifier=Modifier.fillMaxWidth())
                    Row {TextButton(onClick={model.searchMetadata(query)},enabled=!state.busy){Text("查询 TMDB")};TextButton(onClick={model.match(null)}){Text("取消匹配")}}
                    state.metadataCandidates.forEach {m->OutlinedButton(onClick={model.match(m)}){Text("${m.title} · ${m.year ?: ""}")}}
                }
            }
        }
    }
}
@Composable private fun MetadataSettings(model:PlayerModel) {
    val state by model.state.collectAsState()
    var token by remember{mutableStateOf("")}
    var enabled by androidx.compose.runtime.saveable.rememberSaveable{mutableStateOf(state.tmdbEnabled)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Text("影视资料",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("联网获取影视资料");Text("TMDB · 默认关闭",color=MobileStyle.Muted,fontSize=11.sp)};Switch(enabled,{enabled=it})}
        Text("本地 NFO 和图片始终可用。开启后扫描与手动匹配会查询影视标题，不发送媒体地址和账号。",color=MobileStyle.Muted,lineHeight=22.sp,fontSize=13.sp)
        OutlinedTextField(token,{token=it},singleLine=true,label={Text("Read Access Token，留空保留原值")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
        Button(onClick={model.configureTmdb(enabled,token);token=""},modifier=Modifier.fillMaxWidth()){Text("保存设置")}
        HorizontalDivider(color=MobileStyle.Border)
        ScanOptions(model)
        HorizontalDivider(color=MobileStyle.Border)
        Text("播放手势",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
        GestureOptions(model)
        Text("双击底部媒体库：返回当前来源首页\n左右双击：按设置快退 / 快进\n左侧上下滑动：亮度\n右侧上下滑动：音量\n横向滑动：定位\n长按：按手势设置临时加速",color=MobileStyle.Muted,lineHeight=26.sp,fontSize=14.sp)
        Text("预览帧仅缓存在当前设备，缓存上限 64 MiB。清理系统缓存后将按需重新生成。",color=MobileStyle.Muted,fontSize=12.sp)
        Text("This product uses the TMDB API but is not endorsed or certified by TMDB.",fontSize=10.sp,color=MobileStyle.Muted)
    }
}
