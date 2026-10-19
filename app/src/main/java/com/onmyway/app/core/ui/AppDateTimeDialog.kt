package com.onmyway.app.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.onmyway.app.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val dayHeaderFormatter = DateTimeFormatter.ofPattern("M月d日")
private val fullDateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")
private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val weekdayHeaders = listOf("一", "二", "三", "四", "五", "六", "日")

private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "今天"
    today.plusDays(1) -> "明天"
    today.plusDays(2) -> "后天"
    else -> "${date.format(dayHeaderFormatter)} ${weekdays[date.dayOfWeek.value - 1]}"
}

private fun dateDescription(date: LocalDate): String =
    "${date.format(fullDateFormatter)} ${weekdays[date.dayOfWeek.value - 1]}"

/**
 * 应用内统一的日期/时间选择浮层。默认用快捷选项；[compact] 为 true 时
 * 只保留一个日期按钮和直接输入的时间，适合待办编辑器等需要简约操作的场景。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppDateTimeDialog(
    initial: LocalDateTime,
    onDismiss: () -> Unit,
    withTime: Boolean = true,
    title: String = if (withTime) "选择日期和时间" else "选择日期",
    compact: Boolean = false,
    onConfirm: (LocalDateTime) -> Unit,
) {
    val today = remember { LocalDate.now() }
    val horizon = remember(today) { (0L..59L).map { today.plusDays(it) } }
    var selectedDate by rememberSaveable { mutableStateOf(initial.toLocalDate().toString()) }
    var hour by rememberSaveable { mutableIntStateOf(initial.hour) }
    // 分钟按 5 分钟刻度展示，初始值先归整，避免打开时没有任何选项被选中。
    var minute by rememberSaveable { mutableIntStateOf(((initial.minute + 2) / 5 * 5).coerceAtMost(55)) }
    var calendar by remember { mutableStateOf(false) }
    val timeState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )

    val date = LocalDate.parse(selectedDate)
    val shownHour = if (compact && withTime) timeState.hour else hour
    val shownMinute = if (compact && withTime) timeState.minute else minute
    val days = remember(horizon, date) { if (date in horizon) horizon else listOf(date) + horizon }
    val dateIndex = days.indexOf(date).coerceAtLeast(0)
    val dateState = rememberLazyListState(initialFirstVisibleItemIndex = (dateIndex - 1).coerceAtLeast(0))
    val hourState = rememberLazyListState(initialFirstVisibleItemIndex = (hour - 2).coerceAtLeast(0))
    val minuteIndex = minute / 5
    val minuteState = rememberLazyListState(initialFirstVisibleItemIndex = (minuteIndex - 2).coerceAtLeast(0))

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.93f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(top = 22.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppKicker(if (withTime) "DATE · TIME" else "DATE")
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            if (withTime) "${dateDescription(date)}  ${"%02d:%02d".format(shownHour, shownMinute)}" else dateDescription(date),
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                if (compact) {
                    SectionLabel("日期")
                    OutlinedButton(
                        onClick = { calendar = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 50.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_calendar), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(dateDescription(date))
                    }
                    if (withTime) {
                        SectionLabel("时间")
                        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                            TimeInput(state = timeState)
                        }
                    }
                } else {
                    SectionLabel("日期")
                    LazyRow(
                        state = dateState,
                        modifier = Modifier.testTag("date-row"),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(days) { _, day ->
                            AppPill(
                                text = dayLabel(day, today),
                                selected = day == date,
                                onClick = { selectedDate = day.toString() },
                            )
                        }
                    }
                    if (withTime) {
                        SectionLabel("小时")
                        LazyRow(
                            state = hourState,
                            modifier = Modifier.testTag("hour-row"),
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(24) { value ->
                                AppPill(
                                    text = "${value}时",
                                    selected = hour == value,
                                    onClick = { hour = value },
                                )
                            }
                        }
                        SectionLabel("分钟")
                        LazyRow(
                            state = minuteState,
                            modifier = Modifier.testTag("minute-row"),
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(12) { index ->
                                val value = index * 5
                                AppPill(
                                    text = "%02d分".format(value),
                                    selected = minute == value,
                                    onClick = { minute = value },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(
                    Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!compact) TextButton(onClick = { calendar = true }) { Text("更远日期…") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        onClick = { onConfirm(date.atTime(shownHour, shownMinute)) },
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("确定") }
                }
            }
        }
    }

    if (calendar) {
        AppDatePickerDialog(
            initial = date,
            onDismiss = { calendar = false },
            title = "更远日期",
        ) { picked ->
            selectedDate = picked.toString()
            calendar = false
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
internal fun AppDatePickerDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    title: String = "选择日期",
    onConfirm: (LocalDate) -> Unit,
) {
    AppDialogCard(
        title = title,
        onClose = onDismiss,
        subtitle = "翻到要安排的月份，再选具体日期。",
    ) {
        MonthCalendar(initial = initial, onDismiss = onDismiss, onConfirm = onConfirm)
    }
}

/** 与 App 圆角弹窗一致的月历；不使用系统 DatePicker 的英文标题和配色。 */
@Composable
private fun MonthCalendar(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    var month by remember { mutableStateOf(YearMonth.from(initial)) }
    var selected by remember { mutableStateOf(initial) }
    val today = remember { LocalDate.now() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ month = month.minusMonths(1) }, Modifier.size(34.dp)) {
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                "上个月",
                Modifier.size(18.dp).rotate(180f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${month.year}年${month.monthValue}月",
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        IconButton({ month = month.plusMonths(1) }, Modifier.size(34.dp)) {
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                "下个月",
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(Modifier.fillMaxWidth()) {
        weekdayHeaders.forEach { label ->
            Text(
                label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
    val leading = month.atDay(1).dayOfWeek.value - 1
    val dayCount = month.lengthOfMonth()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (row in 0 until (leading + dayCount + 6) / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (column in 0..6) {
                    val day = row * 7 + column - leading + 1
                    Box(Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.Center) {
                        if (day in 1..dayCount) {
                            val value = month.atDay(day)
                            val isSelected = value == selected
                            val isToday = value == today
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isSelected -> MaterialTheme.colorScheme.primary
                                            isToday -> MaterialTheme.colorScheme.primaryContainer
                                            else -> Color.Transparent
                                        },
                                    )
                                    .clickable { selected = value }
                                    .semantics(mergeDescendants = true) {},
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "$day",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.onPrimary
                                        isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    Text(
        "已选 ${dateDescription(selected)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AppPrimaryButton("确定", onClick = { onConfirm(selected) })
    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
}
