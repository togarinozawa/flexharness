import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/** 署名の鍵の在り処と合言葉。無ければ空(release は [checkSigningKey] で止まる)。 */
val signingProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

android {
    namespace = "com.dopachiru"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dopachiru"
        /**
         * Android 10。手持ちのうち一番古い AQUOS R2 SH-03K が
         * 2020年3月の更新で Android 10 に上がったきり打ち止めなので、そこに合わせている。
         *
         * ここを下げるときに効いてくるもの:
         *  - 29 未満: `AppOpsManager.unsafeCheckOpNoThrow`(AppUsageRanking)が無い
         *  - 26 未満: core が使う java.time に脱糖(desugaring)が要る
         */
        minSdk = 29
        targetSdk = 36
        versionCode = 53
        versionName = "0.45.0"
    }

    signingConfigs {
        /**
         * 配る APK の署名。**FlexHarness専用の鍵**で、在り処は keystore.properties に書く
         * (鍵もこのファイルも gitignore 済み。リポジトリは公開なので、絶対に入れない)。
         *
         * 0.41.0 までは「ビルドした機械の debug 鍵」だった。機械を替えたら鍵が黙って
         * 作り直され、配った版に上書きできなくなった。専用の鍵は機械に縛られないので、
         * 持ち歩いて控えておけば同じことは起きない。
         *
         * 同じ鍵で署名し続けるかぎり、アンインストールせずに上書き更新できる。
         */
        create("sideload") {
            signingProps.getProperty("storeFile")?.let { storeFile = rootProject.file(it) }
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
        }
        /**
         * 実機で動かす用。
         *
         * 既定では R8 で圧縮する(63MB → 3MB)。圧縮しても条件・アクションの実装と
         * シリアライザが残ることは mapping.txt で確認済み。
         *
         * 圧縮が原因かどうかを切り分けたいときは `-PnoMinify=true` を付けてビルドする。
         */
        release {
            isMinifyEnabled = project.findProperty("noMinify") != "true"
            isShrinkResources = isMinifyEnabled
            signingConfig = signingConfigs.getByName("sideload")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        /**
         * 展示用。`gradlew :app:distDemo`
         *
         * 別のアプリ(`com.dopachiru.demo`)として入るので、本番と並べて置けて、
         * 本番の更新も同期も巻き込まない。中身は本番と同じで、違うのは
         * 「ルールの1分を数秒に縮める」([com.dopachiru.core.time.TimeScale])と
         * 右上の札・デモ用ルールの口が出ることだけ。
         *
         * flavor にしないのは、タスク名(testDebugUnitTest・assembleRelease)が
         * 全部変わって、手順書と dist が壊れるため。
         *
         * 署名は debug 鍵。展示用の端末に入れて終わりなので、上書き更新できなくても
         * 困らない ── 専用の鍵をここで使うと、鍵の無い機械でデモが組めなくなる。
         */
        create("demo") {
            initWith(getByName("debug"))
            isDebuggable = false
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("boolean", "DEMO", "true")
        }
        getByName("debug") { buildConfigField("boolean", "DEMO", "false") }
        getByName("release") { buildConfigField("boolean", "DEMO", "false") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        // 学習予定の時刻計算のように、Android を触らない部分だけを JVM で見る
        unitTests.isReturnDefaultValues = true
    }
}

/**
 * 配布用の APK を `dist/` に版つきで置く。
 *
 * `gradlew :app:dist`
 *
 * build/ の中の app-release.apk は版が分からず、次のビルドで黙って上書きされる。
 * どの版を渡したのかが後から辿れないと、「入れたはずのものが動かない」を
 * 切り分けられない。desktop 側の packagePortable と対にしてある。
 *
 * なお `dist/` は gitignore されている ── APK は GitHub には上がらない。
 */
tasks.register<Copy>("dist") {
    group = "distribution"
    description = "release APK を dist/ に版つきでコピーする"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    // 版は設定時に読んでおく。実行時に android {} を触ると
    // 構成キャッシュが Project を掴もうとして落ちる
    val name = "flexharness-" + android.defaultConfig.versionName + ".apk"
    rename { name }
}

/**
 * 配る APK の署名の指紋(SHA-256)。**これと違う鍵では配らない。**
 *
 * 鍵を取り違えて配ると、入っている版に上書きできず(「アプリがインストールされていません」)、
 * アンインストールしてルールも記録も失うしかなくなる。0.41.0 で一度それが起きた。
 *
 * 指紋は公開してよい値(証明書の要約で、鍵そのものではない)。
 * 0.41.0 までは debug 鍵の 827020ec…、0.42.0 からFlexHarness専用の鍵。
 */
val expectedSigningSha256 = "d4a015b4a3fb8d39541ad54efdd31ebb3b0376f511a44ccc42c7051859e9ea73"

/**
 * 展示用の APK を `dist/` に置く。`gradlew :app:distDemo`
 *
 * 名前を `flexharness-demo-…` にしてあるのは、リリースに紛れても
 * `/version` が本番の APK と取り違えないようにするため。
 */
tasks.register<Copy>("distDemo") {
    group = "distribution"
    description = "展示用の demo APK を dist/ に版つきでコピーする"
    dependsOn("assembleDemo")
    from(layout.buildDirectory.file("outputs/apk/demo/app-demo.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    val name = "flexharness-demo-" + android.defaultConfig.versionName + ".apk"
    rename { name }
}

tasks.register("checkSigningKey") {
    group = "verification"
    description = "署名の鍵が、これまで配ってきたものと同じかを確かめる"
    // 設定時に読むのは在り処だけ。合言葉は実行時に読む(構成キャッシュに残さない)
    val propsFile = rootProject.file("keystore.properties")
    val root = rootProject.projectDir
    val expected = expectedSigningSha256
    doLast {
        check(propsFile.isFile) {
            "署名の設定が見つかりません: $propsFile\n" +
                "控えてある keystore.properties と dopachiru-release.jks を戻してください。"
        }
        val props = Properties().apply { propsFile.inputStream().use { load(it) } }
        val keystore = File(props.getProperty("storeFile").orEmpty()).let { if (it.isAbsolute) it else File(root, it.path) }
        check(keystore.isFile) { "鍵が見つかりません: $keystore" }
        val store = KeyStore.getInstance(keystore, props.getProperty("storePassword").orEmpty().toCharArray())
        val alias = props.getProperty("keyAlias").orEmpty()
        val cert = store.getCertificate(alias) ?: error("$keystore に $alias がありません")
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
        check(actual == expected) {
            "署名の鍵が、これまで配ってきたものと違います。このまま配ると上書き更新できません。\n" +
                "  期待: $expected\n  実際: $actual ($keystore)"
        }
    }
}

// release を組む前に必ず確かめる。dist だけに付けると、assembleRelease を直に叩いたときに漏れる
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn("checkSigningKey") }

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
