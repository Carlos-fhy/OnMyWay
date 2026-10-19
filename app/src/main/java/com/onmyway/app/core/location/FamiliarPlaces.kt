package com.onmyway.app.core.location

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onmyway.app.R
import com.onmyway.app.core.model.placeTypeLabel
import com.onmyway.app.core.model.placeTypes
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.ui.AppConfirmDialog
import com.onmyway.app.core.ui.AppDialogCard
import com.onmyway.app.core.ui.AppIconBadge
import com.onmyway.app.core.ui.AppPill
import com.onmyway.app.core.ui.AppPrimaryButton
import com.onmyway.app.core.ui.PlacePicker
import com.onmyway.app.core.ui.appTextFieldColors

internal val familiarPlaceSuggestions = listOf("宿舍", "家", "学校", "公司", "快递站", "外卖点")

internal fun familiarPlaceAlias(text: String): String = when (val name = text.trim()) {
    "我家", "我的家" -> "家"
    "我的宿舍" -> "宿舍"
    "我的学校" -> "学校"
    "我的公司" -> "公司"
    "我的快递站" -> "快递站"
    "我的外卖点" -> "外卖点"
    else -> name
}

@Composable
internal fun FamiliarPlacesSettings(vm: AppViewModel) {
    var editing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var tagging by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val savedNames = familiarPlaceSuggestions.filter { it in vm.familiarPlaces } +
        vm.familiarPlaces.keys.filterNot { it in familiarPlaceSuggestions }.sorted()
    val suggested = familiarPlaceSuggestions.filterNot { it in vm.familiarPlaces }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "小助手只在同类型常用地点位于当前位置 3 公里内时自动填入；手动新建待办默认使用你设为默认的地点。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            vm.defaultFamiliarPlaceAlias.takeIf { it.isNotBlank() }?.let { "默认地点：$it" }
                ?: "尚未设置默认地点",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (savedNames.isEmpty()) Text(
            "还没有保存地点。可以先选下方常用称呼，或自己添加。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        savedNames.forEach { alias ->
            val place = vm.familiarPlaces[alias] ?: return@forEach
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        Modifier.padding(end = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIconBadge(
                            R.drawable.ic_place_pin,
                            size = 34.dp,
                            tint = MaterialTheme.colorScheme.tertiary,
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    alias,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (alias == vm.defaultFamiliarPlaceAlias) Text(
                                    "默认",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                place.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (place.address.isNotBlank()) Text(
                                place.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val tags = vm.familiarPlacePurposes[alias].orEmpty()
                            Text(
                                if (tags.isEmpty()) "未标注用途 · 没有已标注用途的同类地点时，按名称匹配"
                                else "用途 · ${tags.sorted().joinToString("、") { placeTypeLabel(it) }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Row(Modifier.padding(top = 2.dp)) {
                        TextButton({ editing = alias }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("修改地点")
                        }
                        TextButton({ tagging = alias }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("设置用途")
                        }
                        TextButton({ renaming = alias }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("改称呼")
                        }
                        TextButton({ removing = alias }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("清除")
                        }
                    }
                    Row(Modifier.padding(bottom = 2.dp)) {
                        val isDefault = alias == vm.defaultFamiliarPlaceAlias
                        TextButton(
                            { vm.setDefaultFamiliarPlace(if (isDefault) null else alias) },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(if (isDefault) "取消默认地点" else "设为默认地点")
                        }
                    }
                }
            }
        }
        if (suggested.isNotEmpty()) {
            Text(
                "快速设置",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggested.forEach { alias ->
                    AppPill(text = alias, selected = false, onClick = { editing = alias })
                }
            }
        }
        OutlinedButton(
            onClick = { adding = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(painterResource(R.drawable.ic_add), null, Modifier.size(18.dp))
            Text("添加自定义地点", Modifier.padding(start = 6.dp))
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var error by remember { mutableStateOf("") }
        AppDialogCard(
            "给地点起个称呼",
            onClose = { adding = false },
            subtitle = "先起一个你会说的称呼，下一步在地图中选择真实点位。",
        ) {
            OutlinedTextField(
                name,
                { name = it.take(24); error = "" },
                label = { Text("称呼 / 备注") },
                placeholder = { Text("例如：东门快递站") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = appTextFieldColors(),
            )
            if (error.isNotBlank()) Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            AppPrimaryButton("选择地图点位", onClick = {
                val alias = familiarPlaceAlias(name)
                if (alias.isBlank()) error = "请填写地点称呼"
                else if (alias in vm.familiarPlaces) error = "这个称呼已存在，请修改现有地点"
                else { adding = false; editing = alias }
            })
            TextButton(onClick = { adding = false }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
    renaming?.let { oldAlias ->
        var name by remember(oldAlias) { mutableStateOf(oldAlias) }
        var error by remember(oldAlias) { mutableStateOf("") }
        AppDialogCard(
            "修改地点称呼",
            onClose = { renaming = null },
            subtitle = "只改称呼，已保存的地图点位保持不变。",
        ) {
            OutlinedTextField(
                name,
                { name = it.take(24); error = "" },
                label = { Text("称呼 / 备注") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = appTextFieldColors(),
            )
            if (error.isNotBlank()) Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            AppPrimaryButton("保存", onClick = {
                if (vm.renameFamiliarPlace(oldAlias, name)) renaming = null
                else error = "称呼不能为空，也不能与其他地点重复"
            })
            TextButton(onClick = { renaming = null }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
    tagging?.let { alias ->
        val tags = vm.familiarPlacePurposes[alias].orEmpty()
        AppDialogCard(
            "「$alias」可以用来做什么？",
            onClose = { tagging = null },
            subtitle = "可多选。小助手只自动填入同类型且距离当前位置不远的地点。",
        ) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                placeTypes.forEach { (type, label) ->
                    AppPill(label, type in tags, { vm.toggleFamiliarPlacePurpose(alias, type) })
                }
            }
            Text(
                "例如文印室位于移动营业厅内，只要标注「打印店」，打印任务就会优先推荐它。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppPrimaryButton("完成", onClick = { tagging = null })
        }
    }
    editing?.let { alias ->
        PlacePicker(vm, { editing = null }, initialKeyword = vm.familiarPlaces[alias]?.name.orEmpty()) {
            vm.saveFamiliarPlace(alias, it)
            editing = null
            vm.message = "已保存${alias}的位置"
        }
    }
    removing?.let { alias ->
        AppConfirmDialog(
            title = "清除${alias}的位置？",
            message = "之后使用这个称呼时需要重新选择地点。已有任务和当前行程不受影响。",
            confirmLabel = "清除",
            onConfirm = { vm.saveFamiliarPlace(alias, null); removing = null },
            onDismiss = { removing = null },
            destructive = true,
        )
    }
}
