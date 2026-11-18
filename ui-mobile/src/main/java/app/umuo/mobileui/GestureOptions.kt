package app.umuo.mobileui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umuo.android.*

@Composable internal fun GestureOptions(model: PlayerModel) {
    val settings by model.playbackPreferences.state.collectAsState()
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("双击快进 / 快退",fontSize=14.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { listOf(5,10,30).forEach { seconds ->
            FilterChip(selected=settings.seekSeconds==seconds,onClick={model.playbackPreferences.update(settings.copy(seekSeconds=seconds))},label={Text("${seconds} 秒")})
        }}
        Text("长按临时倍速",fontSize=14.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { listOf(1.5f,2f,3f).forEach { speed ->
            FilterChip(selected=settings.holdSpeed==speed,onClick={model.playbackPreferences.update(settings.copy(holdSpeed=speed))},label={Text("${speed}×")})
        }}
        Text("滑动灵敏度 · ${"%.1f".format(settings.sensitivity)}×",fontSize=14.sp)
        Slider(value=settings.sensitivity,onValueChange={model.playbackPreferences.update(settings.copy(sensitivity=it))},valueRange=.5f..2f,steps=5)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("剧集播完自动连播", fontSize = 14.sp)
                Text("存在下一集时自动切换并延续倍速、音轨与字幕偏好", color = MobileStyle.Muted, fontSize = 11.sp)
            }
            Switch(
                checked = settings.autoPlayNext,
                onCheckedChange = { model.playbackPreferences.update(settings.copy(autoPlayNext = it)) }
            )
        }
        Text("设置自动保存在当前设备；锁定播放器后只保留解锁入口。",color=MobileStyle.Muted,fontSize=12.sp)
    }
}
