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

// src/ holds every loader's code; the other loaders' packages and files stay out of this jar.
sourceSets.main {
    java.exclude("com/desktopscreens/fabric/**")
    resources.exclude("fabric.mod.json", "desktopscreens.fabric.mixins.json")
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
            // Tried on Fabric only, for the Common Protection API; on NeoForge Flan answers the same events as the others.
            "flan" -> {}
            else -> throw GradleException("-Pclaims=ftb or -Pclaims=opac, not '$claims'")
        }
    }
}

neoForge {
    version = prop("deps.neoforge")
    parchment {
        minecraftVersion = prop("deps.parchment_minecraft")
        mappingsVersion = prop("deps.parchment")
    }
    runs {
        register("client") {
            client()
            gameDirectory = runFolder.resolve("client")
            // A fixed name that reads well in screenshots (the Fabric client is "Alex"). Without it NeoForge uses "Dev".
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
    )
    inputs.properties(expandProps)
    filesMatching(listOf("pack.mcmeta", "META-INF/neoforge.mods.toml")) {
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
