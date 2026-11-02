// Small test app for milestone L0: runs the wire code on old devices and measures its APK size.
// Not part of the published library.
plugins {
    alias(libs.plugins.android.application)
}

val desugar = providers.gradleProperty("sedap.desugar").map(String::toBoolean).getOrElse(true)
val withLib = providers.gradleProperty("probe.withLib").map(String::toBoolean).getOrElse(true)
// Puts the checks into the app itself, so that a minified release build can run them.
val selfCheck = providers.gradleProperty("probe.selfCheck").map(String::toBoolean).getOrElse(false)

android {
    namespace = "de.visentra.sedapexpress.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.visentra.sedapexpress.probe"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = desugar
    }

    sourceSets {
        getByName("main").java.srcDir(
            when {
                selfCheck -> "src/selfCheck/java"
                withLib -> "src/withLib/java"
                else -> "src/withoutLib/java"
            },
        )
        if (selfCheck) {
            getByName("main").java.srcDir("../sedap-wire/src/sharedTest/java")
            getByName("main").assets.srcDir("../vectors")
        }
        getByName("androidTest").java.srcDir("../sedap-wire/src/sharedTest/java")
        getByName("androidTest").assets.srcDir("../vectors")
    }
}

dependencies {
    if (withLib) {
        implementation(project(":sedap-wire"))
    }
    if (desugar) {
        coreLibraryDesugaring(libs.desugar.jdk.libs)
    }
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
