package com.dopachiru.linux.update

import com.dopachiru.core.sync.SyncApi
import com.dopachiru.core.update.Downloader
import com.dopachiru.core.update.ReleaseBuild
import com.dopachiru.core.update.UpdateEndpoint
import com.dopachiru.core.update.Versions
import com.dopachiru.linux.AppVersion
import com.dopachiru.linux.DesktopRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 新しい版を落として、インストーラに渡す。
 *
 * ## 押したときだけ通信します
 *
 * 裏で見に行くようにはしていません。**取り締まりがネットに依存していない**という
 * このアプリの前提を、更新のためだけに崩したくないからです。
 *
 * ## 入れ替えは apt に任せます
 *
 * 自分で自分のフォルダを差し替える道もありますが、途中で転ぶと
 * **起動しなくなる方向に転びます**。.deb を apt に渡すところまでで止めておけば、
 * 失敗しても前の版が残ります。
 */
object DesktopUpdater {

    sealed interface State {
        data object Idle : State

        data object Checking : State

        data class UpToDate(val current: String) : State

        data class Available(val build: ReleaseBuild, val notes: String) : State

        data class Downloading(val build: ReleaseBuild, val percent: Int) : State

        data class Ready(val build: ReleaseBuild, val file: File) : State

        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var cancelled = false

    /**
     * 動いている版。
     *
     * jpackage が渡してくる値を先に見ます。`gradlew :desktop:run` では渡って
     * こないので、そのときだけ [AppVersion] に落とします ── 落とさないと、
     * 開発中は版が読めず、更新の確認そのものが黙って何も言わなくなります。
     */
    fun currentVersion(): String =
        System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: AppVersion.CURRENT

    fun check() {
        _state.value = State.Checking
        scope.launch {
            val current = currentVersion()
            val baseUrl = UpdateEndpoint.resolve(DesktopRuntime.settings.value.sync.baseUrl)
            val latest = withContext(Dispatchers.IO) { SyncApi(baseUrl, "").latest() }
            _state.value = when (latest) {
                is SyncApi.Outcome.Ok -> {
                    val build = latest.value.linux
                    when {
                        build == null || !build.isUsable -> State.Failed("配っている版が見つかりません")
                        Versions.isNewer(current, build.version) ->
                            State.Available(build, latest.value.notes)
                        else -> State.UpToDate(current)
                    }
                }
                is SyncApi.Outcome.Unreachable -> State.Failed(latest.message)
                is SyncApi.Outcome.Rejected -> State.Failed(latest.message)
                is SyncApi.Outcome.Malformed -> State.Failed(latest.message)
            }
        }
    }

    fun download(build: ReleaseBuild) {
        cancelled = false
        _state.value = State.Downloading(build, 0)
        scope.launch {
            val dest = File(updatesDir(), build.fileName.ifBlank { "dopachiru-update.deb" })
            val result = withContext(Dispatchers.IO) {
                Downloader.download(
                    url = build.url,
                    dest = dest,
                    expectedBytes = build.sizeBytes,
                    onProgress = { percent ->
                        if (_state.value is State.Downloading) {
                            _state.value = State.Downloading(build, percent)
                        }
                    },
                    isCancelled = { cancelled },
                )
            }
            _state.value = when (result) {
                is Downloader.Result.Ok -> State.Ready(build, result.file)
                is Downloader.Result.Failed -> State.Failed(result.message)
            }
        }
    }

    fun cancel() {
        cancelled = true
        _state.value = State.Idle
    }

    fun reset() {
        _state.value = State.Idle
    }

    /**
     * .deb を入れる。**入れ終わったら、立ち上げ直すのは自分でやってもらいます。**
     *
     * pkexec で apt に渡す ── KDE のパスワードの窓が出て、断れば何も起きない。
     * dpkg -i ではなく apt にするのは、足りない依存があっても一緒に入れてくれるため。
     * 動いているあいだに入れ替えても、走っている JVM は古いファイルを掴んだまま動き続ける。
     *
     * pkexec が無ければ、ファイルを開くだけにする(Discover などが拾う)。
     */
    fun openInstaller(file: File): Boolean = runCatching {
        ProcessBuilder("pkexec", "apt-get", "install", "-y", "--reinstall", file.absolutePath)
            .inheritIO()
            .start()
        true
    }.getOrElse {
        runCatching { java.awt.Desktop.getDesktop().open(file); true }.getOrDefault(false)
    }

    /**
     * 落とし場所。使い終わったら消せるところに置きます。
     *
     * インストーラに渡す前にアプリが消えても困らないよう、
     * アプリ自身のフォルダの外に置いてあります。
     */
    private fun updatesDir(): File =
        File(System.getProperty("java.io.tmpdir"), "dopachiru-updates").apply { mkdirs() }

    fun cleanUp() {
        runCatching { updatesDir().listFiles()?.forEach { it.delete() } }
    }
}
