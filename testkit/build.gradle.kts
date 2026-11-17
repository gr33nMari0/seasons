// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import groovy.json.JsonSlurper
import java.time.Duration
import xyz.jpenilla.runpaper.task.RunServer

description = "Seasons testkit: the scenario suite (Mineflayer bots plus admin commands) and the test plugin that drives it"

plugins {
    alias(libs.plugins.run.paper)
}

base.archivesName = "seasons-testkit"

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(project(":adapters:paper", "shadowRuntimeElements"))
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform { excludeTags("scenario") }
}

// The Mineflayer client version the bots speak. Mineflayer trails the newest drop, so
// ViaVersion and ViaBackwards on the test server translate (docs/adr/0001).
val botClientVersion = "26.1"
val adminBot = "SeasonsAdmin"
val minecraftVersions = providers.gradleProperty("minecraftVersions").get().split(",").map { it.trim() }
val botsDir = layout.projectDirectory.dir("bots")

val installBots = tasks.register<Exec>("installBots") {
    description = "Installs the Mineflayer bots from the committed lockfile."
    workingDir(botsDir)
    commandLine("npm", "ci", "--no-audit", "--no-fund")
    inputs.file(botsDir.file("package-lock.json"))
    outputs.dir(botsDir.dir("node_modules"))
}

val scenarioOutputs = minecraftVersions.map { version ->
    val runDir = layout.buildDirectory.dir("scenario/$version")
    val outDir = layout.buildDirectory.dir("scenario-results/$version")
    val task = tasks.register<RunServer>("scenarioServer_${version.replace('.', '_')}") {
        group = "verification"
        description = "Runs the scenario suite on a Paper $version server."
        dependsOn(installBots)
        minecraftVersion(version)
        runDirectory(runDir.get().asFile)
        pluginJars.from(
            project(":adapters:paper").tasks.named("shadowJar"),
            project(":web").tasks.named("shadowJar"),
            tasks.jar,
        )
        downloadPlugins {
            modrinth("viaversion", libs.versions.viaversion.get())
            modrinth("viabackwards", libs.versions.viabackwards.get())
        }
        jvmArgs(
            "-Dcom.mojang.eula.agree=true",
            "-Dseasons.scenario.out=${outDir.get().asFile}",
            "-Dseasons.scenario.admin=$adminBot",
            "-Dseasons.scenario.clientVersion=$botClientVersion",
            "-Dseasons.scenario.node=${providers.environmentVariable("NODE").getOrElse("node")}",
            "-Dseasons.scenario.runner=${botsDir.file("run.mjs").asFile}",
        )
        args("--nogui")
        timeout = Duration.ofMinutes(20)
        outputs.dir(outDir)
        outputs.upToDateWhen { false }

        doFirst {
            val run = runDir.get().asFile
            // Every run starts from a fresh world; the downloaded jars and libraries are kept.
            run.listFiles { f -> f.name.startsWith("world") }?.forEach { it.deleteRecursively() }
            outDir.get().asFile.deleteRecursively()
            run.mkdirs()
            run.resolve("server.properties").writeText(
                """
                # Written by the scenario task; never ships. Bots join in offline mode.
                online-mode=false
                enforce-secure-profile=false
                white-list=false
                server-ip=127.0.0.1
                server-port=25599
                level-type=minecraft:flat
                generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}
                generate-structures=false
                spawn-protection=0
                view-distance=4
                simulation-distance=4
                """.trimIndent() + "\n",
            )
        }
        doLast {
            val result = outDir.get().file("result.json").asFile
            check(result.exists()) { "The Paper $version scenario server stopped without a result" }
            @Suppress("UNCHECKED_CAST")
            val json = JsonSlurper().parse(result) as Map<String, Any?>
            check(json["passed"] == true) { "Scenarios failed on Paper $version: $json (see the server log above)" }
        }
    }
    task to outDir
}

val nbtNeutralityTest = tasks.register<Test>("nbtNeutralityTest") {
    group = "verification"
    description = "Opens the item samples the scenario servers wrote with a plain NBT reader."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("scenario") }
    dependsOn(scenarioOutputs.map { it.first })
    systemProperty(
        "seasons.nbtSamples",
        scenarioOutputs.joinToString(File.pathSeparator) { it.second.get().dir("nbt").asFile.path },
    )
    outputs.upToDateWhen { false }
}

tasks.register("scenarioTest") {
    group = "verification"
    description = "Runs the acceptance scenarios on every supported version, then the NBT neutrality test."
    dependsOn(scenarioOutputs.map { it.first }, nbtNeutralityTest)
}
