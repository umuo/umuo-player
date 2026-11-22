package app.umuo.mobileui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umuo.android.BrowserState
import app.umuo.android.PlayerModel
import app.umuo.core.library.CatalogFilter
import app.umuo.core.library.CatalogType
import app.umuo.core.model.SourceKind
import app.umuo.core.source.mediaserver.ServerView

@Composable internal fun MobileLibraryNavigation(
    model: PlayerModel, state: BrowserState, horizontalPadding: Dp,
    filters: Boolean, onFilterExpandedChange: (Boolean) -> Unit, onNavigate: () -> Unit
) {
    val dav = state.connection?.kind == SourceKind.WEBDAV
    var menu by remember { mutableStateOf(false) }
    var searching by rememberSaveable(state.connection?.id) { mutableStateOf(false) }
    var localQuery by rememberSaveable(state.connection?.id) { mutableStateOf(state.localSearch) }
    var serverQuery by rememberSaveable(state.connection?.id, state.serverSearch) { mutableStateOf(state.serverSearch) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.localSearch) {
        if (state.localSearch != localQuery) localQuery = state.localSearch
    }
    LaunchedEffect(searching, dav) {
        if (searching && dav) runCatching { searchFocus.requestFocus() }
    }
    fun navigate(action: () -> Unit) { menu = false; onNavigate(); action() }
    val tabs = listOf("精选", "电影", "剧集")
    val selectedTab = when {
        dav -> when (state.catalogFilter.type) { CatalogType.MOVIES -> 1; CatalogType.SERIES -> 2; else -> 0 }
        else -> when (state.serverView) { ServerView.MOVIES -> 1; ServerView.SERIES -> 2; ServerView.LIBRARIES -> 0; else -> -1 }
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(tabs.indices.toList()) { index ->
                    val active = selectedTab == index
                    Column(
                        Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics { selected = active }
                            .clickable(role = Role.Tab) {
                                navigate {
                                    if (dav) {
                                        model.filterCatalog(when (index) {
                                            1 -> state.catalogFilter.copy(type = CatalogType.MOVIES)
                                            2 -> state.catalogFilter.copy(type = CatalogType.SERIES)
                                            else -> CatalogFilter()
                                        })
                                    } else model.showServerView(when (index) {
                                        1 -> ServerView.MOVIES; 2 -> ServerView.SERIES; else -> ServerView.LIBRARIES
                                    })
                                }
                            },
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(tabs[index], fontSize = 16.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (active) MobileStyle.Accent else MobileStyle.Muted)
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.width(26.dp).height(2.dp).background(if (active) MobileStyle.Accent else androidx.compose.ui.graphics.Color.Transparent))
                    }
                }
            }
            if (dav && state.wall && state.series == null) IconButton(onClick = { onFilterExpandedChange(!filters) }) {
                Icon(Icons.Rounded.Tune, if (filters) "收起筛选" else "展开筛选",
                    tint = if (state.catalogFilter.active || filters) MobileStyle.Accent else MobileStyle.Muted)
            }
            if (dav) IconButton(onClick = { searching = !searching }) {
                Icon(Icons.Rounded.Search, "搜索本地影视库",
                    tint = if (searching || state.localSearch.isNotBlank()) MobileStyle.Accent else MobileStyle.Muted)
            } else IconButton(onClick = { searching = !searching }) {
                Icon(Icons.Rounded.Search, "搜索当前服务器",
                    tint = if (searching || state.serverSearch.isNotBlank()) MobileStyle.Accent else MobileStyle.Muted)
            }
            if (!dav) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, "媒体库选项", tint = MobileStyle.Muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    ServerView.entries.forEach { view ->
                        DropdownMenuItem(text = { Text(view.label) }, onClick = { navigate { model.showServerView(view) } })
                    }
                }
            }
        }
        if (dav && state.wall && state.series == null && filters) Box(Modifier.padding(horizontal = horizontalPadding)) {
            CatalogControls(state, model::filterCatalog, model::setCatalogSort, embedded = true)
        }
        if (dav && (searching || state.localSearch.isNotBlank())) {
            OutlinedTextField(
                value = localQuery,
                onValueChange = {
                    localQuery = it
                    model.setLocalSearch(it)
                },
                label = { Text("搜索本地影视库") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .padding(bottom = 10.dp)
                    .focusRequester(searchFocus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = {
                    if (localQuery.isNotBlank() || state.localSearch.isNotBlank() || searching) {
                        IconButton(onClick = {
                            keyboard?.hide()
                            localQuery = ""
                            searching = false
                            model.clearLocalSearch()
                        }) { Icon(Icons.Rounded.Close, "清除搜索") }
                    }
                }
            )
        }
        if (!dav && (searching || state.serverSearch.isNotBlank())) {
            OutlinedTextField(
                serverQuery, { serverQuery = it }, label = { Text("搜索当前服务器") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding).padding(bottom = 10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); navigate { model.searchServer(serverQuery) } }),
                trailingIcon = {
                    Row {
                        IconButton(onClick = { keyboard?.hide(); navigate { model.searchServer(serverQuery) } }, enabled = !state.busy) {
                            Icon(Icons.Rounded.Search, "搜索影视")
                        }
                        IconButton(onClick = {
                            keyboard?.hide(); serverQuery = ""; searching = false
                            if (state.serverSearch.isNotBlank()) navigate { model.clearServerSearch() }
                        }) { Icon(Icons.Rounded.Close, "清除搜索") }
                    }
                }
            )
        }
    }
}
