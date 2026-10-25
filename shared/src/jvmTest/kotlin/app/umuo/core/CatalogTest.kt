package app.umuo.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.umuo.core.db.UmuoDatabase
import app.umuo.core.library.*
import app.umuo.core.metadata.*
import app.umuo.core.model.*
import app.umuo.core.network.wireJson
import app.umuo.core.source.webdav.WebDavProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlin.test.*

class CatalogTest {
    private fun item(id:String,title:String="Film")=MediaItem(MediaId("dav",id),title,MediaKind.MOVIE)

    @Test fun nfoWithoutProviderIdQualifiesButFilenameAndPosterDoNot() {
        val raw=FilenameParser.parse(item("raw","Film.2024.mp4")).copy(artwork="poster.jpg")
        val nfo=NfoParser.apply(item("nfo"),"""<movie><title>本地电影</title><genre>科幻</genre><genre>科幻</genre><tag>珍藏</tag><tag> </tag><set><name>星际合集</name></set></movie>""")
        assertNull(nfo.metadataId);assertTrue(nfo.hasMetadata)
        assertEquals(listOf("科幻"),nfo.genres);assertEquals(listOf("珍藏"),nfo.tags);assertEquals("星际合集",nfo.collection)
        assertEquals(listOf(nfo),LibraryCatalog.entries(listOf(raw,nfo)))
        assertEquals("旧合集",NfoParser.apply(raw,"<movie><set>旧合集</set></movie>").collection)
    }
    @Test fun independentFacetsAndMultipleSeriesCollectionsNarrowResults() {
        val a=item("a").copy(metadataOrigin=MetadataOrigin.NFO,genres=listOf("科幻"),tags=listOf("科幻","珍藏"),collection=" A ")
        val b=item("b").copy(metadataOrigin=MetadataOrigin.NFO,genres=listOf("剧情"),tags=listOf("家庭"),collections=listOf("B","C"))
        assertEquals(listOf(a),LibraryCatalog.filter(listOf(a,b),CatalogFilter(genre="科幻")))
        assertEquals(listOf(b),LibraryCatalog.filter(listOf(a,b),CatalogFilter(tag="家庭")))
        assertEquals(listOf(a),LibraryCatalog.filter(listOf(a,b),CatalogFilter(collection="a")))
        assertEquals(listOf(b),LibraryCatalog.filter(listOf(a,b),CatalogFilter(collection=" C ")))
        val facets=LibraryCatalog.facets(listOf(a,b),CatalogFilter())
        assertFalse(facets.tags.any {it.value=="科幻"})
        assertEquals(1,facets.collections.single {it.value=="C"}.count)
        val nfo=NfoParser.apply(item("e").copy(kind=MediaKind.EPISODE,series="Show"),"""<episodedetails><title>Ep</title><showtitle>Show</showtitle><set><name>A</name></set><set><name>B</name></set></episodedetails>""")
        assertEquals(listOf("A","B"),nfo.collections)
        val entries=LibraryCatalog.entries(listOf(nfo))
        assertEquals(1,LibraryCatalog.filter(entries,CatalogFilter(collection="B")).size)
        assertTrue(LibraryCatalog.facets(entries,CatalogFilter()).collections.isEmpty()) // 无法缩小结果的合集不重复占位。
    }
    @Test fun explicitMatchedCollectionReplacesOldNfoCollections() {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY);UmuoDatabase.Schema.create(driver)
        try {
            val repository=LibraryRepository(UmuoDatabase(driver))
            val original=item("film").copy(metadataOrigin=MetadataOrigin.NFO,collection="Old",collections=listOf("Old"))
            repository.upsert(original,"/")
            repository.match(original,item("metadata").copy(metadataOrigin=MetadataOrigin.ONLINE,collection="New"))
            assertEquals(listOf("New"),LibraryCatalog.collections(repository.items("dav").single()))
            assertTrue(LibraryCatalog.filter(repository.items("dav"),CatalogFilter(collection="Old")).isEmpty())
        } finally {driver.close()}
    }
    @Test fun legacyPayloadAndManualMatchRemainCompatible() {
        val old=wireJson.decodeFromString<MediaItem>("""{"id":{"sourceId":"dav","value":"1"},"title":"Legacy","kind":"MOVIE","metadataId":"tmdb:1"}""")
        assertTrue(old.hasMetadata);assertEquals(1,LibraryCatalog.entries(listOf(old)).size)
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY);UmuoDatabase.Schema.create(driver)
        try {
            val r=LibraryRepository(UmuoDatabase(driver));val raw=item("file").copy(kind=MediaKind.VIDEO);r.upsert(raw,"/")
            val metadata=raw.copy(kind=MediaKind.MOVIE,metadataId="tmdb:2",metadataOrigin=MetadataOrigin.ONLINE,genres=listOf("Drama"),tags=listOf("Favorite"))
            r.match(raw,metadata);assertEquals(1,LibraryCatalog.entries(r.items("dav")).size)
            assertEquals(listOf("Drama"),r.items("dav").single().genres)
            r.match(raw,null);assertTrue(LibraryCatalog.entries(r.items("dav")).isEmpty())
        } finally {driver.close()}
    }
    @Test fun seriesGroupBySourceAndDirectoryAndSortEpisodes() {
        val ep=item("e1","Episode 1").copy(kind=MediaKind.EPISODE,series="Same title",seriesKey="/show-a/",season=2,episode=1,metadataOrigin=MetadataOrigin.NFO,genres=listOf("Drama"))
        val earlier=ep.copy(id=MediaId("dav","e0"),season=1,episode=2)
        val other=ep.copy(id=MediaId("dav","e2"),seriesKey="/show-b/")
        val source=ep.copy(id=MediaId("other","e1"))
        val all=listOf(ep,earlier,other,source)
        assertEquals(3,LibraryCatalog.entries(all).size)
        assertEquals(listOf(earlier,ep),LibraryCatalog.episodes(all.filter {it.id.sourceId=="dav"},"/show-a/"))
    }
    @Test fun filtersCombineAndMissingFieldsDoNotFabricateFacets() {
        val movie=item("movie").copy(metadataOrigin=MetadataOrigin.NFO,genres=listOf("科幻"),tags=listOf("珍藏"),collection="合集")
        val second=movie.copy(id=MediaId("dav","movie2"),tags=listOf("普通"),collection=null)
        val entries=LibraryCatalog.entries(listOf(movie,second))
        assertEquals(listOf(movie),LibraryCatalog.filter(entries,CatalogFilter(type=CatalogType.MOVIES,genre="科幻",tag="珍藏",collection="合集")))
        assertTrue(LibraryCatalog.filter(entries,CatalogFilter(type=CatalogType.SERIES)).isEmpty())
        assertTrue(LibraryCatalog.filter(entries,CatalogFilter(genre="不存在")).isEmpty())
        assertEquals(entries,LibraryCatalog.filter(entries,CatalogFilter()))
    }
    @Test fun scanInheritsTvshowThroughSeasonAndPersistsOffline()=runTest {
        val base="https://dav.test/videos/"
        fun listing(vararg files:Pair<String,Boolean>)="<d:multistatus xmlns:d=\"DAV:\">"+files.joinToString("") {(path,folder)->
            "<d:response><d:href>/videos/$path</d:href><d:propstat><d:prop><d:displayname>${path.trimEnd('/').substringAfterLast('/')}</d:displayname><d:resourcetype>${if(folder)"<d:collection/>" else ""}</d:resourcetype><d:getetag>v1</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        }+"</d:multistatus>"
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY);UmuoDatabase.Schema.create(driver)
        val repository=LibraryRepository(UmuoDatabase(driver))
        val client=HttpClient(MockEngine {request->
            val body=when(request.url.encodedPath) {
                "/videos/"->listing("Show/" to true,"Raw.2024.mp4" to false)
                "/videos/Show/"->listing("Show/tvshow.nfo" to false,"Show/poster.jpg" to false,"Show/Season1/" to true)
                "/videos/Show/tvshow.nfo"->"<tvshow><title>测试剧集</title><genre>剧情</genre><tag>精选</tag></tvshow>"
                "/videos/Show/Season1/"->listing("Show/Season1/S01E02.mp4" to false,"Show/Season1/S01E01.mp4" to false)
                else->error("Unexpected request")
            }
            respond(body,headers=headersOf("Content-Type","application/xml"))
        })
        val provider=WebDavProvider(SourceConnection("dav","DAV",SourceKind.WEBDAV,base),client,MemorySecrets())
        try {
            LibraryScanner(repository).scan(provider)
            val restored=LibraryRepository(UmuoDatabase(driver));val entries=LibraryCatalog.entries(restored.items("dav"))
            assertEquals(1,entries.size);assertEquals("测试剧集",entries.single().title);assertEquals(listOf("精选"),entries.single().tags)
            val episodes=LibraryCatalog.episodes(restored.items("dav"),entries.single().seriesKey!!)
            assertEquals(listOf(1,2),episodes.map {it.episode});assertTrue(episodes.all {it.genres==listOf("剧情") && it.artwork!=null})
            assertTrue(restored.items("dav").any {it.title=="Raw" && !it.hasMetadata})
        } finally {provider.close();client.close();driver.close()}
    }
}
