pluginManagement {
    repositories {
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        kotlin("jvm").version(extra["kotlin.version"] as String)
        id("org.jetbrains.compose").version(extra["compose.version"] as String)
        id("org.jetbrains.kotlin.plugin.compose").version(extra["kotlin.version"] as String)
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://www.jetbrains.com/intellij-repository/snapshots")
        maven("https://packages.jetbrains.team/maven/p/kpm/public/")
    }
}

rootProject.name = "arbigent"
include(":arbigent-core")
include(":arbigent-device-maestro")
include(":arbigent-ai-openai")
include(":arbigent-ui")
include(":arbigent-cli")
include(":sample-test")
