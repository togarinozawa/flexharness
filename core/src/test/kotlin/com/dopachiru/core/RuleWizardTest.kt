package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DelayAction
import com.dopachiru.core.action.types.LockoutAction
import com.dopachiru.core.action.types.RadioAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.condition.ConditionGroup
import com.dopachiru.core.condition.types.DayOfWeekCondition
import com.dopachiru.core.condition.types.LinkedRuleCondition
import com.dopachiru.core.condition.types.TimeRangeCondition
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.OneShotLimit
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import com.dopachiru.core.preset.PresetKeys
import com.dopachiru.core.preset.RulePresets
import com.dopachiru.core.wizard.Period
import com.dopachiru.core.wizard.Problem
import com.dopachiru.core.wizard.RuleWizard
import com.dopachiru.core.wizard.Strength
import com.dopachiru.core.wizard.WizardDraft
import com.dopachiru.core.wizard.WizardPath
import com.dopachiru.core.wizard.WizardStep
import org.junit.Before
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 対話モード。肝は3つ。
 *  1. 答えたとおりのルールが出来る(雛形の穴・対象・強さ・期限)
 *  2. 聞かなくていいことは聞かない(雛形が1つなら選ばせない、サイト固定なら対象を聞かない)
 *  3. 雛形の穴はどれも、出来上がりに効いている(聞いたのに無視、をしない)
 */
class RuleWizardTest {

    private val zone = ZoneId.of("Asia/Tokyo")
    private val now = LocalDateTime.of(2026, 10, 23, 21, 0)
    private val youtube = "com.google.android.youtube"

    @Before
    fun setUp() = DopaCore.registerAll()

    // ---- 雛形の穴 ------------------------------------------------------

    @Test
    fun `雛形の穴はどれも出来上がりに効く`() {
        RulePresets.all.forEach { preset ->
            val base = preset.build(setOf(youtube))
            preset.inputs.forEach { spec ->
                val changed = preset.build(setOf(youtube), Params.of(spec.key to otherValue(spec)))
                assertNotEquals(base, changed, "${preset.id}: 「${spec.label}」を変えても出来上がりが同じ")
            }
        }
    }

    @Test
    fun `雛形の穴の名前は雛形の中で重ならない`() {
        RulePresets.all.forEach { preset ->
            val keys = preset.inputs.map { it.key }
            assertEquals(keys.size, keys.toSet().size, "${preset.id}: $keys")
        }
    }

    @Test
    fun `穴に答えると数字がルールの名前にも入る`() {
        val rule = RulePresets.byId("forced_break")!!.build(
            setOf(youtube),
            Params.of(PresetKeys.MINUTES to 45, PresetKeys.BREAK_MINUTES to 15),
        )
        assertEquals("45分使ったら15分休む", rule.name)
        assertEquals(15, rule.actionParams.int(LockoutAction.KEY_MINUTES))
    }

    @Test
    fun `勧める雛形はどれも実在する`() {
        val ids = RulePresets.all.map { it.id }.toSet()
        RulePresets.all.forEach { preset ->
            preset.pairsWith.forEach { assertTrue(it in ids, "${preset.id} → $it が無い") }
            assertFalse(preset.id in preset.pairsWith, "${preset.id}: 自分を勧めている")
        }
    }

    // ---- 困りごと ------------------------------------------------------

    @Test
    fun `困りごとの並びは実在する雛形だけを指す`() {
        Problem.entries.forEach { problem ->
            problem.ids.forEach { id ->
                assertTrue(id in RulePresets.definedIds, "${problem.name}: $id という雛形は無い")
            }
            assertEquals(problem.ids.size, problem.ids.toSet().size, "${problem.name}: 重複")
        }
    }

    @Test
    fun `選べる雛形はどれもいずれかの困りごとから辿れる`() {
        val reachable = Problem.entries.flatMap { it.presets() }.map { it.id }.toSet()
        RulePresets.all.forEach { preset ->
            assertTrue(preset.id in reachable, "${preset.id} にどの困りごとからも辿り着けない")
        }
    }

    @Test
    fun `締切だけは雛形を出さずに締切の画面へ送る`() {
        assertTrue(Problem.DEADLINE.opensDeadline)
        assertTrue(Problem.DEADLINE.presets().isEmpty())
        val draft = RuleWizard.chooseProblem(WizardDraft(), Problem.DEADLINE)
        assertFalse(RuleWizard.canProceed(WizardStep.START, draft))
        assertEquals(listOf(WizardStep.START), RuleWizard.steps(draft))
    }

    // ---- 問いの並び ----------------------------------------------------

    @Test
    fun `雛形が1つしかない困りごとは雛形を選ばせない`() {
        val draft = RuleWizard.chooseProblem(WizardDraft(), Problem.TOTAL)
        assertEquals("always_block", draft.presetId)
        assertFalse(WizardStep.PRESET in RuleWizard.steps(draft))
    }

    @Test
    fun `サイトが決まっている雛形は対象を聞かない`() {
        val draft = RuleWizard.choosePreset(RuleWizard.chooseProblem(WizardDraft(), Problem.FEED), "site_shorts")
        val steps = RuleWizard.steps(draft)
        assertFalse(WizardStep.TARGET in steps)
        assertTrue(RuleWizard.canProceed(WizardStep.CONFIRM, draft))
    }

    @Test
    fun `穴の無い雛形は数字を聞かず、段に載らない措置は強さを聞かない`() {
        val draft = RuleWizard.choosePreset(RuleWizard.chooseProblem(WizardDraft(), Problem.FEED), "youtube_radio")
        val steps = RuleWizard.steps(draft)
        assertFalse(WizardStep.NUMBERS in steps)
        assertFalse(WizardStep.STRENGTH in steps, "音だけは強さではなく形の違い")
    }

    @Test
    fun `今日だけの入口からは期限を聞かない`() {
        val draft = RuleWizard.chooseProblem(WizardDraft(todayOnly = true), Problem.TOTAL)
        assertFalse(WizardStep.PERIOD in RuleWizard.steps(draft))
        val rule = RuleWizard.build(draft.copy(packages = setOf(youtube)), now, zone)!!
        assertEquals(OneShotLimit.endOfDay(now, zone), rule.expiresAtSec)
    }

    @Test
    fun `自分で組み立てる入口は対象・条件・強さを順に聞く`() {
        val steps = RuleWizard.steps(RuleWizard.chooseCustom(WizardDraft()))
        assertEquals(
            listOf(
                WizardStep.START,
                WizardStep.TARGET,
                WizardStep.CONDITIONS,
                WizardStep.STRENGTH,
                WizardStep.PERIOD,
                WizardStep.CONFIRM,
            ),
            steps,
        )
    }

    @Test
    fun `止めるものを選ばないと先に進めない`() {
        val draft = RuleWizard.chooseProblem(WizardDraft(), Problem.TOTAL)
        assertFalse(RuleWizard.canProceed(WizardStep.TARGET, draft))
        assertNull(RuleWizard.build(draft, now, zone))
        assertTrue(RuleWizard.canProceed(WizardStep.TARGET, draft.copy(sites = setOf("x.com"))))
    }

    @Test
    fun `残すものを選ぶ型は空でも進める`() {
        // 電話・ホーム・設定は選ばなくても必ず使える。何も残さないのも選択
        val custom = RuleWizard.chooseCustom(WizardDraft()).copy(keepSelected = true)
        assertTrue(RuleWizard.canProceed(WizardStep.TARGET, custom))
        val study = RuleWizard.choosePreset(RuleWizard.chooseProblem(WizardDraft(), Problem.WORK), "study_session")
        assertTrue(RuleWizard.canProceed(WizardStep.TARGET, study))
    }

    // ---- 組み立て ------------------------------------------------------

    @Test
    fun `困りごとから作ると雛形の穴と対象と強さが入る`() {
        val draft = RuleWizard.choosePreset(RuleWizard.chooseProblem(WizardDraft(), Problem.NIGHT), "night")
            .copy(
                packages = setOf(youtube),
                sites = setOf("x.com"),
                answers = Params.of(PresetKeys.START to 21 * 60),
                strength = Strength.HARD_BLOCK,
                period = Period.FOREVER,
            )
        val rule = RuleWizard.build(draft, now, zone)!!

        assertEquals(setOf(youtube), rule.target.packages)
        assertEquals(setOf("x.com"), rule.target.sites, "雛形はアプリしか受け取らないが、サイトも足す")
        val range = (rule.condition as ConditionNode.AllOf).children.single() as ConditionNode.Leaf
        assertEquals(TimeRangeCondition.id, range.typeId)
        assertEquals(21 * 60, range.params.int(TimeRangeCondition.KEY_START))
        assertEquals(6 * 60, range.params.int(TimeRangeCondition.KEY_END), "答えていない穴は既定値")
        assertEquals(BlockAction.id, rule.actionId)
        assertFalse(rule.actionParams.bool(BlockAction.KEY_ALLOW_OVERRIDE, true))
        assertEquals(0L, rule.expiresAtSec)
    }

    @Test
    fun `残す型の雛形ではタグとサイトも残す側に入る`() {
        val draft = RuleWizard.choosePreset(RuleWizard.chooseProblem(WizardDraft(), Problem.WORK), "study_session")
            .copy(packages = setOf("dict"), tags = setOf("勉強"), sites = setOf("wikipedia.org"))
        val rule = RuleWizard.build(draft, now, zone)!!
        assertTrue(rule.target.matchAll)
        assertEquals(setOf("dict"), rule.target.exceptPackages)
        assertEquals(setOf("勉強"), rule.target.exceptTags)
        assertEquals(setOf("wikipedia.org"), rule.target.exceptSites)
    }

    @Test
    fun `連鎖の雛形では選んだアプリが入口になり、止める先は穴で選ぶ`() {
        val draft = RuleWizard.choosePreset(RuleWizard.chooseProblem(WizardDraft(), Problem.OPENING), "app_chain")
            .copy(packages = setOf("line"), answers = Params.of(PresetKeys.CHAIN_TARGET to listOf(youtube)))
        val rule = RuleWizard.build(draft, now, zone)!!
        assertEquals(setOf(youtube), rule.target.packages)
    }

    @Test
    fun `自分で組み立てると条件は全部満たしたときだけになる`() {
        var draft = RuleWizard.chooseCustom(WizardDraft()).copy(packages = setOf(youtube))
        draft = RuleWizard.addCondition(draft, DayOfWeekCondition.id, Params.of(DayOfWeekCondition.KEY_DAYS to listOf(6, 7)))
        draft = RuleWizard.addCondition(
            draft,
            TimeRangeCondition.id,
            Params.of(TimeRangeCondition.KEY_START to 22 * 60, TimeRangeCondition.KEY_END to 6 * 60),
        )
        val rule = RuleWizard.build(draft.copy(period = Period.FOREVER), now, zone)!!

        val all = rule.condition as ConditionNode.AllOf
        assertEquals(listOf(DayOfWeekCondition.id, TimeRangeCondition.id), all.children.map { (it as ConditionNode.Leaf).typeId })
        assertEquals(BlockAction.id, rule.actionId, "強さを選ばなければ「止める」")
        assertTrue(rule.actionParams.bool(BlockAction.KEY_ALLOW_OVERRIDE, false))
        assertTrue(rule.name.isNotBlank())

        val removed = RuleWizard.removeCondition(draft, 0)
        assertEquals(listOf(TimeRangeCondition.id), removed.conditions.map { it.typeId })
    }

    @Test
    fun `自分で組み立てる入口で選んだもの以外を止められる`() {
        val draft = RuleWizard.chooseCustom(WizardDraft()).copy(keepSelected = true, packages = setOf("dict"))
        val rule = RuleWizard.build(draft, now, zone)!!
        assertTrue(rule.target.matchAll)
        assertEquals(setOf("dict"), rule.target.exceptPackages)
    }

    @Test
    fun `条件の選択肢に連動と条件なしは出さない`() {
        val ids = RuleWizard.conditionGroups().flatMap { (_, types) -> types.map { it.id } }
        assertFalse(LinkedRuleCondition.id in ids, "連動は「どれか」でしか意味を持たない")
        assertFalse("always" in ids)
        assertFalse(RuleWizard.conditionGroups().any { it.first == ConditionGroup.ALWAYS })
        assertTrue(ids.isNotEmpty())
    }

    @Test
    fun `お試しは3日後の朝4時に消える`() {
        val draft = RuleWizard.chooseProblem(WizardDraft(), Problem.TOTAL).copy(packages = setOf(youtube))
        val rule = RuleWizard.build(draft, now, zone)!!
        val expected = LocalDateTime.of(2026, 10, 26, 4, 0).atZone(zone).toEpochSecond()
        assertEquals(expected, rule.expiresAtSec)
        assertEquals(3, OneShotLimit.daysLeft(rule.expiresAtSec, now, zone))
        assertEquals(1, OneShotLimit.daysLeft(rule.expiresAtSec, LocalDateTime.of(2026, 10, 25, 12, 0), zone))
        assertEquals(0, OneShotLimit.daysLeft(rule.expiresAtSec, LocalDateTime.of(2026, 10, 26, 5, 0), zone))
    }

    @Test
    fun `お試しのルールは最後の日だけ今日だけの枠に並ぶ`() {
        val draft = RuleWizard.chooseProblem(WizardDraft(), Problem.TOTAL).copy(packages = setOf(youtube))
        val rule = RuleWizard.build(draft, now, zone)!!
        assertFalse(OneShotLimit.endsToday(rule, now, zone))
        assertTrue(OneShotLimit.endsToday(rule, LocalDateTime.of(2026, 10, 25, 12, 0), zone))
    }

    @Test
    fun `もう1本は対象を持ち越して雛形だけ替える`() {
        val first = RuleWizard.choosePreset(RuleWizard.chooseProblem(WizardDraft(), Problem.FEED), "shorts_in_app")
            .copy(packages = setOf(youtube), strength = Strength.HARD_BLOCK)
        assertEquals(listOf("site_shorts"), RuleWizard.pairsFor(first).map { it.id })

        val next = RuleWizard.startPair(first, "site_shorts")
        assertEquals("site_shorts", next.presetId)
        assertEquals(setOf(youtube), next.packages)
        assertNull(next.strength, "強さは雛形ごとに選び直す")
        assertNotNull(RuleWizard.build(next, now, zone))
    }

    @Test
    fun `雛形を替えると前の雛形の穴の答えは消える`() {
        val draft = RuleWizard.choosePreset(RuleWizard.chooseProblem(WizardDraft(), Problem.LONG), "forced_break")
            .copy(answers = Params.of(PresetKeys.MINUTES to 45), packages = setOf(youtube))
        val switched = RuleWizard.choosePreset(draft, "daily_budget")
        assertEquals(Params.EMPTY, switched.answers)
        assertEquals(setOf(youtube), switched.packages, "選んだアプリは消さない")
        assertEquals(draft, RuleWizard.choosePreset(draft, "forced_break"), "同じ雛形を選び直しても消さない")
    }

    @Test
    fun `同じ困りごとを押し直しても答えは消えない`() {
        val draft = RuleWizard.choosePreset(RuleWizard.chooseProblem(WizardDraft(), Problem.LONG), "forced_break")
            .copy(answers = Params.of(PresetKeys.MINUTES to 45), strength = Strength.LOCKOUT)
        assertEquals(draft, RuleWizard.chooseProblem(draft, Problem.LONG))
        assertNull(RuleWizard.chooseProblem(draft, Problem.NIGHT).presetId, "別の困りごとなら雛形は選び直し")

        val custom = RuleWizard.addCondition(RuleWizard.chooseCustom(WizardDraft()), DayOfWeekCondition.id, Params.EMPTY)
            .copy(strength = Strength.PAUSE)
        assertEquals(custom, RuleWizard.chooseCustom(custom))
    }

    // ---- 強さ ----------------------------------------------------------

    @Test
    fun `強さは同じ段なら雛形の調整を残す`() {
        val rule = RulePresets.byId("quick_reopen")!!.build(setOf(youtube))
        assertEquals(Strength.BLOCK, Strength.of(rule))
        assertEquals(rule, Strength.apply(rule, Strength.BLOCK))
    }

    @Test
    fun `強さを変えても対象と条件は変わらず、言葉は持ち越す`() {
        val rule = RulePresets.byId("night")!!.build(setOf(youtube))
        val reflection = rule.actionParams.string(BlockAction.KEY_REFLECTION)
        Strength.entries.forEach { strength ->
            val changed = Strength.apply(rule, strength)
            assertEquals(strength, Strength.of(changed), strength.name)
            assertEquals(rule.target, changed.target)
            assertEquals(rule.condition, changed.condition)
        }
        val pause = Strength.apply(rule, Strength.PAUSE)
        assertEquals(DelayAction.id, pause.actionId)
        assertEquals(reflection, pause.actionParams.string(DelayAction.KEY_MESSAGE))
        val notice = Strength.apply(rule, Strength.NOTICE)
        assertEquals(WarnAction.id, notice.actionId)
        assertEquals(reflection, notice.actionParams.string(WarnAction.KEY_MESSAGE))
    }

    @Test
    fun `止める同士は押し切れるかだけ変わる`() {
        val rule = RulePresets.byId("night")!!.build(setOf(youtube))
        val hard = Strength.apply(rule, Strength.HARD_BLOCK)
        assertEquals(rule.actionParams.with(BlockAction.KEY_ALLOW_OVERRIDE to false), hard.actionParams)
    }

    @Test
    fun `閉め出しは離れたら数え直す長さより短くしない`() {
        // 数え直すより短く閉めると、明けた直後の1回目でまた閉まる
        val rule = RulePresets.byId("long_session")!!.build(setOf(youtube), Params.of(PresetKeys.AWAY_MINUTES to 30))
        val locked = Strength.apply(rule, Strength.LOCKOUT)
        assertEquals(LockoutAction.id, locked.actionId)
        assertEquals(30, locked.actionParams.int(LockoutAction.KEY_MINUTES))
        assertEquals(45, Strength.apply(locked, Strength.LOCKOUT, 45).actionParams.int(LockoutAction.KEY_MINUTES))
        assertEquals(LockoutAction.MAX_MINUTES, Strength.apply(rule, Strength.LOCKOUT, 99_999).actionParams.int(LockoutAction.KEY_MINUTES))
    }

    @Test
    fun `音だけは段に載らない`() {
        val rule = RulePresets.byId("youtube_radio")!!.build(setOf(youtube))
        assertEquals(RadioAction.id, rule.actionId)
        assertNull(Strength.of(rule))
    }

    @Test
    fun `強さの既定は雛形の措置の段`() {
        val draft = RuleWizard.chooseProblem(WizardDraft(), Problem.TOTAL)
        assertEquals(Strength.BLOCK, RuleWizard.defaultStrength(draft))
        assertEquals(Strength.BLOCK, RuleWizard.defaultStrength(RuleWizard.chooseCustom(WizardDraft())))
        val gentle = RuleWizard.choosePreset(RuleWizard.chooseProblem(WizardDraft(), Problem.OPENING), "open_delay")
        assertEquals(Strength.PAUSE, RuleWizard.defaultStrength(gentle))
        assertEquals(WizardPath.PROBLEM, gentle.path)
    }

    /** 既定と違う、受け付けられる値。 */
    private fun otherValue(spec: ParamSpec): Any = when (spec) {
        is ParamSpec.IntParam -> if (spec.default < spec.max) spec.default + 1 else spec.default - 1
        is ParamSpec.TimeOfDayParam -> (spec.default + 60) % (24 * 60)
        is ParamSpec.DurationParam -> if (spec.default < spec.max) spec.default + 1 else spec.default - 1
        is ParamSpec.BoolParam -> !spec.default
        is ParamSpec.DayOfWeekParam -> (spec.default - spec.default.first()).toList()
        is ParamSpec.TextParam -> spec.default + "!"
        is ParamSpec.PackagesParam -> (spec.default + "com.example.other").toList()
        is ParamSpec.EnumParam -> spec.options.first { it.value != spec.default }.value
        is ParamSpec.RuleRefParam -> spec.default + "x"
        is ParamSpec.ResetPolicyParam -> spec.default.copy(periodMinutes = spec.default.periodMinutes / 2)
    }
}
