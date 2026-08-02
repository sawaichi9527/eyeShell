plugins {
    kotlin("jvm") version "2.4.10"
    application
}

val isWindowsBuild = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val sqliteNativeClassifier = if (isWindowsBuild) {
    "natives-windows"
} else {
    "natives-linux"
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
    implementation("com.google.re2j:re2j:1.8")
    compileOnly("net.java.dev.jna:jna:5.19.1")
    compileOnly("de.swiesend:secret-service:1.8.1-jdk17")
    if (isWindowsBuild) {
        runtimeOnly("net.java.dev.jna:jna:5.19.1")
    } else {
        runtimeOnly("de.swiesend:secret-service:1.8.1-jdk17")
    }
    implementation("org.apache.sshd:sshd-core:2.19.0")
    implementation("org.xerial:sqlite-jdbc:3.53.2.1") {
        artifact { classifier = "without-natives" }
    }
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.2.1") {
        artifact { classifier = sqliteNativeClassifier }
    }
    implementation(project(":jediterm-core"))
    implementation(project(":jediterm-ui"))
    runtimeOnly("org.slf4j:slf4j-jdk14:2.0.9")

    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("net.java.dev.jna:jna:5.19.1")
    testRuntimeOnly("de.swiesend:secret-service:1.8.1-jdk17")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
