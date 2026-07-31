# eyeShell OpenCode Rules

## Project Purpose

eyeShell is a local-first visual SSH console workbench for Windows and Linux. It prioritizes reliable SSH access, large console-log inspection, full scrollback copy/export, search, and regex highlighting.

The product baseline and scope are defined in `docs/PRODUCT_SPEC.md`. Changes must remain consistent with that document unless the user explicitly approves a specification update.

## Working Rules

- Read this file, `handoff.md`, and the relevant product specification before changing project files.
- Keep changes small, reviewable, and directly related to the requested task.
- Do not overwrite existing project files during initialization or scaffolding.
- Do not store passwords, tokens, private keys, passphrases, or internal credentials in source control, SQLite, logs, or examples.
- Ask before commit, push, deployment, deletion, stress testing, or other high-impact operations.
- Ask before installing system packages or changing user/system environment variables, shell profiles, registries, or global tool configuration.
- Do not add Telnet, UART/serial/TTY, cloud sync, 32-bit support, RDP, or cloud relay dependencies unless the product specification is explicitly changed first.

## Engineering Baseline

- Target Kotlin/JVM with Java 21 bytecode and Gradle Kotlin DSL.
- Keep the development JDK, Gradle distribution, Kotlin compiler, dependencies, and build caches project-local under `.local/`; do not require global JDK, Gradle, or Kotlin installations.
- Use repository launchers that set `JAVA_HOME`, `PATH`, and `GRADLE_USER_HOME` only for their child process. Do not persist these values outside the project.
- Pin downloaded toolchain versions and verify vendor checksums. Do not use pipe-to-shell installers or `mavenLocal()`.
- Keep the first implementation minimal; do not create every module listed in the architecture proposal before it is needed.
- Preserve capability boundaries between terminal, SSH/SFTP, monitoring, persistence, secrets, and platform services.
- Swing UI work must not block the Event Dispatch Thread with SSH, file transfer, monitoring, regex scanning, or large export operations.
- Secrets must use the operating-system credential store or remain session-only.
- Prefer deterministic tests with an embedded SSH test server over dependencies on external hosts.
- Support Windows 10/11 x64 and Ubuntu 24.04/26.04 x86_64 according to the product specification.

## Validation

Until the Gradle project is created, validate documentation-only changes with:

```text
git diff --check
```

After the Gradle wrapper is added, the minimum code validation becomes:

```text
./scripts/gradlew-local.sh test
./scripts/gradlew-local.sh check
```

Do not claim completion without fresh validation from the current change. Record commands, exit codes, results, test environment, and any unverified platform scope in `handoff.md`.
