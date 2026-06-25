import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) localProps.load(localPropsFile.inputStream())

android {
    namespace = "com.hik.otest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hik.otest"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String",  "HIKOTEST_GITHUB_TOKEN", "\"${localProps.getProperty("hikotest.github.token", "")}\"")
        buildConfigField("String",  "HIKOTEST_REPO_OWNER",   "\"${localProps.getProperty("hikotest.repo.owner", "")}\"")
        buildConfigField("String",  "HIKOTEST_REPO_NAME",    "\"${localProps.getProperty("hikotest.repo.name", "")}\"")
        buildConfigField("String",  "HIKOTEST_ENVIRONMENT",  "\"${localProps.getProperty("hikotest.environment", "production")}\"")
        buildConfigField("boolean", "HIKOTEST_IS_BETA",      "${localProps.getProperty("hikotest.is.beta", "false")}")
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Android içinde WASM binary kodlarını native hızda koşturacak motor
//    implementation("io.github.vshnv:wasman:0.1.3")
    implementation("io.github.kawamuray.wasmtime:wasmtime-java:0.1.0")

    // Ağ istekleri ve indirme yönetimi için
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(project(":hikotest-sdk"))

}