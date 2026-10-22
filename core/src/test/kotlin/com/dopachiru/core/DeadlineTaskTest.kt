package com.dopachiru.core

import com.dopachiru.core.model.DeadlineTask
import com.dopachiru.core.model.DeadlineTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DeadlineTaskTest {

    private val zone = ZoneId.of("Asia/Tokyo")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toEpochSecond()

    /** 10/23 9:00 のテスト。前の晩 18〜21時に集中。 */
    private fun test(
        start: Int = 18 * 60,
        end: Int = 21 * 60,
        lead: Int = 1,
        created: Long = at(20, 12),
        minMinutes: Int = 30,
    ) = DeadlineTask(
        uid = "t1",
        title = "数学のテスト",
        dueAtSec = at(23, 9),
        windowStartMinute = start,
        windowEndMinute = end,
        leadDays = lead,
        minMinutes = minMinutes,
        allowPackages = setOf("com.example.textbook"),
        createdAtSec = created,
        updatedAtSec = created,
    )

    @Test
    fun focusesOnlyInsideTheWindowOnTheLeadDays() {
        val t = test()
        assertNull("2日前は対象外", t.windowAt(at(21, 19), zone))
        assertNull("前の日でも時間帯の前は開いている", t.windowAt(at(22, 17, 59), zone))
        assertNotNull(t.windowAt(at(22, 18), zone))
        assertNotNull(t.windowAt(at(22, 20, 59), zone))
        assertNull("時間帯が終われば開く(寝る時刻を越えて閉めない)", t.windowAt(at(22, 21), zone))
        assertNull("当日も時間帯の外", t.windowAt(at(23, 8), zone))
    }

    @Test
    fun windowAcrossMidnightIsCutAtTheDeadline() {
        // 22時〜翌10時。締切 9時で切れる
        val t = test(start = 22 * 60, end = 10 * 60)
        val w = t.windowAt(at(23, 2), zone)
        assertNotNull("前の日に始まった時間帯が日をまたいで続く", w)
        assertEquals(at(22, 22), w!!.first)
        assertEquals(at(23, 9), w.last + 1)
        assertNull("締切を過ぎたら閉めない", t.windowAt(at(23, 9, 30), zone))
    }

    @Test
    fun doneOrDeletedTasksNeverFocus() {
        val now = at(22, 19)
        assertNull(test().copy(doneAtSec = now - 60).windowAt(now, zone))
        assertNull(test().copy(deletedAtSec = now - 60).windowAt(now, zone))
    }

    @Test
    fun finishingWaitsForTheMinimumCountedFromWindowStartOrCreation() {
        val t = test(minMinutes = 30)
        assertEquals(at(22, 18, 30), t.canFinishAt(at(22, 18, 5), zone))
        // 時間帯の途中で入れたなら、入れた時刻から数える
        val late = test(created = at(22, 19))
        assertEquals(at(22, 19, 30), late.canFinishAt(at(22, 19, 10), zone))
        // 時間帯の外なら今すぐ(閉めていないので抜け道にならない)
        assertEquals(at(22, 12), t.canFinishAt(at(22, 12), zone))
    }

    @Test
    fun onePausePerWindowAndItLiftsTheLockForItsLength() {
        val now = at(22, 19)
        val t = test()
        assertTrue(t.canPauseAt(now, zone))
        val window = t.windowAt(now, zone)!!
        val paused = t.copy(pausedUntilSec = now + 15 * 60, pausedInWindowSec = window.first)
        assertFalse(paused.isFocusingAt(now + 60, zone))
        assertTrue(DeadlineTasks.lockoutsAt(listOf(paused), now + 60, zone, withApps = true).isEmpty())
        assertTrue("時間で勝手に戻る", paused.isFocusingAt(now + 15 * 60, zone))
        assertFalse("同じ時間帯で2回目は無い", paused.canPauseAt(now + 16 * 60, zone))
        // 次の日の時間帯ならまた使える(締切を延ばして確かめる)
        val longer = paused.copy(dueAtSec = at(24, 9), leadDays = 2)
        assertTrue(longer.canPauseAt(at(23, 19), zone))
    }

    @Test
    fun lockoutsBlockEverythingButAllowedAppsAndAlsoTheSites() {
        val now = at(22, 19)
        val locks = DeadlineTasks.lockoutsAt(listOf(test()), now, zone, withApps = true)
        assertEquals(2, locks.size)
        val apps = locks.first { !it.uid.endsWith(":sites") }
        assertTrue(apps.target.matches("com.google.android.youtube", emptySet()))
        assertFalse(apps.target.matches("com.example.textbook", emptySet()))
        assertEquals(at(22, 21), apps.untilEpochSec)
        assertNull("途中でやめる手段は付けない", apps.earlyExit)
        val sites = locks.first { it.uid.endsWith(":sites") }
        assertTrue(sites.target.matches("com.android.chrome", emptySet(), "https://animestore.docomo.ne.jp/animestore/"))
        assertFalse(sites.target.matches("com.android.chrome", emptySet(), "https://ja.wikipedia.org/"))
        // PC はサイトだけ
        val pc = DeadlineTasks.lockoutsAt(listOf(test()), now, zone, withApps = false)
        assertEquals(listOf("deadline:t1:sites"), pc.map { it.uid })
    }

    @Test
    fun mergeKeepsTheNewerSide() {
        val mine = test().copy(updatedAtSec = 100)
        val theirsOld = mine.copy(title = "古い", updatedAtSec = 50)
        val theirsNew = mine.copy(doneAtSec = 200, updatedAtSec = 200)
        assertEquals(listOf(mine) to false, DeadlineTasks.merge(listOf(mine), listOf(theirsOld)))
        val (merged, changed) = DeadlineTasks.merge(listOf(mine), listOf(theirsNew))
        assertTrue(changed)
        assertTrue(merged.single().isDone)
    }

    @Test
    fun nextStartWakesTheWatcherAtTheWindowStart() {
        val t = test()
        assertEquals(at(22, 18), t.nextWindowStartAfter(at(21, 10), zone))
        assertEquals(at(22, 18), t.nextWindowStartAfter(at(22, 17), zone))
        // 当日 18時は締切(9時)より後なので来ない
        assertNull(t.nextWindowStartAfter(at(22, 19), zone))
        assertEquals(at(22, 18), DeadlineTasks.nextChangeAt(listOf(t), at(22, 17), zone))
        assertEquals(at(22, 21), DeadlineTasks.nextChangeAt(listOf(t), at(22, 19), zone))
        assertTrue(DeadlineTasks.willTarget(listOf(t), at(22, 17), "com.google.android.youtube", emptySet()))
        assertFalse(DeadlineTasks.willTarget(listOf(t), at(22, 17), "com.example.textbook", emptySet()))
    }
}
