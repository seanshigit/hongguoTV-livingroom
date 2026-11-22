import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }
val signingProperties = Properties().apply { val config = rootProject.file("keystore.properties"); if (config.exists()) config.inputStream().use { load(it) } }
fun signingValue(env: String, key: String): String? = System.getenv(env) ?: signingProperties.getProperty(key)
// Optional repair build can be installed alongside a locally signed older release.
val sideBySide = providers.gradleProperty("hongguotv.sideBySide").orNull == "true"

android {
    namespace = "com.hongguotv.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = if (sideBySide) "com.hongguotv.nativeapp.playbackfix" else "com.hongguotv.nativeapp"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = System.getenv("HONGGUOTV_TEST_RUNNER") ?: "com.hongguotv.nativeapp.TvAcceptance"
        versionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: 31
        versionName = System.getenv("HONGGUOTV_VERSION_NAME") ?: if (sideBySide) "0.14.16-playbackfix" else "0.14.16-local"
        manifestPlaceholders["appLabel"] = if (sideBySide) "红果 TV 播放修复版" else "红果 TV 原生版"
        buildConfigField("boolean", "SIDE_BY_SIDE", sideBySide.toString())
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("delivery") {
            storeFile = signingValue("HONGGUOTV_KEYSTORE", "storeFile")?.let { file(it) }
            storePassword = signingValue("HONGGUOTV_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("HONGGUOTV_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("HONGGUOTV_KEY_PASSWORD", "keyPassword")
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("delivery"); isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module") }
}
dependencies {
    implementation("androidx.core:core:1.8.0")
    implementation("com.google.zxing:core:3.5.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":core")) { exclude(group = "org.json") }
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
}
