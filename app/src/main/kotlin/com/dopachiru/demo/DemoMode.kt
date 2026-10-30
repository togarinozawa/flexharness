package com.dopachiru.demo

import android.content.Context
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import com.dopachiru.BuildConfig
import com.dopachiru.core.demo.DemoRules
import com.dopachiru.core.time.TimeScale
import com.dopachiru.runtime.DopaRuntime

/**
 * 展示用の版(`:app:assembleDemo`)だけで効くもの。本番の APK では [enabled] が偽で、何もしない。
 *
 * 設定は SharedPreferences に置く。起動の最初に同期で読めないと、
 * 縮尺が効く前に1回だけ本番の速さで判定してしまう(DataStore は suspend)。
 */
object DemoMode {

    val enabled: Boolean = BuildConfig.DEMO

    /** 何も選んでいないときの縮尺。30分の持ち時間が2分半になる。 */
    const val DEFAULT_SECONDS_PER_MINUTE = 5L

    /** 選べる縮尺。60 は「縮めない」= 本番と同じ速さで見せたいとき。 */
    val CHOICES = listOf(1L, 2L, 5L, 10L, 30L, 60L)

    private const val PREFS = "demo"
    private const val KEY_SCALE = "secondsPerMinute"
    private const val KEY_HUD = "hud"

    private var prefs: android.content.SharedPreferences? = null

    /** 画面が読む値。書き換えは [setScale] / [setHud] から。 */
    val scale = mutableLongStateOf(TimeScale.REAL)
    val hud = mutableStateOf(true)

    fun init(context: Context) {
        if (!enabled) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        TimeScale.secondsPerMinute = p.getLong(KEY_SCALE, DEFAULT_SECONDS_PER_MINUTE)
        scale.longValue = TimeScale.secondsPerMinute
        hud.value = p.getBoolean(KEY_HUD, true)
    }

    /**
     * 縮尺を変える。**デモのルールも入れ直す** ── 名前に実際の秒が書いてあるので、
     * 縮尺だけ変えると「30秒で止める」が1分で止まる。
     */
    suspend fun setScale(secondsPerMinute: Long) {
        if (!enabled) return
        TimeScale.secondsPerMinute = secondsPerMinute
        scale.longValue = TimeScale.secondsPerMinute
        prefs?.edit()?.putLong(KEY_SCALE, TimeScale.secondsPerMinute)?.apply()
        if (hasDemoRules()) installRules()
    }

    fun setHud(on: Boolean) {
        if (!enabled) return
        hud.value = on
        prefs?.edit()?.putBoolean(KEY_HUD, on)?.apply()
    }

    suspend fun hasDemoRules(): Boolean =
        DopaRuntime.rules.getAll().any(DemoRules::isDemo)

    /**
     * デモのルールを入れる。前に入れたデモのルールは消してから入れ直すので、
     * 何度押しても同じ一式になる。自分で作ったルール(uid が [DemoRules.UID_PREFIX] で始まらない)には触らない。
     */
    suspend fun installRules(): Int {
        if (!enabled) return 0
        removeRules()
        val entries = DemoRules.build(TimeScale.secondsPerMinute)
        entries.forEach { DopaRuntime.rules.upsert(it.rule) }
        DopaRuntime.demoReset()
        return entries.size
    }

    suspend fun removeRules() {
        DopaRuntime.rules.getAll()
            .filter(DemoRules::isDemo)
            .forEach { DopaRuntime.rules.delete(it.id) }
    }

    /** 次の人に渡す前に。ルールは残し、数えたものだけ捨てる。 */
    suspend fun reset() {
        if (!enabled) return
        DopaRuntime.demoReset()
    }

    /** 実時間の秒を「1:05」のように。札は分より細かく動かないと、縮めた意味が見えない。 */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }
}
