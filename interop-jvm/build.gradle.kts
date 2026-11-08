// Test-only module (D-30): compiles the unmodified Java reference for the JVM, so the tests of
// sedap-express can talk to it in a separate process. Nothing here ships with the library, and this
// is the only place where BouncyCastle is on a main classpath.
plugins {
    java
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    // Gradle would see upstream's module-info.java in the source folder despite the exclude below
    // and compile everything as a module that needs JavaFX and Jakarta.
    modularity.inferModulePath.set(false)
}

sourceSets {
    main {
        java {
            srcDir(rootProject.file("upstream/SEDAPExpress/src/main/java"))
            // Upstream parts that need more than BouncyCastle. Kept as short as possible.
            exclude(
                "**/module-info.java", // the reference runs on the class path
                "**/controls/**", // JavaFX
                "**/json/**", // Gson, Jakarta
                "**/protobuf/**", // Protobuf
                "**/network/SEDAPExpressMQTTClient.java", // Eclipse Paho
                "**/network/SEDAPExpressRESTClient.java", // Gson, json/
                "**/network/SEDAPExpressRESTServer.java", // Gson, json/
                "**/network/SSLUtils.java", // bctls, bcpkix
            )
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Upstream's deprecation and unchecked notes are not ours to fix.
    options.compilerArgs.add("-nowarn")
}

dependencies {
    implementation(libs.bouncycastle.prov)
}
