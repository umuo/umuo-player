package app.umuo.core.library

import app.umuo.core.db.*
import app.umuo.core.model.*
import app.umuo.core.network.wireJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class LibraryRepository(private val db: UmuoDatabase) {
    private val q get() = db.libraryQueries
    fun connections(): List<SourceConnection> = q.allConnections().executeAsList().map { wireJson.decodeFromString(it.payload) }
    fun drafts(): List<SourceConnection> = q.allDrafts().executeAsList().map { wireJson.decodeFromString(it.payload) }
    fun saveDraft(connection: SourceConnection) = q.saveDraft(connection.id, wireJson.encodeToString(connection))
    fun discardDraft(id: String) = q.removeDraft(id)
    fun saveConnection(connection: SourceConnection) {
        val previous = connections().firstOrNull { it.id == connection.id }
        db.transaction {
            // 编辑名称或重试密码不影响续播；换服务器、根目录、账号时清除旧身份的数据。
            if(previous != null && (previous.kind != connection.kind || previous.baseUrl != connection.baseUrl || previous.username != connection.username)) {
                q.clearSourceMedia(connection.id)
                q.clearSourcePositions(connection.id)
                q.clearSourceOverrides(connection.id)
                q.clearSourceQueue(connection.id)
            }
            q.saveConnection(connection.id, wireJson.encodeToString(connection))
            q.removeDraft(connection.id)
        }
    }
    fun indexed(id: MediaId): MediaItem? = q.byId(id.sourceId, id.value).executeAsOneOrNull()?.let { wireJson.decodeFromString(it.payload) }
    private fun overrideMap(source: String): Map<String, String?> =
        q.allOverrides(source).executeAsList().associate { it.media_id to it.payload }
    private fun mergeOverride(item: MediaItem, payload: String?): MediaItem {
        val metadata = payload?.let { wireJson.decodeFromString<MediaItem>(it) }
            ?: return item.copy(metadataId = null)
        return item.copy(kind=if(item.kind==MediaKind.VIDEO && metadata.kind==MediaKind.MOVIE)MediaKind.MOVIE else item.kind,
            title = if(item.kind == MediaKind.EPISODE) item.title else metadata.title,
            series = if(item.kind == MediaKind.EPISODE) metadata.title else item.series, artwork = metadata.artwork ?: item.artwork,
            backdrop = metadata.backdrop ?: item.backdrop, overview = metadata.overview, year = metadata.year, metadataId = metadata.metadataId,
            metadataOrigin = if(metadata.hasMetadata) metadata.metadataOrigin else item.metadataOrigin,
            genres = metadata.genres.ifEmpty {item.genres}, tags = metadata.tags.ifEmpty {item.tags}, collection = metadata.collection ?: item.collection,collections=metadata.collections.ifEmpty {metadata.collection?.let {listOf(it)} ?: item.collections})
    }
    fun items(source: String): List<MediaItem> = db.transactionWithResult {
        val overrides = overrideMap(source)
        q.allMedia(source).executeAsList().map { row ->
            val item = wireJson.decodeFromString<MediaItem>(row.payload)
            if (row.media_id in overrides) mergeOverride(item, overrides[row.media_id]) else item
        }
    }
    fun updateCached(item: MediaItem) {
        val row=q.byId(item.id.sourceId,item.id.value).executeAsOneOrNull() ?: return
        upsert(item,row.parent)
    }
    fun upsert(item: MediaItem, parent: String) = q.upsert(item.id.sourceId, item.id.value, parent, item.fingerprint, wireJson.encodeToString(item))
    fun applyOverride(item: MediaItem): MediaItem {
        val row = q.overrideFor(item.id.sourceId, item.id.value).executeAsOneOrNull() ?: return item
        return mergeOverride(item, row.payload)
    }
    fun match(item: MediaItem, metadata: MediaItem?) = q.setOverride(item.id.sourceId, item.id.value, metadata?.let { wireJson.encodeToString(it) })
    fun automaticMatchAllowed(id: MediaId): Boolean = q.overrideFor(id.sourceId, id.value).executeAsOneOrNull() == null
    fun position(id: MediaId): Long = q.position(id.sourceId, id.value).executeAsOneOrNull() ?: 0
    fun savePosition(id: MediaId, position: Long) = q.savePosition(id.sourceId, id.value, position.coerceAtLeast(0))
    fun recent(source: String): List<MediaItem> = db.transactionWithResult {
        val overrides = overrideMap(source)
        q.recentItems(source).executeAsList().map {
            val item = wireJson.decodeFromString<MediaItem>(it.payload)
            val merged = if (item.id.value in overrides) mergeOverride(item, overrides[item.id.value]) else item
            merged.copy(resumeMs = it.milliseconds)
        }
    }
    fun transaction(block: () -> Unit) = db.transaction { block() }
    fun <T> transactionWithResult(block: () -> T): T = db.transactionWithResult { block() }
    fun saveDuration(id: MediaId, duration: Long) {
        val row = q.byId(id.sourceId, id.value).executeAsOneOrNull() ?: return
        val item = wireJson.decodeFromString<MediaItem>(row.payload)
        if(duration > 0 && item.durationMs != duration) q.upsert(row.source_id, row.media_id, row.parent, row.fingerprint,
            wireJson.encodeToString(item.copy(durationMs = duration)))
    }
    fun enqueue(source: String, path: String) = q.enqueue(source, path)
    fun pending(source: String): List<String> = q.queue(source).executeAsList()
    fun finishDirectory(source: String, path: String) = q.finishDirectory(source, path)
    fun pruneDirectory(source: String, parent: String, found: Set<String>) {
        db.transaction { q.children(source, parent).executeAsList().filter { it.media_id !in found }.forEach { q.removeMedia(source, it.media_id) } }
    }
}
