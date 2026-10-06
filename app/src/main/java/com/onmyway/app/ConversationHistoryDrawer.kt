package com.onmyway.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

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
        ModalDrawerSheet(Modifier.fillMaxWidth(.85f).widthIn(max = 360.dp)) {
            Text("历史对话", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
            NavigationDrawerItem(label = { Text("今天的对话") }, selected = selectedDay == LocalDate.now(),
                onClick = { select(LocalDate.now()) }, modifier = Modifier.padding(horizontal = 12.dp))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) TextButton({ retry++ }) { Text(error) }
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(days.filter { it.day != LocalDate.now().toString() }, key = { it.day }) { day ->
                    val date = LocalDate.parse(day.day)
                    NavigationDrawerItem(label = { Text(day.day) }, selected = selectedDay == date,
                        badge = { Text(day.count.toString()) }, onClick = { select(date) })
                }
            }
        }
    }, content = content)
}
