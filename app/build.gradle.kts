import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.gms.google.services)
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.isFile) localFile.inputStream().use { load(it) }
}
val groqApiKey = localProperties.getProperty("GROQ_API_KEY")
    ?.takeIf { it.isNotBlank() }
    ?: providers.environmentVariable("GROQ_API_KEY").orNull.orEmpty()

android {
    namespace = "com.example.datn_v1"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.datn_v1"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
//        ndk {
//            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
//        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GROQ_API_KEY", "\"$groqApiKey\"")

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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    androidResources{
        noCompress += "tflite"
    }
    packaging {
        jniLibs {
            // Ép hệ thống trích xuất thư viện native để tự động căn lề khi cài đặt
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.drawerlayout)
//    implementation(libs.firebase.database.ktx)
    implementation(libs.firebase.database)
//    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.auth)
    implementation(libs.androidx.ui.graphics)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.gpu)
    implementation(libs.tensorflow.lite.gpu.api)
    implementation(libs.google.guava)
    implementation(libs.tensorflow.lite.support)
    implementation(libs.tensorflow.lite.select.tf.ops)
    implementation(libs.opencv.android)
    // WebRTC
    implementation(libs.stream.webrtc.android)
    // Chatbot: Groq REST API qua OkHttp + Coroutines
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}
