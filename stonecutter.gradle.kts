plugins {
    id("dev.kikugie.stonecutter")
    // Loaded here once, so every version's project shares them.
    id("net.fabricmc.fabric-loom-remap") version "1.17-SNAPSHOT" apply false // obfuscated versions like 1.21.1
    id("net.neoforged.moddev") version "2.0.147" apply false
}

// The version the code in src/ is set up for right now (Stonecutter rewrites this line when switching).
stonecutter active "1.21.1-neoforge"

stonecutter parameters {
    val (version, loader) = current.project.split('-', limit = 2)
    // Properties from stonecutter.properties.toml: the ones for this version and this loader.
    properties {
        tags(version, loader)
    }
    // "//? if fabric {" and friends in the code.
    constants {
        match(loader, "fabric", "neoforge")
    }
}
