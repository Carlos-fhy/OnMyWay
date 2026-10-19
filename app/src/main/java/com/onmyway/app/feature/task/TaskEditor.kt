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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.onmyway.app.R
import com.onmyway.app.core.format.displayTime
import com.onmyway.app.core.format.parseTime
import com.onmyway.app.core.model.INDOOR_PLACE_TYPE
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.model.isIndoor
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONObject

@Composable
internal fun TaskEditor(vm: AppViewModel, task: Errand?, dismiss: () -> Unit,
    allowIncompleteDraft: Boolean = false, autoSaveOnDismiss: Boolean = false,
    saveLabel: String = "保存任务",
    feedback: String = "", mode: TaskEditorMode = TaskEditorMode.Manual,
    save: (Errand) -> Unit) {
    var title by rememberSaveable { mutableStateOf(task?.title ?: "") }
    var deadline by rememberSaveable { mutableStateOf(task?.deadline?.takeIf { it.isNotBlank() }?.let(::displayTime) ?: "") }
    var plannedDate by rememberSaveable { mutableStateOf(task?.plannedDate.orEmpty()) }
    var reminderAt by rememberSaveable { mutableLongStateOf(task?.reminderAt ?: 0L) }
    var indoor by rememberSaveable { mutableStateOf(task?.placeType?.let(::isIndoor) == true) }
    // 不再要求用户选择事项类型；新任务先记为「其他地点」，已有任务保留原类型。
    val type = task?.placeType?.takeIf { it.isNotBlank() } ?: "OTHER"
    val defaultPlace = if (mode == TaskEditorMode.Manual && task == null) vm.defaultFamiliarPlace else null
    val defaultAlias = if (defaultPlace != null) vm.defaultFamiliarPlaceAlias else ""
    var selectedPlaceJson by rememberSaveable {
        mutableStateOf(task?.placeJson.orEmpty().ifBlank { defaultPlace?.json()?.toString().orEmpty() })
    }
    val place = selectedPlaceJson.takeIf { it.isNotBlank() }?.let { Place.from(JSONObject(it)) }
    var search by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingDeadline by remember { mutableStateOf(false) }
    var pickingReminder by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(false) }
    val familiarAlias = place?.let { chosen ->
        vm.familiarPlaces.entries.firstOrNull { it.value.id == chosen.id }?.key
    }

    fun buildTask(): Errand? {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return null
        val originalDeadline = task?.deadline.orEmpty()
        val originalDisplay = originalDeadline.takeIf { it.isNotBlank() }?.let(::displayTime).orEmpty()
        val date = if (deadline == originalDisplay) originalDeadline else try {
            parseTime(deadline) ?: ""
        } catch (_: Exception) {
            throw IllegalArgumentException("截止时间格式应为 yyyy-MM-dd HH:mm")
        }
        return (task ?: Errand(title = trimmed)).copy(
            title = trimmed,
            deadline = date,
            plannedDate = plannedDate,
            reminderAt = reminderAt,
            placeJson = if (indoor) "" else place?.json()?.toString().orEmpty(),
            placeType = if (indoor) INDOOR_PLACE_TYPE
                else type.takeUnless { isIndoor(it) } ?: "OTHER",
        )
    }

    AppBottomSheet(onDismissRequest = {
        // 手动记事的编辑器没有保存按钮：下滑、返回或点空白处即保存已填写内容。
        if (autoSaveOnDismiss && !committed) {
            val updated = runCatching { buildTask() }.getOrNull()
            if (updated != null && (task == null || updated != task)) {
                committed = true
                save(updated)
            }
        }
        dismiss()
    }) { close ->
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding()) {
            BottomSheetTitle(
                when {
                    mode == TaskEditorMode.Assistant -> "核对任务"
                    task == null -> "记下一件事"
                    else -> "编辑任务"
                },
                null,
                subtitle = when {
                    mode == TaskEditorMode.Assistant -> "核对小助手安排的地点、时间和内容；地点不对可以更换。"
                    autoSaveOnDismiss -> "填好名称后下滑即保存"
                    else -> "名称必填；时间、地点可以稍后补"
                },
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
                    if (mode == TaskEditorMode.Assistant) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { pickingReminder = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                                .semantics { contentDescription = "提醒时间" },
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_clock), null, Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (reminderAt > 0) "提醒 ${displayReminderTime(reminderAt)}" else "提醒时间（可选）")
                        }
                        if (reminderAt > 0) {
                            TextButton({ reminderAt = 0 }, contentPadding = PaddingValues(0.dp)) { Text("清除提醒") }
                        }
                    }
                    Text(
                        if (mode == TaskEditorMode.Assistant)
                            "计划日期决定哪天去办；截止时间是硬期限；提醒到点后发通知。三者相互独立。"
                        else "计划日期决定哪天去办；截止时间默认在计划日当天结束，可再调整。两处都不填也可以。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionLabel("地点设置")
                AppCard(contentPadding = PaddingValues(14.dp)) {
                    if (mode == TaskEditorMode.Manual) {
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
                                if (place == null) "还没有选择地点" else "已选 · ${place.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (place == null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                            )
                            if (place != null && place.address.isNotBlank()) Text(
                                place.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (place != null && defaultAlias.isNotBlank() && place.id == defaultPlace?.id) Text(
                                "默认地点「$defaultAlias」，可直接保存或更换。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                    } else if (indoor) {
                        Text(
                            "不用出门 · 助手判断为室内事项，不会进入路线规划。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        Text(
                            if (place == null) "还没有确定办理地点" else "已选 · ${place.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (place == null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                        )
                        if (place != null && place.address.isNotBlank()) Text(
                            place.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            when {
                                place == null -> "助手没有找到可自动填入的常用地点，请选择。"
                                familiarAlias != null -> "已按常用地点填入，请核对。"
                                else -> "地点已选择，请核对。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { search = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_place_pin), null, Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (place == null) "选择地点" else "更换地点")
                        }
                    }
                }

                if (allowIncompleteDraft) Text(
                    "这是助手草稿：可以先保留修改，地点也可以稍后再补。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(bottom = 4.dp))
            }
            if (!autoSaveOnDismiss) {
                AppSheetActionBar {
                    val notice = error.ifBlank { feedback }
                    if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    AppPrimaryButton(
                        saveLabel,
                        onClick = {
                            try {
                                val updated = buildTask() ?: throw IllegalArgumentException("请填写任务名称")
                                save(updated)
                            } catch (e: Exception) {
                                error = e.message ?: "请检查输入"
                            }
                        },
                    )
                    TextButton(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                }
            } else {
                Text(
                    "填写后下滑即可保存，未填名称则不会创建任务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
                )
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
        val current = runCatching { LocalDateTime.parse(deadline, format) }
            .getOrElse { defaultDeadlineAfter(plannedDate) }
        AppDateTimeDialog(
            current, { pickingDeadline = false }, title = "选择截止时间", compact = true,
        ) { picked ->
            deadline = picked.format(format); pickingDeadline = false
        }
    }
    if (pickingReminder) {
        val initial = Instant.ofEpochMilli(
            reminderAt.takeIf { it > System.currentTimeMillis() } ?: (System.currentTimeMillis() + 3_600_000),
        ).atZone(ZoneId.systemDefault()).toLocalDateTime()
        AppDateTimeDialog(
            initial, { pickingReminder = false }, title = "选择提醒时间", compact = true,
        ) { picked ->
            val at = picked.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            pickingReminder = false
            if (at <= System.currentTimeMillis()) error = "请选择未来的提醒时间"
            else { reminderAt = at; error = "" }
        }
    }
    if (search) PlacePicker(vm, { search = false }) { selectedPlaceJson = it.json().toString(); search = false }
}
