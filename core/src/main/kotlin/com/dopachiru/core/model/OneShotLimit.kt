package com.dopachiru.core.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「今日だけ」のルール。
 *
 * ## 中身はただのルール
 *
 * 違うのは期限([Rule.expiresAtSec])が入っていることだけで、作れるものは
 * 普通のルールと同じです(雛形からでも、ゼロから組んでも)。前は
 * 「◯時間使ったら◯分休憩」しか作れず、今日だけ別の縛りにしたいときに困った。
 *
 * ## なぜ入口を分けるのか
 *
 * **作るときの気分が違う**からです。普通のルールは「これから先ずっとどうするか」を
 * 決める。こちらは「今日はこれで行く」を決める。残り続けると分かっているものは、
 * 作る前に「一生守れるか」を考えることになり、その場では作られません。
 * 明日消えると分かっていれば、いま決められます。
 */
object OneShotLimit {

    /**
     * 「今日」の終わり。**日付が変わった瞬間ではなく、翌朝4時**。
     *
     * 深夜1時に作った枠が1時間で消えるのでは、その場の枠にならない。
     * 生活の区切りは日付の区切りより後ろにある。
     */
    const val DAY_ENDS_AT_HOUR = 4

    /** 今日いっぱいの期限(エポック秒)。 */
    fun endOfDay(now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Long {
        val base: LocalDate = if (now.hour < DAY_ENDS_AT_HOUR) now.toLocalDate() else now.toLocalDate().plusDays(1)
        return base.atTime(DAY_ENDS_AT_HOUR, 0).atZone(zone).toEpochSecond()
    }

    /** 作ったルールを今日だけのものにする。 */
    fun forToday(rule: Rule, now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Rule =
        rule.copy(expiresAtSec = endOfDay(now, zone))

    /**
     * [days] 日ぶんの期限。1 なら [endOfDay] と同じ。
     *
     * 「まず3日だけ試す」のためにある。今日だけと同じく、区切りは翌朝4時。
     */
    fun endAfterDays(now: LocalDateTime, days: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val first = endOfDay(now, zone)
        return first + (days.coerceAtLeast(1) - 1) * 24L * 60 * 60
    }

    /** 期限まで、あと何日(今日を1と数える)。期限なし・期限切れは 0。 */
    fun daysLeft(expiresAtSec: Long, now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Int {
        if (expiresAtSec <= 0L) return 0
        val nowSec = now.atZone(zone).toEpochSecond()
        if (expiresAtSec <= nowSec) return 0
        val first = endOfDay(now, zone)
        return (1 + (expiresAtSec - first + DAY_SEC - 1) / DAY_SEC).toInt().coerceAtLeast(1)
    }

    /** 今日いっぱいで消えるか。「今日だけの枠」に並べるものを選ぶため。 */
    fun endsToday(rule: Rule, now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        rule.isTemporary && rule.expiresAtSec <= endOfDay(now, zone)

    private const val DAY_SEC = 24L * 60 * 60
}
