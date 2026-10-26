package com.dopachiru.core.model

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * お試しのルールが消える前の知らせ。
 *
 * お試しは「合わなければ黙って消える」から気軽に作れる。ただ、合っていたのに
 * 気づかないまま消えると、守れていた縛りが一晩で無くなる。消える前に1回だけ
 * 「続けるなら『ずっとにする』」と知らせる。
 *
 * ## 今日だけのルールには出さない
 *
 * 今日だけのルールも同じ期限の仕組みを使っていて、ルールの形だけでは見分けられない
 * (最後の日は、お試しも今日だけも「今日いっぱいで消える」)。そこで
 * **2日以上残っているのを一度でも見たもの**をお試しとして覚えておく。
 * 今日だけのルールは、作った時点で残り1日なので覚えられない。
 *
 * ## いつ出すか
 *
 * 消える前の日の [EVENING_HOUR] 時から。夜は、次の日も続けるかを決める時間なので。
 * 前の日に画面を点けなかったら、最後の日に出す(出さずに消えるよりよい)。
 *
 * 覚えておく札は端末の中だけ。鍵は「uid@期限」── 期限を延ばしたら別のお試しとして数え直す。
 */
object TrialNotices {

    const val EVENING_HOUR = 19

    const val SEEN = "seen"
    const val SENT = "sent"

    data class Plan(
        /** いま知らせるルール。 */
        val notify: List<Rule>,
        /** 書き戻す札。消えたルールの札は落としてある。 */
        val marks: Map<String, String>,
    )

    fun keyOf(rule: Rule): String = "${rule.uid}@${rule.expiresAtSec}"

    fun plan(
        rules: List<Rule>,
        marks: Map<String, String>,
        now: LocalDateTime,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Plan {
        val temporary = rules.filter { it.isTemporary && it.enabled }
        val next = HashMap<String, String>()
        val notify = ArrayList<Rule>()
        for (rule in temporary) {
            val key = keyOf(rule)
            val left = OneShotLimit.daysLeft(rule.expiresAtSec, now, zone)
            if (left <= 0) continue
            var mark = marks[key]
            if (mark == null && left >= 2) mark = SEEN
            if (mark == SEEN && (left == 1 || (left == 2 && now.hour >= EVENING_HOUR))) {
                notify += rule
                mark = SENT
            }
            if (mark != null) next[key] = mark
        }
        return Plan(notify, next)
    }
}
