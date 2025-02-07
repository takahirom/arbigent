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
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://www.jetbrains.com/intellij-repository/snapshots")
        maven("https://packages.jetbrains.team/maven/p/kpm/public/")
    }
}

rootProject.name = "arbigent"
include(":arbigent-core-model")
include(":arbigent-core")
include(":arbigent-device-maestro")
include(":arbigent-ai-openai")
include(":arbigent-cli")
include(":arbigent-ui")
include(":arbigent-core-web-report")
include(":sample-test")

includeBuild("../roborazzi") {
    dependencySubstitution {
        substitute(module("io.github.takahirom.roborazzi:roborazzi-ai-openai")).using(project(":roborazzi-ai-openai"))
    }
}

includeBuild("../roborazzi/include-build") {
    dependencySubstitution {
        substitute(module("io.github.takahirom.roborazzi:roborazzi-core")).using(project(":roborazzi-core"))
    }
}
