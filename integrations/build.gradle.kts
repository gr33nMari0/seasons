// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
description = "Seasons integrations: BlueMap and Discord, each enabled only when present"

base.archivesName = "seasons-integrations"

dependencies {
    implementation(project(":core"))
    compileOnly(libs.bluemap.api)
    testImplementation(libs.slf4j.api)
}
