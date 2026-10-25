package app.umuo.core.source

import app.umuo.core.model.*
import app.umuo.core.playback.*

interface MediaSourceProvider {
    val connection: SourceConnection
    val capabilities: SourceCapabilities
    suspend fun authenticate(credentials: MediaCredentials): SourceConnection
    suspend fun browse(parent: String? = null, offset: Int = 0): MediaPage
    suspend fun details(id: MediaId): MediaItem
    suspend fun resolve(request: PlaybackRequest): PlaybackPlan
    suspend fun report(plan: PlaybackPlan, event: PlaybackEvent)
    suspend fun closeSession(plan: PlaybackPlan) {}
    fun close()
}
class SourceRegistry(factories: Map<SourceKind, (SourceConnection) -> MediaSourceProvider>) {
    private val providers = factories.toMap()
    fun create(connection: SourceConnection): MediaSourceProvider =
        requireNotNull(providers[connection.kind]) { "未注册该来源" }(connection)
}
