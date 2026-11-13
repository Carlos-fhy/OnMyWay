package com.onmyway.app.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.onmyway.app.R
import com.onmyway.app.core.ui.AppScreenHeader

internal enum class SettingsPage(val title: String) {
    HOME("设置"), MEMORY("个人记忆"), TRAVEL("出行偏好"), CONNECTION("数据与连接"), HELP("帮助与诊断"),
}

/** The shell retains each page's unsubmitted fields without saving them to the service. */
@Composable
internal fun SettingsNavigation(
    memorySummary: String,
    travelSummary: String,
    connectionSummary: String,
    content: @Composable (SettingsPage) -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    val pages = rememberSaveableStateHolder()
    BackHandler(page != SettingsPage.HOME) { page = SettingsPage.HOME }
    Column(Modifier.fillMaxSize().imePadding()) {
        AppScreenHeader(
            page.title,
            kicker = if (page == SettingsPage.HOME) "ON MY WAY" else null,
            subtitle = if (page == SettingsPage.HOME) "管理你的偏好、记忆和数据。" else null,
            leading = if (page == SettingsPage.HOME) null else ({
                IconButton(onClick = { page = SettingsPage.HOME }) {
                    Icon(painterResource(R.drawable.ic_chevron_right), "返回设置", Modifier.size(24.dp).rotate(180f))
                }
            }),
        )
        pages.SaveableStateProvider(page.name) {
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (page == SettingsPage.HOME) {
                    Column {
                        SettingsDestination(SettingsPage.MEMORY, memorySummary, R.drawable.ic_assistant) { page = it }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                        SettingsDestination(SettingsPage.TRAVEL, travelSummary, R.drawable.ic_route) { page = it }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                        SettingsDestination(SettingsPage.CONNECTION, connectionSummary, R.drawable.ic_settings) { page = it }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                        SettingsDestination(SettingsPage.HELP, "使用说明、隐私与问题排查", R.drawable.ic_compass) { page = it }
                    }
                } else content(page)
            }
        }
    }
}

@Composable
private fun SettingsDestination(page: SettingsPage, summary: String, icon: Int, open: (SettingsPage) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { open(page) }
            .heightIn(min = 88.dp).padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(page.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
            Text(summary, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
