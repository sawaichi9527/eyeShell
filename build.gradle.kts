plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "io.github.sawaichi9527.eyeshell"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

application {
    mainClass.set("io.github.sawaichi9527.eyeshell.MainKt")
}

dependencies {
    implementation("com.formdev:flatlaf:3.7.2")
    implementation(project(":jediterm-core"))
    implementation(project(":jediterm-ui"))
    runtimeOnly("org.slf4j:slf4j-jdk14:2.0.9")

    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
