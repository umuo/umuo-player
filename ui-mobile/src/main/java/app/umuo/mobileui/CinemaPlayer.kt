package app.umuo.mobileui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.umuo.android.*
import app.umuo.core.playback.PlaybackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun CinemaPlayer(
    model: PlayerModel,
    inPip: Boolean = false,
    onPip: () -> Unit,
    onEditConnection: () -> Unit
) {
    val browser by model.state.collectAsState()
    val state by remember(model.engine) {
        model.engine.state.map { it.copy(positionMs = 0L) }.distinctUntilChanged()
    }.collectAsState(initial = PlaybackState())
    val metrics by model.engine.metrics.collectAsState()
    val gestures by model.playbackPreferences.state.collectAsState()
    var locked by rememberSaveable(browser.playing?.id?.value) {mutableStateOf(false)}
    var unlockVisible by remember {mutableStateOf(true)}
    var feedback by remember { mutableStateOf<GestureFeedback?>(null) }
    var feedbackVersion by remember { mutableIntStateOf(0) }
    fun showFeedback(value: GestureFeedback) { feedback=value; feedbackVersion++ }
    LaunchedEffect(feedbackVersion) { delay(1_200); feedback=null }
    var controls by remember {mutableStateOf(true)}
    var panel by remember {mutableStateOf<String?>(null)}
    var dragging by remember {mutableStateOf<Float?>(null)}
    var holdBoostSpeed by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(browser.playing?.id) { holdBoostSpeed = null }
    LaunchedEffect(inPip) {
        if (inPip) {
            panel = null
            feedback = null
            holdBoostSpeed = null
        }
    }
    val activity = LocalActivity.current
    val landscape=LocalConfiguration.current.orientation==Configuration.ORIENTATION_LANDSCAPE
    DisposableEffect(activity) {
        val original=activity?.requestedOrientation
        val originalBrightness=activity?.window?.attributes?.screenBrightness
        activity?.window?.let {window->WindowCompat.getInsetsController(window,window.decorView).apply {
            systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }}
        onDispose {
            activity?.window?.let {WindowCompat.getInsetsController(it,it.decorView).show(WindowInsetsCompat.Type.systemBars())}
            if(original!=null)activity?.requestedOrientation=original
            if(originalBrightness!=null)activity?.window?.let { window -> window.attributes=window.attributes.apply {screenBrightness=originalBrightness} }
        }
    }
    val activeError = state.error ?: browser.error
    val activeKind = state.failureKind ?: browser.failureKind
    LaunchedEffect(controls,state.playing,panel,feedbackVersion,dragging!=null,activeError,inPip) {
        if(!inPip && controls && state.playing && panel==null && dragging==null && activeError==null){delay(5_000);controls=false}
    }
    LaunchedEffect(locked,unlockVisible,feedbackVersion) {if(locked && unlockVisible){delay(4_000);unlockVisible=false}}
    LaunchedEffect(activeError){if(activeError!=null){locked=false;controls=true}}
    BackHandler(enabled = !inPip) {when {locked->{unlockVisible=true;showFeedback(GestureFeedback("已锁定，请先解锁"))};panel!=null->panel=null;else->model.stop()}}
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PlayerSurface(
            model = model,
            modifier = Modifier.fillMaxSize(),
            touch = !inPip,
            onSingleTap = { if (locked) unlockVisible = !unlockVisible else if (activeError == null) controls = !controls else controls = true },
            onFeedback = { showFeedback(it) },
            onHoldBoostChanged = { holdBoostSpeed = it },
            gesturesLocked = locked
        )
        if(!inPip && state.buffering)CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp),color=MobileStyle.Accent,strokeWidth=2.dp)
        AnimatedVisibility(visible=!inPip && controls && !locked,enter=fadeIn(),exit=fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.8f),Color.Transparent)))
                    .statusBarsPadding().padding(horizontal=14.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=model::stop){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"结束播放",tint=Color.White)}
                    Column(Modifier.weight(1f).padding(start=8.dp)) {
                        Text(browser.playing?.title.orEmpty(),color=Color.White,fontSize=16.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                        val httpWarning = if (browser.connection?.baseUrl?.startsWith("http://", true) == true) " · HTTP 未加密" else ""
                        Text(browser.connection?.name.orEmpty() + httpWarning,color=Color.White.copy(alpha=if (httpWarning.isNotEmpty()) .75f else .5f),fontSize=10.sp,modifier=Modifier.padding(top=3.dp))
                    }
                    PlayerClockText()
                    IconButton(onClick={panel="手势设置"}){Icon(Icons.Rounded.Tune,"手势设置",tint=Color.White)}
                    IconButton(onClick={panel="诊断"}){Icon(Icons.Rounded.MoreHoriz,"播放诊断",tint=Color.White)}
                }
                Row(Modifier.align(Alignment.Center),horizontalArrangement=Arrangement.spacedBy(if(landscape)40.dp else 28.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(enabled=state.seekable,onClick={model.engine.seekBy(-gestures.seekSeconds*1000L);showFeedback(GestureFeedback("−${gestures.seekSeconds}s",side=-1,kind=GestureKind.SEEK_BACK))},modifier=Modifier.size(48.dp)){Icon(when(gestures.seekSeconds){5->Icons.Rounded.Replay5;30->Icons.Rounded.Replay30;else->Icons.Rounded.Replay10},"−${gestures.seekSeconds} 秒",tint=Color.White,modifier=Modifier.size(32.dp))}
                    FilledIconButton(onClick={controls=true;model.engine.toggle()},modifier=Modifier.size(68.dp),shape=CircleShape,
                        colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color.White,contentColor=Color.Black)) {
                        Icon(when {state.ended->Icons.Rounded.Replay;state.playing->Icons.Rounded.Pause;else->Icons.Rounded.PlayArrow},
                            when {state.ended->"重新播放";state.playing->"暂停";else->"播放"},modifier=Modifier.size(36.dp))
                    }
                    IconButton(enabled=state.seekable,onClick={model.engine.seekBy(gestures.seekSeconds*1000L);showFeedback(GestureFeedback("+${gestures.seekSeconds}s",side=1,kind=GestureKind.SEEK_FORWARD))},modifier=Modifier.size(48.dp)){Icon(when(gestures.seekSeconds){5->Icons.Rounded.Forward5;30->Icons.Rounded.Forward30;else->Icons.Rounded.Forward10},"+${gestures.seekSeconds} 秒",tint=Color.White,modifier=Modifier.size(32.dp))}
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.92f))))
                    .navigationBarsPadding().padding(start=24.dp,end=24.dp,top=28.dp,bottom=12.dp)) {
                    activeError?.let {PlaybackRecovery(it,activeKind,browser.connection?.kind!=app.umuo.core.model.SourceKind.WEBDAV,browser.busy,{model.retryPlayback()},{model.retryPlayback(true)},onEditConnection)}
                    if(!state.seekable)Text("此资源仅支持顺序播放",color=MobileStyle.Muted,fontSize=11.sp)
                    MobilePlaybackProgress(
                        engine = model.engine,
                        dragging = dragging,
                        onDragChange = { dragging = it; controls = true },
                        onDragFinished = { dragging?.let { model.engine.seekTo(it.toLong()) }; dragging = null }
                    )
                    PlaybackToolbar(
                        speed=state.speed,
                        scaleLabel=gestures.scaleMode.label,
                        onSpeed={panel="播放速度"},onAudio={panel="音轨"},onSubtitles={panel="字幕"},
                        onCycleScale={
                            val next = gestures.scaleMode.next()
                            model.playbackPreferences.update(gestures.copy(scaleMode = next))
                            controls = true
                            showFeedback(GestureFeedback("画面：${next.label}", kind = GestureKind.SCALE))
                        },
                        onEpisodes=if(browser.seriesEpisodes.size > 1) { { panel = "选集" } } else null,
                        onNextEpisode=if(browser.nextEpisode != null) model::playNextEpisode else null,
                        onLock={locked=true;unlockVisible=true;controls=false;panel=null;model.engine.restoreBoost()},
                        onPip=onPip,onFullscreen={activity?.requestedOrientation=if(landscape)ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE},
                        landscape=landscape
                    )
                }
            }
        }
        if(!inPip && locked && unlockVisible) FilledIconButton(onClick={locked=false;controls=true},modifier=Modifier.align(Alignment.CenterStart).padding(20.dp).size(52.dp),
            colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color.Black.copy(alpha=.7f),contentColor=Color.White)) {
            Icon(Icons.Rounded.LockOpen,"解锁播放器")
        }
        if (!inPip) holdBoostSpeed?.let { speed ->
            Surface(
                color = Color.Black.copy(alpha = 0.78f),
                shape = CircleShape,
                border = BorderStroke(0.5.dp, MobileStyle.Accent.copy(alpha = 0.6f)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 18.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Rounded.FastForward, null, tint = MobileStyle.Accent, modifier = Modifier.size(16.dp))
                    Text("${speed}× 倍速播放中", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
        if(!inPip) feedback?.let { value ->
            val alignment=when(value.side) { -1->Alignment.CenterStart; 1->Alignment.CenterEnd; else->Alignment.Center }
            val icon = when (value.kind) {
                GestureKind.SEEK_BACK -> Icons.Rounded.FastRewind
                GestureKind.SEEK_FORWARD -> Icons.Rounded.FastForward
                GestureKind.BRIGHTNESS -> Icons.Rounded.Brightness6
                GestureKind.VOLUME -> Icons.AutoMirrored.Rounded.VolumeUp
                GestureKind.SCALE -> Icons.Rounded.AspectRatio
                GestureKind.INFO -> Icons.Rounded.Info
            }
            // 纯绘制层不拦截触摸，提示出现时仍可继续双击或开始下一次滑动。
            Surface(
                color = Color.Black.copy(alpha = 0.80f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.14f)),
                modifier = Modifier.align(alignment).padding(horizontal = 32.dp).widthIn(min = 126.dp, max = 230.dp)
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(icon, null, tint = MobileStyle.Accent, modifier = Modifier.size(22.dp))
                    Text(value.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    value.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.width(130.dp).height(4.dp), color = MobileStyle.Accent, trackColor = Color.White.copy(alpha = 0.2f)) }
                }
            }
        }
        if(!inPip && panel!=null)ModalBottomSheet(sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),onDismissRequest={panel=null;controls=true},containerColor=MobileStyle.Surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).navigationBarsPadding()) {
                Text(panel!!,fontSize=22.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(bottom=16.dp))
                when(panel) {
                    "手势设置"->GestureOptions(model)
                    "音轨"->if(state.audioTracks.isEmpty()) {
                        Text("暂无可切换的音轨",color=MobileStyle.Muted,modifier=Modifier.padding(bottom=24.dp))
                    } else LazyColumn(Modifier.heightIn(max=320.dp)) {items(state.audioTracks,key={it.id}) {t->ListItem(modifier=Modifier.clickable{model.engine.selectAudio(t.id)},headlineContent={Text(t.label)},leadingContent={RadioButton(t.selected,null)},colors=ListItemDefaults.colors(containerColor=MobileStyle.Surface))}}
                    "字幕" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("字幕大小", color = MobileStyle.Muted, fontSize = 12.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(SubtitleSize.entries) { size ->
                                FilterChip(
                                    selected = gestures.subtitleSize == size,
                                    onClick = { model.playbackPreferences.update(gestures.copy(subtitleSize = size)) },
                                    label = { Text(size.label) }
                                )
                            }
                        }
                        Text("底部位置", color = MobileStyle.Muted, fontSize = 12.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf(3, 6, 10, 14)) { pct ->
                                FilterChip(
                                    selected = gestures.subtitleBottomPercent == pct,
                                    onClick = { model.playbackPreferences.update(gestures.copy(subtitleBottomPercent = pct)) },
                                    label = { Text("$pct%") }
                                )
                            }
                        }
                        Text("字幕样式", color = MobileStyle.Muted, fontSize = 12.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(SubtitleBackground.entries) { style ->
                                FilterChip(
                                    selected = gestures.subtitleBackground == style,
                                    onClick = { model.playbackPreferences.update(gestures.copy(subtitleBackground = style)) },
                                    label = { Text(style.label) }
                                )
                            }
                        }
                        HorizontalDivider(color = MobileStyle.Border)
                        LazyColumn(Modifier.heightIn(max = 260.dp)) {
                            item { ListItem(modifier = Modifier.clickable { model.engine.selectSubtitle(null) }, headlineContent = { Text("关闭字幕") }, leadingContent = { RadioButton(state.subtitles.none { it.selected }, null) }, colors = ListItemDefaults.colors(containerColor = MobileStyle.Surface)) }
                            if (state.subtitles.isEmpty()) item { Text("此视频没有可用字幕", color = MobileStyle.Muted, modifier = Modifier.padding(vertical = 16.dp)) }
                            items(state.subtitles, key = { it.id }) { t -> ListItem(modifier = Modifier.clickable { model.engine.selectSubtitle(t.id) }, headlineContent = { Text(t.label) }, supportingContent = { t.detail?.let { Text(it, color = MobileStyle.Muted) } }, leadingContent = { RadioButton(t.selected, null) }, colors = ListItemDefaults.colors(containerColor = MobileStyle.Surface)) }
                        }
                    }
                    "选集" -> {
                        val epState = rememberLazyListState()
                        val currentId = browser.playing?.id
                        LaunchedEffect(panel, currentId) {
                            val idx = browser.seriesEpisodes.indexOfFirst { it.id == currentId }
                            if (idx >= 0) epState.scrollToItem(idx)
                        }
                        LazyColumn(state = epState, modifier = Modifier.heightIn(max = 360.dp)) {
                            items(browser.seriesEpisodes, key = { it.id.value }) { ep ->
                                val active = ep.id == currentId
                                val label = listOfNotNull(
                                    ep.episode?.let { "S${ep.season ?: 1}E$it" },
                                    ep.durationMs.takeIf { it > 0L }?.let(::timeLabel),
                                    if (active) "正在播放" else if (ep.resumeMs > 0L) "看到 ${timeLabel(ep.resumeMs)}" else null
                                ).joinToString(" · ")
                                ListItem(
                                    modifier = Modifier.clickable {
                                        panel = null
                                        if (!active) model.play(ep)
                                    },
                                    headlineContent = {
                                        Text(
                                            episodeDisplayTitle(ep),
                                            color = if (active) MobileStyle.Accent else MobileStyle.Text,
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    supportingContent = if (label.isNotEmpty()) { { Text(label, color = MobileStyle.Muted) } } else null,
                                    leadingContent = { RadioButton(active, null) },
                                    colors = ListItemDefaults.colors(containerColor = MobileStyle.Surface)
                                )
                            }
                        }
                    }
                    "播放速度"->FlowRow(Modifier.fillMaxWidth().padding(bottom=20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        listOf(.5f,.75f,1f,1.25f,1.5f,2f,3f).forEach {speed->
                            FilterChip(selected=state.speed==speed,onClick={model.engine.setSpeed(speed);panel=null;controls=true},label={Text("$speed×")})
                        }
                    }
                    else -> {
                        val diagnosticItems = listOf(
                            "首帧耗时" to (metrics.firstFrameMs?.let { "${it} ms" } ?: "—"),
                            "定位耗时" to (metrics.seekReadyMs?.let { "${it} ms" } ?: "—"),
                            "定位能力" to (if (state.seekable) "支持定位" else "顺序播放"),
                            "缓冲状态" to (if (state.buffering) "缓冲中" else if (state.playing) "播放正常" else "已就绪")
                        )
                        Column(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            diagnosticItems.chunked(2).forEach { pair ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    pair.forEach { (label, value) ->
                                        Surface(
                                            color = MobileStyle.Elevated,
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(0.5.dp, MobileStyle.Border),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(label, color = MobileStyle.Muted, fontSize = 11.sp)
                                                Text(value, color = MobileStyle.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobilePlaybackProgress(
    engine: Media3Engine,
    dragging: Float?,
    onDragChange: (Float) -> Unit,
    onDragFinished: () -> Unit
) {
    val state by engine.state.collectAsState()
    if (dragging != null) {
        val targetMs = dragging.toLong()
        val deltaSec = (targetMs - state.positionMs) / 1000L
        val deltaText = "${if (deltaSec >= 0L) "+" else "−"}${kotlin.math.abs(deltaSec)}s"
        Box(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Surface(
                color = MobileStyle.Elevated,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(0.5.dp, MobileStyle.Border),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(timeLabel(targetMs), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(deltaText, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MobileStyle.Accent)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(timeLabel(dragging?.toLong() ?: state.positionMs), fontSize = 11.sp, color = Color.White)
        Text(timeLabel(state.durationMs), fontSize = 11.sp, color = Color.White.copy(alpha = .5f))
    }
    Slider(
        value = dragging ?: state.positionMs.toFloat(),
        onValueChange = onDragChange,
        onValueChangeFinished = onDragFinished,
        valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(),
        enabled = state.seekable && state.durationMs > 0,
        thumb = { Box(Modifier.size(if (dragging != null) 16.dp else 10.dp).background(Color.White, CircleShape)) },
        track = {
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .22f), CircleShape)) {
                Box(
                    Modifier
                        .fillMaxWidth(((dragging ?: state.positionMs.toFloat()) / state.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color.White, CircleShape)
                )
            }
        },
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .22f)),
        modifier = Modifier.fillMaxWidth().height(34.dp)
    )
}

@Composable
private fun PlayerClockText() {
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        while (true) {
            clock = format.format(java.util.Date())
            delay(30_000L)
        }
    }
    if (clock.isNotEmpty()) {
        Text(
            clock,
            color = Color.White.copy(alpha = .75f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(end = 4.dp)
        )
    }
}
