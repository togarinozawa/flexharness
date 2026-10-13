package com.dopachiru.linux.platform

/**
 * 何があってもブロックしないアプリ。
 *
 * Android 版の ProtectedApps と同じ役割で、ルールより強く、設定からも外せない。
 * 「必要なアプリ以外ぜんぶ」が書ける以上、選ばなくても巻き込めてしまうので、
 * 巻き込まれると詰むものはここで弾く。
 *
 * システムモニタを入れてあるのは意図的。ドパチル自身を必ず止められるようにしておく。
 * 自分で入れたものを自分で止められなくなるのは、抑止ではなく事故なので。
 *
 * 名前は [AppIds] がそろえた形(.desktop の名前・小文字)。同じものが
 * 版や入れ方で別の名前になるので、両方書いてある。
 */
object ProtectedProcesses {

    private val names = setOf(
        // シェルと画面まわり。塞ぐとデスクトップが操作できなくなる
        "plasmashell",
        "org.kde.plasmashell",
        "kwin_wayland",
        "kwin_x11",
        "org.kde.krunner",
        "krunner",
        "xwaylandvideobridge",
        "org.kde.xwaylandvideobridge",

        // ログオン・認証・画面ロック
        "org.kde.polkit-kde-authentication-agent-1",
        "polkit-kde-authentication-agent-1",
        "org.kde.ksmserver",
        "ksmserver-logout-greeter",
        "org.kde.ksmserver-logout-greeter",
        "kscreenlocker_greet",
        "org.kde.kscreenlocker_greet",
        "pinentry-qt",
        "org.kde.pinentry-qt",
        "kwalletd6",
        "org.kde.kwalletd6",

        // 逃げ道。ドパチルを止める手段は必ず残す
        "systemsettings",
        "org.kde.systemsettings",
        "kcmshell6",
        "org.kde.plasma-systemmonitor",
        "plasma-systemmonitor",
        "org.kde.ksysguard",

        // ドパチル自身。名前より先に pid で弾いてあるので、ここは念のため。
        // `java` は入れない ── Java で動くゲーム(Minecraft のランチャーなど)まで素通しになる
        "dopachiru",
        "dopachiru-dopachiru",
    )

    operator fun contains(processName: String): Boolean = processName.lowercase() in names

    /** 一覧表示用。 */
    fun all(): Set<String> = names
}
