package app.umuo.android

import android.content.Context
import app.umuo.core.model.CredentialStore
import app.umuo.core.model.SourceConnection
import coil.ImageLoader
import coil.intercept.Interceptor
import java.security.MessageDigest

/** 同一个图片 URL 在不同服务器实例或账号下也可能返回不同内容；内存与磁盘缓存均隔离。 */
fun sourceImages(context: Context, connection: SourceConnection?, secrets: CredentialStore): ImageLoader {
    val namespace = connection?.let { "${it.id}\u0000${it.kind}\u0000${it.baseUrl}\u0000${it.username}" } ?: "local"
    fun key(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$namespace\u0000$value".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    return ImageLoader.Builder(context).apply {
        connection?.let { okHttpClient(sourceHttp(it, secrets)) }
        components {
            add(Interceptor { chain ->
                val request = chain.request
                chain.proceed(request.newBuilder()
                    .memoryCacheKey(key(request.memoryCacheKey?.key ?: request.data.toString()))
                    .diskCacheKey(key(request.diskCacheKey ?: request.data.toString()))
                    .build())
            })
        }
    }.build()
}
