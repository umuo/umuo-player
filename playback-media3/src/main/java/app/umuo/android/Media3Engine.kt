@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.umuo.android

import android.content.Context
import androidx.media3.common.*
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import app.umuo.core.model.*
import app.umuo.core.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PlaybackMetrics(val firstFrameMs: Long? = null, val seekReadyMs: Long? = null)

class Media3Engine(
    private val context: Context, private val scope: CoroutineScope,
    private val connection: (String) -> SourceConnection?, private val secrets: CredentialStore
) : PlaybackEngine {
    private val metricsMutable = MutableStateFlow(PlaybackMetrics())
    val metrics = metricsMutable.asStateFlow()
    private var prepareAt = 0L
    private var seekAt = 0L
    private val mutable = MutableStateFlow(PlaybackState())
    override val state = mutable.asStateFlow()
    private var sourceSeekable = true
    private var externalSubtitleIds: Set<String> = emptySet()
    private var session: MediaSession? = null
    private var ticker: Job? = null
    private var http: okhttp3.OkHttpClient? = null
    private val currentPlayer = MutableStateFlow<ExoPlayer?>(null)
    val players = currentPlayer.asStateFlow()
    val player: ExoPlayer? get() = currentPlayer.value
    override fun prepare(plan: PlaybackPlan) {
        release()
        prepareAt = android.os.SystemClock.elapsedRealtime()
        seekAt = 0
        metricsMutable.value = PlaybackMetrics()
        sourceSeekable = plan.seekable
        externalSubtitleIds = plan.subtitles.map { it.id }.toSet()
        mutable.value = PlaybackState(buffering = true, seekable = plan.seekable)
        val c = plan.credentialKey?.let(connection) ?: error("媒体来源不存在")
        val client = sourceHttp(c, secrets).also { http = it }
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(client)))
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 1_000, 1_500)
            .setBackBuffer(10_000, true).build()
        val exo = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setLoadControl(loadControl).setMediaSourceFactory(factory).build()
        currentPlayer.value = exo
        exo.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        exo.setHandleAudioBecomingNoisy(true)
        // 保持双击和进度条的目标时间准确，避免退回较远的关键帧。
        exo.setSeekParameters(SeekParameters.EXACT)
        session = MediaSession.Builder(context, exo).build()
        exo.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                val elapsed = android.os.SystemClock.elapsedRealtime() - prepareAt
                if(metrics.value.firstFrameMs == null) {
                    metricsMutable.update { it.copy(firstFrameMs = elapsed) }
                    android.util.Log.i("UmuoPlayback", "first_frame_ms=$elapsed")
                }
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if(seekAt > 0 && exo.playbackState == Player.STATE_READY) {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - seekAt
                    metricsMutable.update { it.copy(seekReadyMs = elapsed) }; seekAt = 0
                    android.util.Log.i("UmuoPlayback", "seek_ready_ms=$elapsed")
                }
                update(exo)
            }
            override fun onTracksChanged(tracks: Tracks) { update(exo) }
            override fun onPlayerError(error: PlaybackException) {
                val kind=playbackFailureKind(error)
                mutable.update { it.copy(buffering=false,playing=false,failureKind=kind,error=playbackFailureMessage(error, c.baseUrl)) }

            }
        })
        val subtitles = plan.subtitles.map { s -> ExoMediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(s.uri))
            .setId(s.id).setMimeType(s.mimeType).setLabel(s.label).setLanguage(s.language).build() }
        exo.setMediaItem(ExoMediaItem.Builder().setUri(plan.uri).setMediaId(plan.item.id.value).setMimeType(plan.mimeType)
            .setSubtitleConfigurations(subtitles).setMediaMetadata(MediaMetadata.Builder().setTitle(plan.item.title).build()).build(), plan.startPositionMs)
        exo.prepare(); exo.playWhenReady = true
        ticker = scope.launch { while(isActive) { delay(500); if(player === exo) update(exo) } }
    }
    private fun update(exo: ExoPlayer) {
        fun tracks(type: Int): List<TrackChoice> {
            var ordinal = 0
            return exo.currentTracks.groups.flatMapIndexed { g, group ->
            if(group.type != type) emptyList() else (0 until group.length).filter { group.isTrackSupported(it) }.map { t ->
                val f = group.getTrackFormat(t)
                if (type == C.TRACK_TYPE_TEXT) {
                    val name = subtitleName(f.language, f.label, f.sampleMimeType, ordinal++,
                        f.id in externalSubtitleIds, f.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                        f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                        f.roleFlags and C.ROLE_FLAG_CAPTION != 0 || f.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0)
                    TrackChoice("$g:$t", name.label, group.isTrackSelected(t), name.detail)
                } else TrackChoice("$g:$t", f.label ?: listOfNotNull(f.language, f.sampleMimeType).joinToString(" · ").ifBlank { "轨道 ${t+1}" }, group.isTrackSelected(t))
            }
        }
        }
        mutable.update { it.copy(ready = exo.playbackState == Player.STATE_READY || exo.playbackState == Player.STATE_ENDED,
            playing = exo.isPlaying, buffering = exo.playbackState == Player.STATE_BUFFERING,
            positionMs = exo.currentPosition.coerceAtLeast(0), durationMs = exo.duration.coerceAtLeast(0),
            speed = exo.playbackParameters.speed, audioTracks = tracks(C.TRACK_TYPE_AUDIO), subtitles = tracks(C.TRACK_TYPE_TEXT),
            ended = exo.playbackState == Player.STATE_ENDED, seekable = sourceSeekable && exo.isCurrentMediaItemSeekable) }
    }
    fun toggle() {
        val exo = player ?: return
        if(exo.playbackState == Player.STATE_ENDED) play()
        else if(exo.playWhenReady) pause() else play()
    }
    fun seekBy(offsetMs: Long) = seekTo((player?.currentPosition ?: state.value.positionMs)+offsetMs)
    private var boostRestore: Float? = null
    fun boost(speed: Float = 2f) { if(boostRestore == null) { boostRestore=state.value.speed; player?.setPlaybackSpeed(speed.coerceIn(1f,4f)) } }
    fun restoreBoost() { boostRestore?.let { player?.setPlaybackSpeed(it) }; boostRestore=null }
    override fun play() {
        player?.let { exo ->
            if(exo.playbackState == Player.STATE_ENDED) exo.seekToDefaultPosition()
            exo.play()
            update(exo)
        }
    }
    override fun pause() { restoreBoost(); player?.pause() }
    override fun seekTo(positionMs: Long) { if(state.value.seekable) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: state.value.durationMs
        val target = if(duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        seekAt = android.os.SystemClock.elapsedRealtime()
        exo.seekTo(target)
        update(exo)
    } }
    override fun setSpeed(speed: Float) { restoreBoost(); player?.setPlaybackSpeed(speed.coerceIn(.25f, 4f)) }
    private fun select(id: String, type: Int) {
        val exo = player ?: return
        val indices = id.split(':').mapNotNull { it.toIntOrNull() }
        if(indices.size != 2) return
        val group = exo.currentTracks.groups.getOrNull(indices[0]) ?: return
        if(group.type != type || indices[1] !in 0 until group.length) return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, indices[1])).build()
    }
    override fun selectAudio(id: String) = select(id, C.TRACK_TYPE_AUDIO)
    override fun selectSubtitle(id: String?) {
        if(id != null) select(id, C.TRACK_TYPE_TEXT) else player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
    }
    override fun release() {
        restoreBoost()
        ticker?.cancel(); ticker = null; session?.release(); session = null
        player?.release(); currentPlayer.value = null
        http?.connectionPool?.evictAll(); http?.dispatcher?.executorService?.shutdown(); http = null
        mutable.value = PlaybackState()
    }
}
