package app.umuo.core.playback

import app.umuo.core.library.LibraryRepository
import app.umuo.core.source.MediaSourceProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 单个播放会话；START/PROGRESS/STOP 串行上报，停止失败不触发重复计数重试。 */
class PlaybackCoordinator(
    private val engine: PlaybackEngine, private val library: LibraryRepository,
    private val scope: CoroutineScope, private val warning: (String) -> Unit
) {
    private var plan: PlaybackPlan? = null
    private var provider: MediaSourceProvider? = null
    private var monitor: Job? = null
    private var started = false
    private val lock = Mutex()
    suspend fun open(source: MediaSourceProvider, request: PlaybackRequest): PlaybackPlan = lock.withLock {
        stopCurrent()
        val resolved = source.resolve(request)
        provider = source; plan = resolved; started = false
        engine.prepare(resolved)
        monitor = scope.launch {
            var ticks = 0
            var lastPaused: Boolean? = null
            var lastSavedPosition = -1L
            var savedDuration = 0L
            while (isActive) {
                delay(500)
                val state = engine.state.value
                if (state.ready) {
                    if(state.durationMs > 0 && state.durationMs != savedDuration) {
                        library.saveDuration(resolved.item.id, state.durationMs); savedDuration = state.durationMs
                    }
                    if(lastSavedPosition < 0 || kotlin.math.abs(state.positionMs - lastSavedPosition) >= 1_000 || lastPaused != !state.playing || state.ended) {
                        library.savePosition(resolved.item.id, if (state.ended) 0 else state.positionMs)
                        lastSavedPosition = state.positionMs
                    }
                    if (!started && state.playing) {
                        started = true
                        report(source, resolved, PlaybackEventKind.START, state)
                    } else if (started && (++ticks >= 20 || lastPaused != !state.playing)) {
                        ticks = 0
                        report(source, resolved, PlaybackEventKind.PROGRESS, state)
                    }
                    lastPaused = !state.playing
                }
                if (state.ended || state.error != null) break
            }
        }
        resolved
    }
    private suspend fun report(source: MediaSourceProvider, current: PlaybackPlan, kind: PlaybackEventKind, state: PlaybackState) {
        try { withTimeout(8_000) { source.report(current, PlaybackEvent(kind, state.positionMs, !state.playing, state.ended)) } }
        catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e; warning("服务器进度上报超时，本地记录已保留") }
        catch (_: Exception) { warning("服务器进度上报失败，本地记录已保留") }
    }
    private suspend fun stopCurrent() {
        monitor?.cancelAndJoin(); monitor = null
        val current = plan ?: return
        val source = provider ?: return
        val state = engine.state.value
        engine.pause()
        if (state.ready) library.savePosition(current.item.id, if(state.ended) 0 else state.positionMs)
        withContext(NonCancellable) {
            if (started) report(source, current, PlaybackEventKind.STOP, state)
            try { withTimeout(8_000) { source.closeSession(current) } }
            catch (_: Exception) { warning("服务器播放会话释放失败，请检查连接") }
        }
        plan = null; provider = null; started = false
    }
    suspend fun stop() = lock.withLock { stopCurrent() }
}
