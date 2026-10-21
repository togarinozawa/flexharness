package com.dopachiru.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dopachiru.DopaApplication
import com.dopachiru.MainActivity
import com.dopachiru.R
import com.dopachiru.data.KnownApps
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.ui.rules.InstalledApps

/**
 * アプリが入った・消えたのを見張る。
 *
 * ## なぜ要るか
 *
 * 衝動に負けないよう X を消しておき、衝動に負けて入れ直す ── この流れでは、
 * **入れ直した X がどのルールにも入っていない**と素通りになる。対象に入っていれば
 * 入れた瞬間から止まるが、入っていなければ、入れ直したこと自体に気づかせたい。
 *
 * 知らせるのは、よく止めたくなるアプリ([KnownApps])が入って、**どのルールにも
 * 当たっていない**ときだけ。入れるたびに鳴らすと、ただの騒音になる。
 *
 * ## 常駐サービスで受ける理由
 *
 * Android 8 からは、アプリが入った報せをマニフェストでは受けられない。
 * 動いているあいだに登録した受け口にだけ届く。
 */
class InstallWatcher(private val context: Context) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            // 一覧を作り直させる。でないと入れ直したものが「未インストール」のまま出る
            InstalledApps.invalidate()
            if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
            // 更新は入れ直しではない
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            onInstalled(pkg)
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun onInstalled(pkg: String) {
        if (pkg !in KnownApps) return
        if (DopaRuntime.isTargeted(pkg)) return
        val label = InstalledApps.labelOf(context, pkg)
        val text = "$label が入りました。どのルールにも入っていないので、いまは止まりません。" +
            "タップしてルールに足してください。"
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, DopaApplication.CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$label を入れ直しました")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val NOTIFICATION_ID = 1003
    }
}
