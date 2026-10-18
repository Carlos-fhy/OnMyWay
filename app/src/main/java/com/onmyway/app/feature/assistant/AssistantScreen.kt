package com.onmyway.app.feature.assistant

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.onmyway.app.R
import com.onmyway.app.core.format.displayTime
import com.onmyway.app.core.format.formatDuration
import com.onmyway.app.core.location.hasLocationPermission
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.model.isIndoor
import com.onmyway.app.core.model.placeTypeIcon
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.ui.AppCard
import com.onmyway.app.core.ui.AppIconBadge
import com.onmyway.app.core.ui.AppPill
import com.onmyway.app.core.ui.AppPrimaryButton
import com.onmyway.app.core.ui.PlacePicker
import com.onmyway.app.core.ui.ThinkingDots
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.feature.navigation.RouteArchiveSheet
import com.onmyway.app.feature.planning.AutomaticRouteCard
import com.onmyway.app.feature.planning.PrepareTrip
import com.onmyway.app.feature.task.TaskEditor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantScreen(vm: AppViewModel, tasks: List<Errand>, settings: () -> Unit, openPlan: () -> Unit, openNavigation: () -> Unit,
    chat: AssistantViewModel = viewModel()) {
    var draft by remember { mutableStateOf<Pair<Errand, String>?>(null) }
    var taskDraftEdit by remember { mutableStateOf<TaskDraftEdit?>(null) }
    var search by remember { mutableStateOf<String?>(null) }
    var showVoice by rememberSaveable { mutableStateOf(false) }
    var showArchive by remember { mutableStateOf(false) }
    val historyDrawer = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    var selectedHistoryDate by rememberSaveable { mutableStateOf<String?>(null) }
    var screenTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) screenTime = System.currentTimeMillis()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            screenTime = System.currentTimeMillis()
        }
    }
    val today = Instant.ofEpochMilli(screenTime).atZone(ZoneId.systemDefault()).toLocalDate()
    val displayedDay = selectedHistoryDate?.let(LocalDate::parse) ?: today
    val viewingToday = displayedDay == today
    var visibleLines by remember { mutableStateOf<List<ChatLine>>(emptyList()) }
    var dayLoadError by remember { mutableStateOf("") }
    LaunchedEffect(chat.ready, chat.lines, displayedDay, vm.routeSession?.id, vm.tripStarted) {
        if (chat.ready) try {
            visibleLines = latestRouteEntries(chat.conversationDay(displayedDay))
            dayLoadError = ""
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { dayLoadError = "对话读取失败，请重新选择日期。" }
    }
    var archiveEventId by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scroll = rememberLazyListState()
    val dragging by scroll.interactionSource.collectIsDraggedAsState()
    var followLatest by remember(displayedDay) { mutableStateOf(true) }
    var scrollRequest by remember { mutableIntStateOf(0) }
    var listHeight by remember { mutableIntStateOf(0) }
    val itemCount by remember { derivedStateOf { scroll.layoutInfo.totalItemsCount } }
    val showLatest by remember { derivedStateOf { scroll.canScrollForward } }
    val context = LocalContext.current
    val auto = chat.automatic
    fun currentCard(expectedDraftId: String): Boolean {
        val current = chat.proposal
        val issue = current?.let(::assistantCardIssue)
        if (current == null || current.optString("_draft_id") != expectedDraftId || issue != null) {
            chat.error = issue ?: "待确认建议已变化，请使用当前卡片。"
            return false
        }
        return true
    }
    fun beginAutomatic(expectedDraftId: String = chat.proposal?.optString("_draft_id").orEmpty()) {
        if (chat.pendingRequest != null || chat.busy) return
        if (!currentCard(expectedDraftId)) return
        chat.proposal?.takeIf { it.optString("action") == "plan_route" && it.optString("mode") in listOf("walking", "cycling") }?.let {
            val knownDestination = it.optJSONObject("known_destination")?.let(Place::from) ?: chat.historyDestination
            auto.start(vm, it.getString("destination"), it.optString("mode", "walking"), knownDestination, it.getString("_draft_id"), it.getString("_plan_id"))
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission(context)) beginAutomatic()
        else chat.error = "未获得位置权限，可重试或手动设置起点。"
    }
    val locatePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission(context)) vm.locate()
        else vm.message = "未获得位置权限，请手动选择起点。"
    }
    LaunchedEffect(chat.proposal, chat.ready, chat.pendingRequest, chat.busy, vm.restoringTrip, vm.updatingTrip, vm.busy) {
        // Re-entering this tab must keep the existing preview, destination choices and edits.
        // Only a new proposal (or an explicit retry) starts a fresh calculation.
        if (chat.ready && chat.pendingRequest == null && !chat.busy && !vm.restoringTrip && !vm.updatingTrip && !vm.busy && chat.proposal?.optString("action") == "plan_route" &&
            chat.proposal?.optString("mode") in listOf("walking", "cycling") &&
            chat.proposal?.let(::assistantCardIssue) == null &&
            !chat.preparingRoute && !auto.busy && auto.preview == null &&
            auto.choices.isEmpty() && auto.error.isBlank()
        ) {
            if (hasLocationPermission(context)) beginAutomatic()
            else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    LaunchedEffect(vm.result) { if (chat.routeResult !== vm.result) chat.routeResult = null }
    fun chooseDestination(place: Place, expectedDraftId: String) {
        if (vm.busy || vm.updatingTrip || vm.restoringTrip || auto.committing) return
        if (!currentCard(expectedDraftId)) return
        vm.beginPlanEdit()
        auto.clear()
        vm.inputsChanged(); vm.destination = place
        vm.mode = chat.proposal?.optString("mode", "walking") ?: "walking"
        // 自动规划可能因定位超时没有起点；手动调整时补上当前位置，否则「重新规划」一直不可点。
        if (vm.origin == null) {
            if (hasLocationPermission(context)) vm.locate()
            else locatePermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        chat.preparingRoute = true
        focus.clearFocus(); keyboard?.hide()
    }
    LaunchedEffect(dragging) {
        if (dragging) followLatest = false
    }
    LaunchedEffect(scroll, displayedDay) {
        snapshotFlow { scroll.isScrollInProgress to scroll.canScrollForward }.collect { (scrolling, more) ->
            if (!scrolling && !more) followLatest = true
        }
    }
    LaunchedEffect(displayedDay, itemCount, listHeight, scrollRequest, visibleLines.lastOrNull()?.id,
        chat.proposal, chat.busy, chat.error, chat.routeResult, chat.tripCompletion?.eventId) {
        if (followLatest && itemCount > 0) scroll.scrollToItem(itemCount - 1)
    }
    fun sendMessage() {
        followLatest = true
        scrollRequest++
        chat.send(vm)
    }
    ConversationHistoryDrawer(chat, historyDrawer, displayedDay, { day ->
        selectedHistoryDate = if (day == today) null else day.toString()
        drawerScope.launch { historyDrawer.close() }
    }) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).imePadding()) {
            AssistantChatHeader(
                historyLabel = if (viewingToday) null else displayedDay.format(DateTimeFormatter.ofPattern("M月d日")) + " · 历史对话",
                history = { focus.clearFocus(); keyboard?.hide(); drawerScope.launch { historyDrawer.open() } },
                routes = { archiveEventId = null; showArchive = true },
                settings = settings,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("assistant-messages").onSizeChanged { listHeight = it.height },
                state = scroll,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (dayLoadError.isNotBlank()) item { Text(dayLoadError, color = MaterialTheme.colorScheme.error) }
                visibleLines.forEachIndexed { index, line ->
                    val day = chatDateLabel(line.createdAt)
                    if (index == 0 || chatDateLabel(visibleLines[index - 1].createdAt) != day) {
                        item(key = "date:${line.id}") {
                            DayDivider(day)
                        }
                    }
                    item(key = line.id) {
                        if (line.routeHistoryId != null) {
                            RouteHistoryLine(
                                createdAt = line.createdAt,
                                open = { archiveEventId = line.routeHistoryId; showArchive = true },
                            )
                        } else {
                            ChatBubble(line)
                        }
                    }
                }
                if (viewingToday) {
                    chat.pendingRequest?.takeIf { !chat.busy }?.let { request -> item(key = "interrupted-request") {
                        AppCard(contentPadding = PaddingValues(16.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIconBadge(R.drawable.ic_route, size = 34.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(if (chat.busy) "正在获取结果" else "上次请求尚未完成", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        if (chat.recoveryMessage.isNotBlank()) chat.recoveryMessage else "原请求已保留，可以继续获取。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(request.userText, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(10.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppPrimaryButton(
                                    "继续获取结果",
                                    onClick = { chat.recoverRequest(vm) },
                                    enabled = !chat.busy && !auto.busy && !vm.busy && !vm.restoringTrip && !vm.updatingTrip,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton({ chat.editInterruptedRequest() }, enabled = !chat.busy) { Text("重新编辑") }
                            }
                        }
                    } }
                    if (chat.ready && chat.pendingRequest == null && visibleLines.isEmpty() && chat.proposal == null && vm.result == null && !chat.busy) {
                        item(key = "welcome") { AssistantWelcome { prompt ->
                            chat.input = prompt
                            showVoice = false
                        } }
                    }
                    chat.proposal?.takeIf { chat.pendingRequest == null }?.let { proposal -> item {
                        val cardIssue = assistantCardIssue(proposal, screenTime)
                        if (cardIssue != null) {
                            UnavailableAssistantCard(cardIssue)
                        } else if (proposal.optString("action") == "plan_route") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("walking" to "步行", "cycling" to "骑行").forEach { (mode, label) ->
                                    AppPill(
                                        text = label,
                                        selected = proposal.optString("mode") == mode,
                                        onClick = { chat.chooseMode(mode, proposal.getString("_draft_id")) },
                                        enabled = !auto.busy && !vm.busy && !vm.updatingTrip && !vm.restoringTrip,
                                    )
                                }
                            }
                            if (proposal.optString("mode").isNotBlank()) {
                                AutomaticRouteCard(auto, vm, retry = {
                                    chat.error = ""
                                    if (hasLocationPermission(context)) beginAutomatic(proposal.getString("_draft_id"))
                                    else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                }, manual = {
                                    if (currentCard(proposal.getString("_draft_id"))) {
                                        if (auto.prepareManual(vm)) chat.preparingRoute = true else search = proposal.getString("destination")
                                    }
                                }, cancel = { chat.dismissDraft(proposal.getString("_draft_id"), proposal.getString("_plan_id"), vm) }, accepted = {
                                    chat.completeRoute(proposal.optString("_draft_id"), it, vm.lastPlannedHistoryId, "已更新方案，请核对后开始导航。")
                                }, canAct = { currentCard(proposal.getString("_draft_id")) && !chat.busy && chat.pendingRequest == null })
                            } else TextButton({ chat.dismissDraft(proposal.getString("_draft_id"), proposal.getString("_plan_id"), vm) },
                                enabled = !auto.busy && !vm.busy && !vm.updatingTrip && !vm.restoringTrip) { Text("取消") }
                        } else if (proposal.optString("action") == "create_tasks") {
                            AssistantTaskDraftCard(proposal,
                                enabled = chat.ready && !chat.busy && !vm.busy && !vm.updatingTrip && !vm.restoringTrip,
                                edit = { taskDraftEdit = it },
                                confirm = { chat.saveTasks(vm, proposal.getString("_draft_id"), it) },
                                cancel = { chat.dismissDraft(proposal.getString("_draft_id"), proposal.getString("_plan_id"), vm) })
                        } else if (proposal.optString("action") == "create_task") {
                            val task = proposal.getString("action") == "create_task"
                            val indoor = task && isIndoor(proposal.optString("place_type"))
                            val locationRequired = task && !indoor
                            AppCard(contentPadding = PaddingValues(18.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AppIconBadge(
                                        if (task) placeTypeIcon(proposal.optString("place_type")) else R.drawable.ic_tasks,
                                        size = 36.dp,
                                    )
                                    Text(
                                        if (task) "待确认任务" else "待确认目的地",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    if (task) proposal.getString("title") else proposal.getString("destination"),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                if (task && proposal.optString("planned_date").isNotBlank()) {
                                    Text(
                                        "计划 ${proposal.getString("planned_date")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (task && proposal.optString("deadline").isNotBlank()) {
                                    Text(
                                        "截止 ${displayTime(proposal.getString("deadline"))}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (locationRequired) {
                                    Text(
                                        "请选择实际办理地点；常用地点会优先显示，不会自动替你选店。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else if (indoor) {
                                    Text(
                                        "这类待办只记录，不参与路线规划。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    AppPrimaryButton(
                                        if (locationRequired) "选择办理地点" else if (task) "快速保存" else "选择目的地",
                                        onClick = {
                                            if (task) {
                                                val suggestedTask = Errand(
                                                    title = proposal.getString("title"),
                                                    placeType = proposal.getString("place_type"),
                                                    deadline = proposal.optString("deadline"),
                                                    plannedDate = proposal.optString("planned_date"),
                                                )
                                                val draftId = proposal.getString("_draft_id")
                                                if (locationRequired) draft = suggestedTask to draftId else chat.saveTask(vm, suggestedTask, draftId)
                                            } else {
                                                val keyword = proposal.getString("destination")
                                                val familiar = vm.familiarPlace(keyword)
                                                if (familiar != null) chooseDestination(familiar, proposal.getString("_draft_id")) else search = keyword
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        enabled = chat.ready && !chat.busy && !vm.busy && !vm.updatingTrip && !vm.restoringTrip,
                                    )
                                    if (task && indoor) {
                                        TextButton(onClick = {
                                            draft = Errand(
                                                title = proposal.getString("title"),
                                                placeType = proposal.getString("place_type"),
                                                deadline = proposal.optString("deadline"),
                                                plannedDate = proposal.optString("planned_date"),
                                            ) to proposal.getString("_draft_id")
                                        }, enabled = chat.ready && !chat.busy && !vm.busy) { Text("微调") }
                                    }
                                    TextButton(
                                        { chat.dismissDraft(proposal.getString("_draft_id"), proposal.getString("_plan_id"), vm) },
                                        enabled = chat.ready && !chat.busy,
                                    ) { Text("取消") }
                                }
                            }
                        }
                    } }
                    if (viewingToday) {
                        chat.tripCompletion?.let { prompt -> item(key = "trip-completion:${prompt.eventId}") {
                            TripCompletionCard(
                                prompt,
                                enabled = chat.ready && !chat.busy && chat.pendingRequest == null &&
                                    !vm.busy && !vm.updatingTrip && !vm.restoringTrip,
                                confirm = { chat.confirmTripCompletion(vm, it) },
                                dismiss = { chat.dismissTripCompletion() },
                            )
                        } }
                    }
                    vm.result?.let { route -> item {
                        val current = route === vm.result && !vm.editingPlan && !vm.restoringTrip
                        AppCard(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            border = null,
                            contentPadding = PaddingValues(18.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIconBadge(
                                    R.drawable.ic_route,
                                    size = 36.dp,
                                    container = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.55f),
                                )
                                Text(
                                    if (current) "路线已算好" else "此方案已更新，请查看当前行程",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                Place.from(route.getJSONObject("destination")).name,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            if (current) {
                                Text(
                                    "全程 ${formatDuration(route.getInt("total_seconds"))} · 多花 ${formatDuration(route.getInt("extra_seconds"))}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    "预计 ${displayTime(route.getString("arrival"))} 到达 · 规划估算",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                                )
                                if (vm.planNeedsRefresh) {
                                    Text(
                                        if (vm.tripStarted) "待办有变化，当前导航路线未自动更改，建议重新规划。"
                                        else "待办有变化，此路线需重新规划后才能开始。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                                val stops = route.getJSONArray("stops")
                                for (i in 0 until stops.length()) {
                                    Text(
                                        "${i + 1}. ${Place.from(stops.getJSONObject(i).getJSONObject("place")).name}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                                val omitted = route.getJSONArray("omitted")
                                if (omitted.length() > 0) {
                                    Text(
                                        "${omitted.length()} 件未安排，请在详情查看原因。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                    )
                                }
                                val warnings = route.getJSONArray("warnings")
                                for (i in 0 until warnings.length()) {
                                    Text(
                                        warnings.getString(i),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (current) AppPrimaryButton(
                                    if (vm.planNeedsRefresh && !vm.tripStarted) "重新规划" else if (vm.tripStarted) "继续导航" else "开始导航",
                                    onClick = {
                                        if (vm.planNeedsRefresh && !vm.tripStarted) { vm.beginPlanEdit(); openPlan() }
                                        else openNavigation()
                                    },
                                    enabled = !vm.busy && !vm.updatingTrip && chat.proposal == null && chat.pendingRequest == null,
                                    modifier = Modifier.weight(1f),
                                )
                                if (current) TextButton(openPlan) { Text("查看路线") }
                                else TextButton({ archiveEventId = null; showArchive = true }) { Text("路线记录") }
                            }
                        }
                    } }
                    if (!chat.ready) item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ThinkingDots()
                            Text("正在读取本机对话记录…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (chat.busy) item(key = "typing") { AssistantTypingIndicator() }
                    if (chat.error.isNotBlank()) item {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
                            Column(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(chat.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text(
                                    if (chat.pendingRequest != null) "原请求已保留，请使用上方恢复入口。"
                                    else if (chat.proposal != null) "建议已保留，可再次确认。"
                                    else if (chat.input.isNotBlank()) "内容已保留，可修改后重新发送。"
                                    else "可重新录音，或切换文字输入。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                }
            }
            if (showLatest) {
                SmallFloatingActionButton(
                    onClick = { followLatest = true; scrollRequest++ },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ) {
                    Icon(painterResource(R.drawable.ic_arrow_down), "回到最新消息", Modifier.size(22.dp))
                }
            }
            }
            if (!viewingToday) {
                TextButton({ selectedHistoryDate = null }, Modifier.fillMaxWidth()) {
                    Text("返回今天继续对话")
                }
            } else {
                val canSend = chat.ready && chat.pendingRequest == null && !chat.busy && !auto.busy &&
                    !vm.busy && !vm.updatingTrip && !vm.restoringTrip
                AssistantComposer(
                    input = chat.input, onInput = { chat.input = it }, ready = chat.ready, busy = chat.busy,
                    canSend = canSend, showVoice = showVoice,
                    toggleVoice = { showVoice = !showVoice; focus.clearFocus(); keyboard?.hide() },
                    server = vm.server,
                    onVoice = { chat.input = it.take(4000); sendMessage() },
                    onVoiceError = { chat.error = it },
                    send = { if (canSend && chat.input.isNotBlank()) sendMessage() },
                )
            }
        }
    }
    if (showArchive) RouteArchiveSheet(vm, initialEventId = archiveEventId, dismiss = { showArchive = false },
        repeatRoute = { event -> chat.repeatRoute(event, vm) }, openCurrent = openPlan)
    draft?.let { (initial, draftId) -> TaskEditor(vm, initial, { draft = null }) { updated ->
        if (chat.saveTask(vm, updated, draftId)) draft = null
    } }
    taskDraftEdit?.let { edit -> key(edit.draftId, edit.itemId, edit.version) {
        TaskEditor(vm, edit.task, { taskDraftEdit = null }, allowIncompleteDraft = true, saveLabel = "保留草稿修改") { updated ->
            if (chat.editTaskDraft(vm, edit, updated)) taskDraftEdit = null
        }
    } }
    search?.let { keyword ->
        val capturedDraftId = remember(keyword) { chat.proposal?.optString("_draft_id").orEmpty() }
        PlacePicker(vm, { search = null }, initialKeyword = keyword) { place ->
            search = null
            chooseDestination(place, capturedDraftId)
        }
    }
    if (chat.preparingRoute) {
        val editorDraftId = remember { chat.proposal?.optString("_draft_id").orEmpty() }
        val dismissRoute = {
            if (!vm.busy && !vm.updatingTrip) { vm.cancelPlanEdit(); chat.preparingRoute = false }
        }
        ModalBottomSheet(
            onDismissRequest = dismissRoute,
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || (!vm.busy && !vm.updatingTrip) }),
        ) {
            Column(Modifier.fillMaxHeight(.94f)) {
                if (vm.message.isNotBlank()) {
                    Text(
                        vm.message,
                        Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                PrepareTrip(vm, tasks, submit = {
                    if (currentCard(editorDraftId)) {
                        val confirmingDraft = chat.proposal!!
                        vm.planForAssistant(confirmingDraft.getString("_plan_id"), editorDraftId) { response ->
                            chat.completeRoute(editorDraftId, response, vm.lastPlannedHistoryId, "路线已算好，请核对方案后开始导航。")
                        }
                    }
                }, cancel = dismissRoute)
            }
        }
    }
}

@Composable
private fun DayDivider(day: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
        Text(day, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
    }
}

@Composable
private fun RouteHistoryLine(createdAt: Long, open: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_route),
                null,
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "路线方案 · " + Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton({ open() }) { Text("查看记录") }
    }
}
