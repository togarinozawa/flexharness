package com.dopachiru.linux.platform

import java.io.File
import java.util.Locale

/**
 * 入っているアプリの一覧。.desktop ファイルから作る。
 *
 * ## なぜ .desktop なのか
 *
 * アプリのメニューに並ぶものは全部これで、**本人が見ている名前**(`Name[ja]`)と、
 * KWin が前面のウィンドウに付ける名前(`desktopFileName`)の両方が1か所で取れる。
 * パッケージの一覧(dpkg)ではライブラリの海になり、Flatpak や手で入れたものは漏れる。
 *
 * ## 取りこぼしは埋める
 *
 * AppImage やゲームのように .desktop を持たないものは拾えない。画面では
 * **いま動いているもの([RunningApps])と混ぜて**出し、それでも足りなければ
 * 識別子を直接打てるようにしてある。
 */
object InstalledApps {

    /** 一覧に出すもの。[processName] は .desktop の名前(小文字)。 */
    data class Entry(val processName: String, val label: String)

    private data class Scan(
        val entries: List<Entry>,
        val labels: Map<String, String>,
        /** `StartupWMClass`(小文字) → 識別子。X11 のアプリの WM_CLASS を引き直すため。 */
        val byWmClass: Map<String, String>,
    )

    @Volatile
    private var cache: Scan? = null

    fun all(): List<Entry> = scanned().entries

    fun refresh(): List<Entry> = scan().also { cache = it }.entries

    fun labelOf(id: String): String? = scanned().labels[id.lowercase()]

    fun idForWmClass(wmClass: String): String? = scanned().byWmClass[wmClass.lowercase()]

    private fun scanned(): Scan = cache ?: scan().also { cache = it }

    /**
     * 探す場所。**先に見つけたほうが勝つ** ── 利用者の置いたものが、同じ名前の
     * 配布物のものを上書きする(XDG の決まりどおり)。
     */
    private fun roots(): List<File> {
        val home = System.getProperty("user.home")
        val dataHome = System.getenv("XDG_DATA_HOME")?.ifBlank { null } ?: "$home/.local/share"
        val dataDirs = (System.getenv("XDG_DATA_DIRS")?.ifBlank { null } ?: "/usr/local/share:/usr/share")
            .split(':')
        return (
            listOf(dataHome) + dataDirs +
                // XDG_DATA_DIRS に入っていない環境もあるので、Flatpak の置き場は名指しで足す
                listOf("$home/.local/share/flatpak/exports/share", "/var/lib/flatpak/exports/share")
            )
            .map { File(it, "applications") }
            .filter { it.isDirectory }
            .distinctBy { it.canonicalPath }
    }

    private fun scan(): Scan {
        val seen = HashSet<String>()
        val entries = ArrayList<Entry>()
        val labels = HashMap<String, String>()
        val byWmClass = HashMap<String, String>()

        for (root in roots()) {
            for (file in walk(root, MAX_DEPTH)) {
                // 下のフォルダにあるものは「フォルダ-名前」が識別子になる(XDG の決まり)
                val id = file.relativeTo(root).path.removeSuffix(".desktop").replace('/', '-').lowercase()
                if (!seen.add(id)) continue
                val entry = parse(file) ?: continue
                if (entry.hidden) continue
                labels[id] = entry.name
                entry.wmClass?.let { byWmClass.putIfAbsent(it.lowercase(), id) }
                if (!entry.noDisplay) entries += Entry(id, entry.name)
            }
        }

        return Scan(entries.sortedBy { it.label.lowercase() }, labels, byWmClass)
    }

    private class Parsed(
        val name: String,
        val wmClass: String?,
        val noDisplay: Boolean,
        val hidden: Boolean,
    )

    /**
     * `[Desktop Entry]` の組だけを読む。ほかの組(`[Desktop Action new-window]` など)にも
     * `Name=` があり、混ぜると名前が「新しいウィンドウ」になる。
     */
    private fun parse(file: File): Parsed? {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        val lang = Locale.getDefault().language.ifBlank { "ja" }
        var inEntry = false
        val values = HashMap<String, String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEntry = line == "[Desktop Entry]"
                continue
            }
            if (!inEntry || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            values.putIfAbsent(line.substring(0, eq).trim(), line.substring(eq + 1).trim())
        }
        if (values["Type"] != "Application") return null
        val name = values["Name[$lang]"] ?: values["Name"] ?: return null
        return Parsed(
            name = name,
            wmClass = values["StartupWMClass"]?.ifBlank { null },
            noDisplay = values["NoDisplay"].equals("true", ignoreCase = true),
            hidden = values["Hidden"].equals("true", ignoreCase = true),
        )
    }

    /** 深さを切って歩く。壊れた連結で戻ってこないことがあるので `walkTopDown` は使わない。 */
    private fun walk(dir: File, depth: Int): List<File> {
        if (depth <= 0) return emptyList()
        val children = dir.listFiles() ?: return emptyList()
        val here = children.filter { it.isFile && it.name.endsWith(".desktop") }
        val below = children.filter { it.isDirectory }.flatMap { walk(it, depth - 1) }
        return here + below
    }

    private const val MAX_DEPTH = 3
}
