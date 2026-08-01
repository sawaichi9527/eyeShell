# Handoff

## Current Status

The M1D SSH authentication baseline is implemented. Password, user-selected private key, keyboard-interactive, and explicit OpenSSH agent authentication share the existing transport and strict Known Hosts policy.

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

## In Progress

- None.

## Next Actions

- Validate the PowerShell bootstrap and launcher scripts on Windows 10/11 x64.
- Validate the build and Swing fallback path on Ubuntu 24.04 X11.
- Create the project-controlled JediTerm fork only when the first necessary upstream patch is identified; GitHub MCP currently lacks fork/create-repository permission.
- Validate keyboard-interactive dialogs against external multi-prompt and MFA-capable SSH servers.
- Validate OpenSSH agent authentication on Ubuntu 24.04/26.04 with a desktop-inherited `SSH_AUTH_SOCK`.
- Validate the asynchronous OpenSSH agent named-pipe transport on Windows 10/11 x64 with the pinned Temurin 21 runtime.

## Validation Evidence

- Commands: `./scripts/gradlew-local.sh test --rerun-tasks`; `./scripts/gradlew-local.sh check`; `git diff --check`; targeted secret-boundary search.
- Executed at: 2026-08-01
- Exit codes: 0 for tests, check, secret-boundary search, and diff check.
- Result: 15 tests passed with 0 failures/skips; `check` passed. New M1D tests cover keyboard-interactive challenge metadata and response-array clearing plus end-to-end agent identity enumeration, RSA SHA-2 signing, authentication, terminal opening, and cleanup through an in-process Unix-domain agent. No private key, challenge response, agent packet, or fixed credential fixture is stored in the repository.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with XWayland display available.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope: Windows scripts/runtime, ACL behavior, and OpenSSH agent named-pipe I/O; Ubuntu 24.04 X11 and external `SSH_AUTH_SOCK` interoperability; multi-prompt/MFA keyboard-interactive servers; native Wayland/JBR 25; visual review of authentication dialogs; packaging; non-RSA agent keys; adverse network behavior; large-output performance; SFTP; monitoring; SQLite; and OS secret stores.

## Known Issues

- PowerShell scripts are present but have not been executed on Windows.
- Monitoring, SFTP, and command input remain placeholders; no host metrics or remote file data are fabricated.
- Native Wayland behavior is not covered by the Temurin 21 M0 runtime; the current Linux baseline can use XWayland fallback until the JBR 25 runtime is evaluated.
- GitHub MCP's PAT returned 403 for both fork and repository creation. M1A therefore uses the pinned, unmodified official submodule; a project-controlled fork remains pending until a patch is required.
- Main-buffer export and scrollback clearing fail fast while an alternate screen is active because upstream JediTerm exposes only the active buffer. Phase 1 needs a narrowly documented fork patch before enabling those actions inside applications such as `vim` or `top`.
- Passwords and Private Key Passphrases remain session-only; OS Credential Store integration is not implemented.
- Public Key authentication is covered with a runtime-generated encrypted RSA OpenSSH key; other key formats/providers and external OpenSSH interoperability remain unverified.
- Changed Host Keys require manual verification and Known Hosts file editing outside eyeShell; automatic replacement is intentionally unavailable.
- Windows OpenSSH agent access relies on Temurin/OpenJDK asynchronous file-channel behavior for `\\.\pipe\openssh-ssh-agent`; this is not a portable Java SE named-pipe API and remains unverified on Windows 10/11.
- Linux agent authentication requires eyeShell to inherit a valid `SSH_AUTH_SOCK`; eyeShell intentionally does not discover arbitrary sockets or start an agent.
