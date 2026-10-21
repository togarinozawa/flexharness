package com.dopachiru.core.model

import com.dopachiru.core.param.Params
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ルールの対象アプリ。
 *
 * 二通りの指定ができる。
 *  - 正指定: パッケージ名とタグの和。「この5個を止める」
 *  - 全指定: [matchAll] を立てて、例外を [exceptPackages] / [exceptTags] に置く。
 *    「必要なもの以外ぜんぶ止める」= 許可リスト型。
 *
 * 除外は正指定より常に強い。全指定と併用したときだけ意味があるわけではなく、
 * 正指定に対しても効く(タグで広く取って数個だけ抜く、という書き方ができる)。
 *
 * 電話やランチャーのような「絶対に止めてはいけないアプリ」は、ここではなく
 * app 側の ProtectedApps で弾く。ユーザーが設定から外せてしまってはいけないので。
 */
@Serializable
data class Target(
    val packages: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    /** 全アプリを対象にする。例外は except* で指定する。 */
    val matchAll: Boolean = false,
    val exceptPackages: Set<String> = emptySet(),
    val exceptTags: Set<String> = emptySet(),

    /**
     * URL の指定。ブラウザ相手のときだけ効く。書き方は [SitePattern]。
     *
     * パッケージ名と**同じ列に並ぶ**(和で足される)。ブラウザを対象に足す必要は
     * ない ── 「youtube.com/shorts」とだけ書けば、そのページを開いたときに当たる。
     */
    val sites: Set<String> = emptySet(),
    val exceptSites: Set<String> = emptySet(),
) {
    /**
     * @param url いま見ているページ。ブラウザでなければ null。
     *   null のとき [sites] は**当たらない**。URL が取れないことを
     *   「当たらない」ではなく「当たる」に倒すと、ブラウザ以外のアプリまで
     *   サイト規則で巻き込む。
     */
    fun matches(packageName: String, tagsOfApp: Set<String>, url: String? = null): Boolean {
        if (packageName in exceptPackages) return false
        if (exceptTags.any { it in tagsOfApp }) return false
        if (SitePattern.matchesAny(exceptSites, url)) return false
        // 全指定は「本人が開いて使うアプリ」の全部であって、システムの裏方は含まない。
        // 名指しやタグで選んだものは別 ── 選んだのなら、裏方でも当てる
        if (matchAll && SYSTEM_TAG !in tagsOfApp) return true
        if (packageName in packages || tags.any { it in tagsOfApp }) return true
        return SitePattern.matchesAny(sites, url)
    }

    val isEmpty: Boolean
        get() = !matchAll && packages.isEmpty() && tags.isEmpty() && sites.isEmpty()

    companion object {
        /**
         * 端末が付ける印。**全指定([matchAll])はこの印の付いたアプリを含まない。**
         *
         * 付けるのは、消せず・ホーム画面にも出ないシステムのアプリ(Google Play 開発者
         * サービス、アンインストールの確認、権限の確認など)。本人が開いたのではなく、
         * 別のアプリの用事で勝手に前に出てくる。許可リスト型でこれを止めると、
         * ログインや権限の確認の途中でブロック画面が出て、許可したアプリまで使えなくなる。
         *
         * 印は判定のときに足すだけで、タグとして保存も同期もしない。`@` で始まる名前は
         * 人が付けるタグとかち合わない。
         */
        const val SYSTEM_TAG = "@system"
    }
}

/**
 * 条件の木。AND / OR / NOT で入れ子にできる。
 *
 * 木を組み替える道具は [ConditionTree] にある。設定画面はそちら越しに触るので、
 * この型そのものは不変のままでよい。
 */
@Serializable
sealed interface ConditionNode {

    @Serializable
    @SerialName("leaf")
    data class Leaf(val typeId: String, val params: Params) : ConditionNode

    /** 子がすべて成立したら成立。子が空なら常に成立(= 無条件 = 完全封印)。 */
    @Serializable
    @SerialName("allOf")
    data class AllOf(val children: List<ConditionNode> = emptyList()) : ConditionNode

    /** 子のどれかが成立したら成立。子が空なら成立しない。 */
    @Serializable
    @SerialName("anyOf")
    data class AnyOf(val children: List<ConditionNode> = emptyList()) : ConditionNode

    @Serializable
    @SerialName("not")
    data class Not(val child: ConditionNode) : ConditionNode
}

/**
 * ルール = 対象 × 条件 × アクション × 破ったときの報い。
 */
@Serializable
data class Rule(
    /** 端末内での ID。Android は Room の自動採番、Windows は自前の連番。 */
    val id: Long = 0L,

    /**
     * 端末をまたいで一意な ID。同期の鍵。
     *
     * [id] は端末ごとに独立して振られるので、別の端末で作ったルールが同じ番号になる。
     * 同期で突き合わせるときはこちらを使う。作成時に UUID を振り、
     * 空のまま保存されていた古いルールには読み込み時に振り直す。
     */
    val uid: String = "",
    val name: String,
    val enabled: Boolean = true,
    val target: Target,
    val condition: ConditionNode,
    val actionId: String,
    val actionParams: Params = Params.EMPTY,

    /**
     * このルールを破った / 守ったときに起きること。
     *
     * 既定は「封鎖はしない・ポイントは設定の既定値」。この機能より前に作った
     * ルールもここに落ちるので、黙って封鎖が始まることはない。
     */
    val consequence: Consequence = Consequence.NONE,

    /**
     * どの端末で効かせるか(deviceId の集合)。空ならどの端末でも。
     *
     * 既定が空なので、この欄より前に作ったルールは挙動が変わらない。[DeviceScope]
     */
    val devices: Set<String> = DeviceScope.EVERYWHERE,

    /**
     * この時刻(エポック秒)を過ぎたら消えるルール。0 なら消えない。
     *
     * 「今日だけ 2時間使ったら1時間休憩」のような**その場で決める枠**のためにある。
     * 消えると分かっているから気軽に作れる ── 残り続けるなら、作る前に
     * 「これを一生守れるか」を考えることになり、その場では作られない。
     *
     * 消すのは端末側([Rules.prune])。評価から外すだけでなく行ごと消すのは、
     * 一覧に死んだルールが溜まると、生きているものが見えなくなるため。
     */
    val expiresAtSec: Long = 0L,

    /**
     * 1組目に重ねる動作。先頭の [actionId] が主で、ここが上乗せ。
     *
     * **いまのエンジンは実行しません。** 器だけ先に用意してある([Clause])。
     */
    val extraActions: List<ActionSpec> = emptyList(),

    /**
     * 2組目以降の「条件 → こうする」。1組目は [condition] と [actionId]。
     *
     * 対称ではありませんが、そうしてあるのは**既存の保存をそのまま読む**ためと、
     * **既存のルールの数える鍵を動かさない**ため ── 振り直すと、更新した瞬間に
     * 持ち時間の窓がリセットされます。読むときは [clauses] を使うこと。
     */
    val extraClauses: List<Clause> = emptyList(),

    /**
     * この時刻(エポック秒)まで一時停止。過ぎれば**誰の手も借りずに**また効く。0 なら止めていない。
     *
     * 無効化([enabled] = false)と分けてあるのは、戻るかどうかが違うから。無効化は
     * 自分で戻すまで外れたまま ── 衝動で切ったものは、たいてい数日戻らない。
     * 押し切る口が「無効にする」しか無いと、押し切った1回が半日・数日の空白になる。
     * 時間で戻る止め方を用意して、押し切っても被害が区切られるようにする。
     */
    val pausedUntilSec: Long = 0L,
) {
    /**
     * 「条件 → こうする」の全部。1組目もここでは同じ形に揃う。
     *
     * 判定も画面もこちらを見ること。[condition] と [actionId] を直に読むのは、
     * 1組しか無いと分かっている場所だけにする。
     */
    val clauses: List<Clause>
        get() = buildList {
            add(
                Clause(
                    id = Clauses.FIRST_ID,
                    condition = condition,
                    actions = listOf(ActionSpec(actionId, actionParams)) + extraActions,
                ),
            )
            // 1組目と番号がかち合うものは捨てる。財布の鍵なので、重なると混ざる
            addAll(extraClauses.filter { it.id != Clauses.FIRST_ID })
        }

    /** 組が2つ以上あるか。一覧に印を付けるため。 */
    val hasManyClauses: Boolean get() = extraClauses.isNotEmpty()

    /** その端末で評価に載せるか。 */
    fun appliesToDevice(deviceId: String): Boolean = DeviceScope.appliesTo(devices, deviceId)

    /** 期限切れか。期限なし(0)は常に偽。 */
    fun isExpiredAt(nowSec: Long): Boolean = expiresAtSec > 0L && nowSec >= expiresAtSec

    /** 期限つきか。一覧で印を付けるため。 */
    val isTemporary: Boolean get() = expiresAtSec > 0L

    /** いま一時停止しているか。 */
    fun isPausedAt(nowSec: Long): Boolean = pausedUntilSec > nowSec

    /** いま効いているか(有効で、止めていない)。 */
    fun isActiveAt(nowSec: Long): Boolean = enabled && !isPausedAt(nowSec)
}

/** ルール一覧の手入れ。端末側の保存方法に依存しないようここに置く。 */
object Rules {
    /** 期限切れを落とす。 */
    fun prune(all: List<Rule>, nowSec: Long): List<Rule> = all.filterNot { it.isExpiredAt(nowSec) }

    /** 落ちるものがあるか。無ければ書き戻さない(無駄な同期を起こさないため)。 */
    fun hasExpired(all: List<Rule>, nowSec: Long): Boolean = all.any { it.isExpiredAt(nowSec) }

    /**
     * 判定に渡す形。一時停止中のものは無効として渡す。
     *
     * エンジンは [Rule.enabled] だけを見ているので、ここで畳んでから渡す ──
     * エンジンの側で時刻を見させると、判定のたびに時計を引く場所が増える。
     */
    fun effectiveAt(all: List<Rule>, nowSec: Long): List<Rule> =
        if (all.none { it.isPausedAt(nowSec) }) all
        else all.map { if (it.enabled && it.isPausedAt(nowSec)) it.copy(enabled = false) else it }

    /** いちばん早く明ける一時停止の時刻。無ければ null。明けた瞬間に判定し直すため。 */
    fun nextResumeAt(all: List<Rule>, nowSec: Long): Long? =
        all.filter { it.enabled && it.isPausedAt(nowSec) }.minOfOrNull { it.pausedUntilSec }
}
