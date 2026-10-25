package app.umuo.mobileui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.umuo.android.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun CinemaPlayer(model:PlayerModel,onPip:()->Unit,onEditConnection:()->Unit) {
    val browser by model.state.collectAsState()
    val state by model.engine.state.collectAsState()
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
    val activity=LocalContext.current as? Activity
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
    LaunchedEffect(controls,state.playing,panel,feedbackVersion,dragging!=null) {
        if(controls && state.playing && panel==null && dragging==null){delay(5_000);controls=false}
    }
    LaunchedEffect(locked,unlockVisible,feedbackVersion) {if(locked && unlockVisible){delay(4_000);unlockVisible=false}}
    LaunchedEffect(state.error){if(state.error!=null){locked=false;controls=true}}
    BackHandler {when {locked->{unlockVisible=true;showFeedback(GestureFeedback("已锁定，请先解锁"))};panel!=null->panel=null;else->model.stop()}}
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PlayerSurface(model,Modifier.fillMaxSize(),onSingleTap={if(locked)unlockVisible=!unlockVisible else controls=!controls},onFeedback={showFeedback(it)},gesturesLocked=locked)
        if(state.buffering)CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp),color=MobileStyle.Accent,strokeWidth=2.dp)
        AnimatedVisibility(visible=controls && !locked,enter=fadeIn(),exit=fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.8f),Color.Transparent)))
                    .statusBarsPadding().padding(horizontal=14.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=model::stop){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"结束播放",tint=Color.White)}
                    Column(Modifier.weight(1f).padding(start=8.dp)) {
                        Text(browser.playing?.title.orEmpty(),color=Color.White,fontSize=16.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(browser.connection?.name.orEmpty(),color=Color.White.copy(alpha=.5f),fontSize=10.sp,modifier=Modifier.padding(top=3.dp))
                    }
                    IconButton(onClick={panel="手势设置"}){Icon(Icons.Rounded.Tune,"手势设置",tint=Color.White)}
                    IconButton(onClick={panel="诊断"}){Icon(Icons.Rounded.MoreHoriz,"播放诊断",tint=Color.White)}
                }
                Row(Modifier.align(Alignment.Center),horizontalArrangement=Arrangement.spacedBy(if(landscape)40.dp else 28.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(enabled=state.seekable,onClick={model.engine.seekBy(-gestures.seekSeconds*1000L);showFeedback(GestureFeedback("−${gestures.seekSeconds}s",side=-1))},modifier=Modifier.size(48.dp)){Icon(when(gestures.seekSeconds){5->Icons.Rounded.Replay5;30->Icons.Rounded.Replay30;else->Icons.Rounded.Replay10},"−${gestures.seekSeconds} 秒",tint=Color.White,modifier=Modifier.size(32.dp))}
                    FilledIconButton(onClick={controls=true;model.engine.toggle()},modifier=Modifier.size(68.dp),shape=CircleShape,
                        colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color.White,contentColor=Color.Black)) {
                        Icon(when {state.ended->Icons.Rounded.Replay;state.playing->Icons.Rounded.Pause;else->Icons.Rounded.PlayArrow},
                            when {state.ended->"重新播放";state.playing->"暂停";else->"播放"},modifier=Modifier.size(36.dp))
                    }
                    IconButton(enabled=state.seekable,onClick={model.engine.seekBy(gestures.seekSeconds*1000L);showFeedback(GestureFeedback("+${gestures.seekSeconds}s",side=1))},modifier=Modifier.size(48.dp)){Icon(when(gestures.seekSeconds){5->Icons.Rounded.Forward5;30->Icons.Rounded.Forward30;else->Icons.Rounded.Forward10},"+${gestures.seekSeconds} 秒",tint=Color.White,modifier=Modifier.size(32.dp))}
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.92f))))
                    .navigationBarsPadding().padding(start=24.dp,end=24.dp,top=28.dp,bottom=12.dp)) {
                    state.error?.let {PlaybackRecovery(it,state.failureKind,browser.connection?.kind!=app.umuo.core.model.SourceKind.WEBDAV,browser.busy,{model.retryPlayback()},{model.retryPlayback(true)},onEditConnection)}
                    if(!state.seekable)Text("此资源仅支持顺序播放",color=MobileStyle.Muted,fontSize=11.sp)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(timeLabel((dragging?.toLong() ?: state.positionMs)),fontSize=11.sp,color=Color.White)
                        Text(timeLabel(state.durationMs),fontSize=11.sp,color=Color.White.copy(alpha=.5f))
                    }
                    Slider(value=dragging ?: state.positionMs.toFloat(),onValueChange={dragging=it;controls=true},
                        onValueChangeFinished={dragging?.let {model.engine.seekTo(it.toLong())};dragging=null},
                        valueRange=0f..state.durationMs.coerceAtLeast(1).toFloat(),enabled=state.seekable && state.durationMs>0,
                        thumb={Box(Modifier.size(10.dp).background(Color.White,CircleShape))},
                        track={Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha=.22f),CircleShape)) {
                            Box(Modifier.fillMaxWidth(((dragging ?: state.positionMs.toFloat())/state.durationMs.coerceAtLeast(1)).coerceIn(0f,1f)).fillMaxHeight().background(Color.White,CircleShape))
                        }},
                        colors=SliderDefaults.colors(thumbColor=Color.White,activeTrackColor=Color.White,inactiveTrackColor=Color.White.copy(alpha=.22f)),modifier=Modifier.fillMaxWidth().height(34.dp))
                    PlaybackToolbar(
                        speed=state.speed,
                        onSpeed={panel="播放速度"},onAudio={panel="音轨"},onSubtitles={panel="字幕"},
                        onLock={locked=true;unlockVisible=true;controls=false;panel=null;model.engine.restoreBoost()},
                        onPip=onPip,onFullscreen={activity?.requestedOrientation=if(landscape)ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE},
                        landscape=landscape
                    )
                }
            }
        }
        if(locked && unlockVisible) FilledIconButton(onClick={locked=false;controls=true},modifier=Modifier.align(Alignment.CenterStart).padding(20.dp).size(52.dp),
            colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color.Black.copy(alpha=.7f),contentColor=Color.White)) {
            Icon(Icons.Rounded.LockOpen,"解锁播放器")
        }
        feedback?.let { value ->
            val alignment=when(value.side) { -1->Alignment.CenterStart; 1->Alignment.CenterEnd; else->Alignment.Center }
            // 纯绘制层不拦截触摸，提示出现时仍可继续双击或开始下一次滑动。
            Box(Modifier.align(alignment).padding(horizontal=32.dp).widthIn(min=120.dp,max=220.dp)
                .background(Color.Black.copy(alpha=.78f),androidx.compose.foundation.shape.RoundedCornerShape(16.dp))) {
                Column(Modifier.padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(value.text,fontSize=22.sp,fontWeight=FontWeight.SemiBold,color=Color.White)
                    value.progress?.let { LinearProgressIndicator(progress={it},modifier=Modifier.width(130.dp).height(4.dp),color=MobileStyle.Accent,trackColor=Color.White.copy(alpha=.2f)) }
                }
            }
        }
        if(panel!=null)ModalBottomSheet(sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),onDismissRequest={panel=null;controls=true},containerColor=MobileStyle.Surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).navigationBarsPadding()) {
                Text(panel!!,fontSize=22.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(bottom=16.dp))
                when(panel) {
                    "手势设置"->GestureOptions(model)
                    "音轨"->if(state.audioTracks.isEmpty()) {
                        Text("暂无可切换的音轨",color=MobileStyle.Muted,modifier=Modifier.padding(bottom=24.dp))
                    } else LazyColumn(Modifier.heightIn(max=320.dp)) {items(state.audioTracks,key={it.id}) {t->ListItem(modifier=Modifier.clickable{model.engine.selectAudio(t.id)},headlineContent={Text(t.label)},leadingContent={RadioButton(t.selected,null)},colors=ListItemDefaults.colors(containerColor=MobileStyle.Surface))}}
                    "字幕"->LazyColumn(Modifier.heightIn(max=320.dp)) {
                        item {ListItem(modifier=Modifier.clickable{model.engine.selectSubtitle(null)},headlineContent={Text("关闭字幕")},leadingContent={RadioButton(state.subtitles.none{it.selected},null)},colors=ListItemDefaults.colors(containerColor=MobileStyle.Surface))}
                        if(state.subtitles.isEmpty())item {Text("此视频没有可用字幕",color=MobileStyle.Muted,modifier=Modifier.padding(vertical=16.dp))}
                        items(state.subtitles,key={it.id}){t->ListItem(modifier=Modifier.clickable{model.engine.selectSubtitle(t.id)},headlineContent={Text(t.label)},supportingContent={t.detail?.let {Text(it,color=MobileStyle.Muted)}},leadingContent={RadioButton(t.selected,null)},colors=ListItemDefaults.colors(containerColor=MobileStyle.Surface))}
                    }
                    "播放速度"->FlowRow(Modifier.fillMaxWidth().padding(bottom=20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        listOf(.5f,.75f,1f,1.25f,1.5f,2f,3f).forEach {speed->
                            FilterChip(selected=state.speed==speed,onClick={model.engine.setSpeed(speed);panel=null;controls=true},label={Text("$speed×")})
                        }
                    }
                    else->Text("首帧 ${metrics.firstFrameMs ?: "—"} ms\n定位后 READY ${metrics.seekReadyMs ?: "—"} ms\n${if(state.seekable)"支持定位" else "顺序播放"}",color=MobileStyle.Muted,lineHeight=25.sp,modifier=Modifier.padding(bottom=24.dp))
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
