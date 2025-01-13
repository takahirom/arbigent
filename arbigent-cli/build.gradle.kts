import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    application
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.palantir.git-version") version "0.15.0"
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
        provider { "-Xfriend-paths=${friendCollection.joinToString(",")}"}
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

