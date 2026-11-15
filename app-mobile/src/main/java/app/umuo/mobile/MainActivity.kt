package app.umuo.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import app.umuo.android.PlayerModel
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.util.Rational
import androidx.compose.runtime.*
import app.umuo.mobileui.MobileApp

class MainActivity : ComponentActivity() {
 private lateinit var model: PlayerModel
 private var inPip by mutableStateOf(false)
  override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  model = ViewModelProvider(this)[PlayerModel::class.java]
  setContent {
   val browser by model.state.collectAsState()
   val videoSize by model.engine.videoSize.collectAsState()
   LaunchedEffect(browser.playing?.id, videoSize) {
    if (packageManager.hasSystemFeature("android.software.picture_in_picture") && browser.playing != null) {
     runCatching { setPictureInPictureParams(buildPipParams()) }
    }
   }
   MobileApp(model, inPip) { enterPip() }
  }
 }
 override fun onPause() { super.onPause(); model.engine.restoreBoost() }
 override fun onStop() { super.onStop(); model.background(isInPictureInPictureMode) }
 private fun buildPipParams(): PictureInPictureParams {
  val (width, height) = model.engine.pipAspectRatio()
  val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(width, height))
  model.videoBounds?.let { builder.setSourceRectHint(it) }
  if (android.os.Build.VERSION.SDK_INT >= 31) {
   builder.setAutoEnterEnabled(false)
   builder.setSeamlessResizeEnabled(true)
  }
  return builder.build()
 }
 private fun enterPip() {
  if (packageManager.hasSystemFeature("android.software.picture_in_picture") && model.state.value.playing != null) {
   // 当前仅响应用户点击画中画；不在离开应用时隐式进入。
   enterPictureInPictureMode(buildPipParams())
  }
 }
 override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
  super.onPictureInPictureModeChanged(isInPictureInPictureMode,newConfig)
  inPip=isInPictureInPictureMode
 }
 }
