# Handoff

## Current Status

The M0 desktop scaffold is buildable and tested on Ubuntu 26.04 x86_64. It uses a fully project-local Temurin/Gradle environment and provides a dark FlatLaf workbench shell. The Monitor is persistent outside the right-side Session pages; Quick Tools share the Command Input row; the SFTP/Commands dock expands below it.

## Completed

- Defined the eyeShell product overview in `README.md`.
- Defined the initial architecture and product baseline in `docs/PRODUCT_SPEC.md`.
- Initialized local OpenCode project rules and handoff tracking.
- Added the MIT license and a Kotlin/Gradle-oriented `.gitignore`.
- Defined project-local isolation for JDK, Gradle, Kotlin, dependencies, daemon state, and build caches.
- Pinned Temurin 21.0.12+8, Gradle 9.5.0, Kotlin 2.4.10, FlatLaf 3.7.2, and JUnit Jupiter 6.1.2.
- Added checksum-verifying Linux and Windows JDK bootstrap scripts.
- Added local Gradle launchers that scope `JAVA_HOME`, `PATH`, and `GRADLE_USER_HOME` to child processes.
- Added the Gradle Wrapper with distribution checksum verification.
- Added strict SHA-256 dependency verification metadata for the Kotlin toolchain and Maven dependencies.
- Added the M0 Swing workbench and a headless layout/dock behavior test.
- Documented the FinalShell-inspired layout reference and its intellectual-property/product-scope boundaries.
- Corrected the shell hierarchy so Session tabs do not include the Monitor and the Tools toggle is part of the Command Input row.

## In Progress

- None.

## Next Actions

- Request explicit user approval before pushing the project baseline and M0 commits.
- Validate the PowerShell bootstrap and launcher scripts on Windows 10/11 x64.
- Validate the build and Swing fallback path on Ubuntu 24.04 X11.
- Define M1 around the first real SSH/Terminal vertical slice without creating unused architecture modules.
- Select and pin Apache MINA SSHD and the JediTerm fork commit when M1 begins.

## Validation Evidence

- Commands: `./scripts/bootstrap-jdk.sh`; `./scripts/gradlew-local.sh --version`; `./scripts/gradlew-local.sh --write-verification-metadata sha256 test`; `./scripts/gradlew-local.sh test --rerun-tasks`; `./scripts/gradlew-local.sh check`; `bash -n scripts/bootstrap-jdk.sh scripts/gradlew-local.sh`; Gradle Wrapper JAR SHA-256 check; 8-second `./scripts/gradlew-local.sh run` smoke with expected timeout; system `java`/`gradle` lookup; `git diff --check`.
- Executed at: 2026-07-31
- Exit code: 0
- Result: Temurin bootstrap installed and then passed exact vendor/build and executable idempotency checks; Gradle 9.5.0 ran on Temurin 21.0.12+8; strict SHA-256 dependency verification passed; 1 test passed with 0 failures/skips; `check` passed. The test verifies Monitor is outside Session tabs and the Tools toggle is a child of the Command Input row. Shell scripts passed syntax validation; the Wrapper JAR matched the official checksum; system `java`, global Gradle home, and system `gradle` remained absent.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with XWayland display available.
- Test report: `build/reports/tests/test/index.html`; XML result: `build/test-results/test/TEST-io.github.sawaichi9527.eyeshell.ui.WorkbenchPanelTest.xml`.
- Remaining unverified scope: Windows scripts/runtime, Ubuntu 24.04 X11, native Wayland/JBR 25, visual review across DPI scales, packaging, SSH, terminal emulation, SFTP, monitoring, persistence, and secret stores.

## Known Issues

- PowerShell scripts are present but have not been executed on Windows.
- M0 contains layout placeholders only; it does not connect to hosts or display fabricated monitoring/SFTP data.
- Native Wayland behavior is not covered by the Temurin 21 M0 runtime; the current Linux baseline can use XWayland fallback until the JBR 25 runtime is evaluated.
