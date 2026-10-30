package com.onmyway.app.feature.task

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
                // 名称直接放在弹层内，不再包一层卡片。
                OutlinedTextField(
                    title,
                    { title = it.take(100) },
                    label = { Text("要办什么") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = appTextFieldColors(),
                )

                SectionLabel("时间安排")
                Column {
                    EditorFieldRow(
                        icon = R.drawable.ic_calendar,
                        label = "计划日期",
                        value = plannedDate.ifBlank { "计划哪天做（可选）" },
                        isPlaceholder = plannedDate.isBlank(),
                        description = "计划日期",
                        onClick = { pickingDate = true },
                        onClear = plannedDate.takeIf { it.isNotBlank() }?.let { { plannedDate = "" } },
                    )
                    EditorFieldRow(
                        icon = R.drawable.ic_clock,
                        label = "截止时间",
                        value = deadline.ifBlank { "截止时间（可选）" },
                        isPlaceholder = deadline.isBlank(),
                        description = "截止时间",
                        onClick = { pickingDeadline = true },
                        onClear = deadline.takeIf { it.isNotBlank() }?.let { { deadline = "" } },
                    )
                    if (mode == TaskEditorMode.Assistant) {
                        EditorFieldRow(
                            icon = R.drawable.ic_clock,
                            label = "提醒",
                            value = if (reminderAt > 0) "提醒 ${displayReminderTime(reminderAt)}" else "提醒时间（可选）",
                            isPlaceholder = reminderAt <= 0,
                            description = "提醒时间",
                            onClick = { pickingReminder = true },
                            onClear = if (reminderAt > 0) ({ reminderAt = 0 }) else null,
                        )
                    }
                }
                Text(
                    if (mode == TaskEditorMode.Assistant)
                        "计划日期决定哪天去办；截止时间是硬期限；提醒到点后发通知。三者相互独立。"
                    else "计划日期决定哪天去办；截止时间默认在计划日当天结束，可再调整。两处都不填也可以。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SectionLabel("地点设置")
                if (mode == TaskEditorMode.Manual) {
                    ChoiceRow(listOf("out" to "要出门", "indoors" to "不用出门"), if (indoor) "indoors" else "out") {
                        indoor = it == "indoors"
                    }
                    if (indoor) {
                        Text(
                            "写作业、复习、线上缴费这类待办会正常保存，但不会进入路线规划。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val subtle = MaterialTheme.colorScheme.onSurfaceVariant
                        val warning = MaterialTheme.colorScheme.tertiary
                        EditorFieldRow(
                            icon = R.drawable.ic_place_pin,
                            label = "地点",
                            value = if (place != null) "已选 · ${place.name}" else "搜索并选择地点",
                            isPlaceholder = place == null,
                            description = "地点",
                            onClick = { search = true },
                            supporting = buildList {
                                if (place != null && place.address.isNotBlank()) add(place.address to subtle)
                                if (place != null && defaultAlias.isNotBlank() && place.id == defaultPlace?.id) {
                                    add("默认地点「$defaultAlias」，可直接保存或更换。" to subtle)
                                }
                                if (place == null) {
                                    add("也可以先保存为「待补办理地点」；补全前不会参与路线规划。" to warning)
                                }
                            },
                        )
                    }
                } else if (indoor) {
                    Text(
                        "不用出门 · 助手判断为室内事项，不会进入路线规划。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    val subtle = MaterialTheme.colorScheme.onSurfaceVariant
                    val warning = MaterialTheme.colorScheme.tertiary
                    EditorFieldRow(
                        icon = R.drawable.ic_place_pin,
                        label = "地点",
                        value = if (place != null) "已选 · ${place.name}" else "选择地点",
                        isPlaceholder = place == null,
                        description = "地点",
                        onClick = { search = true },
                        supporting = buildList {
                            if (place != null && place.address.isNotBlank()) add(place.address to subtle)
                            add(
                                when {
                                    place == null -> "助手没有找到可自动填入的常用地点，请选择。"
                                    familiarAlias != null -> "已按常用地点填入，请核对。"
                                    else -> "地点已选择，请核对。"
                                } to if (place == null) warning else subtle,
                            )
                        },
                    )
                }

                if (allowIncompleteDraft) Text(
                    "这是助手草稿：可以先保留修改，地点也可以稍后再补。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
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

/** 表单行：图标＋字段名＋当前值＋箭头；只有字段本身有边界，不再外包卡片。 */
@Composable
private fun EditorFieldRow(
    icon: Int,
    label: String,
    value: String,
    isPlaceholder: Boolean,
    description: String,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
    supporting: List<Pair<String, Color>> = emptyList(),
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .semantics { contentDescription = description }
                .heightIn(min = 56.dp)
                .padding(horizontal = 2.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(icon),
                null,
                Modifier.size(18.dp),
                tint = if (isPlaceholder) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    value,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isPlaceholder) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                supporting.forEach { (text, color) ->
                    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
                }
            }
            if (onClear != null) {
                IconButton(onClear, Modifier.size(36.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        "清除$label",
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}
