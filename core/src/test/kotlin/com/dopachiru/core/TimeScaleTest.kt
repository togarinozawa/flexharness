package com.dopachiru.core

import com.dopachiru.core.condition.ConditionRegistry
import com.dopachiru.core.condition.types.UsageBudgetCondition
import com.dopachiru.core.demo.DemoRules
import com.dopachiru.core.engine.BudgetQuery
import com.dopachiru.core.engine.BudgetReset
import com.dopachiru.core.engine.CountBy
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.UsageBudgets
import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.engine.WindowUsage
import com.dopachiru.core.model.Lockout
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.core.preset.RulePresets
import com.dopachiru.core.time.TimeScale
import org.junit.After
import org.junit.Before
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * デモ版の縮尺。「ルールの1分」を数秒にしても、数え方が丸ごと速くなるだけで
 * 食い違わないこと、そして本番(60)では何も変わらないこと。
 */
class TimeScaleTest {

    @Before
    fun setUp() = DopaCore.registerAll()

    // 他の試験に縮尺を漏らさない。漏れると関係ない試験が時間の計算で落ちる
    @After
    fun tearDown() {
        TimeScale.secondsPerMinute = TimeScale.REAL
    }

    private val now = LocalDateTime.of(2026, 10, 30, 12, 0)
    private val nowSec = 1_800_000_000L

    private fun query(reset: BudgetReset, awayMinutes: Int = 30) = BudgetQuery(
        ruleId = 1,
        clauseId = 1,
        packageName = "com.google.android.youtube",
        countBy = CountBy.GROUP,
        reset = reset,
        awayMinutes = awayMinutes,
    )

    @Test
    fun `本番では1分は60秒のまま`() {
        assertFalse(TimeScale.isScaled)
        assertEquals(1800L, TimeScale.seconds(30))
        assertEquals(29, TimeScale.minutes(1799))
        assertEquals(30, TimeScale.minutesCeil(1741))
    }

    @Test
    fun `縮めると30秒で6分使ったことになる`() {
        TimeScale.secondsPerMinute = 5
        val spans = listOf(nowSec - 30 to nowSec)
        val usage = UsageBudgets.compute(query(BudgetReset.AWAY, awayMinutes = 2), spans, now, nowSec)
        assertEquals(6, usage.usedMinutes)
        // 離れたら数え直すまで2分 = 10秒
        assertEquals(10L, usage.remainingSeconds)
    }

    @Test
    fun `縮めると離れた時間も縮む`() {
        TimeScale.secondsPerMinute = 5
        // 30秒使って11秒離れた = 2分(10秒)離れたので数え直し済み
        val spans = listOf(nowSec - 41 to nowSec - 11)
        val usage = UsageBudgets.compute(query(BudgetReset.AWAY, awayMinutes = 2), spans, now, nowSec)
        assertEquals(0, usage.usedMinutes)
    }

    @Test
    fun `次に見に来る時刻も縮む`() {
        TimeScale.secondsPerMinute = 5
        val p = Params.of(
            UsageBudgetCondition.KEY_BUDGET_MINUTES to 6,
            UsageBudgetCondition.KEY_RESET to BudgetReset.AWAY.name,
            UsageBudgetCondition.KEY_AWAY_MINUTES to 2,
        )
        val ctx = EvalContext(
            now = now,
            packageName = "com.google.android.youtube",
            usage = UsageSnapshot.EMPTY,
            // 2分(10秒)使ったところ
            budgetUsageOf = { WindowUsage(usedSeconds = 10, remainingSeconds = 10) },
        )
        val type = ConditionRegistry.get(UsageBudgetCondition.id)!!
        assertFalse(type.evaluate(p, ctx))
        // 残り4分 = 20秒だが、数え直し(10秒)のほうが先
        assertEquals(now.plusSeconds(10), type.nextChangeAt(p, ctx))
    }

    @Test
    fun `封鎖の残りも縮んだ分で数える`() {
        TimeScale.secondsPerMinute = 5
        val lockout = Lockout(target = Target(), untilEpochSec = nowSec + 12, reason = "", createdAtEpochSec = nowSec)
        // 12秒 = 2.4分 → 切り上げて3分
        assertEquals(3, lockout.remainingMinutesAt(nowSec))
    }

    @Test
    fun `縮尺は1秒より短くも60秒より長くもならない`() {
        TimeScale.secondsPerMinute = 0
        assertEquals(1L, TimeScale.secondsPerMinute)
        TimeScale.secondsPerMinute = 600
        assertEquals(60L, TimeScale.secondsPerMinute)
    }

    @Test
    fun `デモのルールは全部組めて、どれも別の措置を使う`() {
        val entries = DemoRules.build(5)
        assertTrue(entries.isNotEmpty())
        entries.forEach { e ->
            assertTrue(DemoRules.isDemo(e.rule), e.rule.uid)
            assertEquals(e.packages, e.rule.target.packages)
            assertTrue(e.rule.name.isNotBlank())
        }
        // uid が被ると、入れ直したときに1本が上書きで消える
        assertEquals(entries.size, entries.map { it.rule.uid }.toSet().size)
        // 同じアプリを2本のルールが取り合うと、どちらが効いたか見に来た人に説明できない
        val all = entries.flatMap { it.packages }
        assertEquals(all.size, all.toSet().size)
        // 措置が被りすぎていたら展示の意味が薄れる。止める(block)以外は1つずつ
        val nonBlock = entries.map { it.rule.actionId }.filter { it != "block" }
        assertEquals(nonBlock.size, nonBlock.toSet().size)
    }

    @Test
    fun `デモの名前は実際に待つ秒で書く`() {
        val youtube = DemoRules.build(5).first { "com.google.android.youtube" in it.packages }
        assertTrue("30秒" in youtube.rule.name, youtube.rule.name)
        val slow = DemoRules.build(10).first { "com.google.android.youtube" in it.packages }
        assertTrue("1分" in slow.rule.name, slow.rule.name)
    }

    @Test
    fun `デモが使う雛形はどれも実在する`() {
        listOf("forced_break", "reopen", "open_delay", "state_intention", "declare_first", "long_session", "cooldown_between")
            .forEach { assertTrue(RulePresets.byId(it) != null, it) }
    }
}
