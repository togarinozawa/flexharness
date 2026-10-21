package com.dopachiru.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * システムの裏方アプリを見分ける。許可リスト型(「必要なもの以外ぜんぶ」)の対象から外すため。
 *
 * ## 裏方とは
 *
 * **消せない(システムに焼き込まれている)うえに、ホーム画面にも出ない**アプリ。
 * Google Play 開発者サービス、アンインストールや権限の確認、アカウントの選択など。
 * 本人が開くのではなく、別のアプリの用事で勝手に前に出てくる。
 *
 * ## 2つとも要る理由
 *
 *  - 「消せない」だけで切ると、最初から入っている YouTube や Chrome まで外れる。
 *    許可リスト型で止めたいのはまさにそれなので、外してはいけない
 *  - 「ホーム画面に出ない」だけで切ると、入れたアプリの裏の画面(ショートカットから
 *    開くものなど)まで外れて、抜け道になる
 *
 * 電話やホームのように**名指しでも止めてはいけない**ものは [ProtectedApps] の仕事。
 * こちらは全指定から外すだけで、名指しすれば止められる。
 */
object SystemApps {

    fun isBackstage(context: Context, packageName: String): Boolean = runCatching {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
        system && !hasLauncherEntry(pm, packageName)
    }.getOrDefault(false)

    /**
     * ホーム画面(アプリ一覧)に出るか。
     *
     * 判定の途中で引くので、見つからない・権限が無いときは「出る」に倒す ──
     * 倒し先を間違えると、止めるべきアプリが許可リスト型をすり抜ける。
     */
    private fun hasLauncherEntry(pm: PackageManager, packageName: String): Boolean =
        runCatching { pm.getLaunchIntentForPackage(packageName) != null }.getOrDefault(true)
}
