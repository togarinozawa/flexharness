package com.dopachiru.linux.platform

/**
 * ルールの対象を選ぶための一覧。
 *
 * 入っているアプリ([InstalledApps])だけでは、AppImage や手で展開したもの、
 * ゲームのランチャーから起動するものを取りこぼす。そこで
 * **いまウィンドウを持って動いているもの**も KWin に聞いて出す。
 */
object RunningApps {

    data class RunningApp(
        val processName: String,
        val label: String,
        /** 見えているウィンドウのタイトル。どのアプリか思い出す手がかりに出す。 */
        val windowTitle: String,
    )

    /** いま見えているウィンドウを持つアプリ。KWin につながらなければ空。 */
    fun visible(): List<RunningApp> {
        val found = LinkedHashMap<String, String>()
        val own = ProcessHandle.current().pid()
        for (w in KWin.windows()) {
            if (w.pid.toLong() == own) continue
            val id = AppIds.of(w) ?: continue
            if (id in ProtectedProcesses) continue
            found.putIfAbsent(id, w.caption)
        }
        return found.map { (id, title) ->
            RunningApp(processName = id, label = ForegroundApp.labelFor(id), windowTitle = title)
        }.sortedBy { it.label.lowercase() }
    }
}
