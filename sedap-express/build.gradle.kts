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

// The unmodified Java reference from :interop-jvm, started by ReferenceInteropProcessTest as its own
// process (D-30). It never reaches the test class path, so BouncyCastle stays out of this module.
val interopRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

tasks.withType<Test>().configureEach {
    val classpath: FileCollection = interopRuntime
    inputs.files(classpath).withPropertyName("interopClasspath").withNormalizer(ClasspathNormalizer::class.java)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dsedap.interop.classpath=${classpath.asPath}") })
}

dependencies {
    interopRuntime(project(":interop-jvm"))

    api(project(":sedap-wire"))
    // api, not implementation: StateFlow, SharedFlow and CoroutineScope are part of the public API.
    api(libs.kotlinx.coroutines.core)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
