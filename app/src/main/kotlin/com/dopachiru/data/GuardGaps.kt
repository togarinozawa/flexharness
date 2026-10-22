package com.dopachiru.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.ui.rules.InstalledApps
import java.time.Instant
import java.time.ZoneId

/**
 * 見張りが止まっていたあいだに、何をどれだけ使っていたか。
 *
 * ## なぜ要るか
 *
 * 強制停止されると、FlexHarnessは何も動けない。止められているあいだのことは
 * **戻ってきてから振り返る**しかない。端末の使用状況には止まっていたあいだの
 * 記録も残っているので、そこから引いて「外していた3時間のうち、X に2時間」と出す。
 *
 * 黙って抜けられないようにするのが狙い ── 抜けた事実と中身が残ると分かっていれば、
 * 抜ける前に一度考える。
 *
 * 「使用状況へのアクセス」が無いと中身は引けない。そのときは止まっていた長さだけを出す。
 */
object GuardGaps {

    data class Gap(
        val startSec: Long,
        val endSec: Long,
        /** 使っていた分(アクセスが無ければ null)。 */
        val usedMinutes: Int?,
        /** 長く使っていた順。名前と分。 */
        val topApps: List<Pair<String, Int>>,
    ) {
        val minutes: Int get() = ((endSec - startSec) / 60).toInt()
    }

    fun measure(context: Context, startSec: Long, endSec: Long): Gap {
        if (!AppUsageRanking.hasUsageAccess(context)) return Gap(startSec, endSec, null, emptyList())
        val perPackage = foregroundMs(context, startSec * 1000, endSec * 1000)
            .filterKeys { it != context.packageName && !DopaRuntime.isProtected(it) }
        val top = perPackage.entries
            .sortedByDescending { it.value }
            .map { InstalledApps.labelOf(context, it.key) to (it.value / 60_000L).toInt() }
            .filter { it.second > 0 }
        return Gap(startSec, endSec, (perPackage.values.sum() / 60_000L).toInt(), top.take(3))
    }

    /** 人が読む1行。通知にも、ほかの端末の名簿にもこれを出す。 */
    fun describe(gap: Gap): String {
        val span = "${clock(gap.startSec)}〜${clock(gap.endSec)}(${duration(gap.minutes)})"
        val used = when {
            gap.usedMinutes == null -> "使用状況へのアクセスが無いので、中身は分かりません"
            gap.usedMinutes == 0 -> "そのあいだは使っていません"
            else -> "そのあいだ ${duration(gap.usedMinutes)}使用" +
                gap.topApps.joinToString("") { (name, m) -> " ・ $name ${duration(m)}" }
        }
        return "$span 見張りなし。$used"
    }

    /**
     * 前面にいた時間をアプリごとに足す。
     *
     * 窓の頭で既に前面にいたものは拾えない(始まりの出来事が窓の外にある)。
     * 少なめに出るほうに転ぶが、多めに出して責めるよりはいい。
     */
    private fun foregroundMs(context: Context, startMs: Long, endMs: Long): Map<String, Long> = runCatching {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val events = manager.queryEvents(startMs, endMs) ?: return emptyMap()
        val resumedAt = HashMap<String, Long>()
        val total = HashMap<String, Long>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> resumedAt[pkg] = event.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val from = resumedAt.remove(pkg) ?: continue
                    total[pkg] = (total[pkg] ?: 0L) + (event.timeStamp - from).coerceAtLeast(0L)
                }
            }
        }
        // 窓の終わりでまだ前面にいたものは、終わりまで数える
        resumedAt.forEach { (pkg, from) -> total[pkg] = (total[pkg] ?: 0L) + (endMs - from).coerceAtLeast(0L) }
        total
    }.getOrDefault(emptyMap())

    private fun clock(sec: Long): String {
        val t = Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault())
        return String.format("%d/%d %d:%02d", t.monthValue, t.dayOfMonth, t.hour, t.minute)
    }

    private fun duration(minutes: Int): String = when {
        minutes < 60 -> "${minutes}分"
        minutes % 60 == 0 -> "${minutes / 60}時間"
        else -> "${minutes / 60}時間${minutes % 60}分"
    }
}
