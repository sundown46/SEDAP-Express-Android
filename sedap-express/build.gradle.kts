plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "de.visentra.sedapexpress"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    // The golden vectors are read in place, never copied.
    sourceSets["test"].resources.srcDir(rootProject.file("vectors"))

    testOptions {
        unitTests.isReturnDefaultValues = false
    }

    lint {
        abortOnError = false
        checkDependencies = false
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":sedap-wire"))
    implementation(libs.kotlinx.coroutines.core)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
