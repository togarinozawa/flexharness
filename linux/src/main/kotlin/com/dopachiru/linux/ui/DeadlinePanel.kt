package com.dopachiru.linux.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.model.DeadlineTask
import com.dopachiru.core.model.DeadlineTasks
import com.dopachiru.linux.DesktopRuntime
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/**
 * 締切の札(PC)。入れるのはスマホで、ここでは「終わった」と一時停止だけ。
 *
 * PC では時間帯のあいだサイトだけを塞ぐ。スマホで「終わった」にすれば、
 * 同期でここも解ける ── 逆も同じ。
 */
@Composable
fun DeadlinePanel() {
    val file by DesktopRuntime.ruleFile.collectAsState()
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            nowSec = System.currentTimeMillis() / 1000
            delay(15_000)
        }
    }
    val zone = ZoneId.systemDefault()
    // 終わっていなくて、これから集中があるもの・いま集中しているものだけ
    val open = DeadlineTasks.visible(file.deadlines).filter {
        !it.isDone && (it.windowAt(nowSec, zone) != null || it.nextWindowStartAfter(nowSec, zone) != null)
    }
    if (open.isEmpty()) return

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("締切", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            open.forEach { task ->
                Spacer(Modifier.height(10.dp))
                DeadlineLine(task, nowSec, zone)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun DeadlineLine(task: DeadlineTask, nowSec: Long, zone: ZoneId) {
    val window = task.windowAt(nowSec, zone)
    val paused = window != null && task.pausedUntilSec > nowSec
    val canFinishAt = task.canFinishAt(nowSec, zone)
    var asking by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Text(task.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    Text(
        when {
            paused -> "一時停止中 ・ ${time(task.pausedUntilSec, zone)} に戻ります"
            window != null && nowSec < canFinishAt ->
                "集中中(サイトを塞いでいます)・ ${time(window.last + 1, zone)} まで ・ " +
                    "あと${(canFinishAt - nowSec + 59) / 60}分で「終わった」にできます"
            window != null -> "集中中(サイトを塞いでいます)・ ${time(window.last + 1, zone)} まで"
            else -> "次の集中 " + (task.nextWindowStartAfter(nowSec, zone)?.let { dateTime(it, zone) } ?: "なし")
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (window != null && !paused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { note = ""; asking = "finish" }, enabled = nowSec >= canFinishAt) { Text("終わった") }
        if (window != null) {
            OutlinedButton(onClick = { note = ""; asking = "pause" }, enabled = task.canPauseAt(nowSec, zone)) {
                Text("${DeadlineTask.PAUSE_MINUTES}分だけ止める")
            }
        }
    }
    if (error.isNotBlank()) {
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    asking?.let { kind ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(if (kind == "finish") "「${task.title}」を終わった" else "${DeadlineTask.PAUSE_MINUTES}分だけ止める") },
            text = {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(if (kind == "finish") "何をやったか一言" else "なぜ止めたいか") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = note.isNotBlank(),
                    onClick = {
                        error = (
                            if (kind == "finish") DesktopRuntime.finishDeadline(task.uid, note)
                            else DesktopRuntime.pauseDeadline(task.uid, note)
                            ).orEmpty()
                        asking = null
                    },
                ) { Text(if (kind == "finish") "終わった" else "止める") }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("やめる") } },
        )
    }
}

private fun time(epochSec: Long, zone: ZoneId): String {
    val t = Instant.ofEpochSecond(epochSec).atZone(zone)
    return String.format("%d:%02d", t.hour, t.minute)
}

private fun dateTime(epochSec: Long, zone: ZoneId): String {
    val t = Instant.ofEpochSecond(epochSec).atZone(zone)
    return String.format("%d/%d %d:%02d", t.monthValue, t.dayOfMonth, t.hour, t.minute)
}
