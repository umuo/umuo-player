@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.umuo.android

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.umuo.core.playback.GestureMachine
import app.umuo.core.playback.brightnessAfterDrag
import kotlin.math.abs
import kotlinx.coroutines.*
import kotlin.coroutines.resume

enum class GestureKind { INFO, SEEK_BACK, SEEK_FORWARD, BRIGHTNESS, VOLUME, SCALE }

data class GestureFeedback(
    val text: String,
    val progress: Float? = null,
    val side: Int = 0,
    val kind: GestureKind = GestureKind.INFO
)

private fun VideoScaleMode.toResizeMode(): Int = when (this) {
    VideoScaleMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoScaleMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    VideoScaleMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}

@Composable fun PlayerSurface(
    model: PlayerModel,
    modifier: Modifier = Modifier,
    touch: Boolean = true,
    onSingleTap: (() -> Unit)? = null,
    onFeedback: ((GestureFeedback) -> Unit)? = null,
    onHoldBoostChanged: ((Float?) -> Unit)? = null,
    gesturesLocked: Boolean = false
) {
    val playing by model.state.collectAsState()
    val currentPlayer by model.engine.players.collectAsState()
    val settings by model.playbackPreferences.state.collectAsState()
    key(playing.playing?.id) {
        var containerRef by remember { mutableStateOf<android.widget.FrameLayout?>(null) }
        fun updateVideoBounds(container: android.widget.FrameLayout?, fallback: android.graphics.Rect) {
            val surface = (container?.getChildAt(0) as? PlayerView)?.videoSurfaceView
            if (surface != null && surface.width > 0 && surface.height > 0 && surface.isAttachedToWindow) {
                val loc = IntArray(2)
                surface.getLocationInWindow(loc)
                if (loc[0] >= 0 && loc[1] >= 0) {
                    model.videoBounds = android.graphics.Rect(loc[0], loc[1], loc[0] + surface.width, loc[1] + surface.height)
                    return
                }
            }
            model.videoBounds = fallback
        }
        AndroidView(modifier = modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            val fallback = android.graphics.Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
            updateVideoBounds(containerRef, fallback)
        }, factory = { context ->
            android.widget.FrameLayout(context).apply {
                containerRef = this
                val pv = PlayerView(context).apply {
                    player = currentPlayer
                    useController = false
                    keepScreenOn = true
                    resizeMode = settings.scaleMode.toResizeMode()
                    subtitleView?.applyCinemaAppearance(settings.subtitleSize, settings.subtitleBottomPercent, settings.subtitleBackground)
                }
                addView(pv, android.widget.FrameLayout.LayoutParams(-1, -1))
                pv.videoSurfaceView?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    if (v.width > 0 && v.height > 0 && v.isAttachedToWindow) {
                        val loc = IntArray(2)
                        v.getLocationInWindow(loc)
                        if (loc[0] >= 0 && loc[1] >= 0) {
                            model.videoBounds = android.graphics.Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
                        }
                    }
                }
                addView(GestureOverlay(context, model.engine, onSingleTap, onFeedback, onHoldBoostChanged, settings, gesturesLocked, touch), android.widget.FrameLayout.LayoutParams(-1, -1))
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
        }, update = { frame ->
            (frame.getChildAt(0) as PlayerView).apply {
                player = currentPlayer
                resizeMode = settings.scaleMode.toResizeMode()
                subtitleView?.applyCinemaAppearance(settings.subtitleSize, settings.subtitleBottomPercent, settings.subtitleBackground)
            }
            (frame.getChildAt(1) as? GestureOverlay)?.apply {
                this.onSingleTap = onSingleTap
                this.onFeedback = onFeedback
                this.onHoldBoostChanged = onHoldBoostChanged
                this.settings = settings
                this.locked = gesturesLocked
                this.touchEnabled = touch
            }
        },
            onRelease = { frame -> containerRef = null; model.captureFrame = null; (frame.getChildAt(0) as PlayerView).player = null })
        LaunchedEffect(playing.playing?.id) { while(isActive) { delay(15_000); if (model.engine.state.value.playing) model.captureFrame?.invoke() } }
    }
}
/** 从 Peach 的独立手势状态机迁移；取消、松开和离开页面均恢复长按倍率。 */
private class GestureOverlay(
    context: Context,
    val engine: Media3Engine,
    var onSingleTap: (() -> Unit)?,
    var onFeedback: ((GestureFeedback) -> Unit)?,
    var onHoldBoostChanged: ((Float?) -> Unit)?,
    var settings: GestureSettings,
    lockedInitially: Boolean,
    touchInitially: Boolean = true
) : View(context) {
    private val gestures = GestureMachine()
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var x = 0f; private var y = 0f; private var position = 0L
    private var brightness = .5f; private var volume = 0
    private var viewportHeight = 0
    private var dragged = false; private var held = false
    private var lastDoubleTapAtMs = 0L
    private var lastDoubleTapSide = 0
    private var accumulatedSeekSec = 0
    private fun clearHoldBoost() {
        if (held) {
            held = false
            onHoldBoostChanged?.invoke(null)
        }
        engine.restoreBoost()
    }
    private fun cancelActiveGestures() {
        val wasSeeking = gestures.mode == GestureMachine.Mode.SEEK
        handler.removeCallbacksAndMessages(null)
        gestures.release()
        clearHoldBoost()
        if (wasSeeking) engine.cancelPreviewSeek()
        dragged = false
    }
    var locked = lockedInitially
        set(value) {
            if (value && !field) cancelActiveGestures()
            field = value
        }
    var touchEnabled = touchInitially
        set(value) {
            if (!value && field) cancelActiveGestures()
            field = value
            visibility = if (value) View.VISIBLE else View.GONE
            updateExclusionRects()
        }
    private fun updateExclusionRects() {
        if (Build.VERSION.SDK_INT < 29) return
        systemGestureExclusionRects = if (touchEnabled && width > 0 && height > 0) {
            val margin = (96 * resources.displayMetrics.density).toInt()
            listOf(android.graphics.Rect(0, (height - margin).coerceAtLeast(0), width, height))
        } else {
            emptyList()
        }
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) updateExclusionRects()
    }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val hold = Runnable {
        if (gestures.hold()) {
            held = true
            engine.boost(settings.holdSpeed)
            val callback = onHoldBoostChanged
            if (callback != null) {
                callback(settings.holdSpeed)
            } else {
                onFeedback?.invoke(GestureFeedback("${settings.holdSpeed}× 倍速播放"))
            }
        }
    }
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if(!dragged && !held) performClick(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if(locked)return true
            handler.removeCallbacks(hold); clearHoldBoost()
            val side = when {
                e.x < width * .35f -> -1
                e.x > width * .65f -> 1
                else -> 0
            }
            if (side != 0 && !engine.state.value.seekable) {
                onFeedback?.invoke(GestureFeedback("此资源不支持定位", kind = GestureKind.INFO))
                return true
            }
            if (side != 0) {
                val now = SystemClock.uptimeMillis()
                val sameSide = lastDoubleTapSide == side && now - lastDoubleTapAtMs <= 900L
                accumulatedSeekSec = (if (sameSide) accumulatedSeekSec else 0) + settings.seekSeconds
                lastDoubleTapSide = side
                lastDoubleTapAtMs = now
                engine.seekBy(side * settings.seekSeconds * 1000L)
                onFeedback?.invoke(
                    GestureFeedback(
                        text = if (side < 0) "−${accumulatedSeekSec}s" else "+${accumulatedSeekSec}s",
                        side = side,
                        kind = if (side < 0) GestureKind.SEEK_BACK else GestureKind.SEEK_FORWARD
                    )
                )
            } else {
                engine.toggle()
            }
            return true
        }
    }).apply { setIsLongpressEnabled(false) }
    init {
        isClickable = true
        contentDescription = "视频：单击显示控制栏，左右双击快进快退，长按临时加速"
        visibility = if (touchEnabled) View.VISIBLE else View.GONE
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!touchEnabled) return false
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
                                engine.previewSeek(target)
                                val delta=(target-position)/1000
                                onFeedback?.invoke(GestureFeedback(
                                    text = "${if(delta>=0) "+" else ""}${delta}s · ${timeLabel(target)}",
                                    kind = if (delta >= 0) GestureKind.SEEK_FORWARD else GestureKind.SEEK_BACK
                                ))
                            }
                            GestureMachine.Mode.BRIGHTNESS -> {
                                val level=brightnessAfterDrag(brightness,dy,viewportHeight)
                                (context as? Activity)?.window?.let { window ->
                                    window.attributes=window.attributes.apply {screenBrightness=level}
                                }
                                onFeedback?.invoke(GestureFeedback("亮度 ${(level*100).toInt()}%", level, kind = GestureKind.BRIGHTNESS))
                            }
                            GestureMachine.Mode.VOLUME -> {
                                val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                val target=(volume-dy/height.coerceAtLeast(1)*max).toInt().coerceIn(0,max)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC,target,0)
                                onFeedback?.invoke(GestureFeedback("音量 ${target*100/max}%", target.toFloat()/max, kind = GestureKind.VOLUME))
                            }
                            else -> Unit
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasSeeking = gestures.mode == GestureMachine.Mode.SEEK
                handler.removeCallbacks(hold); gestures.release(); clearHoldBoost()
                if(wasSeeking) {
                    if(e.actionMasked == MotionEvent.ACTION_UP) engine.commitPreviewSeek()
                    else engine.cancelPreviewSeek()
                }
            }
        }
        if(!dragged && !held) detector.onTouchEvent(e)
        return true
    }
    override fun performClick(): Boolean { super.performClick(); onSingleTap?.invoke() ?: run { engine.toggle() }; return true }
    override fun onDetachedFromWindow() {
        cancelActiveGestures()
        super.onDetachedFromWindow()
    }
}
