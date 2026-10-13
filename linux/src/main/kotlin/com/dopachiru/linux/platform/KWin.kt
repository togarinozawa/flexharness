package com.dopachiru.linux.platform

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * KWin との窓口。KDE Plasma 6 の Wayland で、前面のウィンドウを知り、最小化するため。
 *
 * ## なぜ KWin スクリプトなのか
 *
 * Wayland では、ほかのアプリのウィンドウを外から覗く口が**わざと**無い
 * (X11 の `_NET_ACTIVE_WINDOW` にあたるものが無い)。知っているのはコンポジタだけで、
 * KDE ではそれが KWin。KWin は JS のスクリプトを読み込めて、スクリプトからは
 * `callDBus` で外に話しかけられる。そこで:
 *
 *  - **見張り役**のスクリプトを1本、常駐させる。前面が変わるたびに、こちらの
 *    D-Bus の口([BUS_NAME])を叩いて報せてくる
 *  - **使い捨て**のスクリプトで、一覧を取る・最小化するといった単発の用を足す。
 *    結果は同じ口に返してもらい、受け取ったら下ろす
 *
 * kdotool(KDE 向けの xdotool)と同じやり方で、KWin の版をまたいで生き残ってきた形。
 *
 * ## 繋がらないとき
 *
 * KDE でない・D-Bus が無いときは [available] が false になり、前面は「分からない」
 * (null)のまま。**落ちはしないが、制限も効かない。** 画面に理由を出すために
 * [problem] を持っておく。
 */
object KWin {

    /** こちらの口の名前。KWin スクリプトがここを呼ぶ。 */
    const val BUS_NAME = "com.dopachiru.Linux"
    const val OBJECT_PATH = "/Bridge"
    const val INTERFACE = "com.dopachiru.Bridge"

    /** 見張り役の名前。起動のたびに下ろして積み直す(前の起動の残りを掴まないように)。 */
    private const val WATCHER = "dopachiru-watcher"

    /** 使い捨ての答えを待つ長さ。KWin は普通 数十ミリ秒で返す。 */
    private const val ONCE_TIMEOUT_MS = 2_000L

    /** 見張り役が生きているかを確かめる間隔。KWin が再起動すると黙って消える。 */
    private const val WATCHER_CHECK_MS = 30_000L

    private val json = Json { ignoreUnknownKeys = true }

    /** 前面のウィンドウ。KWin が報せてきたものをそのまま持つ。 */
    @Serializable
    data class WindowInfo(
        /** KWin の internalId。ウィンドウを指す鍵(Windows の HWND にあたる)。 */
        val id: String = "",
        val pid: Int = 0,
        val caption: String = "",
        /** WM_CLASS / Wayland の app_id。 */
        val cls: String = "",
        val name: String = "",
        /** KWin が突き止めた .desktop の名前。いちばん当てになる識別子。 */
        val app: String = "",
        val minimized: Boolean = false,
    )

    @Volatile
    private var connection: DBusConnection? = null

    @Volatile
    private var latest: WindowInfo? = null

    @Volatile
    var problem: String = ""
        private set

    val available: Boolean get() = connection != null

    private val pending = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val seq = AtomicLong(0)
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dopachiru-kwin").apply { isDaemon = true }
    }

    @Volatile
    private var lastWatcherCheckMs = 0L

    /** いま前面にあるウィンドウ。まだ何も報せが無ければ null。 */
    fun active(): WindowInfo? {
        ensureStarted()
        maybeCheckWatcher()
        return latest
    }

    @Volatile
    private var lastAttemptMs = 0L

    /** つなぎ損ねたあと、次に試すまでの間。毎秒試すと、そのたびに D-Bus を叩くことになる。 */
    private const val RETRY_MS = 15_000L

    /**
     * 繋ぐ。何度呼んでもよい。失敗したら [problem] に理由を残して null 扱いで続け、
     * [RETRY_MS] ごとに試し直す ── ログインと一緒に立ち上がると、KWin の口が
     * まだ開いていないことがある。一度の失敗で諦めると、再起動するまで何も止まらない。
     */
    @Synchronized
    fun ensureStarted(): Boolean {
        if (connection != null) return true
        val now = System.currentTimeMillis()
        if (lastAttemptMs != 0L && now - lastAttemptMs < RETRY_MS) return false
        lastAttemptMs = now
        var conn: DBusConnection? = null
        return try {
            conn = DBusConnectionBuilder.forSessionBus().build()
            conn.requestBusName(BUS_NAME)
            conn.exportObject(OBJECT_PATH, Receiver)
            loadWatcher(conn)
            connection = conn
            problem = ""
            true
        } catch (e: Throwable) {
            problem = "KWin につながりません: " + (e.message ?: e::class.simpleName.orEmpty()).take(160)
            runCatching { conn?.close() }
            false
        }
    }

    /** 終了時。見張り役を下ろす。残しておくと、居ない口を1秒ごとに呼び続ける。 */
    fun shutdown() {
        val conn = connection ?: return
        runCatching { scripting(conn).unloadScript(WATCHER) }
        runCatching { conn.releaseBusName(BUS_NAME) }
        runCatching { conn.close() }
        connection = null
    }

    // ---- 使い捨て ----------------------------------------------------------

    /** 見えているウィンドウの一覧。タスクバーに出ないものは除く。 */
    fun windows(): List<WindowInfo> {
        val raw = runOnce(
            """
            var out = [];
            var ws = workspace.windowList();
            for (var i = 0; i < ws.length; i++) {
                var w = ws[i];
                if (!w.normalWindow || w.skipTaskbar) continue;
                out.push(${'$'}describe(w));
            }
            ${'$'}reply(JSON.stringify(out));
            """,
        ) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(WindowInfo.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    /** 最小化する。答えは待たない(見張りの刻みを止めないため)。 */
    fun minimize(windowId: String) {
        if (windowId.isBlank()) return
        worker.execute {
            runOnce(
                """
                var ws = workspace.windowList();
                for (var i = 0; i < ws.length; i++) {
                    if (String(ws[i].internalId) === ${quote(windowId)}) ws[i].minimized = true;
                }
                ${'$'}reply("ok");
                """,
            )
        }
    }

    /**
     * 使い捨てのスクリプトを1本走らせて、答えを待つ。
     *
     * 本文の中では `$reply(文字列)` で答えを返し、`$describe(w)` でウィンドウを
     * [WindowInfo] の形にできる。答えが来なくても [ONCE_TIMEOUT_MS] で諦めて、必ず下ろす。
     */
    private fun runOnce(body: String): String? {
        if (!ensureStarted()) return null
        val conn = connection ?: return null
        val req = "once-" + seq.incrementAndGet()
        val name = "dopachiru-$req"
        val future = CompletableFuture<String>()
        pending[req] = future
        val file = writeScript(name, PRELUDE + "\nfunction \$reply(s) { $CALL_PREFIX\"Result\", ${quote(req)}, String(s)); }\n" + body)
        return try {
            val scripting = scripting(conn)
            val id = scripting.loadScript(file.toString(), name)
            if (id < 0) return null
            runScript(conn, id)
            future.get(ONCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            null
        } finally {
            pending.remove(req)
            runCatching { scripting(conn).unloadScript(name) }
            runCatching { Files.deleteIfExists(file) }
        }
    }

    // ---- 見張り役 ----------------------------------------------------------

    private fun loadWatcher(conn: DBusConnection) {
        val scripting = scripting(conn)
        runCatching { scripting.unloadScript(WATCHER) }
        val file = writeScript(WATCHER, PRELUDE + "\n" + WATCHER_BODY)
        val id = scripting.loadScript(file.toString(), WATCHER)
        // 読み込めなかったら投げる。つながったことにすると、前面が永遠に分からないまま黙る
        check(id >= 0) { "KWin が見張り役のスクリプトを読み込みませんでした" }
        runScript(conn, id)
        lastWatcherCheckMs = System.currentTimeMillis()
    }

    /** KWin が入れ直されると見張り役は黙って消える。ときどき確かめて積み直す。 */
    private fun maybeCheckWatcher() {
        val conn = connection ?: return
        val now = System.currentTimeMillis()
        if (now - lastWatcherCheckMs < WATCHER_CHECK_MS) return
        lastWatcherCheckMs = now
        worker.execute {
            val loaded = runCatching { scripting(conn).isScriptLoaded(WATCHER) }.getOrDefault(false)
            if (!loaded) {
                problem = runCatching { loadWatcher(conn) }.fold(
                    onSuccess = { "" },
                    onFailure = { "KWin の見張り役を積み直せません: " + it.message.orEmpty().take(160) },
                )
            }
        }
    }

    /**
     * 読み込んだスクリプトを走らせる。
     *
     * Plasma 6 では `/Scripting/Script<id>`、5 では `/<id>`。6 だけを相手にしているが、
     * 片方で外れたらもう片方を試すだけなので両方書いておく。
     */
    private fun runScript(conn: DBusConnection, id: Int) {
        val paths = listOf("/Scripting/Script$id", "/$id")
        for (path in paths) {
            val ok = runCatching {
                conn.getRemoteObject(KWIN_SERVICE, path, KWinScript::class.java).run()
            }.isSuccess
            if (ok) return
        }
        // どちらも駄目なら、読み込んだぶんをまとめて走らせる口に頼る
        runCatching { scripting(conn).start() }
    }

    private fun scripting(conn: DBusConnection): KWinScripting =
        conn.getRemoteObject(KWIN_SERVICE, "/Scripting", KWinScripting::class.java)

    private fun writeScript(name: String, text: String): Path {
        val dir = Path.of(System.getProperty("java.io.tmpdir"), "dopachiru-kwin-" + ProcessHandle.current().pid())
        Files.createDirectories(dir)
        val file = dir.resolve("$name.js")
        Files.writeString(file, text)
        return file
    }

    /** JS の文字列リテラルにする。JSON の文字列はそのまま JS の文字列として読める。 */
    private fun quote(s: String): String = Json.encodeToString(String.serializer(), s)

    private const val KWIN_SERVICE = "org.kde.KWin"

    private const val CALL_PREFIX = "callDBus(\"$BUS_NAME\", \"$OBJECT_PATH\", \"$INTERFACE\", "

    /**
     * どのスクリプトにも頭に付ける。
     *
     * 値は**すべて文字列にしてから**渡す。数をそのまま渡すと、KWin が D-Bus の型を
     * double にするか int にするかが版で揺れて、こちらの受け口と食い違う。
     */
    private val PRELUDE = """
        function ${'$'}describe(w) {
            if (!w) return null;
            return {
                id: String(w.internalId),
                pid: Number(w.pid) || 0,
                caption: String(w.caption || ""),
                cls: String(w.resourceClass || ""),
                name: String(w.resourceName || ""),
                app: String(w.desktopFileName || ""),
                minimized: !!w.minimized
            };
        }
    """.trimIndent()

    /**
     * 見張り役の本体。前面が変わったとき・題名が変わったときに報せる。
     *
     * 題名も見るのは、同じウィンドウの中でタブを替えたことを拾うため
     * (ブラウザの題名は開いているページの題名になる)。
     */
    private val WATCHER_BODY = """
        function report() {
            ${CALL_PREFIX}"Activated", JSON.stringify(${'$'}describe(workspace.activeWindow)));
        }
        var watched = null;
        workspace.windowActivated.connect(function (w) {
            if (watched) { try { watched.captionChanged.disconnect(report); } catch (e) {} }
            watched = w;
            if (w) w.captionChanged.connect(report);
            report();
        });
        report();
    """.trimIndent()

    // ---- 受け口 ------------------------------------------------------------

    @DBusInterfaceName(INTERFACE)
    interface BridgeInterface : DBusInterface {
        @DBusMemberName("Activated")
        fun activated(json: String)

        @DBusMemberName("Result")
        fun result(req: String, json: String)
    }

    private object Receiver : BridgeInterface {
        override fun activated(json: String) {
            latest = if (json == "null" || json.isBlank()) {
                null
            } else {
                runCatching { KWin.json.decodeFromString(WindowInfo.serializer(), json) }.getOrNull()
            }
        }

        override fun result(req: String, json: String) {
            pending.remove(req)?.complete(json)
        }

        override fun getObjectPath(): String = OBJECT_PATH
    }

    @DBusInterfaceName("org.kde.kwin.Scripting")
    interface KWinScripting : DBusInterface {
        fun loadScript(filePath: String, pluginName: String): Int
        fun isScriptLoaded(pluginName: String): Boolean
        fun unloadScript(pluginName: String): Boolean
        fun start()
    }

    @DBusInterfaceName("org.kde.kwin.Script")
    interface KWinScript : DBusInterface {
        fun run()
        fun stop()
    }
}
