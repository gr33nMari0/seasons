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
