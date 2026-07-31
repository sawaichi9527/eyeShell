plugins {
    kotlin("jvm") version "2.4.10"
    `java-library`
}

layout.buildDirectory = rootProject.layout.buildDirectory.dir("jediterm/core")

sourceSets {
    main {
        java.srcDir(rootProject.file("third-party/jediterm/core/src"))
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.jetbrains:annotations:24.0.1")
}
