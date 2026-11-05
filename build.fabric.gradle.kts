// Builds the Fabric jar of one Minecraft version (versions/<version>-fabric) from src/ and core/.
plugins {
    // Fabric Loom: the remapping kind up to 1.21.11, the plain one from 26.1 on (Minecraft ships with Mojang's names).
    id("dev.kikugie.loom-back-compat")
}

val loader = "fabric"
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
    java.exclude("com/desktopscreens/neoforge/**", "com/desktopscreens/forge/**")
    resources.exclude("META-INF/neoforge.mods.toml", "META-INF/mods.toml")
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
    exclusiveContent {
        forRepository { maven("https://maven.terraformersmc.com/releases/") { name = "TerraformersMC" } }
        filter { includeGroup("com.terraformersmc") }
    }
    // Land-claim mods (see LandClaimsFabric) and what they need.
    exclusiveContent {
        forRepository { maven("https://maven.nucleoid.xyz/") { name = "Nucleoid" } }
        filter { includeGroup("eu.pb4") }
    }
    exclusiveContent {
        forRepository { maven("https://maven.architectury.dev") { name = "Architectury" } }
        filter { includeGroup("dev.architectury") }
    }
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
    exclusiveContent {
        forRepository { maven("https://maven.ftb.dev/releases") { name = "FTB" } }
        filter { includeGroup("dev.ftb.mods") }
    }
}

dependencies {
    coreJava(project(path = ":core", configuration = "coreJava"))

    minecraft("com.mojang:minecraft:$minecraft")
    // Obfuscated versions get Mojang's names (and Parchment's parameter names) mapped on; 26.1 on has them already.
    if (sc.current.parsed < "26.1") mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-${prop("deps.parchment_minecraft")}:${prop("deps.parchment")}@zip")
    })
    modImplementation("net.fabricmc:fabric-loader:${prop("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${prop("deps.fabric_api")}")
    // Optional for players: we only compile against Mod Menu's API, and add it to our own test client.
    modCompileOnly("com.terraformersmc:modmenu:${prop("deps.modmenu")}") { isTransitive = false }
    modLocalRuntime("com.terraformersmc:modmenu:${prop("deps.modmenu")}") { isTransitive = false }
    // Land-claim mods the Screen Builder asks when they're installed (LandClaimsFabric). The Common Protection API
    // (Patbox, MIT, 8 KB) goes inside our jar, as its README suggests: Flan answers through it only when some mod
    // brought it, and doesn't bring it itself (checked in its jar, 2026-09-28); a claim mod with a newer one wins.
    modImplementation("eu.pb4:common-protection-api:${prop("deps.common_protection")}")
    include("eu.pb4:common-protection-api:${prop("deps.common_protection")}")
    // Only compiled against: players get these with their claim mod.
    modCompileOnly("dev.architectury:architectury-fabric:${prop("deps.architectury")}") { isTransitive = false }
    modCompileOnly("maven.modrinth:open-parties-and-claims:${prop("deps.opac")}") { isTransitive = false }
    // FTB Chunks stopped using Architectury in 26.1; we ask its own API before placing (ClaimMods.FtbChunks).
    if (sc.current.parsed >= "26.1") {
        modCompileOnly("dev.ftb.mods:ftb-chunks-fabric:${prop("deps.ftb_chunks")}") { isTransitive = false }
        modCompileOnly("dev.ftb.mods:ftb-library-fabric:${prop("deps.ftb_library")}") { isTransitive = false }
    }
}

tasks.compileJava {
    dependsOn(coreJava)
    source(coreJava)
}

// A land-claim mod in our own test game, to check that the Screen Builder asks it (see LandClaimsFabric):
//   gradlew :1.21.1-fabric:runClient -Pclaims=ftb    FTB Chunks, with FTB Teams, FTB Library and Architectury
//   gradlew :1.21.1-fabric:runClient -Pclaims=opac   Open Parties and Claims, with Forge Config API Port
//   gradlew :1.21.1-fabric:runClient -Pclaims=flan   Flan (asked through the Common Protection API in our jar)
// Only then, and never in the mod's jar. Loom leaves out the jars mods carry inside (it drops their "jars" list when it
// remaps them for the test game), so those are added by hand where a mod needs one to start: without night-config,
// Forge Config API Port crashed Open Parties and Claims at launch (2026-09-28). Flan's lingua_bib, its translations,
// isn't on any public Maven we use: its messages show as keys ("flan.claimCreateSuccess") in our test game.
val claims = findProperty("claims") as String?
if (claims != null) {
    dependencies {
        when (claims) {
            "ftb" -> modLocalRuntime("dev.ftb.mods:ftb-chunks-fabric:${prop("deps.ftb_chunks")}") {
                exclude(group = "net.fabricmc")
            }
            "opac" -> {
                modLocalRuntime("maven.modrinth:open-parties-and-claims:${prop("deps.opac")}") { isTransitive = false }
                modLocalRuntime("maven.modrinth:forge-config-api-port:${prop("deps.forge_config_api_port")}") { isTransitive = false }
                localRuntime("com.electronwill.night-config:core:3.8.0")
                localRuntime("com.electronwill.night-config:toml:3.8.0")
            }
            "flan" -> {
                modLocalRuntime("maven.modrinth:flan:${prop("deps.flan")}") { isTransitive = false }
                localRuntime("org.yaml:snakeyaml:2.4")
            }
            else -> throw GradleException("-Pclaims=ftb, -Pclaims=opac or -Pclaims=flan, not '$claims'")
        }
    }
}

// Iris and Sodium in our own test game, to see how screens look with a shader pack (see IrisPrograms), where a version
// lists them (deps.iris, deps.sodium). The shader pack goes in runs/<version>-fabric/client/shaderpacks.
//   gradlew :1.20.1-fabric:runClient -Pshaders=iris
// Only then, and never in the mod's jar. As above, Loom leaves out the jars mods carry inside: Iris's libraries are
// added by hand (glsl-transformer, ANTLR, jcpp; checked in Iris 1.7.6's jar, 2026-11-05). The Fabric API parts both
// carry are in our test game already.
// -Pshaders=sodium: Sodium alone, to tell its doing from Iris's.
val shaders = findProperty("shaders") as String?
if (shaders != null && shaders != "iris" && shaders != "sodium") throw GradleException("-Pshaders=iris or -Pshaders=sodium, not '$shaders'")
// Every version's build reads -Pshaders; the ones that list no Iris skip it, as -Pclaims=flan does.
if (shaders != null) runCatching { prop("deps.iris") }.getOrNull()?.let { iris ->
    dependencies {
        modLocalRuntime("maven.modrinth:sodium:${prop("deps.sodium")}") { isTransitive = false }
        if (shaders == "iris") {
            modLocalRuntime("maven.modrinth:iris:$iris") { isTransitive = false }
            localRuntime("io.github.douira:glsl-transformer:2.0.1")
            localRuntime("org.antlr:antlr4-runtime:4.13.1")
            localRuntime("org.anarres:jcpp:1.4.14")
        }
    }
}

loom {
    runs {
        named("client") {
            client()
            displayName = "Fabric Client"
            generateRunConfig = true
            // A fixed name (the NeoForge client is "MagiMigi"): otherwise it's a new random one each launch, which has to
            // be typed into the share list again and loses the player's things on the test server. A second Fabric
            // player, in a folder of its own: gradlew :26.1.2-fabric:runClient -Pplayer=Steve. From 26.1 on, Fabric API
            // lets only players with Fabric onto a Fabric server that has our blocks, so two Fabric games test sharing there.
            val player = findProperty("player") as String? ?: "Alex"
            runDirectory = runFolder.resolve(if (player == "Alex") "client" else "client-${player.lowercase()}")
            programArguments.addAll("--username", player)
            // The VR panel view without a headset, the mouse as the laser: gradlew :1.21.1-fabric:runClient -PvrTest.
            if (hasProperty("vrTest")) property("desktopscreens.vrPanelTest", "true")
            // Sodium 0.5 (1.20.1) wants LWJGL 3.3.1 exactly and stops the game otherwise; Loom's test game has 3.3.2
            // (2026-11-05). Players' launchers bring 3.3.1. Its own switch for that check (BugChecks, issue 2561):
            if (shaders != null) property("sodium.checks.issue2561", "false")
        }
        // Start it with tools/server.cmd, which sets it up for the dev client first.
        named("server") {
            server()
            displayName = "Fabric Server"
            generateRunConfig = true
            runDirectory = runFolder.resolve("server")
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
        "minecraft_version" to minecraft,
        "fabric_loader_min" to prop("deps.fabric_loader_min"),
        "java_version" to javaVersion,
        "pack_format" to prop("mod.pack_format"),
    )
    inputs.properties(expandProps)
    filesMatching(listOf("pack.mcmeta", "fabric.mod.json", "*.mixins.json")) {
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
