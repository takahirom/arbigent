import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    application
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

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.0.2")
    implementation(project(":arbigent-core"))
    implementation(project(":arbigent-ai-openai"))
    testImplementation(kotlin("test"))
    // coroutine test
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.5.2")
}

