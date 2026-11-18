package app.umuo.core.library

import app.umuo.core.metadata.*
import app.umuo.core.model.*
import app.umuo.core.network.SourceFailure
import app.umuo.core.source.webdav.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

 data class ScanState(val running: Boolean = false, val indexed: Int = 0, val failures: Int = 0, val directory: String = "", val requests: Int = 0, val pausedReason: String? = null, val resumeAfterMs: Long = 0)
class LibraryScanner(private val repository: LibraryRepository) {
    private val mutable = MutableStateFlow(ScanState())
    val state = mutable.asStateFlow()
    private val scanLock = Mutex()
    suspend fun scan(original: WebDavProvider, metadata: MetadataProvider? = null, traffic: ScanTrafficGuard = ScanTrafficGuard()) = scanLock.withLock {
        val source=original.forScan(traffic)
        val id = source.connection.id
        if (repository.pending(id).isEmpty()) repository.enqueue(id, source.connection.baseUrl)
        mutable.value = ScanState(running = true)
        val attempted = mutableSetOf<String>()
        val seriesByPath = repository.items(id).filter { it.kind == MediaKind.SERIES }.associateBy { it.path }.toMutableMap()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val directory = repository.pending(id).firstOrNull { it !in attempted } ?: break
                attempted += directory
                mutable.update { it.copy(directory = directory) }
                try {
                    val entries = source.listEntries(directory)
                    mutable.update {it.copy(requests=traffic.requests)}
                    val pendingDirs = entries.filter { it.directory && it.url !in attempted }.map { it.url }
                    if (pendingDirs.isNotEmpty()) repository.enqueueAll(id, pendingDirs)
                    val videos = entries.filter { !it.directory && it.extension in videoExtensions }
                    val showNfo = entries.firstOrNull {it.name.equals("tvshow.nfo",true)}
                    var pendingShow: MediaItem? = null
                    val show = if(showNfo != null) {
                        val poster = entries.firstOrNull {it.name.lowercase() in setOf("poster.jpg","poster.png","folder.jpg")}
                        val backdrop = entries.firstOrNull {it.name.lowercase() in setOf("fanart.jpg","fanart.png")}
                        NfoParser.apply(MediaItem(MediaId(id,"series:$directory"),"",MediaKind.SERIES,path=directory),source.readText(showNfo.url))
                            .copy(seriesKey=directory,artwork=poster?.url,backdrop=backdrop?.url,
                                fingerprint=listOf(showNfo.fingerprint,poster?.fingerprint,backdrop?.fingerprint).joinToString("|"))
                            .also { pendingShow = it; seriesByPath[directory] = it }
                    } else {
                        seriesByPath.remove(directory)
                        seriesByPath.values.filter {
                            it.path!=directory && directory.startsWith(it.path.trimEnd('/')+"/")
                        }.maxByOrNull {it.path.length}
                    }
                    val existingById = repository.directoryItems(id, directory)
                    val overrideIds = if (metadata != null) repository.overrideIds(id) else emptySet()
                    val pendingUpserts = mutableListOf<MediaItem>()
                    pendingShow?.let { pendingUpserts += it }
                    val pendingTmdb = mutableListOf<MediaItem>()
                    for(entry in videos) {
                        val raw = source.item(entry)
                        val old = existingById[raw.id.value]
                        // 资料变更也会触发重读；视频本身没变化时仍保留缓存元数据。
                        var item = FilenameParser.parse(raw)
                        val stem = entry.name.substringBeforeLast('.')
                        val nfo = entries.firstOrNull { it.name.equals("$stem.nfo", true) }
                            ?: entries.firstOrNull { videos.size == 1 && it.name.equals("movie.nfo", true) }
                        val poster = entries.firstOrNull { it.name.equals("$stem-poster.jpg", true) }
                            ?: entries.firstOrNull { it.name.lowercase() in setOf("poster.jpg", "folder.jpg", "poster.png") }
                        val backdrop = entries.firstOrNull { it.name.equals("$stem-fanart.jpg", true) || it.name.equals("$stem-fanart.png", true) }
                            ?: entries.firstOrNull { videos.size == 1 && it.name.lowercase() in setOf("fanart.jpg", "fanart.png") }
                        val fingerprint = listOf("catalog-v3",raw.fingerprint, nfo?.fingerprint, poster?.fingerprint, backdrop?.fingerprint,show?.fingerprint).joinToString("|")
                        val autoEligible = metadata != null && nfo == null && raw.id.value !in overrideIds
                        if (old != null && old.fingerprint == fingerprint && !autoEligible) {
                            mutable.update { it.copy(indexed = it.indexed + 1,requests=traffic.requests) }
                            continue
                        }
                        if (nfo != null) {
                            try { item = NfoParser.apply(item, source.readText(nfo.url)) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { throw e }
                        }
                        if(show != null && item.kind==MediaKind.EPISODE) item=item.copy(
                            series=show.title,seriesKey=show.seriesKey,
                            metadataOrigin=if(item.hasMetadata)item.metadataOrigin else show.metadataOrigin,
                            year=item.year ?: show.year,artwork=item.artwork ?: show.artwork,backdrop=item.backdrop ?: show.backdrop,
                            genres=(item.genres+show.genres).distinct(),tags=(item.tags+show.tags).distinct(),
                            collection=item.collection ?: show.collection,collections=(item.collections+show.collections).distinct())
                        item = item.copy(artwork = poster?.url ?: item.artwork, backdrop = backdrop?.url ?: item.backdrop, durationMs = old?.durationMs ?: item.durationMs, fingerprint = fingerprint)
                        pendingUpserts += item
                        if (metadata != null && nfo == null && item.kind == MediaKind.MOVIE && item.id.value !in overrideIds) {
                            pendingTmdb += item
                        }
                        mutable.update { it.copy(indexed = it.indexed + 1,requests=traffic.requests) }
                    }
                    repository.commitDirectory(id, directory, pendingUpserts, videos.map { it.url }.toSet() + listOfNotNull(show?.takeIf {it.path==directory}?.id?.value))
                    for (item in pendingTmdb) {
                        try {
                            fun canonical(value: String) = value.lowercase().filter { it.isLetterOrDigit() }
                            val matches = metadata!!.search(item.title, item.year, false).filter {
                                canonical(it.title) == canonical(item.title) && (item.year == null || it.year == item.year)
                            }
                            if (matches.size == 1) repository.match(item, matches.single())
                        } catch (e: CancellationException) { throw e }
                        catch (e: ScanPaused) {throw e}
                        catch (e: SourceFailure) {if(e.status in setOf(401,403,429,503))throw ScanPaused(message="服务拒绝请求，扫描已暂停");mutable.update {it.copy(failures=it.failures+1)}}
                        catch (_: Exception) { mutable.update { it.copy(failures = it.failures + 1) } }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: ScanPaused) {throw e}
                catch (e: SourceFailure) {if(e.status in setOf(401,403,429,503))throw ScanPaused(message="认证或访问受限，扫描已暂停");mutable.update {it.copy(failures=it.failures+1)}}
                catch (_: Exception) { mutable.update { it.copy(failures = it.failures + 1) } }
            }
        } catch(e: ScanPaused) {
            mutable.update {it.copy(pausedReason=e.message,resumeAfterMs=e.untilMs,failures=it.failures+1)}
        } finally {
            source.close()
            mutable.update { it.copy(running = false, directory = "", requests=traffic.requests) }
        }
    }
}
