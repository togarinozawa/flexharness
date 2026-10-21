package com.dopachiru.core

import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Rules
import com.dopachiru.core.model.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 一時停止は時間が来たら勝手に戻る。無効化とは別物。 */
class RulePauseTest {

    private fun rule(id: Long, pausedUntil: Long = 0L, enabled: Boolean = true) = Rule(
        id = id,
        name = "r$id",
        enabled = enabled,
        target = Target(packages = setOf("com.twitter.android")),
        condition = ConditionNode.AllOf(),
        actionId = "block",
        pausedUntilSec = pausedUntil,
    )

    @Test
    fun pausedRuleIsOffUntilItsTimeThenBackOn() {
        val r = rule(1, pausedUntil = 1_000)
        assertTrue(r.isPausedAt(999))
        assertFalse(r.isActiveAt(999))
        // 期限ちょうどで戻る
        assertFalse(r.isPausedAt(1_000))
        assertTrue(r.isActiveAt(1_000))
    }

    @Test
    fun effectiveRulesHandPausedOnesToTheEngineAsDisabled() {
        val all = listOf(rule(1, pausedUntil = 1_000), rule(2))
        val during = Rules.effectiveAt(all, 500)
        assertEquals(listOf(false, true), during.map { it.enabled })
        val after = Rules.effectiveAt(all, 1_000)
        assertEquals(listOf(true, true), after.map { it.enabled })
        // 止めているものが無ければ同じものを返す(判定のたびに作り直さない)
        assertSame(all, Rules.effectiveAt(all, 2_000))
    }

    @Test
    fun nextResumeIsTheEarliestPauseThatIsStillRunning() {
        val all = listOf(rule(1, pausedUntil = 1_500), rule(2, pausedUntil = 1_200), rule(3, pausedUntil = 900))
        assertEquals(1_200L, Rules.nextResumeAt(all, 1_000))
        assertNull(Rules.nextResumeAt(all, 2_000))
        // 無効にしてあるものは戻らないので数えない
        assertNull(Rules.nextResumeAt(listOf(rule(4, pausedUntil = 1_500, enabled = false)), 1_000))
    }
}
