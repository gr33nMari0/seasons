// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
description = "Seasons core: all season logic, plain Java with no Minecraft or platform imports"

base.archivesName = "seasons-core"

dependencies {
    api(project(":api"))
    compileOnlyApi(libs.slf4j.api)
    testImplementation(libs.slf4j.api)
    implementation(libs.configurate.yaml)
    implementation(libs.configurate.gson)
    implementation(libs.snakeyaml)
    implementation(libs.gson)
}

// The default cell templates (questions 238) ship in the jar under templates/.
tasks.processResources {
    from(rootProject.file("templates")) {
        into("templates")
        include("*.nbt")
    }
}

// Writes the default templates into templates/ from their code in the tests (DefaultTemplates).
tasks.register<JavaExec>("writeDefaultTemplates") {
    description = "Writes the default cell templates into templates/"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.petepeterepeat.seasons.core.DefaultTemplates"
    args(rootProject.file("templates").path)
}

// Writes the bundled config.yml and secrets.template.yml, comments and all, and docs/config.md
// from each setting's @Comment in the code (ConfigReference, in the tests). ConfigReferenceTest
// fails until they match.
tasks.register<JavaExec>("configReference") {
    group = "documentation"
    description = "Writes the bundled config.yml, secrets.template.yml and docs/config.md from the code"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.petepeterepeat.seasons.core.config.ConfigReference"
    args(
        file("src/main/resources/com/petepeterepeat/seasons/core").path,
        rootProject.file("docs/config.md").path,
    )
}
