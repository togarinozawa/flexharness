package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.OneShotLimit
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.model.TrialNotices
import com.dopachiru.core.param.Params
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** お試しが消える前の知らせ。肝は「1回だけ」と「今日だけのルールには出さない」。 */
class TrialNoticesTest {

    private val zone = ZoneId.of("Asia/Tokyo")
    private val created = LocalDateTime.of(2026, 10, 23, 21, 0)

    private fun rule(uid: String, expiresAtSec: Long) = Rule(
        uid = uid,
        name = uid,
        target = Target(packages = setOf("com.example")),
        condition = ConditionNode.AllOf(),
        actionId = BlockAction.id,
        actionParams = Params.EMPTY,
        expiresAtSec = expiresAtSec,
    )

    private val trial = rule("trial", OneShotLimit.endAfterDays(created, 3, zone))

    /** 時刻を順に進めて、知らせたルールを集める。札は前の結果を持ち越す。 */
    private fun run(rules: List<Rule>, times: List<LocalDateTime>): List<Pair<LocalDateTime, String>> {
        var marks = emptyMap<String, String>()
        val sent = ArrayList<Pair<LocalDateTime, String>>()
        for (t in times) {
            val plan = TrialNotices.plan(rules, marks, t, zone)
            plan.notify.forEach { sent += t to it.uid }
            marks = plan.marks
        }
        return sent
    }

    @Test
    fun `お試しは消える前の日の夜に1回だけ知らせる`() {
        val times = listOf(
            created,
            LocalDateTime.of(2026, 10, 24, 12, 0),
            LocalDateTime.of(2026, 10, 24, 18, 59),
            LocalDateTime.of(2026, 10, 24, 19, 0),
            LocalDateTime.of(2026, 10, 24, 22, 0),
            LocalDateTime.of(2026, 10, 25, 12, 0),
        )
        assertEquals(listOf(LocalDateTime.of(2026, 10, 24, 19, 0) to "trial"), run(listOf(trial), times))
    }

    @Test
    fun `前の日に画面を点けなかったら最後の日に知らせる`() {
        val times = listOf(created, LocalDateTime.of(2026, 10, 25, 8, 0), LocalDateTime.of(2026, 10, 25, 20, 0))
        assertEquals(listOf(LocalDateTime.of(2026, 10, 25, 8, 0) to "trial"), run(listOf(trial), times))
    }

    @Test
    fun `今日だけのルールには知らせない`() {
        val today = rule("today", OneShotLimit.endOfDay(created, zone))
        val times = listOf(created, created.plusHours(1), created.plusHours(5))
        assertTrue(run(listOf(today), times).isEmpty())
    }

    @Test
    fun `消えたルールの札は落とす`() {
        val plan = TrialNotices.plan(emptyList(), mapOf("gone@1" to TrialNotices.SENT), created, zone)
        assertTrue(plan.marks.isEmpty())
    }

    @Test
    fun `期限を延ばしたら別のお試しとして数え直す`() {
        var marks = TrialNotices.plan(listOf(trial), emptyMap(), created, zone).marks
        marks = TrialNotices.plan(listOf(trial), marks, LocalDateTime.of(2026, 10, 24, 20, 0), zone).marks
        val extended = trial.copy(expiresAtSec = OneShotLimit.endAfterDays(LocalDateTime.of(2026, 10, 24, 20, 0), 3, zone))
        val plan = TrialNotices.plan(listOf(extended), marks, LocalDateTime.of(2026, 10, 24, 20, 5), zone)
        assertTrue(plan.notify.isEmpty(), "延ばした直後はまだ残りが長い")
        assertEquals(TrialNotices.SEEN, plan.marks[TrialNotices.keyOf(extended)])
    }

    @Test
    fun `ずっとにしたルールは知らせない`() {
        val kept = trial.copy(expiresAtSec = 0L)
        assertTrue(run(listOf(kept), listOf(created, LocalDateTime.of(2026, 10, 24, 20, 0))).isEmpty())
    }
}
