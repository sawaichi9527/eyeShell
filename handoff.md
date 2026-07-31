# Handoff

## Current Status

The M1A synthetic terminal baseline is implemented on top of the M0 workbench. JediTerm 3.74 is pinned as a submodule and only its core/ui source is compiled through local Gradle adapter modules. The application displays deterministic ANSI and Unicode terminal output without adding SSH or local TTY support.

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

## In Progress

- None.

## Next Actions

- Validate the PowerShell bootstrap and launcher scripts on Windows 10/11 x64.
- Validate the build and Swing fallback path on Ubuntu 24.04 X11.
- Create the project-controlled JediTerm fork only when the first necessary upstream patch is identified; GitHub MCP currently lacks fork/create-repository permission.
- Define M1B around the first real SSH terminal vertical slice and pin Apache MINA SSHD without adding unused architecture modules.

## Validation Evidence

- Commands: `./scripts/gradlew-local.sh --write-verification-metadata sha256 test`; `./scripts/gradlew-local.sh test --rerun-tasks`; `./scripts/gradlew-local.sh check`; `./scripts/gradlew-local.sh dependencies --configuration runtimeClasspath`; 8-second `timeout --signal=TERM 8s ./scripts/gradlew-local.sh run`; `git diff --check`.
- Executed at: 2026-08-01
- Exit codes: 0 for dependency metadata generation, tests, check, dependency report, and diff check; 124 expected for the bounded GUI smoke timeout.
- Result: strict SHA-256 verification passed; 4 tests passed with 0 failures/skips; `check` passed. Tests cover M0 layout plus ANSI parsing, Unicode/CJK export, soft-wrap reconstruction, hard breaks, scrollback, alternate-screen isolation, scrollback clearing, synthetic I/O, and TerminalView embedding. Runtime dependencies contain only FlatLaf, JediTerm core/ui, Kotlin stdlib, SLF4J, and JetBrains annotations; Pty4J/JNA are absent. Swing launch remained alive until the expected timeout and left no application process behind.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with XWayland display available.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope: Windows scripts/runtime, Ubuntu 24.04 X11, native Wayland/JBR 25, visual review across DPI scales, packaging, live SSH terminal I/O, large-output performance, SFTP, monitoring, persistence, and secret stores.

## Known Issues

- PowerShell scripts are present but have not been executed on Windows.
- SSH, monitoring, SFTP, and command input remain placeholders; no host metrics or remote file data are fabricated.
- Native Wayland behavior is not covered by the Temurin 21 M0 runtime; the current Linux baseline can use XWayland fallback until the JBR 25 runtime is evaluated.
- GitHub MCP's PAT returned 403 for both fork and repository creation. M1A therefore uses the pinned, unmodified official submodule; a project-controlled fork remains pending until a patch is required.
- Main-buffer export and scrollback clearing fail fast while an alternate screen is active because upstream JediTerm exposes only the active buffer. Phase 1 needs a narrowly documented fork patch before enabling those actions inside applications such as `vim` or `top`.
