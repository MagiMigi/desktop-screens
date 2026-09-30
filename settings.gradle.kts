pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        exclusiveContent {
            forRepository { maven("https://maven.fabricmc.net") { name = "Fabric" } }
            filter {
                includeGroupAndSubgroups("net.fabricmc")
                includeGroup("fabric-loom")
            }
        }
        exclusiveContent {
            forRepository { maven("https://maven.neoforged.net/releases") { name = "NeoForged" } }
            filter { includeGroupAndSubgroups("net.neoforged") }
        }
        exclusiveContent {
            forRepository { maven("https://maven.kikugie.dev/releases") { name = "KikuGie" } }
            filter { includeGroupAndSubgroups("dev.kikugie") }
        }
    }
}

plugins {
    // Builds every Minecraft version and loader from the one source tree in src/ (https://stonecutter.kikugie.dev).
    id("dev.kikugie.stonecutter") version "0.9.8"
    // Downloads a matching JDK if the build machine doesn't have one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "desktopscreens"

// core: plain Java, no Minecraft (screen capture, mouse/keyboard, windows). Java 8, so every version can share it.
include("core")

// src:  the Minecraft code, built once per version and loader as versions/<version>-<loader>, each with the build
//       script build.<loader>.gradle.kts. Code for one loader lives in its own package (com.desktopscreens.fabric,
//       ...neoforge); differences between Minecraft versions are marked in the code with Stonecutter comments.
stonecutter {
    create(rootProject) {
        fun match(version: String, vararg loaders: String) {
            for (loader in loaders) version("$version-$loader", version).buildscript("build.$loader.gradle.kts")
        }
        match("1.21.1", "fabric", "neoforge")
        // The version the committed code is written for.
        vcsVersion = "1.21.1-neoforge"
    }
}
