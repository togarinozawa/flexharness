import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

/**
 * Linux 版の版番号。.deb と持ち運び版の名前の両方で使う。
 *
 * Windows 版(`:desktop`)とは別の数え方。Windows 版から写して始めたが、
 * 版まで引き継ぐと「1.29 から 1.30 で何が変わったか」が2つの OS で混ざる。
 */
val linuxVersion = "1.4.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(compose.desktop.currentOs)
    @Suppress("DEPRECATION")
    implementation(compose.material3)

    // KWin と話すため。KDE の Wayland では前面のウィンドウを外から聞く口が無く、
    // KWin スクリプトに D-Bus で報せてもらうしかない
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

/**
 * 配る .deb を `dist/` に版つきで置く。`gradlew :linux:distDeb`
 *
 * jpackage の生の名前(`flexharness_1.3.0_amd64.deb`)では `/version` が版を読めない。
 * :app:dist と同じく、配る名前はここで決める。
 */
tasks.register<Copy>("distDeb") {
    group = "distribution"
    description = ".deb を dist/ に版つきでコピーする"
    dependsOn("packageDeb")
    from(layout.buildDirectory.dir("compose/binaries/main/deb"))
    include("*.deb")
    into(rootProject.layout.projectDirectory.dir("dist"))
    // 版は設定時に読んでおく(構成キャッシュが script の参照を直列化できないため)
    val name = "flexharness-linux-$linuxVersion.deb"
    rename { name }
}

/**
 * インストール不要の持ち運び版。JRE ごと固めるので、展開して `flexharness/bin/flexharness` で動く。
 * `gradlew :linux:packagePortable`
 */
tasks.register<Tar>("packagePortable") {
    group = "compose desktop"
    description = "app イメージを tar.gz に固める(root 権限なしで動かせる)"
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    compression = Compression.GZIP
    archiveFileName.set("flexharness-linux-$linuxVersion.tar.gz")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("dist"))
}

/** 入っているアプリの一覧が取れるかを見る。`gradlew :linux:installedAppsSmoke` */
tasks.register<JavaExec>("installedAppsSmoke") {
    group = "verification"
    description = ".desktop ファイルから識別子と名前を拾えているか目で見る"
    mainClass.set("com.dopachiru.linux.tools.InstalledAppsSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
}

/** 使用状況の書き出しが1枚の紙として読めるかを見る。`gradlew :linux:usageReportSmoke` */
tasks.register<JavaExec>("usageReportSmoke") {
    group = "verification"
    description = "作り物の記録を流し込んで、書き出しの見た目を目で見る"
    mainClass.set("com.dopachiru.linux.tools.UsageReportSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
}

/** KWin まわりが実機で動くかを確かめる。`gradlew :linux:kwinSmoke` */
tasks.register<JavaExec>("kwinSmoke") {
    group = "verification"
    description = "前面ウィンドウの取得・一覧・最小化・プロセス一時停止を実際に叩く"
    mainClass.set("com.dopachiru.linux.tools.KWinSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
}

compose.desktop {
    application {
        mainClass = "com.dopachiru.linux.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            // ブラウザ拡張の受け口(com.sun.net.httpserver)と、dbus-java が要るもの
            // (UNIX ソケットの相手の確かめと、D-Bus の EXTERNAL 認証で使う uid)。
            // jlink で削られると、**配布版でだけ** 拡張や KWin が繋がらなくなる
            modules("jdk.httpserver", "jdk.net", "jdk.security.auth")
            // 名前を FlexHarness に変えたときに、パッケージの名前も変えた(1.3.0)。
            // 1.2.0 までの dopachiru は別のパッケージとして残るので、入れ替えるときに消してもらう。
            // データの置き場所(~/.local/share/dopachiru)は変えていない ── 変えるとルールも記録も消えて見える
            packageName = "flexharness"
            packageVersion = linuxVersion
            description = "FlexHarness - a flexible harness for yourself"
            vendor = "flexharness"

            linux {
                // 入れ先は /opt/flexharness。起動は bin/flexharness
                packageName = "flexharness"
                // 指定しないと Java の既定の絵がメニューに出る。絵は tools/icons/IconGen.java で描く
                iconFile.set(project.file("icon.png"))
                menuGroup = "Utility"
                appCategory = "utils"
                shortcut = true
            }
        }
    }
}
