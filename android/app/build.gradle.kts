plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "com.wififiles.android"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig { applicationId = "com.wififiles.android"; minSdk = 30; targetSdk = 36; versionCode = 3; versionName = "0.3.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // Signing material stays outside the repository and is supplied only at build time.
    val releaseKey = providers.environmentVariable("SFLINK_KEYSTORE_PATH").orNull
    if (releaseKey != null) {
        signingConfigs.create("production") {
            storeFile = file(releaseKey)
            storePassword = providers.environmentVariable("SFLINK_KEYSTORE_PASSWORD").orNull ?: error("SFLINK_KEYSTORE_PASSWORD ausente")
            keyAlias = providers.environmentVariable("SFLINK_KEY_ALIAS").orNull ?: error("SFLINK_KEY_ALIAS ausente")
            keyPassword = providers.environmentVariable("SFLINK_KEY_PASSWORD").orNull ?: error("SFLINK_KEY_PASSWORD ausente")
        }
    }
    buildTypes {
        release {
            if (releaseKey != null) signingConfig = signingConfigs.getByName("production")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            matchingFallbacks += "release"
        }
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
