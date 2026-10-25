@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.umuo.android

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import app.umuo.core.playback.GestureMachine
import app.umuo.core.playback.brightnessAfterDrag
import kotlin.math.abs
import kotlinx.coroutines.*
import kotlin.coroutines.resume

data class GestureFeedback(val text: String, val progress: Float? = null, val side: Int = 0)

@Composable fun PlayerSurface(model: PlayerModel, modifier: Modifier = Modifier, touch: Boolean = true, onSingleTap: (() -> Unit)? = null, onFeedback: ((GestureFeedback) -> Unit)? = null, gesturesLocked: Boolean = false) {
    val playing by model.state.collectAsState()
    val currentPlayer by model.engine.players.collectAsState()
    val settings by model.playbackPreferences.state.collectAsState()
    key(playing.playing?.id) {
        AndroidView(modifier = modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            model.videoBounds = android.graphics.Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
        }, factory = { context ->
            android.widget.FrameLayout(context).apply {
                addView(PlayerView(context).apply { player = currentPlayer; useController = false; keepScreenOn = true
                    subtitleView?.applyCinemaAppearance() },
                    android.widget.FrameLayout.LayoutParams(-1, -1))
                if(touch) addView(GestureOverlay(context, model.engine, onSingleTap, onFeedback,settings,gesturesLocked), android.widget.FrameLayout.LayoutParams(-1, -1))
                model.captureFrame = capture@{
                    val item = model.state.value.playing ?: return@capture
                    val surface = (getChildAt(0) as PlayerView).videoSurfaceView as? SurfaceView ?: return@capture
                    if(!model.engine.state.value.ready || !surface.holder.surface.isValid || surface.width <= 0 || surface.height <= 0) return@capture
                    val bitmap = android.graphics.Bitmap.createBitmap(640, (640f*surface.height/surface.width).toInt().coerceIn(1,1280), android.graphics.Bitmap.Config.ARGB_8888)
                    val ok = suspendCancellableCoroutine<Boolean> { continuation ->
                        try { PixelCopy.request(surface, bitmap, { result ->
                            if(continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS) else bitmap.recycle()
                        }, Handler(Looper.getMainLooper())) }
                        catch(_: IllegalArgumentException) { continuation.resume(false) }
                    }
                    if(ok) model.thumbnails.saveFrame(item, bitmap) else bitmap.recycle()
                }
            }
        }, update = { frame -> (frame.getChildAt(0) as PlayerView).player = currentPlayer
            (frame.getChildAt(1) as? GestureOverlay)?.apply { this.onSingleTap = onSingleTap; this.onFeedback = onFeedback; this.settings=settings; this.locked=gesturesLocked }
        },
            onRelease = { frame -> model.captureFrame = null; (frame.getChildAt(0) as PlayerView).player = null })
        LaunchedEffect(playing.playing?.id) { while(isActive) { delay(15_000); model.captureFrame?.invoke() } }
    }
}
/** 从 Peach 的独立手势状态机迁移；取消、松开和离开页面均恢复长按倍率。 */
private class GestureOverlay(context: Context, val engine: Media3Engine, var onSingleTap: (() -> Unit)?, var onFeedback: ((GestureFeedback) -> Unit)?, var settings: GestureSettings, lockedInitially: Boolean) : View(context) {
    var locked = lockedInitially
        set(value) {
            if(value && !field) { handler.removeCallbacksAndMessages(null);gestures.release();engine.restoreBoost();dragged=false;held=false }
            field=value
        }
    private val gestures = GestureMachine()
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var x = 0f; private var y = 0f; private var position = 0L
    private var brightness = .5f; private var volume = 0
    private var viewportHeight = 0
    private var dragged = false; private var held = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val hold = Runnable { if(gestures.hold()) { held = true; engine.boost(settings.holdSpeed); onFeedback?.invoke(GestureFeedback("${settings.holdSpeed}× 倍速播放")) } }
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if(!dragged && !held) performClick(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if(locked)return true
            handler.removeCallbacks(hold); engine.restoreBoost()
            if((e.x < width*.35f || e.x > width*.65f) && !engine.state.value.seekable) {
                onFeedback?.invoke(GestureFeedback("此资源不支持定位")); return true
            }
            if(e.x < width*.35f) { engine.seekBy(-settings.seekSeconds*1000L); onFeedback?.invoke(GestureFeedback("−${settings.seekSeconds}s", side=-1)) }
            else if(e.x > width*.65f) { engine.seekBy(settings.seekSeconds*1000L); onFeedback?.invoke(GestureFeedback("+${settings.seekSeconds}s", side=1)) }
            else engine.toggle()
            return true
        }
    }).apply { setIsLongpressEnabled(false) }
    init { isClickable = true; contentDescription = "视频：单击显示控制栏，左右双击快进快退，长按临时加速" }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if(locked) { detector.onTouchEvent(e);return true }
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x=e.x; y=e.y; viewportHeight=height; position=engine.state.value.positionMs; dragged=false; held=false
                brightness=(context as? Activity)?.window?.attributes?.screenBrightness?.takeIf { it >= 0 }
                    ?: (android.provider.Settings.System.getInt(context.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS,128)/255f).coerceIn(.02f,1f)
                volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                gestures.down(engine.state.value.speed); handler.postDelayed(hold,350)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx=(e.x-x)*settings.sensitivity; val dy=(e.y-y)*settings.sensitivity
                if(abs(dx)>slop || abs(dy)>slop) {
                    handler.removeCallbacks(hold)
                    if(gestures.drag(abs(dx)>=abs(dy), x<width/2f)) {
                        dragged=true
                        when(gestures.mode) {
                            GestureMachine.Mode.SEEK -> {
                                val target=(position+dx/width.coerceAtLeast(1)*120_000).toLong().coerceIn(0,engine.state.value.durationMs.coerceAtLeast(0))
                                engine.seekTo(target)
                                val delta=(target-position)/1000
                                onFeedback?.invoke(GestureFeedback("${if(delta>=0) "+" else ""}${delta}s · ${timeLabel(target)}"))
                            }
                            GestureMachine.Mode.BRIGHTNESS -> {
                                val level=brightnessAfterDrag(brightness,dy,viewportHeight)
                                (context as? Activity)?.window?.let { window ->
                                    window.attributes=window.attributes.apply {screenBrightness=level}
                                }
                                onFeedback?.invoke(GestureFeedback("亮度 ${(level*100).toInt()}%",level))
                            }
                            GestureMachine.Mode.VOLUME -> {
                                val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                val target=(volume-dy/height.coerceAtLeast(1)*max).toInt().coerceIn(0,max)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC,target,0)
                                onFeedback?.invoke(GestureFeedback("音量 ${target*100/max}%",target.toFloat()/max))
                            }
                            else -> Unit
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(hold); gestures.release(); engine.restoreBoost() }
        }
        if(!dragged && !held) detector.onTouchEvent(e)
        return true
    }
    override fun performClick(): Boolean { super.performClick(); onSingleTap?.invoke() ?: run { engine.toggle() }; return true }
    override fun onDetachedFromWindow() { handler.removeCallbacksAndMessages(null); gestures.release(); engine.restoreBoost(); super.onDetachedFromWindow() }
}
