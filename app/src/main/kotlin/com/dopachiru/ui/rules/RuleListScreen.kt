package com.dopachiru.ui.rules

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.gate.ChangeKind
import com.dopachiru.core.model.ConditionTree
import com.dopachiru.core.model.DeviceScope
import com.dopachiru.core.model.Rule
import com.dopachiru.data.PendingChange
import com.dopachiru.runtime.DopaRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RuleListViewModel(app: Application) : AndroidViewModel(app) {
    val rules: StateFlow<List<Rule>> = DopaRuntime.rules.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 無効化・一時停止の申請中のもの。ルールの番号ごとに。カードに「申請中」と出すため。 */
    val pendingByRule: StateFlow<Map<Long, PendingChange>> = DopaRuntime.changes.pending
        .map { list ->
            list.filter { it.kind == ChangeKind.DISABLE && it.entity.targetRuleId != null }
                .associateBy { it.entity.targetRuleId!! }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * 止める。[minutes] が 0 ならずっと無効にする、それ以外は時間で戻る一時停止。
     *
     * どちらも関門を通す(縛りを緩める方向にだけ摩擦をかける)。関門が無ければその場で効く。
     */
    fun stop(rule: Rule, minutes: Int) {
        viewModelScope.launch {
            val gates = DopaRuntime.settings.gates.first()
            DopaRuntime.changes.request(ChangeKind.DISABLE, rule, gates, pauseMinutes = minutes)
        }
    }

    /** 有効にする。縛る方向なので即時。 */
    fun enable(rule: Rule) {
        viewModelScope.launch { DopaRuntime.changes.request(ChangeKind.ENABLE, rule, emptyList()) }
    }

    /** 一時停止をやめて、すぐ効かせる。縛る方向なので即時。 */
    fun resume(rule: Rule) {
        viewModelScope.launch { DopaRuntime.resumeRule(rule.id) }
    }

    fun cancelRequest(requestId: Long) {
        viewModelScope.launch { DopaRuntime.changes.cancel(requestId) }
    }

    /**
     * まるごと写して1本増やす。少しだけ違うものを作るときの近道。
     * 番号と uid は新しく振ります ── 引き継ぐと、写した先が元を上書きします。
     *
     * 新規作成なので関門は通しません(縛りを増やす方向)。
     */
    fun duplicate(rule: Rule) {
        viewModelScope.launch {
            val copy = rule.copy(id = 0L, uid = "", name = rule.name + "(写し)")
            DopaRuntime.changes.request(ChangeKind.CREATE, copy, emptyList())
        }
    }

    /** 名簿。一覧に「どの端末で効くか」を出すため。 */
    val deviceNames: StateFlow<Map<String, String>> = DopaRuntime.devices
        .map { list -> list.associate { it.deviceId to it.displayName } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 雛形から作る。新規作成なのでゲートは通さず即時反映。 */
    fun create(rule: Rule) {
        viewModelScope.launch {
            DopaRuntime.changes.request(ChangeKind.CREATE, rule, emptyList())
        }
    }
}

@Composable
fun RuleListScreen(
    onCreate: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenTags: () -> Unit = {},
    onOpenChanges: () -> Unit = {},
    viewModel: RuleListViewModel = viewModel(),
) {
    val rules by viewModel.rules.collectAsState()
    val deviceNames by viewModel.deviceNames.collectAsState()
    val pendingByRule by viewModel.pendingByRule.collectAsState()
    val context = LocalContext.current

    var pickingPreset by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf<Rule?>(null) }

    // 一時停止の残りを出すための時計。止まっているあいだの表示が古くならないように
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            nowSec = System.currentTimeMillis() / 1000
            delay(30_000)
        }
    }

    stopping?.let { rule ->
        StopRuleDialog(
            ruleName = rule.name,
            onPick = { minutes ->
                viewModel.stop(rule, minutes)
                stopping = null
            },
            onDismiss = { stopping = null },
        )
    }

    Box(Modifier.fillMaxSize()) {
        if (rules.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("まだルールがありません", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "雛形から始めるのが速いです。細部はあとから直せます。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { pickingPreset = true }) { Text("雛形から作る") }
                RuleTransferControls()
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCreate) { Text("ゼロから組む") }
                TextButton(onClick = onOpenTags) { Text("タグを編集") }
            }
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { pickingPreset = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("雛形から足す")
                        }
                        // タグはタブをやめてここに置いた。ルールを書くときにしか
                        // 触らないものに、下タブを1つ使う必要はない
                        OutlinedButton(onClick = onOpenTags, modifier = Modifier.weight(1f)) {
                            Text("タグを編集")
                        }
                    }
                    // 込み入ったルールは、書き出して手元の道具に直してもらうほうが早い
                    RuleTransferControls()
                }

                items(rules, key = { it.id }) { rule ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onEdit(rule.id) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    rule.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    describeTarget(context, rule),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    describeRule(rule),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (rule.devices.isNotEmpty()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "端末: " + DeviceScope.describe(rule.devices) { id ->
                                            deviceNames[id] ?: id
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                val pending = pendingByRule[rule.id]
                                StopStatus(
                                    rule = rule,
                                    pending = pending,
                                    nowSec = nowSec,
                                    onResume = { viewModel.resume(rule) },
                                    onOpenChanges = onOpenChanges,
                                    onCancel = { pending?.let { viewModel.cancelRequest(it.entity.id) } },
                                )
                                TextButton(onClick = { viewModel.duplicate(rule) }) { Text("複製") }
                            }
                            Switch(
                                checked = rule.isActiveAt(nowSec),
                                onCheckedChange = {
                                    when {
                                        !rule.enabled -> viewModel.enable(rule)
                                        rule.isPausedAt(nowSec) -> viewModel.resume(rule)
                                        // 申請中にもう一度押しても、申請を重ねない
                                        pendingByRule[rule.id] != null -> onOpenChanges()
                                        else -> stopping = rule
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onCreate,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "ルールを作る")
        }
    }

    if (pickingPreset) {
        PresetFlow(onBuilt = viewModel::create, onDismiss = { pickingPreset = false })
    }
}

private fun describeTarget(context: android.content.Context, rule: Rule): String {
    if (rule.target.matchAll) {
        val excluded = rule.target.exceptPackages.size + rule.target.exceptTags.size
        return if (excluded == 0) "全アプリ" else "全アプリ(除外${excluded}件)"
    }
    val apps = rule.target.packages.map { InstalledApps.labelOf(context, it) }
    val tags = rule.target.tags.map { "#$it" }
    val all = apps + tags
    return when {
        all.isEmpty() -> "対象なし"
        all.size <= 3 -> all.joinToString("、")
        else -> "${all.take(3).joinToString("、")} 他${all.size - 3}件"
    }
}

/**
 * 条件・アクション・罰を1行に畳む。
 *
 * 入れ子や OR も [ConditionTree.describe] が括弧付きで畳んでくれるので、
 * 一覧を見ただけで中身の見当がつく。
 */
fun describeRule(rule: Rule): String {
    val condition = ConditionTree.describe(rule.condition)
    val action = ActionRegistry[rule.actionId]?.summarize(rule.actionParams) ?: rule.actionId
    val head = if (ConditionTree.leafCount(rule.condition) == 0) "常に" else condition
    val consequence = rule.consequence.breakPoints
        ?.takeIf { it != 0 }
        ?.let { pt -> " / 破ったら${if (pt < 0) "${-pt}pt払う" else "+${pt}pt"}" }
        ?: ""
    // 2組目以降があることを隠さない。隠すと、一覧に出ていない組が黙って
    // 効いて「書いていないのに閉まる」になる
    val more = if (rule.extraClauses.isEmpty()) "" else " ほか" + rule.extraClauses.size + "組"
    return "$head → $action$consequence$more"
}

/**
 * 止め方を選ばせる。
 *
 * **時間で戻るほうを先に並べる。** 押し切る口が「ずっと無効にする」しか無かったころは、
 * 押し切った1回がそのまま数日の空白になっていた。戻ると分かっている止め方を
 * いちばん手前に置いて、被害を区切る。
 */
@Composable
private fun StopRuleDialog(
    ruleName: String,
    onPick: (minutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「$ruleName」を止める") },
        text = {
            Column {
                Text(
                    "時間がたつと勝手に戻ります。関門(待ち時間など)を通り終えたところで止まります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                PAUSE_CHOICES.forEach { minutes ->
                    OutlinedButton(onClick = { onPick(minutes) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (minutes < 60) "${minutes}分だけ止める" else "${minutes / 60}時間だけ止める")
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { onPick(0) }, modifier = Modifier.fillMaxWidth()) {
                    Text("ずっと止める(自分で戻すまで)", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

private val PAUSE_CHOICES = listOf(15, 30, 60)

/**
 * 止まっている・止める申請中、をカードに出す。
 *
 * 前はスイッチを押しても申請が裏で出るだけで、何も変わらないように見えた
 * ── 「押せない」と思われて、ドパチルごと切られていた。何が起きているかを必ず出す。
 */
@Composable
private fun StopStatus(
    rule: Rule,
    pending: PendingChange?,
    nowSec: Long,
    onResume: () -> Unit,
    onOpenChanges: () -> Unit,
    onCancel: () -> Unit,
) {
    when {
        rule.enabled && rule.isPausedAt(nowSec) -> {
            val at = java.time.Instant.ofEpochSecond(rule.pausedUntilSec)
                .atZone(java.time.ZoneId.systemDefault())
            Spacer(Modifier.height(4.dp))
            Text(
                String.format("一時停止中 ・ %d:%02d に勝手に戻ります", at.hour, at.minute),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            TextButton(onClick = onResume) { Text("いま戻す") }
        }

        pending != null -> {
            val what = if (pending.pauseMinutes > 0) "${pending.pauseMinutes}分止める" else "ずっと止める"
            val left = pending.remaining.size
            Spacer(Modifier.height(4.dp))
            Text(
                if (left > 0) {
                    "$what 申請中 ・ 関門があと${left}つ(「変更」で進めます)"
                } else if (pending.pauseMinutes > 0) {
                    "$what 申請中 ・ まもなく止まります"
                } else {
                    "$what 申請中 ・ 「変更」で適用すると止まります"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Row {
                TextButton(onClick = onOpenChanges) { Text("変更を開く") }
                TextButton(onClick = onCancel) { Text("取り下げる") }
            }
        }
    }
}
