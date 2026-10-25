package app.umuo.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*

/** 探测、CDN 请求和实际播放必须使用相同 UA，部分签名直链会绑定此值。 */
const val MEDIA_USER_AGENT = "UmuoPlayer/1.0"
class MediaAccessFailure(status: Int, val redirected: Boolean) : SourceFailure(status,
    if (redirected) "播放直链拒绝访问（HTTP $status），请检查直链有效期与存储访问限制。"
    else "WebDAV 拒绝读取视频（HTTP $status），请检查账号的文件读取权限。")

class MediaRedirectFailure(message: String) : Exception(message)
data class MediaProbe(val uri: String,val seekable: Boolean)

/** Location 按当前资源解析；仅用于临时播放地址，不写入媒体索引。 */
internal fun mediaRedirectUrl(current: String, location: String): String {
    val value=location.trim()
    if(value.isEmpty())throw MediaRedirectFailure("播放跳转未提供地址")
    val base=URLBuilder(current).apply {encodedPath="/";parameters.clear();fragment=""}.buildString().trimEnd('/')
    val target=when {
        value.startsWith("//")->Url(current).protocol.name+":"+value
        value.startsWith("/")->base+value
        value.startsWith("#")->current.substringBefore('#')+value
        value.startsWith("?")->current.substringBefore('#').substringBefore('?')+value
        Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value)->value
        else->current.substringBefore('#').substringBefore('?').substringBeforeLast('/')+"/"+value
    }
    val url=Url(target)
    if(url.protocol!=URLProtocol.HTTP && url.protocol!=URLProtocol.HTTPS || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty())
        throw MediaRedirectFailure("播放跳转地址不安全")
    val segments=mutableListOf<String>()
    url.encodedPath.split('/').forEach {part->when(part) {"."->Unit;".."->if(segments.isNotEmpty())segments.removeAt(segments.lastIndex);else->segments.add(part)}}
    return URLBuilder(url).apply {encodedPath=segments.joinToString("/").let {if(it.startsWith('/'))it else "/$it"};fragment=""}.buildString()
}

/** 每一跳都关闭自动重定向；跨源使用无任何认证插件/默认头的独立客户端。 */
internal suspend fun probeMedia(
    initial: String, sourceOrigin: String, authenticated: HttpClient, anonymous: HttpClient,
    headers: ()->Map<String,String>
): MediaProbe {
    var current=initial
    val visited=mutableSetOf<String>()
    repeat(6) {hop->
        if(!visited.add(current))throw MediaRedirectFailure("播放地址出现循环跳转")
        val sameOrigin=origin(current)==sourceOrigin
        val client=if(sameOrigin)authenticated else anonymous
        val result=client.prepareGet(current) {
            if(sameOrigin)headers().forEach {(key,value)->header(key,value)}
            header(HttpHeaders.UserAgent, MEDIA_USER_AGENT)
            header(HttpHeaders.Range,"bytes=0-0")
        }.execute {response->Triple(response.status.value,response.headers[HttpHeaders.Location],response.headers[HttpHeaders.ContentRange])}
        if(result.first in setOf(301,302,303,307,308)) {
            if(hop==5)throw MediaRedirectFailure("播放地址跳转次数过多")
            val next=mediaRedirectUrl(current,result.second ?: throw MediaRedirectFailure("播放跳转缺少地址"))
            if(Url(current).protocol==URLProtocol.HTTPS && Url(next).protocol!=URLProtocol.HTTPS)
                throw MediaRedirectFailure("HTTPS 播放地址不能跳转到 HTTP")
            current=next
        } else {
            if(result.first !in 200..299)throw MediaAccessFailure(result.first, !sameOrigin)
            return MediaProbe(current,result.first==206 && result.third?.startsWith("bytes 0-0/")==true)
        }
    }
    throw MediaRedirectFailure("播放地址跳转次数过多")
}
