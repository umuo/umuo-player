package app.umuo.tvui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import app.umuo.android.BrowserState
import app.umuo.core.library.*

@Composable
internal fun TvCatalogFilters(state: BrowserState, onFilter: (CatalogFilter) -> Unit) {
    val filter=state.catalogFilter
    var expanded by rememberSaveable(state.connection?.id) {mutableStateOf(false)}
    val facets=remember(state.catalogItems,filter) {LibraryCatalog.facets(state.catalogItems,filter)}
    Column {
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Button(onClick={expanded=!expanded}){Text(if(expanded)"收起筛选" else "展开筛选",fontSize=12.sp)}
            if(filter.active)Button(onClick={onFilter(CatalogFilter())}) {Text("清除筛选",fontSize=12.sp)}
            Text(if(filter.active)"已筛选 · ${state.items.size} 部" else "全部已刮削影视",fontSize=12.sp)
        }
        AnimatedVisibility(expanded) {
            LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(5.dp)) {
                items(CatalogType.entries) {type->
                    val label=when(type) {CatalogType.ALL->"全部影视";CatalogType.MOVIES->"电影";CatalogType.SERIES->"剧集"}
                    FilterButton(label,filter.type==type) {onFilter(filter.copy(type=type))}
                }
                items(facets.genres,key={"genre:${it.value}"}) {option->FilterButton("类型 · ${option.value} (${option.count})",filter.genre==option.value,option.count>0 || filter.genre==option.value){onFilter(filter.copy(genre=if(filter.genre==option.value)null else option.value))}}
                items(facets.tags,key={"tag:${it.value}"}) {option->FilterButton("标签 · ${option.value} (${option.count})",filter.tag==option.value,option.count>0 || filter.tag==option.value){onFilter(filter.copy(tag=if(filter.tag==option.value)null else option.value))}}
                items(facets.collections,key={"set:${it.value}"}) {option->FilterButton("合集 · ${option.value} (${option.count})",filter.collection==option.value,option.count>0 || filter.collection==option.value){onFilter(filter.copy(collection=if(filter.collection==option.value)null else option.value))}}
            }
        }
    }
}

@Composable
private fun FilterButton(label:String,selected:Boolean,enabled:Boolean=true,onClick:()->Unit) {
    Button(enabled=enabled,onClick=onClick){Text((if(selected)"✓ " else "")+label,fontSize=11.sp)}
}
