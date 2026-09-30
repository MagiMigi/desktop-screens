// Builds the Fabric jar of one Minecraft version (versions/<version>-fabric) from src/ and core/.
plugins {
    id("net.fabricmc.fabric-loom-remap")
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

// src/ holds every loader's code; the other loaders' packages and files stay out of this jar.
sourceSets.main {
    java.exclude("com/desktopscreens/neoforge/**")
    resources.exclude("META-INF/neoforge.mods.toml")
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
    mappings(loom.layered {
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
}

tasks.compileJava {
    dependsOn(coreJava)
    source(coreJava)
}

// A land-claim mod in our own test game, to check that the Screen Builder asks it (see LandClaimsFabric):
//   gradlew :1.21.1-fabric:runClient -Pclaims=ftb    FTB Chunks, with FTB Teams, FTB Library and Architectury
//   gradlew :1.21.1-fabric:runClient -Pclaims=opac   Open Parties and Claims, with Forge Config API Port
//   gradlew :1.21.1-fabric:runClient -Pclaims=flan   Flan (asked through the Common Protection API it brings)
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

loom {
    runs {
        named("client") {
            client()
            displayName = "Fabric Client"
            generateRunConfig = true
            runDirectory = runFolder.resolve("client")
            // A fixed name (the NeoForge client is "MagiMigi"): otherwise it's a new random one each launch, which has to
            // be typed into the share list again and loses the player's things on the test server.
            programArguments.addAll("--username", "Alex")
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
    )
    inputs.properties(expandProps)
    filesMatching(listOf("pack.mcmeta", "fabric.mod.json")) {
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
