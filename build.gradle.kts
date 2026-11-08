plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Runs the unit tests under another default locale, e.g. -Psedap.testLocale=de-DE, to catch
// formatting that depends on it (PROTOCOL §8). Without the property the JVM default applies.
providers.gradleProperty("sedap.testLocale").orNull?.let { tag ->
    val locale = java.util.Locale.forLanguageTag(tag)
    subprojects {
        tasks.withType<Test>().configureEach {
            // Gradle does not count user.language and user.country as inputs, so a run under a
            // different locale would otherwise be skipped as up to date.
            inputs.property("sedap.testLocale", tag)
            systemProperty("user.language", locale.language)
            systemProperty("user.country", locale.country)
        }
    }
}
