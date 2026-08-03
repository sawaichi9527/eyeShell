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

val jpackageTool = "${System.getProperty("java.home")}/bin/jpackage"
val packageVersion = version.toString().substringBefore('-')

val preparePackageInput by tasks.registering(Sync::class) {
    from(configurations.runtimeClasspath)
    from(tasks.jar)
    into(layout.buildDirectory.dir("package/input"))
}

fun jpackageArgs(
    jpackageTool: String,
    packageVersion: String,
    type: String,
    inputDir: File,
    destDir: File,
    mainJar: String,
): List<String> = listOf(
    jpackageTool,
    "--type", type,
    "--input", inputDir.absolutePath,
    "--dest", destDir.absolutePath,
    "--name", "eyeShell",
    "--app-version", packageVersion,
    "--main-jar", mainJar,
    "--main-class", "io.github.sawaichi9527.eyeshell.MainKt",
    "--vendor", "sawaichi9527",
    "--java-options", "-Dfile.encoding=UTF-8",
)

tasks.register<Exec>("jpackageAppImage") {
    dependsOn(preparePackageInput)
    val inputDir = layout.buildDirectory.dir("package/input").get().asFile
    val destDir = layout.buildDirectory.dir("package").get().asFile
    val mainJar = tasks.jar.get().archiveFileName.get()
    inputs.files(configurations.runtimeClasspath, tasks.jar)
    doFirst { destDir.resolve("eyeShell").deleteRecursively() }
    commandLine(jpackageArgs(jpackageTool, packageVersion, "app-image", inputDir, destDir, mainJar))
}

val cleanPackageMsi by tasks.registering(Delete::class) {
    delete(layout.buildDirectory.file("package/eyeShell-${packageVersion}.msi"))
}

val cleanPackageDeb by tasks.registering(Delete::class) {
    delete(layout.buildDirectory.file("package/eyeshell_${packageVersion}_amd64.deb"))
}

tasks.register<Exec>("jpackageInstaller") {
    dependsOn(preparePackageInput, if (isWindowsBuild) cleanPackageMsi else cleanPackageDeb)
    val inputDir = layout.buildDirectory.dir("package/input").get().asFile
    val destDir = layout.buildDirectory.dir("package").get().asFile
    val mainJar = tasks.jar.get().archiveFileName.get()
    inputs.files(configurations.runtimeClasspath, tasks.jar)
    if (isWindowsBuild) {
        commandLine(jpackageArgs(jpackageTool, packageVersion, "msi", inputDir, destDir, mainJar))
        outputs.files(layout.buildDirectory.file("package/eyeShell-${packageVersion}.msi"))
    } else {
        commandLine(
            jpackageArgs(jpackageTool, packageVersion, "deb", inputDir, destDir, mainJar) + listOf(
                "--linux-package-name", "eyeshell",
                "--linux-deb-maintainer", "sawaichi9527@users.noreply.github.com",
            ),
        )
        outputs.files(layout.buildDirectory.file("package/eyeshell_${packageVersion}_amd64.deb"))
    }
}

val cleanPackagePortable by tasks.registering(Delete::class) {
    delete(layout.buildDirectory.file("package/eyeShell-${packageVersion}.${if (isWindowsBuild) "zip" else "tar.gz"}"))
}

tasks.register<Exec>("jpackagePortable") {
    dependsOn("jpackageAppImage", cleanPackagePortable)
    val appImageDir = layout.buildDirectory.dir("package/eyeShell").get().asFile
    val portable = layout.buildDirectory.file("package/eyeShell-${packageVersion}.${if (isWindowsBuild) "zip" else "tar.gz"}")
    inputs.dir(appImageDir)
    outputs.file(portable)
    val archivePath = portable.get().asFile.absolutePath
    if (isWindowsBuild) {
        commandLine("powershell", "-NoProfile", "-Command",
            "Compress-Archive -Path '${appImageDir}\\*' -DestinationPath '$archivePath' -Force")
    } else {
        commandLine("tar", "-C", appImageDir.absolutePath, "-czf", archivePath, ".")
    }
}
