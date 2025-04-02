import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm") version libs.versions.kotlin
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
    // maestro client
    api("dev.mobile:maestro-client:1.39.1")
    testImplementation(kotlin("test"))
    // coroutine test
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.5.2")
}

