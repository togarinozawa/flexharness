package com.dopachiru.core.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 締切のある、片付けないといけないタスク。締切が近づくと、決めた時間帯に勝手に集中が始まる。
 *
 * ## なぜ要るか
 *
 * 自分で始める集中は、**始めなければ始まらない**。テストの前の晩にアニメを見続けて
 * 勉強の時間が消えた ── そういう晩こそ、始める気が起きない。締切を先に入れておけば、
 * その時間になったら勝手に閉まる。
 *
 * ## 終わり方
 *
 * 時間ではなく**「終わった」にしたら**終わる。時間で終わると、時間まで眺めて待てば
 * 済んでしまう。ただし押した瞬間に抜けられるなら抜け道なので、
 *
 *  - その時間帯の集中が始まってから [minMinutes] たつまでは押せない
 *  - 押すときに「何をやったか」を一言書く([doneNote])。後で振り返りに使う
 *
 * 時間帯([windowStartMinute]〜[windowEndMinute])が終われば、その日はいったん開く。
 * 終わるまで閉め続けると、寝る時刻も越えて閉め続けることになる ── 寝不足でテストを
 * 受けるのがいちばん点を落とす。続きは次の日の時間帯に。
 *
 * ## 端末をまたぐ
 *
 * スマホで入れた締切は PC にも配る(同期の種類 `deadlines`)。衝突は [updatedAtSec] が
 * 新しいほうが勝つ。消すときは行を消さずに [deletedAtSec] を立てる ── 消すと、
 * 寝ていた端末が古いものを送り返して生き返らせる。
 */
@Serializable
data class DeadlineTask(
    /** 端末をまたいで一意な ID。 */
    val uid: String,
    /** 人が読む名前。**AI には渡さない**(タスク名は端末の中だけで使う約束)。 */
    val title: String,
    /** 締切(エポック秒)。過ぎたら集中は起きない。 */
    val dueAtSec: Long,
    /** 集中する時間帯の始まり(0時起点の分)。 */
    val windowStartMinute: Int = DEFAULT_START,
    /** 集中する時間帯の終わり(0時起点の分)。始まり以下なら日をまたぐ。 */
    val windowEndMinute: Int = DEFAULT_END,
    /**
     * 締切の何日前から集中するか。0 なら締切の当日だけ、1 なら前の日から。
     *
     * テストのように「前の晩が勝負」のものは 1。レポートのように何日もかかるものは増やす。
     */
    val leadDays: Int = 1,
    /** 「終わった」にできるまでの最低時間(分)。その時間帯の集中が始まってから数える。 */
    val minMinutes: Int = DEFAULT_MIN_MINUTES,
    /**
     * 集中のあいだも開けるアプリ(Android のパッケージ名)。
     *
     * 教科書・問題集・ブラウザなど。電話・ホーム・設定・ドパチル自身は書かなくても開く。
     */
    val allowPackages: Set<String> = emptySet(),
    /** 集中のあいだも開けるタグ。 */
    val allowTags: Set<String> = emptySet(),
    /**
     * 集中のあいだ塞ぐサイト([SitePattern] の書き方)。
     *
     * 調べ物にブラウザは要るので、ブラウザそのものは通して、ここに挙げたサイトだけ塞ぐ。
     * PC ではアプリは塞がず、これだけを効かせる(PC で勉強に使うアプリは人それぞれなので)。
     */
    val blockSites: Set<String> = DEFAULT_BLOCK_SITES,

    val createdAtSec: Long,
    /** 最後に変えた時刻。同期の衝突はこれで決める。 */
    val updatedAtSec: Long,
    /** 終わった時刻。0 ならまだ。 */
    val doneAtSec: Long = 0L,
    /** 終わったときに書いた一言。 */
    val doneNote: String = "",
    /** 消した時刻。0 なら生きている。墓標として残す。 */
    val deletedAtSec: Long = 0L,

    /**
     * この時刻まで一時停止。時間で勝手に戻る。
     *
     * 押し切る口が無いと、ドパチルごと切られて数時間戻らない。戻ると分かっている
     * 止め方を1つだけ用意する。**1つの時間帯に1回まで**([pausedInWindowSec])。
     */
    val pausedUntilSec: Long = 0L,
    /** 一時停止を使った時間帯の始まり。同じ時間帯で2回目は使えない。 */
    val pausedInWindowSec: Long = 0L,
    /** 一時停止したときに書いた理由。振り返り用。新しいものが後ろ。 */
    val pauseNotes: List<String> = emptyList(),
) {
    val isDone: Boolean get() = doneAtSec > 0L
    val isDeleted: Boolean get() = deletedAtSec > 0L

    /**
     * いま集中する時間帯なら、その始まりと終わり(エポック秒、終わりは含まない)。違えば null。
     *
     * 終わりは締切で切る。締切を過ぎてまで閉めても、もう間に合わない。
     */
    fun windowAt(nowSec: Long, zone: ZoneId): LongRange? {
        if (isDone || isDeleted || nowSec >= dueAtSec) return null
        val dueDate = Instant.ofEpochSecond(dueAtSec).atZone(zone).toLocalDate()
        val firstDate = dueDate.minusDays(leadDays.coerceAtLeast(0).toLong())
        val today = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
        // 日をまたぐ時間帯は、前の日に始まったものがまだ続いていることがある
        for (day in listOf(today.minusDays(1), today)) {
            if (day.isBefore(firstDate) || day.isAfter(dueDate)) continue
            val (start, end) = spanOn(day, zone)
            val clippedEnd = minOf(end, dueAtSec)
            if (nowSec in start until clippedEnd) return start until clippedEnd
        }
        return null
    }

    /**
     * 次に時間帯が始まる時刻(いまより後)。無ければ null。
     *
     * 開きっぱなしのアプリを、時間帯が始まった瞬間に閉めるため。見張りはアプリを
     * 切り替えたときにしか判定しないので、始まる時刻に起こしてもらう必要がある。
     */
    fun nextWindowStartAfter(nowSec: Long, zone: ZoneId): Long? {
        if (isDone || isDeleted || nowSec >= dueAtSec) return null
        val dueDate = Instant.ofEpochSecond(dueAtSec).atZone(zone).toLocalDate()
        val firstDate = dueDate.minusDays(leadDays.coerceAtLeast(0).toLong())
        val today = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
        var day = if (today.isBefore(firstDate)) firstDate else today
        while (!day.isAfter(dueDate)) {
            val start = spanOn(day, zone).first
            if (start > nowSec && start < dueAtSec) return start
            day = day.plusDays(1)
        }
        return null
    }

    /** アプリを閉める範囲。通すアプリ以外ぜんぶ。 */
    val appTarget: Target
        get() = Target(matchAll = true, exceptPackages = allowPackages, exceptTags = allowTags)

    /** いま閉めているか(時間帯の中で、一時停止していない)。 */
    fun isFocusingAt(nowSec: Long, zone: ZoneId): Boolean =
        windowAt(nowSec, zone) != null && pausedUntilSec <= nowSec

    /**
     * 「終わった」にできる時刻。時間帯の外なら今すぐ(閉めていないので抜け道にならない)。
     *
     * 数え始めは時間帯の始まり。ただし時間帯の途中で入れたタスクは、入れた時刻から。
     */
    fun canFinishAt(nowSec: Long, zone: ZoneId): Long {
        val window = windowAt(nowSec, zone) ?: return nowSec
        val from = maxOf(window.first, createdAtSec)
        return from + minMinutes.coerceAtLeast(0) * 60L
    }

    /** この時間帯でまだ一時停止を使えるか。 */
    fun canPauseAt(nowSec: Long, zone: ZoneId): Boolean {
        val window = windowAt(nowSec, zone) ?: return false
        return pausedInWindowSec != window.first && pausedUntilSec <= nowSec
    }

    private fun spanOn(day: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val start = day.atStartOfDay(zone).plusMinutes(windowStartMinute.toLong()).toEpochSecond()
        val endDay = if (windowEndMinute <= windowStartMinute) day.plusDays(1) else day
        val end = endDay.atStartOfDay(zone).plusMinutes(windowEndMinute.toLong()).toEpochSecond()
        return start to end
    }

    companion object {
        const val DEFAULT_START = 18 * 60
        const val DEFAULT_END = 21 * 60
        const val DEFAULT_MIN_MINUTES = 30

        /** 一時停止の長さ(分)。短く決め打ちにする ── 長さを選ばせると、長いほうを選ぶ。 */
        const val PAUSE_MINUTES = 15

        /** 既定で塞ぐサイト。動画・配信・SNS・掲示板。 */
        val DEFAULT_BLOCK_SITES: Set<String> = listOf(
            SiteCatalog.STREAMING,
            SiteCatalog.VIDEO,
            SiteCatalog.SHORT_VIDEO,
            SiteCatalog.SOCIAL,
            SiteCatalog.FORUM,
        ).flatMap { it.patterns }.toSet()
    }
}

/** 締切タスクの一覧に対する手入れ。端末側の保存方法に依存しないよう、ここに置く。 */
object DeadlineTasks {

    /** 集中の封鎖の uid の頭。ほかの封鎖と見分けるため。 */
    const val LOCKOUT_PREFIX = "deadline:"

    /**
     * いま効かせる封鎖。保存はせず、判定のたびに組み立てる。
     *
     * 封鎖([Lockout])の形にそろえるのは、判定もブロック画面も今のまま使えるから。
     * **途中でやめる手段は付けない**(earlyExit = null)── やめ方は「終わった」か一時停止だけ。
     *
     * @param withApps アプリも塞ぐか。PC では false(サイトだけ塞ぐ)。
     */
    fun lockoutsAt(
        all: List<DeadlineTask>,
        nowSec: Long,
        zone: ZoneId,
        withApps: Boolean,
    ): List<Lockout> = all.flatMap { task ->
        val window = task.windowAt(nowSec, zone) ?: return@flatMap emptyList()
        if (task.pausedUntilSec > nowSec) return@flatMap emptyList()
        val reason = "締切「${task.title}」の集中中。終わったらドパチルで「終わった」にしてください"
        buildList {
            if (withApps) {
                add(
                    Lockout(
                        uid = LOCKOUT_PREFIX + task.uid,
                        target = task.appTarget,
                        untilEpochSec = window.last + 1,
                        reason = reason,
                        createdAtEpochSec = window.first,
                    ),
                )
            }
            if (task.blockSites.isNotEmpty()) {
                // 通したブラウザの中でも、このサイトは塞ぐ。アプリの例外より強くしたいので別の封鎖にする
                add(
                    Lockout(
                        uid = LOCKOUT_PREFIX + task.uid + ":sites",
                        target = Target(sites = task.blockSites),
                        untilEpochSec = window.last + 1,
                        reason = reason,
                        createdAtEpochSec = window.first,
                    ),
                )
            }
        }
    }

    /** いま集中しているタスク。画面に出すため。 */
    fun focusingAt(all: List<DeadlineTask>, nowSec: Long, zone: ZoneId): List<DeadlineTask> =
        all.filter { it.isFocusingAt(nowSec, zone) }

    /** 画面に並べるもの。消したものを除き、終わっていないものを締切の近い順に先へ。 */
    fun visible(all: List<DeadlineTask>): List<DeadlineTask> =
        all.filterNot { it.isDeleted }.sortedWith(compareBy<DeadlineTask> { it.isDone }.thenBy { it.dueAtSec })

    /** 次に集中が始まる・終わる・一時停止が明ける時刻。そこで判定し直すため。無ければ null。 */
    fun nextChangeAt(all: List<DeadlineTask>, nowSec: Long, zone: ZoneId): Long? {
        val ends = all.mapNotNull { it.windowAt(nowSec, zone)?.let { w -> w.last + 1 } }
        val starts = all.mapNotNull { it.nextWindowStartAfter(nowSec, zone) }
        val resumes = all.map { it.pausedUntilSec }.filter { it > nowSec }
        return (ends + starts + resumes).minOrNull()
    }

    /**
     * これから閉めることになるか(まだ終わっていない締切が、このアプリを閉める範囲に入れているか)。
     *
     * 見張りは「どれかが狙っているアプリ」でしか判定を予約しない。時間帯の前から
     * 開いているアプリも見張りに入れておかないと、時間帯が始まっても閉まらない。
     */
    fun willTarget(
        all: List<DeadlineTask>,
        nowSec: Long,
        packageName: String,
        tagsOfApp: Set<String>,
        url: String? = null,
    ): Boolean = all.any { task ->
        !task.isDone && !task.isDeleted && nowSec < task.dueAtSec &&
            (task.appTarget.matches(packageName, tagsOfApp) || Target(sites = task.blockSites).matches(packageName, tagsOfApp, url))
    }

    /**
     * 受け取ったものを手元に混ぜる。**新しいほうが勝つ。**
     *
     * @return 混ぜたあとの一覧と、手元が変わったか。
     */
    fun merge(local: List<DeadlineTask>, incoming: List<DeadlineTask>): Pair<List<DeadlineTask>, Boolean> {
        val byUid = local.associateBy { it.uid }.toMutableMap()
        var changed = false
        for (task in incoming) {
            val mine = byUid[task.uid]
            if (mine == null || task.updatedAtSec > mine.updatedAtSec) {
                byUid[task.uid] = task
                changed = true
            }
        }
        return byUid.values.sortedBy { it.dueAtSec } to changed
    }

    /**
     * 古いものを落とす。締切か消した時刻から十分たったもの。
     *
     * 墓標を早く落とすと、長く寝ていた端末が送り返して生き返らせる。
     */
    fun prune(all: List<DeadlineTask>, nowSec: Long): List<DeadlineTask> = all.filterNot { task ->
        val gone = if (task.isDeleted) task.deletedAtSec else task.dueAtSec
        nowSec - gone > KEEP_SEC
    }

    private const val KEEP_SEC = 90L * 24 * 60 * 60
}
