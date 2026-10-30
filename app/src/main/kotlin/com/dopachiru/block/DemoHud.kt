package com.dopachiru.block

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import com.dopachiru.core.time.TimeScale
import com.dopachiru.demo.DemoMode
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.ui.theme.DopaBlockTheme

/**
 * デモ版だけが出す、右上の札。いまのアプリに何が掛かっていて、あと何秒で何が起きるか。
 *
 * 見に来た人は、止まった瞬間より**止まるまでの数え下ろし**を見て仕組みを分かる。
 * 本番の「経過時間を出す」([com.dopachiru.core.action.types.TimerAction])は
 * ルールの措置なので、ほかの措置が勝つと消える。札はルールの外に置いて、
 * ブロック画面の上にも出し続ける。
 *
 * [OverlayHost] とは別のウィンドウにしてある。あちらは一度に1枚しか出せないので、
 * 札を相乗りさせるとブロック画面と取り合いになる。触れない窓なので、下の操作は妨げない。
 */
class DemoHud(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var container: FrameLayout? = null
    private var owner: OverlayLifecycleOwner? = null

    private val state = mutableStateOf<DemoHudView?>(null)

    val isShowing: Boolean get() = container != null

    fun update(appLabel: String, status: DopaRuntime.DemoStatus) {
        state.value = DemoHudView(appLabel, status)
        if (container == null) attach()
    }

    /** ほかのウィンドウが上に載ったあと、もう一度いちばん上に戻る。 */
    fun bringToFront() {
        if (container == null) return
        detach()
        attach()
    }

    fun hide() {
        detach()
        state.value = null
    }

    private fun attach() {
        val lifecycle = OverlayLifecycleOwner().apply { create() }
        val view = FrameLayout(service)
        view.addView(ComposeView(service).apply { setContent { state.value?.let { Hud(it) } } })
        view.setViewTreeLifecycleOwner(lifecycle)
        view.setViewTreeViewModelStoreOwner(lifecycle)
        view.setViewTreeSavedStateRegistryOwner(lifecycle)
        runCatching { windowManager.addView(view, layoutParams()) }.onFailure {
            lifecycle.destroy()
            return
        }
        lifecycle.resume()
        container = view
        owner = lifecycle
    }

    private fun detach() {
        val view = container ?: return
        container = null
        runCatching { windowManager.removeViewImmediate(view) }
        owner?.destroy()
        owner = null
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        // 状態バー(時計・電池)に重ねない
        y = statusBarHeight() + (8 * service.resources.displayMetrics.density).toInt()
        x = (8 * service.resources.displayMetrics.density).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun statusBarHeight(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else 0
    }
}

private val Mono = FontFamily.Monospace
private val Firing = Color(0xFFFF8A80)
private val Waiting = Color(0xFFFFE082)

@androidx.compose.runtime.Composable
private fun Hud(v: DemoHudView) = DopaBlockTheme {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xE6141420)),
        modifier = Modifier.widthIn(max = 240.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    v.appLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (TimeScale.isScaled) "DEMO 1分=${TimeScale.secondsPerMinute}秒" else "DEMO",
                    fontSize = 9.sp,
                    color = Color(0xFF9FA8DA),
                )
            }
            Spacer(Modifier.height(4.dp))
            Stat("いま", DemoMode.clock(v.status.sessionSeconds))
            Stat("今日", DemoMode.clock(v.status.todaySeconds))
            Stat("起動", "今日 ${v.status.opensToday}回目")
            v.status.declaredSecondsLeft?.let { Stat("宣言", "のこり " + DemoMode.clock(it), Waiting) }

            if (v.status.lines.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = Color(0x33FFFFFF))
                v.status.lines.forEach { line ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        line.ruleName,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val left = line.secondsLeft
                    Text(
                        when {
                            line.firing && left != null -> "● ${line.actionName}中 / あと ${DemoMode.clock(left)} で解ける"
                            line.firing -> "● ${line.actionName}中"
                            left != null -> "あと ${DemoMode.clock(left)} で${line.actionName}"
                            else -> "待機中(${line.actionName})"
                        },
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        color = if (line.firing) Firing else Waiting,
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Stat(label: String, value: String, color: Color = Color.White) {
    Row {
        Text(label, fontSize = 11.sp, color = Color(0xFFB0B0C0), modifier = Modifier.width(36.dp))
        Text(value, fontFamily = Mono, fontSize = 13.sp, color = color)
    }
}

/** 札1枚ぶんの中身。 */
internal data class DemoHudView(val appLabel: String, val status: DopaRuntime.DemoStatus)
