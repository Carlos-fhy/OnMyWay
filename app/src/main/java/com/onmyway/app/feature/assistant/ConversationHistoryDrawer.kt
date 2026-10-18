package com.onmyway.app.feature.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.onmyway.app.R
import com.onmyway.app.core.ui.AppKicker
import com.onmyway.app.core.ui.AppStatusPill
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ConversationHistoryDrawer(chat: AssistantViewModel, drawerState: DrawerState,
    selectedDay: LocalDate, select: (LocalDate) -> Unit, content: @Composable () -> Unit) {
    var days by remember { mutableStateOf<List<AssistantDay>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }
    LaunchedEffect(drawerState.isOpen, retry, chat.lines.size) {
        if (drawerState.isOpen) {
            loading = true; error = ""
            try { days = chat.conversationDays() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "对话读取失败，点击重试" }
            finally { loading = false }
        }
    }
    ModalNavigationDrawer(drawerState = drawerState, drawerContent = {
        ModalDrawerSheet(
            modifier = Modifier.fillMaxWidth(.85f).widthIn(max = 360.dp),
            drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
            drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 18.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    AppKicker("HISTORY")
                    Text("历史对话", style = MaterialTheme.typography.headlineSmall)
                }
                IconButton(
                    { scope.launch { drawerState.close() } },
                    Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                ) {
                    Icon(painterResource(R.drawable.ic_close), "关闭历史对话", Modifier.size(18.dp))
                }
            }
            Text(
                "按日期回看之前的安排，不会影响当前待办。",
                Modifier.padding(horizontal = 22.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            NavigationDrawerItem(
                label = { Text("今天的对话") },
                selected = selectedDay == LocalDate.now(),
                onClick = { select(LocalDate.now()) },
                modifier = Modifier.padding(horizontal = 12.dp),
                shape = RoundedCornerShape(14.dp),
            )
            if (loading) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
            if (error.isNotBlank()) TextButton({ retry++ }) { Text(error) }
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(days.filter { it.day != LocalDate.now().toString() }, key = { it.day }) { day ->
                    val date = LocalDate.parse(day.day)
                    NavigationDrawerItem(
                        label = { Text(day.day) },
                        selected = selectedDay == date,
                        badge = {
                            AppStatusPill(
                                day.count.toString(),
                                container = MaterialTheme.colorScheme.secondaryContainer,
                                content = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        },
                        onClick = { select(date) },
                        shape = RoundedCornerShape(14.dp),
                    )
                }
                if (!loading && days.none { it.day != LocalDate.now().toString() }) {
                    item {
                        Text(
                            "还没有更早的对话。",
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }, content = content)
}
