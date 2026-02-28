plugins {
    base
}

allprojects {
    group = "dev.yatpa"
    version = "1.0.0"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.fabricmc.net/")
        maven("https://libraries.minecraft.net/")
    }
}
