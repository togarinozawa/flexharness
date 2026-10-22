package com.dopachiru.linux.platform

import java.util.concurrent.TimeUnit

/**
 * デスクトップの通知。KDE の通知欄に出る。
 *
 * AWT のトレイ通知は Plasma 6 では橋渡しの都合で出ないことがあるので、
 * freedesktop の通知を `notify-send` で出す(libnotify-bin。KDE なら大抵入っている)。
 * 無ければ黙る ── 画面の帯([com.dopachiru.linux.ui] の端末の画面)にも同じことを出してある。
 */
object DesktopNotify {
    fun send(title: String, body: String) {
        runCatching {
            val p = ProcessBuilder(
                "notify-send", "--app-name=FlexHarness", "--urgency=critical", title, body,
            ).redirectErrorStream(true).start()
            p.waitFor(3, TimeUnit.SECONDS)
        }
    }
}
