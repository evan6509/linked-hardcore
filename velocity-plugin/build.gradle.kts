import org.gradle.api.tasks.SourceSet

plugins {
    java
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:${property("velocity_version")}")
    annotationProcessor("com.velocitypowered:velocity-api:${property("velocity_version")}")

    compileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
    testRuntimeOnly("com.google.code.gson:gson:2.11.0")
}

tasks.test {
    useJUnitPlatform()
}

// The velocity-api annotation processor emits velocity-plugin.json into
// CLASS_OUTPUT (build/classes/java/main), which the jar task picks up automatically.

// No shading needed: Velocity provides adventure, brigadier, guava, gson, etc. at runtime.
