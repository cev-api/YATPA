plugins {
    id("fabric-loom") version "1.11-SNAPSHOT"
    java
}

base {
    archivesName.set("YATPA")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.1")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.16.10")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.116.6+1.21.1")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.named<org.gradle.jvm.tasks.Jar>("jar") {
    archiveFileName.set("YATPA-v${project.version}-Fabric-dev.jar")
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("YATPA-v${project.version}-Fabric.jar")
}
