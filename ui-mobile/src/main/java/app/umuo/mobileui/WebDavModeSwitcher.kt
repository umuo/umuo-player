package app.umuo.mobileui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LocalMovies
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umuo.android.PlayerModel

/** 模式切换固定在媒体页面顶部；分类、滚动或进入详情都不能藏起另一个模式。 */
@Composable internal fun WebDavModeSwitcher(
    model: PlayerModel, wall: Boolean, busy: Boolean, wide: Boolean, onSelect: (Boolean) -> Unit
) {
    val scan by model.scanner.state.collectAsState()
    var tools by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (wide) 32.dp else 20.dp).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(false to "文件浏览", true to "影视库").forEach { (library, label) ->
            val selected = wall == library
            val tint = if (selected) MobileStyle.Accent else MobileStyle.Muted
            Column(
                Modifier.weight(1f).heightIn(min = 48.dp)
                    .selectable(selected = selected, enabled = !busy, role = Role.Tab) { onSelect(library) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (library) Icons.Rounded.LocalMovies else Icons.Rounded.Folder, null, Modifier.size(18.dp), tint)
                    Text(label, color = tint, fontSize = 14.sp, lineHeight = 20.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(5.dp))
                Box(Modifier.width(26.dp).height(2.dp).background(if (selected) MobileStyle.Accent else Color.Transparent))
            }
        }
        Box {
            IconButton(onClick = { tools = true }) {
                Icon(Icons.Rounded.MoreHoriz, "媒体库选项", tint = MobileStyle.Muted)
            }
            DropdownMenu(expanded = tools, onDismissRequest = { tools = false }) {
                DropdownMenuItem(
                    text = { Text(if (scan.running) "停止扫描" else "扫描 / 恢复") },
                    onClick = { tools = false; model.scan() }
                )
            }
        }
    }
}
