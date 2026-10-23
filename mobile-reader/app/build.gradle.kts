import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigning = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

android {
    testBuildType = providers.gradleProperty("onlineTestBuildType").getOrElse("debug")
    namespace = "com.shengzimao.reader.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.shengzimao.reader.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.1"
        testInstrumentationRunner = "com.shengzimao.reader.mobile.OnlineSmokeRunner"

        // SoNovel 引擎以「预编译 dex + 规则资源」的形式随 APK 分发，不进 R8/AGP 的源码集合。
        ndk {
            // 引擎是纯 Java 实现，不含原生库；显式限定 ABI 以免空 native 目录。
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            assets.directories.add("src/main/assets")
        }
    }

    // 引擎 dex 必须原样打包：既不压缩（ART 可 mmap 更快），也不能被主 dex 合并。
    androidResources {
        noCompress += listOf("dex", "jar")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // 引擎 dex 通过 assets 提供，避免与 AGP 生成的 classes.dex 冲突。
        jniLibs.useLegacyPackaging = true
    }

    signingConfigs {
        create("release") {
            // 自签发布密钥保存于工程 keystore/ 目录（见 README 的签名说明）。
              if (releaseSigning.isNotEmpty()) storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".verification"
        }
        release {
            if (releaseSigning.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("verification") {
            initWith(getByName("release"))
            applicationIdSuffix = ".verification.release"
            signingConfig = signingConfigs.getByName("debug")
            proguardFile("verification-rules.pro")
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation(platform("com.squareup.okio:okio-bom:3.15.0"))
    implementation("com.squareup.okio:okio-jvm:3.15.0")

    // 本机可复用缓存；干净源码检出时从 Maven 下载同一版本，不依赖私有 .tools 目录。
    listOf(
        "okhttp-jvm-5.1.0.jar" to "com.squareup.okhttp3:okhttp-jvm:5.1.0",
        "json-20250517.jar" to "org.json:json:20250517",
    ).forEach { (jar, coordinate) ->
        val cached = rootProject.file(".tools/libs/$jar")
        if (cached.isFile) implementation(files(cached)) else implementation(coordinate)
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
