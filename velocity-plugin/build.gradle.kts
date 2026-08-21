import org.gradle.api.tasks.SourceSet;

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

    // Gson is bundled with Velocity at runtime; we only need it at compile time
    // to read/write the plugin's config.json and reset signal files.
    compileOnly("com.google.code.gson:gson:2.11.0")

    testImplementation("com.velocitypowered:velocity-api:${property("velocity_version")}")
    testImplementation("org.mockito:mockito-core:5.15.2")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

// The velocity-api annotation processor emits velocity-plugin.json into
// CLASS_OUTPUT (build/classes/java/main), which the jar task picks up automatically.

// No shading needed: Velocity provides adventure, brigadier, guava, gson, etc. at runtime.
