import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    application
}

group = "com.venturecart"
version = "1.0.0"

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.12"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation(kotlin("test"))
}

application {
    // Default entry point starts the API server.
    // Run the data generator instead with:
    //   ./gradlew run -PmainClass=com.venturecart.decline.datagen.GenerateTestDataKt
    mainClass.set(project.findProperty("mainClass") as String? ?: "com.venturecart.decline.ApplicationKt")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

// Convenience task: `./gradlew generateData`
tasks.register<JavaExec>("generateData") {
    group = "application"
    description = "Generates the 60-day synthetic transaction dataset into test-data/transactions.csv"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.venturecart.decline.datagen.GenerateTestDataKt")
}
