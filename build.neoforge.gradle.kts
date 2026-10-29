// Builds the NeoForge jar of one Minecraft version (versions/<version>-neoforge) from src/ and core/.
plugins {
    id("net.neoforged.moddev")
}

val loader = "neoforge"
val minecraft = sc.current.version
val modId = property("mod_id") as String
val modName = property("mod_name") as String
val modAuthor = property("mod_author") as String
val license = property("license") as String
// A property of this version and loader, from stonecutter.properties.toml.
fun prop(key: String): String = sc.properties[key]
val javaVersion = prop("java")

base.archivesName = "$modId-$loader-$minecraft"
// The test game and server of this version and loader (worlds, settings, logs).
val runFolder = rootProject.file("runs/${project.name}")

java.toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// src/ holds every loader's code; the other loaders' packages and files stay out of this jar. The recipes, loot
// tables and tags come from the folders in src/data listed for this version (mod.data), in its format.
sourceSets.main {
    java.exclude("com/desktopscreens/fabric/**", "com/desktopscreens/forge/**")
    resources.exclude("fabric.mod.json", "desktopscreens.fabric.mixins.json", "META-INF/mods.toml")
    for (folder in prop("mod.data").split(" ")) resources.srcDir(rootProject.file("src/data/$folder"))
    // Stand-ins for networking classes Minecraft has only from 1.20.5 on (see net/CustomPacketPayload).
    if (sc.current.parsed >= "1.20.5") java.exclude("com/desktopscreens/net/**")
    // 26.1 draws the GUI picture with its own shader (in src/data/26.1) and needs no pack.mcmeta.
    if (sc.current.parsed >= "26.1") resources.exclude("assets/desktopscreens/shaders/core/desktop.*", "pack.mcmeta")
}

// core's sources (and the Concentus copy it prepares) are compiled straight into this jar.
val coreJava: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven("https://maven.parchmentmc.org/") { name = "ParchmentMC" } }
        filter { includeGroup("org.parchmentmc.data") }
    }
}

dependencies {
    coreJava(project(path = ":core", configuration = "coreJava"))
}

tasks.compileJava {
    dependsOn(coreJava)
    source(coreJava)
}

// A land-claim mod in our own test game, to check that the Screen Builder asks it (see LandClaims):
//   gradlew :1.21.1-neoforge:runClient -Pclaims=ftb    FTB Chunks (what All the Mods uses), with FTB Teams and Architectury
//   gradlew :1.21.1-neoforge:runClient -Pclaims=opac   Open Parties and Claims
//   gradlew :26.3-neoforge:runClient -Pclaims=flan     Flan, where a version lists one (deps.flan)
// Only then, and never in the mod's jar.
val claims = findProperty("claims") as String?
if (claims != null) {
    repositories {
        exclusiveContent {
            forRepository { maven("https://maven.ftb.dev/releases") { name = "FTB" } }
            filter { includeGroup("dev.ftb.mods") }
        }
        exclusiveContent {
            forRepository { maven("https://maven.architectury.dev") { name = "Architectury" } }
            filter { includeGroup("dev.architectury") }
        }
        exclusiveContent {
            forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
            filter { includeGroup("maven.modrinth") }
        }
    }
    dependencies {
        when (claims) {
            "ftb" -> runtimeOnly("dev.ftb.mods:ftb-chunks-neoforge:${prop("deps.ftb_chunks")}")
            "opac" -> runtimeOnly("maven.modrinth:open-parties-and-claims:${prop("deps.opac")}")
            // Flan answers the same NeoForge events as the others. Only where OPAC can't be tried: its 26.3 build needs a
            // config type NeoForge 26.3 dropped in a later beta (SERVER), and stops the game at startup (2026-10-02).
            "flan" -> runCatching { prop("deps.flan") }.getOrNull()?.let { runtimeOnly("maven.modrinth:flan:$it") }
            else -> throw GradleException("-Pclaims=ftb, -Pclaims=opac or -Pclaims=flan, not '$claims'")
        }
    }
}

neoForge {
    version = prop("deps.neoforge")
    // Parchment's parameter names, for versions up to 1.21.11; 26.1 on comes with Mojang's own.
    if (sc.current.parsed < "26.1") parchment {
        minecraftVersion = prop("deps.parchment_minecraft")
        mappingsVersion = prop("deps.parchment")
    }
    runs {
        register("client") {
            client()
            // A fixed name that reads well in screenshots (the Fabric client is "Alex"). Without it NeoForge uses "Dev".
            // A second player, in a folder of its own: gradlew :1.21.1-neoforge:runClient -Pplayer=Alex.
            val player = findProperty("player") as String? ?: "MagiMigi"
            gameDirectory = runFolder.resolve(if (player == "MagiMigi") "client" else "client-${player.lowercase()}")
            programArguments.addAll("--username", player)
            // The VR panel view without a headset, the mouse as the laser: gradlew :1.21.1-neoforge:runClient -PvrTest.
            if (hasProperty("vrTest")) systemProperty("desktopscreens.vrPanelTest", "true")
        }
        // Start it with tools/server.cmd, which sets it up for the dev client first.
        register("server") {
            server()
            gameDirectory = runFolder.resolve("server")
        }
    }
    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

tasks.processResources {
    val expandProps = mapOf(
        "version" to project.version.toString(),
        "mod_id" to modId,
        "mod_name" to modName,
        "mod_author" to modAuthor,
        "license" to license,
        "description" to project.description,
        "minecraft_version_range" to prop("mod.minecraft_range"),
        "neoforge_version_range" to prop("deps.neoforge_range"),
        "neoforge_loader_version_range" to prop("deps.neoforge_loader_range"),
        "pack_format" to prop("mod.pack_format"),
        "java_version" to javaVersion,
    )
    inputs.properties(expandProps)
    filesMatching(listOf("pack.mcmeta", "META-INF/neoforge.mods.toml", "*.mixins.json")) {
        expand(expandProps)
    }
}

tasks.jar {
    from(rootProject.file("LICENSE")) {
        rename { "${it}_$modId" }
    }
    manifest.attributes(
        "Specification-Title" to modName,
        "Specification-Vendor" to modAuthor,
        "Specification-Version" to project.version,
        "Implementation-Title" to loader,
        "Implementation-Version" to project.version,
        "Implementation-Vendor" to modAuthor,
        "Built-On-Minecraft" to minecraft,
    )
}
