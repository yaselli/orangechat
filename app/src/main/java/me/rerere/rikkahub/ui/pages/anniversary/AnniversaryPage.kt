/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.anniversary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.data.datastore.AnniversaryEntry
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.ChronoUnit
import java.util.UUID

@Composable
fun AnniversaryPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val display = settings.displaySetting
    val entries = display.anniversaries
    val selected = entries.firstOrNull { it.id == display.anniversaryAiInjectionId }
        ?: entries.firstOrNull()
    var editing by remember { mutableStateOf<AnniversaryEntry?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    fun updateEntries(newEntries: List<AnniversaryEntry>, selectedId: String? = display.anniversaryAiInjectionId) {
        vm.updateSettings(
            settings.copy(
                displaySetting = display.copy(
                    anniversaries = newEntries,
                    anniversaryAiInjectionId = selectedId?.takeIf { id -> newEntries.any { it.id == id } },
                    anniversaryAiInjectionEnabled = display.anniversaryAiInjectionEnabled && newEntries.isNotEmpty(),
                )
            )
        )
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("纪念日") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                AnniversaryHero(entry = selected)
            }

            item {
                Text(
                    text = "我的纪念日",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
            }

            if (entries.isEmpty()) {
                item {
                    EmptyAnniversaryCard {
                        editing = null
                        showEditor = true
                    }
                }
            } else {
                items(entries, key = { it.id }) { entry ->
                    AnniversaryRow(
                        entry = entry,
                        selected = entry.id == selected?.id,
                        onSelect = {
                            vm.updateSettings(
                                settings.copy(
                                    displaySetting = display.copy(anniversaryAiInjectionId = entry.id)
                                )
                            )
                        },
                        onEdit = {
                            editing = entry
                            showEditor = true
                        },
                        onDelete = {
                            val remaining = entries.filterNot { it.id == entry.id }
                            val nextSelected = if (entry.id == selected?.id) remaining.firstOrNull()?.id else selected?.id
                            updateEntries(remaining, nextSelected)
                        },
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        editing = null
                        showEditor = true
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("新建纪念日")
                }
            }

            item {
                AiInjectionCard(
                    checked = display.anniversaryAiInjectionEnabled,
                    enabled = selected != null,
                    selectedTitle = selected?.title,
                    onCheckedChange = { checked ->
                        vm.updateSettings(
                            settings.copy(
                                displaySetting = display.copy(
                                    anniversaryAiInjectionEnabled = checked,
                                    anniversaryAiInjectionId = selected?.id,
                                )
                            )
                        )
                    },
                )
            }
        }
    }

    if (showEditor) {
        AnniversaryEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { title, date, countdown ->
                val old = editing
                val saved = AnniversaryEntry(
                    id = old?.id ?: UUID.randomUUID().toString(),
                    title = title,
                    startDate = date.toString(),
                    countdown = countdown,
                )
                val newEntries = if (old == null) entries + saved else entries.map { if (it.id == old.id) saved else it }
                updateEntries(newEntries, display.anniversaryAiInjectionId ?: saved.id)
                showEditor = false
            },
        )
    }
}

@Composable
private fun AnniversaryHero(entry: AnniversaryEntry?) {
    val today = LocalDate.now()
    val start = entry?.let { runCatching { LocalDate.parse(it.startDate) }.getOrNull() }
    val days = start?.let {
        if (entry?.countdown == true) ChronoUnit.DAYS.between(today, it)
        else ChronoUnit.DAYS.between(it, today) + 1
    }
    val nextAnnual = start?.let {
        val monthDay = MonthDay.from(it)
        var candidate = monthDay.atYear(today.year)
        if (!candidate.isAfter(today)) candidate = monthDay.atYear(today.year + 1)
        ChronoUnit.DAYS.between(today, candidate)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.13f), RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f),
                        )
                    )
                )
                .padding(26.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    text = entry?.let {
                        if (it.countdown) "距离“${it.title}”还有" else "我们的“${it.title}”"
                    } ?: "把重要的日子留在这里",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (days != null && days >= 0) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(days.toString(), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Light)
                        Text(" 天", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 10.dp))
                    }
                    Text(
                        if (entry?.countdown == true) "目标日期 ${entry.startDate.replace('-', '.')}"
                        else "始于 ${entry?.startDate.orEmpty().replace('-', '.')}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (entry?.countdown != true) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "距离下一周年还有 ${nextAnnual ?: 0} 天",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else if (days == 0L) {
                        Spacer(Modifier.height(10.dp))
                        Text("就是今天", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Text("新建一个纪念日后，这里会自动记录每一天。", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.InLove,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
    }
}

@Composable
private fun AnniversaryRow(
    entry: AnniversaryEntry,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val start = runCatching { LocalDate.parse(entry.startDate) }.getOrNull()
    val days = start?.let {
        if (entry.countdown) ChronoUnit.DAYS.between(LocalDate.now(), it).coerceAtLeast(0)
        else (ChronoUnit.DAYS.between(it, LocalDate.now()) + 1).coerceAtLeast(0)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .then(
                if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.38f), RoundedCornerShape(22.dp))
                else Modifier
            ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.9f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(HugeIcons.InLove, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.startDate.replace('-', '.'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (days != null) {
                Text(if (entry.countdown) "倒数 $days 天" else "$days 天", style = MaterialTheme.typography.titleMedium)
            }
            IconButton(onClick = onEdit) { Icon(HugeIcons.PencilEdit01, "编辑") }
            IconButton(onClick = onDelete) { Icon(HugeIcons.Delete01, "删除") }
        }
    }
}

@Composable
private fun EmptyAnniversaryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.78f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有纪念日", style = MaterialTheme.typography.titleMedium)
            Text("点这里记下第一个重要日子", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AiInjectionCard(
    checked: Boolean,
    enabled: Boolean,
    selectedTitle: String?,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.9f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("告诉 AI", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (selectedTitle == null) "请先新建纪念日" else "仅注入“$selectedTitle”的一条精简信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun AnniversaryEditorDialog(
    initial: AnniversaryEntry?,
    onDismiss: () -> Unit,
    onSave: (String, LocalDate, Boolean) -> Unit,
) {
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var dateText by remember(initial) { mutableStateOf(initial?.startDate ?: LocalDate.now().toString()) }
    var countdown by remember(initial) { mutableStateOf(initial?.countdown ?: false) }
    val parsedDate = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
    val valid = title.isNotBlank() && parsedDate != null && if (countdown) {
        !parsedDate.isBefore(LocalDate.now())
    } else {
        !parsedDate.isAfter(LocalDate.now())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建纪念日" else "编辑纪念日") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !countdown,
                        onClick = { countdown = false },
                        label = { Text("累计天数") },
                    )
                    FilterChip(
                        selected = countdown,
                        onClick = { countdown = true },
                        label = { Text("倒数日") },
                    )
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("名称") },
                    placeholder = { Text("例如：在一起") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text(if (countdown) "目标日期" else "开始日期") },
                    supportingText = {
                        Text(if (countdown) "格式：2026-12-31，可选择未来日期" else "格式：2023-05-20")
                    },
                    isError = dateText.isNotBlank() && parsedDate == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title.trim(), parsedDate!!, countdown) }, enabled = valid) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
