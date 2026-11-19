// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
rootProject.name = "seasons"

pluginManagement {
    repositories {
        gradlePluginPortal()
        // The portal answers for plugins' own dependencies by proxying Maven Central, and that
        // proxy sometimes answers 404 (a CI shard failed so); Central itself is the fallback.
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
        maven("https://repo.bluecolored.de/releases") {
            name = "bluecolored"
            content { includeGroup("de.bluecolored") }
        }
    }
}

include(":api", ":core", ":integrations", ":adapters:paper", ":web", ":testkit")
