plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
}

dependencies {
    implementation(project(":arbiter-core"))
    testImplementation(kotlin("test"))
    // coroutine test
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.5.2")
}

