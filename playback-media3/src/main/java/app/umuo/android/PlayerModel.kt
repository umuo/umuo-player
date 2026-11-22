package app.umuo.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.umuo.core.db.UmuoDatabase
import app.umuo.core.library.*
import app.umuo.core.metadata.*
import app.umuo.core.model.*
import app.umuo.core.network.*
import app.umuo.core.playback.*
import app.umuo.core.source.*
import app.umuo.core.source.mediaserver.*
import app.umuo.core.source.webdav.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

 data class BrowserState(
    val connections: List<SourceConnection> = emptyList(), val drafts: List<SourceConnection> = emptyList(), val connection: SourceConnection? = null,
    val items: List<MediaItem> = emptyList(), val recent: List<MediaItem> = emptyList(), val selected: MediaItem? = null,
    val busy: Boolean = false, val refreshing: Boolean = false, val error: String? = null, val failedItem: MediaItem? = null, val failureKind: PlaybackFailureKind? = null, val warning: String? = null,
    val parent: String? = null, val nextOffset: Int? = null, val wall: Boolean = false,
    val serverType: MediaKind? = null,
    val serverView: ServerView = ServerView.LIBRARIES, val serverSearch: String = "", val userActionBusy: Boolean = false,
    val catalogItems: List<MediaItem> = emptyList(), val catalogFilter: CatalogFilter = CatalogFilter(),
    val catalogSort: CatalogSort = CatalogSort.TITLE, val localSearch: String = "",
    val directoryFiles: List<MediaItem> = emptyList(),
    val series: String? = null, val tmdbEnabled: Boolean = false,
    val metadataCandidates: List<MediaItem> = emptyList(), val playing: MediaItem? = null,
    val seriesEpisodes: List<MediaItem> = emptyList(),
    val nextEpisode: MediaItem? = null
) {
    val breadcrumbs: List<PathBreadcrumb>
        get() = if (connection?.kind == SourceKind.WEBDAV && !wall) {
            LibraryCatalog.breadcrumbs(connection.baseUrl, parent)
        } else emptyList()
    val currentFolderTitle: String? get() = if (parent != null) breadcrumbs.lastOrNull()?.label else null
    val showHomeSections: Boolean
        get() = (wall || parent==null) && series==null && selected==null && (!wall || !catalogFilter.active) &&
            serverView==ServerView.LIBRARIES && serverSearch.isBlank() && localSearch.isBlank()
    val inServerSubview: Boolean get() = connection?.kind?.let {it!=SourceKind.WEBDAV}==true && (serverView!=ServerView.LIBRARIES || serverSearch.isNotBlank())
    val seriesTitle: String? get() = series?.let {key->catalogItems.firstOrNull {it.seriesKey==key}?.title ?: key}
    val visibleRecent: List<MediaItem> get() = if(wall) recent.filter {it.hasMetadata} else recent
}
class PlayerModel(application: Application) : AndroidViewModel(application) {
    var videoBounds: android.graphics.Rect? = null
    var captureFrame: (suspend () -> Unit)? = null
    private val uiPreferences = application.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    val secrets = SecureCredentials(application)
    val playbackPreferences = PlaybackPreferences(application)
    val scanPreferences = ScanPreferences(application)
    val thumbnails = VideoThumbnails(application, { id -> state.value.connections.firstOrNull { it.id == id } }, secrets)
    private val driver = AndroidSqliteDriver(UmuoDatabase.Schema, application, "umuo.db")
    val library = LibraryRepository(UmuoDatabase(driver))
    private val baseClient = HttpClient(OkHttp) { followRedirects = false }
    private fun createProvider(c: SourceConnection, credentials: CredentialStore): MediaSourceProvider = when(c.kind) {
        SourceKind.EMBY -> EmbyProvider(c,baseClient,credentials)
        SourceKind.JELLYFIN -> JellyfinProvider(c,baseClient,credentials)
        SourceKind.WEBDAV -> WebDavProvider(c,baseClient,credentials)
    }
    private val registry = SourceRegistry(SourceKind.entries.associateWith { { c:SourceConnection -> createProvider(c,secrets) } })
    private fun displayedConnections(): List<SourceConnection> = (library.connections()+library.drafts()).distinctBy {it.id}
    fun editableConnection(id: String): SourceConnection? = library.drafts().firstOrNull {it.id==id} ?: library.connections().firstOrNull {it.id==id}
    fun hasActiveConnection(id: String): Boolean = library.connections().any {it.id==id}
    private fun refreshConnections() { mutable.update {it.copy(connections=displayedConnections(),drafts=library.drafts())} }
    fun discardDraft(id: String) { library.discardDraft(id); secrets.remove("draft:$id:password");secrets.remove("draft:$id:token");refreshConnections();clearMessage() }
    private val mutable = MutableStateFlow(
        BrowserState(
            connections = displayedConnections(),
            drafts = library.drafts(),
            tmdbEnabled = secrets.read("tmdb:enabled") == "true",
            catalogSort = runCatching {
                CatalogSort.valueOf(uiPreferences.getString("catalogSort", null).orEmpty())
            }.getOrDefault(CatalogSort.TITLE)
        )
    )
    val state = mutable.asStateFlow()
    val scanner = LibraryScanner(library)
    val engine: Media3Engine = Media3Engine(application, viewModelScope, { id -> mutable.value.connections.firstOrNull { it.id == id } }, secrets) { posMs ->
        coordinator.refreshPlan(posMs)
    }
    private val coordinator: PlaybackCoordinator = PlaybackCoordinator(engine, library, viewModelScope, Dispatchers.IO) { message -> mutable.update { it.copy(warning = message) } }
    private var provider: MediaSourceProvider? = null
    private var browseJob: Job? = null
    private var scanJob: Job? = null
    private var playJob: Job? = null
    private var nextEpisodeJob: Job? = null
    private var autoAdvancedFromId: MediaId? = null
    private var userActionJob: Job? = null
    private var serverHomeJob: Job? = null
    private var preSearchServerView: ServerView? = null
    @Volatile private var cachedWallAll: List<MediaItem> = emptyList()
    private val history = mutableListOf<String?>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        val saved=uiPreferences.getString("source",null)
        (library.connections().firstOrNull { it.id==saved } ?: library.connections().firstOrNull())?.let(::selectConnection)
        viewModelScope.launch {
            engine.state.collect { playback ->
                val current = state.value.playing ?: return@collect
                if (playback.ended &&
                    playback.error == null &&
                    playbackPreferences.state.value.autoPlayNext &&
                    state.value.nextEpisode != null &&
                    autoAdvancedFromId != current.id &&
                    playJob?.isActive != true
                ) {
                    autoAdvancedFromId = current.id
                    playNextEpisode()
                }
            }
        }
    }
    private fun safeMessage(e: Exception): String = when(e) {
        is SourceFailure -> e.message ?: "服务器请求失败"
        is IllegalArgumentException -> "资料或服务地址无效，请检查连接设置"
        else -> "操作失败，请检查网络、账号权限和服务地址"
    }
    /** 先保存独立草稿与候选凭据；认证和首次浏览成功后才替换有效连接。 */
    fun connect(name: String, kind: SourceKind, url: String, username: String, password: String,
                id: String = UUID.randomUUID().toString(), onConnected: () -> Unit = {}) {
        if(state.value.busy || state.value.playing != null) return
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null, warning = null) }
            var candidate: MediaSourceProvider? = null
            try {
                val c = SourceConnection(id, name.ifBlank { kind.name }, kind, normalizedBase(url), username)
                val previous = library.connections().firstOrNull { it.id == id }
                val previousDraft = library.drafts().firstOrNull { it.id == id }
                val sameIdentity = previous?.let { it.kind==c.kind && it.baseUrl==c.baseUrl && it.username==c.username } == true
                val staged = PendingCredentials(secrets,id,sameIdentity,
                    previousDraft?.let { it.kind==c.kind && it.username==c.username } == true)
                if(password.isNotEmpty() || staged.read("$id:password")==null) staged.write("$id:password",password)
                library.saveDraft(c)
                refreshConnections()
                candidate = createProvider(c,staged)
                val authenticated = withContext(Dispatchers.IO) { candidate.authenticate(MediaCredentials(username,staged.read("$id:password").orEmpty())) }
                val firstPage = withContext(Dispatchers.IO) { candidate.browse(null) }
                scanJob?.cancelAndJoin()
                staged.commit { library.saveConnection(authenticated) }
                refreshConnections()
                activate(candidate, authenticated)
                applyPage(candidate,firstPage,null)
                candidate = null
                onConnected()
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutable.update { it.copy(error = "草稿已保存，原连接和观看记录未更改。" + safeMessage(e)) } }
            finally { candidate?.close(); mutable.update { it.copy(busy = false) } }
        }
    }
    private suspend fun activate(source: MediaSourceProvider, c: SourceConnection) {
        scanJob?.cancel();userActionJob?.cancel();serverHomeJob?.cancel();nextEpisodeJob?.cancel();provider?.close(); provider = source; history.clear()
        preSearchServerView = null
        cachedWallAll = emptyList()
        uiPreferences.edit().putString("source",c.id).apply()
        val recentList = withContext(Dispatchers.IO) { library.recent(c.id) }
        mutable.update {
            it.copy(
                connection = c, serverType = null, serverView = ServerView.LIBRARIES, serverSearch = "",
                localSearch = "", directoryFiles = emptyList(), userActionBusy = false, parent = null,
                selected = null, items = emptyList(), recent = recentList, wall = false,
                catalogFilter = CatalogFilter(), catalogItems = emptyList(), series = null,
                nextOffset = null, refreshing = false, error = null, warning = null, seriesEpisodes = emptyList(), nextEpisode = null
            )
        }
    }
    fun selectConnection(c: SourceConnection) {
        if(state.value.playing != null) return
        if(!hasActiveConnection(c.id)) { mutable.update {it.copy(error="此来源尚未验证，请编辑草稿后连接")};return }
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            try {
                activate(registry.create(c), c)
                if(c.kind==SourceKind.WEBDAV && uiPreferences.getBoolean("catalog:${c.id}",false)) loadWall(c) else loadPage(null)
            }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutable.update { it.copy(error = safeMessage(e)) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private suspend fun loadPage(parent: String?, offset: Int = 0) {
        val source = provider ?: return
        val query = ServerLibraryQuery(state.value.serverView, state.value.serverSearch)
        val page = withContext(Dispatchers.IO) {
            if(source is MediaServerProvider && parent==null) source.listing(query, offset) else source.browse(parent, offset)
        }
        applyPage(source,page,parent,offset)
    }
    private suspend fun applyPage(source: MediaSourceProvider, page: MediaPage, parent: String?, offset: Int = 0) {
        val (display, recentList) = withContext(Dispatchers.IO) {
            library.transactionWithResult {
                val pos = if (source is WebDavProvider) library.positions(source.connection.id) else emptyMap()
                val items = page.items.map { item ->
                    if(source is WebDavProvider) {
                        val cached=library.indexed(item.id)
                        if(cached==null && !item.isFolder) library.upsert(item,parent ?: source.connection.baseUrl)
                        val merged = library.applyOverride(cached ?: item)
                        merged.copy(resumeMs = pos[item.id.value] ?: merged.resumeMs)
                    } else {
                        if(!item.isFolder) library.upsert(item, parent.orEmpty())
                        item
                    }
                }
                items to library.recent(source.connection.id)
            }
        }
        mutable.update { current ->
            val combined = (if (offset == 0) emptyList() else current.directoryFiles) + display
            current.copy(
                items = if (offset == 0) display else current.items + display,
                directoryFiles = combined,
                recent = recentList,
                parent = parent,
                nextOffset = page.nextOffset,
                selected = null
            )
        }
        if(parent==null && offset==0)refreshServerHome()
    }
    private fun refreshServerHome() {
        val source=provider as? MediaServerProvider ?: return
        if(state.value.serverView!=ServerView.LIBRARIES || state.value.parent!=null || state.value.serverSearch.isNotBlank())return
        serverHomeJob?.cancel()
        serverHomeJob=viewModelScope.launch {
            val message="服务器续看暂不可用，显示本机记录"
            try {
                val recent=withContext(Dispatchers.IO) { source.listing(ServerLibraryQuery(ServerView.RESUME)).items }
                if(state.value.connection?.id==source.connection.id && state.value.serverView==ServerView.LIBRARIES && state.value.parent==null && state.value.serverSearch.isBlank()) {
                    withContext(Dispatchers.IO) {
                        library.transaction {
                            recent.asReversed().forEach {library.upsert(it,"");library.savePosition(it.id,it.resumeMs)}
                        }
                    }
                    mutable.update {it.copy(recent=recent,warning=if(it.warning==message)null else it.warning)}
                }
            } catch(e: CancellationException){throw e}
            catch(_:Exception){if(state.value.connection?.id==source.connection.id)mutable.update {it.copy(warning=message)}}
        }
    }
    fun browse(item: MediaItem? = null, more: Boolean = false) {
        if(state.value.busy) return
        if(item != null && !item.isFolder) {
            browseJob?.cancel()
            browseJob = viewModelScope.launch {
                val detailed = withContext(Dispatchers.IO) { library.detailed(item) }
                mutable.update { it.copy(selected = detailed, metadataCandidates = emptyList()) }
            }
            return
        }
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null, localSearch = if (!more) "" else it.localSearch) }
            val oldParent = state.value.parent
            try {
                loadPage(if(more) oldParent else item?.id?.value, if(more) state.value.nextOffset ?: 0 else 0)
                if(!more && item != null) history += oldParent
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutable.update { it.copy(error = safeMessage(e)) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun home() {
        if(state.value.playing!=null || provider==null)return
        if(state.value.wall) {
            mutable.update {it.copy(catalogFilter=CatalogFilter(), localSearch="")}
            showWall()
            return
        }
        if(provider is MediaServerProvider && (state.value.serverView!=ServerView.LIBRARIES || state.value.serverSearch.isNotBlank())) {
            showServerView(ServerView.LIBRARIES)
            return
        }
        val previous=browseJob
        previous?.cancel()
        browseJob=viewModelScope.launch {
            previous?.join()
            history.clear()
            mutable.update {it.copy(parent=null,series=null,selected=null,wall=false,catalogFilter=CatalogFilter(),catalogItems=emptyList(),localSearch="",directoryFiles=emptyList(),nextOffset=null,items=emptyList(),busy=true,error=null,failedItem=null,failureKind=null)}
            try {loadPage(null)}
            catch(e: CancellationException){throw e}
            catch(e: Exception){mutable.update {it.copy(error=safeMessage(e))}}
            finally {mutable.update {it.copy(busy=false)}}
        }
    }
    fun back() {
        if(state.value.playing != null) { stop(); return }
        if(state.value.selected != null) { if(state.value.wall) showWall(state.value.series) else mutable.update { it.copy(selected = null, metadataCandidates = emptyList()) }; return }
        if(state.value.localSearch.isNotBlank()) { clearLocalSearch(); return }
        if(state.value.series != null) { showWall(); return }
        if(state.value.wall) {showFiles();return}
        if(history.isNotEmpty() && !state.value.busy) {
            val parent = history.removeAt(history.lastIndex)
            browseJob = viewModelScope.launch {
                mutable.update { it.copy(busy = true, error = null) }
                try { loadPage(parent) } catch(e: CancellationException) { throw e }
                catch(e: Exception) { mutable.update { it.copy(error = safeMessage(e)) } }
                finally { mutable.update { it.copy(busy = false) } }
            }
        } else if(provider is MediaServerProvider && state.value.serverSearch.isNotBlank()) {
            clearServerSearch()
        } else if(provider is MediaServerProvider && state.value.serverView!=ServerView.LIBRARIES) {
            showServerView(ServerView.LIBRARIES)
        }
    }
    fun jumpToDirectory(targetParent: String?) {
        val c = state.value.connection ?: return
        if (c.kind != SourceKind.WEBDAV || state.value.playing != null) return
        if (targetParent == state.value.parent && state.value.localSearch.isBlank()) return
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null, localSearch = "") }
            try {
                if (targetParent == null) {
                    history.clear()
                } else {
                    val crumbs = LibraryCatalog.breadcrumbs(c.baseUrl, targetParent)
                    history.clear()
                    crumbs.dropLast(1).forEach { history += it.path }
                }
                loadPage(targetParent, 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = safeMessage(e)) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }
    fun refreshCurrent() {
        val snapshot = state.value
        val c = snapshot.connection ?: return
        val source = provider ?: return
        if (snapshot.playing != null || snapshot.busy || snapshot.refreshing) return
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            mutable.update { it.copy(refreshing = true, error = null) }
            try {
                if (source is WebDavProvider && snapshot.wall) {
                    loadWall(c, snapshot.series)
                } else {
                    loadPage(snapshot.parent, 0)
                    if (source is MediaServerProvider &&
                        snapshot.parent == null &&
                        snapshot.serverView == ServerView.LIBRARIES &&
                        snapshot.serverSearch.isBlank()
                    ) {
                        refreshServerHome()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = safeMessage(e)) }
            } finally {
                mutable.update { it.copy(refreshing = false, busy = false) }
            }
        }
    }
    fun showServerCatalog(type: MediaKind?) = showServerView(when(type) {MediaKind.MOVIE->ServerView.MOVIES;MediaKind.SERIES->ServerView.SERIES;else->ServerView.LIBRARIES})
    fun searchServer(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            if (state.value.serverSearch.isNotBlank()) clearServerSearch()
            return
        }
        val currentView = state.value.serverView
        if (state.value.serverSearch.isBlank()) {
            preSearchServerView = currentView
        }
        val targetView = when (currentView) {
            ServerView.MOVIES, ServerView.SERIES, ServerView.FAVORITES, ServerView.LATEST, ServerView.LIBRARIES -> currentView
            ServerView.RESUME, ServerView.NEXT_UP, ServerView.COLLECTIONS -> ServerView.LIBRARIES
        }
        showServerView(targetView, trimmed)
    }
    fun clearServerSearch() {
        val restoreView = preSearchServerView ?: state.value.serverView
        preSearchServerView = null
        showServerView(restoreView, "")
    }
    fun showServerView(view: ServerView, search: String = "") {
        if(provider !is MediaServerProvider || state.value.playing!=null)return
        if(search.isBlank()) preSearchServerView = null
        serverHomeJob?.cancel()
        val previous=browseJob
        previous?.cancel()
        browseJob=viewModelScope.launch {
            previous?.join();history.clear()
            mutable.update {it.copy(serverView=view,serverSearch=search.trim(),serverType=when(view){ServerView.MOVIES->MediaKind.MOVIE;ServerView.SERIES->MediaKind.SERIES;else->null},parent=null,selected=null,items=emptyList(),nextOffset=null,busy=true,error=null)}
            try {loadPage(null)}
            catch(e: CancellationException){throw e}
            catch(e: Exception){mutable.update {it.copy(error=safeMessage(e))}}
            finally {mutable.update {it.copy(busy=false)}}
        }
    }
    fun setFavorite(item: MediaItem) = updateUserState(item,true)
    fun setWatched(item: MediaItem) = updateUserState(item,false)
    private fun updateUserState(item: MediaItem, favorite: Boolean) {
        val source=provider as? MediaServerProvider ?: return
        if(item.id.sourceId!=source.connection.id || userActionJob?.isActive==true)return
        val value=if(favorite)item.favorite!=true else item.watched!=true
        serverHomeJob?.cancel()
        userActionJob=viewModelScope.launch {
            mutable.update {it.copy(userActionBusy=true,error=null)}
            try {
                val (updated, recentList) = withContext(Dispatchers.IO) {
                    if(favorite)source.setFavorite(item.id,value) else source.setWatched(item.id,value)
                    val nextItem = if(favorite)item.copy(favorite=value) else item.copy(watched=value,resumeMs=0)
                    library.transactionWithResult {
                        library.updateCached(nextItem)
                        if(!favorite)library.savePosition(item.id,0)
                        nextItem to library.recent(item.id.sourceId)
                    }
                }
                if(state.value.connection?.id!=item.id.sourceId)return@launch
                mutable.update {current->current.copy(
                    selected=if(current.selected?.id==item.id)updated else current.selected,
                    items=current.items.mapNotNull {existing->
                        if(existing.id!=item.id)existing
                        else if(current.serverView==ServerView.FAVORITES && favorite && !value)null
                        else if(current.serverView in listOf(ServerView.RESUME,ServerView.NEXT_UP) && !favorite && value)null
                        else updated
                    },recent=recentList)}
            } catch(e: CancellationException){throw e}
            catch(e: Exception){mutable.update {it.copy(error=safeMessage(e))}}
            finally {if(state.value.connection?.id==item.id.sourceId)mutable.update {it.copy(userActionBusy=false)}}
        }
    }
    private fun computeWallItems(
        catalog: List<MediaItem>,
        all: List<MediaItem>,
        recent: List<MediaItem>,
        filter: CatalogFilter,
        series: String?,
        sort: CatalogSort,
        search: String
    ): List<MediaItem> {
        val base = if (series != null) {
            LibraryCatalog.episodes(all, series)
        } else {
            LibraryCatalog.sort(LibraryCatalog.filter(catalog, filter), sort, recent.map { it.id }, all)
        }
        return LibraryCatalog.searchLocal(base, search)
    }
    private suspend fun loadWall(c: SourceConnection, series: String? = null) {
        uiPreferences.edit().putBoolean("catalog:${c.id}",true).apply()
        val filter = if(state.value.wall) state.value.catalogFilter else CatalogFilter()
        val sort = state.value.catalogSort
        val search = if (state.value.wall && state.value.series == series) state.value.localSearch else ""
        val result = withContext(Dispatchers.IO) {
            val all = library.items(c.id)
            val recentItems = library.recent(c.id)
            withContext(Dispatchers.Default) {
                val cat = LibraryCatalog.entries(all)
                val filtered = computeWallItems(cat, all, recentItems, filter, series, sort, search)
                Triple(all, cat, filtered) to recentItems
            }
        }
        if(state.value.connection?.id != c.id) return
        val (triple, recentList) = result
        cachedWallAll = triple.first
        mutable.update {
            it.copy(
                wall = true, parent = null, catalogItems = triple.second, catalogFilter = filter,
                localSearch = search, items = triple.third, recent = recentList, nextOffset = null,
                selected = null, series = series, busy = false, error = null
            )
        }
    }
    fun showWall(series: String? = null) {
        val c = state.value.connection ?: return
        if(c.kind != SourceKind.WEBDAV) return
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            loadWall(c, series)
        }
    }
    fun filterCatalog(filter: CatalogFilter) {
        if(!state.value.wall || state.value.series!=null)return
        mutable.update {
            it.copy(
                catalogFilter = filter,
                items = computeWallItems(it.catalogItems, cachedWallAll, it.recent, filter, null, it.catalogSort, it.localSearch)
            )
        }
    }
    fun setCatalogSort(sort: CatalogSort) {
        uiPreferences.edit().putString("catalogSort", sort.name).apply()
        mutable.update {
            if (!it.wall || it.series != null) {
                it.copy(catalogSort = sort)
            } else {
                it.copy(
                    catalogSort = sort,
                    items = computeWallItems(it.catalogItems, cachedWallAll, it.recent, it.catalogFilter, null, sort, it.localSearch)
                )
            }
        }
    }
    fun setLocalSearch(query: String) {
        val current = state.value
        val c = current.connection ?: return
        if (c.kind != SourceKind.WEBDAV) return
        if (current.wall) {
            mutable.update {
                it.copy(
                    localSearch = query,
                    items = computeWallItems(it.catalogItems, cachedWallAll, it.recent, it.catalogFilter, it.series, it.catalogSort, query)
                )
            }
            return
        }
        if (query.isBlank()) {
            mutable.update { it.copy(localSearch = "", items = it.directoryFiles) }
            return
        }
        val currentFiles = current.directoryFiles
        mutable.update { it.copy(localSearch = query) }
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                val indexed = library.items(c.id).filter { !it.isFolder }
                val pool = (currentFiles + indexed).distinctBy { it.id }
                withContext(Dispatchers.Default) {
                    LibraryCatalog.searchLocal(pool, query)
                }
            }
            if (state.value.connection?.id == c.id && !state.value.wall && state.value.localSearch == query) {
                mutable.update { it.copy(items = results) }
            }
        }
    }
    fun clearLocalSearch() = setLocalSearch("")
    fun showFiles() {
        if(state.value.busy)return
        state.value.connection?.id?.let {uiPreferences.edit().putBoolean("catalog:$it",false).apply()}
        cachedWallAll = emptyList()
        mutable.update {it.copy(wall=false,series=null,catalogFilter=CatalogFilter(),catalogItems=emptyList(),localSearch="")}
        home()
    }

    fun openItem(item: MediaItem) {
        if(state.value.wall && item.kind == MediaKind.SERIES) showWall(item.seriesKey ?: item.series ?: item.title) else browse(item)
    }
    fun scan() {
        val dav = provider as? WebDavProvider ?: return
        if(scanJob?.isActive == true) { scanJob?.cancel(); return }
        val id=dav.connection.id
        val until=scanPreferences.blockedUntil(id)
        if(until>System.currentTimeMillis()) {
            mutable.update {it.copy(warning="服务器要求等待，请在 ${(until-System.currentTimeMillis())/1000+1} 秒后恢复扫描")}
            return
        }
        val traffic=ScanTrafficGuard(scanPreferences.speed.value.intervalMs,
            lastRequestMs=scanPreferences.lastRequest(id),blockedUntilMs=until,
            onActivity={scanPreferences.recordRequest(id,it)},onBlocked={scanPreferences.block(id,it)})
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            val metadataClient=baseClient.config {install(ScanTrafficPlugin) {guard=traffic}}
            try {
                val metadata = if(state.value.tmdbEnabled) TmdbProvider(metadataClient, secrets.read("tmdb:token").orEmpty()) else null
                scanner.scan(dav, metadata,traffic)
                if(state.value.connection?.id==id) {
                    if(state.value.wall) loadWall(dav.connection, state.value.series)
                    scanner.state.value.pausedReason?.let {message->mutable.update {it.copy(warning=message)}}
                }
            }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutable.update { it.copy(error = safeMessage(e)) } }
            finally {metadataClient.close()}
        }
    }
    private var lastCompatible=false
    fun retryPlayback(compatible: Boolean = lastCompatible) {
        val item=state.value.playing ?: state.value.failedItem ?: return
        val resume=if(state.value.playing!=null) {
            engine.state.value.positionMs.takeIf { it > 0 || engine.state.value.ready }
        } else null
        engine.preserveStateFor(item.id)
        play(item,compatible,resume)
    }
    fun playNextEpisode() {
        val next = state.value.nextEpisode ?: return
        if (playJob?.isActive == true) return
        play(next, compatible = lastCompatible, resumeOverride = 0L)
    }
    fun clearProgress(item: MediaItem) {
        val source = provider ?: return
        viewModelScope.launch {
            val recentList = withContext(Dispatchers.IO) {
                if (source is MediaServerProvider && item.id.sourceId == source.connection.id) {
                    runCatching { source.setWatched(item.id, false) }
                }
                library.savePosition(item.id, 0L)
                library.recent(item.id.sourceId)
            }
            if (state.value.connection?.id != item.id.sourceId) return@launch
            fun clearItem(entry: MediaItem): MediaItem =
                if (entry.id == item.id) entry.copy(resumeMs = 0L, watched = if (source is MediaServerProvider) false else entry.watched)
                else entry
            cachedWallAll = cachedWallAll.map(::clearItem)
            mutable.update { current ->
                val updatedCatalog = if (current.wall && cachedWallAll.isNotEmpty()) {
                    LibraryCatalog.entries(cachedWallAll)
                } else {
                    current.catalogItems.map(::clearItem)
                }
                val updatedItems = if (current.wall && cachedWallAll.isNotEmpty()) {
                    computeWallItems(updatedCatalog, cachedWallAll, recentList, current.catalogFilter, current.series, current.catalogSort, current.localSearch)
                } else {
                    current.items.map(::clearItem)
                }
                current.copy(
                    recent = recentList,
                    selected = current.selected?.let(::clearItem),
                    directoryFiles = current.directoryFiles.map(::clearItem),
                    catalogItems = updatedCatalog,
                    items = updatedItems,
                    seriesEpisodes = current.seriesEpisodes.map(::clearItem),
                    warning = "已清除续播记录"
                )
            }
        }
    }
    fun removeConnection(id: String, onRemoved: () -> Unit = {}) {
        val wasPlaying = state.value.playing != null
        val activeRemoved = state.value.connection?.id == id
        scanJob?.cancel()
        browseJob?.cancel()
        userActionJob?.cancel()
        serverHomeJob?.cancel()
        nextEpisodeJob?.cancel()
        playJob?.cancel()
        if (activeRemoved) {
            provider?.close()
            provider = null
            history.clear()
            preSearchServerView = null
            cachedWallAll = emptyList()
        }
        viewModelScope.launch {
            if (wasPlaying) {
                coordinator.stop()
                engine.release()
            }
            withContext(Dispatchers.IO) {
                library.removeConnection(id)
            }
            listOf("$id:password", "$id:token", "draft:$id:password", "draft:$id:token").forEach { secrets.remove(it) }
            scanPreferences.clearSource(id)
            thumbnails.removeSource(id)
            uiPreferences.edit().apply {
                remove("catalog:$id")
                if (uiPreferences.getString("source", null) == id) remove("source")
            }.apply()
            val (nextConnections, nextDrafts, fallback) = withContext(Dispatchers.IO) {
                Triple(displayedConnections(), library.drafts(), library.connections().firstOrNull())
            }
            if (activeRemoved) {
                mutable.value = BrowserState(
                    connections = nextConnections,
                    drafts = nextDrafts,
                    tmdbEnabled = state.value.tmdbEnabled,
                    catalogSort = state.value.catalogSort,
                    warning = "已删除媒体来源"
                )
                if (fallback != null) selectConnection(fallback)
            } else {
                mutable.update {
                    it.copy(
                        connections = nextConnections,
                        drafts = nextDrafts,
                        warning = "已删除媒体来源"
                    )
                }
            }
            onRemoved()
        }
    }
    private suspend fun resolveSeriesContext(
        source: MediaSourceProvider,
        current: MediaItem,
        snapshot: BrowserState
    ): Pair<List<MediaItem>, MediaItem?> {
        val key = current.seriesKey?.takeIf { it.isNotBlank() } ?: current.series?.takeIf { it.isNotBlank() }
        val episodes: List<MediaItem> = when (source) {
            is WebDavProvider -> {
                val allIndexed = library.items(source.connection.id)
                val fromCatalog = if (key != null) {
                    allIndexed.filter {
                        it.id.sourceId == current.id.sourceId &&
                            !it.isFolder &&
                            (it.kind == MediaKind.EPISODE || it.season != null || it.episode != null || it.id == current.id) &&
                            ((it.seriesKey?.takeIf { k -> k.isNotBlank() } ?: it.series) == key)
                    }
                } else emptyList()
                val rawList = when {
                    fromCatalog.size > 1 -> fromCatalog
                    snapshot.series != null && snapshot.items.isNotEmpty() -> snapshot.items
                    current.kind == MediaKind.EPISODE || current.season != null || current.episode != null -> {
                        val directory = current.path.substringBeforeLast('/') + "/"
                        val pos = library.positions(source.connection.id)
                        val entries = runCatching { source.listEntries(directory) }.getOrDefault(emptyList())
                        entries.filter { !it.directory && it.extension in videoExtensions }
                            .map { entry ->
                                val parsed = FilenameParser.parse(source.item(entry))
                                parsed.copy(resumeMs = pos[parsed.id.value] ?: parsed.resumeMs)
                            }
                    }
                    else -> emptyList()
                }
                (if (rawList.any { it.id == current.id }) rawList else rawList + current)
                    .distinctBy { it.id }
                    .sortedWith(
                        compareBy<MediaItem> { it.season ?: Int.MAX_VALUE }
                            .thenBy { it.episode ?: Int.MAX_VALUE }
                            .thenBy { it.title }
                            .thenBy { it.path }
                    )
            }
            is MediaServerProvider -> runCatching { source.seriesEpisodes(current) }.getOrDefault(emptyList())
            else -> emptyList()
        }
        val next = LibraryCatalog.findNextEpisode(current, episodes.ifEmpty { snapshot.items })
        return episodes to next
    }
    fun play(item: MediaItem, compatible: Boolean = false, resumeOverride: Long? = null) {
        if(playJob?.isActive == true) return
        val source = provider ?: return
        if(state.value.playing?.id == item.id || state.value.failedItem?.id == item.id) {
            engine.preserveStateFor(item.id)
        }
        lastCompatible=compatible
        playJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null, warning = null,failedItem=null,failureKind=null) }
            try {
                val fresh = withContext(Dispatchers.IO) {
                    val fetched = if (source is WebDavProvider) {
                        item.copy(resumeMs = library.position(item.id))
                    } else {
                        val serverItem = source.details(item.id)
                        if (serverItem.resumeMs > 0) serverItem else serverItem.copy(resumeMs = library.position(item.id))
                    }
                    if (resumeOverride != null) fetched.copy(resumeMs = resumeOverride) else fetched
                }
                coordinator.open(source, PlaybackRequest(fresh, DeviceCapabilities.profile(), compatible))
                autoAdvancedFromId = null
                val keepSeriesEpisodes = state.value.seriesEpisodes.takeIf { list -> list.any { it.id == fresh.id } }.orEmpty()
                mutable.update { it.copy(playing = fresh, selected = null, seriesEpisodes = keepSeriesEpisodes, nextEpisode = null) }
                nextEpisodeJob?.cancel()
                val snapshot = state.value
                nextEpisodeJob = viewModelScope.launch {
                    val (episodes, next) = withContext(Dispatchers.IO) { resolveSeriesContext(source, fresh, snapshot) }
                    if (state.value.playing?.id == fresh.id) {
                        mutable.update {
                            it.copy(
                                seriesEpisodes = if (episodes.size > 1) episodes else emptyList(),
                                nextEpisode = next
                            )
                        }
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutable.update { it.copy(error=playbackFailureMessage(e),failureKind=playbackFailureKind(e),failedItem=item) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun stop() {
        playJob?.cancel()
        nextEpisodeJob?.cancel()
        autoAdvancedFromId = null
        playJob = viewModelScope.launch {
            captureFrame?.invoke()
            coordinator.stop(); engine.release()
            val cid = state.value.connection?.id
            val (recentList, positions) = withContext(Dispatchers.IO) {
                (cid?.let(library::recent).orEmpty()) to (cid?.let(library::positions).orEmpty())
            }
            fun applyPos(list: List<MediaItem>) = list.map { entry ->
                val p = positions[entry.id.value]
                if (p != null) entry.copy(resumeMs = p) else entry
            }
            cachedWallAll = applyPos(cachedWallAll)
            mutable.update { current ->
                val updatedCatalog = if (current.wall && cachedWallAll.isNotEmpty()) {
                    LibraryCatalog.entries(cachedWallAll)
                } else {
                    applyPos(current.catalogItems)
                }
                val updatedItems = if (current.wall && cachedWallAll.isNotEmpty()) {
                    computeWallItems(updatedCatalog, cachedWallAll, recentList, current.catalogFilter, current.series, current.catalogSort, current.localSearch)
                } else {
                    applyPos(current.items)
                }
                current.copy(
                    playing = null,
                    busy = false,
                    recent = recentList,
                    directoryFiles = applyPos(current.directoryFiles),
                    catalogItems = updatedCatalog,
                    items = updatedItems,
                    seriesEpisodes = emptyList(),
                    nextEpisode = null
                )
            }
            refreshServerHome()
        }
    }
    fun configureTmdb(enabled: Boolean, token: String): Boolean {
        if(token.isNotBlank()) secrets.write("tmdb:token", token.trim())
        if(enabled && secrets.read("tmdb:token").isNullOrBlank()) { mutable.update { it.copy(error = "请先填写 TMDB Read Access Token") }; return false }
        secrets.write("tmdb:enabled", enabled.toString())
        mutable.update { it.copy(tmdbEnabled = enabled, metadataCandidates = emptyList(), error = null, warning = "元数据设置已保存") }
        return true
    }
    fun searchMetadata(title: String) {
        val item = state.value.selected ?: return
        if(!state.value.tmdbEnabled) { mutable.update { it.copy(error = "请先在设置中主动启用 TMDB") }; return }
        browseJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try {
                val results = withContext(Dispatchers.IO) {
                    val tmdb = TmdbProvider(baseClient, secrets.read("tmdb:token").orEmpty())
                    tmdb.search(title, item.year, item.kind == MediaKind.EPISODE)
                }
                mutable.update { it.copy(metadataCandidates = results, warning = if(results.isEmpty()) "未找到匹配资料" else null) }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutable.update { it.copy(error = safeMessage(e)) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun match(metadata: MediaItem?) {
        val item = state.value.selected ?: return
        browseJob = viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                library.match(item, metadata)
                library.indexed(item.id)?.let(library::applyOverride) ?: library.applyOverride(item)
            }
            mutable.update { it.copy(selected = updated, metadataCandidates = emptyList(), warning = "本地匹配已更新") }
        }
    }
    fun clearMessage() { mutable.update { it.copy(error = null, warning = null,failedItem=null,failureKind=null) } }
    fun background(inPip: Boolean) { if(!inPip) { viewModelScope.launch { captureFrame?.invoke() }; engine.pause() } }
    override fun onCleared() {
        scanJob?.cancel(); browseJob?.cancel(); playJob?.cancel(); nextEpisodeJob?.cancel(); userActionJob?.cancel();serverHomeJob?.cancel()
        cleanupScope.launch {
            coordinator.stop(); engine.release(); thumbnails.close(); provider?.close(); baseClient.close(); driver.close(); cleanupScope.cancel()
        }
    }
}
