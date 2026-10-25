package app.umuo.tv

import android.os.Bundle
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import app.umuo.android.PlayerModel
import app.umuo.tvui.TvApp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
 private lateinit var model: PlayerModel

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  WindowCompat.setDecorFitsSystemWindows(window, false)
  WindowCompat.getInsetsController(window, window.decorView).apply {
   systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
   hide(WindowInsetsCompat.Type.systemBars())
  }
  model = ViewModelProvider(this)[PlayerModel::class.java]
  val isTelevision = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
   (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
  setContent {
   val focusManager = LocalFocusManager.current
   val inputMode = LocalInputModeManager.current
   Box(Modifier.fillMaxSize()) {
    TvApp(model)
    if (!isTelevision) PadTestRemote(Modifier.align(Alignment.BottomEnd).padding(16.dp).navigationBarsPadding().imePadding(),
     send = { code ->
      val now = SystemClock.uptimeMillis()
      inputMode.requestInputMode(InputMode.Keyboard)
      val handled = window.decorView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0).apply { source = InputDevice.SOURCE_DPAD })
      window.decorView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0).apply { source = InputDevice.SOURCE_DPAD })
      if (!handled) when (code) {
       KeyEvent.KEYCODE_DPAD_UP -> focusManager.moveFocus(FocusDirection.Up)
       KeyEvent.KEYCODE_DPAD_DOWN -> focusManager.moveFocus(FocusDirection.Down)
       KeyEvent.KEYCODE_DPAD_LEFT -> focusManager.moveFocus(FocusDirection.Left)
       KeyEvent.KEYCODE_DPAD_RIGHT -> focusManager.moveFocus(FocusDirection.Right)
      }
     }, back = {
      inputMode.requestInputMode(InputMode.Keyboard)
      onBackPressedDispatcher.onBackPressed()
     })
   }
  }
 }
 override fun onPause() { super.onPause(); model.engine.restoreBoost() }
 override fun onStop() { super.onStop(); model.background(false) }
 }
