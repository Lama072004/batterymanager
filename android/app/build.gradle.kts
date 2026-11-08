import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Release-Signatur: android/key.properties (nicht eingecheckt, Vorlage key.properties.example).
// Fehlt die Datei, wird zur Entwicklung mit dem Debug-Schlüssel signiert und gewarnt.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "de.mlampert.batterymanager"
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // Endgültig: Änderung später = neue App + Datenverlust.
        applicationId = "de.mlampert.batterymanager"
        minSdk = 24
        targetSdk = 36
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKey) {
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
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("batterymanager: android/key.properties fehlt – Release wird mit dem DEBUG-Schlüssel signiert (nicht installationsstabil).")
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/specAssets"))

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// spec/db (Schema + Abfragen) ist die einzige Schemaquelle: als Asset "db/..." ins APK kopieren.
val copySpecDb by tasks.registering(Copy::class) {
    from(rootProject.file("../spec/db"))
    into(layout.buildDirectory.dir("generated/specAssets/db"))
}
tasks.named("preBuild") { dependsOn(copySpecDb) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

flutter {
    source = "../.."
}
