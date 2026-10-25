package app.umuo.core

import app.umuo.core.model.*
import app.umuo.core.network.*
import app.umuo.core.playback.*
import app.umuo.core.source.webdav.WebDavProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MediaRedirectTest {
    @Test fun httpsOpenListRedirectStripsCredentialsAndKeepsSignedQuery()=runTest {
        var cdnRequests=0
        val client=HttpClient(MockEngine {r->
            if(r.method==HttpMethod.Get) assertEquals(MEDIA_USER_AGENT,r.headers[HttpHeaders.UserAgent])
            when {
                r.url.host=="cdn.test"->{
                    cdnRequests++;assertNull(r.headers[HttpHeaders.Authorization]);assertNull(r.headers["X-Emby-Token"])
                    assertEquals("signed-test",r.url.parameters["sign"]);assertEquals("bytes=0-0",r.headers[HttpHeaders.Range])
                    respond("x",HttpStatusCode.PartialContent,headersOf(HttpHeaders.ContentRange,"bytes 0-0/100"))
                }
                r.method.value=="PROPFIND"->respond("""<d:multistatus xmlns:d="DAV:"/>""",headers=headersOf("Content-Type","application/xml"))
                r.headers[HttpHeaders.Authorization]==null->respond("",HttpStatusCode.Unauthorized,headersOf(HttpHeaders.WWWAuthenticate,"Basic realm=\"fixture\""))
                else->respond("",HttpStatusCode.Found,headersOf(HttpHeaders.Location,"https://cdn.test/video.mp4?sign=signed-test"))
            }
        })
        val secrets=MemorySecrets().also {it.write("s:password","fixture")}
        val p=WebDavProvider(SourceConnection("s","OpenList",SourceKind.WEBDAV,"https://dav.test/dav/","fixture"),client,secrets)
        try {
            val item=MediaItem(MediaId("s","https://dav.test/dav/movie.mp4"),"Movie",path="https://dav.test/dav/movie.mp4")
            val plan=p.resolve(PlaybackRequest(item,DeviceProfile()))
            assertEquals("https://cdn.test/video.mp4?sign=signed-test",plan.uri);assertTrue(plan.seekable)
            assertEquals(item.path,plan.item.path);assertEquals(1,cdnRequests)
            assertTrue(p.browse().items.isEmpty()) // 关闭匿名探测客户端不影响原来源。
        } finally {p.close();client.close()}
    }
    @Test fun httpsDowngradeIsRejectedBeforeSendingCredentialsOrAnonymousRequest()=runTest {
        var requests=0
        val client=HttpClient(MockEngine {requests++;respond("",HttpStatusCode.Found,headersOf(HttpHeaders.Location,"http://cdn.test/file"))})
        val p=WebDavProvider(SourceConnection("s","DAV",SourceKind.WEBDAV,"https://dav.test/dav/"),client,MemorySecrets())
        try {
            assertFailsWith<MediaRedirectFailure>{p.resolve(PlaybackRequest(MediaItem(MediaId("s","id"),"file",path="https://dav.test/dav/file"),DeviceProfile()))}
            assertEquals(1,requests)
        } finally {p.close();client.close()}
    }
    @Test fun upstreamForbiddenIsNotReportedAsExpiredWebDavAccount()=runTest {
        val client=HttpClient(MockEngine {r->
            if(r.url.host=="dav.test") respond("",HttpStatusCode.Found,headersOf(HttpHeaders.Location,"https://cdn.test/file?sign=private"))
            else respond("",HttpStatusCode.Forbidden)
        }) {followRedirects=false;expectSuccess=false}
        val anonymous=HttpClient(client.engine) {followRedirects=false;expectSuccess=false}
        try {
            val failure=assertFailsWith<MediaAccessFailure>{probeMedia("https://dav.test/file","https://dav.test:443",client,anonymous){emptyMap()}}
            assertTrue(failure.redirected);assertEquals(403,failure.status)
            assertFalse(failure.message.orEmpty().contains("private"));assertTrue(failure.message.orEmpty().contains("直链"))
        } finally {anonymous.close();client.close()}
    }
    @Test fun originForbiddenHasFilePermissionContext()=runTest {
        val client=HttpClient(MockEngine {respond("",HttpStatusCode.Forbidden)})
        try {
            val failure=assertFailsWith<MediaAccessFailure>{probeMedia("https://dav.test/file","https://dav.test:443",client,client){emptyMap()}}
            assertFalse(failure.redirected);assertTrue(failure.message.orEmpty().contains("WebDAV"))
        } finally {client.close()}
    }
    @Test fun relativeLocationsKeepQueryAndResolveParentPath() {
        assertEquals("https://dav.test/dav/stream/movie?sign=test",mediaRedirectUrl("https://dav.test/dav/folder/movie.mp4","../stream/movie?sign=test"))
        assertEquals("https://cdn.test/file?sign=a%2Fb%2B1&expires=2",mediaRedirectUrl("https://dav.test/movie","https://cdn.test/file?sign=a%2Fb%2B1&expires=2"))
        assertEquals("https://cdn.test/file",mediaRedirectUrl("https://dav.test/dav/movie","//cdn.test/file"))
        assertEquals("https://dav.test/dav/movie?sign=new",mediaRedirectUrl("https://dav.test/dav/movie?sign=old","?sign=new"))
        assertFailsWith<MediaRedirectFailure>{mediaRedirectUrl("https://dav.test/file","https://user:secret@cdn.test/file")}
        assertFailsWith<MediaRedirectFailure>{mediaRedirectUrl("https://dav.test/file","file:///etc/passwd")}
    }
    @Test fun uniqueRedirectChainIsBounded()=runTest {
        var requests=0
        val client=HttpClient(MockEngine {requests++;respond("",HttpStatusCode.Found,headersOf(HttpHeaders.Location,"/dav/file?hop=$requests"))})
        val p=WebDavProvider(SourceConnection("s","DAV",SourceKind.WEBDAV,"https://dav.test/dav/"),client,MemorySecrets())
        try {
            assertFailsWith<MediaRedirectFailure>{p.resolve(PlaybackRequest(MediaItem(MediaId("s","id"),"file",path="https://dav.test/dav/file"),DeviceProfile()))}
            assertEquals(6,requests)
        } finally {p.close();client.close()}
    }
    @Test fun redirectLoopStopsWithoutDownloadingAWholeVideo()=runTest {
        var requests=0
        val client=HttpClient(MockEngine {requests++;respond("",HttpStatusCode.Found,headersOf(HttpHeaders.Location,"/dav/file"))})
        val p=WebDavProvider(SourceConnection("s","DAV",SourceKind.WEBDAV,"https://dav.test/dav/"),client,MemorySecrets())
        try {
            assertFailsWith<MediaRedirectFailure>{p.resolve(PlaybackRequest(MediaItem(MediaId("s","id"),"file",path="https://dav.test/dav/file"),DeviceProfile()))}
            assertEquals(1,requests)
        } finally {p.close();client.close()}
    }
}
