plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.cashflow.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cashflow.app"
        minSdk = 26
        targetSdk = 35
        // CI run number so every build can install over the previous one; 1 for local builds.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.10.0"

        // Per-variant identity. The real app is the default; the "lab" build type overrides all of these (see below).
        buildConfigField("boolean", "IS_LAB", "false")
        buildConfigField("String", "ID_SUFFIX", "\"\"")
        buildConfigField("String", "UPDATE_VERSION_URL", "\"https://github.com/Dragovitcmia-dot/MoneyPlan/releases/download/latest-debug/version.json\"")
        manifestPlaceholders["allowBackup"] = "true"
    }

    // A fixed, committed debug key: every CI build is signed identically and updates in place.
    // (Debug-only key with the well-known password; never use it for a release.)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        // What CI ships: a release-like build (R8, no debug flag) signed with the fixed debug key,
        // same applicationId and version logic, so it installs over earlier builds. It keeps the
        // debug tools (see the sourceSets block below).
        create("preview") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // "Cashflow Lab": the same code as the preview build, installable next to the real app with completely separate data.
        // Own applicationId, label ("Cashflow Lab", src/lab/res), amber-badged icon (src/lab/res), no Google backup, its own update
        // channel. Same committed key, R8-minified, not debuggable. Lab-only code lives in src/lab/java; every other variant gets
        // no-op stand-ins from src/labStub/java, so the real app contains no lab code at all.
        create("lab") {
            initWith(getByName("preview"))
            applicationIdSuffix = ".lab"
            versionNameSuffix = "-lab"
            matchingFallbacks += listOf("preview", "release")
            buildConfigField("boolean", "IS_LAB", "true")
            buildConfigField("String", "ID_SUFFIX", "\".lab\"")
            buildConfigField("String", "UPDATE_VERSION_URL", "\"https://github.com/Dragovitcmia-dot/MoneyPlan/releases/download/latest-lab/version.json\"")
            manifestPlaceholders["allowBackup"] = "false"
        }
    }

    // The debug-only tools (Load sample data, Clear all data) are also part of the preview build.
    sourceSets {
        getByName("preview") {
            java.srcDir("src/debug/java")
        }
        // The lab build has the debug tools' data helpers (reference scenario, scenario catalogue) and its own lab code.
        getByName("lab") {
            java.srcDir("src/debug/java")
        }
        // No-op stand-ins for the lab hooks everywhere except the lab build.
        getByName("debug") { java.srcDir("src/labStub/java") }
        getByName("release") { java.srcDir("src/labStub/java") }
        getByName("preview") { java.srcDir("src/labStub/java") }
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
}

composeCompiler {
    stabilityConfigurationFile.set(layout.projectDirectory.file("compose-stability.conf"))
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Room writes the schema file of a database the first time it sees it. The variants' ksp tasks share one schema
// folder, so they must not run at the same time (two writers leave an empty file: "Empty schema file").
afterEvaluate {
    tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("Kotlin") }.toList()
        .sortedBy { it.name }
        .zipWithNext { first, second -> second.mustRunAfter(first) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3") // real SQLite files for the backup round-trip tests
}
