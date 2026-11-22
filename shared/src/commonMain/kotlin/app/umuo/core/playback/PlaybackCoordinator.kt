package app.umuo.core.playback

import app.umuo.core.library.LibraryRepository
import app.umuo.core.source.MediaSourceProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal fun isUserPaused(state: PlaybackState): Boolean =
    state.paused || (!state.playing && !state.buffering && !state.ended)

internal fun isNearCompletion(positionMs: Long, durationMs: Long, ended: Boolean): Boolean =
    ended || (durationMs >= 180_000L && positionMs >= (durationMs * 95L) / 100L)

/** 单个播放会话；START/PROGRESS/STOP 串行上报，停止失败不触发重复计数重试。 */
class PlaybackCoordinator(
    private val engine: PlaybackEngine,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
    private val ioContext: CoroutineContext = EmptyCoroutineContext,
    private val warning: (String) -> Unit
) {
    @Volatile private var plan: PlaybackPlan? = null
    private var provider: MediaSourceProvider? = null
    private var lastRequest: PlaybackRequest? = null
    private var monitor: Job? = null
    private var started = false
    private var everReady = false
    private val lock = Mutex()
    suspend fun open(source: MediaSourceProvider, request: PlaybackRequest): PlaybackPlan = lock.withLock {
        stopCurrent()
        val resolved = source.resolve(request)
        provider = source; plan = resolved; lastRequest = request; started = false; everReady = false
        engine.prepare(resolved)
        monitor = scope.launch {
            var ticks = 0
            var lastPaused: Boolean? = null
            var lastSavedPosition = -1L
            var savedDuration = 0L
            while (isActive) {
                delay(500)
                val state = engine.state.value
                val currentPlan = plan ?: resolved
                if (state.ready) everReady = true
                val activeSession = (state.ready || everReady) && state.error == null
                val paused = isUserPaused(state)
                if (activeSession) {
                    if (state.durationMs > 0 && state.durationMs != savedDuration) {
                        withContext(ioContext) { library.saveDuration(currentPlan.item.id, state.durationMs) }
                        savedDuration = state.durationMs
                    }
                    if (lastSavedPosition < 0 || kotlin.math.abs(state.positionMs - lastSavedPosition) >= 1_000 || lastPaused != paused || state.ended) {
                        val savedPos = if (state.ended) 0L else state.positionMs
                        withContext(ioContext) { library.savePosition(currentPlan.item.id, savedPos) }
                        lastSavedPosition = state.positionMs
                    }
                    if (!started && state.playing) {
                        started = true
                        report(source, currentPlan, PlaybackEventKind.START, state, paused)
                    } else if (started && (++ticks >= 20 || lastPaused != paused)) {
                        ticks = 0
                        report(source, currentPlan, PlaybackEventKind.PROGRESS, state, paused)
                    }
                    lastPaused = paused
                }
                if (state.ended || state.error != null) break
            }
        }
        resolved
    }
    /** 仅在会话内原位刷新临时播放地址或转码会话，不重复触发 START/STOP 计数。 */
    suspend fun refreshPlan(positionMs: Long): PlaybackPlan? = lock.withLock {
        val current = plan ?: return@withLock null
        val source = provider ?: return@withLock null
        val req = lastRequest ?: return@withLock null
        val refreshed = source.resolve(req.copy(item = current.item.copy(resumeMs = positionMs.coerceAtLeast(0))))
        if (current.sessionId != null && current.sessionId != refreshed.sessionId) {
            try { withTimeout(5_000) { source.closeSession(current) } }
            catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e }
            catch (_: Exception) { }
        }
        plan = refreshed
        refreshed
    }
    private suspend fun report(
        source: MediaSourceProvider, current: PlaybackPlan, kind: PlaybackEventKind,
        state: PlaybackState, paused: Boolean = isUserPaused(state), completed: Boolean = state.ended
    ) {
        try { withTimeout(8_000) { source.report(current, PlaybackEvent(kind, state.positionMs, paused, completed)) } }
        catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e; warning("服务器进度上报超时，本地记录已保留") }
        catch (_: Exception) { warning("服务器进度上报失败，本地记录已保留") }
    }
    private suspend fun stopCurrent() {
        monitor?.cancelAndJoin(); monitor = null
        val current = plan ?: return
        val source = provider ?: return
        val state = engine.state.value
        val paused = isUserPaused(state)
        val effectiveDuration = state.durationMs.takeIf { it > 0 } ?: current.item.durationMs
        val completed = isNearCompletion(state.positionMs, effectiveDuration, state.ended)
        engine.pause()
        withContext(NonCancellable) {
            if ((state.ready || everReady || started) && state.error == null) {
                withContext(ioContext) {
                    library.savePosition(current.item.id, if (completed) 0L else state.positionMs)
                }
            }
            if (started) report(source, current, PlaybackEventKind.STOP, state, paused, completed)
            try { withTimeout(8_000) { source.closeSession(current) } }
            catch (_: Exception) { warning("服务器播放会话释放失败，请检查连接") }
        }
        plan = null; provider = null; lastRequest = null; started = false; everReady = false
    }
    suspend fun stop() = lock.withLock { stopCurrent() }
}
