plugins {
    id("com.android.application")
}

android {
    namespace = "com.sugarpal.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sugarpal.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}

// ================= 交付物命名规范（产品名-版本号） =================
// 版本号变更时，需同步修改 defaultConfig.versionName 与本处 appVersion
val appVersion = "1.1.0"
val apkBaseName = "糖伴SugarPal-$appVersion"

// 构建完成后（assembleDebug / assembleRelease）自动执行：
//   1) 在 app/build/outputs/apk/<variant>/ 生成规范名产物 糖伴SugarPal-<versionName>.apk
//   2) 复制一份到 安卓端\成品\
val deliverDir = rootProject.file("成品")

listOf("Debug", "Release").forEach { variantName ->
    val variantLower = variantName.lowercase()
    tasks.register("deliverApk$variantName") {
        dependsOn("assemble$variantName")
        val apkDir = rootProject.file("app/build/outputs/apk/$variantLower")
        val producedGlob = "app-$variantLower*.apk"
        val producedRegex = "app-$variantLower.*\\.apk"
        doLast {
            // 1) build 输出目录内生成规范名产物  2) 复制到 安卓端\成品\
            listOf(apkDir, deliverDir).forEach { target ->
                copy {
                    from(apkDir) {
                        include(producedGlob)
                        rename(producedRegex, "$apkBaseName.apk")
                    }
                    into(target)
                }
            }
        }
    }
    tasks.matching { it.name == "assemble$variantName" }.configureEach {
        finalizedBy("deliverApk$variantName")
    }
}
