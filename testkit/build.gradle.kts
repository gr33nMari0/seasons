// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.lang.management.ManagementFactory
import kotlin.random.Random
import org.gradle.internal.logging.progress.ProgressLoggerFactory
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.workers.WorkerExecutor
import seasons.scenarios.ScenarioShard
import xyz.jpenilla.runpaper.task.RunServer
import xyz.jpenilla.runtask.service.DownloadsAPIService

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

// The scenarios, from their files. Header lines: "// @sessions <n>" restarts the server between
// sessions on the same world; "// @isolated <reason>" gives the scenario a fresh server of its own
// instead of sharing one (docs/adr/0003).
data class Scenario(val name: String, val sessions: Int, val isolated: Boolean)

val sessionsHeader = Regex("""^// @sessions (\d+)$""", RegexOption.MULTILINE)
val isolatedHeader = Regex("""^// @isolated \S.*$""", RegexOption.MULTILINE)
val allScenarios =
    botsDir.dir("scenarios").asFile
        .listFiles { f -> f.name.endsWith(".mjs") }!!
        .sortedBy { it.name }
        .map { f ->
            val text = f.readText()
            val sessions = sessionsHeader.find(text)?.groupValues?.get(1)?.toInt() ?: 1
            Scenario(f.nameWithoutExtension, sessions, isolatedHeader.containsMatchIn(text))
        }

// -Pscenarios=a33-homecoming,m3-* runs just those; * matches anything.
val scenarioFilter = providers.gradleProperty("scenarios").orNull
val selectedScenarios =
    if (scenarioFilter == null) {
        allScenarios
    } else {
        val patterns = scenarioFilter.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .map { Regex(it.split("*").joinToString(".*") { part -> Regex.escape(part) }) }
        allScenarios.filter { s -> patterns.any { it.matches(s.name) } }
    }

// -PscenarioIsolation=fresh runs every scenario on a freshly wiped server of its own, as before
// shared servers; the nightly workflow runs it. The default shares one server per shard.
val isolation = providers.gradleProperty("scenarioIsolation").getOrElse("shared")
check(isolation == "shared" || isolation == "fresh") { "-PscenarioIsolation is shared or fresh, not $isolation" }

// The order within each shard is shuffled; -PscenarioSeed=<n> repeats a run's order.
val scenarioSeed = providers.gradleProperty("scenarioSeed").map { it.toLong() }.getOrElse(Random.nextLong(1, 1_000_000))

// How many servers run side by side: -PscenarioShards=<n>, or as many as this machine's cores and
// memory allow (two cores and 2 GB each, leaving 4 GB for everything else). -PscenarioShard=<k>
// runs only shard k of n, as each CI shard job does.
val shardHeap = "1536m"
val autoShards = run {
    val cores = Runtime.getRuntime().availableProcessors()
    val memoryGb = (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean)
        .totalMemorySize / (1L shl 30)
    maxOf(1, minOf(cores / 2, ((memoryGb - 4) / 2).toInt()))
}
val shardCount = providers.gradleProperty("scenarioShards").map { it.toInt() }.getOrElse(autoShards)
    .coerceAtMost(maxOf(1, selectedScenarios.size))
val onlyShard = providers.gradleProperty("scenarioShard").map { it.toInt() }.orNull
check(onlyShard == null || onlyShard in 1..shardCount) { "-PscenarioShard must be between 1 and $shardCount" }

// Seconds each scenario took in a full run, all its sessions and resets included; refresh it with
// ./gradlew :testkit:updateScenarioTimings after a full run. Shards are filled longest first.
val timingsFile = layout.projectDirectory.file("scenario-timings.json")
val bootSeconds = 8.0

@Suppress("UNCHECKED_CAST")
val recordedSeconds: Map<String, Double> =
    timingsFile.asFile.takeIf { it.exists() }
        ?.let { (JsonSlurper().parse(it) as Map<String, Number>).mapValues { e -> e.value.toDouble() } }
        ?: emptyMap()

/** The scenarios of each shard, in the order it runs them. */
fun plan(scenarios: List<Scenario>, shards: Int, seed: Long): List<List<Scenario>> {
    val known = recordedSeconds.values.sorted()
    val median = if (known.isEmpty()) 10.0 else known[known.size / 2]
    fun cost(s: Scenario) =
        (recordedSeconds[s.name] ?: median) + if (s.isolated) bootSeconds else 0.0
    val bins = List(shards) { mutableListOf<Scenario>() }
    val loads = DoubleArray(shards)
    for (s in scenarios.sortedWith(compareByDescending<Scenario> { cost(it) }.thenBy { it.name })) {
        val i = loads.indices.minByOrNull { loads[it] }!!
        bins[i].add(s)
        loads[i] += cost(s)
    }
    return bins.mapIndexed { i, bin -> bin.shuffled(Random(seed + i)) }
}

val scenarioPaper = tasks.register<RunServer>("scenarioPaper") {
    group = "verification"
    description = "Runs a plain Paper server with the scenario suite's ViaVersion and ViaBackwards; the shards use its jars."
    minecraftVersion(minecraftVersions.last())
    runDirectory(layout.buildDirectory.dir("scenario/paper").get().asFile)
    downloadPlugins {
        modrinth("viaversion", libs.versions.viaversion.get())
        modrinth("viabackwards", libs.versions.viabackwards.get())
    }
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

val resultsRoot = layout.buildDirectory.dir("scenario-results")
val javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
val workers = serviceOf<WorkerExecutor>()

val scenarioRuns = minecraftVersions.map { version ->
    val v = version.replace('.', '_')
    val bundlerDir = layout.buildDirectory.dir("scenario/$version/bundler")
    tasks.register("scenarioRun_$v") {
        group = "verification"
        description = "Runs the acceptance scenarios on Paper $version, in shards side by side."
        dependsOn(installBots, scenarioTls)
        // -PscenarioJars=<dir> runs the jars a CI build job made (Seasons, Seasons Web and this
        // test plugin) instead of building them.
        val prebuilt = providers.gradleProperty("scenarioJars").orNull
        val pluginJars =
            if (prebuilt != null) {
                fileTree(rootProject.file(prebuilt)) { include("**/*.jar") }
            } else {
                files(
                    project(":adapters:paper").tasks.named("shadowJar"),
                    project(":web").tasks.named("shadowJar"),
                    tasks.jar,
                )
            }
        inputs.files(pluginJars)
        outputs.upToDateWhen { false }
        doLast {
            val progress = serviceOf<ProgressLoggerFactory>()
            val paperJar = DownloadsAPIService.paper(project).get()
                .resolveBuild(progress, version, DownloadsAPIService.Build.Latest)
            val template = scenarioPaper.get()
            val via = template.downloadPlugins.downloads.map { template.pluginDownloadService.get().resolvePlugin(progress, it) }
            val java = javaLauncher.get().executablePath.asFile.path
            // Paperclip unpacks Paper and its libraries once, into a folder every shard shares.
            val bundler = bundlerDir.get().asFile
            bundler.mkdirs()
            providers.exec {
                workingDir = bundler
                commandLine(java, "-DbundlerRepoDir=${bundler.path}", "-Dpaperclip.patchonly=true", "-jar", paperJar.toString())
            }.result.get().assertNormalExitValue()

            val shards = plan(selectedScenarios, shardCount, scenarioSeed)
            val running = shards.indices.map { it + 1 }.filter { onlyShard == null || it == onlyShard }
            logger.lifecycle(
                "Scenarios on Paper $version: ${selectedScenarios.size} in ${shards.size} shard(s), " +
                    "$isolation servers, seed $scenarioSeed (repeat with -PscenarioSeed=$scenarioSeed)",
            )
            val tls = tlsDir.get().asFile
            val out = resultsRoot.get().dir(version).asFile
            if (onlyShard == null) {
                out.deleteRecursively() // no shard of an earlier run is read as this one's
            }
            for (k in running) {
                logger.lifecycle("  shard $k: ${shards[k - 1].joinToString(" ") { it.name }}")
                workers.noIsolation().submit(ScenarioShard::class.java) {
                    shard.set(k)
                    this.java.set(java)
                    this.paperJar.set(paperJar.toFile())
                    this.pluginJars.from(pluginJars, via.map { it.toFile() })
                    runDirectory.set(layout.buildDirectory.dir("scenario/$version/shard-$k"))
                    outputDirectory.set(File(out, "shard-$k"))
                    serverPort.set(25700 + 10 * k)
                    webhookPort.set(25701 + 10 * k)
                    shared.set(isolation == "shared")
                    jvmArgs.set(
                        listOf(
                            "-Xmx$shardHeap",
                            "-Dcom.mojang.eula.agree=true",
                            "-Ddisable.watchdog=true",
                            "-Dfile.encoding=UTF-8",
                            "-DbundlerRepoDir=${bundler.path}",
                            "-Djavax.net.ssl.trustStore=${tls.resolve("truststore.p12")}",
                            "-Djavax.net.ssl.trustStorePassword=$tlsPassword",
                            // Turns on Seasons' scenario hooks; never set on a real server (docs/adr/0003).
                            "-Dseasons.scenario=true",
                            "-Dseasons.scenario.tls=${tls.resolve("webhook.p12")}",
                            "-Dseasons.scenario.admin=$adminBot",
                            "-Dseasons.scenario.clientVersion=$botClientVersion",
                            "-Dseasons.scenario.node=${providers.environmentVariable("NODE").getOrElse("node")}",
                            "-Dseasons.scenario.runner=${botsDir.file("run.mjs").asFile}",
                        ),
                    )
                    steps.set(
                        shards[k - 1].flatMap { s ->
                            (1..s.sessions).map { "${s.name}:$it:${s.sessions}:${if (s.isolated) 1 else 0}" }
                        },
                    )
                }
            }
            workers.await()
            @Suppress("UNCHECKED_CAST")
            val failed = running.flatMap { k ->
                val json = File(out, "shard-$k/shard.json")
                if (!json.exists()) return@flatMap listOf("shard $k wrote no results")
                ((JsonSlurper().parse(json) as Map<String, Any?>)["results"] as List<Map<String, Any?>>)
                    .filter { it["passed"] != true }
                    .map { "${it["name"]} session ${it["session"]} (shard $k): ${it["error"].toString().lineSequence().first()}" }
            }
            check(failed.isEmpty()) {
                "${failed.size} scenario session(s) failed on Paper $version (seed $scenarioSeed):\n" +
                    failed.joinToString("\n") { "  $it" } +
                    "\nServer logs: ${out}/shard-<k>/server.log"
            }
        }
        finalizedBy(scenarioTimings)
    }
}

// Boot, scenario, reset and shutdown time for the run, slowest scenario first, written to
// build/reports/scenario-timings.md. Boot runs from starting the JVM to the server having loaded;
// scenario is the bot runner's time for each session; reset the time to put the server back after
// it; shutdown from the runner finishing to the JVM having stopped.
val scenarioTimings: TaskProvider<Task> = tasks.register("scenarioTimings") {
    group = "verification"
    description = "Writes build/reports/scenario-timings.md from the last scenario run."
    val report = rootProject.layout.buildDirectory.file("reports/scenario-timings.md")
    outputs.file(report)
    outputs.upToDateWhen { false }
    doLast {
        val shards = shardFiles()
        fun n(v: Any?) = (v as Number?)?.toLong()
        fun sec(ms: Long?) = ms?.let { "%.1f".format(it / 1000.0) } ?: "–"
        val boots = shards.flatMap { (_, json) -> json.list("boots") }
        val results = shards.flatMap { (_, json) -> json.list("results") }
        val bootMs = boots.sumOf { b -> n(b["serverLoaded"])?.let { it - n(b["start"])!! } ?: 0 }
        val scenarioMs = results.sumOf { n(it["ms"]) ?: 0 }
        val resetMs = results.sumOf { n(it["resetMs"]) ?: 0 }
        val shutdownMs = boots.sumOf { b -> n(b["runnerEnd"])?.let { maxOf(0, n(b["end"])!! - it) } ?: 0 }
        val wall = if (boots.isEmpty()) 0 else boots.maxOf { n(it["end"])!! } - boots.minOf { n(it["start"])!! }
        val byScenario = results.groupBy { it["name"].toString() }
            .mapValues { (_, rs) -> rs.sumOf { (n(it["ms"]) ?: 0) + (n(it["resetMs"]) ?: 0) } }
            .entries.sortedByDescending { it.value }
        val text = buildString {
            appendLine("# Scenario timings")
            appendLine()
            appendLine("Written by `./gradlew scenarioTest` (task `scenarioTimings`). Seconds.")
            appendLine()
            appendLine("| Wall clock | Shards | Boots | Sessions | Boot | Scenario | Reset | Shutdown |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- | --- |")
            appendLine("| ${sec(wall)} | ${shards.size} | ${boots.size} | ${results.size} | ${sec(bootMs)} | ${sec(scenarioMs)} | ${sec(resetMs)} | ${sec(shutdownMs)} |")
            appendLine()
            appendLine("| Shard | Wall clock | Boots | Sessions |")
            appendLine("| --- | --- | --- | --- |")
            for ((name, json) in shards) {
                val bs = json.list("boots")
                val w = if (bs.isEmpty()) 0 else bs.maxOf { n(it["end"])!! } - bs.minOf { n(it["start"])!! }
                appendLine("| $name | ${sec(w)} | ${bs.size} | ${json.list("results").size} |")
            }
            appendLine()
            appendLine("| Scenario | Sessions | Scenario and reset |")
            appendLine("| --- | --- | --- |")
            for ((name, ms) in byScenario) {
                appendLine("| $name | ${results.count { it["name"] == name }} | ${sec(ms)} |")
            }
        }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(text)
        logger.lifecycle("Scenario timings: ${report.get().asFile}")
    }
}

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.list(key: String) = (this[key] as List<Map<String, Any?>>?) ?: emptyList()

/** Every shard.json under build/scenario-results, by shard folder. */
@Suppress("UNCHECKED_CAST")
fun shardFiles(): List<Pair<String, Map<String, Any?>>> =
    resultsRoot.get().asFile.walkTopDown()
        .filter { it.name == "shard.json" }
        .sortedBy { it.path }
        .map { "${it.parentFile.parentFile.name}/${it.parentFile.name}" to (JsonSlurper().parse(it) as Map<String, Any?>) }
        .toList()

tasks.register("updateScenarioTimings") {
    group = "verification"
    description = "Rewrites scenario-timings.json from the last full scenario run, for balancing shards."
    doLast {
        fun n(v: Any?) = (v as Number?)?.toLong() ?: 0
        val shards = shardFiles()
        // A later session costs a restart: the run's own average boot.
        val boots = shards.flatMap { (_, json) -> json.list("boots") }.filter { it["serverLoaded"] != null }
        val bootMs = if (boots.isEmpty()) bootSeconds * 1000 else boots.map { n(it["serverLoaded"]) - n(it["start"]) }.average()
        val seconds = sortedMapOf<String, Double>()
        for ((_, json) in shards) {
            for (r in json.list("results")) {
                val name = r["name"].toString()
                val restart = if (n(r["session"]) > 1) bootMs else 0.0
                seconds[name] = (seconds[name] ?: 0.0) + (n(r["ms"]) + n(r["resetMs"]) + restart) / 1000.0
            }
        }
        check(seconds.isNotEmpty()) { "No scenario run to take timings from" }
        timingsFile.asFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(seconds.mapValues { "%.1f".format(it.value).toDouble() })) + "\n")
        logger.lifecycle("Wrote ${timingsFile.asFile} for ${seconds.size} scenarios")
    }
}

val nbtNeutralityTest = tasks.register<Test>("nbtNeutralityTest") {
    group = "verification"
    description = "Opens the item samples and archives the scenario servers wrote with a plain NBT reader."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("scenario") }
    mustRunAfter(scenarioRuns)
    // Every shard's samples, including those downloaded from CI shard jobs into the same folders.
    inputs.files(resultsRoot).withPropertyName("samples").optional()
    doFirst {
        val dirs = resultsRoot.get().asFile.walkTopDown().filter { it.isDirectory && it.name == "nbt" }.toList()
        systemProperty("seasons.nbtSamples", dirs.joinToString(File.pathSeparator) { it.path })
    }
    outputs.upToDateWhen { false }
}

tasks.register("scenarioTest") {
    group = "verification"
    description = "Runs the acceptance scenarios on every supported version, then the NBT neutrality test."
    dependsOn(scenarioRuns)
    // The neutrality test needs the archives of the whole suite: a run of some scenarios or of one
    // shard skips it, and CI runs it once every shard has finished.
    if (scenarioFilter == null && onlyShard == null) {
        dependsOn(nbtNeutralityTest)
    }
}
