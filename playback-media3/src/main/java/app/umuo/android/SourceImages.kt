package app.umuo.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.umuo.core.model.CredentialStore
import app.umuo.core.model.SourceConnection
import coil.ImageLoader
import coil.intercept.Interceptor
import okhttp3.OkHttpClient
import java.security.MessageDigest

/** 绑定生命周期并在切换来源或离开界面时同步关闭底层 OkHttp 连接池与线程池。 */
@Composable fun rememberSourceImageLoader(connection: SourceConnection?, secrets: CredentialStore): ImageLoader {
    val context = LocalContext.current
    val bundle = remember(connection) {
        val client = connection?.let { sourceHttp(it, secrets) }
        sourceImages(context, connection, secrets, client) to client
    }
    DisposableEffect(bundle) {
        onDispose {
            bundle.first.shutdown()
            bundle.second?.shutdownCleanly()
        }
    }
    return bundle.first
}

/** 同一个图片 URL 在不同服务器实例或账号下也可能返回不同内容；内存与磁盘缓存均隔离。 */
fun sourceImages(
    context: Context, connection: SourceConnection?, secrets: CredentialStore, client: OkHttpClient? = null
): ImageLoader {
    val namespace = connection?.let { "${it.id}\u0000${it.kind}\u0000${it.baseUrl}\u0000${it.username}" } ?: "local"
    fun key(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$namespace\u0000$value".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    return ImageLoader.Builder(context).apply {
        connection?.let { okHttpClient(client ?: sourceHttp(it, secrets)) }
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
