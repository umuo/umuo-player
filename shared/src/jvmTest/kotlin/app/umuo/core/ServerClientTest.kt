package app.umuo.core

import app.umuo.core.model.*
import app.umuo.core.network.SourceFailure
import app.umuo.core.source.mediaserver.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ServerClientTest {
    private suspend fun queries(kind:SourceKind) {
        val requests=mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client=HttpClient(MockEngine {r->requests+=r;respond("""{"Items":[{"Id":"m","Name":"Film","Type":"Movie","UserData":{"IsFavorite":true,"Played":true,"PlaybackPositionTicks":10000000}}],"TotalRecordCount":121}""",headers=headersOf("Content-Type","application/json"))})
        val c=SourceConnection("s","Fixture",kind,"https://server.test/proxy/",userId="user1")
        val p=if(kind==SourceKind.EMBY)EmbyProvider(c,client,MemorySecrets()) else JellyfinProvider(c,client,MemorySecrets())
        try {
            for(view in ServerView.entries) {
                val page=p.listing(ServerLibraryQuery(view),60)
                val r=requests.last();assertTrue(r.url.encodedPath.startsWith(if(kind==SourceKind.EMBY)"/proxy/emby/" else "/proxy/"))
                assertEquals("60",r.url.parameters["StartIndex"])
                assertEquals(true,page.items.single().favorite);assertEquals(true,page.items.single().watched);assertNull(page.items.single().episode);assertNull(page.items.single().season)
                when(view) {
                    ServerView.RESUME->if(kind==SourceKind.EMBY)assertEquals("IsResumable",r.url.parameters["Filters"]) else assertTrue(r.url.encodedPath.endsWith("/Items/Resume"))
                    ServerView.NEXT_UP->{assertTrue(r.url.encodedPath.endsWith("Shows/NextUp"));assertEquals("user1",r.url.parameters["UserId"])}
                    ServerView.FAVORITES->assertEquals("IsFavorite",r.url.parameters["Filters"])
                    ServerView.LATEST->{assertEquals("DateCreated",r.url.parameters["SortBy"]);assertEquals("Descending",r.url.parameters["SortOrder"])}
                    ServerView.COLLECTIONS->assertEquals("BoxSet",r.url.parameters["IncludeItemTypes"])
                    else->Unit
                }
            }
            val page=p.listing(ServerLibraryQuery(search="星海 & A/B"),60)
            assertEquals("星海 & A/B",requests.last().url.parameters["SearchTerm"])
            assertEquals(61,page.nextOffset)
        } finally {p.close();client.close()}
    }
    @Test fun embyViewerQueries()=runTest {queries(SourceKind.EMBY)}
    @Test fun jellyfinViewerQueries()=runTest {queries(SourceKind.JELLYFIN)}

    private suspend fun mutations(kind:SourceKind) {
        val requests=mutableListOf<Pair<String,HttpMethod>>()
        val client=HttpClient(MockEngine {r->requests+=r.url.encodedPath to r.method;respond("",HttpStatusCode.NoContent)})
        val c=SourceConnection("s","Fixture",kind,"https://server.test/",userId="u")
        val p=if(kind==SourceKind.EMBY)EmbyProvider(c,client,MemorySecrets()) else JellyfinProvider(c,client,MemorySecrets())
        try {
            val id=MediaId("s","m")
            p.setFavorite(id,true);p.setFavorite(id,false);p.setWatched(id,true);p.setWatched(id,false)
            val prefix=if(kind==SourceKind.EMBY)"/emby" else ""
            assertEquals(listOf("$prefix/Users/u/FavoriteItems/m" to HttpMethod.Post,"$prefix/Users/u/FavoriteItems/m" to HttpMethod.Delete,"$prefix/Users/u/PlayedItems/m" to HttpMethod.Post,"$prefix/Users/u/PlayedItems/m" to HttpMethod.Delete),requests)
            assertFailsWith<IllegalArgumentException>{p.setFavorite(MediaId("another-source","m"),true)}
            assertEquals(4,requests.size)
        } finally {p.close();client.close()}
    }
    @Test fun embyFavoriteAndWatchState()=runTest {mutations(SourceKind.EMBY)}
    @Test fun jellyfinFavoriteAndWatchState()=runTest {mutations(SourceKind.JELLYFIN)}
    @Test fun deniedMutationDoesNotBecomeSuccess()=runTest {
        val client=HttpClient(MockEngine {respond("private server body",HttpStatusCode.Forbidden)})
        val p=EmbyProvider(SourceConnection("s","Fixture",SourceKind.EMBY,"https://server.test/",userId="u"),client,MemorySecrets())
        try {
            val failure=assertFailsWith<SourceFailure>{p.setFavorite(MediaId("s","m"),true)}
            assertEquals(403,failure.status);assertFalse(failure.message!!.contains("private"))
        } finally {p.close();client.close()}
    }
}
