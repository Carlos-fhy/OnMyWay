package com.onmyway.app.feature.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onmyway.app.R
import com.onmyway.app.core.format.displayTime
import com.onmyway.app.core.format.parseTime
import com.onmyway.app.core.model.INDOOR_PLACE_TYPE
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.model.isIndoor
import com.onmyway.app.core.model.placeTypes
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.ui.AppBottomSheet
import com.onmyway.app.core.ui.AppCard
import com.onmyway.app.core.ui.AppDatePickerDialog
import com.onmyway.app.core.ui.AppDateTimeDialog
import com.onmyway.app.core.ui.AppPrimaryButton
import com.onmyway.app.core.ui.AppSheetActionBar
import com.onmyway.app.core.ui.BottomSheetTitle
import com.onmyway.app.core.ui.ChoiceRow
import com.onmyway.app.core.ui.PlacePicker
import com.onmyway.app.core.ui.SectionLabel
import com.onmyway.app.core.ui.appTextFieldColors
import com.onmyway.app.data.local.entity.Errand
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject

@Composable
internal fun TaskEditor(vm: AppViewModel, task: Errand?, dismiss: () -> Unit,
    allowIncompleteDraft: Boolean = false, saveLabel: String = "保存任务", save: (Errand) -> Unit) {
    var title by rememberSaveable { mutableStateOf(task?.title ?: "") }
    var duration by rememberSaveable { mutableStateOf(task?.serviceMinutes?.toString() ?: "5") }
    var deadline by rememberSaveable { mutableStateOf(task?.deadline?.takeIf { it.isNotBlank() }?.let(::displayTime) ?: "") }
    var plannedDate by rememberSaveable { mutableStateOf(task?.plannedDate.orEmpty()) }
    var indoor by rememberSaveable { mutableStateOf(task?.placeType?.let(::isIndoor) == true) }
    var type by rememberSaveable { mutableStateOf(task?.placeType?.takeIf { it in placeTypes } ?: "SUPERMARKET") }
    var selectedPlaceJson by rememberSaveable { mutableStateOf(task?.placeJson.orEmpty()) }
    val place = selectedPlaceJson.takeIf { it.isNotBlank() }?.let { Place.from(JSONObject(it)) }
    var search by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingDeadline by remember { mutableStateOf(false) }
    val storedTasks by vm.tasks.collectAsStateWithLifecycle()
    val recentPlaces = storedTasks.filter { !isIndoor(it.placeType) && it.placeType == type }
        .mapNotNull { it.place() }.distinctBy { it.id }.take(3)
    val familiar = vm.familiarPlacesFor(type)
    val selectedAlias = familiar.firstOrNull { it.second.id == place?.id }?.first.orEmpty()
    AppBottomSheet(onDismissRequest = dismiss) { close ->
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding()) {
            BottomSheetTitle(
                if (task == null) "记下一件事" else "编辑任务",
                close,
                subtitle = "名称必填；时间、地点可以稍后补",
            )
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AppCard(contentPadding = PaddingValues(16.dp)) {
                    OutlinedTextField(
                        title,
                        { title = it.take(100) },
                        label = { Text("要办什么") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = appTextFieldColors(),
                    )
                }

                SectionLabel("时间安排")
                AppCard(contentPadding = PaddingValues(14.dp)) {
                    OutlinedButton(
                        onClick = { pickingDate = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                            .semantics { contentDescription = "计划日期" },
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_calendar), null, Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(plannedDate.ifBlank { "计划哪天做（可选）" })
                    }
                    if (plannedDate.isNotBlank()) {
                        TextButton({ plannedDate = "" }, contentPadding = PaddingValues(0.dp)) { Text("清除计划日期") }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { pickingDeadline = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                            .semantics { contentDescription = "截止时间" },
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_clock), null, Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(deadline.ifBlank { "截止时间（可选）" })
                    }
                    if (deadline.isNotBlank()) {
                        TextButton({ deadline = "" }, contentPadding = PaddingValues(0.dp)) { Text("清除截止时间") }
                    }
                    Text(
                        "计划日期决定哪天去办；截止时间用于判断是否过期。两处都不填也可以。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionLabel("地点设置")
                AppCard(contentPadding = PaddingValues(14.dp)) {
                    ChoiceRow(listOf("out" to "要出门", "indoors" to "不用出门"), if (indoor) "indoors" else "out") {
                        indoor = it == "indoors"
                    }
                    Spacer(Modifier.height(10.dp))
                    if (indoor) {
                        Text(
                            "写作业、复习、线上缴费这类待办会正常保存，但不会进入路线规划。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "办什么事",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ChoiceRow(placeTypes.toList(), type) {
                            if (type != it) {
                                type = it
                                selectedPlaceJson = ""
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (place == null) "还没有选择地点" else "已选 · ${place.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (place == null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                        )
                        if (place != null && place.address.isNotBlank()) Text(
                            place.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (familiar.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "常用地点 · 优先从这里选",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ChoiceRow(
                                familiar.map { (alias, saved) -> alias to "$alias · ${saved.name}" },
                                selectedAlias,
                            ) { alias ->
                                selectedPlaceJson = vm.familiarPlaces[alias]?.json()?.toString().orEmpty()
                            }
                        } else {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "常用地点里还没有标注「${placeTypes[type]}」用途的地点，可以搜索真实地点。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (recentPlaces.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "最近用过",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ChoiceRow(recentPlaces.map { it.id to it.name }, place?.id ?: "") { id ->
                                selectedPlaceJson = recentPlaces.first { it.id == id }.json().toString()
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { search = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_place_pin), null, Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (place == null) "搜索并选择地点" else "更换地点")
                        }
                        if (place == null) Text(
                            "也可以先保存为「待补办理地点」；补全前不会参与路线规划。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }

                if (!indoor) {
                    SectionLabel("预计办事时间")
                    AppCard(contentPadding = PaddingValues(14.dp)) {
                        ChoiceRow(
                            listOf("5" to "5分钟", "10" to "10分钟", "15" to "15分钟", "20" to "20分钟", "30" to "30分钟"),
                            duration,
                        ) { duration = it }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            duration,
                            { duration = it },
                            label = { Text("自定义办事分钟数（1–240）") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = appTextFieldColors(),
                        )
                    }
                }
                if (allowIncompleteDraft) Text(
                    "这是助手草稿：可以先保留修改，地点也可以稍后再补。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(bottom = 4.dp))
            }
            AppSheetActionBar {
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                AppPrimaryButton(
                    saveLabel,
                    onClick = {
                        try {
                            require(title.isNotBlank()) { "请填写任务名称" }
                            val minutes = if (indoor) {
                                duration.toIntOrNull()?.takeIf { it in 1..240 } ?: 5
                            } else {
                                duration.toIntOrNull()?.takeIf { it in 1..240 }
                                    ?: throw IllegalArgumentException("办事时长应为 1–240 分钟")
                            }
                            val originalDeadline = task?.deadline.orEmpty()
                            val originalDisplay = originalDeadline.takeIf { it.isNotBlank() }?.let(::displayTime).orEmpty()
                            val date = if (deadline == originalDisplay) originalDeadline else try {
                                parseTime(deadline) ?: ""
                            } catch (e: Exception) { throw IllegalArgumentException("截止时间格式应为 yyyy-MM-dd HH:mm") }
                            val updated = (task ?: Errand(title = title.trim())).copy(
                                title = title.trim(),
                                serviceMinutes = minutes,
                                deadline = date,
                                plannedDate = plannedDate,
                                placeJson = if (indoor) "" else place?.json()?.toString().orEmpty(),
                                placeType = if (indoor) INDOOR_PLACE_TYPE else type,
                            )
                            save(updated)
                        } catch (e: Exception) {
                            error = e.message ?: "请检查输入"
                        }
                    },
                )
                TextButton(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        }
    }
    if (pickingDate) {
        val current = runCatching { LocalDate.parse(plannedDate) }.getOrElse { LocalDate.now() }
        AppDatePickerDialog(current, { pickingDate = false }, title = "选择计划日期") { picked ->
            plannedDate = picked.toString(); pickingDate = false
        }
    }
    if (pickingDeadline) {
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val current = runCatching { LocalDateTime.parse(deadline, format) }.getOrElse { LocalDateTime.now().plusHours(1) }
        AppDateTimeDialog(current, { pickingDeadline = false }, title = "选择截止时间") { picked ->
            deadline = picked.format(format); pickingDeadline = false
        }
    }
    if (search) PlacePicker(vm, { search = false }) { selectedPlaceJson = it.json().toString(); search = false }
}
