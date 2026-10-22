package com.dopachiru.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.dopachiru.DopaApplication
import com.dopachiru.R
import com.dopachiru.data.GuardGaps
import com.dopachiru.runtime.DopaRuntime

/**
 * 見張り(ユーザー補助・重ね表示)が外れていないか、常駐サービスの折々に確かめる。
 *
 * ## なぜ要るか
 *
 * FlexHarnessの取り締まりは、そのほとんどがユーザー補助の上に乗っている。これを切られると、
 * アプリは起動していても**何も止めない**。いちばん危ないのは、切ったこと自体を忘れて
 * 「守られているつもり」で過ごすこと ── 誘惑に負けて切ったのなら、なおさら気づかない。
 * だから外れたら、目に入る強さの通知で知らせる。
 *
 * ## 一度でも許可したものだけ見張る
 *
 * 入れたてでまだ許可していない段階で鳴らすと、設定を促す案内と区別がつかず、ただ煩い。
 * **一度許可したものが消えたとき**だけ鳴らす。許可した事実は端末に残すので、
 * プロセスが作り直されても判断がぶれない。
 *
 * ## 重ね表示について
 *
 * ブロック画面は本来ユーザー補助のオーバーレイで出すので、重ね表示は切られても
 * すぐ困るわけではない(フォールバック用)。それでもユーザーが気にしていたので、
 * 一度許可したものが外れたら同じように知らせる ── 気づける材料は多いほうがよい。
 */
object PermissionGuard {

    /** このプロセスで一度でも確かめたか。常駐ごと止まっていた空白を、起動直後に1回だけ見るため。 */
    @Volatile
    private var checkedOnce = false

    /**
     * いまの許可の状態を確かめ、外れていれば通知する。外れていなければ通知を消す。
     * 常駐サービスのループから、そのつど呼ぶ。
     */
    fun check(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nowSec = System.currentTimeMillis() / 1000
        val accessibilityOn = isAccessibilityEnabled(context)
        val overlayOn = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(true)

        // 常駐ごと止まっていたか(強制停止・アプリの無効化)。止まっているあいだは何も
        // 動けないので、戻ってきた最初の1回で、最後に生きていた時刻からの空白を見る
        val aliveAt = prefs.getLong(KEY_ALIVE_AT, 0L)
        if (!checkedOnce) {
            checkedOnce = true
            if (aliveAt > 0 && nowSec - aliveAt > SILENT_GAP_SEC) reportSilentGap(context, aliveAt, nowSec)
        }

        // 許可を観測したら「一度は許可された」と覚える
        prefs.edit().apply {
            putLong(KEY_ALIVE_AT, nowSec)
            if (accessibilityOn) putBoolean(KEY_ACC_GRANTED, true)
            if (overlayOn) putBoolean(KEY_OVERLAY_GRANTED, true)
            apply()
        }

        val accessibilityLost = !accessibilityOn && prefs.getBoolean(KEY_ACC_GRANTED, false)
        val overlayLost = !overlayOn && prefs.getBoolean(KEY_OVERLAY_GRANTED, false)

        // 外れた時刻を覚える。戻ったら、そのあいだの使用をまとめて出す
        val lostAt = prefs.getLong(KEY_LOST_AT, 0L)
        if (accessibilityLost && lostAt == 0L) {
            prefs.edit().putLong(KEY_LOST_AT, nowSec).apply()
            // ほかの端末にすぐ知らせる。5分刻みの同期を待つと、そのあいだに崩れる
            DopaRuntime.syncSoon()
        } else if (!accessibilityLost && lostAt > 0L) {
            prefs.edit().putLong(KEY_LOST_AT, 0L).apply()
            report(context, GuardGaps.measure(context, lostAt, nowSec))
            DopaRuntime.syncSoon()
        }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!accessibilityLost && !overlayLost) {
            manager.cancel(NOTIFICATION_ID)
            return
        }

        // 鳴らし直すのは間を空けて。毎回鳴らすと通知ごと切られる
        val lastAlertMs = prefs.getLong(KEY_LAST_ALERT_MS, 0L)
        val nowMs = System.currentTimeMillis()
        val alert = nowMs - lastAlertMs >= REALERT_MS
        if (alert) prefs.edit().putLong(KEY_LAST_ALERT_MS, nowMs).apply()
        val since = prefs.getLong(KEY_LOST_AT, 0L).takeIf { it > 0 }
        // 通知を出す許可自体が無い端末では、鳴らせないので黙って戻る
        runCatching {
            manager.notify(NOTIFICATION_ID, build(context, accessibilityLost, overlayLost, since, nowSec, alert))
        }
    }

    /** ユーザー補助が外れた時刻。外れていなければ 0。名簿に載せて、ほかの端末に知らせる。 */
    fun lostAtSec(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LOST_AT, 0L)

    /** 最後に見張りが止まっていたときのまとめ(人が読む1行)。無ければ空。 */
    fun lastGap(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_GAP, "").orEmpty()

    /**
     * 常駐ごと止まっていた空白を報せる。
     *
     * 電源を切っていただけの夜まで責めないよう、**そのあいだに何か使っていたときだけ**出す。
     * 使用状況へのアクセスが無ければ区別がつかないので出さない。
     */
    private fun reportSilentGap(context: Context, fromSec: Long, toSec: Long) {
        val gap = GuardGaps.measure(context, fromSec, toSec)
        val used = gap.usedMinutes ?: return
        if (used < SILENT_GAP_MIN_USE) return
        report(context, gap)
    }

    private fun report(context: Context, gap: GuardGaps.Gap) {
        val text = GuardGaps.describe(gap)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_GAP, text)
            .apply()
        val notification = NotificationCompat.Builder(context, DopaApplication.CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("見張りが止まっていました")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(GAP_NOTIFICATION_ID, notification) }
    }

    /**
     * 外れているあいだ出し続ける通知。
     *
     * **スワイプで消せない**(ongoing)。前は消せたので、消したら戻すまで何も言わず、
     * 外したまま数日たっていた。戻せば消える。
     */
    private fun build(
        context: Context,
        accessibilityLost: Boolean,
        overlayLost: Boolean,
        sinceSec: Long?,
        nowSec: Long,
        alert: Boolean,
    ) = NotificationCompat.Builder(context, DopaApplication.CHANNEL_GUARD)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(
            "FlexHarnessの見張りが外れています" +
                (sinceSec?.let { "(${((nowSec - it) / 60).coerceAtLeast(0)}分前から)" } ?: ""),
        )
        .setContentText(message(accessibilityLost, overlayLost))
        .setStyle(NotificationCompat.BigTextStyle().bigText(message(accessibilityLost, overlayLost)))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_ERROR)
        .setOngoing(accessibilityLost)
        .setAutoCancel(!accessibilityLost)
        .setOnlyAlertOnce(!alert)
        .setContentIntent(fixIntent(context, accessibilityLost))
        .build()

    private fun message(accessibilityLost: Boolean, overlayLost: Boolean): String = when {
        accessibilityLost && overlayLost ->
            "ユーザー補助と重ね表示の許可が外れています。いまは制限が効きません。タップして戻してください。"
        accessibilityLost ->
            "ユーザー補助が外れています。いまはアプリを何も止められません。タップして戻してください。"
        else ->
            "重ね表示の許可が外れています。ブロック画面が出せないことがあります。タップして戻してください。"
    }

    /** 外れているほうの設定へ直行させる。両方なら、まず要のユーザー補助へ。 */
    private fun fixIntent(context: Context, accessibilityLost: Boolean): PendingIntent {
        val intent = if (accessibilityLost) {
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        } else {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            if (accessibilityLost) 0 else 1,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** FlexHarnessのユーザー補助が有効か。設定画面と同じ見方。 */
    private fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${DopaAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private const val PREFS = "permission_guard"
    private const val KEY_ACC_GRANTED = "accessibility_once_granted"
    private const val KEY_OVERLAY_GRANTED = "overlay_once_granted"
    private const val KEY_LOST_AT = "accessibility_lost_at_sec"
    private const val KEY_LAST_ALERT_MS = "last_alert_ms"
    private const val KEY_ALIVE_AT = "alive_at_sec"
    private const val KEY_LAST_GAP = "last_gap"
    private const val NOTIFICATION_ID = 1002
    private const val GAP_NOTIFICATION_ID = 1005

    /** 外れているあいだ、鳴らし直す間隔。 */
    private const val REALERT_MS = 15L * 60 * 1000

    /**
     * 常駐が止まっていたとみなす空白。画面が消えているあいだの刻みは10分なので、それより長く取る。
     */
    private const val SILENT_GAP_SEC = 30L * 60

    /** 常駐が止まっていたあいだに、これだけ使っていたら報せる(分)。 */
    private const val SILENT_GAP_MIN_USE = 5
}
