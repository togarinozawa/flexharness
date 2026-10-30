package com.dopachiru.core.time

import java.time.LocalDateTime

/**
 * 「ルールの1分」が実時間の何秒か。ふだんは 60 で、デモ版だけが縮める。
 *
 * 展示で「30分使ったら止まる」を見せるのに30分待たせられない。かといって
 * デモ用に数字だけ小さくすると、分より短くはできず、待ち時間・封鎖・間隔は
 * 結局長いまま残る。**分と秒の換算をここに寄せて、換算の側で縮める。**
 * ルールの中身(「30分」)は本番と同じまま、数え方だけが速くなる。
 *
 * 縮むのは「長さ」だけ。**時刻(22時から・毎日4時に戻る・曜日)は縮まない** ──
 * 壁時計まで速めると、記録の実時刻と食い違って何も数えられなくなる。
 * 時刻の条件を見せたいときは、開発ツールの「時計をずらす」を使う。
 *
 * 本番の APK でここが 60 以外になる経路は無い(書き換えるのはデモ版の起動だけ)。
 */
object TimeScale {
    /** 縮めていないときの値。 */
    const val REAL: Long = 60L

    @Volatile
    var secondsPerMinute: Long = REAL
        set(value) {
            field = value.coerceIn(1L, REAL)
        }

    val isScaled: Boolean get() = secondsPerMinute != REAL

    /** ルールの分 → 実時間の秒。 */
    fun seconds(minutes: Int): Long = minutes * secondsPerMinute
    fun seconds(minutes: Long): Long = minutes * secondsPerMinute

    /** 実時間の秒 → ルールの分(切り捨て)。「何分使ったか」はこちら。 */
    fun minutes(seconds: Long): Int = (seconds / secondsPerMinute).toInt()

    /** 実時間の秒 → ルールの分(切り上げ)。「あと何分」はこちら ── 0分と出たのにまだ閉まっている、を避ける。 */
    fun minutesCeil(seconds: Long): Int = ((seconds + secondsPerMinute - 1) / secondsPerMinute).toInt()

    /** ルールの分 → 実時間のミリ秒。 */
    fun millis(minutes: Int): Long = minutes * secondsPerMinute * 1000L

    /** [now] からルールの分だけ進んだ時刻。条件の「次に変わる時刻」に使う。 */
    fun after(now: LocalDateTime, minutes: Long): LocalDateTime = now.plusSeconds(minutes * secondsPerMinute)

    /** [now] からルールの分だけ戻った時刻。 */
    fun before(now: LocalDateTime, minutes: Long): LocalDateTime = now.minusSeconds(minutes * secondsPerMinute)
}
