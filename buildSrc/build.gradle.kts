// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Build logic for the scenario suite (testkit/build.gradle.kts): the shard runner, in plain Java
// on the Gradle API, so it needs nothing downloaded.
plugins {
    java
}

dependencies {
    implementation(gradleApi())
}
