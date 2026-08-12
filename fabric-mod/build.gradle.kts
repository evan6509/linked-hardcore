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
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    // NOTE: `implementation`, NOT `modImplementation` — in Loom 1.17's
    // non-obfuscated mode `modImplementation` no longer exists; the example mod
    // uses plain `implementation` for mods.
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

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
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

java {
    withSourcesJar()
}
