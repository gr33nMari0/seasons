// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import groovy.json.JsonSlurper
import java.time.Duration
import org.gradle.kotlin.dsl.support.serviceOf
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

// Each scenario file runs on its own freshly wiped server, because scenarios change persistent
// state. A file can ask for several sessions with a header line "// @sessions <n>": the server
// is stopped after each one and started again on the same world for the next.
data class Scenario(val name: String, val sessions: Int)

val sessionsHeader = Regex("""^// @sessions (\d+)$""", RegexOption.MULTILINE)
val scenarios =
    botsDir.dir("scenarios").asFile
        .listFiles { f -> f.name.endsWith(".mjs") }!!
        .sortedBy { it.name }
        .map { f ->
            val sessions = sessionsHeader.find(f.readText())?.groupValues?.get(1)?.toInt() ?: 1
            Scenario(f.nameWithoutExtension, sessions)
        }

// Webhook URLs must be https, in tests too. The scenario servers trust a self-signed certificate
// made here, and the bot runner serves the webhook receiver with it.
val tlsDir = layout.buildDirectory.dir("scenario-tls")
val tlsPassword = "changeit"
val scenarioTls = tasks.register("scenarioTls") {
    description = "Makes the self-signed webhook certificate and a trust store that includes it."
    val launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val execs = serviceOf<ExecOperations>()
    outputs.dir(tlsDir)
    doLast {
        val home = launcher.get().metadata.installationPath.asFile
        val keytool = home.resolve("bin/keytool").path
        val dir = tlsDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        val keyStore = dir.resolve("webhook.p12").path
        val cert = dir.resolve("webhook.crt").path
        val trustStore = dir.resolve("truststore.p12")
        execs.exec {
            commandLine(
                keytool, "-genkeypair", "-alias", "webhook", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "30", "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1,dns:localhost",
                "-keystore", keyStore, "-storetype", "PKCS12", "-storepass", tlsPassword,
            )
        }
        execs.exec {
            commandLine(keytool, "-exportcert", "-alias", "webhook", "-keystore", keyStore, "-storepass", tlsPassword, "-file", cert)
        }
        // Start from the JDK's own trust store, so the server's other https calls still work.
        home.resolve("lib/security/cacerts").copyTo(trustStore)
        execs.exec {
            commandLine(
                keytool, "-importcert", "-noprompt", "-alias", "seasons-webhook", "-file", cert,
                "-keystore", trustStore.path, "-storepass", tlsPassword,
            )
        }
    }
}

val scenarioServers = minecraftVersions.map { version ->
    val runDir = layout.buildDirectory.dir("scenario/$version")
    val resultsDir = layout.buildDirectory.dir("scenario-results/$version")
    val nbtDir = resultsDir.map { it.dir("nbt") }
    val tasksForVersion = scenarios.flatMap { scenario ->
        (1..scenario.sessions).map { session ->
            val outDir = resultsDir.map { it.dir("${scenario.name}/$session") }
            val taskName = "scenarioServer_${version.replace('.', '_')}_${scenario.name}_$session"
            tasks.register<RunServer>(taskName) {
                group = "verification"
                description = "Runs session $session of scenario ${scenario.name} on a Paper $version server."
                dependsOn(installBots, scenarioTls)
                if (session > 1) {
                    dependsOn("scenarioServer_${version.replace('.', '_')}_${scenario.name}_${session - 1}")
                }
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
                val tls = tlsDir.get().asFile
                jvmArgs(
                    "-Dcom.mojang.eula.agree=true",
                    "-Djavax.net.ssl.trustStore=${tls.resolve("truststore.p12")}",
                    "-Djavax.net.ssl.trustStorePassword=$tlsPassword",
                    // Turns on Seasons' scenario hooks; never set on a real server (docs/adr/0003).
                    "-Dseasons.scenario=true",
                    "-Dseasons.scenario.out=${outDir.get().asFile}",
                    "-Dseasons.scenario.nbt=${nbtDir.get().asFile}",
                    "-Dseasons.scenario.name=${scenario.name}",
                    "-Dseasons.scenario.session=$session",
                    "-Dseasons.scenario.tls=${tls.resolve("webhook.p12")}",
                    "-Dseasons.scenario.admin=$adminBot",
                    "-Dseasons.scenario.clientVersion=$botClientVersion",
                    "-Dseasons.scenario.node=${providers.environmentVariable("NODE").getOrElse("node")}",
                    "-Dseasons.scenario.runner=${botsDir.file("run.mjs").asFile}",
                )
                args("--nogui")
                timeout = Duration.ofMinutes(10)
                outputs.dir(outDir)
                outputs.upToDateWhen { false }

                doFirst {
                    val run = runDir.get().asFile
                    outDir.get().asFile.deleteRecursively()
                    run.mkdirs()
                    if (session == 1) {
                        // A fresh world and fresh Seasons files; the downloaded jars and libraries are kept.
                        run.listFiles { f -> f.name.startsWith("world") }?.forEach { it.deleteRecursively() }
                        run.resolve("plugins/Seasons").deleteRecursively()
                    }
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
                    val halted = outDir.get().file("halted.json").asFile
                    if (!result.exists() && halted.exists()) {
                        // A crash scenario halted the server on purpose. The bot runner outlives it
                        // and writes its own results once the session's checks have run.
                        val results = outDir.get().file("results.json").asFile
                        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
                        while (!results.exists() && System.nanoTime() < deadline) {
                            Thread.sleep(500)
                        }
                        check(results.exists()) {
                            "Scenario ${scenario.name} session $session halted, but the runner wrote no results"
                        }
                        @Suppress("UNCHECKED_CAST")
                        val runs = (JsonSlurper().parse(results) as Map<String, Any?>)["results"] as List<Map<String, Any?>>
                        check(runs.isNotEmpty() && runs.all { it["passed"] == true }) {
                            "Scenario ${scenario.name} session $session failed after the halt on Paper $version: $runs"
                        }
                        return@doLast
                    }
                    check(result.exists()) {
                        "Scenario ${scenario.name} session $session on Paper $version stopped without a result"
                    }
                    @Suppress("UNCHECKED_CAST")
                    val json = JsonSlurper().parse(result) as Map<String, Any?>
                    check(json["passed"] == true) {
                        "Scenario ${scenario.name} session $session failed on Paper $version: $json (see the server log above)"
                    }
                }
            }
        }
    }
    // Scenarios share one run directory per version, so they run one after another.
    tasksForVersion.zipWithNext { earlier, later -> later.configure { mustRunAfter(earlier) } }
    Triple(version, tasksForVersion, nbtDir)
}

val nbtNeutralityTest = tasks.register<Test>("nbtNeutralityTest") {
    group = "verification"
    description = "Opens the item samples the scenario servers wrote with a plain NBT reader."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("scenario") }
    dependsOn(scenarioServers.flatMap { it.second })
    systemProperty(
        "seasons.nbtSamples",
        scenarioServers.joinToString(File.pathSeparator) { it.third.get().asFile.path },
    )
    outputs.upToDateWhen { false }
}

tasks.register("scenarioTest") {
    group = "verification"
    description = "Runs the acceptance scenarios on every supported version, then the NBT neutrality test."
    dependsOn(scenarioServers.flatMap { it.second }, nbtNeutralityTest)
}
