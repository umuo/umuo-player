package app.umuo.tvui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text

/** 遥控导航时是普通焦点项，确认后才进入文字编辑；返回只结束本次编辑。 */
@Composable internal fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = if (password) KeyboardType.Password else KeyboardType.Text
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var editorHadFocus by remember(editing) { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    var pendingDirection by remember { mutableStateOf<FocusDirection?>(null) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val navigationFocus = remember { FocusRequester() }
    val editorFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val inputMode = LocalInputModeManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun finish(direction: FocusDirection? = null, restore: Boolean = true) {
        if (!editing) return
        keyboard?.hide()
        pendingDirection = direction
        restoreFocus = restore
        editing = false
    }
    BackHandler(enabled = editing) { finish() }
    LaunchedEffect(editing) {
        if (editing) {
            editorFocus.requestFocus()
            withFrameNanos { }
            keyboard?.show()
        } else if (restoreFocus) {
            inputMode.requestInputMode(InputMode.Keyboard)
            navigationFocus.requestFocus()
            pendingDirection?.let { focus.moveFocus(it) }
            pendingDirection = null
            restoreFocus = false
        }
    }
    // 输入法改变可用高度后，整个编辑区（包括完成按钮）都应保持可见。
    LaunchedEffect(editing, imeBottom) {
        if (editing) {
            withFrameNanos { }
            bringIntoView.bringIntoView()
        }
    }
    Column(modifier.bringIntoViewRequester(bringIntoView)) {
        if (editing) {
            OutlinedTextField(
                value, onValueChange, label = { androidx.compose.material3.Text(label) }, singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { finish() }),
                modifier = Modifier.fillMaxWidth().focusRequester(editorFocus).onFocusChanged {
                    if (it.isFocused) editorHadFocus = true
                    else if (editorHadFocus && editing) finish(restore = false)
                }.onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionDown, Key.Tab -> {
                            if (event.type == KeyEventType.KeyDown) finish(when (event.key) {
                                Key.DirectionUp -> FocusDirection.Up
                                Key.DirectionDown -> FocusDirection.Down
                                else -> if (event.nativeKeyEvent.isShiftPressed) FocusDirection.Previous else FocusDirection.Next
                            })
                            true
                        }
                        Key.Back, Key.Escape, Key.Enter, Key.DirectionCenter -> {
                            if (event.type == KeyEventType.KeyUp) finish()
                            true
                        }
                        else -> false // 编辑时左右键保留给光标，不能误触表单按钮。
                    }
                }
            )
            androidx.compose.material3.TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.Text("完成输入")
            }
        } else Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth().focusRequester(navigationFocus)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, fontSize = 11.sp)
                Text(if (value.isEmpty()) "未填写 · 按确定输入" else if (password) "•".repeat(value.length.coerceAtMost(20)) else value,
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
