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

val slf4jVersion = "2.0.13"
allprojects {
  // Apply dependency resolution rules to all projects
  configurations.all {
    resolutionStrategy {
      // Use dependency substitution to replace SLF4J bindings with slf4j-nop
      dependencySubstitution {
        // Iterate over all requested dependencies
        all {
          val dependency: DependencySubstitution = this
          // Check if the requested dependency is an SLF4J module
          if (dependency.requested is ModuleComponentSelector) {
            val requested = dependency.requested as ModuleComponentSelector
            if (requested.group == "org.slf4j") {
              // List of SLF4J bindings to replace
              // Add any other bindings you might encounter (e.g., logback-classic, slf4j-log4j12, slf4j-jdk14, slf4j-simple)
              val bindingsToReplace = setOf(
                "slf4j-classic",
                "slf4j-log4j12",
                "slf4j-jdk14",
                "slf4j-simple",
                "slf4j-jcl",
                "logback-classic" // Logback also uses slf4j-api but is a common binding
                // Add other potential bindings here if needed
              )

              // If the requested module is one of the bindings we want to replace...
              if (requested.module in bindingsToReplace) {
                // ...substitute it with slf4j-nop using the same requested version.
                // Using the same version ensures compatibility with the slf4j-api version likely required transitively.
                // However, we override it with our consistent slf4jVersion for clarity and control.
                dependency.useTarget(
                  "org.slf4j:slf4j-nop:${slf4jVersion}",
                  "Replaced SLF4J binding '${requested.module}' with 'slf4j-nop' globally."
                )
              }
            }
            // Handle Logback specifically if it's brought in directly sometimes
            else if (requested.group == "ch.qos.logback" && requested.module == "logback-classic") {
              dependency.useTarget(
                "org.slf4j:slf4j-nop:${slf4jVersion}",
                "Replaced Logback binding 'logback-classic' with 'slf4j-nop' globally."
              )
            }
          }
        }
      }

      // Optionally, force the slf4j-api version as well for consistency
      // This prevents different transitive versions of slf4j-api causing issues.
      eachDependency {
        if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
          useVersion(slf4jVersion)
          because("Ensure consistent SLF4J API version across all dependencies")
        }
      }
    }
  }
}