// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.run.paper) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone) apply false
}

val spdxHeader = "// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0"

spotless {
    kotlinGradle {
        target("*.gradle.kts")
        licenseHeader(spdxHeader, "(import|plugins|rootProject|pluginManagement)")
    }
}

val catalog = libs

subprojects {
    group = "com.petepeterepeat.seasons"
    version = rootProject.version

    // Container projects such as :adapters hold no code.
    if (project.path == ":adapters") return@subprojects

    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "net.ltgt.errorprone")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(25)
    }

    dependencies {
        "compileOnly"(catalog.jspecify)
        "testCompileOnly"(catalog.jspecify)
        "errorprone"(catalog.errorprone.core)
        "errorprone"(catalog.nullaway)
        "testImplementation"(platform(catalog.junit.bom))
        "testImplementation"(catalog.junit.jupiter)
        "testImplementation"(catalog.assertj.core)
        "testRuntimeOnly"(catalog.junit.launcher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 25
        options.compilerArgs.addAll(listOf("-Xlint:deprecation,removal,unchecked", "-Werror", "-parameters"))
        options.errorprone {
            disableWarningsInGeneratedCode = true
            check("NullAway", if (name.contains("Test")) CheckSeverity.OFF else CheckSeverity.ERROR)
            option("NullAway:OnlyNullMarked", "true")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    // Every source file carries the SPDX header (see Licence in docs/spec.md).
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat()
            licenseHeader(spdxHeader, "(package|import|module|@)")
        }
        kotlinGradle {
            target("*.gradle.kts")
            licenseHeader(spdxHeader, "(import|plugins|dependencies|tasks|val|description)")
        }
    }
}
