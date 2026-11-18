package app.umuo.mobile

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import app.umuo.android.PlayerModel
import app.umuo.mobileui.MobileApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val ACTION_PIP_CONTROL = "app.umuo.player.PIP_CONTROL"
private const val EXTRA_PIP_OP = "op"

class MainActivity : ComponentActivity() {
    private lateinit var model: PlayerModel
    private var inPip by mutableStateOf(false)
    private var pipReceiverRegistered = false
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PIP_CONTROL || intent.`package` != packageName) return
            when (intent.getStringExtra(EXTRA_PIP_OP)) {
                "rew" -> {
                    val seconds = model.playbackPreferences.state.value.seekSeconds
                    model.engine.seekBy(-seconds * 1_000L)
                }
                "toggle" -> model.engine.toggle()
                "fwd" -> {
                    val seconds = model.playbackPreferences.state.value.seekSeconds
                    model.engine.seekBy(seconds * 1_000L)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[PlayerModel::class.java]
        ContextCompat.registerReceiver(
            this,
            pipReceiver,
            IntentFilter(ACTION_PIP_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        pipReceiverRegistered = true
        setContent {
            val browser by model.state.collectAsState()
            val videoSize by model.engine.videoSize.collectAsState()
            val pipActionState by remember(model.engine) {
                model.engine.state.map { it.playing to it.seekable }.distinctUntilChanged()
            }.collectAsState(initial = false to true)
            LaunchedEffect(browser.playing?.id, videoSize, inPip, pipActionState) {
                if (packageManager.hasSystemFeature("android.software.picture_in_picture") && browser.playing != null) {
                    runCatching { setPictureInPictureParams(buildPipParams(pipActionState.first, pipActionState.second)) }
                }
            }
            MobileApp(model, inPip) { enterPip() }
        }
    }

    override fun onDestroy() {
        if (pipReceiverRegistered) {
            runCatching { unregisterReceiver(pipReceiver) }
            pipReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onPause() { super.onPause(); model.engine.restoreBoost() }
    override fun onStop() { super.onStop(); model.background(isInPictureInPictureMode) }

    private fun pipAction(requestCode: Int, iconRes: Int, title: String, op: String): RemoteAction {
        val intent = Intent(ACTION_PIP_CONTROL).setPackage(packageName).putExtra(EXTRA_PIP_OP, op)
        val pending = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(Icon.createWithResource(this, iconRes), title, title, pending)
    }

    private fun buildPipParams(
        isPlaying: Boolean = model.engine.state.value.playing,
        seekable: Boolean = model.engine.state.value.seekable
    ): PictureInPictureParams {
        val (width, height) = model.engine.pipAspectRatio()
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(width, height))
        model.videoBounds?.let { builder.setSourceRectHint(it) }
        val maxActions = maxNumPictureInPictureActions.coerceAtLeast(0)
        if (maxActions > 0) {
            val toggleAction = pipAction(
                2,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "暂停" else "播放",
                "toggle"
            )
            val actions = if (seekable && maxActions >= 3) {
                listOf(
                    pipAction(1, android.R.drawable.ic_media_rew, "快退", "rew"),
                    toggleAction,
                    pipAction(3, android.R.drawable.ic_media_ff, "快进", "fwd")
                )
            } else {
                listOf(toggleAction)
            }
            builder.setActions(actions.take(maxActions))
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(false)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun enterPip() {
        if (packageManager.hasSystemFeature("android.software.picture_in_picture") && model.state.value.playing != null) {
            // 当前仅响应用户点击画中画；不在离开应用时隐式进入。
            val s = model.engine.state.value
            enterPictureInPictureMode(buildPipParams(s.playing, s.seekable))
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }
}
