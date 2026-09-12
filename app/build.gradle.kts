plugins {
    id("com.android.application")
}

// ================= 版本号单一来源（只需维护这两行） =================
// defaultConfig 与交付物命名均引用此处的 appVersion / appVersionCode，避免多处不同步。
// 每次出包前可执行：gradlew.bat bumpVersion  —— 自动 versionCode +1、versionName patch 位 +1
val appVersionCode = 4
val appVersion = "1.2.1"

android {
    namespace = "com.sugarpal.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sugarpal.app"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
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
// 版本号由文件顶部 appVersion 单一来源驱动，无需重复维护
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

// ================= 版本号自动递增（后续每次出包前执行） =================
// 用法：gradlew.bat bumpVersion
// 规则：versionCode +1，versionName 的 patch 位 +1（如 1.2.1 -> 1.2.2），并回写本文件顶部两行
tasks.register("bumpVersion") {
    group = "versioning"
    description = "递增版本号：versionCode +1 且 versionName patch 位 +1，并回写 build.gradle.kts"

    doLast {
        val scriptFile = file("build.gradle.kts")
        var text = scriptFile.readText()

        val codeRegex = Regex("val appVersionCode = (\\d+)")
        val nameRegex = Regex("val appVersion = \"(\\d+)\\.(\\d+)\\.(\\d+)\"")

        val codeMatch = codeRegex.find(text)
            ?: throw GradleException("未找到 appVersionCode 定义，请检查 build.gradle.kts")
        val nameMatch = nameRegex.find(text)
            ?: throw GradleException("未找到 appVersion 定义，请检查 build.gradle.kts")

        val oldCode = codeMatch.groupValues[1].toInt()
        val major = nameMatch.groupValues[1]
        val minor = nameMatch.groupValues[2]
        val patch = nameMatch.groupValues[3].toInt()

        val newCode = oldCode + 1
        val newName = "$major.$minor.${patch + 1}"

        text = codeRegex.replace(text) { "val appVersionCode = $newCode" }
        text = nameRegex.replace(text) { "val appVersion = \"$newName\"" }
        scriptFile.writeText(text)

        println("[bumpVersion] versionCode: $oldCode -> $newCode")
        println("[bumpVersion] versionName: $major.$minor.$patch -> $newName")
        println("[bumpVersion] 已完成，请执行 assembleDebug 出包")
    }
}
