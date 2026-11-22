package app.umuo.mobileui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umuo.android.*
import app.umuo.core.model.*
import app.umuo.core.network.ServerAddress
import java.util.UUID

@Composable internal fun ConnectionEditor(model: PlayerModel, existing: SourceConnection?, done: () -> Unit) {
    val state by model.state.collectAsState()
    val initial = remember(existing?.id) { existing?.let { runCatching { ServerAddress.parse(it.baseUrl) }.getOrNull() } }
    val id = rememberSaveable(existing?.id) { existing?.id ?: UUID.randomUUID().toString() }
    var kind by rememberSaveable(existing?.id) { mutableStateOf(existing?.kind ?: SourceKind.WEBDAV) }
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var host by rememberSaveable(existing?.id) { mutableStateOf(initial?.host.orEmpty()) }
    var port by rememberSaveable(existing?.id) { mutableStateOf(initial?.port ?: ServerAddress.defaultPort(kind)) }
    var path by rememberSaveable(existing?.id) { mutableStateOf(initial?.path ?: ServerAddress.defaultPath(kind)) }
    var https by rememberSaveable(existing?.id) { mutableStateOf(initial?.https ?: false) }
    var username by rememberSaveable(existing?.id) { mutableStateOf(existing?.username.orEmpty()) }
    // 密码不写入 SavedState；保存时只进入 Keystore，编辑留空沿用安全存储的值。
    var password by remember(existing?.id) { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val active = model.hasActiveConnection(id)
    val pending = state.drafts.any {it.id==id}
    val address = runCatching { ServerAddress(host,port,path,https).baseUrl() }
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("影片留在你的服务器，连接信息保存在当前设备。",color=MobileStyle.Muted,fontSize=13.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            SourceKind.entries.forEach { k -> FilterChip(selected=kind==k,enabled=existing==null && !state.busy,onClick={
                if(port==ServerAddress.defaultPort(kind,https))port=ServerAddress.defaultPort(k,https)
                if(path.trimEnd('/')==ServerAddress.defaultPath(kind).trimEnd('/'))path=ServerAddress.defaultPath(k)
                kind=k
            },label={Text(k.name)}) }
        }
        OutlinedTextField(name,{name=it},label={Text("名称（选填）")},singleLine=true,enabled=!state.busy,modifier=Modifier.fillMaxWidth())
        run {
            fun splitAddress() {
                if(host.trim().startsWith("http://") || host.trim().startsWith("https://")) {
                    runCatching {ServerAddress.parse(host.trim())}.onSuccess { parsed -> host=parsed.host;port=parsed.port;path=parsed.path;https=parsed.https }
                }
            }
            OutlinedTextField(host,{host=it;localError=null},label={Text("服务器地址")},placeholder={Text("192.168.1.10 或 nas.example.com")},supportingText={Text("完整地址离开输入框后自动拆分")},singleLine=true,enabled=!state.busy,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth().onFocusChanged { if(!it.isFocused)splitAddress() })
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(port,{port=it.filter(Char::isDigit).take(5);localError=null},label={Text("端口")},singleLine=true,enabled=!state.busy,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.width(100.dp))
                OutlinedTextField(path,{path=it;localError=null},label={Text("路径")},singleLine=true,enabled=!state.busy,modifier=Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)){Text("HTTPS 安全连接");Text("${if(https)"HTTPS" else "HTTP"} · 默认端口 ${ServerAddress.defaultPort(kind,https)}",color=MobileStyle.Muted,fontSize=11.sp)}
                Switch(checked=https,enabled=!state.busy,onCheckedChange={ secure -> if(port==ServerAddress.defaultPort(kind,https))port=ServerAddress.defaultPort(kind,secure);https=secure })
            }
            address.getOrNull()?.let { resolved ->
                Surface(
                    color = MobileStyle.Surface,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.5.dp, MobileStyle.Border)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (https) Icons.Rounded.Lock else Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = if (https) MobileStyle.Accent else androidx.compose.ui.graphics.Color(0xFFFFB74D),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(resolved, color = MobileStyle.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        OutlinedTextField(username,{username=it},label={Text("用户名")},singleLine=true,enabled=!state.busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(password,{password=it},label={Text(if(existing!=null || state.connections.any{it.id==id})"密码（留空保留原值）" else "密码")},singleLine=true,enabled=!state.busy,
            visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
        (localError ?: state.error)?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp) }
        Button(onClick={
            val result=runCatching { if(host.trim().startsWith("http://") || host.trim().startsWith("https://")) ServerAddress.parse(host).baseUrl() else ServerAddress(host,port,path,https).baseUrl() }
            result.onSuccess { model.connect(name,kind,it,username,password,id) {password="";done()} }.onFailure {localError=it.message}
        },enabled=!state.busy && host.isNotBlank(),modifier=Modifier.fillMaxWidth().height(50.dp)) {
            Text(if(state.busy)"正在验证连接…" else "保存并连接")
        }
        Text("验证失败保留草稿，原连接与观看记录保持不变。",color=MobileStyle.Muted,fontSize=12.sp)
        if(pending)TextButton(onClick={model.discardDraft(id);done()},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text(if(active)"放弃草稿，保留原连接" else "删除未验证草稿")}
        if(existing != null) {
            var confirmDelete by remember(existing.id) { mutableStateOf(false) }
            OutlinedButton(
                onClick = { confirmDelete = true },
                enabled = !state.busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("删除此媒体来源")
            }
            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("删除媒体来源") },
                    text = { Text("将删除「${existing.name}」的连接配置、本机索引、观看进度与安全存储凭据，不会修改远程服务器文件。") },
                    confirmButton = {
                        TextButton(onClick = { model.removeConnection(existing.id) { confirmDelete = false; done() } }) {
                            Text("确认删除", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDelete = false }) { Text("取消") }
                    }
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    }
}
