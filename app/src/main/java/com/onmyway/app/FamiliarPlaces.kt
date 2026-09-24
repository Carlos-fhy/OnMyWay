package com.onmyway.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun familiarPlaceAlias(text: String): String = when (val name = text.trim()) {
    "我家", "我的家" -> "家"
    "我的宿舍" -> "宿舍"
    "我的学校" -> "学校"
    "我的公司" -> "公司"
    else -> name
}

@Composable
internal fun FamiliarPlacesSettings(vm: AppViewModel) {
    var editing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    Text("常用地点", style = MaterialTheme.typography.titleLarge)
    Text("提前设置，之后说“回宿舍”“回家”即可使用。地点保存在本机。", style = MaterialTheme.typography.bodySmall)
    listOf("宿舍", "家", "学校", "公司").forEach { alias ->
        val place = vm.familiarPlaces[alias]
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(alias, style = MaterialTheme.typography.titleMedium)
                Text(place?.name ?: "尚未设置")
                if (!place?.address.isNullOrBlank()) Text(place!!.address, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton({ editing = alias }) { Text(if (place == null) "设置地点" else "修改地点") }
                    if (place != null) TextButton({ removing = alias }) { Text("清除") }
                }
            }
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
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("清除${alias}的位置？") },
            text = { Text("之后使用这个名称时需要重新选择地点。当前行程不受影响。") },
            confirmButton = { TextButton({ vm.saveFamiliarPlace(alias, null); removing = null }) { Text("清除") } },
            dismissButton = { TextButton({ removing = null }) { Text("取消") } })
    }
}
