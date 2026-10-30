package com.onmyway.app.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onmyway.app.R
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.state.networkMessage
import kotlinx.coroutines.launch

/** 搜索与常用地点共用的无卡片列表行。 */
@Composable
private fun PlaceListRow(name: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_place_pin),
            null,
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) Text(
                detail,
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun PlacePicker(vm: AppViewModel, dismiss: () -> Unit, initialKeyword: String = "", choose: (Place) -> Unit) {
    var keyword by rememberSaveable(initialKeyword) { mutableStateOf(initialKeyword) }
    var city by rememberSaveable { mutableStateOf(vm.city) }
    var items by remember { mutableStateOf(emptyList<Place>()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun doSearch() {
        if (loading || keyword.isBlank() || city.isBlank()) return
        loading = true; items = emptyList(); message = ""
        scope.launch {
            try {
                items = vm.search(keyword.trim(), city.trim())
                if (items.isEmpty()) message = "没有找到地点，请换个关键词。"
            } catch (e: Exception) {
                message = networkMessage(e)
            } finally {
                loading = false
            }
        }
    }
    AppBottomSheet(onDismissRequest = dismiss) { close ->
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.86f).imePadding()) {
            BottomSheetTitle("选择地点", close, subtitle = "搜索地图，或从常用地点直接选择")
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        city, { city = it; items = emptyList() }, enabled = !loading,
                        label = { Text("城市") }, singleLine = true, modifier = Modifier.weight(0.36f),
                        shape = RoundedCornerShape(14.dp),
                        colors = appTextFieldColors(),
                    )
                    OutlinedTextField(
                        keyword, { keyword = it; items = emptyList() }, enabled = !loading,
                        label = { Text("地点名称 / 地址") }, singleLine = true, modifier = Modifier.weight(0.64f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                        shape = RoundedCornerShape(14.dp),
                        colors = appTextFieldColors(),
                    )
                }
                AppPrimaryButton(
                    if (loading) "搜索中…" else "搜索高德地点",
                    onClick = { doSearch() },
                    enabled = !loading && keyword.isNotBlank() && city.isNotBlank(),
                    icon = R.drawable.ic_search,
                )
                if (loading) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
                if (message.isNotBlank() && items.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            message,
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                        Text(
                            "正在搜索附近地点",
                            Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 没有搜索结果时，常用地点直接以列表呈现，整行点击即可选用。
                    items.isEmpty() && message.isBlank() && vm.familiarPlaces.isNotEmpty() -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
                    ) {
                        item {
                            Text(
                                "常用地点",
                                Modifier.padding(bottom = 4.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(vm.familiarPlaces.entries.toList(), key = { it.key }) { (alias, place) ->
                            PlaceListRow("$alias · ${place.name}", place.address) { choose(place) }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                    items.isEmpty() && message.isBlank() -> Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppIconBadge(R.drawable.ic_compass, size = 56.dp, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            if (keyword.isBlank()) "输入地点名称后搜索" else "点击上方按钮开始搜索",
                            Modifier.padding(top = 14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
                    ) {
                        items(items, key = { it.id }) { place ->
                            PlaceListRow(place.name, place.address) { choose(place) }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}
