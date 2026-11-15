package app.umuo.tvui

import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import app.umuo.android.*

@Composable
internal fun TvServerSearch(model: PlayerModel, state: BrowserState) {
    var query by rememberSaveable(state.connection?.id,state.serverSearch) {mutableStateOf(state.serverSearch)}
    val keyboard=LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var fieldFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = fieldFocused || query.isNotBlank()) {
        keyboard?.hide()
        query = ""
        if (state.serverSearch.isNotBlank()) model.clearServerSearch()
        focus.moveFocus(FocusDirection.Up)
    }
    Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        TvTextField(query,{query=it},"搜索当前服务器",Modifier.width(400.dp).onFocusChanged {fieldFocused=it.hasFocus})
        Button(enabled=!state.busy,onClick={keyboard?.hide();model.searchServer(query)}) {Text("搜索影视")}
        if(state.serverSearch.isNotBlank())Button(onClick={query="";model.clearServerSearch()}) {Text("清除搜索")}
    }
}
