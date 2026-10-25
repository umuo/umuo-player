package app.umuo.android

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.umuo.core.model.MediaItem
import coil.ImageLoader

/** 海报优先，裸视频按需提帧；继续观看优先使用最近保存的实际视频画面。 */
@Composable fun VideoArtwork(model: PlayerModel, item: MediaItem, loader: ImageLoader, modifier: Modifier = Modifier, resume: Boolean = false, hero: Boolean = false) {
    val revision by model.thumbnails.revision.collectAsState()
    val scan by model.scanner.state.collectAsState()
    val autoPreviews by model.scanPreferences.autoPreviews.collectAsState()
    var preview by remember(item.id,item.fingerprint) { mutableStateOf(model.thumbnails.cached(item,resume)) }
    val artwork = if(resume || hero) item.backdrop ?: item.artwork else item.artwork
    LaunchedEffect(item.id,item.fingerprint,artwork,scan.running,autoPreviews) {
        if(autoPreviews && !scan.running && artwork==null && !item.isFolder) preview=model.thumbnails.preview(item)
    }
    val uri = if(resume) model.thumbnails.cached(item,true) ?: artwork ?: preview else artwork ?: preview
    CinemaArtwork(uri,item.title,loader,modifier,hero,if(uri?.startsWith("file:")==true)revision else 0)
}
