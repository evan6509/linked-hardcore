plugins {
    java
}

group = "dev.linkedhardcore"
version = "0.1.0"

subprojects {
    apply(plugin = "java")

    group = rootProject.group
    version = rootProject.version

    java {
        // MC 26.2 / Velocity 4.0 / FabricProxy-Lite 2.12 all require Java 25.
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(25)
        options.encoding = "UTF-8"
    }

    tasks.withType<Jar>().configureEach {
        manifest {
            attributes["Implementation-Title"] = project.name
            attributes["Implementation-Version"] = project.version
        }
    }

    dependencies {
        testImplementation(platform("org.junit:junit-bom:5.11.4"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Mockito's current Byte Buddy release needs this opt-in while running
        // against the Java 25 toolchain required by Minecraft 26.2.
        jvmArgs("-Dnet.bytebuddy.experimental=true")
    }
}
