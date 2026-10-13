package com.dopachiru.linux.tools

import com.dopachiru.linux.platform.ForegroundWatcher
import com.dopachiru.linux.platform.KWin
import com.dopachiru.linux.platform.ProtectedProcesses
import com.dopachiru.linux.platform.RunningApps
import com.dopachiru.linux.platform.WindowControl

/**
 * KWin まわりが実機で動くかを確かめる。`gradlew :linux:kwinSmoke`
 *
 * KWin スクリプトは KDE の版で細かく振る舞いが変わり、テストでは確かめようがない。
 * Plasma を入れ直したり版が上がったりしたら、まずこれを流す。
 *
 * 引数に pid を渡すと、そのプロセスを2秒止めて戻す(SIGSTOP / SIGCONT)。
 * 引数に `minimize` を渡すと、5秒後に前面のウィンドウを最小化する。
 */
fun main(args: Array<String>) {
    println("== KWin につなぐ")
    if (!KWin.ensureStarted()) {
        println("  NG: ${KWin.problem}")
        return
    }
    println("  OK")

    println()
    println("== 前面のウィンドウ(5秒のあいだ、ほかのウィンドウを触ってみてください)")
    var last: String? = null
    repeat(10) {
        val fg = ForegroundWatcher.current()
        val line = fg?.let {
            "${it.processName}  pid=${it.pid}  「${it.windowTitle.take(40)}」  " +
                "保護=${it.processName in ProtectedProcesses}  exe=${it.exePath}"
        } ?: "(まだ報せが来ていない)"
        if (line != last) println("  $line")
        last = line
        Thread.sleep(500)
    }

    println()
    println("== 見えているウィンドウ")
    val running = RunningApps.visible()
    running.forEach { println("  ${it.processName.padEnd(32)} ${it.label}  「${it.windowTitle.take(30)}」") }
    println(if (running.isNotEmpty()) "  OK: ${running.size} 件" else "  NG: 一覧が空。使い捨てスクリプトの答えが返っていない")

    if ("minimize" in args) {
        println()
        println("== 5秒後に前面のウィンドウを最小化します")
        Thread.sleep(5_000)
        val fg = ForegroundWatcher.current()
        if (fg == null) {
            println("  NG: 前面が分からない")
        } else {
            WindowControl.minimize(fg.windowId)
            Thread.sleep(1_000)
            println("  ${fg.processName} を最小化しました(目で確かめてください)")
        }
    }

    args.firstNotNullOfOrNull { it.toIntOrNull() }?.let { pid ->
        println()
        println("== pid=$pid を2秒止める")
        val stopped = WindowControl.suspend(pid)
        println("  止めた: $stopped  状態=T: ${WindowControl.isStopped(pid)}")
        Thread.sleep(2_000)
        val resumed = WindowControl.resume(pid)
        println("  戻した: $resumed  状態=T: ${WindowControl.isStopped(pid)}")
    }

    KWin.shutdown()
    // dbus-java の糸が残って JVM が終わらないことがあるので、明示的に抜ける
    kotlin.system.exitProcess(0)
}
