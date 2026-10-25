package app.umuo.android

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import app.umuo.core.network.SourceFailure
import app.umuo.core.network.MediaAccessFailure
import app.umuo.core.network.origin
import app.umuo.core.network.MediaRedirectFailure
import javax.net.ssl.SSLException
import java.security.cert.CertificateException
import app.umuo.core.playback.PlaybackFailureKind
import java.io.IOException

fun playbackFailureKind(error: Throwable): PlaybackFailureKind {
    val causes=generateSequence(error) {it.cause}.take(8).toList()
    if(causes.any {it is SSLException || it is CertificateException})return PlaybackFailureKind.TLS
    if(causes.any {it is MediaRedirectFailure || it is UnsafeMediaRedirect})return PlaybackFailureKind.REDIRECT
    val status=causes.filterIsInstance<SourceFailure>().firstOrNull()?.status
        ?: causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
    if(status!=null)return when(status) {
        401,403->PlaybackFailureKind.AUTHENTICATION
        404,410->PlaybackFailureKind.NOT_FOUND
        in 300..399->PlaybackFailureKind.REDIRECT
        429->PlaybackFailureKind.RATE_LIMIT
        408,in 500..599->PlaybackFailureKind.NETWORK
        else->PlaybackFailureKind.OTHER
    }
    if(error is PlaybackException)return when(error.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> PlaybackFailureKind.UNSUPPORTED
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackFailureKind.NETWORK
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackFailureKind.NOT_FOUND
        else -> if(causes.any {it is IOException})PlaybackFailureKind.NETWORK else PlaybackFailureKind.OTHER
    }
    return if(causes.any {it is IOException})PlaybackFailureKind.NETWORK else PlaybackFailureKind.OTHER
}
fun PlaybackFailureKind.message(): String = when(this) {
    PlaybackFailureKind.AUTHENTICATION->"认证已失效或没有访问权限，请检查连接账号。"
    PlaybackFailureKind.TLS->"HTTPS 证书校验失败，请检查证书链、有效期与域名。"
    PlaybackFailureKind.REDIRECT->"播放直链跳转失败，请检查 OpenList 的直链与重定向设置。"
    PlaybackFailureKind.RATE_LIMIT->"服务器正在限制请求，请稍后再播放。"
    PlaybackFailureKind.NETWORK->"网络连接失败，请检查服务器、网络或 HTTPS 证书后重试。"
    PlaybackFailureKind.UNSUPPORTED->"当前设备无法解码此资源。"
    PlaybackFailureKind.NOT_FOUND->"资源已移动、删除或播放地址已失效，请刷新媒体库。"
    PlaybackFailureKind.OTHER->"播放未能完成，请重试或检查媒体来源。"
}

fun playbackFailureMessage(error: Throwable, sourceBaseUrl: String? = null): String {
    val causes=generateSequence(error){it.cause}.take(8).toList()
    val access = causes.filterIsInstance<MediaAccessFailure>().firstOrNull()
    if (access != null && access.status in setOf(401, 403)) return access.message.orEmpty()
    val response = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    if (response != null && response.responseCode in setOf(401, 403)) {
        val redirected = sourceBaseUrl != null && runCatching {
            origin(response.dataSpec.uri.toString()) != origin(sourceBaseUrl)
        }.getOrDefault(false)
        return if (redirected) "播放直链拒绝访问（HTTP ${response.responseCode}），请检查直链有效期与存储访问限制。"
        else "媒体服务器拒绝读取视频（HTTP ${response.responseCode}），请检查文件读取权限。"
    }
    val redirect=causes.filterIsInstance<MediaRedirectFailure>().firstOrNull()
    if(redirect!=null)return "播放直链跳转失败：${redirect.message}"
    if(causes.any {it is UnsafeMediaRedirect})return "播放直链跳转失败：HTTPS 不能降级为 HTTP"
    return playbackFailureKind(error).message()
}
