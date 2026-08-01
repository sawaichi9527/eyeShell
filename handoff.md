# Handoff

## Current Status

The M1E-A terminal output baseline is implemented. Copy All and Save All share an immutable retained-main-buffer snapshot, including while alternate screen applications are active; the required JediTerm fork patch is published and pinned.

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
- Added Password and Public Key authentication request types that copy and clear session-only secret arrays without exposing them through generated `toString` methods.
- Added encrypted OpenSSH private-key loading with optional session-only passphrase and immediate removal of authentication identities after auth.
- Added OpenSSH-style strict POSIX permission validation before loading a Private Key File.
- Added cross-platform app-specific Known Hosts paths for Linux/XDG and Windows/Roaming AppData.
- Added strict Known Hosts preparation with POSIX `0700`/`0600`, symbolic-link rejection, fail-closed write errors, and no automatic changed-key replacement.
- Extended the connection dialog with authentication selection, private-key file chooser, and optional passphrase.
- Added embedded tests for Known Hosts persistence/reuse, changed-key rejection, encrypted private-key authentication, and platform path resolution without committed key fixtures.
- Added a session-only keyboard-interactive challenge boundary with server name, instruction, language, prompt, and echo metadata.
- Added a Swing keyboard-interactive challenge dialog that runs on the EDT while authentication remains on a background virtual thread.
- Kept keyboard-interactive network phases under the 15-second authentication timeout while excluding active human-response time; timeout, disconnect, and controller close dispose the owned challenge dialog.
- Added explicit SSH agent authentication with no fallback, key mutation, agent forwarding, service startup, or socket discovery.
- Added bounded OpenSSH agent framing with a 256 KiB response limit, Linux Unix-domain sockets from `SSH_AUTH_SOCK`, and the fixed Windows OpenSSH named pipe.
- Added an in-process Unix-domain test agent covering identity enumeration, RSA SHA-2 signing, embedded-server authentication, terminal opening, and cleanup.
- Created the project-controlled `sawaichi9527/jediterm` fork from upstream `377b76e` and published immutable main-buffer snapshot patches through `765500d`.
- Switched the JediTerm submodule source to the project fork and pinned the first independent buffer patch.
- Added immutable text/wrap snapshots so large Writer and disk operations do not hold the JediTerm model lock or block Swing repaint; 100,000-line snapshot memory remains a later performance gate.
- Added `Copy All Output` with an 8,388,608 UTF-16 code-unit limit and Save All fallback prompt.
- Added UTF-8 `Save All Output...` through a same-directory temporary file and fail-closed atomic replacement.
- Added cancellation-safe capture/publication state and deferred terminal close without waiting on the Swing EDT.
- Added deterministic coverage for alternate-screen export, context action enablement, clipboard limits, Unicode boundaries, atomic replacement, failure cleanup, cancellation, and close-during-capture lifecycle.

## In Progress

- None.

## Next Actions

- Validate the PowerShell bootstrap and launcher scripts on Windows 10/11 x64.
- Validate the build and Swing fallback path on Ubuntu 24.04 X11.
- Implement M1E-B context-menu ordering, Select Visible, Select All Output, and hardened terminal search.
- Validate keyboard-interactive dialogs against external multi-prompt and MFA-capable SSH servers.
- Validate OpenSSH agent authentication on Ubuntu 24.04/26.04 with a desktop-inherited `SSH_AUTH_SOCK`.
- Validate the asynchronous OpenSSH agent named-pipe transport on Windows 10/11 x64 with the pinned Temurin 21 runtime.

## Validation Evidence

- Commands: `./scripts/gradlew-local.sh test --rerun-tasks`; `./scripts/gradlew-local.sh check`; 8-second `timeout --signal=TERM 8s ./scripts/gradlew-local.sh run`; parent/fork `git diff --check`; targeted secret-boundary search.
- Executed at: 2026-08-01
- Exit codes: 0 for tests, check, secret-boundary search, and diff checks; 124 expected for the bounded GUI smoke timeout.
- Result: 20 tests passed with 0 failures/skips; `check` passed. New M1E-A tests cover retained-main-buffer export while alternate screen is active, context action enablement/dispatch, UTF-16 clipboard boundaries, atomic replacement and failure cleanup, interrupted publication, and close during non-interruptible snapshot capture. Swing launched and remained alive until the expected timeout. No credential or terminal-output fixture is stored in the repository.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with XWayland display available.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope: the fork's standalone Gradle test suite, Windows clipboard and atomic-move behavior, 100,000-line snapshot performance, full context-menu visual review, Windows scripts/runtime, ACL behavior and OpenSSH agent named-pipe I/O, Ubuntu 24.04 X11 and external `SSH_AUTH_SOCK` interoperability, multi-prompt/MFA servers, native Wayland/JBR 25, packaging, non-RSA agent keys, adverse network behavior, SFTP, monitoring, SQLite, and OS secret stores.

## Known Issues

- PowerShell scripts are present but have not been executed on Windows.
- Monitoring, SFTP, and command input remain placeholders; no host metrics or remote file data are fabricated.
- Native Wayland behavior is not covered by the Temurin 21 M0 runtime; the current Linux baseline can use XWayland fallback until the JBR 25 runtime is evaluated.
- Scrollback clearing remains unavailable while alternate screen is active; Copy All and Save All now operate on the retained main buffer.
- Passwords and Private Key Passphrases remain session-only; OS Credential Store integration is not implemented.
- Public Key authentication is covered with a runtime-generated encrypted RSA OpenSSH key; other key formats/providers and external OpenSSH interoperability remain unverified.
- Changed Host Keys require manual verification and Known Hosts file editing outside eyeShell; automatic replacement is intentionally unavailable.
- Windows OpenSSH agent access relies on Temurin/OpenJDK asynchronous file-channel behavior for `\\.\pipe\openssh-ssh-agent`; this is not a portable Java SE named-pipe API and remains unverified on Windows 10/11.
- Linux agent authentication requires eyeShell to inherit a valid `SSH_AUTH_SOCK`; eyeShell intentionally does not discover arbitrary sockets or start an agent.
- Save All requires atomic move support in the selected target file system; unsupported providers fail without replacing the existing target.
- The complete terminal context-menu ordering and visual selection actions remain M1E-B scope.
