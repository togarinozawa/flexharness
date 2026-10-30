package com.dopachiru.core.demo

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DeclareAction
import com.dopachiru.core.action.types.LockoutAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.condition.types.CooldownCondition
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.core.preset.PresetKeys
import com.dopachiru.core.preset.RulePresets
import kotlinx.serialization.json.JsonObject

/**
 * 展示用のルール一式。アプリごとに**違う措置**を1つずつ掛けて、全種類を触ってもらう。
 *
 * 雛形([RulePresets])から作る。デモ専用の組み立てを書くと、雛形を直したときに
 * デモだけ古い形のまま残り、展示で本番と違う動きを見せることになる。
 *
 * 数字は「ルールの分」で持ち、[com.dopachiru.core.time.TimeScale] が数秒に縮める。
 * 名前だけは**実際に待つ秒**で書く ── 見に来た人が読むのは名前で、
 * 「6分」と書いてあるのに30秒で止まると、壊れているように見える。
 *
 * 入っていないアプリを指していても害は無い(当たらないだけ)。初期化した端末に
 * 最初からある Google のアプリと、あとから入れそうな SNS を両方挙げてある。
 */
object DemoRules {

    /**
     * デモのルールの uid の頭。入れ直し・消すときに、自分で作ったルールと見分ける印。
     *
     * 名前で見分けないのは、係の人が名前を書き換えると消せなくなるため。
     */
    const val UID_PREFIX = "demo-"

    fun isDemo(rule: Rule): Boolean = rule.uid.startsWith(UID_PREFIX)

    /** 1組ぶん。[packages] のどれかを開くと [rule] が効く。 */
    data class Entry(val appLabel: String, val packages: Set<String>, val rule: Rule)

    fun build(secondsPerMinute: Long): List<Entry> {
        fun real(minutes: Int): String {
            val s = minutes * secondsPerMinute
            return when {
                s < 60 -> "${s}秒"
                s % 60 == 0L -> "${s / 60}分"
                else -> "${s / 60}分${s % 60}秒"
            }
        }

        fun preset(id: String, packages: Set<String>, vararg answers: Pair<String, Any?>): Rule =
            requireNotNull(RulePresets.byId(id)) { "雛形 $id が無い" }
                .build(packages, Params.of(*answers))

        fun Rule.demo(name: String, packages: Set<String>, params: Params = actionParams): Rule =
            copy(
                // 端末の中で一意なら足りる(同期はしない)。対象の先頭で振ると、何度入れ直しても同じ uid になる
                uid = UID_PREFIX + packages.first(),
                name = name,
                target = Target(packages = packages),
                actionParams = params,
            )

        fun Params.with(vararg pairs: Pair<String, Any?>): Params =
            Params(JsonObject(json + Params.of(*pairs).json))

        val youtube = setOf("com.google.android.youtube")
        val chrome = setOf("com.android.chrome")
        val delay = setOf("com.twitter.android", "com.instagram.barcelona")
        val intention = setOf("com.instagram.android", "com.facebook.katana")
        val declare = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.google.android.apps.photos")
        val warn = setOf("com.google.android.gm")
        val lockout = setOf("com.android.vending", "com.google.android.play.games")
        val cooldown = setOf("com.google.android.apps.youtube.music", "com.google.android.apps.maps")

        return listOf(
            Entry(
                "YouTube", youtube,
                // 離れたら数え直す財布にしてあるのは、次の人に渡すたびに
                // 「やり直す」を押さなくても、少し置けば元に戻るようにするため
                preset("forced_break", youtube, PresetKeys.MINUTES to 6, PresetKeys.BREAK_MINUTES to 2)
                    .let { base ->
                        base.copy(
                            actionId = BlockAction.id,
                            actionParams = Params.of(
                                BlockAction.KEY_REFLECTION to "続けて${real(6)}見た。${real(2)}離れたらまた見られる。",
                                BlockAction.KEY_MIN_SECONDS to 5,
                                BlockAction.KEY_RELEASE_EFFORT to BlockAction.Effort.HOLD,
                            ),
                        ).demo("YouTube: 続けて${real(6)}で止める", youtube)
                    },
            ),
            Entry(
                "Chrome", chrome,
                preset("reopen", chrome, PresetKeys.COUNT to 3)
                    .let { it.demo("Chrome: 1日3回まで", chrome, it.actionParams.with(BlockAction.KEY_MIN_SECONDS to 5)) },
            ),
            Entry(
                "X・Threads", delay,
                preset("open_delay", delay, PresetKeys.SECONDS to 5).let { it.demo("X: 開くたび5秒待たせる", delay) },
            ),
            Entry(
                "Instagram・Facebook", intention,
                preset("state_intention", intention).let { it.demo("Instagram: 目的を書かせる", intention) },
            ),
            Entry(
                "TikTok・フォト", declare,
                preset("declare_first", declare, PresetKeys.MINUTES to 6).let {
                    it.demo(
                        "TikTok: 開く前に何分使うか宣言(1分=${secondsPerMinute}秒)",
                        declare,
                        it.actionParams.with(DeclareAction.KEY_DEFAULT_MINUTES to 2),
                    )
                },
            ),
            Entry(
                "Gmail", warn,
                preset("long_session", warn, PresetKeys.MINUTES to 2, PresetKeys.AWAY_MINUTES to 2).let {
                    it.demo(
                        "Gmail: ${real(2)}ごとに声をかける",
                        warn,
                        it.actionParams.with(
                            WarnAction.KEY_MESSAGE to "${real(2)}経った。まだ続ける?",
                            WarnAction.KEY_SECONDS to 4,
                            WarnAction.KEY_REPEAT_MINUTES to 2,
                        ),
                    )
                },
            ),
            Entry(
                "Play ストア・ゲーム", lockout,
                preset("forced_break", lockout, PresetKeys.MINUTES to 4, PresetKeys.BREAK_MINUTES to 6).let {
                    it.demo(
                        "ゲーム: ${real(4)}遊んだら${real(6)}閉め出し",
                        lockout,
                        it.actionParams.with(LockoutAction.KEY_NOTICE to "${real(4)}遊んだ。${real(6)}は開かない。"),
                    )
                },
            ),
            Entry(
                "YouTube Music・マップ", cooldown,
                // 雛形は時間単位でしか聞かないので、分の端数の欄に直に入れる
                preset("cooldown_between", cooldown, PresetKeys.HOURS to 1).let { base ->
                    val leaf = ConditionNode.Leaf(
                        CooldownCondition.id,
                        Params.of(
                            CooldownCondition.KEY_HOURS to 0,
                            CooldownCondition.KEY_MINUTES to 4,
                        ),
                    )
                    base.copy(condition = ConditionNode.AllOf(listOf(leaf)))
                        .demo("Music: 閉じたら${real(4)}あけないと開けない", cooldown, base.actionParams.with(BlockAction.KEY_MIN_SECONDS to 5))
                },
            ),
        )
    }
}
