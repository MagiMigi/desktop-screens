// Builds the Forge jar of one Minecraft version (versions/<version>-forge) from src/ and core/. Forge is for the
// versions before NeoForge took over (1.20.1 and older); its jars are remapped to Forge's names ("reobfuscated").
plugins {
    id("net.neoforged.moddev.legacyforge")
}

val loader = "forge"
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
    java.exclude("com/desktopscreens/fabric/**", "com/desktopscreens/neoforge/**")
    resources.exclude("fabric.mod.json", "desktopscreens.fabric.mixins.json", "META-INF/neoforge.mods.toml")
    for (folder in prop("mod.data").split(" ")) resources.srcDir(rootProject.file("src/data/$folder"))
    // Stand-ins for networking classes Minecraft has only from 1.20.5 on (see net/CustomPacketPayload).
    if (sc.current.parsed >= "1.20.5") java.exclude("com/desktopscreens/net/**")
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
    // Writes the refmap that points our mixins at Forge's names in the player's game.
    annotationProcessor("org.spongepowered:mixin:0.8.5:processor")
}

tasks.compileJava {
    dependsOn(coreJava)
    source(coreJava)
}

// A land-claim mod in our own test game, to check that the Screen Builder asks it (see LandClaims):
//   gradlew :1.20.1-forge:runClient -Pclaims=ftb    FTB Chunks, with FTB Teams and Architectury
//   gradlew :1.20.1-forge:runClient -Pclaims=opac   Open Parties and Claims
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
            // Legacy Forge's remapping leaves out what a mod needs (and the jars it carries inside): listed by hand.
            "ftb" -> {
                modRuntimeOnly("dev.ftb.mods:ftb-chunks-forge:${prop("deps.ftb_chunks")}")
                modRuntimeOnly("dev.ftb.mods:ftb-teams-forge:${prop("deps.ftb_teams")}")
                modRuntimeOnly("dev.ftb.mods:ftb-library-forge:${prop("deps.ftb_library")}")
                modRuntimeOnly("dev.architectury:architectury-forge:${prop("deps.architectury")}")
                runtimeOnly("io.github.llamalad7:mixinextras-forge:${prop("deps.mixinextras")}")
            }
            "opac" -> modRuntimeOnly("maven.modrinth:open-parties-and-claims:${prop("deps.opac")}")
            "flan" -> {}
            else -> throw GradleException("-Pclaims=ftb or -Pclaims=opac, not '$claims'")
        }
    }
}

legacyForge {
    version = prop("deps.forge")
    parchment {
        minecraftVersion = prop("deps.parchment_minecraft")
        mappingsVersion = prop("deps.parchment")
    }
    runs {
        register("client") {
            client()
            gameDirectory = runFolder.resolve("client")
            // A fixed name that reads well in screenshots, as on NeoForge. Without it Forge uses "Dev".
            programArguments.addAll("--username", "MagiMigi")
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

mixin {
    add(sourceSets.main.get(), "$modId.refmap.json")
    config("$modId.mixins.json")
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
        "forge_version_range" to prop("deps.forge_range"),
        "forge_loader_version_range" to prop("deps.forge_loader_range"),
        "pack_format" to prop("mod.pack_format"),
        "java_version" to javaVersion,
    )
    inputs.properties(expandProps)
    filesMatching(listOf("pack.mcmeta", "META-INF/mods.toml", "*.mixins.json")) {
        expand(expandProps)
    }
    // Forge runs the game with its own names, so the mixins need the refmap (the other loaders don't read one).
    filesMatching("$modId.mixins.json") {
        filter { line -> line.replace("\"package\":", "\"refmap\": \"$modId.refmap.json\",\n  \"package\":") }
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
        // Forge finds a mod jar's mixins only through this (the test game gets them from the mixin block above).
        "MixinConfigs" to "$modId.mixins.json",
    )
}
