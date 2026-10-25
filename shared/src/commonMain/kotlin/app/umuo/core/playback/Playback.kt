package app.umuo.core.playback

import app.umuo.core.model.*
import kotlinx.coroutines.flow.StateFlow

/** 只声明实际验证过的解码组合；未知编码交给服务器协商，不能宣称全格式支持。 */
data class DeviceProfile(val maxBitrate: Int = 20_000_000, val directPlayProfiles: List<DirectPlayProfile> = emptyList())
data class DirectPlayProfile(val containers: String, val videoCodecs: String, val audioCodecs: String)
enum class PlaybackMode { DIRECT, REMUX, TRANSCODE }
data class Subtitle(val id: String, val label: String, val uri: String, val mimeType: String, val language: String? = null)
data class PlaybackRequest(val item: MediaItem, val profile: DeviceProfile, val forceTranscode: Boolean = false)
data class PlaybackPlan(
    val item: MediaItem, val uri: String, val mode: PlaybackMode = PlaybackMode.DIRECT,
    val mimeType: String? = null, val credentialKey: String? = null,
    val subtitles: List<Subtitle> = emptyList(), val sessionId: String? = null,
    val mediaSourceId: String? = null, val startPositionMs: Long = 0,
    val seekable: Boolean = true
)
enum class PlaybackEventKind { START, PROGRESS, STOP }
data class PlaybackEvent(
    val kind: PlaybackEventKind, val positionMs: Long, val paused: Boolean = false,
    val completed: Boolean = false
)
data class TrackChoice(val id: String, val label: String, val selected: Boolean, val detail: String? = null)
data class PlaybackState(
    val ready: Boolean = false, val playing: Boolean = false, val buffering: Boolean = false,
    val positionMs: Long = 0, val durationMs: Long = 0, val speed: Float = 1f,
    val audioTracks: List<TrackChoice> = emptyList(), val subtitles: List<TrackChoice> = emptyList(),
    val error: String? = null, val failureKind: PlaybackFailureKind? = null, val ended: Boolean = false, val seekable: Boolean = true
)
interface PlaybackEngine {
    val state: StateFlow<PlaybackState>
    fun prepare(plan: PlaybackPlan)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun selectAudio(id: String)
    fun selectSubtitle(id: String?)
    fun release()
}
