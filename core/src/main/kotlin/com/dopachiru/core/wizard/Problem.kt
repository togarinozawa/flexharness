package com.dopachiru.core.wizard

import com.dopachiru.core.preset.RulePreset
import com.dopachiru.core.preset.RulePresets

/**
 * 対話モードの最初の問い「何に困っている?」の選択肢。
 *
 * 雛形の一覧は「どういう措置か」(軽い・上限・癖・強い)で分けてある。
 * 作り方を知っている人には読めるが、困っている最中の人が知っているのは
 * 措置の種類ではなく**困りごと**のほう。入口を困りごとにして、
 * そこから雛形へ降りる。
 *
 * 並びは雛形の ID で持つ。中の順は軽いものから ── 強いものから始めると続かない。
 * 1つの雛形が2つの困りごとに出てもよい(夜の先約は「夜更かし」にも「押し切る」にも効く)。
 */
enum class Problem(
    val label: String,
    val help: String,
    private val presetIds: List<String>,
    /**
     * ルールではなく締切の集中で扱う困りごと。対話モードは雛形を出さず、締切の画面へ送る。
     *
     * 締切は「終わったら解ける」「最低時間」を持っていて、ルールの形には収まらない。
     */
    val opensDeadline: Boolean = false,
) {
    LONG(
        "だらだら長く使ってしまう",
        "気づいたら30分、1時間たっている",
        listOf("session_timer", "long_session", "forced_break", "hourly_budget", "daily_budget", "either_limit", "declare_first"),
    ),
    OPENING(
        "何度も開いてしまう",
        "用もないのに手が伸びる・閉じてもまた開く",
        listOf("open_delay", "sometimes_friction", "state_intention", "quick_reopen", "reopen", "cooldown_between", "app_chain"),
    ),
    NIGHT(
        "夜更かししてしまう",
        "寝る前に見はじめて止まらない",
        listOf("site_social_night", "night", "night_precommit"),
    ),
    WORK(
        "勉強・作業中に触ってしまう",
        "やるべきことの最中に逃げる",
        listOf("study_prep", "work_hours", "study_session", "reservation_only", "calendar_focus"),
    ),
    FEED(
        "特定の画面だけ見すぎる",
        "ショート・リール・おすすめ・動画",
        listOf("shorts_in_app", "site_shorts", "youtube_radio", "browser_video_off"),
    ),
    OVERRIDE(
        "止めても押し切ってしまう",
        "ルールはあるのに破る",
        listOf("escalate", "night_precommit"),
    ),
    TOTAL(
        "とにかく使えなくしたい",
        "条件は要らない。開いたら止める",
        listOf("always_block"),
    ),
    DEADLINE(
        "締切があるのに逃げてしまう",
        "締切の集中で、決めた時間に閉まるようにする",
        emptyList(),
        opensDeadline = true,
    ),
    ;

    /** いま選べる雛形。凍結中のもの(カレンダー)は出さない。 */
    fun presets(): List<RulePreset> = presetIds.mapNotNull { RulePresets.byId(it) }

    /** 並びに書いた ID。実在するかを試験で見るため。 */
    internal val ids: List<String> get() = presetIds
}
