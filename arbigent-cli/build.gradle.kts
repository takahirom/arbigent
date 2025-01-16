import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.Properties

plugins {
  id("org.jetbrains.kotlin.jvm") version "2.0.21"
  application
  id("org.jetbrains.compose")
  id("org.jetbrains.kotlin.plugin.compose")
  id("com.palantir.git-version") version "0.15.0"
  id("org.gradle.crypto.checksum") version "1.4.0"
}

val gitVersion: groovy.lang.Closure<String> by extra
version = gitVersion()

val localProperties = Properties()
if (rootProject.file("local.properties").exists()) {
  localProperties.load(rootProject.file("local.properties").inputStream())
}

application {
  mainClass.set("io.github.takahirom.arbigent.cli.MainKt")
  applicationName = "arbigent"
}

tasks.run.get().workingDir = File(System.getProperty("user.dir"))

val checksumAlgorithms = listOf(Checksum.Algorithm.MD5, Checksum.Algorithm.SHA256)

tasks {
  val distTasks = listOf(distTar, distZip)

  // Generate checksums for each distribution to separated directories
  distTasks.forEach { distTask ->
    checksumAlgorithms.forEach { algorithm ->
      val checksumTaskName = "generate${algorithm.name.capitalize()}For${distTask.name.capitalize()}"
      register<Checksum>(checksumTaskName) {
        dependsOn(distTask)
        inputFiles.from(distTask.get().outputs.files)
        outputDirectory.set(layout.buildDirectory.dir("tmp/checksums/${distTask.name}/${algorithm.name}"))
        checksumAlgorithm.set(algorithm)
      }
      distTask.configure { finalizedBy(checksumTaskName) }
    }
  }

  // Aggregate checksums into a single directory
  val assembleChecksums by registering(Copy::class) {
    dependsOn(distTasks.flatMap { distTask ->
      checksumAlgorithms.flatMap { algorithm ->
        getTasksByName("generate${algorithm.name.capitalize()}For${distTask.name.capitalize()}", false)
      }
    })
    from(layout.buildDirectory.dir("tmp/checksums")) {
      include("**/*.md5", "**/*.sha256")
      eachFile {
        val newName = file.name
        relativePath = RelativePath(true, newName)
      }
      includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("distributions"))
  }

  assemble {
    dependsOn(assembleChecksums)
  }
}

tasks.distTar {
  compression = Compression.GZIP
  archiveExtension.set("tar.gz")
}

// Workaround for running mosaic not RAW mode
// https://github.com/JakeWharton/mosaic/issues/496#issuecomment-2585240776
// https://www.liutikas.net/2025/01/12/Kotlin-Library-Friends.html
val friends = configurations.create("friends") {
  isCanBeResolved = true
  isCanBeConsumed = false
  isTransitive = false
}
// Make sure friends libraries are on the classpath
configurations.findByName("implementation")?.extendsFrom(friends)
// Make these libraries friends :)
tasks.withType<KotlinJvmCompile>().configureEach {
  val friendCollection = friends.incoming.artifactView { }.files
  compilerOptions.freeCompilerArgs.add(
    provider { "-Xfriend-paths=${friendCollection.joinToString(",")}" }
  )
}


dependencies {
  implementation("com.github.ajalt.clikt:clikt:5.0.2")
  friends("com.jakewharton.mosaic:mosaic-runtime:0.14.0")
  implementation(project(":arbigent-core"))
  implementation(project(":arbigent-ai-openai"))
  testImplementation(kotlin("test"))
  // coroutine test
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.5.2")
}

