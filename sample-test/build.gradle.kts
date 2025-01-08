import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
}
val localProperties = Properties()
if (rootProject.file("local.properties").exists()) {
    localProperties.load(rootProject.file("local.properties").inputStream())
}

tasks.withType<Test> {
    useJUnitPlatform()
    this.systemProperty("OPENAI_API_KEY", localProperties.getProperty("OPENAI_API_KEY"))
}

dependencies {
    implementation(project(":arbigent-core"))
    implementation(project(":arbigent-ai-openai"))
    testImplementation(kotlin("test"))
    // maestro client
    api("dev.mobile:maestro-client:1.39.1")
    // coroutine test
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.5.2")
}

