import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 版本号：CI 从 tag 注入（-PvvVersionName=1.0.0 -PvvVersionCode=10000），本地不传就是 1.0/1
val vvVersionName: String = providers.gradleProperty("vvVersionName").orNull ?: "1.0"
val vvVersionCode: Int = providers.gradleProperty("vvVersionCode").orNull?.toIntOrNull() ?: 1

// 发布签名：优先读本地 keystore.properties（已 gitignore），其次读 CI 注入的环境变量。
// 两处都没有时退回 debug 签名，保证 clone 下来直接 assembleRelease 能出包。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun secret(prop: String, env: String): String? =
    keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val releaseStorePath = secret("storeFile", "VVFILE_KEYSTORE_FILE")
val releaseStorePassword = secret("storePassword", "VVFILE_KEYSTORE_PASSWORD")
val releaseKeyAlias = secret("keyAlias", "VVFILE_KEY_ALIAS")
val releaseKeyPassword = secret("keyPassword", "VVFILE_KEY_PASSWORD")
val hasReleaseSigning =
    releaseStorePath != null && releaseStorePassword != null && releaseKeyAlias != null

android {
    namespace = "com.vvfile.app"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.vvfile.app"
        minSdk = 26
        targetSdk = 36
        versionCode = vvVersionCode
        versionName = vvVersionName

        vectorDrawables { useSupportLibrary = true }

        // 终端 PTY：只有这一个 .so，来自 Termux terminal-emulator 的 jni/termux.c（Apache-2.0）。
        // 只保留 64 位 ABI，包体每个 ABI 约增加 12KB。
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        externalNativeBuild {
            ndkBuild {
                cFlags += listOf("-std=c11", "-Os")
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(releaseStorePath!!)
                    .let { if (it.isAbsolute) it else rootProject.file(releaseStorePath!!) }
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword ?: releaseStorePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 有发布签名就用发布签名；没有就退回 debug 签名，本地也能直接 assembleRelease
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
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
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.commonmark)
    // 7z：容器解析 + LZMA/LZMA2/BCJ + AES-256 解码（xz 提供 LZMA 实现）
    implementation(libs.commons.compress) {
        // 只用 7z 通道，把用不到的可选编解码器摘掉：
        // zstd-jni 自带几十 MB 各平台原生库，塞进 APK 纯属浪费
        exclude(group = "com.github.luben", module = "zstd-jni")
        exclude(group = "org.brotli", module = "dec")
        exclude(group = "org.ow2.asm", module = "asm")
        exclude(group = "commons-codec", module = "commons-codec")
    }
    implementation(libs.commons.io)
    implementation(libs.xz)
    // rar / rar5 / rar 自解压：junrar 是纯 Java 实现（无 native 依赖）
    implementation(libs.junrar)
    // junrar 只用 slf4j 打日志；Android 上没有 provider 会一直往 stderr 刷警告
    runtimeOnly(libs.slf4j.nop)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
