package com.dopachiru.linux.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.gate.ChangeKind
import com.dopachiru.core.model.OneShotLimit
import com.dopachiru.core.model.Rule
import com.dopachiru.linux.DesktopRuntime
import com.dopachiru.linux.platform.ForegroundApp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 今日だけのルール。明日の朝4時に消える。Android 側と同じもの。
 *
 * 作れるものはルールのタブと同じ(雛形からでも、ゼロから組んでも)。
 * 前は「◯時間使ったら◯分休憩」しか作れなかった。
 *
 * Windows 版には集中のタブが無いので「今日」のタブに置いたまま。決めたくなるのは
 * **使う直前**で、設定の奥に入れると行き着くころには決める気が消えている。
 */
@Composable
internal fun OneShotCard() {
    val file by DesktopRuntime.ruleFile.collectAsState()
    val settings by DesktopRuntime.settings.collectAsState()
    val temporary = remember(file) { file.rules.filter { it.isTemporary } }

    var pickingPreset by remember { mutableStateOf(false) }
    // ゼロから組むときの下書き、または直している今日だけのルール
    var drafting by remember { mutableStateOf<Rule?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("今日だけの枠", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "ルールのタブと同じものを、今日だけ作れます。明日の朝4時に消えます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            temporary.forEach { rule ->
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                TemporaryRuleRow(
                    rule = rule,
                    onEdit = { drafting = rule },
                    // 消すのは縛りを緩める側。関門があるなら通す
                    onCancel = { DesktopRuntime.requestChange(ChangeKind.DELETE, rule) },
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickingPreset = true }) { Text("雛形から") }
                OutlinedButton(
                    onClick = { drafting = OneShotLimit.forToday(blankRule(), LocalDateTime.now()) },
                ) { Text("ゼロから組む") }
            }
        }
    }

    if (pickingPreset) {
        PresetFlow(
            // 縛りを増やす側なので関門は通さない(ルールのタブと同じ)
            onBuilt = { DesktopRuntime.addRule(OneShotLimit.forToday(it, LocalDateTime.now())) },
            onDismiss = { pickingPreset = false },
        )
    }

    drafting?.let { draft ->
        RuleEditorDialog(
            rule = draft,
            policy = settings.pointPolicy,
            onSave = { built ->
                if (built.id == 0L) {
                    DesktopRuntime.addRule(built)
                } else {
                    DesktopRuntime.requestChange(ChangeKind.UPDATE, built)
                }
                drafting = null
            },
            onDismiss = { drafting = null },
        )
    }
}

@Composable
private fun TemporaryRuleRow(rule: Rule, onEdit: () -> Unit, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(rule.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                rule.target.packages.joinToString("・") { ForegroundApp.labelFor(it) }
                    .ifBlank { "対象なし" } + "  ・" + expiryLabel(rule.expiresAtSec) + "に消えます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        TextButton(onClick = onEdit) { Text("直す") }
        TextButton(onClick = onCancel) { Text("やめる") }
    }
}

private val expiryFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm")

private fun expiryLabel(epochSec: Long): String =
    Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).format(expiryFormat)
