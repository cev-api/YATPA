plugins {
    id("net.fabricmc.fabric-loom") version "1.18.2"
    java
}

base {
    archivesName.set("YATPA")
}

sourceSets.main { java.srcDir("../shared/src/main/java") }

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.160.0+26.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.named<org.gradle.jvm.tasks.Jar>("jar") {
    archiveFileName.set("YATPA-v${project.version}-Fabric.jar")
}

sourceSets.test { java.srcDir("../shared/src/test/java") }
tasks.test {
    failOnNoDiscoveredTests = false
}

val dialogTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.yatpa.dialog.SettingsDialogTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
tasks.check { dependsOn(dialogTest) }

val dialogCodecTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.yatpa.fabric.DialogCodecTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
tasks.check { dependsOn(dialogCodecTest) }
