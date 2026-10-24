plugins {
    // Matches gradle.properties loom_version (1.17 line). Pinned as a literal here
    // because the plugins DSL can't resolve project properties. The full plugin id
    // must be used — the short id `fabric-loom` resolves to a stale snapshot marker
    // that predates the non-obfuscated (baked-in mojmap) MC 26.x support.
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

// For the current MC line (26.x) there is NO yarn and no separate mappings
// artifact: Loom auto-detects that the game jar ships with Mojang's official
// mappings baked in. Do not add a `mappings` block.
repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://api.modrinth.com/maven")
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

// The default tuple and artifact path remain compatible with the Docker installer.
val minecraftTarget = providers.gradleProperty("minecraft_version").get()
val targetApiVersion = when (minecraftTarget) {
    "26.2" -> "0.156.0+26.2"
    "26.3" -> "0.165.0+26.3"
    else -> error("Unsupported Minecraft target: $minecraftTarget (choose 26.2 or 26.3)")
}
val loaderVersion = providers.gradleProperty("loader_version").get()
require(providers.gradleProperty("fabric_api_version").orElse(targetApiVersion).get() == targetApiVersion) {
    "Minecraft $minecraftTarget requires fabric_api_version=$targetApiVersion"
}
// Isolate compiled classes, test reports and resources when switching targets.
if (minecraftTarget != "26.2") {
    layout.buildDirectory.set(layout.projectDirectory.dir("build/$minecraftTarget"))
    tasks.withType<Jar>().configureEach {
        archiveBaseName.set("fabric-mod-mc$minecraftTarget")
    }
}

dependencies {
    testImplementation("org.mockito:mockito-core:5.15.2")
    // Exercise the actual wire sender/receiver together; production modules stay independent.
    testImplementation(project(":velocity-plugin"))
    testImplementation("com.velocitypowered:velocity-api:${property("velocity_version")}")
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    // NOTE: `implementation`, NOT `modImplementation` — in Loom 1.17's
    // non-obfuscated mode `modImplementation` no longer exists; the example mod
    // uses plain `implementation` for mods.
    implementation("net.fabricmc:fabric-loader:${loaderVersion}")
    implementation("net.fabricmc.fabric-api:fabric-api:${targetApiVersion}")

    // FabricProxy-Lite — bundled into THIS jar (Jar-in-Jar) so the operator only
    // drops one jar into each server's mods/ folder. It still runs as its own mod
    // at runtime. MIT license permits redistribution. Version pinned here on purpose:
    // this repo owns bumping it when the target MC version changes.
    implementation("maven.modrinth:fabricproxy-lite:${property("fabricproxy_lite_version")}")
    include("maven.modrinth:fabricproxy-lite:${property("fabricproxy_lite_version")}")
}

loom {
    mods {
        create("linkedhardcore") {
            sourceSet(sourceSets.main.get())
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    val version = project.version.toString()
    inputs.property("version", version)
    inputs.property("minecraftTarget", minecraftTarget)
    inputs.property("fabricApiVersion", targetApiVersion)
    inputs.property("loaderVersion", loaderVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to version,
            "minecraft" to "~$minecraftTarget",
            "fabricApi" to ">=$targetApiVersion",
            "loader" to ">=${loaderVersion}")
    }
}

java {
    withSourcesJar()
}
