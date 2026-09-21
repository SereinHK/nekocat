import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 发布签名配置。
 *
 * 密钥不写进仓库，而是从仓库根目录的 `keystore.properties` 读（该文件已被 .gitignore 忽略）。
 * 没有这个文件时**不影响任何构建**：release 包照常产出，只是未签名（不能直接安装/分发）。
 * 首次创建密钥：
 *   keytool -genkeypair -v -keystore nekochat.jks -alias nekochat \
 *     -keyalg RSA -keysize 2048 -validity 10000
 * 然后在仓库根目录建 keystore.properties：
 *   storeFile=nekochat.jks
 *   storePassword=...
 *   keyAlias=nekochat
 *   keyPassword=...
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.nekochat"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nekochat"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 配了密钥就签名，没配就保持未签名（不报错）
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // 使用内置 Kotlin 时，jvmTarget 默认跟随 compileOptions.targetCompatibility
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Xiaomi HyperOS 风格组件库
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.preference)
    // 液态玻璃（背景模糊 + 玻璃描边高光）。依赖 RuntimeShader，Android 需 API 33+，
    // 因此所有调用点都必须用 isRuntimeShaderSupported() 门控。
    implementation(libs.miuix.blur)

    // 二维码连接：zxing core 纯 Java 实现，不依赖 GMS
    //（MatePad 是鸿蒙底子的 Android 12，没有 Google Play 服务）
    implementation(libs.zxing.core)
    // 扫码预览自己接 CameraX，不用第三方扫码 Activity
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // 纯逻辑单元测试（协议编解码 / 组网路由），不依赖真机
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.mockito:mockito-core:5.14.2")
}
