package app.umuo.mobileui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umuo.android.*
import app.umuo.core.library.ScanSpeed

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScanOptions(model: PlayerModel) {
    val speed by model.scanPreferences.speed.collectAsState()
    val scan by model.scanner.state.collectAsState()
    val autoPreviews by model.scanPreferences.autoPreviews.collectAsState()
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("WebDAV 扫描速度",fontSize=20.sp)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ScanSpeed.entries.forEach {value->FilterChip(selected=speed==value,enabled=!scan.running,onClick={model.scanPreferences.setSpeed(value)},label={Text(value.label)})}
        }
        Text("单并发；档位控制扫描请求间隔，下一轮扫描生效。限流或服务繁忙时暂停并保存进度，不自动连续重试。",color=MobileStyle.Muted,fontSize=12.sp)
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("自动提取视频预览",modifier=Modifier.weight(1f))
            Switch(autoPreviews,{model.scanPreferences.setAutoPreviews(it)})
        }
        Text("提帧会额外读取视频，担心站点限流时可关闭；已有预览缓存不受影响。扫描时自动暂停提帧。",color=MobileStyle.Muted,fontSize=12.sp)
        Text("播放与海报加载不计入扫描限速，不能保证站点不会触发风控。",color=MobileStyle.Muted,fontSize=12.sp)
    }
}
