package app.umuo.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.umuo.core.db.UmuoDatabase
import app.umuo.core.library.LibraryRepository
import app.umuo.core.model.*
import app.umuo.core.playback.*
import app.umuo.core.source.MediaSourceProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorTest {
    private class Engine : PlaybackEngine {
        override val state = MutableStateFlow(PlaybackState())
        override fun prepare(plan: PlaybackPlan) { state.value=PlaybackState(ready=true,playing=true,durationMs=60_000,positionMs=2_000) }
        override fun play(){state.value=state.value.copy(playing=true)}
        override fun pause(){state.value=state.value.copy(playing=false)}
        override fun seekTo(positionMs:Long){state.value=state.value.copy(positionMs=positionMs)}
        override fun setSpeed(speed:Float){}
        override fun selectAudio(id:String){}
        override fun selectSubtitle(id:String?){}
        override fun release(){}
    }
    private class Source : MediaSourceProvider {
        override val connection=SourceConnection("s","fixture",SourceKind.JELLYFIN,"https://fixture.test/")
        override val capabilities=SourceCapabilities(serverProgress=true)
        val events=mutableListOf<PlaybackEvent>()
        var closed=false
        override suspend fun authenticate(credentials:MediaCredentials)=connection
        override suspend fun browse(parent:String?,offset:Int)=MediaPage(emptyList())
        override suspend fun details(id:MediaId)=MediaItem(id,"fixture")
        override suspend fun resolve(request:PlaybackRequest)=PlaybackPlan(request.item,"https://fixture.test/video.mp4")
        override suspend fun report(plan:PlaybackPlan,event:PlaybackEvent){events+=event}
        override suspend fun closeSession(plan:PlaybackPlan){closed=true}
        override fun close(){}
    }
    @Test fun pauseAndStopSavePositionWithSingleStartAndOrderedEvents()=runTest {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY);UmuoDatabase.Schema.create(driver)
        try {
            val library=LibraryRepository(UmuoDatabase(driver));val engine=Engine();val source=Source()
            val coordinator=PlaybackCoordinator(engine,library,backgroundScope){fail(it)}
            val item=MediaItem(MediaId("s","v"),"Video")
            coordinator.open(source,PlaybackRequest(item,DeviceProfile()))
            advanceTimeBy(501);runCurrent()
            engine.seekTo(6_000);engine.pause();advanceTimeBy(501);runCurrent()
            coordinator.stop()
            assertEquals(listOf(PlaybackEventKind.START,PlaybackEventKind.PROGRESS,PlaybackEventKind.STOP),source.events.map{it.kind})
            assertEquals(6_000,library.position(item.id));assertTrue(source.closed)
        }finally{driver.close()}
    }
}
