package com.dopachiru.linux.platform

import java.nio.file.Files
import java.nio.file.Path

/**
 * いま前面にあるアプリ。
 *
 * Android の `packageName`、Windows の `chrome.exe` にあたるものは、Linux では
 * **.desktop ファイルの名前**(`firefox`、`org.kde.dolphin` のような)。ルールの対象指定は
 * これで書く。実行ファイル名にしないのは、Flatpak や Electron のアプリでは
 * 実行ファイルが `bwrap` や `electron` になって、アプリを見分けられないため。
 */
data class ForegroundApp(
    /** 小文字の識別子。例: `firefox`、`org.kde.dolphin` */
    val processName: String,
    val pid: Int,
    /** KWin のウィンドウの鍵。最小化に使う。 */
    val windowId: String,
    val windowTitle: String,
    val exePath: String,
) {
    /** 画面に出す名前。入っているアプリの名前が分かればそれ、無ければ識別子から作る。 */
    val label: String get() = labelFor(processName)

    companion object {
        fun labelFor(processName: String): String =
            InstalledApps.labelOf(processName)
                ?: processName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}

/**
 * 前面ウィンドウを見に行く。
 *
 * 実際に見ているのは KWin の見張り役で、ここは最後に報せてきたものを返すだけ
 * ([KWin.active])。呼ぶ側は Windows 版と同じく1秒ごとに聞きに来る。
 */
object ForegroundWatcher {

    fun current(): ForegroundApp? {
        val w = KWin.active() ?: return null
        if (w.id.isBlank()) return null
        val id = AppIds.of(w) ?: return null
        return ForegroundApp(
            processName = id,
            pid = w.pid,
            windowId = w.id,
            windowTitle = w.caption,
            exePath = exePathOf(w.pid).orEmpty(),
        )
    }

    /** pid から実行ファイルのパス。取れなければ null(よその利用者のプロセスなど)。 */
    fun exePathOf(pid: Int): String? {
        if (pid <= 0) return null
        return runCatching { Files.readSymbolicLink(Path.of("/proc/$pid/exe")).toString() }.getOrNull()
    }
}

/**
 * ウィンドウからアプリの識別子を決める。
 *
 * 当てになる順に見る:
 *  1. KWin が突き止めた .desktop の名前(Wayland のアプリはたいていこれで決まる)
 *  2. WM_CLASS を .desktop の `StartupWMClass` で引き直したもの(X11 のアプリ向け)
 *  3. WM_CLASS そのもの
 *  4. 実行ファイル名
 *
 * どれも小文字にそろえる。`com.valvesoftware.Steam` のように大文字の混じる名前があり、
 * 書いた側と見えた側で大小が食い違うと、ルールが黙って効かなくなる。
 */
object AppIds {
    fun of(w: KWin.WindowInfo): String? {
        val fromDesktop = normalize(w.app)
        if (fromDesktop != null) return fromDesktop
        val cls = normalize(w.cls)
        if (cls != null) return InstalledApps.idForWmClass(cls) ?: cls
        normalize(w.name)?.let { return InstalledApps.idForWmClass(it) ?: it }
        return ForegroundWatcher.exePathOf(w.pid)?.let { normalize(it.substringAfterLast('/')) }
    }

    /** `/usr/share/applications/firefox.desktop` のような形で来ても `firefox` にする。 */
    fun normalize(raw: String): String? =
        raw.trim().substringAfterLast('/').removeSuffix(".desktop").lowercase().ifBlank { null }
}
