import java.util.Properties

plugins {
    id("com.android.application")
}

val localKeystoreFile = rootProject.file("keystore.properties")
val localKeystore = Properties().apply {
    if (localKeystoreFile.isFile) {
        localKeystoreFile.inputStream().use { load(it) }
    }
}

fun signingValue(environment: String, property: String): String? =
    System.getenv(environment) ?: localKeystore.getProperty(property)

val releaseStoreFile = signingValue("ANDROID_KEYSTORE_PATH", "storeFile")
val releaseStorePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")

val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

val releaseRequested = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true)
}

if (releaseRequested && !hasReleaseSigning) {
    throw GradleException("Release signing configuration is missing.")
}

android {
    namespace = "io.github.thorvolume.control"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.thorvolume.control"
        minSdk = 21
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
    }

    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            minSdk = 23
            targetSdk = 35
        }
        create("lite") {
            dimension = "edition"
            targetSdk = 22
            applicationIdSuffix = ".lite"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/NOTICE", "META-INF/LICENSE")
    }
}

dependencies {
    // AppCompat 的传递依赖同时引用新旧 Kotlin 标准库模块，用 BOM 将其统一到同一版本。
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.22"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    add("standardImplementation", "dev.rikka.shizuku:api:13.1.5")
    add("standardImplementation", "dev.rikka.shizuku:provider:13.1.5")
    add("standardImplementation", "com.github.topjohnwu.libsu:core:6.0.0")
    add("standardCompileOnly", "androidx.annotation:annotation:1.3.0")
}
