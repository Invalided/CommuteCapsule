plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.busnotice.commute"
    compileSdk = 36

    defaultConfig {
        // 包名可通过环境变量 APP_ID 覆盖
        applicationId = System.getenv("APP_ID") ?: "io.github.commutecapsule"
        // 图标配色：接了自有数据源（src/extra）的构建用蓝底白车，开源版用深底金车，桌面上好区分
        val ownBuild = file("src/extra").exists()
        resValue("color", "launcher_bg", if (ownBuild) "#FF3D6BE0" else "#FF151C33")
        resValue("color", "launcher_fg", if (ownBuild) "#FFFFFFFF" else "#FFE8C27A")
        minSdk = 31
        targetSdk = 36
        // 版本号：CI 由 git 标签算出 VERSION_NAME（如 0.4.0 或 0.4.1-dev.12），versionCode = 主*10000+次*100+修订
        val ver = System.getenv("VERSION_NAME") ?: "0.0.0-local"
        val (vMaj, vMin, vPat) = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(ver)!!.destructured
        versionCode = vMaj.toInt() * 10000 + vMin.toInt() * 100 + vPat.toInt()
        versionName = ver
        // 检查更新用的仓库（owner/name）：CI 里自动取当前仓库，本地构建为空 = 不检查
        buildConfigField("String", "UPDATE_REPO", "\"" + (System.getenv("GITHUB_REPOSITORY") ?: "") + "\"")
    }

    // 签名：CI 里有 SIGNING_KEYSTORE_B64 等密钥时用固定签名（每次构建可覆盖安装），否则用 Android 默认调试签名
    val ks = System.getenv("SIGNING_KEYSTORE_PATH")
    if (!ks.isNullOrEmpty() && file(ks).exists()) {
        signingConfigs {
            create("fixed") {
                storeFile = file(ks)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
        buildTypes { debug { signingConfig = signingConfigs.getByName("fixed") } }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }

    // 可选扩展源码（如自定义数据源）：目录存在时参与编译，见 docs/DATA_SOURCE.md
    if (file("src/extra").exists()) {
        sourceSets["main"].java.srcDir("src/extra/java")
        sourceSets["main"].assets.srcDir("src/extra/assets")
        sourceSets["test"].java.srcDir("src/extra/test")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
