import java.security.MessageDigest
import java.util.Properties

plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    id("org.fcitx.fcitx5.android.fcitx-component")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val personalSigningFile = rootProject.file(".private/signing.properties")
val personalSigning = Properties().apply {
    if (personalSigningFile.isFile) personalSigningFile.inputStream().use { load(it) }
}

android {
    namespace = "org.fcitx.fcitx5.android"

    defaultConfig {
        applicationId = "dev.feinianyu.ime"
        versionName = providers.gradleProperty("personalVersionName").orNull ?: "0.2.0"
        buildConfigField("String", "UPDATE_REPOSITORY", "\"zzzzzec/feinianyu-ime\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets(
                    // jni
                    "native-lib",
                    // copy fcitx5 built-in addon libraries
                    "copy-fcitx5-modules",
                    // android specific modules
                    "androidfrontend",
                    "androidkeyboard",
                    "androidnotification"
                )
            }
        }
    }

    buildFeatures {
        viewBinding = true
        resValues = true
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    buildTypes {
        release {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round")
            resValue("string", "app_name", "@string/personal_app_name")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher_debug")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round_debug")
            resValue("string", "app_name", "@string/personal_app_name")
            if (personalSigningFile.isFile) applicationIdSuffix = ""
        }
    }

    if (personalSigningFile.isFile) {
        val personal = signingConfigs.create("personal") {
            storeFile = rootProject.file(personalSigning.getProperty("storeFile"))
            storePassword = personalSigning.getProperty("storePassword")
            keyAlias = personalSigning.getProperty("keyAlias")
            keyPassword = personalSigning.getProperty("keyPassword")
        }
        buildTypes.getByName("debug").signingConfig = personal
        buildTypes.getByName("release").signingConfig = personal
    }

    androidResources {
        noCompress += "gguf"
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }
}

val verifyBundledSuzu = tasks.register("verifyBundledSuzu") {
    val model = layout.projectDirectory.file("src/main/assets/suzu/suzu-2.4.q8_0.gguf")
    val vocab = layout.projectDirectory.file("src/main/assets/suzu/suzu-2.4.q8_0.gguf.vocab.txt")
    inputs.files(model, vocab)
    doLast {
        check(model.asFile.isFile && vocab.asFile.isFile) { "Run scripts/fetch-suzu.ps1 before building." }
        val sha = MessageDigest.getInstance("SHA-256")
        model.asFile.inputStream().buffered().use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                val size = input.read(bytes)
                if (size < 0) break
                sha.update(bytes, 0, size)
            }
        }
        check(sha.digest().joinToString("") { "%02x".format(it) } == "a2ff2c46110c7ba47746871eaf8ff37cc4fab58105ddbe11e0a768e340cdb996") { "Suzu model checksum mismatch" }
    }
}
tasks.named("preBuild") { dependsOn(verifyBundledSuzu) }

generateDataDescriptor {
    // SuzuAssets extracts these once to noBackupFilesDir. They are not Fcitx data.
    excludes.addAll("suzu/suzu-2.4.q8_0.gguf", "suzu/suzu-2.4.q8_0.gguf.vocab.txt")
}

fcitxComponent {
    includeLibs = listOf(
        "fcitx5",
        "fcitx5-lua",
        "libime",
        "fcitx5-chinese-addons"
    )
    // exclude (delete immediately after install) tables that nobody would use
    excludeFiles = listOf("cangjie", "erbi", "qxm", "wanfeng").map {
        "usr/share/fcitx5/inputmethod/$it.conf"
    }
    installPrebuiltAssets = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    ksp(project(":codegen"))
    implementation(project(":lib:fcitx5"))
    implementation(project(":lib:fcitx5-lua"))
    implementation(project(":lib:libime"))
    implementation(project(":lib:fcitx5-chinese-addons"))
    implementation(project(":lib:common"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)
    implementation(libs.arrow.core)
    implementation(libs.arrow.functions)
    implementation(libs.imagecropper)
    implementation(libs.flexbox)
    implementation(libs.dependency)
    implementation(libs.timber)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.dimensions)
    implementation(libs.splitties.resources)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.appcompat)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.lifecycle.testing)
    androidTestImplementation(libs.junit)
}

configurations {
    all {
        // remove Baseline Profile Installer or whatever it is...
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
        // remove unwanted splitties libraries...
        exclude(group = "com.louiscad.splitties", module = "splitties-appctx")
        exclude(group = "com.louiscad.splitties", module = "splitties-systemservices")
    }
}
