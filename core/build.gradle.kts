// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
description = "Seasons core: all season logic, plain Java with no Minecraft or platform imports"

base.archivesName = "seasons-core"

dependencies {
    api(project(":api"))
    compileOnlyApi(libs.slf4j.api)
    testImplementation(libs.slf4j.api)
    implementation(libs.configurate.yaml)
}
