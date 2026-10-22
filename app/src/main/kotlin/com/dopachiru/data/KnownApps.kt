package com.dopachiru.data

/**
 * よく止めたくなるアプリ。**入っていなくても**ルールの対象に選べるようにするための一覧。
 *
 * ## なぜ要るか
 *
 * 対象は端末に入っているアプリからしか選べなかった。衝動に負けないよう X や YouTube を
 * 消しておくと、そのあいだに作ったルールには入れられない。すると**入れ直した瞬間に
 * 素通り**になる ── FlexHarnessを外すまでもなく抜けられてしまう。
 *
 * 先に名前を挙げておけば、消してあるうちから対象に入れておける。入れ直したら
 * その場で止まる。
 *
 * 載せるのはパッケージ名を確かめられたものだけ。違う名前を載せると、選んだつもりで
 * 何も止まらない。ここに無いものは、選ぶ画面でパッケージ名を直接打てる。
 */
object KnownApps {

    data class Known(val packageName: String, val label: String)

    val all: List<Known> = listOf(
        Known("com.twitter.android", "X(Twitter)"),
        Known("com.google.android.youtube", "YouTube"),
        Known("jp.nicovideo.android", "ニコニコ動画"),
        Known("com.instagram.android", "Instagram"),
        Known("com.instagram.barcelona", "Threads"),
        Known("com.zhiliaoapp.musically", "TikTok"),
        Known("com.ss.android.ugc.trill", "TikTok(アジア版)"),
        Known("com.facebook.katana", "Facebook"),
        Known("com.reddit.frontpage", "Reddit"),
        Known("xyz.blueskyweb.app", "Bluesky"),
        Known("tv.twitch.android.app", "Twitch"),
        Known("tv.abema", "ABEMA"),
        Known("com.netflix.mediaclient", "Netflix"),
        Known("jp.pxv.android", "pixiv"),
        Known("com.discord", "Discord"),
    )

    private val byPackage = all.associateBy { it.packageName }

    fun labelOf(packageName: String): String? = byPackage[packageName]?.label

    operator fun contains(packageName: String): Boolean = packageName in byPackage

    /**
     * パッケージ名として読めるか。手で打ったものを受け付けるかの判定。
     *
     * 厳密な決まりより緩いが、「空白を含まず、点で区切られた2つ以上の語」で足りる。
     * ここを通っても実在するとは限らない ── 入っていないアプリを選べることが要なので。
     */
    fun looksLikePackage(text: String): Boolean = PACKAGE.matches(text.trim())

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
}
