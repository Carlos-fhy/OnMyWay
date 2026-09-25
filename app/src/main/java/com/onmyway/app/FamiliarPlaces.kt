package com.onmyway.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
    var adding by remember { mutableStateOf(false) }
    val savedNames = familiarPlaceSuggestions.filter { it in vm.familiarPlaces } +
        vm.familiarPlaces.keys.filterNot { it in familiarPlaceSuggestions }.sorted()
    val suggested = familiarPlaceSuggestions.filterNot { it in vm.familiarPlaces }

    Text("常用地点", style = MaterialTheme.typography.titleLarge)
    Text("给地图点位起一个你会说的称呼，例如「东门快递站」。以后在对话中说这个名称即可定位；取件、取餐时仍要确认任务的实际领取点。",
        style = MaterialTheme.typography.bodySmall)
    if (savedNames.isEmpty()) Text("还没有保存地点。可以先选下方常用称呼，或自己添加。", style = MaterialTheme.typography.bodySmall)
    savedNames.forEach { alias ->
        val place = vm.familiarPlaces[alias] ?: return@forEach
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(alias, style = MaterialTheme.typography.titleMedium)
                Text(place.name)
                if (place.address.isNotBlank()) Text(place.address, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton({ editing = alias }) { Text("修改地点") }
                    TextButton({ renaming = alias }) { Text("改称呼") }
                    TextButton({ removing = alias }) { Text("清除") }
                }
            }
        }
    }
    if (suggested.isNotEmpty()) {
        Text("快速设置", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggested.forEach { alias -> AssistChip(onClick = { editing = alias }, label = { Text(alias) }) }
        }
    }
    OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("添加自定义地点") }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("给地点起个称呼") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(24); error = "" }, label = { Text("称呼 / 备注") },
                        placeholder = { Text("例如：东门快递站") }, singleLine = true)
                    Text("下一步在地图中选择真实点位。", style = MaterialTheme.typography.bodySmall)
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton({
                val alias = familiarPlaceAlias(name)
                if (alias.isBlank()) error = "请填写地点称呼"
                else if (alias in vm.familiarPlaces) error = "这个称呼已存在，请修改现有地点"
                else { adding = false; editing = alias }
            }) { Text("选择地图点位") } },
            dismissButton = { TextButton({ adding = false }) { Text("取消") } })
    }
    renaming?.let { oldAlias ->
        var name by remember(oldAlias) { mutableStateOf(oldAlias) }
        var error by remember(oldAlias) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("修改地点称呼") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(24); error = "" }, label = { Text("称呼 / 备注") }, singleLine = true)
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton({
                if (vm.renameFamiliarPlace(oldAlias, name)) renaming = null
                else error = "称呼不能为空，也不能与其他地点重复"
            }) { Text("保存") } },
            dismissButton = { TextButton({ renaming = null }) { Text("取消") } })
    }
    editing?.let { alias ->
        PlacePicker(vm, { editing = null }, initialKeyword = vm.familiarPlaces[alias]?.name.orEmpty()) {
            vm.saveFamiliarPlace(alias, it)
            editing = null
            vm.message = "已保存${alias}的位置"
        }
    }
    removing?.let { alias ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("清除${alias}的位置？") },
            text = { Text("之后使用这个称呼时需要重新选择地点。已有任务和当前行程不受影响。") },
            confirmButton = { TextButton({ vm.saveFamiliarPlace(alias, null); removing = null }) { Text("清除") } },
            dismissButton = { TextButton({ removing = null }) { Text("取消") } })
    }
}
