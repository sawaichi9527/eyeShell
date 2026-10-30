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
version = "0.1.4-SNAPSHOT"

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
    implementation("net.i2p.crypto:eddsa:0.3.0")
    compileOnly("net.java.dev.jna:jna:5.19.1")
    compileOnly("de.swiesend:secret-service:1.8.1-jdk17")
    if (isWindowsBuild) {
        runtimeOnly("net.java.dev.jna:jna:5.19.1")
    } else {
        runtimeOnly("de.swiesend:secret-service:1.8.1-jdk17")
    }
    implementation("org.apache.sshd:sshd-core:2.19.0")
    implementation("org.apache.sshd:sshd-sftp:2.19.0")
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

/**
 * Curated display data for the About page. Keys are `group:name`; the version always comes from the
 * resolved runtime classpath, so the list cannot drift from the packaged jars. An unknown module
 * falls back to its artifact name and points at `THIRD_PARTY_NOTICES.md`.
 */
val aboutLicenses: Map<String, List<String>> = mapOf(
    "org.jetbrains.kotlin:kotlin-stdlib" to listOf("Kotlin standard library", "Apache-2.0", "https://kotlinlang.org"),
    "org.jetbrains:annotations" to listOf("JetBrains annotations", "Apache-2.0", "https://github.com/JetBrains/java-annotations"),
    "com.formdev:flatlaf" to listOf("FlatLaf", "Apache-2.0", "https://github.com/JFormDesigner/FlatLaf"),
    "com.google.re2j:re2j" to listOf("RE2/J", "BSD-3-Clause", "https://github.com/google/re2j"),
    "org.apache.sshd:sshd-core" to listOf("Apache MINA SSHD core", "Apache-2.0", "https://mina.apache.org/sshd-project/"),
    "org.apache.sshd:sshd-common" to listOf("Apache MINA SSHD common", "Apache-2.0", "https://mina.apache.org/sshd-project/"),
    "org.apache.sshd:sshd-sftp" to listOf("Apache MINA SSHD SFTP", "Apache-2.0", "https://mina.apache.org/sshd-project/"),
    "org.xerial:sqlite-jdbc" to listOf("Xerial SQLite JDBC", "Apache-2.0", "https://github.com/xerial/sqlite-jdbc"),
    "net.java.dev.jna:jna" to listOf("Java Native Access (JNA)", "Apache-2.0", "https://github.com/java-native-access/jna"),
    "de.swiesend:secret-service" to listOf("Secret Service", "MIT", "https://github.com/swiesend/secret-service"),
    "net.i2p.crypto:eddsa" to listOf("EdDSA-Java", "CC0-1.0", "https://github.com/str4d/ed25519-java"),
    "org.slf4j:slf4j-api" to listOf("SLF4J API", "MIT", "https://www.slf4j.org"),
    "org.slf4j:slf4j-jdk14" to listOf("SLF4J JDK14 binding", "MIT", "https://www.slf4j.org"),
    "org.slf4j:jcl-over-slf4j" to listOf("JCL over SLF4J", "MIT", "https://www.slf4j.org"),
)

val aboutRuntimeCoordinates: List<String> = configurations.runtimeClasspath.get()
    .resolvedConfiguration
    .resolvedArtifacts
    .map { it.moduleVersion.id.toString() }
    // The bundled JediTerm modules are project artifacts; they are listed as one static entry below.
    .filterNot { it.startsWith("io.github.sawaichi9527.eyeshell:") || it.endsWith(":unspecified") }
    .distinct()
    .sorted()

val generateAboutManifest by tasks.registering {
    val coordinates = aboutRuntimeCoordinates
    val licenses = aboutLicenses
    val projectVersion = version.toString()
    val outputDir = layout.buildDirectory.dir("generated/about")
    inputs.property("coordinates", coordinates)
    inputs.property("projectVersion", projectVersion)
    outputs.dir(outputDir)
    doLast {
        fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", " ")

        val lines = mutableListOf<String>()
        var index = 0
        fun entry(name: String, componentVersion: String, license: String, url: String, scope: String) {
            index += 1
            lines += "component.$index.name=${escape(name)}"
            lines += "component.$index.version=${escape(componentVersion)}"
            lines += "component.$index.license=${escape(license)}"
            lines += "component.$index.url=${escape(url)}"
            lines += "component.$index.scope=$scope"
        }

        coordinates.forEach { coordinate ->
            val parts = coordinate.split(":")
            val key = "${parts[0]}:${parts[1]}"
            val versionPart = parts.getOrNull(2).orEmpty()
            val meta = licenses[key] ?: listOf(parts[1], "See THIRD_PARTY_NOTICES.md", "")
            entry(meta[0], versionPart, meta[1], meta[2], "runtime")
        }
        entry(
            "JediTerm (eyeShell fork)",
            "4ab49e9",
            "Apache-2.0",
            "https://github.com/sawaichi9527/jediterm",
            "runtime",
        )
        entry("JUnit Jupiter", "6.1.2", "EPL-2.0", "https://junit.org", "test")
        entry("FinalShell background presets", "-", "Not open source (see notices)", "", "asset")

        val file = outputDir.get().file("components.properties").asFile
        file.parentFile.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

tasks.processResources {
    val buildVersion = version.toString()
    inputs.property("eyeshellBuildVersion", buildVersion)
    filesMatching("eyeshell-build.properties") {
        expand("version" to buildVersion)
    }
    from(generateAboutManifest) { into("about") }
    from("THIRD_PARTY_NOTICES.md") { into("about") }
}

val jpackageTool = "${System.getProperty("java.home")}/bin/jpackage"
val jarTool = "${System.getProperty("java.home")}/bin/jar"
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
    iconFile: String? = null,
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
) + (
    iconFile?.let { listOf("--icon", File(it).absolutePath) } ?: emptyList()
    )

/** Windows packages need an ICO; Linux uses the largest bundled PNG. */
val appIconFile: String =
    if (isWindowsBuild) "packaging/eyeshell.ico" else "src/main/resources/icons/eyeshell-512.png"

tasks.register<Exec>("jpackageAppImage") {
    dependsOn(preparePackageInput)
    val inputDir = layout.buildDirectory.dir("package/input").get().asFile
    val destDir = layout.buildDirectory.dir("package").get().asFile
    val mainJar = tasks.jar.get().archiveFileName.get()
    inputs.files(configurations.runtimeClasspath, tasks.jar)
    doFirst { destDir.resolve("eyeShell").deleteRecursively() }
    commandLine(jpackageArgs(jpackageTool, packageVersion, "app-image", inputDir, destDir, mainJar, appIconFile))
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
        commandLine(jpackageArgs(jpackageTool, packageVersion, "msi", inputDir, destDir, mainJar, appIconFile))
        outputs.files(layout.buildDirectory.file("package/eyeShell-${packageVersion}.msi"))
    } else {
        commandLine(
            jpackageArgs(jpackageTool, packageVersion, "deb", inputDir, destDir, mainJar, appIconFile) + listOf(
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
        // The project-local JDK's jar tool writes the ZIP directly: Windows PowerShell 5.1 is not
        // permitted, and this keeps packaging free of an external pwsh or shell dependency.
        commandLine(jarTool, "--create", "--no-manifest", "--file", archivePath, "-C", appImageDir.absolutePath, ".")
    } else {
        commandLine("tar", "-C", appImageDir.absolutePath, "-czf", archivePath, ".")
    }
}
