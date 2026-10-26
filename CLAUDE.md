# FlexHarness

個人用のアプリ使用制限アプリ。Kotlin + Compose Multiplatform。

0.44.0 で「ドパチル」から名前を変えた。**変えたのは人に見える名前だけ。**
`com.dopachiru`(applicationId・パッケージ名)、データの置き場所(`~/.local/share/dopachiru` など)、
サーバー(`dopachiru-sync`・`dopa.togar.dev`)は前のまま ── applicationId を変えると
上書き更新できなくなり、置き場所を変えるとルールも記録も消えて見える。

## 構成

| モジュール | 中身 |
|---|---|
| `:core` | ルールエンジン。条件・措置・判定・同期の型。**UI も端末 API も持たない** |
| `:app` | Android。AccessibilityService + オーバーレイ + Room |
| `:desktop` | Windows。**凍結**(ビルドもリリースもしない。コードは残す) |
| `:linux` | Debian + KDE Plasma 6(Wayland)。`:desktop` から写したもの。前面アプリは KWin スクリプト → D-Bus |
| `extension/` | Chrome 拡張。URL とページ内要素を見る |
| `server/worker/` | Cloudflare Workers + D1。同期と `/version` |

## 機能の足しかた

**条件と措置はレジストリに登録する。** `DopaCore.registerAll()` に1行足せば、
両OSの画面に自動で出る。分岐を書き足す場所は無い。

- 条件 = `ConditionType`(`core/condition/types/`)
- 措置 = `ActionType`(`core/action/types/`)
- パラメータは `Params`(JSON)。欄の定義は `ParamSpec`。
  `visibleWhen` で他の欄の値に応じて畳める
- `available = false` にすると**凍結**(既存ルールは読めるが、新規に選べない)

**雛形(`RulePresets`)は数字を穴(`inputs` = `ParamSpec`)にする。** 対話モード
(`core/wizard/`、Android の「質問に答えて作る」)が穴をそのまま聞く。新しい雛形は
`Problem`(困りごと)のどれかに載せる ── 載せ忘れと、聞いたのに効いていない穴は
`RuleWizardTest` が見つける。

判定の要は `RuleEngine.decide` ── 成立した組のうち `severity` がいちばん高い
措置を1つだけ採る。重ねられるのは `stackable` な覚え書きだけ。

## 守ること

- **制限の実行をネットワークに依存させない。** 圏外・機内モードで外れる制限は、
  機内モードにするだけで抜けられる制限になる。同期は上乗せであって土台ではない
- **このリポジトリは公開。** 鍵・トークン・個人的な記録を置かない
  (`dopa.md` `dopa_design_doc.md` `dist/` `local.properties` は gitignore 済み)
- **ASCII パスで作業する。** 非 ASCII のパスに置くと Gradle のテストが全滅する
- コメントは**なぜそう書いたか**を残す。何をしているかはコードが言う

## 確かめかた

```bash
./gradlew :core:test :app:testDebugUnitTest
dbus-run-session -- ./gradlew :linux:test   # KWin の代役を立てる試験がセッションバスを要る
```

KWin そのものは試験で動かせない。Plasma の上で `./gradlew :linux:kwinSmoke` を流して目で見る。

Room のマイグレーションは、手書きの SQL と生成されたスキーマ JSON を
突き合わせる `MigrationSqlTest` が見張っている。列を足したら必ず通すこと。

## ビルドとリリース

**ビルド・コミット・GitHub リリース・サーバーのデプロイで1セット。**
バラバラにやらない。

```bash
./gradlew :app:dist :linux:distDeb :linux:packagePortable
```

3つとも版つきの名前で `dist/` に出る。**リリースにはこの3つを上げる。**

**`dist/` の名前のまま上げること。** `/version`(自動更新)が名前から版を読むので、
名前が合わないと更新が届かない。`build/` の中の jpackage 生の
`flexharness_x.y.z_amd64.deb` を上げると**この事故が起きる**:

```
flexharness-<app版>.apk              例 flexharness-0.44.0.apk
flexharness-linux-<linux版>.deb      例 flexharness-linux-1.3.0.deb
flexharness-linux-<linux版>.tar.gz   持ち運び版
```

`/version` は前の名前(`dopachiru-…`)のファイルも読む。

**APK は FlexHarness 専用の鍵で署名する。** 在り処と合言葉は `keystore.properties`(gitignore 済み)、
鍵は `~/.dopachiru-signing/dopachiru-release.jks`(リポジトリの外)。**なくすと二度と上書き更新
できない**ので、控えを別の場所に置いておく。機械を替えるときは2つとも持っていく。
`:app:checkSigningKey` が指紋を見張っていて、違えば release を組む前に止まる
(0.41.0 は機械ごとの debug 鍵で署名してしまい、上書きできなかった)。

版は2本立て ── `app/build.gradle.kts` の `versionName`/`versionCode` と、
`linux/build.gradle.kts` の `linuxVersion`(と `linux/.../AppVersion.kt`)。両方上げる。

.deb には `dpkg-deb` と `fakeroot` が要る(Debian なら入っている)。

サーバーは `server/worker/` で `npm run deploy`(wrangler は Node 22 以上が要る)。差分が無ければ触らない。
