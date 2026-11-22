package app.umuo.mobileui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umuo.android.*
import app.umuo.core.library.*
import app.umuo.core.model.*

@Composable
internal fun CatalogControls(
    state: BrowserState,
    onFilter: (CatalogFilter) -> Unit,
    onSort: (CatalogSort) -> Unit = {},
    embedded: Boolean = false
) {
    val filter=state.catalogFilter
    var expanded by rememberSaveable(state.connection?.id) {mutableStateOf(false)}
    val facets=remember(state.catalogItems,filter) {LibraryCatalog.facets(state.catalogItems,filter)}
    val summary=listOfNotNull(if(filter.type==CatalogType.MOVIES)"电影" else if(filter.type==CatalogType.SERIES)"剧集" else null,
        filter.genre?.let {"类型：$it"},filter.tag?.let {"标签：$it"},filter.collection?.let {"合集：$it"},
        state.catalogSort.takeIf { it != CatalogSort.TITLE }?.label).joinToString(" · ")
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            if(!embedded)TextButton(onClick={expanded=!expanded}) {Text(if(expanded)"收起筛选" else "展开筛选")}
            Text(if(summary.isNotEmpty())summary else "全部已刮削影视",modifier=Modifier.weight(1f),color=MobileStyle.Muted,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(filter.active)TextButton(onClick={onFilter(CatalogFilter())}) {Text("清除")}
        }
        AnimatedVisibility(embedded || expanded) {
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(CatalogType.entries) {type->
                        FilterChip(selected=filter.type==type,onClick={onFilter(filter.copy(type=type))},label={Text(when(type) {CatalogType.ALL->"全部影视";CatalogType.MOVIES->"电影";CatalogType.SERIES->"剧集"})})
                    }
                }
                Text("排序", color = MobileStyle.Muted, fontSize = 11.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CatalogSort.entries) { sortOption ->
                        FilterChip(
                            selected = state.catalogSort == sortOption,
                            onClick = { onSort(sortOption) },
                            label = { Text(sortOption.label) }
                        )
                    }
                }
                CatalogFacet("类型",facets.genres,filter.genre) {onFilter(filter.copy(genre=it))}
                CatalogFacet("标签",facets.tags,filter.tag) {onFilter(filter.copy(tag=it))}
                CatalogFacet("合集",facets.collections,filter.collection) {onFilter(filter.copy(collection=it))}
                Text("${state.items.size} 部符合条件 · 同一维度点选切换，不同维度组合筛选",color=MobileStyle.Muted,fontSize=11.sp)
            }
        }
    }
}

@Composable
private fun CatalogFacet(label: String, values: List<CatalogOption>, selected: String?, onSelect: (String?) -> Unit) {
    if(values.isEmpty())return
    Column {
        Text(label,color=MobileStyle.Muted,fontSize=11.sp)
        LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(values,key={it.value}) {option->
                FilterChip(selected=selected==option.value,enabled=option.count>0 || selected==option.value,
                    onClick={onSelect(if(selected==option.value)null else option.value)},label={Text("${option.value} (${option.count})")})
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MetadataLabels(item: MediaItem) {
    val labels=item.genres+item.tags+LibraryCatalog.collections(item)
    if(labels.isEmpty())return
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        labels.distinct().forEach { label ->
            Surface(color=MobileStyle.Surface,shape=androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                Text(label,color=MobileStyle.Accent,fontSize=12.sp,modifier=Modifier.padding(horizontal=10.dp,vertical=6.dp))
            }
        }
    }
}
