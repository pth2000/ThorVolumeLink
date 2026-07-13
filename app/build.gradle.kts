plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.thorvolume.control"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.thorvolume.control"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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

    buildTypes {
        release {
            isMinifyEnabled = false
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
    add("standardCompileOnly", "androidx.annotation:annotation:1.3.0")
}
