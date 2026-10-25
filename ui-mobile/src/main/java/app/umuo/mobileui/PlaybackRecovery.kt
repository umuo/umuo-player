package app.umuo.mobileui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umuo.android.Cinema
import app.umuo.core.playback.PlaybackFailureKind

@Composable internal fun PlaybackRecovery(message: String, kind: PlaybackFailureKind?, serverTranscoding: Boolean,
    busy: Boolean, retry: ()->Unit, compatible: ()->Unit, edit: ()->Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(message,color=MaterialTheme.colorScheme.error,fontSize=13.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick=retry,enabled=!busy){Text(if(busy)"重试中…" else "重试播放",fontSize=12.sp)}
            if(serverTranscoding && kind in listOf(PlaybackFailureKind.UNSUPPORTED,PlaybackFailureKind.OTHER))
                OutlinedButton(onClick=compatible,enabled=!busy){Text("兼容播放",fontSize=12.sp)}
        }
        TextButton(onClick=edit,enabled=!busy,contentPadding=PaddingValues(0.dp)){Text("编辑连接",color=MobileStyle.Accent,fontSize=12.sp)}
        if(kind==PlaybackFailureKind.UNSUPPORTED && !serverTranscoding)Text("WebDAV 不提供服务端转码，请选择设备支持的视频或切换媒体服务器。",color=MobileStyle.Muted,fontSize=11.sp)
    }
}
