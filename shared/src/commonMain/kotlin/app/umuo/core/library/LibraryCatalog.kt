package app.umuo.core.library

import app.umuo.core.model.*

enum class CatalogType { ALL, MOVIES, SERIES }
data class CatalogFilter(
    val type: CatalogType = CatalogType.ALL,
    val genre: String? = null,
    val tag: String? = null,
    val collection: String? = null
) {
    val active: Boolean get() = this != CatalogFilter()
}

/** 影视库只读取已确认的元数据；文件名识别与文件夹仍由文件浏览负责。 */
object LibraryCatalog {
    private fun seriesKey(item: MediaItem) = item.seriesKey ?: item.series ?: item.title

    fun entries(all: List<MediaItem>): List<MediaItem> {
        val known = all.filter { it.hasMetadata }
        val movies = known.filter { it.kind == MediaKind.MOVIE }
        val series = known.filter { it.kind == MediaKind.EPISODE }
            .groupBy { it.id.sourceId to seriesKey(it) }.map { (identity, episodes) ->
                val show = known.firstOrNull {
                    it.kind == MediaKind.SERIES && it.id.sourceId == identity.first && seriesKey(it) == identity.second
                }
                val first = show ?: episodes.first()
                first.copy(
                    id = show?.id ?: MediaId(identity.first, "series:${identity.second}"),
                    title = show?.title ?: first.series ?: first.title,
                    kind = MediaKind.SERIES, seriesKey = identity.second,
                    genres = (show?.genres.orEmpty() + episodes.flatMap {it.genres}).distinct(),
                    tags = (show?.tags.orEmpty() + episodes.flatMap {it.tags}).distinct(),
                    collections = ((show?.let(::collections)).orEmpty()+episodes.flatMap(::collections)).distinct()
                )
            }
        return (movies + series).sortedBy { it.title.lowercase() }
    }

    fun episodes(all: List<MediaItem>, key: String): List<MediaItem> = all.filter {
        it.hasMetadata && it.kind == MediaKind.EPISODE && seriesKey(it) == key
    }.sortedWith(compareBy<MediaItem> { it.season ?: 0 }.thenBy { it.episode ?: 0 }.thenBy {it.title})

    /** 从候选列表中查找下一集：优先按同剧集匹配，其次按同目录剧集/分集命名序匹配。 */
    fun findNextEpisode(current: MediaItem, candidates: List<MediaItem>): MediaItem? {
        if (current.isFolder) return null
        val sameSource = candidates.filter { it.id.sourceId == current.id.sourceId && !it.isFolder }
        val series = current.seriesKey?.takeIf { it.isNotBlank() } ?: current.series?.takeIf { it.isNotBlank() }
        val pool = when {
            series != null -> sameSource.filter {
                (it.seriesKey?.takeIf { k -> k.isNotBlank() } ?: it.series) == series &&
                    (it.kind == MediaKind.EPISODE || it.season != null || it.episode != null || it.id == current.id)
            }
            current.kind == MediaKind.EPISODE || current.season != null || current.episode != null -> {
                val parent = current.path.substringBeforeLast('/', "")
                sameSource.filter {
                    it.path.substringBeforeLast('/', "") == parent &&
                        (it.kind == MediaKind.EPISODE || it.season != null || it.episode != null || it.id == current.id)
                }
            }
            else -> return null
        }
        val ordered = (if (pool.any { it.id == current.id }) pool else pool + current)
            .distinctBy { it.id }
            .sortedWith(
                compareBy<MediaItem> { it.season ?: Int.MAX_VALUE }
                    .thenBy { it.episode ?: Int.MAX_VALUE }
                    .thenBy { it.title }
                    .thenBy { it.path }
            )
        val index = ordered.indexOfFirst { it.id == current.id }
        return if (index >= 0) ordered.getOrNull(index + 1) else null
    }

    fun collections(item: MediaItem): List<String> = (item.collections+listOfNotNull(item.collection)).map(String::trim).filter {it.isNotEmpty()}.distinct()
    private fun key(value: String) = value.trim().replace(Regex("\\s+")," ").lowercase()
    private fun contains(values: List<String>, value: String) = values.any {key(it)==key(value)}

    fun filter(items: List<MediaItem>, filter: CatalogFilter): List<MediaItem> = items.filter {
        (filter.type == CatalogType.ALL || it.kind == if(filter.type == CatalogType.MOVIES) MediaKind.MOVIE else MediaKind.SERIES) &&
            (filter.genre == null || contains(it.genres,filter.genre)) &&
            (filter.tag == null || contains(it.tags,filter.tag)) &&
            (filter.collection == null || contains(collections(it),filter.collection))
    }

    fun facets(items: List<MediaItem>, selected: CatalogFilter): CatalogFacets {
        fun members(values: (MediaItem)->List<String>) = buildMap<String,Set<MediaId>> {
            items.forEach {item->values(item).filter {it.isNotBlank()}.forEach {value->
                val normalized=key(value);put(normalized,get(normalized).orEmpty()+item.id)
            }}
        }
        val genreMembers=members {it.genres}
        val tagMembers=members {it.tags}
        val collectionMembers=members(::collections)
        fun options(values: (MediaItem)->List<String>, other: CatalogFilter): List<CatalogOption> {
            val matched=filter(items,other)
            return items.flatMap(values).map(String::trim).filter {it.isNotBlank()}.distinctBy(::key).sorted().map {value->
                CatalogOption(value,matched.count {contains(values(it),value)})
            }
        }
        return CatalogFacets(
            genres=options({it.genres},selected.copy(genre=null)),
            tags=options({it.tags},selected.copy(tag=null)).filter {option->tagMembers[key(option.value)]!=genreMembers[key(option.value)]},
            collections=options(::collections,selected.copy(collection=null)).filter {option->
                // 整个影视库都属于同一合集时，这个条件不会缩小结果，不占用筛选空间。
                collectionMembers[key(option.value)]?.size!=items.size || selected.collection?.let {key(it)==key(option.value)}==true
            }
        )
    }
}

data class CatalogOption(val value: String,val count: Int)
data class CatalogFacets(val genres: List<CatalogOption>,val tags: List<CatalogOption>,val collections: List<CatalogOption>)
