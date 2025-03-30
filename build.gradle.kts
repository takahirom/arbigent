// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    id("org.jetbrains.kotlin.jvm") version libs.versions.kotlin apply false
    id("org.jetbrains.kotlin.plugin.serialization") version libs.versions.kotlin apply false
    alias(libs.plugins.buildconfig) apply false
}

allprojects {
    tasks.withType(Test::class).configureEach {
        testLogging {
            lifecycle {
                showStackTraces = true
            }
        }
    }
}