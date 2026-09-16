plugins {
    java
}

sourceSets.main { java.srcDir("../shared/src/main/java") }

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.123-stable")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    implementation("net.kyori:adventure-api:5.2.0")
    implementation("net.kyori:adventure-text-serializer-plain:5.2.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.jar {
    archiveFileName.set("YATPA-v${project.version}-Paper.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { zipTree(it) })
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
