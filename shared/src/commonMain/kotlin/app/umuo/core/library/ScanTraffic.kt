package app.umuo.core.library

import io.ktor.client.plugins.api.*
import io.ktor.http.*
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ScanSpeed(val intervalMs: Long, val label: String) {
    CONSERVATIVE(1_000,"保守 · 1 秒/请求"), SLOW(3_000,"更慢 · 3 秒/请求"), VERY_SLOW(5_000,"极慢 · 5 秒/请求")
}
class ScanPaused(val untilMs: Long = 0, message: String) : Exception(message)

/** 同一轮扫描统一排队，包含认证握手；收到限流后不再发出后续请求。 */
class ScanTrafficGuard(
    val intervalMs: Long = ScanSpeed.CONSERVATIVE.intervalMs,
    private val nowMs: () -> Long = {GMTDate().timestamp},
    lastRequestMs: Long? = null,
    private var blockedUntilMs: Long = 0,
    private val onActivity: (Long) -> Unit = {},
    private val onBlocked: (Long) -> Unit = {}
) {
    private val lock=Mutex()
    private var lastSent=lastRequestMs
    private var halted=false
    var requests: Int = 0
        private set
    init {require(intervalMs>=0)}
    suspend fun beforeRequest() = lock.withLock {
        if(halted || nowMs()<blockedUntilMs)throw ScanPaused(blockedUntilMs,"服务器要求等待，扫描已暂停")
        val wait=lastSent?.let {(intervalMs-(nowMs()-it).coerceAtLeast(0)).coerceAtLeast(0)} ?: 0
        if(wait>0)delay(wait)
        currentCoroutineContext().ensureActive()
        if(halted)throw ScanPaused(blockedUntilMs,"服务器要求等待，扫描已暂停")
        val started=nowMs();lastSent=started;requests++;onActivity(started)
    }
    suspend fun responseReceived() = lock.withLock {
        // 从响应完成后等待，避免首包网络延迟压缩服务端实际收到请求的间隔。
        val completed=nowMs();lastSent=completed;onActivity(completed)
    }
    fun pauseForResponse(status: Int, retryAfter: String?): Nothing {
        val now=nowMs()
        val seconds=retryAfter?.trim()?.toLongOrNull()?.takeIf {it>=0}
        val requested=if(seconds!=null) seconds.coerceAtMost(Long.MAX_VALUE/1000)*1000
            else runCatching {retryAfter?.fromHttpToGmtDate()?.timestamp?.let {(it-now).coerceAtLeast(0)}}.getOrNull() ?: 0
        val wait=requested.coerceAtLeast(60_000)
        blockedUntilMs=if(wait>Long.MAX_VALUE-now)Long.MAX_VALUE else now+wait
        halted=true;onBlocked(blockedUntilMs)
        throw ScanPaused(blockedUntilMs,"服务器返回 $status，已暂停扫描并保留进度")
    }
}
class ScanTrafficConfig {lateinit var guard: ScanTrafficGuard}
val ScanTrafficPlugin=createClientPlugin("ScanTraffic",::ScanTrafficConfig) {
    val guard=pluginConfig.guard
    on(SendingRequest) {_,_->guard.beforeRequest()}
    onResponse {response->
        guard.responseReceived()
        if(response.status.value==429 || response.status.value==503)
            guard.pauseForResponse(response.status.value,response.headers[HttpHeaders.RetryAfter])
    }
}
