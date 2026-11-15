@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.umuo.android

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import app.umuo.core.model.*
import app.umuo.core.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.net.ssl.SSLException
import java.security.cert.CertificateException

private const val MAX_RECOVERY_ATTEMPTS = 2

data class PlaybackMetrics(val firstFrameMs: Long? = null, val seekReadyMs: Long? = null)

class Media3Engine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val connection: (String) -> SourceConnection?,
    private val secrets: CredentialStore,
    private val refreshPlan: (suspend (Long) -> PlaybackPlan?)? = null
) : PlaybackEngine {
    private data class AudioPreference(val language: String?, val label: String?, val id: String?)
    private sealed interface SubtitlePreference {
        data object Default : SubtitlePreference
        data object Disabled : SubtitlePreference
        data class Selected(val language: String?, val label: String?, val id: String?, val external: Boolean) : SubtitlePreference
    }

    private val metricsMutable = MutableStateFlow(PlaybackMetrics())
    val metrics = metricsMutable.asStateFlow()
    private val videoSizeMutable = MutableStateFlow(0 to 0)
    val videoSize = videoSizeMutable.asStateFlow()

    /** 返回当前视频尺寸对应的画中画宽高比。 */
    fun pipAspectRatio(): Pair<Int, Int> {
        val (w, h) = videoSizeMutable.value
        return app.umuo.android.pipAspectRatio(w, h)
    }
    private var prepareAt = 0L
    private var seekAt = 0L
    private val mutable = MutableStateFlow(PlaybackState())
    override val state = mutable.asStateFlow()
    private var sourceSeekable = true
    private var externalSubtitleIds: Set<String> = emptySet()
    private var session: MediaSession? = null
    private var ticker: Job? = null
    private var seekJob: Job? = null
    private var recoveryJob: Job? = null
    private var http: okhttp3.OkHttpClient? = null
    private val currentPlayer = MutableStateFlow<ExoPlayer?>(null)
    val players = currentPlayer.asStateFlow()
    val player: ExoPlayer? get() = currentPlayer.value

    private var activePlan: PlaybackPlan? = null
    private var activeItemId: MediaId? = null
    private var preservedItemId: MediaId? = null
    private var desiredSpeed = 1f
    private var audioPref: AudioPreference? = null
    private var subtitlePref: SubtitlePreference = SubtitlePreference.Default
    private var appliedAudioPref = false
    private var appliedSubtitlePref = false
    private var hadFirstFrame = false
    private var recoveryAttempts = 0
    private var lastRecoveryAt = 0L
    private var pendingSeekPosition: Long? = null
    private var lastSeekSentAt = 0L
    private var lastPreviewSeekSentAt = 0L
    private var seekModeExact = true

    fun preserveStateFor(itemId: MediaId) {
        preservedItemId = itemId
    }

    override fun prepare(plan: PlaybackPlan) {
        val preserve = preservedItemId == plan.item.id
        preservedItemId = null
        prepare(plan, preserveState = preserve)
    }

    fun prepare(plan: PlaybackPlan, preserveState: Boolean) {
        prepareInternal(plan, preserveState = preserveState, fromRecovery = false, autoPlay = true)
    }

    private fun prepareInternal(
        plan: PlaybackPlan,
        preserveState: Boolean,
        fromRecovery: Boolean,
        autoPlay: Boolean
    ) {
        val keepPrefs = (preserveState || fromRecovery) && activeItemId == plan.item.id
        teardown(resetPreferences = !keepPrefs, resetRecovery = !fromRecovery)
        activePlan = plan
        activeItemId = plan.item.id
        prepareAt = SystemClock.elapsedRealtime()
        seekAt = 0
        pendingSeekPosition = null
        lastSeekSentAt = 0
        lastPreviewSeekSentAt = 0
        seekModeExact = true
        appliedAudioPref = false
        appliedSubtitlePref = false
        if (!fromRecovery) {
            metricsMutable.value = PlaybackMetrics()
            videoSizeMutable.value = 0 to 0
        }
        sourceSeekable = plan.seekable
        externalSubtitleIds = plan.subtitles.map { it.id }.toSet()
        mutable.value = PlaybackState(
            buffering = true,
            seekable = plan.seekable,
            speed = desiredSpeed,
            positionMs = plan.startPositionMs.coerceAtLeast(0)
        )
        val c = plan.credentialKey?.let(connection) ?: error("媒体来源不存在")
        val client = sourceHttp(c, secrets, forPlayback = true).also { http = it }
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(client)))
            .setLoadErrorHandlingPolicy(createLoadErrorPolicy())
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 1_000, 1_500)
            .setBackBuffer(10_000, true).build()
        val exo = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setLoadControl(loadControl).setMediaSourceFactory(factory).build()
        currentPlayer.value = exo
        exo.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        exo.setHandleAudioBecomingNoisy(true)
        // 保持双击和进度条的目标时间准确，避免退回较远的关键帧。
        exo.setSeekParameters(SeekParameters.EXACT)
        if (desiredSpeed != 1f) exo.setPlaybackSpeed(desiredSpeed)
        if (subtitlePref is SubtitlePreference.Disabled) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            appliedSubtitlePref = true
        }
        session = MediaSession.Builder(context, exo).build()
        exo.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                hadFirstFrame = true
                val elapsed = SystemClock.elapsedRealtime() - prepareAt
                if (metrics.value.firstFrameMs == null) {
                    metricsMutable.update { it.copy(firstFrameMs = elapsed) }
                    android.util.Log.i("UmuoPlayback", "first_frame_ms=$elapsed")
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val width = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
                    videoSizeMutable.value = width to videoSize.height
                }
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if (seekAt > 0 && exo.playbackState == Player.STATE_READY) {
                    val elapsed = SystemClock.elapsedRealtime() - seekAt
                    metricsMutable.update { it.copy(seekReadyMs = elapsed) }; seekAt = 0
                    android.util.Log.i("UmuoPlayback", "seek_ready_ms=$elapsed")
                }
                if (exo.playbackState == Player.STATE_READY && exo.isPlaying &&
                    SystemClock.elapsedRealtime() - lastRecoveryAt > 2_000L
                ) {
                    recoveryAttempts = 0
                }
                update(exo)
            }
            override fun onTracksChanged(tracks: Tracks) {
                applySavedTrackPreferences(exo)
                update(exo)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (shouldAutoRecoverPlayback(hadFirstFrame, recoveryAttempts, MAX_RECOVERY_ATTEMPTS, error)) {
                    scheduleRecovery(exo, c, plan, error)
                    return
                }
                recoveryJob?.cancel()
                val kind = playbackFailureKind(error)
                mutable.update {
                    it.copy(
                        buffering = false,
                        playing = false,
                        paused = false,
                        failureKind = kind,
                        error = playbackFailureMessage(error, c.baseUrl)
                    )
                }
            }
        })
        val subtitles = plan.subtitles.map { s -> ExoMediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(s.uri))
            .setId(s.id).setMimeType(s.mimeType).setLabel(s.label).setLanguage(s.language).build() }
        exo.setMediaItem(ExoMediaItem.Builder().setUri(plan.uri).setMediaId(plan.item.id.value).setMimeType(plan.mimeType)
            .setSubtitleConfigurations(subtitles).setMediaMetadata(MediaMetadata.Builder().setTitle(plan.item.title).build()).build(), plan.startPositionMs)
        exo.prepare(); exo.playWhenReady = autoPlay
        ticker = scope.launch { while (isActive) { delay(500); if (player === exo) update(exo) } }
    }

    private fun createLoadErrorPolicy(): LoadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy(6) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val causes = generateSequence<Throwable>(loadErrorInfo.exception) { it.cause }.take(6).toList()
            if (causes.any { it is SSLException || it is CertificateException || it is UnsafeMediaRedirect }) {
                return C.TIME_UNSET
            }
            val code = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()?.responseCode
            if (code != null) {
                return when (code) {
                    429 -> minOf(1_200L * loadErrorInfo.errorCount, 4_800L)
                    408, 500, 502, 503, 504 -> minOf(600L * loadErrorInfo.errorCount, 3_000L)
                    401, 403, 404, 410, 416 -> C.TIME_UNSET
                    else -> super.getRetryDelayMsFor(loadErrorInfo)
                }
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    private fun scheduleRecovery(
        exo: ExoPlayer,
        c: SourceConnection,
        plan: PlaybackPlan,
        error: PlaybackException
    ) {
        val attempt = ++recoveryAttempts
        lastRecoveryAt = SystemClock.elapsedRealtime()
        val duration = exo.duration.takeIf { it > 0 } ?: state.value.durationMs
        val currentPos = (pendingSeekPosition ?: exo.currentPosition).coerceAtLeast(0L)
        val resumePos = if (isOutOfRangeError(error) && duration > 0L) {
            (duration - 2_000L).coerceAtLeast(0L)
        } else {
            clampSeekPosition(currentPos, duration)
        }
        val shouldPlay = exo.playWhenReady
        val needsRefresh = attempt >= 2 || needsPlanRefreshError(error)
        pendingSeekPosition = resumePos
        mutable.update {
            it.copy(
                buffering = true,
                playing = false,
                positionMs = resumePos,
                error = null,
                failureKind = null
            )
        }
        android.util.Log.i("UmuoPlayback", "auto_recover_attempt=$attempt code=${error.errorCode} refresh=$needsRefresh")
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            delay(if (attempt == 1) 300L else 800L)
            if (player !== exo) return@launch
            if (!needsRefresh) {
                exo.seekTo(resumePos)
                exo.prepare()
                exo.playWhenReady = shouldPlay
            } else {
                val nextPlan = try {
                    refreshPlan?.invoke(resumePos)?.copy(startPositionMs = resumePos)
                        ?: plan.copy(startPositionMs = resumePos)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update {
                        it.copy(
                            buffering = false,
                            playing = false,
                            paused = false,
                            failureKind = playbackFailureKind(e),
                            error = playbackFailureMessage(e, c.baseUrl)
                        )
                    }
                    return@launch
                }
                prepareInternal(nextPlan, preserveState = true, fromRecovery = true, autoPlay = shouldPlay)
            }
        }
    }

    private fun applySavedTrackPreferences(exo: ExoPlayer) {
        if (!appliedAudioPref) {
            val pref = audioPref
            if (pref == null) {
                appliedAudioPref = true
            } else {
                val match = findMatchingTrack(exo, C.TRACK_TYPE_AUDIO) { f ->
                    (pref.id != null && f.id == pref.id) ||
                        (pref.language != null && f.language == pref.language && f.label == pref.label) ||
                        (pref.language != null && f.language == pref.language) ||
                        (pref.label != null && f.label == pref.label)
                }
                if (match != null) {
                    appliedAudioPref = true
                    if (!match.first.isTrackSelected(match.second)) {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .setOverrideForType(TrackSelectionOverride(match.first.mediaTrackGroup, match.second))
                            .build()
                    }
                }
            }
        }
        if (!appliedSubtitlePref) {
            when (val pref = subtitlePref) {
                SubtitlePreference.Default -> appliedSubtitlePref = true
                SubtitlePreference.Disabled -> {
                    appliedSubtitlePref = true
                    if (!exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    }
                }
                is SubtitlePreference.Selected -> {
                    val match = findMatchingTrack(exo, C.TRACK_TYPE_TEXT) { f ->
                        (pref.id != null && f.id == pref.id) ||
                            ((f.id in externalSubtitleIds) == pref.external &&
                                pref.language != null && f.language == pref.language && f.label == pref.label) ||
                            (pref.language != null && f.language == pref.language) ||
                            (pref.label != null && f.label == pref.label)
                    }
                    if (match != null) {
                        appliedSubtitlePref = true
                        if (!match.first.isTrackSelected(match.second) ||
                            exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                        ) {
                            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(match.first.mediaTrackGroup, match.second))
                                .build()
                        }
                    }
                }
            }
        }
    }

    private fun findMatchingTrack(
        exo: ExoPlayer,
        type: Int,
        predicate: (Format) -> Boolean
    ): Pair<Tracks.Group, Int>? {
        for (group in exo.currentTracks.groups) {
            if (group.type != type) continue
            for (t in 0 until group.length) {
                if (group.isTrackSupported(t) && predicate(group.getTrackFormat(t))) {
                    return group to t
                }
            }
        }
        return null
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
        val pending = pendingSeekPosition
        if (pending != null && seekJob?.isActive != true &&
            (exo.playbackState == Player.STATE_READY || exo.playbackState == Player.STATE_ENDED) &&
            (kotlin.math.abs(exo.currentPosition - pending) <= 1_500L ||
                SystemClock.elapsedRealtime() - lastSeekSentAt >= 1_000L)
        ) {
            pendingSeekPosition = null
        }
        val reportedPosition = pendingSeekPosition ?: exo.currentPosition.coerceAtLeast(0)
        val paused = !exo.playWhenReady && exo.playbackState != Player.STATE_ENDED && exo.playbackState != Player.STATE_IDLE
        mutable.update {
            it.copy(
                ready = exo.playbackState == Player.STATE_READY || exo.playbackState == Player.STATE_ENDED,
                playing = exo.isPlaying,
                paused = paused,
                buffering = exo.playbackState == Player.STATE_BUFFERING,
                positionMs = reportedPosition,
                durationMs = exo.duration.coerceAtLeast(0),
                speed = exo.playbackParameters.speed,
                audioTracks = tracks(C.TRACK_TYPE_AUDIO),
                subtitles = tracks(C.TRACK_TYPE_TEXT),
                ended = exo.playbackState == Player.STATE_ENDED,
                seekable = sourceSeekable && exo.isCurrentMediaItemSeekable
            )
        }
    }

    fun toggle() {
        val exo = player ?: return
        if (exo.playbackState == Player.STATE_ENDED) play()
        else if (exo.playWhenReady) pause() else play()
    }

    fun seekBy(offsetMs: Long) {
        val base = pendingSeekPosition ?: player?.currentPosition ?: state.value.positionMs
        seekTo(base + offsetMs)
    }

    private var boostRestore: Float? = null
    fun boost(speed: Float = 2f) {
        if (boostRestore == null) {
            boostRestore = desiredSpeed
            player?.setPlaybackSpeed(speed.coerceIn(1f, 4f))
        }
    }

    fun restoreBoost() {
        boostRestore?.let { restored ->
            desiredSpeed = restored
            player?.setPlaybackSpeed(restored)
        }
        boostRestore = null
    }

    override fun play() {
        player?.let { exo ->
            if (exo.playbackState == Player.STATE_ENDED) {
                pendingSeekPosition = null
                seekJob?.cancel()
                exo.seekToDefaultPosition()
            }
            exo.play()
            update(exo)
        }
    }

    override fun pause() {
        restoreBoost()
        player?.let { exo ->
            exo.pause()
            update(exo)
        }
    }

    private fun dispatchSeek(exo: ExoPlayer, target: Long, exact: Boolean) {
        if (seekModeExact != exact) {
            exo.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
            seekModeExact = exact
        }
        val now = SystemClock.elapsedRealtime()
        lastSeekSentAt = now
        seekAt = now
        exo.seekTo(target)
    }

    /** 触屏横滑时更新目标时间并用关键帧轻量预览，松手后再执行精确跳转。 */
    fun previewSeek(positionMs: Long) {
        if (!state.value.seekable) return
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: state.value.durationMs
        val target = clampSeekPosition(positionMs, duration)
        pendingSeekPosition = target
        seekJob?.cancel()
        mutable.update { it.copy(positionMs = target, ended = false) }
        val now = SystemClock.elapsedRealtime()
        if (now - lastPreviewSeekSentAt >= 220L) {
            lastPreviewSeekSentAt = now
            dispatchSeek(exo, target, exact = false)
        }
    }

    fun commitPreviewSeek() {
        val exo = player ?: return
        val target = pendingSeekPosition ?: return
        seekJob?.cancel()
        dispatchSeek(exo, target, exact = true)
        update(exo)
    }

    fun cancelPreviewSeek() {
        val exo = player ?: return
        if (!seekModeExact) {
            exo.setSeekParameters(SeekParameters.EXACT)
            seekModeExact = true
        }
        val target = pendingSeekPosition ?: return
        seekJob?.cancel()
        dispatchSeek(exo, target, exact = true)
        update(exo)
    }

    override fun seekTo(positionMs: Long) {
        if (!state.value.seekable) return
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: state.value.durationMs
        val target = clampSeekPosition(positionMs, duration)
        pendingSeekPosition = target
        mutable.update { it.copy(positionMs = target, ended = false) }
        val now = SystemClock.elapsedRealtime()
        val minInterval = if (exo.playbackState == Player.STATE_BUFFERING) 220L else 180L
        if (seekJob?.isActive != true && now - lastSeekSentAt >= minInterval) {
            dispatchSeek(exo, target, exact = true)
            update(exo)
        } else {
            seekJob?.cancel()
            seekJob = scope.launch {
                delay(if (exo.playbackState == Player.STATE_BUFFERING) 220L else 160L)
                val current = player ?: return@launch
                if (current === exo) {
                    val finalTarget = pendingSeekPosition ?: target
                    dispatchSeek(current, finalTarget, exact = true)
                    update(current)
                }
            }
        }
    }

    override fun setSpeed(speed: Float) {
        boostRestore = null
        desiredSpeed = speed.coerceIn(.25f, 4f)
        player?.setPlaybackSpeed(desiredSpeed)
    }

    private fun select(id: String, type: Int): Format? {
        val exo = player ?: return null
        val indices = id.split(':').mapNotNull { it.toIntOrNull() }
        if (indices.size != 2) return null
        val group = exo.currentTracks.groups.getOrNull(indices[0]) ?: return null
        if (group.type != type || indices[1] !in 0 until group.length) return null
        val format = group.getTrackFormat(indices[1])
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, indices[1])).build()
        return format
    }

    override fun selectAudio(id: String) {
        val format = select(id, C.TRACK_TYPE_AUDIO) ?: return
        audioPref = AudioPreference(format.language, format.label, format.id)
        appliedAudioPref = true
    }

    override fun selectSubtitle(id: String?) {
        if (id != null) {
            val format = select(id, C.TRACK_TYPE_TEXT) ?: return
            subtitlePref = SubtitlePreference.Selected(format.language, format.label, format.id, format.id in externalSubtitleIds)
            appliedSubtitlePref = true
        } else {
            subtitlePref = SubtitlePreference.Disabled
            appliedSubtitlePref = true
            player?.let {
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }
        }
    }

    private fun teardown(resetPreferences: Boolean, resetRecovery: Boolean) {
        restoreBoost()
        seekJob?.cancel(); seekJob = null
        recoveryJob?.cancel(); recoveryJob = null
        ticker?.cancel(); ticker = null
        session?.release(); session = null
        player?.release(); currentPlayer.value = null
        http?.shutdownCleanly(); http = null
        pendingSeekPosition = null
        if (resetRecovery) {
            hadFirstFrame = false
            recoveryAttempts = 0
            lastRecoveryAt = 0L
        }
        if (resetPreferences) {
            activeItemId = null
            desiredSpeed = 1f
            audioPref = null
            subtitlePref = SubtitlePreference.Default
        }
    }

    override fun release() {
        preservedItemId = null
        activePlan = null
        videoSizeMutable.value = 0 to 0
        teardown(resetPreferences = true, resetRecovery = true)
        mutable.value = PlaybackState()
    }
}
