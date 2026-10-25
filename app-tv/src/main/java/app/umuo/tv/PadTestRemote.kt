package app.umuo.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.umuo.android.Cinema
import android.view.KeyEvent

/** 只在非电视设备出现；触控按钮不取得焦点，按键仍交给原 TV 界面。 */
@Composable internal fun PadTestRemote(modifier: Modifier = Modifier, send: (Int) -> Unit, back: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    MaterialTheme(colorScheme = darkColorScheme(primary = Cinema.Accent, surface = Cinema.Surface, onSurface = Cinema.Text)) {
        if (!expanded) {
            FilledTonalButton(onClick = { expanded = true }, modifier = modifier.focusProperties { canFocus = false }) {
                Icon(Icons.Rounded.Gamepad, null)
                Spacer(Modifier.width(8.dp))
                Text("测试遥控器")
            }
        } else Surface(modifier = modifier.width(224.dp), shape = RoundedCornerShape(20.dp),
            color = Cinema.Surface, border = BorderStroke(1.dp, Cinema.Muted.copy(alpha = .35f)), shadowElevation = 8.dp) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Pad 测试遥控器", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { expanded = false }, modifier = Modifier.focusProperties { canFocus = false }
                        .semantics { contentDescription = "收起测试遥控器" }) { Icon(Icons.Rounded.Close, null) }
                }
                RemoteArrow("遥控上", KeyEvent.KEYCODE_DPAD_UP, send) { Icon(Icons.Rounded.KeyboardArrowUp, null) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RemoteArrow("遥控左", KeyEvent.KEYCODE_DPAD_LEFT, send) { Icon(Icons.Rounded.KeyboardArrowLeft, null) }
                    FilledTonalButton(onClick = { send(KeyEvent.KEYCODE_DPAD_CENTER) }, contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(64.dp, 48.dp).focusProperties { canFocus = false }
                            .semantics { contentDescription = "遥控确定" }) { Text("确定") }
                    RemoteArrow("遥控右", KeyEvent.KEYCODE_DPAD_RIGHT, send) { Icon(Icons.Rounded.KeyboardArrowRight, null) }
                }
                RemoteArrow("遥控下", KeyEvent.KEYCODE_DPAD_DOWN, send) { Icon(Icons.Rounded.KeyboardArrowDown, null) }
                TextButton(onClick = back, modifier = Modifier.fillMaxWidth().focusProperties { canFocus = false }
                    .semantics { contentDescription = "遥控返回" }) { Icon(Icons.Rounded.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text("返回") }
            }
        }
    }
}

@Composable private fun RemoteArrow(label: String, code: Int, send: (Int) -> Unit, icon: @Composable () -> Unit) {
    IconButton(onClick = { send(code) }, modifier = Modifier.size(48.dp).focusProperties { canFocus = false }
        .semantics { contentDescription = label }, content = icon)
}
