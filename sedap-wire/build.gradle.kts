import java.nio.charset.StandardCharsets

plugins {
    alias(libs.plugins.android.library)
}

val upstreamSources = rootProject.layout.projectDirectory.dir("upstream/SEDAPExpress/src/main/java")
val adaptedSources = layout.buildDirectory.dir("generated/upstream-adapted/java")

// Upstream files that cannot or should not run on Android. See PATCHES.md.
val excludedUpstream = listOf(
    "module-info.java",
    "**/ReleaseInfo.java",
    "**/controls/**",
    "**/json/**",
    "**/network/**",
    "**/processing/**",
    "**/protobuf/**",
    "**/utils/DHUtils.java",
    "**/utils/ECDHUtils.java",
    "**/utils/EncryptionUtils.java",
    "**/utils/FrodoUtils.java",
    "**/utils/KyberUtils.java",
    // Replaced by src/main/java/.../utils/MACUtils.java, which does not need BouncyCastle.
    "**/utils/MACUtils.java",
)

// Upstream stays untouched: these textual swaps run on a copy during the build. Each entry must
// match in the current upstream state, so an upstream change cannot silently disable a swap.
val swaps = listOf(
    "import java.util.HexFormat;" to "import de.visentra.sedapexpress.compat.HexFormat;",
    "import org.bouncycastle.util.encoders.Base64;" to "import de.visentra.sedapexpress.compat.Base64;",
    "import org.bouncycastle.util.encoders.DecoderException;" to "import de.visentra.sedapexpress.compat.DecoderException;",
    "import java.lang.reflect.RecordComponent;" to "import de.visentra.sedapexpress.compat.RecordComponent;",
    "obj.getClass().getRecordComponents()" to "RecordComponent.of(obj.getClass())",
    "import org.bouncycastle.asn1.ASN1Sequence;" to "",
    "import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;" to "",
    "import org.bouncycastle.jcajce.provider.asymmetric.dh.BCDHPublicKey;" to "import de.visentra.sedapexpress.compat.DhPublicKeys;",
    "new BCDHPublicKey(SubjectPublicKeyInfo.getInstance(ASN1Sequence.getInstance(Base64.decode(value))))" to "DhPublicKeys.decode(Base64.decode(value))",
    "source.chars()" to "de.visentra.sedapexpress.compat.Strings.chars(source)",
    "value.chars()" to "de.visentra.sedapexpress.compat.Strings.chars(value)",
    "!sourceStr.isEmpty()" to "sourceStr.length() != 0",
    "new DeflaterInputStream(" to "de.visentra.sedapexpress.compat.Streams.readAll(new DeflaterInputStream(",
    "CompressUtils.deflater).readAllBytes()" to "CompressUtils.deflater))",
    "new InflaterInputStream(" to "de.visentra.sedapexpress.compat.Streams.readAll(new InflaterInputStream(",
    "CompressUtils.inflater).readAllBytes()" to "CompressUtils.inflater))",
    // Invalid Base64 must not escape from decompressMessage (NFR-REL-02).
    "catch (final IOException e)" to "catch (final IOException | IllegalStateException e)",
)

val adaptUpstream by tasks.registering {
    val input = upstreamSources
    val output = adaptedSources
    val rules = swaps
    val excludes = excludedUpstream
    inputs.dir(input)
    inputs.property("swaps", rules.toString())
    inputs.property("excludes", excludes.toString())
    outputs.dir(output)
    doLast {
        val target = output.get().asFile
        target.deleteRecursively()
        val tree = input.asFileTree.matching { exclude(excludes) }
        val used = mutableSetOf<String>()
        tree.visit {
            if (isDirectory) return@visit
            // Upstream sources are UTF-8 with mixed line endings, both are preserved.
            var text = file.readText(StandardCharsets.UTF_8)
            for ((from, to) in rules) {
                if (text.contains(from)) {
                    used += from
                    text = text.replace(from, to)
                }
            }
            val destination = relativePath.getFile(target)
            destination.parentFile.mkdirs()
            destination.writeText(text, StandardCharsets.UTF_8)
        }
        val unused = rules.map { it.first }.filter { it !in used }
        check(unused.isEmpty()) { "Swap rules no longer match upstream: $unused" }

        // Dex files lose the declaration order of record fields, so it is recorded here.
        val recordPattern = Regex("""\brecord\s+(\w+)\s*\(([^)]*)\)""")
        val entries = input.asFileTree.matching { include("**/messages/*.java") }.files.sortedBy { it.name }
            .flatMap { source ->
                val top = source.nameWithoutExtension
                recordPattern.findAll(source.readText(StandardCharsets.UTF_8)).map { match ->
                    val names = splitParameters(match.groupValues[2]).map { "\"${it.substringAfterLast(' ')}\"" }
                    "            case \"$top\$${match.groupValues[1]}\": return new String[] {${names.joinToString(", ")}};"
                }.toList()
            }
        val layouts = target.resolve("de/visentra/sedapexpress/compat/RecordLayouts.java")
        layouts.parentFile.mkdirs()
        layouts.writeText(
            """
            |package de.visentra.sedapexpress.compat;
            |
            |/** Generated from the upstream sources by sedap-wire/build.gradle.kts. */
            |final class RecordLayouts {
            |
            |    private RecordLayouts() {
            |    }
            |
            |    static String[] componentNames(String name) {
            |        switch (name) {
            |${entries.joinToString("\n") { "|$it" }.trimMargin()}
            |            default: return null;
            |        }
            |    }
            |}
            |
            """.trimMargin(),
            StandardCharsets.UTF_8,
        )
    }
}

/** Splits record parameters at top-level commas, so generic types stay in one piece. */
fun splitParameters(parameters: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    for (c in parameters) {
        when {
            c == '<' -> { depth++; current.append(c) }
            c == '>' -> { depth--; current.append(c) }
            c == ',' && depth == 0 -> { result += current.toString().trim(); current.clear() }
            else -> current.append(c)
        }
    }
    if (current.isNotBlank()) result += current.toString().trim()
    return result
}

android {
    namespace = "de.visentra.sedapexpress.wire"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = providers.gradleProperty("sedap.desugar").map(String::toBoolean).getOrElse(true)
    }

    sourceSets["main"].java.srcDir(adaptedSources)

    sourceSets["test"].java.srcDir("src/sharedTest/java")

    testOptions {
        unitTests.isReturnDefaultValues = false
    }

    lint {
        abortOnError = false
        checkDependencies = false
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.named("preBuild") {
    dependsOn(adaptUpstream)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.bouncycastle.prov)
}
