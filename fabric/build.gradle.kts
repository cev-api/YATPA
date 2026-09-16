plugins {
    id("fabric-loom") version "1.11.8"
    java
}

base {
    archivesName.set("YATPA")
}

sourceSets.main { java.srcDir("../shared/src/main/java") }

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.6")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.16.13")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.128.2+1.21.6")
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

sourceSets.test { java.srcDir("../shared/src/test/java") }

val dialogTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.yatpa.dialog.SettingsDialogTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
tasks.check { dependsOn(dialogTest) }

val dialogCodecTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.yatpa.fabric.DialogCodecTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
tasks.check { dependsOn(dialogCodecTest) }
