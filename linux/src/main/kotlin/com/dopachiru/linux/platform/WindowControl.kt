package com.dopachiru.linux.platform

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** ブロックのやり方。強いほど逃げにくいが、荒い。 */
@Serializable
enum class BlockStrength(val displayName: String, val description: String) {
    OVERLAY(
        "オーバーレイのみ",
        "最前面に全画面の窓を出す。Alt+Tab や仮想デスクトップで裏に回れる余地が残る。",
    ),
    MINIMIZE(
        "最小化し続ける",
        "オーバーレイに加えて、対象のウィンドウを最小化し続ける。戻してもまた最小化される。",
    ),
    SUSPEND(
        "プロセスを一時停止",
        "対象のプロセスを止める(SIGSTOP)。最も強いが、保存していない編集内容や通信中の状態を壊すことがある。",
    ),
}

/**
 * ウィンドウとプロセスの操作。
 *
 * ## 一時停止の後始末について
 * 止めたまま終了すると、そのプロセスは誰にも再開されず固まったままになる。
 * 止めた pid は必ず控えて、[resumeAll] を終了フックから呼ぶ。
 * ドパチルが落ちてもプロセスが巻き添えにならないための最低限の約束。
 *
 * それでも SIGKILL で落とされたら控えは消える。そのときは `kill -CONT <pid>` で戻せる。
 */
object WindowControl {

    private val suspendedPids = ConcurrentHashMap.newKeySet<Int>()

    /**
     * 最後に最小化を頼んだ時刻。
     *
     * Windows では毎回 ShowWindow を呼べば済んだが、こちらは1回ごとに KWin へ
     * スクリプトを積んで下ろす。毎秒やると KWin が忙しくなるので間を空ける。
     */
    private val lastMinimizeMs = ConcurrentHashMap<String, Long>()
    private const val MINIMIZE_EVERY_MS = 1_500L

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread({ resumeAll() }, "dopachiru-resume-on-exit")
        )
    }

    fun minimize(windowId: String) {
        if (windowId.isBlank()) return
        val now = System.currentTimeMillis()
        val last = lastMinimizeMs[windowId] ?: 0L
        if (now - last < MINIMIZE_EVERY_MS) return
        lastMinimizeMs[windowId] = now
        KWin.minimize(windowId)
    }

    /**
     * デスクトップを前面に出す。
     *
     * Windows 版では、閉じたアプリが前面に残って次の判定でまた塞がれるのを防ぐために
     * 要った。KWin では最小化した時点で次のウィンドウに前面が移るので、やることが無い。
     * 「デスクトップを表示」を使うと**関係ないウィンドウまで全部しまう**ので、使わない。
     */
    fun focusDesktop() = Unit

    fun suspend(pid: Int): Boolean {
        if (pid in suspendedPids) return true
        if (pid <= 1 || pid.toLong() == ProcessHandle.current().pid()) return false
        val ok = signal("STOP", pid)
        if (ok) suspendedPids.add(pid)
        return ok
    }

    fun resume(pid: Int): Boolean {
        val ok = signal("CONT", pid)
        // 戻せなかった = もう居ない。控えからは外す
        suspendedPids.remove(pid)
        return ok
    }

    /** 止めているプロセスを全部戻す。終了時と、ブロックを解くときに呼ぶ。 */
    fun resumeAll() {
        suspendedPids.toList().forEach { resume(it) }
    }

    fun isSuspended(pid: Int): Boolean = pid in suspendedPids

    val suspendedCount: Int get() = suspendedPids.size

    /** プロセスの状態が `T`(止まっている)か。/proc から読むだけなので権限は要らない。 */
    fun isStopped(pid: Int): Boolean = runCatching {
        val stat = java.io.File("/proc/$pid/stat").readText()
        // 「pid (名前) 状態 …」。名前に空白や括弧が入りうるので、最後の ')' の後ろを見る
        stat.substring(stat.lastIndexOf(')') + 2).first() == 'T'
    }.getOrDefault(false)

    /**
     * シグナルを送る。JVM には送る手段が無いので kill を借りる。
     * 自分の利用者のプロセスにしか届かない ── それで足りる。
     */
    private fun signal(name: String, pid: Int): Boolean = runCatching {
        val p = ProcessBuilder("kill", "-$name", pid.toString())
            .redirectErrorStream(true)
            .start()
        p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)
}
