package app.umuo.android

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import app.umuo.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 只在本机缓存图片；按来源、媒体和指纹隔离，最多 64 MiB。凭据只经现有同源客户端发送。 */
class VideoThumbnails(context: Context, private val connection: (String) -> SourceConnection?, private val secrets: CredentialStore) {
    private val directory = File(context.cacheDir, "video-previews").apply { mkdirs() }
    private val extraction = Mutex()
    private val attempted = mutableSetOf<String>()
    private var cachedBytes = -1L
    private val clients = mutableMapOf<String, OkHttpClient>()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    @Synchronized private fun clientFor(c: SourceConnection): OkHttpClient {
        val identity = "${c.id}\u0000${c.kind}\u0000${c.baseUrl}\u0000${c.username}"
        return clients.getOrPut(identity) {
            sourceHttp(c, secrets).newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
        }
    }
    @Synchronized private fun markAttempted(key: String): Boolean {
        if (attempted.size >= 512) attempted.clear()
        return attempted.add(key)
    }
    @Synchronized private fun unmarkAttempted(key: String) {
        attempted.remove(key)
    }
    @Synchronized fun close() {
        clients.values.forEach { it.shutdownCleanly() }
        clients.clear()
        attempted.clear()
        cachedBytes = -1L
    }
    private fun key(item: MediaItem): String = MessageDigest.getInstance("SHA-256")
        .digest("${item.id.sourceId}\u0000${connection(item.id.sourceId)?.let { "${it.kind}:${it.baseUrl}:${it.username}" }}\u0000${item.id.value}\u0000${item.fingerprint}".toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun file(item: MediaItem, resume: Boolean) = File(directory, key(item) + if(resume) "-resume.jpg" else "-preview.jpg")
    fun cached(item: MediaItem, resume: Boolean = false): String? =
        (if(resume) file(item, true).takeIf { it.exists() } else null)?.toURI()?.toString()
            ?: file(item, false).takeIf { it.exists() }?.toURI()?.toString()

    suspend fun saveFrame(item: MediaItem, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        try { write(file(item, true), bitmap); mutableRevision.value++ }
        catch(_: IOException) { /* 缓存失败不能中断停止播放。 */ }
        finally { bitmap.recycle() }
    }
    suspend fun preview(item: MediaItem): String? = withContext(Dispatchers.IO) {
        cached(item)?.let { return@withContext it }
        extraction.withLock {
            cached(item)?.let { return@withLock it }
            val c = connection(item.id.sourceId) ?: return@withLock null
            val target = file(item, false)
            val previewKey = key(item)
            // 服务端影片已经提供图片；WebDAV 文件才做按需、本地提帧。
            if(c.kind != SourceKind.WEBDAV || item.isFolder || !markAttempted(previewKey)) return@withLock null
            val http = clientFor(c)
            val job=currentCoroutineContext()[Job]
            try {
                RangeMediaDataSource(http, item.path) {job?.isActive == false}.use { source ->
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(source)
                        val frame = if(android.os.Build.VERSION.SDK_INT >= 27) {
                            retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 360)
                        } else retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { original ->
                            val scale=minOf(640f/original.width,360f/original.height,1f)
                            val scaled=Bitmap.createScaledBitmap(original,(original.width*scale).toInt().coerceAtLeast(1),(original.height*scale).toInt().coerceAtLeast(1),true)
                            if(scaled !== original)original.recycle()
                            scaled
                        }
                        if(frame != null) try { write(target, frame) } finally { frame.recycle() }
                    } finally { retriever.release() }
                }
            } catch(e: CancellationException) { unmarkAttempted(previewKey); throw e }
            catch(_: Exception) { if(job?.isActive==false) unmarkAttempted(previewKey); /* Range、容器或解码不支持时保留占位图，不影响浏览与播放。 */ }
            cached(item)
        }
    }
    @Synchronized private fun write(target: File, bitmap: Bitmap) {
        val temporary = File(directory, target.name + ".tmp")
        try {
            temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            val previousLength = if (target.exists()) target.length() else 0L
            if (!temporary.renameTo(target)) throw IOException("Preview cache unavailable")
            val newLength = target.length()
            if (cachedBytes < 0L) {
                cachedBytes = directory.listFiles().orEmpty().sumOf { it.length() }
            } else {
                cachedBytes = (cachedBytes - previousLength + newLength).coerceAtLeast(newLength)
            }
            if (cachedBytes > 64L * 1024 * 1024) {
                val files = directory.listFiles().orEmpty()
                cachedBytes = files.sumOf { it.length() }
                files.sortedBy { it.lastModified() }.forEach {
                    if (cachedBytes > 64L * 1024 * 1024 && it != target) {
                        val size = it.length()
                        if (it.delete()) cachedBytes = (cachedBytes - size).coerceAtLeast(0L)
                    }
                }
            }
        } finally { temporary.delete() }
    }
}

/** 原生提帧器通过有限 Range 随机读取；拒绝 200 全量下载，单片最多读取 16 MiB / 15 秒。 */
private class RangeMediaDataSource(private val http: OkHttpClient, private val url: String, private val cancelled: () -> Boolean): MediaDataSource() {
    private var size = -1L
    private var blockStart = -1L
    private var block = byteArrayOf()
    private var consumed = 0L
    private val deadline = SystemClock.elapsedRealtime() + 15_000
    @Volatile private var closed = false
    override fun getSize(): Long {
        if(size < 0) readAt(0, ByteArray(1), 0, 1)
        return size
    }
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, requested: Int): Int {
        if(closed || cancelled() || SystemClock.elapsedRealtime() > deadline || consumed >= 16L*1024*1024) throw IOException("Preview read limit")
        if(requested == 0) return 0
        if(size >= 0 && position >= size) return -1
        if(position < blockStart || position >= blockStart + block.size) {
            val end = position + 256*1024 - 1
            http.newCall(Request.Builder().url(url).header("Range", "bytes=$position-$end").build()).execute().use { response ->
                if(response.code != 206) throw IOException("Range not supported")
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty()) ?: throw IOException("Invalid range")
                if(range.groupValues[1].toLong() != position) throw IOException("Invalid range offset")
                size = range.groupValues[3].toLong()
                val expected = (range.groupValues[2].toLong() - position + 1).toInt()
                if(expected !in 1..256*1024) throw IOException("Invalid range length")
                block = response.body?.byteStream()?.readBytesLimited(expected) ?: throw IOException("Empty range")
                blockStart = position; consumed += block.size
            }
        }
        val start = (position-blockStart).toInt()
        val count = minOf(requested, block.size-start)
        if(count <= 0) return -1
        block.copyInto(buffer, offset, start, start+count)
        return count
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val result = ByteArray(limit); var count=0
        while(count < limit) { val read=read(result,count,limit-count); if(read<0)break; count+=read }
        return result.copyOf(count)
    }
    override fun close() { closed=true; block=byteArrayOf() }
}
