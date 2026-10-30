package com.dopachiru.ui.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.demo.DemoRules
import com.dopachiru.core.time.TimeScale
import com.dopachiru.demo.DemoMode
import kotlinx.coroutines.launch

/**
 * デモ版のホームの一番上に出す操作。本番では出さない([DemoMode.enabled])。
 *
 * 展示の係の人が触るのはここだけで済むようにしてある:
 * ルールを入れる → 見せる → 「次の人へ」で数えたものを戻す。
 */
@Composable
fun DemoCard() {
    if (!DemoMode.enabled) return
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var installed by remember { mutableStateOf(false) }
    val scale = DemoMode.scale.longValue
    val hud = DemoMode.hud.value

    LaunchedEffect(Unit) { installed = DemoMode.hasDemoRules() }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("デモ版", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "ルールの1分を ${scale}秒 で数えます。持ち時間・閉め出し・間隔・申請の待ちがまとめて縮みます。" +
                    "時刻の条件(22時から等)は縮みません。",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(12.dp))
            Text("1分 = 何秒", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DemoMode.CHOICES.forEach { choice ->
                    FilterChip(
                        selected = scale == choice,
                        onClick = {
                            scope.launch {
                                DemoMode.setScale(choice)
                                note = if (choice == TimeScale.REAL) "本番と同じ速さにしました" else "1分 = ${choice}秒 にしました"
                            }
                        },
                        label = { Text(if (choice == TimeScale.REAL) "縮めない" else "${choice}秒") },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("右上の札", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "経過・今日の合計・起動回数・効いている縛りと残り秒",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = hud, onCheckedChange = { DemoMode.setHud(it) })
            }

            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val n = DemoMode.installRules()
                        installed = true
                        note = "デモのルールを${n}本入れました(前のデモのルールは入れ直し)"
                    }
                }) { Text(if (installed) "ルールを入れ直す" else "デモのルールを入れる") }

                OutlinedButton(onClick = {
                    scope.launch {
                        DemoMode.reset()
                        note = "使った時間・回数・閉め出し・宣言を戻しました"
                    }
                }) { Text("次の人へ(やり直す)") }

                if (installed) {
                    TextButton(onClick = {
                        scope.launch {
                            DemoMode.removeRules()
                            installed = false
                            note = "デモのルールを消しました"
                        }
                    }) { Text("デモのルールを消す") }
                }
            }

            if (note.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(12.dp))
            Text("入るもの", style = MaterialTheme.typography.labelLarge)
            DemoRules.build(scale).forEach {
                Text("・${it.appLabel} — ${it.rule.name}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
