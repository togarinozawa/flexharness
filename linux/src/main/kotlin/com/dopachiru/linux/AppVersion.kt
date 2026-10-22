package com.dopachiru.linux

/**
 * この版の番号。
 *
 * 名簿に載せて、端末どうしで食い違いが出たときの切り分けに使います
 * (「片方が古くて、その欄をまだ知らない」がいちばん多い)。
 *
 * **`linux/build.gradle.kts` の `linuxVersion` と揃えてください。**
 * jpackage の版はビルドの都合で生成物にしか入らず、実行時には読めません。
 */
object AppVersion {
    const val CURRENT = "1.3.0"
}
