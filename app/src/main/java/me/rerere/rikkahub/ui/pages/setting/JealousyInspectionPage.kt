/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Process
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.service.AppLockGuard
import me.rerere.rikkahub.data.service.AppLockStore
import me.rerere.rikkahub.data.service.JealousyInspectionState
import me.rerere.rikkahub.data.service.JealousyInspectionStore
import me.rerere.rikkahub.data.service.JealousyMood
import me.rerere.rikkahub.service.RikkaAccessibilityService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import org.koin.compose.koinInject

private val JealousyAccent = Color(0xFFF25F5C)
private data class InstalledApp(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JealousyInspectionPage(vm: SettingVM = koinInject()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf(JealousyInspectionStore.read(context)) }
    var pickerMode by remember { mutableStateOf<AppPickerMode?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            state = JealousyInspectionStore.read(context)
            delay(1_000L)
        }
    }

    val assistant = settings.assistants.find {
        it.id.toString() == settings.proactiveMessageSetting.assistantId
    } ?: settings.getCurrentAssistant()

    pickerMode?.let { mode ->
        AppSelectionDialog(
            mode = mode,
            selected = if (mode == AppPickerMode.MANAGED) {
                state.managedPackages
            } else {
                state.whitelistPackages
            },
            onDismiss = { pickerMode = null },
            onSave = { selected ->
                if (mode == AppPickerMode.MANAGED) {
                    JealousyInspectionStore.setManagedPackages(context, selected)
                } else {
                    JealousyInspectionStore.setWhitelistPackages(context, selected)
                }
                state = JealousyInspectionStore.read(context)
                pickerMode = null
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("吃醋巡检") },
                navigationIcon = { BackButton() },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            JealousyAccent.copy(alpha = 0.08f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    HeroCard(
                        assistantName = assistant.name.ifBlank { "AI" },
                        avatar = assistant.avatar,
                        state = state,
                    )
                }
                item { StatsRow(state) }
                if (state.recentUsageMinutes.isNotEmpty()) {
                    item { RecentUsageCard(context, state.recentUsageMinutes) }
                }
                item { RuleCard() }
                item { PermissionCard(context) }
                item {
                    GlassCard {
                        SettingRow(
                            title = "启用吃醋巡检",
                            subtitle = "停止聊天 30 分钟后，按固定规则计算吃醋值",
                            trailing = {
                                Switch(
                                    checked = state.enabled,
                                    onCheckedChange = { enabled ->
                                        JealousyInspectionStore.setEnabled(context, enabled)
                                        state = JealousyInspectionStore.read(context)
                                    },
                                )
                            },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        )
                        SettingRow(
                            title = "AI 可管理应用",
                            subtitle = "${state.managedPackages.size} 个，只有这些应用允许被锁",
                            onClick = { pickerMode = AppPickerMode.MANAGED },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        SettingRow(
                            title = "永不锁定白名单",
                            subtitle = "${state.whitelistPackages.size} 个自定义应用，" +
                                "系统关键应用始终受保护",
                            onClick = { pickerMode = AppPickerMode.WHITELIST },
                        )
                    }
                }
                if (state.jealousyLockedPackages.isNotEmpty() || state.forcedOpen || state.reconciling) {
                    item { CurrentLockCard(context, state) { state = JealousyInspectionStore.read(context) } }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(context: Context) {
    val usageGranted = hasUsageAccess(context)
    val accessibilityGranted = RikkaAccessibilityService.instance != null
    GlassCard {
        Text("运行权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        SettingRow(
            title = "使用情况访问",
            subtitle = if (usageGranted) {
                "已授权，可以计算应用使用时长"
            } else {
                "未授权，巡检无法计算吃醋值"
            },
            onClick = {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        SettingRow(
            title = "无障碍服务",
            subtitle = if (accessibilityGranted) {
                "已开启，可以即时拦截被锁应用"
            } else {
                "未开启，应用锁不会及时出现"
            },
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
        )
    }
}

private fun hasUsageAccess(context: Context): Boolean =
    (context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager).checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName,
    ) == AppOpsManager.MODE_ALLOWED

@Composable
private fun HeroCard(
    assistantName: String,
    avatar: Avatar,
    state: JealousyInspectionState,
) {
    val progress by animateFloatAsState(state.score / 100f, label = "jealousy-progress")
    val moodTitle = when (state.mood) {
        JealousyMood.CALM -> "今天心情不错"
        JealousyMood.CONCERNED -> "有点不高兴了"
        JealousyMood.JEALOUS -> "已经吃醋了"
        JealousyMood.ANGRY -> "真的生气了"
        JealousyMood.RECONCILING -> "还在慢慢消气"
        JealousyMood.FORCED_OPEN -> "强制解除，尚未和好"
    }
    val moodLabel = when (state.mood) {
        JealousyMood.CALM -> "平静"
        JealousyMood.CONCERNED -> "在意"
        JealousyMood.JEALOUS -> "吃醋"
        JealousyMood.ANGRY -> "生气"
        JealousyMood.RECONCILING -> "和好中"
        JealousyMood.FORCED_OPEN -> "未和好"
    }

    GlassCard(accent = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                    .padding(6.dp),
            ) {
                UIAvatar(
                    name = assistantName,
                    value = avatar,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    text = moodLabel,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(JealousyAccent)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = moodTitle,
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = assistantName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = state.score.toString(),
                color = JealousyAccent,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = " / 100",
                modifier = Modifier.padding(bottom = 7.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(50)),
                color = JealousyAccent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(18.dp),
                contentAlignment = Alignment.TopEnd,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(JealousyAccent),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text("提醒阈值 70", color = JealousyAccent, style = MaterialTheme.typography.labelMedium)
        }
        Text(
            text = if (state.score < 70) "距离提醒阈值还差 ${70 - state.score}" else "已达到提醒阈值",
            modifier = Modifier.padding(top = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun StatsRow(state: JealousyInspectionState) {
    val silenceMinutes = if (state.silenceStartedAt == 0L) {
        0L
    } else {
        ((System.currentTimeMillis() - state.silenceStartedAt) / 60_000L).coerceAtLeast(0L)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatTile("沉默", "$silenceMinutes 分钟", Modifier.weight(1f))
        StatTile("本轮巡检", "${state.inspectionCount} 次", Modifier.weight(1f))
        StatTile("受管应用", "${state.managedPackages.size} 个", Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(title: String, value: String, modifier: Modifier) {
    GlassCard(modifier = modifier, compact = true) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(value, modifier = Modifier.padding(top = 6.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RuleCard() {
    GlassCard {
        Text("巡检规则", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RuleItem("30 分钟后", "开始")
            RuleItem("每分钟", "+1")
            RuleItem("连续 15 分钟", "+10")
            RuleItem("提醒阈值", "70")
        }
    }
}

@Composable
private fun RecentUsageCard(context: Context, usage: Map<String, Int>) {
    val rows = remember(usage) {
        usage.entries.sortedByDescending { it.value }.take(4).map { entry ->
            val label = runCatching {
                val info = context.packageManager.getApplicationInfo(entry.key, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(entry.key)
            label to entry.value
        }
    }
    val maxMinutes = rows.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    GlassCard {
        Text("最近动向", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        rows.forEach { (label, minutes) ->
            Row(
                modifier = Modifier.padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(
                        progress = { minutes.toFloat() / maxMinutes },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(50)),
                        color = JealousyAccent,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    )
                }
                Text(
                    "$minutes 分钟",
                    modifier = Modifier.padding(start = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RuleItem(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Text(value, color = JealousyAccent, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GlassCard(
    accent: Boolean = false,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(if (compact) 20.dp else 24.dp)
    val colors = MaterialTheme.colorScheme
    // Draw one clipped background on the content container itself. Avoid nesting
    // a Material Card surface inside the glass decoration.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        colors.surface.copy(alpha = if (accent) 0.52f else 0.42f),
                        colors.surfaceContainerLow.copy(alpha = 0.28f),
                    ),
                ),
                shape = shape,
            )
            .border(
                width = 0.6.dp,
                color = colors.outlineVariant.copy(alpha = 0.3f),
                shape = shape,
            )
            .padding(if (compact) 14.dp else 20.dp),
        content = content,
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (trailing != null) trailing() else Icon(HugeIcons.ArrowRight01, null)
    }
}

@Composable
private fun CurrentLockCard(
    context: Context,
    state: JealousyInspectionState,
    onChanged: () -> Unit,
) {
    var showForceDialog by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    val hasPin = remember { AppLockStore.hasPin(context) }

    if (showForceDialog) {
        AlertDialog(
            onDismissRequest = { showForceDialog = false },
            title = { Text("强制解除") },
            text = {
                Column {
                    Text(
                        if (hasPin) {
                            "输入应用锁 PIN。强制解除不会清空吃醋值，TA 也会知道这次解除。"
                        } else {
                            "尚未设置应用锁 PIN。仍要强制解除吗？吃醋值不会清空。"
                        },
                    )
                    if (hasPin) {
                        OutlinedTextField(
                            value = pin,
                            onValueChange = {
                                pin = it.filter(Char::isDigit).take(6)
                                pinError = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            label = { Text("PIN") },
                            isError = pinError,
                            singleLine = true,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (hasPin && !AppLockStore.verifyPin(context, pin)) {
                            pinError = true
                        } else {
                            state.jealousyLockedPackages.forEach { AppLockStore.unlockApp(context, it) }
                            JealousyInspectionStore.recordForcedOpen(context)
                            AppLockGuard.refresh()
                            showForceDialog = false
                            onChanged()
                        }
                    },
                ) { Text("确认解除") }
            },
            dismissButton = {
                TextButton(onClick = { showForceDialog = false }) { Text("取消") }
            },
        )
    }

    GlassCard {
        Text("当前状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            when {
                state.forcedOpen -> "应用已被强制解除，但这次还没有和好。"
                state.reconciling -> "应用已经归还，继续聊一会儿，等 TA 真正消气。"
                else -> "${state.jealousyLockedPackages.size} 个应用正在等待 TA 同意解锁。"
            },
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.jealousyLockedPackages.isNotEmpty()) {
            TextButton(
                onClick = { showForceDialog = true },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("强制解除", color = JealousyAccent)
            }
        }
    }
}

private enum class AppPickerMode { MANAGED, WHITELIST }

@Composable
private fun AppSelectionDialog(
    mode: AppPickerMode,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val apps by produceState<List<InstalledApp>>(emptyList()) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }
    var query by remember { mutableStateOf("") }
    var draft by remember(selected) { mutableStateOf(selected) }
    val protected = remember { JealousyInspectionStore.protectedPackages(context) }
    val visibleApps = remember(apps, query) {
        apps.filter {
            it.packageName !in protected &&
                (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mode == AppPickerMode.MANAGED) "AI 可管理应用" else "永不锁定白名单") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索应用") },
                    singleLine = true,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .padding(top = 10.dp),
                ) {
                    items(visibleApps, key = { it.packageName }) { app ->
                        AppPickerRow(
                            app = app,
                            checked = app.packageName in draft,
                            onCheckedChange = { checked ->
                                draft = if (checked) draft + app.packageName else draft - app.packageName
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(draft) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AppPickerRow(
    app: InstalledApp,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        app.icon?.let {
            Image(
                bitmap = remember(it) { it.toBitmap().asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(11.dp)),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.packageName,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun loadLaunchableApps(context: Context): List<InstalledApp> {
    val packageManager = context.packageManager
    return packageManager.getInstalledApplications(0)
        .asSequence()
        .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
        .filterNot { it.packageName == context.packageName }
        .map { info: ApplicationInfo ->
            InstalledApp(
                label = packageManager.getApplicationLabel(info).toString(),
                packageName = info.packageName,
                icon = runCatching { packageManager.getApplicationIcon(info) }.getOrNull(),
            )
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}
