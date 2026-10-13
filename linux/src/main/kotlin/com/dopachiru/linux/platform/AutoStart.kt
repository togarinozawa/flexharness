package com.dopachiru.linux.platform

import java.io.File

/**
 * ログインしたら、ドパチルも一緒に立ち上がるようにする。
 *
 * ## なぜ ~/.config/autostart なのか
 * XDG の自動起動の置き場で、KDE の「システム設定 → 自動起動」にそのまま並ぶ。
 * **本人が必ず切れる**場所に置く ── 自分で入れた見張りを自分で外せないのは、
 * 抑止ではなく事故なので。systemd のユーザー単位も使えるが、設定画面に出ないぶん
 * 切りかたが分かりにくい。
 */
object AutoStart {

    /** 自動起動で立ち上がったときに付ける印。窓を出さず、トレイだけで始まる。 */
    const val STARTUP_FLAG = "--startup"

    private val file: File
        get() {
            val config = System.getenv("XDG_CONFIG_HOME")?.ifBlank { null }
                ?: (System.getProperty("user.home") + "/.config")
            return File(config, "autostart/dopachiru.desktop")
        }

    /**
     * いま動いている起動ファイルの在り処。開発中に Gradle から動かしていれば null。
     *
     * jpackage が渡してくる値を使う。`gradlew :linux:run` では渡ってこない ──
     * そのときは java を指してしまうので、**登録できないことにする**。
     */
    fun launcherPath(): String? =
        System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }

    val supported: Boolean get() = launcherPath() != null

    fun isEnabled(): Boolean = file.isFile

    /**
     * 置いてはあるが、KDE の設定で切られているか。
     *
     * KDE は切っても消さずに `Hidden=true` を書き足す。これを見ないと、切られているのに
     * 「入っています」と出し続けることになる。
     */
    fun blockedByOs(): Boolean = runCatching {
        file.isFile && file.readLines().any {
            it.trim().replace(" ", "").equals("Hidden=true", ignoreCase = true)
        }
    }.getOrDefault(false)

    /**
     * 置く。パスは毎回書き直す ── 入れ直して場所が変わると、前のパスは黙って効かなくなる。
     */
    fun enable(): Boolean {
        val path = launcherPath() ?: return false
        return runCatching {
            file.parentFile.mkdirs()
            file.writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=Dopachiru
                Comment=ドパチル(アプリの使いすぎを止める)
                Exec="$path" $STARTUP_FLAG
                X-GNOME-Autostart-enabled=true
                """.trimIndent() + "\n",
            )
            true
        }.getOrDefault(false)
    }

    fun disable(): Boolean = runCatching {
        if (file.exists()) file.delete()
        true
    }.getOrDefault(false)

    /**
     * 設定に書いてある望みと、実際の置き場を合わせる。起動のたびに呼ぶ。
     *
     * KDE で切られている(`Hidden=true`)ものは**書き直さない**。書き直すと本人の
     * 「切った」を黙って取り消すことになる。
     */
    fun reconcile(desired: Boolean): Boolean {
        if (!supported) return isEnabled()
        if (blockedByOs()) return isEnabled()
        if (desired) enable() else disable()
        return isEnabled()
    }
}
