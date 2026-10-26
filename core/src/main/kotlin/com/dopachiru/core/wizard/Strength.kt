package com.dopachiru.core.wizard

import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DelayAction
import com.dopachiru.core.action.types.LockoutAction
import com.dopachiru.core.action.types.TimerAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.condition.types.UsageBudgetCondition
import com.dopachiru.core.engine.BudgetReset
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.ConditionTree
import com.dopachiru.core.model.Rule
import com.dopachiru.core.param.Params

/**
 * 措置の強さ。対話モードでは措置を選ばせず、この5段から1つ選ばせる。
 *
 * 措置は8種類あり、さらに押し切れるか・何秒待たせるかの欄が付く。
 * 作る人が決めたいのは「どのくらい強く止めるか」だけで、どの措置の
 * どの欄で実現するかではない。段に並べると、弱い順に読める。
 *
 * 音だけ・申告させる・目的を書かせる、は強さではなく**形**が違うので段に入れない。
 * それらは雛形の側で選ぶ([of] が null を返す)。
 */
enum class Strength(val label: String, val help: String) {
    NOTICE("気づかせるだけ", "止めない。使った時間や知らせを画面に出す"),
    PAUSE("一拍置く", "数秒待たせてから、必ず通す"),
    BLOCK("止める(押し切れる)", "止めるが、長押しすれば使える"),
    HARD_BLOCK("止める(押し切れない)", "押し切る口が無い。条件が外れるまで使えない"),
    LOCKOUT("しばらく閉め出す", "決めた時間、対象が開かなくなる"),
    ;

    companion object {
        /** 閉め出す長さの既定(分)。措置そのものの既定と揃える。 */
        const val DEFAULT_LOCKOUT_MINUTES = 10

        /** ルールの1組目がどの段か。段に載らない措置(音だけなど)は null。 */
        fun of(rule: Rule): Strength? = of(rule.actionId, rule.actionParams)

        fun of(actionId: String, params: Params): Strength? = when (actionId) {
            TimerAction.id, WarnAction.id -> NOTICE
            DelayAction.id -> PAUSE
            BlockAction.id ->
                if (params.bool(BlockAction.KEY_ALLOW_OVERRIDE, true)) BLOCK else HARD_BLOCK
            LockoutAction.id -> LOCKOUT
            else -> null
        }

        /**
         * 1組目の措置を [to] の段に差し替える。対象と条件には触らない。
         *
         * 同じ段なら何もしない ── 雛形が段の中で細かく調整した欄(待たせる秒数、
         * 押し切りの手間)を、段を選び直しただけで既定に戻さないため。
         * 段を変えるときは、雛形が書いた言葉(反省文・知らせ)を持ち越す。
         *
         * @param lockoutMinutes [LOCKOUT] のときの長さ。null なら今の長さか既定。
         */
        fun apply(rule: Rule, to: Strength, lockoutMinutes: Int? = null): Rule {
            val current = of(rule)
            if (current == to && to != LOCKOUT) return rule
            if (current == LOCKOUT && to == LOCKOUT) {
                if (lockoutMinutes == null) return rule
                return rule.copy(actionParams = rule.actionParams.with(LockoutAction.KEY_MINUTES to clampLockout(lockoutMinutes)))
            }

            val message = messageOf(rule)
            val (actionId, params) = when (to) {
                NOTICE -> WarnAction.id to Params.defaultsOf(WarnAction.params).with(
                    WarnAction.KEY_MESSAGE to (message ?: "決めた線を越えた。"),
                    WarnAction.KEY_SECONDS to 6,
                    WarnAction.KEY_REPEAT_MINUTES to 5,
                )

                PAUSE -> DelayAction.id to Params.defaultsOf(DelayAction.params).with(
                    DelayAction.KEY_SECONDS to 8,
                    DelayAction.KEY_MESSAGE to (message ?: "何をしに開いた?"),
                )

                BLOCK, HARD_BLOCK -> {
                    // 止める同士なら、押し切れるかどうかだけ変える。待たせる秒数や
                    // 押し切りの手間は雛形が選んだものを残す
                    val base = if (rule.actionId == BlockAction.id) {
                        rule.actionParams
                    } else {
                        Params.defaultsOf(BlockAction.params).let { defaults ->
                            if (message != null) defaults.with(BlockAction.KEY_REFLECTION to message) else defaults
                        }
                    }
                    BlockAction.id to base.with(BlockAction.KEY_ALLOW_OVERRIDE to (to == BLOCK))
                }

                LOCKOUT -> LockoutAction.id to Params.defaultsOf(LockoutAction.params).with(
                    LockoutAction.KEY_MINUTES to clampLockout(lockoutMinutes ?: suggestedLockoutMinutes(rule)),
                    LockoutAction.KEY_SCOPE to LockoutAction.Scope.TARGET,
                    LockoutAction.KEY_NOTICE to (message ?: "決めた線を越えた。少し休んでから。"),
                )
            }
            return rule.copy(actionId = actionId, actionParams = params)
        }

        /**
         * 閉め出す長さの勧め。「離れたら数え直す」型の持ち時間があるなら、その長さ以上。
         *
         * 数え直すより短く閉めると、明けた直後の1回目でまた閉まる(閉め出しの欄の注意書きと同じ)。
         */
        fun suggestedLockoutMinutes(rule: Rule): Int {
            val away = awayMinutesIn(rule.condition)
            return maxOf(DEFAULT_LOCKOUT_MINUTES, away ?: 0)
        }

        /** 段の名前で措置を説明する。段に載らない措置は措置の名前。 */
        fun describe(rule: Rule): String =
            of(rule)?.label ?: ActionRegistry[rule.actionId]?.displayName ?: rule.actionId

        private fun clampLockout(minutes: Int): Int = minutes.coerceIn(1, LockoutAction.MAX_MINUTES)

        private fun messageOf(rule: Rule): String? {
            val p = rule.actionParams
            val text = when (rule.actionId) {
                BlockAction.id -> p.string(BlockAction.KEY_REFLECTION)
                DelayAction.id -> p.string(DelayAction.KEY_MESSAGE)
                WarnAction.id -> p.string(WarnAction.KEY_MESSAGE)
                LockoutAction.id -> p.string(LockoutAction.KEY_NOTICE)
                else -> ""
            }
            return text.ifBlank { null }
        }

        private fun awayMinutesIn(node: ConditionNode): Int? {
            val (inner, _) = ConditionTree.stripNot(node)
            return when (inner) {
                is ConditionNode.Leaf ->
                    if (inner.typeId == UsageBudgetCondition.id &&
                        inner.params.string(UsageBudgetCondition.KEY_RESET) == BudgetReset.AWAY.name
                    ) {
                        inner.params.int(UsageBudgetCondition.KEY_AWAY_MINUTES)
                    } else {
                        null
                    }

                is ConditionNode.AllOf -> inner.children.mapNotNull { awayMinutesIn(it) }.maxOrNull()
                is ConditionNode.AnyOf -> inner.children.mapNotNull { awayMinutesIn(it) }.maxOrNull()
                is ConditionNode.Not -> null
            }
        }
    }
}
