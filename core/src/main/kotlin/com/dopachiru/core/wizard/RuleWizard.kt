package com.dopachiru.core.wizard

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.condition.ConditionGroup
import com.dopachiru.core.condition.ConditionRegistry
import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.condition.types.AlwaysCondition
import com.dopachiru.core.condition.types.LinkedRuleCondition
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.OneShotLimit
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.RulePhrase
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.core.preset.PresetTarget
import com.dopachiru.core.preset.RulePreset
import com.dopachiru.core.preset.RulePresets
import java.time.LocalDateTime
import java.time.ZoneId

/** 対話モードの2つの入口。 */
enum class WizardPath {
    /** 困りごとから雛形を選び、雛形の穴を聞く。 */
    PROBLEM,

    /** 止める/残す → 対象 → 条件 → 強さ、を1つずつ選ぶ。 */
    CUSTOM,
}

enum class WizardStep(val title: String) {
    START("何に困っていますか"),
    PRESET("どの縛り方にしますか"),
    TARGET("どれを?"),
    NUMBERS("数字を決める"),
    CONDITIONS("いつ止めますか"),
    STRENGTH("どのくらい強く?"),
    PERIOD("どのくらい続けますか"),
    CONFIRM("これで作りますか"),
}

/** 作るルールの期限。 */
enum class Period(val label: String, val help: String) {
    TRIAL("まず3日だけ試す", "3日後の朝4時に消えます。続けたくなったら一覧で「ずっとにする」"),
    FOREVER("ずっと", "自分で消すまで残ります"),
}

/**
 * 対話モードで答えたこと。画面はこれを持って、1問ずつ書き換える。
 *
 * 前の問いに戻って答えを変えても、**ほかの答えは消さない**(選んだアプリなど)。
 * 戻っただけで選び直しになると、戻るのが怖くなる。消すのは、前提が変わって
 * 意味を失う答えだけ(雛形を替えたら、その雛形の穴の答え)。
 */
data class WizardDraft(
    val path: WizardPath? = null,
    val problem: Problem? = null,
    val presetId: String? = null,
    /** 自分で組み立てるとき、選んだもの**以外**を止める(許可リスト型)。 */
    val keepSelected: Boolean = false,
    val packages: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val sites: Set<String> = emptySet(),
    /** 雛形の穴への答え。無いものは既定値。 */
    val answers: Params = Params.EMPTY,
    /** 自分で組み立てるときの条件。**全部満たしたとき**に止まる。 */
    val conditions: List<ConditionNode.Leaf> = emptyList(),
    /** 選んだ強さ。null なら雛形のまま(自分で組み立てるなら「止める」)。 */
    val strength: Strength? = null,
    val lockoutMinutes: Int? = null,
    val period: Period = Period.TRIAL,
    /** 「今日だけ」の入口から来た。期限は聞かずに今日いっぱい。 */
    val todayOnly: Boolean = false,
) {
    val preset: RulePreset? get() = presetId?.let(RulePresets::byId)
}

/**
 * 対話モードの流れ。画面を持たない ── 何を聞くか・答えから何を作るかだけ。
 *
 * ## 作るのは普通のルール
 *
 * 対話モードは**入口が違うだけ**で、出来上がるのは編集画面で作るのと同じ [Rule]。
 * 対話モード専用の形を作ると、編集画面とずれていき、対話で作ったものを
 * 編集画面で直せなくなる。最後に「細かく直す」で編集画面へそのまま渡せるのも、
 * 同じ形だから。
 *
 * ## 「全部満たしたら」だけ
 *
 * 自分で組み立てる入口は、条件を「さらに絞る」形でしか足せない(AND)。
 * 「どれか満たしたら」(OR)は聞かない ── AND と OR を人に選ばせると、
 * 片方しか使っていないのに取り違えて一度も止まらないルールが出来る。
 * 「別の場合も止めたい」は、もう1本作れば書ける。
 */
object RuleWizard {

    /** お試しの日数。 */
    const val TRIAL_DAYS = 3

    /** いまの答えで進む問いの並び。答えが変わると並びも変わる。 */
    fun steps(draft: WizardDraft): List<WizardStep> = buildList {
        add(WizardStep.START)
        when (draft.path) {
            null -> return@buildList
            WizardPath.PROBLEM -> {
                val problem = draft.problem ?: return@buildList
                if (problem.opensDeadline) return@buildList
                if (problem.presets().size > 1) add(WizardStep.PRESET)
                val preset = draft.preset ?: return@buildList
                if (preset.target != PresetTarget.SITES) add(WizardStep.TARGET)
                if (preset.inputs.isNotEmpty()) add(WizardStep.NUMBERS)
                if (Strength.of(preset.build(draft.packages, draft.answers)) != null) add(WizardStep.STRENGTH)
            }

            WizardPath.CUSTOM -> {
                add(WizardStep.TARGET)
                add(WizardStep.CONDITIONS)
                add(WizardStep.STRENGTH)
            }
        }
        if (!draft.todayOnly) add(WizardStep.PERIOD)
        add(WizardStep.CONFIRM)
    }

    /** その問いを答え終えたか。「次へ」を押せるか。 */
    fun canProceed(step: WizardStep, draft: WizardDraft): Boolean = when (step) {
        WizardStep.START -> when (draft.path) {
            null -> false
            WizardPath.CUSTOM -> true
            WizardPath.PROBLEM -> draft.problem != null && !draft.problem.opensDeadline
        }

        WizardStep.PRESET -> draft.preset != null
        WizardStep.TARGET -> hasTarget(draft)
        WizardStep.CONFIRM -> build(draft, LocalDateTime.now()) != null
        else -> true
    }

    private fun hasTarget(draft: WizardDraft): Boolean {
        val chosen = draft.packages.isNotEmpty() || draft.tags.isNotEmpty() || draft.sites.isNotEmpty()
        return when (draft.path) {
            WizardPath.CUSTOM -> draft.keepSelected || chosen
            WizardPath.PROBLEM -> when (draft.preset?.target) {
                PresetTarget.KEEP, PresetTarget.SITES -> true
                PresetTarget.CHAIN_SOURCE -> draft.packages.isNotEmpty()
                PresetTarget.BLOCK -> chosen
                null -> false
            }

            null -> false
        }
    }

    // ---- 答えの書き換え ------------------------------------------------

    /** 選び直しても、同じものなら答えは消さない(戻って押し直しただけで雛形を選び直しにしない)。 */
    fun chooseProblem(draft: WizardDraft, problem: Problem): WizardDraft =
        if (draft.path == WizardPath.PROBLEM && draft.problem == problem) {
            draft
        } else {
            draft.copy(
                path = WizardPath.PROBLEM,
                problem = problem,
                // 雛形が1つしか無い困りごとは、選ぶ問いを飛ばす
                presetId = problem.presets().singleOrNull()?.id,
                answers = Params.EMPTY,
                strength = null,
                lockoutMinutes = null,
            )
        }

    fun chooseCustom(draft: WizardDraft): WizardDraft =
        if (draft.path == WizardPath.CUSTOM) {
            draft
        } else {
            draft.copy(
                path = WizardPath.CUSTOM,
                problem = null,
                presetId = null,
                answers = Params.EMPTY,
                strength = null,
                lockoutMinutes = null,
            )
        }

    fun choosePreset(draft: WizardDraft, presetId: String): WizardDraft =
        if (draft.presetId == presetId) {
            draft
        } else {
            draft.copy(presetId = presetId, answers = Params.EMPTY, strength = null, lockoutMinutes = null)
        }

    /**
     * 作ったあとの「もう1本」。対象はそのまま持ち越し、雛形だけ替えて始め直す。
     * 困りごとは、勧めた雛形が載っているものに合わせる(雛形を選ぶ問いの並びが合うように)。
     */
    fun startPair(draft: WizardDraft, presetId: String): WizardDraft {
        val problem = Problem.entries.firstOrNull { !it.opensDeadline && presetId in it.ids } ?: draft.problem
        return WizardDraft(
            path = WizardPath.PROBLEM,
            problem = problem,
            presetId = presetId,
            packages = draft.packages,
            tags = draft.tags,
            sites = draft.sites,
            period = draft.period,
            todayOnly = draft.todayOnly,
        )
    }

    /** 作ったあとに勧める雛形。 */
    fun pairsFor(draft: WizardDraft): List<RulePreset> =
        draft.preset?.pairsWith.orEmpty().mapNotNull { RulePresets.byId(it) }

    // ---- 自分で組み立てるときの条件 ------------------------------------

    /**
     * 選べる条件の仲間。「条件なし」は出さない ── 条件を1つも足さなければ、それが条件なし。
     */
    fun conditionGroups(): List<Pair<ConditionGroup, List<ConditionType>>> =
        ConditionRegistry.byGroup()
            .map { (group, types) -> group to types.filter { it.id !in HIDDEN_CONDITIONS } }
            .filter { (group, types) -> group != ConditionGroup.ALWAYS && types.isNotEmpty() }

    fun addCondition(draft: WizardDraft, typeId: String, params: Params): WizardDraft =
        draft.copy(conditions = draft.conditions + ConditionNode.Leaf(typeId, params))

    fun removeCondition(draft: WizardDraft, index: Int): WizardDraft =
        draft.copy(conditions = draft.conditions.filterIndexed { i, _ -> i != index })

    /**
     * 対話モードに出さない条件。
     *
     * - 連動(ほかの端末で効いているあいだ)は、元の条件との「どれか」で足すもの。
     *   「全部満たしたら」の並びに入れると、向こうで効いているときしか効かないルールになる
     * - 条件なしは、何も足さないことで表す
     */
    private val HIDDEN_CONDITIONS = setOf(LinkedRuleCondition.id, AlwaysCondition.id)

    // ---- 組み立て ------------------------------------------------------

    /** 強さの問いで、最初に選んでおく段。 */
    fun defaultStrength(draft: WizardDraft): Strength? = when (draft.path) {
        WizardPath.CUSTOM -> Strength.BLOCK
        WizardPath.PROBLEM -> draft.preset?.let { Strength.of(it.build(draft.packages, draft.answers)) }
        null -> null
    }

    /**
     * 答えからルールを組む。答えが足りなければ null。
     *
     * @param labelOf 名前を付けるときに、パッケージ名を人が読む名前にする
     */
    fun build(
        draft: WizardDraft,
        now: LocalDateTime,
        zone: ZoneId = ZoneId.systemDefault(),
        labelOf: (String) -> String = { it },
    ): Rule? {
        if (!hasTarget(draft)) return null
        val base = when (draft.path) {
            WizardPath.PROBLEM -> buildFromPreset(draft) ?: return null
            WizardPath.CUSTOM -> buildCustom(draft, labelOf)
            null -> return null
        }
        val strong = draft.strength?.let { Strength.apply(base, it, draft.lockoutMinutes) } ?: base
        val expires = when {
            draft.todayOnly -> OneShotLimit.endOfDay(now, zone)
            draft.period == Period.TRIAL -> OneShotLimit.endAfterDays(now, TRIAL_DAYS, zone)
            else -> 0L
        }
        return strong.copy(expiresAtSec = expires)
    }

    private fun buildFromPreset(draft: WizardDraft): Rule? {
        val preset = draft.preset ?: return null
        val rule = preset.build(draft.packages, draft.answers)
        // 雛形はアプリしか受け取らない。対話モードではサイトとタグも選べるので、ここで足す
        val target = when (preset.target) {
            PresetTarget.BLOCK -> rule.target.copy(
                tags = rule.target.tags + draft.tags,
                sites = rule.target.sites + draft.sites,
            )

            PresetTarget.KEEP -> rule.target.copy(
                exceptTags = rule.target.exceptTags + draft.tags,
                exceptSites = rule.target.exceptSites + draft.sites,
            )

            PresetTarget.SITES, PresetTarget.CHAIN_SOURCE -> rule.target
        }
        return rule.copy(target = target)
    }

    private fun buildCustom(draft: WizardDraft, labelOf: (String) -> String): Rule {
        val target = if (draft.keepSelected) {
            Target(
                matchAll = true,
                exceptPackages = draft.packages,
                exceptTags = draft.tags,
                exceptSites = draft.sites,
            )
        } else {
            Target(packages = draft.packages, tags = draft.tags, sites = draft.sites)
        }
        val condition = ConditionNode.AllOf(draft.conditions)
        val base = Rule(
            name = RulePhrase.suggestName(target, condition, labelOf).ifBlank { "名前のないルール" },
            target = target,
            condition = condition,
            actionId = BlockAction.id,
            actionParams = Params.defaultsOf(BlockAction.params),
        )
        // 強さを選ばずに来ても「止める」にしておく(既定の封印の欄は押し切れる)
        return if (draft.strength == null) Strength.apply(base, Strength.BLOCK) else base
    }
}
