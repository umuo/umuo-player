package app.umuo.core

import app.umuo.core.model.*
import app.umuo.core.network.*
import app.umuo.core.playback.*
import app.umuo.core.source.mediaserver.*
import app.umuo.core.source.webdav.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MemorySecrets : CredentialStore {
    private val values=mutableMapOf<String,String>()
    override fun read(key:String)=values[key]
    override fun write(key:String,value:String){values[key]=value}
    override fun remove(key:String){values.remove(key)}
}
class ProviderContractTest {
    private fun server(kind:SourceKind, response:(io.ktor.client.request.HttpRequestData)->String): Pair<MediaServerProvider,MutableList<String>> {
        val paths=mutableListOf<String>()
        val http=HttpClient(MockEngine {request-> paths+=request.url.encodedPath
            respond(response(request),HttpStatusCode.OK,headersOf("Content-Type","application/json"))})
        val c=SourceConnection("source", "test",kind,"https://media.test/")
        val secrets=MemorySecrets()
        return (if(kind==SourceKind.EMBY) EmbyProvider(c,http,secrets) else JellyfinProvider(c,http,secrets)) to paths
    }
    private suspend fun checkServer(kind:SourceKind) {
        val (provider,paths)=server(kind) {request-> when {
            request.url.encodedPath.endsWith("AuthenticateByName") -> """{"AccessToken":"test-token","User":{"Id":"u1"}}"""
            request.url.encodedPath.endsWith("PlaybackInfo") -> """{"PlaySessionId":"session1","MediaSources":[{"Id":"version1","SupportsDirectPlay":true,"MediaStreams":[{"Type":"Subtitle","IsExternal":true,"Index":2,"Codec":"srt","DeliveryUrl":"/sub.srt","DisplayTitle":"中文"}]}]}"""
            request.url.encodedPath.endsWith("Views") -> {
                assertEquals("test-token",request.headers["X-Emby-Token"])
                """{"Items":[{"Id":"movie1","Name":"Test Movie","Type":"Movie","RunTimeTicks":200000000,"UserData":{"PlaybackPositionTicks":20000000}}],"TotalRecordCount":1}"""
            }
            else -> "{}"
        } }
        try {
            assertEquals("u1",provider.authenticate(MediaCredentials("user","password")).userId)
            val item=provider.browse().items.single()
            assertEquals(2_000,item.resumeMs)
            val plan=provider.resolve(PlaybackRequest(item,DeviceProfile(directPlayProfiles=listOf(DirectPlayProfile("mp4","h264","aac")))))
            assertEquals(PlaybackMode.DIRECT,plan.mode)
            assertEquals("application/x-subrip",plan.subtitles.single().mimeType)
            provider.report(plan,PlaybackEvent(PlaybackEventKind.START,2000))
            provider.report(plan,PlaybackEvent(PlaybackEventKind.PROGRESS,3000,true))
            provider.report(plan,PlaybackEvent(PlaybackEventKind.STOP,4000))
            val prefix=if(kind==SourceKind.EMBY)"/emby" else ""
            assertEquals(listOf("$prefix/Users/AuthenticateByName","$prefix/Users/u1/Views","$prefix/Items/movie1/PlaybackInfo","$prefix/Sessions/Playing","$prefix/Sessions/Playing/Progress","$prefix/Sessions/Playing/Stopped"),paths)
        } finally {provider.close()}
    }
    private suspend fun catalogContract(kind:SourceKind) {
        var expectedType="Movie"
        var expectedOffset="60"
        val (p,paths)=server(kind) {r->
            assertEquals("true",r.url.parameters["Recursive"])
            assertEquals(expectedType,r.url.parameters["IncludeItemTypes"])
            assertEquals(expectedOffset,r.url.parameters["StartIndex"])
            assertTrue(r.url.parameters["Fields"]!!.contains("Genres"))
            """{"Items":[{"Id":"m","Name":"Film","Type":"Movie","Genres":["科幻"],"Tags":["珍藏"]}],"TotalRecordCount":122}"""
        }
        try {
            val page=p.catalog(MediaKind.MOVIE,60)
            assertEquals(61,page.nextOffset)
            assertEquals(listOf("科幻"),page.items.single().genres);assertEquals(listOf("珍藏"),page.items.single().tags)
            assertEquals(MetadataOrigin.SERVER,page.items.single().metadataOrigin)
            expectedType="Series";expectedOffset="0"
            p.catalog(MediaKind.SERIES)
            assertEquals(2,paths.size)
            assertTrue(paths.all {it.startsWith(if(kind==SourceKind.EMBY)"/emby/" else "/Users/")})
        } finally {p.close()}
    }
    @Test fun embyCatalogContract()=runTest {catalogContract(SourceKind.EMBY)}
    @Test fun jellyfinCatalogContract()=runTest {catalogContract(SourceKind.JELLYFIN)}
    @Test fun jellyfinContract()=runTest {checkServer(SourceKind.JELLYFIN)}
    @Test fun embyContract()=runTest {checkServer(SourceKind.EMBY)}
    @Test fun embySubpathDoesNotDuplicatePrefix()=runTest {
        val client=HttpClient(MockEngine {r->assertEquals("/proxy/emby/Users/u/Views",r.url.encodedPath);respond("""{"Items":[]}""")})
        val p=EmbyProvider(SourceConnection("s","t",SourceKind.EMBY,"https://media.test/proxy/emby/",userId="u"),client,MemorySecrets())
        try{p.browse()}finally{p.close();client.close()}
    }
    @Test fun transcodeNegotiationAndRelease()=runTest {
        var deleted=false
        val (p,_)=server(SourceKind.JELLYFIN){r->when {
            r.url.encodedPath.endsWith("PlaybackInfo")->"""{"PlaySessionId":"p1","MediaSources":[{"Id":"m1","SupportsDirectPlay":false,"SupportsDirectStream":false,"TranscodingUrl":"/stream.m3u8"}]}"""
            r.method==HttpMethod.Delete->{deleted=true;"{}"}
            else->"{}"
        }}
        try{
            val plan=p.resolve(PlaybackRequest(MediaItem(MediaId("source","v"),"video"),DeviceProfile(),true))
            assertEquals(PlaybackMode.TRANSCODE,plan.mode);assertEquals("application/x-mpegURL",plan.mimeType)
            p.closeSession(plan);assertTrue(deleted)
        }finally{p.close()}
    }
    @Test fun davNamespaceEscapesAndFailedProperties()=runTest {
        val p=WebDavProvider(SourceConnection("dav","DAV",SourceKind.WEBDAV,"https://dav.test/videos/"),HttpClient(MockEngine{respond("")}),MemorySecrets())
        try {
            val rows=p.parseListing("""<x:multistatus xmlns:x="DAV:"><x:response><x:href>/videos/A%20%26%20B.mp4</x:href><x:propstat><x:prop><x:displayname>A &amp; B.mp4</x:displayname><x:resourcetype/></x:prop><x:status>HTTP/1.1 200 OK</x:status></x:propstat></x:response><x:response><x:href>/videos/missing.mp4</x:href><x:propstat><x:prop/><x:status>HTTP/1.1 404 Not Found</x:status></x:propstat></x:response></x:multistatus>""","https://dav.test/videos/")
            assertEquals("A & B.mp4",rows.single().name)
            assertTrue(rows.single().url.endsWith("A%20%26%20B.mp4"))
        }finally{p.close()}
    }
    @Test fun transportRejectsCrossOriginBeforeRequest()=runTest {
        var requests=0
        val t=HttpTransport("https://source.test/",HttpClient(MockEngine{requests++;respond("{}")})){mapOf("Authorization" to "secret")}
        try {assertFailsWith<IllegalArgumentException>{t.json("https://other.test/items")};assertEquals(0,requests)}finally{t.close()}
    }
    @Test fun authFailuresDoNotExposeServerBody()=runTest {
        val t=HttpTransport("https://source.test/",HttpClient(MockEngine{respond("secret-password",HttpStatusCode.Unauthorized)}))
        try {val error=assertFailsWith<SourceFailure>{t.json("items")};assertFalse(error.message!!.contains("secret-password"))}finally{t.close()}
    }
    @Test fun davBasicAndDigestAuthentication()=runTest {
        for(digest in listOf(false,true)) {
            val secrets=MemorySecrets()
            val c=SourceConnection("dav","DAV",SourceKind.WEBDAV,"https://dav.test/videos/","fixture")
            secrets.write("dav:password","fixture")
            var authenticated=false
            val base=HttpClient(MockEngine {r->
                val auth=r.headers[HttpHeaders.Authorization].orEmpty()
                if((digest && auth.startsWith("Digest ")) || (!digest && auth=="Basic Zml4dHVyZTpmaXh0dXJl")) {
                    authenticated=true
                    respond("""<d:multistatus xmlns:d="DAV:"/>""",headers=headersOf("Content-Type","application/xml"))
                } else respond("",HttpStatusCode.Unauthorized,headersOf("WWW-Authenticate",if(digest) """Digest realm="fixture", nonce="nonce", algorithm=MD5, qop="auth"""" else """Basic realm="fixture""""))
            })
            val provider=WebDavProvider(c,base,secrets)
            try {provider.browse();assertTrue(authenticated)}finally{provider.close();base.close()}
        }
    }
    @Test fun davRejectsTraversalAndRangeProbeDoesNotDownloadWholeMovie()=runTest {
        val methods=mutableListOf<String>()
        val base=HttpClient(MockEngine {r->
            methods+=r.method.value
            if(r.method==HttpMethod.Get) {
                assertEquals("bytes=0-0",r.headers[HttpHeaders.Range])
                respond("x",HttpStatusCode.PartialContent,headersOf("Content-Range","bytes 0-0/100"))
            } else respond("""<d:multistatus xmlns:d="DAV:"/>""")
        })
        val provider=WebDavProvider(SourceConnection("dav","DAV",SourceKind.WEBDAV,"https://dav.test/videos/"),base,MemorySecrets())
        try {
            val item=MediaItem(MediaId("dav","https://dav.test/videos/a.mp4"),"A",path="https://dav.test/videos/a.mp4")
            val plan=provider.resolve(PlaybackRequest(item,DeviceProfile()))
            assertTrue(plan.seekable);assertEquals(listOf("GET","PROPFIND"),methods)
            assertFailsWith<IllegalArgumentException>{provider.browse("https://dav.test/private/")}
        }finally{provider.close();base.close()}
    }

}
