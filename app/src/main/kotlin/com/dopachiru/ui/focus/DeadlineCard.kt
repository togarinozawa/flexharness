package com.dopachiru.ui.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dopachiru.core.model.DeadlineTask
import com.dopachiru.core.model.DeadlineTasks
import com.dopachiru.core.model.SiteCatalog
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.ui.common.AppPickerList
import com.dopachiru.ui.common.HourMinutePicker
import com.dopachiru.ui.rules.InstalledApps
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 締切タスクの札。集中タブに置く。
 *
 * 入れておくと、締切の前の決めた時間帯に**勝手に**集中が始まる。始める気が起きない晩
 * (テストの前の晩にアニメを見続けた、など)のためのもの。終わり方は「終わった」だけ。
 */
@Composable
fun DeadlineCard() {
    val all by DopaRuntime.deadlines.collectAsState(initial = emptyList())
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            nowSec = System.currentTimeMillis() / 1000
            delay(15_000)
        }
    }
    var editing by remember { mutableStateOf<DeadlineTask?>(null) }
    var creating by remember { mutableStateOf(false) }

    val visible = DeadlineTasks.visible(all)
    // 終わったものは直近の数件だけ。溜まると、まだ終わっていないものが埋もれる
    val open = visible.filterNot { it.isDone }
    val done = visible.filter { it.isDone }.sortedByDescending { it.doneAtSec }.take(3)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("締切", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "テストや提出物の締切を入れておくと、その前の決めた時間帯に勝手に集中が始まります。" +
                    "「終わった」にするまで続きます(時間帯が終わればその日はいったん開きます)。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            open.forEach { task ->
                Spacer(Modifier.height(12.dp))
                DeadlineRow(task, nowSec, onEdit = { editing = task })
            }
            if (done.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                done.forEach { task ->
                    Text(
                        "✓ ${task.title} ・ ${task.doneNote}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                Text("締切を足す")
            }
        }
    }

    if (creating) DeadlineEditor(initial = null, onDismiss = { creating = false })
    editing?.let { task -> DeadlineEditor(initial = task, onDismiss = { editing = null }) }
}

@Composable
private fun DeadlineRow(task: DeadlineTask, nowSec: Long, onEdit: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val scope = rememberCoroutineScope()
    val window = task.windowAt(nowSec, zone)
    val focusing = task.isFocusingAt(nowSec, zone)
    val paused = window != null && task.pausedUntilSec > nowSec
    val canFinishAt = task.canFinishAt(nowSec, zone)

    var asking by remember { mutableStateOf<Ask?>(null) }
    var error by remember { mutableStateOf("") }

    Surface(
        color = if (focusing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(task.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "締切 ${dateTime(task.dueAtSec)} ・ " + leadLabel(task.leadDays) + " " +
                    clock(task.windowStartMinute) + "〜" + clock(task.windowEndMinute) +
                    " ・ 最低${task.minMinutes}分",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    paused -> "一時停止中 ・ ${dateTime(task.pausedUntilSec, withDate = false)} に戻ります"
                    focusing && nowSec < canFinishAt ->
                        "集中中 ・ ${dateTime(window!!.last + 1, withDate = false)} まで ・ " +
                            "あと${(canFinishAt - nowSec + 59) / 60}分で「終わった」にできます"
                    focusing -> "集中中 ・ ${dateTime(window!!.last + 1, withDate = false)} まで"
                    else -> task.nextWindowStartAfter(nowSec, zone)?.let { "次の集中 ${dateTime(it)}" }
                        ?: "これから集中の時間帯はありません"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (focusing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { asking = Ask.FINISH }, enabled = nowSec >= canFinishAt) { Text("終わった") }
                if (window != null) {
                    OutlinedButton(onClick = { asking = Ask.PAUSE }, enabled = task.canPauseAt(nowSec, zone)) {
                        Text("${DeadlineTask.PAUSE_MINUTES}分だけ止める")
                    }
                } else {
                    // 時間帯のあいだに変えられると、時間帯を縮めて抜けられる。変えるのは時間帯の外だけ
                    TextButton(onClick = onEdit) { Text("変える") }
                    TextButton(onClick = { asking = Ask.DELETE }) { Text("消す") }
                }
            }
            if (window != null) {
                Text(
                    if (task.canPauseAt(nowSec, zone)) {
                        "止めると${DeadlineTask.PAUSE_MINUTES}分で勝手に戻ります。この時間帯に1回だけ。"
                    } else {
                        "この時間帯の一時停止は使いました。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (error.isNotBlank()) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    when (asking) {
        Ask.FINISH -> NoteDialog(
            title = "「${task.title}」を終わった",
            label = "何をやったか一言",
            confirm = "終わった",
            onDismiss = { asking = null },
            onConfirm = { note ->
                scope.launch {
                    error = DopaRuntime.finishDeadline(task.uid, note).orEmpty()
                    asking = null
                }
            },
        )
        Ask.PAUSE -> NoteDialog(
            title = "${DeadlineTask.PAUSE_MINUTES}分だけ止める",
            label = "なぜ止めたいか",
            confirm = "止める",
            onDismiss = { asking = null },
            onConfirm = { note ->
                scope.launch {
                    error = DopaRuntime.pauseDeadline(task.uid, note).orEmpty()
                    asking = null
                }
            },
        )
        Ask.DELETE -> AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("「${task.title}」を消す") },
            text = { Text("この締切の集中は起きなくなります。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { DopaRuntime.deleteDeadline(task.uid) }
                    asking = null
                }) { Text("消す", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("やめる") } },
        )
        null -> Unit
    }
}

private enum class Ask { FINISH, PAUSE, DELETE }

/** 一言書かせる。空のままでは押せない(書かずに通れるなら、書かせる意味が無い)。 */
@Composable
private fun NoteDialog(
    title: String,
    label: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(label) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(note) }, enabled = note.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

/**
 * 締切を入れる・変える画面。
 *
 * 時間帯と最低時間は自由に決められる。既定は「前の日の 18:00〜21:00、最低30分」。
 */
@Composable
private fun DeadlineEditor(initial: DeadlineTask?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()

    val initialDue = initial?.dueAtSec?.let { Instant.ofEpochSecond(it).atZone(zone) }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var dueDate by remember { mutableStateOf(initialDue?.toLocalDate() ?: LocalDate.now(zone).plusDays(1)) }
    var dueHour by remember { mutableIntStateOf(initialDue?.hour ?: 9) }
    var dueMinute by remember { mutableIntStateOf(initialDue?.minute ?: 0) }
    var startMinute by remember { mutableIntStateOf(initial?.windowStartMinute ?: DeadlineTask.DEFAULT_START) }
    var endMinute by remember { mutableIntStateOf(initial?.windowEndMinute ?: DeadlineTask.DEFAULT_END) }
    var leadDays by remember { mutableIntStateOf(initial?.leadDays ?: 1) }
    var minMinutes by remember { mutableIntStateOf(initial?.minMinutes ?: DeadlineTask.DEFAULT_MIN_MINUTES) }
    var allowPackages by remember { mutableStateOf(initial?.allowPackages ?: emptySet()) }
    var blockSites by remember { mutableStateOf(initial?.blockSites ?: DeadlineTask.DEFAULT_BLOCK_SITES) }
    var pickingApps by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Text(
                    if (initial == null) "締切を足す" else "締切を変える",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("何の締切か(例: 数学のテスト)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Section("締切")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { dueDate = dueDate.minusDays(1) }) { Text("−1日") }
                    Text(dateLabel(dueDate), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { dueDate = dueDate.plusDays(1) }) { Text("+1日") }
                }
                HourMinutePicker(dueHour, dueMinute, onChange = { h, m -> dueHour = h; dueMinute = m })

                Section("集中する時間帯")
                Text("始まり", style = MaterialTheme.typography.labelLarge)
                HourMinutePicker(startMinute / 60, startMinute % 60, onChange = { h, m -> startMinute = h * 60 + m })
                Text("終わり", style = MaterialTheme.typography.labelLarge)
                HourMinutePicker(endMinute / 60, endMinute % 60, onChange = { h, m -> endMinute = h * 60 + m })
                Text(
                    "終わりが始まりより前なら、日をまたいで続きます。寝る時刻より後にしないのがおすすめです。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Section("いつから")
                Stepper(
                    label = leadLabel(leadDays),
                    onMinus = { leadDays = (leadDays - 1).coerceAtLeast(0) },
                    onPlus = { leadDays = (leadDays + 1).coerceAtMost(14) },
                )

                Section("「終わった」にできるまでの最低時間")
                Stepper(
                    label = "${minMinutes}分",
                    onMinus = { minMinutes = (minMinutes - 5).coerceAtLeast(0) },
                    onPlus = { minMinutes = (minMinutes + 5).coerceAtMost(240) },
                )

                Section("集中中も開けるアプリ")
                Text(
                    if (allowPackages.isEmpty()) {
                        "まだありません。電話・ホーム・設定・キーボード・ドパチルは選ばなくても開きます。"
                    } else {
                        allowPackages.joinToString("、") { InstalledApps.labelOf(context, it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { pickingApps = true }) { Text("開けるアプリを選ぶ") }

                Section("集中中に塞ぐサイト")
                Text(
                    "開けるアプリにブラウザを入れても、ここのサイトは塞ぎます。PC ではサイトだけを塞ぎます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SiteCatalog.all.forEach { group ->
                    val on = group.patterns.all { it in blockSites }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = on,
                            onCheckedChange = { checked ->
                                blockSites = if (checked) blockSites + group.patterns else blockSites - group.patterns.toSet()
                            },
                        )
                        Column {
                            Text(group.label, style = MaterialTheme.typography.bodyMedium)
                            Text(group.help, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = title.isNotBlank(),
                        onClick = {
                            val now = System.currentTimeMillis() / 1000
                            val due = dueDate.atTime(dueHour, dueMinute).atZone(zone).toEpochSecond()
                            val task = (initial ?: DeadlineTask(
                                uid = UUID.randomUUID().toString(),
                                title = "",
                                dueAtSec = due,
                                createdAtSec = now,
                                updatedAtSec = now,
                            )).copy(
                                title = title.trim(),
                                dueAtSec = due,
                                windowStartMinute = startMinute,
                                windowEndMinute = endMinute,
                                leadDays = leadDays,
                                minMinutes = minMinutes,
                                allowPackages = allowPackages,
                                blockSites = blockSites,
                            )
                            scope.launch {
                                DopaRuntime.saveDeadline(task)
                                onDismiss()
                            }
                        },
                    ) { Text("保存") }
                    TextButton(onClick = onDismiss) { Text("やめる") }
                }
            }
        }
    }

    if (pickingApps) {
        AlertDialog(
            onDismissRequest = { pickingApps = false },
            title = { Text("集中中も開けるアプリ") },
            text = {
                AppPickerList(
                    selected = allowPackages,
                    onToggle = { pkg -> allowPackages = if (pkg in allowPackages) allowPackages - pkg else allowPackages + pkg },
                )
            },
            confirmButton = { TextButton(onClick = { pickingApps = false }) { Text("決める") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onMinus) { Text("−") }
        Text(label, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onPlus) { Text("+") }
    }
}

private fun leadLabel(days: Int): String = when (days) {
    0 -> "当日だけ"
    1 -> "前の日から"
    else -> "${days}日前から"
}

private fun clock(minuteOfDay: Int): String = String.format("%d:%02d", minuteOfDay / 60, minuteOfDay % 60)

private val WEEKDAYS = listOf("月", "火", "水", "木", "金", "土", "日")

private fun dateLabel(date: LocalDate): String =
    "${date.monthValue}/${date.dayOfMonth}(${WEEKDAYS[date.dayOfWeek.value - 1]})"

private fun dateTime(epochSec: Long, withDate: Boolean = true): String {
    val t = Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault())
    val time = String.format("%d:%02d", t.hour, t.minute)
    return if (withDate) dateLabel(t.toLocalDate()) + " " + time else time
}
