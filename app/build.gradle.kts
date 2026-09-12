import java.util.Properties

plugins {
    id("com.android.application")
}

// ================= 版本号单一来源：version.properties（每次构建自动递增） =================
// 规则：
//   1) 版本号只存于 安卓端/version.properties，defaultConfig 与交付物命名均引用其值；
//   2) 执行 assemble / bundle / deliverApk 类构建任务时，自动 versionCode +1、versionName 的 PATCH 位 +1；
//   3) 递增发生在配置阶段且发生于本次构建取值之前，因此「APK 内 versionName = 成品文件名」始终一致；
//   4) 非构建调用（IDE 同步、gradlew tasks 等）只读取、不递增。
val versionPropsFile = rootProject.file("version.properties")

fun bumpPatch(name: String): String {
    val parts = name.trim().split(".")
    if (parts.size != 3) throw GradleException("versionName 需为 x.y.z 格式，当前为：$name")
    val patch = parts[2].toIntOrNull() ?: throw GradleException("versionName PATCH 位不是数字：$name")
    return "${parts[0]}.${parts[1]}.${patch + 1}"
}

fun isBuildInvocation(): Boolean =
    gradle.startParameter.taskNames.map { it.lowercase() }.any { n ->
        n.contains("assemble") || n.contains("bundle") || n.contains("deliverapk") ||
            n == "build" || n.endsWith(":build")
    }

fun loadVersionProps(): Pair<Int, String> {
    val props = Properties()
    versionPropsFile.inputStream().use { props.load(it) }
    val code = props.getProperty("versionCode")?.trim()?.toIntOrNull()
        ?: throw GradleException("version.properties 缺少合法的 versionCode")
    val name = props.getProperty("versionName")?.trim()
        ?: throw GradleException("version.properties 缺少 versionName")
    return code to name
}

fun saveVersionProps(code: Int, name: String) {
    versionPropsFile.writeText("versionCode=$code\nversionName=$name\n", Charsets.UTF_8)
}

val (appVersionCode, appVersion) = run {
    val (code, name) = loadVersionProps()
    if (!isBuildInvocation()) {
        code to name
    } else {
        val newCode = code + 1
        val newName = bumpPatch(name)
        saveVersionProps(newCode, newName)
        println("[version] 本次构建自动递增：versionCode $code -> $newCode，versionName $name -> $newName")
        newCode to newName
    }
}

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
// 版本号由 安卓端/version.properties 单一来源驱动（本次构建已自动递增），无需重复维护
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

// ================= 版本号（已改为构建时自动递增，无需手动任务） =================
// 单一来源：安卓端/version.properties；每次执行 assemble / bundle / deliverApk / build 类构建任务自动递增。
// 如需在不出包的情况下查询当前版本号，直接查看 安卓端/version.properties 即可。
