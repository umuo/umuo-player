package app.umuo.mobileui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.umuo.android.BrowserState
import app.umuo.android.PlayerModel
import app.umuo.core.model.MediaItem
import app.umuo.core.source.mediaserver.ServerView

@Composable
internal fun ServerUserActions(model: PlayerModel, state: BrowserState, item: MediaItem) {
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        FilterChip(selected=item.favorite==true,enabled=!state.userActionBusy,onClick={model.setFavorite(item)},
            label={Text(if(item.favorite==true)"已收藏" else "收藏")})
        FilterChip(selected=item.watched==true,enabled=!state.userActionBusy,onClick={model.setWatched(item)},
            label={Text(if(item.watched==true)"已看" else "标记已看")})
        if(state.userActionBusy)CircularProgressIndicator(Modifier.size(24.dp).padding(4.dp),strokeWidth=2.dp,color=MobileStyle.Accent)
    }
}

internal fun serverEmptyText(state: BrowserState): String = when {
    state.serverSearch.isNotBlank()->"没有找到匹配的影视"
    state.serverView==ServerView.FAVORITES->"还没有收藏"
    state.serverView==ServerView.RESUME->"暂无可继续观看的内容"
    state.serverView==ServerView.NEXT_UP->"暂无待看的下一集"
    else->"这个影视库暂时没有内容"
}
