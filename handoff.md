# Handoff

## Current Status

The M1B SSH terminal vertical slice is implemented. The workbench starts in an honest disconnected state, collects direct SSH connection details, confirms the presented host-key fingerprint, and opens an Apache MINA SSHD-backed interactive PTY in JediTerm without blocking the Swing EDT.

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
- Pushed the M0 baseline commits through `f3f7fc4` to `origin/main` and changed the local origin URL to SSH after HTTPS credential lookup failed.
- Pinned `JetBrains/jediterm` at `377b76e682a5f86bcbb18a318386f530dbebf5c1` under `third-party/jediterm`.
- Selected JediTerm's Apache-2.0 license option and documented the third-party notice.
- Added local `jediterm-core` and `jediterm-ui` Gradle adapters; standalone JediTerm, Pty4J, JNA, and local terminal support are excluded.
- Added a terminal capability boundary so the workbench does not depend directly on JediTerm APIs.
- Added a deterministic synthetic terminal session covering ANSI colors, Unicode/CJK, scrollback, soft-wrap, hard line breaks, and alternate screen behavior.
- Added non-EDT streaming export of main-buffer logical lines, including removal of JediTerm's internal double-width character marker.
- Added deterministic tests for terminal parsing/export, alternate-screen isolation, scrollback clearing, synthetic I/O, and terminal component embedding.
- Pinned Apache MINA SSHD 2.19.0 with strict SHA-256 dependency verification and documented its Apache-2.0 notice.
- Added validated SSH endpoint and presented host-key boundaries without exposing MINA types to the UI.
- Added a password-only MINA client transport with explicit host-key verification, disabled implicit local identities/config, UTF-8 shell I/O, PTY resize, and non-blocking immediate close.
- Added a session-only SSH connection dialog; passwords are held as `CharArray`, cleared after connection attempt, and never persisted or logged.
- Moved connect/auth work to virtual threads and kept host-key confirmation on the Swing EDT.
- Replaced the startup synthetic demo with an explicit disconnected state and real `Connect...` flow.
- Added process-free embedded SSH tests for accepted/rejected host keys, accepted/rejected passwords, shell I/O, Unicode, resize, and connection lifecycle.

## In Progress

- None.

## Next Actions

- Validate the PowerShell bootstrap and launcher scripts on Windows 10/11 x64.
- Validate the build and Swing fallback path on Ubuntu 24.04 X11.
- Create the project-controlled JediTerm fork only when the first necessary upstream patch is identified; GitHub MCP currently lacks fork/create-repository permission.
- Define M1C around persistent Known Hosts and the next authentication method without storing secrets in project data.

## Validation Evidence

- Commands: `./scripts/gradlew-local.sh --write-verification-metadata sha256 compileKotlin`; `./scripts/gradlew-local.sh test --rerun-tasks`; `./scripts/gradlew-local.sh check`; `./scripts/gradlew-local.sh dependencies --configuration runtimeClasspath`; 8-second `timeout --signal=TERM 8s ./scripts/gradlew-local.sh run`; `git diff --check`.
- Executed at: 2026-08-01
- Exit codes: 0 for dependency metadata generation, tests, check, dependency report, and diff check; 124 expected for the bounded GUI smoke timeout.
- Result: strict SHA-256 verification passed; 8 tests passed with 0 failures/skips; `check` passed. Tests cover the M0/M1A layout and terminal behavior plus embedded SSH host-key acceptance/rejection, password acceptance/rejection, authenticated UTF-8 shell I/O, resize, close, and disconnected-to-connected UI state. Runtime dependencies add only Apache MINA SSHD core/common and its logging bridge; Pty4J/JNA remain absent. Swing launched in the disconnected state, remained alive until the expected timeout, and left no application process behind.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with XWayland display available.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope: Windows scripts/runtime, Ubuntu 24.04 X11, native Wayland/JBR 25, visual review across DPI scales, packaging, external OpenSSH interoperability, adverse network behavior, large-output performance, SFTP, monitoring, persistence, and secret stores.

## Known Issues

- PowerShell scripts are present but have not been executed on Windows.
- SSH, monitoring, SFTP, and command input remain placeholders; no host metrics or remote file data are fabricated.
- Native Wayland behavior is not covered by the Temurin 21 M0 runtime; the current Linux baseline can use XWayland fallback until the JBR 25 runtime is evaluated.
- GitHub MCP's PAT returned 403 for both fork and repository creation. M1A therefore uses the pinned, unmodified official submodule; a project-controlled fork remains pending until a patch is required.
- Main-buffer export and scrollback clearing fail fast while an alternate screen is active because upstream JediTerm exposes only the active buffer. Phase 1 needs a narrowly documented fork patch before enabling those actions inside applications such as `vim` or `top`.
- M1B supports direct TCP and password authentication only. Host-key acceptance lasts for one connection and is not yet written to a Known Hosts store.
- M1B has only been tested against the embedded Apache MINA server; interoperability with OpenSSH servers and network failure behavior remain unverified.
