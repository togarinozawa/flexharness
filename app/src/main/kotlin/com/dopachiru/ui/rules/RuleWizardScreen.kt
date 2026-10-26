package com.dopachiru.ui.rules

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.dopachiru.core.condition.ConditionGroup
import com.dopachiru.core.condition.ConditionRegistry
import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.gate.ChangeKind
import com.dopachiru.core.model.OneShotLimit
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.RuleOverlap
import com.dopachiru.core.model.RulePhrase
import com.dopachiru.core.model.SitePattern
import com.dopachiru.core.model.SiteCatalog
import com.dopachiru.core.param.Params
import com.dopachiru.core.preset.PresetTarget
import com.dopachiru.core.preset.RulePreset
import com.dopachiru.core.wizard.Period
import com.dopachiru.core.wizard.Problem
import com.dopachiru.core.wizard.RuleWizard
import com.dopachiru.core.wizard.Strength
import com.dopachiru.core.wizard.WizardDraft
import com.dopachiru.core.wizard.WizardPath
import com.dopachiru.core.wizard.WizardStep
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.ui.common.AppPickerList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 対話モードから編集画面へ、作りかけのルールを渡す。
 *
 * 画面の行き来の引数にルールを丸ごと載せると、長い反省文で上限を超える。
 * 渡すのは1回きりなので、置いて・取ったら消す。
 * アプリが落ちて消えたら、編集画面は空から始まるだけ(作りかけを失うが、壊れはしない)。
 */
object RuleDrafts {
    @Volatile
    private var pending: Rule? = null

    fun put(rule: Rule) {
        pending = rule
    }

    fun take(): Rule? = pending.also { pending = null }
}

class RuleWizardViewModel(app: Application) : AndroidViewModel(app) {
    private val _draft = MutableStateFlow(WizardDraft())
    val draft: StateFlow<WizardDraft> = _draft.asStateFlow()

    /**
     * いまの問い。番号ではなく問いそのものを持つ ── 答えによって並びが変わる
     * (雛形が1つしか無ければ「どれにする?」が消える)ので、番号だとずれる。
     */
    private val _step = MutableStateFlow(WizardStep.START)
    val step: StateFlow<WizardStep> = _step.asStateFlow()

    /** 作ったルール。入っていれば「作りました」の画面を出す。 */
    private val _created = MutableStateFlow<Rule?>(null)
    val created: StateFlow<Rule?> = _created.asStateFlow()

    /** 作ったときの答え。「もう1本」で対象を持ち越すため。 */
    private var lastDraft: WizardDraft? = null

    val tags: StateFlow<List<String>> = DopaRuntime.rules.tags
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allRules: StateFlow<List<Rule>> = DopaRuntime.rules.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var started = false

    fun start(today: Boolean) {
        if (started) return
        started = true
        _draft.value = WizardDraft(todayOnly = today)
    }

    fun update(transform: (WizardDraft) -> WizardDraft) = _draft.update(transform)

    fun next() {
        val steps = RuleWizard.steps(_draft.value)
        val index = steps.indexOf(_step.value)
        steps.getOrNull(index + 1)?.let { _step.value = it }
    }

    fun back() {
        val steps = RuleWizard.steps(_draft.value)
        val index = steps.indexOf(_step.value)
        _step.value = steps.getOrNull(index - 1) ?: WizardStep.START
    }

    /** 選んだらそのまま次の問いへ。1つ選ぶだけの問いで「次へ」を押させない。 */
    fun chooseAndNext(transform: (WizardDraft) -> WizardDraft) {
        _draft.update(transform)
        next()
    }

    fun create(labelOf: (String) -> String) {
        val current = _draft.value
        val rule = RuleWizard.build(current, LocalDateTime.now(), labelOf = labelOf) ?: return
        viewModelScope.launch {
            // 新しく作るのは縛りを増やす側。関門は通さず、すぐ効かせる(編集画面と同じ)
            DopaRuntime.changes.request(ChangeKind.CREATE, rule, emptyList())
            lastDraft = current
            _created.value = rule
        }
    }

    /** 「細かく直す」。保存はせず、編集画面に渡す。 */
    fun handOff(labelOf: (String) -> String): Boolean {
        val rule = RuleWizard.build(_draft.value, LocalDateTime.now(), labelOf = labelOf) ?: return false
        RuleDrafts.put(rule)
        return true
    }

    fun again() {
        val today = _draft.value.todayOnly
        _created.value = null
        _draft.value = WizardDraft(todayOnly = today)
        _step.value = WizardStep.START
    }

    fun startPair(presetId: String) {
        val base = lastDraft ?: _draft.value
        _created.value = null
        _draft.value = RuleWizard.startPair(base, presetId)
        // 雛形はもう決まっているので、その次の問いから
        val steps = RuleWizard.steps(_draft.value)
        _step.value = steps.firstOrNull { it != WizardStep.START && it != WizardStep.PRESET } ?: WizardStep.CONFIRM
    }
}

/**
 * 質問に答えてルールを作る画面。
 *
 * ## なぜ編集画面とは別に要るのか
 *
 * 編集画面は部品(対象・条件・措置・逃げ道)を並べて組ませる。
 * 部品の名前を知っている人には速いが、困っている最中の人は部品ではなく
 * **困りごと**から考えている。ここでは1画面に1問だけ出して、答えを部品に
 * 翻訳するのはこちらでやる。
 *
 * 入口は2つ ── 困りごとから雛形を選ぶか、止める/残す・対象・条件を自分で選ぶか。
 * どちらで作っても普通のルールになり、最後に編集画面で細かく直せる。
 */
@Composable
fun RuleWizardScreen(
    today: Boolean,
    onDone: () -> Unit,
    onEditDraft: () -> Unit,
    onOpenDeadline: () -> Unit,
    viewModel: RuleWizardViewModel = viewModel(),
) {
    LaunchedEffect(today) { viewModel.start(today) }
    val draft by viewModel.draft.collectAsState()
    val step by viewModel.step.collectAsState()
    val created by viewModel.created.collectAsState()
    val context = LocalContext.current
    val labelOf: (String) -> String = { InstalledApps.labelOf(context, it) }

    // 端末の「戻る」は1問戻る。対話の途中で画面ごと閉じると、それまでの答えが全部消える
    BackHandler(enabled = created == null && step != WizardStep.START) { viewModel.back() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (today) "今日だけのルールを作る" else "質問に答えて作る",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "やめる") }
        }

        val done = created
        if (done != null) {
            DoneStep(
                rule = done,
                pairs = RuleWizard.pairsFor(draft),
                onPair = viewModel::startPair,
                onAgain = viewModel::again,
                onClose = onDone,
            )
            return@Column
        }

        val steps = RuleWizard.steps(draft)
        val index = steps.indexOf(step).coerceAtLeast(0)
        // 並びは答えるほど伸びる(雛形を選ぶまで、何問あるか決まらない)。
        // 分母が変わる進み具合は嘘になるので、決まっているぶんだけで描く
        LinearProgressIndicator(
            progress = { (index + 1f) / steps.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(step.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            when (step) {
                WizardStep.START -> StartStep(
                    draft = draft,
                    onProblem = { problem ->
                        if (problem.opensDeadline) onOpenDeadline()
                        else viewModel.chooseAndNext { RuleWizard.chooseProblem(it, problem) }
                    },
                    onCustom = { viewModel.chooseAndNext(RuleWizard::chooseCustom) },
                )

                WizardStep.PRESET -> PresetStep(
                    draft = draft,
                    onPick = { id -> viewModel.chooseAndNext { RuleWizard.choosePreset(it, id) } },
                )

                WizardStep.TARGET -> TargetStep(draft, viewModel)
                WizardStep.NUMBERS -> NumbersStep(draft, viewModel)
                WizardStep.CONDITIONS -> ConditionsStep(draft, viewModel)
                WizardStep.STRENGTH -> StrengthStep(draft, viewModel)
                WizardStep.PERIOD -> PeriodStep(
                    draft = draft,
                    onPick = { period -> viewModel.chooseAndNext { it.copy(period = period) } },
                )

                WizardStep.CONFIRM -> ConfirmStep(draft, viewModel, labelOf)
            }
            Spacer(Modifier.height(24.dp))
        }

        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step != WizardStep.START) {
                OutlinedButton(onClick = viewModel::back) { Text("戻る") }
            }
            Spacer(Modifier.weight(1f))
            if (step == WizardStep.CONFIRM) {
                TextButton(onClick = { if (viewModel.handOff(labelOf)) onEditDraft() }) { Text("細かく直す") }
                Button(
                    onClick = { viewModel.create(labelOf) },
                    enabled = RuleWizard.canProceed(step, draft),
                ) { Text("作る") }
            } else if (step != WizardStep.START) {
                Button(onClick = viewModel::next, enabled = RuleWizard.canProceed(step, draft)) { Text("次へ") }
            }
        }
    }
}

// ---- 1. 何に困っている -----------------------------------------------------

@Composable
private fun StartStep(draft: WizardDraft, onProblem: (Problem) -> Unit, onCustom: () -> Unit) {
    Problem.entries.forEach { problem ->
        ChoiceCard(
            title = problem.label,
            body = problem.help,
            selected = draft.path == WizardPath.PROBLEM && draft.problem == problem,
            onClick = { onProblem(problem) },
        )
    }
    Spacer(Modifier.height(16.dp))
    Text(
        "部品を自分で選びたいとき",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    ChoiceCard(
        title = "自分で組み立てる",
        body = "止める/残す → 対象 → 条件 → 強さ を1つずつ選ぶ",
        selected = draft.path == WizardPath.CUSTOM,
        onClick = onCustom,
    )
}

// ---- 2. どの縛り方 ---------------------------------------------------------

@Composable
private fun PresetStep(draft: WizardDraft, onPick: (String) -> Unit) {
    val problem = draft.problem ?: return
    Text(
        problem.label + "。上ほど軽く、下ほど強いやり方です。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    problem.presets().forEach { preset ->
        ChoiceCard(
            title = preset.name,
            body = preset.description,
            note = preset.evidence,
            badge = Strength.describe(preset.build(emptySet())),
            selected = draft.presetId == preset.id,
            onClick = { onPick(preset.id) },
        )
    }
}

// ---- 3. どれを -------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetStep(draft: WizardDraft, viewModel: RuleWizardViewModel) {
    val context = LocalContext.current
    val preset = draft.preset
    val kind = if (draft.path == WizardPath.CUSTOM) {
        if (draft.keepSelected) PresetTarget.KEEP else PresetTarget.BLOCK
    } else {
        preset?.target ?: PresetTarget.BLOCK
    }

    if (draft.path == WizardPath.CUSTOM) {
        // 許可リストかどうかを、言葉の上では聞かない。「止めるものを選ぶ」か
        // 「残すものを選ぶ」かなら、仕組みを知らなくても答えられる
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !draft.keepSelected,
                onClick = { viewModel.update { it.copy(keepSelected = false) } },
                label = { Text("止めるものを選ぶ") },
            )
            FilterChip(
                selected = draft.keepSelected,
                onClick = { viewModel.update { it.copy(keepSelected = true) } },
                label = { Text("残すものを選ぶ") },
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    Text(
        when (kind) {
            PresetTarget.KEEP -> if (draft.path == WizardPath.CUSTOM) {
                "選んだもの以外を全部止めます。電話・ホーム・設定は選ばなくても使えます。"
            } else {
                preset?.appPrompt.orEmpty()
            }

            PresetTarget.CHAIN_SOURCE -> preset?.appPrompt.orEmpty().replace("**", "")
            else -> "止めたいアプリ・サイトを選んでください。使った時間の多い順に並んでいます。"
        },
        style = MaterialTheme.typography.bodyMedium,
    )

    val chosen = draft.packages.map { InstalledApps.labelOf(context, it) } +
        draft.tags.map { "#$it" } + draft.sites.map { SitePattern.normalize(it) }
    if (chosen.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            "選択中: " + if (chosen.size <= 4) chosen.joinToString("、") else chosen.take(3).joinToString("、") + " ほか${chosen.size - 3}件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Spacer(Modifier.height(8.dp))

    AppPickerList(
        selected = draft.packages,
        onToggle = { pkg ->
            viewModel.update { it.copy(packages = if (pkg in it.packages) it.packages - pkg else it.packages + pkg) }
        },
        maxHeight = 360.dp,
    )

    // 連鎖の入口はアプリしか取れない。サイトやタグを足しても入口にならない
    if (kind == PresetTarget.CHAIN_SOURCE) return

    val tags by viewModel.tags.collectAsState()
    if (tags.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text("タグ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { tag ->
                FilterChip(
                    selected = tag in draft.tags,
                    onClick = {
                        viewModel.update { it.copy(tags = if (tag in it.tags) it.tags - tag else it.tags + tag) }
                    },
                    label = { Text("#$tag") },
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Text("サイト", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    SitesEditor(
        sites = draft.sites,
        onAdd = { raw ->
            val normalized = SitePattern.normalize(raw)
            if (SitePattern.isValid(normalized)) viewModel.update { it.copy(sites = it.sites + normalized) }
        },
        onRemove = { site -> viewModel.update { it.copy(sites = it.sites - site) } },
        onToggleGroup = { id ->
            val group = SiteCatalog.byId(id) ?: return@SitesEditor
            val all = group.patterns.toSet()
            viewModel.update { it.copy(sites = if (it.sites.containsAll(all)) it.sites - all else it.sites + all) }
        },
    )
}

// ---- 4. 数字 ---------------------------------------------------------------

@Composable
private fun NumbersStep(draft: WizardDraft, viewModel: RuleWizardViewModel) {
    val preset = draft.preset ?: return
    Text(
        "「${preset.name}」の数字です。そのままでも作れます。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    ParamEditor(
        specs = preset.inputs,
        params = withDefaults(preset, draft.answers),
        onChange = { params -> viewModel.update { it.copy(answers = params) } },
    )
}

private fun withDefaults(preset: RulePreset, answers: Params): Params =
    Params(kotlinx.serialization.json.JsonObject(Params.defaultsOf(preset.inputs).json + answers.json))

// ---- 5. いつ(自分で組み立てる) ---------------------------------------------

@Composable
private fun ConditionsStep(draft: WizardDraft, viewModel: RuleWizardViewModel) {
    var group by remember { mutableStateOf<ConditionGroup?>(null) }
    var type by remember { mutableStateOf<ConditionType?>(null) }
    var params by remember { mutableStateOf(Params.EMPTY) }

    Text(
        if (draft.conditions.isEmpty()) {
            "条件を足さなければ、いつでも止めます。"
        } else {
            "ここに並んだ条件を全部満たしたときに止めます。"
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(8.dp))

    draft.conditions.forEachIndexed { index, leaf ->
        val known = ConditionRegistry[leaf.typeId]
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (index > 0) {
                        Text("かつ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(known?.displayName ?: leaf.typeId, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        runCatching { known?.summarize(leaf.params) }.getOrNull().orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { viewModel.update { RuleWizard.removeCondition(it, index) } }) { Text("外す") }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    val pickingType = type
    val pickingGroup = group
    when {
        pickingType != null -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(pickingType.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        pickingType.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    ParamEditor(specs = pickingType.params, params = params, onChange = { params = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { type = null }) { Text("ほかの条件") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = {
                            viewModel.update { RuleWizard.addCondition(it, pickingType.id, params) }
                            type = null
                            group = null
                        }) { Text("足す") }
                    }
                }
            }
        }

        pickingGroup != null -> {
            Text(pickingGroup.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            RuleWizard.conditionGroups().firstOrNull { it.first == pickingGroup }?.second.orEmpty().forEach { candidate ->
                ChoiceCard(
                    title = candidate.displayName,
                    body = candidate.example.ifBlank { candidate.description },
                    selected = false,
                    onClick = {
                        type = candidate
                        params = Params.defaultsOf(candidate.params)
                    },
                )
            }
            TextButton(onClick = { group = null }) { Text("戻る") }
        }

        else -> {
            Text(
                if (draft.conditions.isEmpty()) "条件を足す" else "さらに絞る",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            RuleWizard.conditionGroups().forEach { (candidate, _) ->
                ChoiceCard(
                    title = candidate.label,
                    body = candidate.summary,
                    selected = false,
                    onClick = { group = candidate },
                )
            }
        }
    }
}

// ---- 6. 強さ ---------------------------------------------------------------

@Composable
private fun StrengthStep(draft: WizardDraft, viewModel: RuleWizardViewModel) {
    val chosen = draft.strength ?: RuleWizard.defaultStrength(draft)
    Text(
        "上ほど軽く、下ほど強くなります。強いものから始めると、たいてい続かないか、目標のほうを緩めることになります。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Strength.entries.forEach { strength ->
        ChoiceCard(
            title = strength.label,
            body = strength.help,
            selected = chosen == strength,
            onClick = { viewModel.update { it.copy(strength = strength) } },
        )
    }
    if (chosen == Strength.LOCKOUT) {
        val base = RuleWizard.build(draft.copy(strength = null), LocalDateTime.now())
        val minutes = draft.lockoutMinutes ?: base?.let { Strength.suggestedLockoutMinutes(it) } ?: Strength.DEFAULT_LOCKOUT_MINUTES
        Spacer(Modifier.height(8.dp))
        Text("閉め出す長さ", style = MaterialTheme.typography.labelLarge)
        NumberStepper(
            value = minutes,
            min = 1,
            max = com.dopachiru.core.action.types.LockoutAction.MAX_MINUTES,
            step = 5,
            suffix = "分",
            onChange = { value -> viewModel.update { it.copy(strength = Strength.LOCKOUT, lockoutMinutes = value) } },
        )
    }
}

// ---- 7. 期限 ---------------------------------------------------------------

@Composable
private fun PeriodStep(draft: WizardDraft, onPick: (Period) -> Unit) {
    Text(
        "ずっと守れるかを、作る前に決めなくていい。試して合わなければ、そのまま消えます。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Period.entries.forEach { period ->
        ChoiceCard(
            title = period.label,
            body = period.help,
            selected = draft.period == period,
            onClick = { onPick(period) },
        )
    }
}

// ---- 8. 確認 ---------------------------------------------------------------

@Composable
private fun ConfirmStep(draft: WizardDraft, viewModel: RuleWizardViewModel, labelOf: (String) -> String) {
    val rule = RuleWizard.build(draft, LocalDateTime.now(), labelOf = labelOf)
    if (rule == null) {
        Text("まだ決まっていないところがあります。戻って選んでください。")
        return
    }
    Text(rule.name, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            RulePhrase.of(rule.target, rule.condition, rule.actionId, rule.actionParams, labelOf),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    InfoLine("強さ", Strength.describe(rule))
    InfoLine("期限", expiryText(rule.expiresAtSec))

    // 同じ相手を狙うルールがあるなら、どれが勝つかを先に見せる。
    // 作ってから「なぜこっちが出ないのか」になるのを防ぐ
    val all by viewModel.allRules.collectAsState()
    val siblings = remember(rule, all) { RuleOverlap.siblingsOf(rule, all).filter { it.enabled } }
    if (siblings.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            "同じアプリ・サイトに効いているルールがあります。同時に当てはまると、強いほうが出ます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        siblings.take(4).forEach { other ->
            Text(
                "・${other.name}(${Strength.describe(other)})",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun expiryText(expiresAtSec: Long): String {
    if (expiresAtSec <= 0L) return "ずっと"
    val at = Instant.ofEpochSecond(expiresAtSec).atZone(ZoneId.systemDefault())
    val end = at.format(DateTimeFormatter.ofPattern("M/d H:mm"))
    return if (expiresAtSec <= OneShotLimit.endOfDay(LocalDateTime.now())) "今日いっぱい($end に消える)" else "$end に消える"
}

// ---- 作ったあと ------------------------------------------------------------

@Composable
private fun DoneStep(
    rule: Rule,
    pairs: List<RulePreset>,
    onPair: (String) -> Unit,
    onAgain: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("作りました", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("「${rule.name}」はもう効いています。", style = MaterialTheme.typography.bodyMedium)
        if (rule.isTemporary) {
            Text(
                expiryText(rule.expiresAtSec) + "。続けたくなったら、ルールの一覧で「ずっとにする」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (pairs.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("合わせて作ると効くもの", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Text(
                "片方だけ塞ぐと、もう片方へ逃げます。選んだ対象はそのまま引き継ぎます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            pairs.forEach { preset ->
                ChoiceCard(
                    title = preset.name,
                    body = preset.description,
                    selected = false,
                    onClick = { onPair(preset.id) },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAgain) { Text("もう1本作る") }
            Button(onClick = onClose) { Text("閉じる") }
        }
    }
}

// ---- 部品 ------------------------------------------------------------------

/** 1つ選ぶ問いの選択肢。選んだものだけ縁取る。 */
@Composable
private fun ChoiceCard(
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
    note: String = "",
    badge: String = "",
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (badge.isNotBlank()) {
                    Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (body.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    body.replace("**", ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    note.replace("**", ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
        }
    }
}
