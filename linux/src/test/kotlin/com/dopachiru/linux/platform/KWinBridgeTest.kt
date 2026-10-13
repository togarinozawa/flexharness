package com.dopachiru.linux.platform

import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * KWin の代役を同じセッションバスに立てて、こちらの口と D-Bus の型が噛み合うかを見る。
 *
 * 本物の KWin は JS を走らせるが、代役は走らせない。代わりに、積まれたスクリプトの
 * 中身から「誰に何を返すか」だけを読んで、こちらの口を叩き返す。確かめたいのは
 * **D-Bus の名前・道筋・型**で、そこを間違えると実機で黙って何も起きない。
 *
 * セッションバスが無い環境では飛ばす。流すときは
 * `dbus-run-session -- ./gradlew :linux:test`
 */
class KWinBridgeTest {

    @Test
    fun watcherAndOneShotScriptsTalkBackOverDbus() {
        assumeTrue(
            "セッションバスが無い(dbus-run-session で流す)",
            !System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank(),
        )

        val fake = FakeKWin.start()
        try {
            assertTrue(KWin.ensureStarted(), KWin.problem)
            assertTrue(fake.loaded.containsKey("dopachiru-watcher"), "見張り役が積まれていない")

            // 見張り役が前面を報せてきたことにする
            fake.callBridge {
                it.activated(
                    """{"id":"{abc}","pid":4242,"caption":"YouTube — Mozilla Firefox",""" +
                        """"cls":"firefox","name":"Navigator","app":"firefox","minimized":false}""",
                )
            }
            val fg = waitFor { ForegroundWatcher.current() }
            assertNotNull(fg)
            assertEquals("firefox", fg.processName)
            assertEquals("{abc}", fg.windowId)
            assertEquals(4242, fg.pid)

            // 前面が無くなった(デスクトップを押した)
            fake.callBridge { it.activated("null") }
            waitFor { if (ForegroundWatcher.current() == null) Unit else null }

            // 使い捨て: 一覧
            val windows = KWin.windows()
            assertEquals(listOf("org.kde.kate"), windows.map { AppIds.of(it) })
            assertTrue(fake.loaded.keys.none { it.startsWith("dopachiru-once-") }, "使い捨てが下ろされていない")
        } finally {
            KWin.shutdown()
            fake.close()
        }
    }

    private fun <T> waitFor(read: () -> T?): T? {
        repeat(40) {
            read()?.let { return it }
            Thread.sleep(50)
        }
        return null
    }

    /** org.kde.KWin の代役。積まれたスクリプトを走らせたふりをする。 */
    private class FakeKWin(private val conn: DBusConnection) : KWin.KWinScripting {
        val loaded = ConcurrentHashMap<String, Int>()
        private val ids = AtomicInteger(0)

        override fun loadScript(filePath: String, pluginName: String): Int {
            val id = ids.incrementAndGet()
            loaded[pluginName] = id
            val text = File(filePath).readText()
            dumpForSyntaxCheck(pluginName, text)
            conn.exportObject("/Scripting/Script$id", Script(text))
            return id
        }

        override fun isScriptLoaded(pluginName: String): Boolean = loaded.containsKey(pluginName)
        override fun unloadScript(pluginName: String): Boolean = loaded.remove(pluginName) != null
        override fun start() = Unit
        override fun getObjectPath(): String = "/Scripting"

        inner class Script(private val text: String) : KWin.KWinScript {
            override fun run() {
                // 使い捨てなら、本文に埋め込まれた番号で一覧を返す
                val req = Regex("\"Result\", \"(once-\\d+)\"").find(text)?.groupValues?.get(1) ?: return
                callBridge {
                    it.result(
                        req,
                        """[{"id":"{k}","pid":7,"caption":"memo.txt — Kate","cls":"kate",""" +
                            """"name":"kate","app":"org.kde.kate","minimized":false}]""",
                    )
                }
            }

            override fun stop() = Unit
            override fun getObjectPath(): String = "/"
        }

        /** 呼ばれている最中に呼び返すと詰まるので、別の糸から叩く。 */
        fun callBridge(call: (KWin.BridgeInterface) -> Unit) {
            Thread {
                val bridge = conn.getRemoteObject(KWin.BUS_NAME, KWin.OBJECT_PATH, KWin.BridgeInterface::class.java)
                call(bridge)
            }.apply { start(); join(5_000) }
        }

        fun close() {
            runCatching { conn.releaseBusName("org.kde.KWin") }
            conn.close()
        }

        private fun dumpForSyntaxCheck(name: String, text: String) {
            val dir = System.getenv("KWIN_DUMP") ?: return
            File(dir).mkdirs()
            File(dir, "$name.js").writeText(text)
        }

        companion object {
            fun start(): FakeKWin {
                val conn = DBusConnectionBuilder.forSessionBus().build()
                conn.requestBusName("org.kde.KWin")
                val fake = FakeKWin(conn)
                conn.exportObject("/Scripting", fake)
                return fake
            }
        }
    }
}
