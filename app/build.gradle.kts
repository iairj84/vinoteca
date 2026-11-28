plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.wineverdict.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wineverdict.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 65
        versionName = "1.2.50"
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        // CI decodes the project's keystore into a temp file and points SIDELOAD_KEYSTORE_FILE at
        // it, so every release carries the same signature and can install over the one before it.
        // A local build without that file falls back to the debug key and is not installable over
        // a published release.
        val keystore = System.getenv("SIDELOAD_KEYSTORE_FILE")?.let { File(it) }?.takeIf { it.exists() }
        if (keystore != null) {
            create("update") {
                storeFile = keystore
                storePassword = System.getenv("SIDELOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIDELOAD_KEY_ALIAS")
                keyPassword = System.getenv("SIDELOAD_KEY_PASSWORD")
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
        }
        // Small, installable build for sideloading. Signed with the debug key so it
        // installs without a Play upload key; not for store distribution.
        create("sideload") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("update") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

/*
 * The live instruments (LiveListJudgeTest, LiveChatBudgetTest) talk to Groq and spend real
 * free-tier quota, so the default test task leaves them out: `:app:testDebugUnitTest` stays offline
 * and repeatable. Run them on purpose, one class at a time if quota is tight:
 *
 *     ./gradlew :app:testDebugUnitTest -Plive
 *     ./gradlew :app:testDebugUnitTest -Plive --tests "*LiveChatBudgetTest.thinBudgetOnGroq"
 */
tasks.withType<Test>().configureEach {
    if (!providers.gradleProperty("live").isPresent) {
        exclude("**/Live*Test.class")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    // The post-list reminder has to survive the screen closing and the process being killed.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // The Play-services variant ships no native libraries of its own: the bundled model added
    // 40 MB (four ABIs of the OCR pipeline) to a 1.7 MB APK, and every update would carry it.
    // Play Services fetches the Latin model once, on first use, and shares it across apps.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}