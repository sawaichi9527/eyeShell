# Handoff

## Current Status

The M1H Saved Password baseline is implemented in the worktree. Schema v2 assigns stable UUID identities to Saved Hosts, and password-only OS Credential Store adapters integrate Windows Credential Manager and Linux Freedesktop Secret Service without putting secrets in SQLite or blocking the Swing EDT.

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
- Published JediTerm core revision/bounds patch `bdc5ddf`, popup/selection/coordinate-search patch `c822275`, styled coordinate highlight overlay patch `05ed207`, and renderer composition tests `38413f9`.
- Added the exact Phase 1 terminal context menu with disabled Highlight placeholders instead of fake flows.
- Added Select Visible and Select All Output with bounded endpoint discovery and retained-main selection during alternate screen applications.
- Added background retained-main search with 150 ms debounce, cancellation, generation/revision stale-result rejection, and coordinate rendering.
- Added logical-line search semantics for soft wraps, hard breaks, Unicode/CJK DWC cells, locale-independent case matching, and keyboard navigation.
- Added deterministic coverage for menu order/enablement, empty/alternate selection, stale query publication, EDT responsiveness, soft/hard line matching, and CJK cell spans.
- Selected Google RE2/J 1.8 with strict SHA-256 dependency verification and documented its BSD 3-Clause notice.
- Added explicit Current Session highlight rules with case sensitivity, enablement, priority, foreground/background colors, bold/italic/underline, and Merge/Override behavior.
- Added Current Session Add/Manage rule dialogs without persistence or fabricated Global/Host/Workspace scopes.
- Added visible-first retained-main regex scanning, one-generation logical-line match reuse, coalesced model notifications, cancellation, and revision/generation stale-result rejection.
- Added a separate JediTerm styled coordinate overlay painted before interactive Search and Selection, hidden during alternate screen use, without modifying terminal text or ANSI data.
- Added deterministic coverage for RE2 syntax rejection, zero-length matches, soft/hard wraps, CJK DWC mapping, rule priority/style composition, override semantics, viewport logical-line boundaries, async publication, and immutable fork result indexing.
- Raised the Phase 1 terminal scrollback baseline from JediTerm's default 5,000 lines to 100,000 lines.
- Replaced full-history viewport preparation with direct indexed traversal bounded to the viewport and adjacent soft-wrap boundaries.
- Reduced incremental highlight cache cardinality from lines multiplied by rules to one entry per distinct logical-line text, with per-rule results inside each line entry.
- Added a real 100,000-line JediTerm pipeline gate plus a three-rule detached-snapshot gate that proves only the changed line is reevaluated in the next generation.
- Added sustained-output EDT probes, final-revision convergence, active-stream close/worker termination, and no-publication-after-close coverage.
- Added offscreen renderer coverage for ANSI Merge/Override composition, Highlight/Search/Selection/Cursor paint order, stale revision suppression, and alternate-screen hide/restore behavior.
- Pinned Xerial SQLite JDBC 3.53.2.1 using its classes-only artifact plus build-platform Linux/Windows native artifact with strict SHA-256 verification.
- Added XDG Data Home and Windows Local AppData catalog paths while preserving the existing Known Hosts config paths.
- Added transactional schema v1 migrations for `schema_versions`, `host_groups`, `hosts`, `tags`, and `host_tags` without secret-bearing columns.
- Added owner-only POSIX directory/file permissions, full path symlink rejection, database identity pinning, and journal-sidecar validation.
- Added non-secret Saved Host CRUD with Unicode/IPv6, groups, tags, explicit authentication method, rollback, reopen, and future-schema rejection coverage.
- Added a background serialized Host Catalog controller, `Hosts...` workbench entry, Add/Edit/Delete UI, and profile-prefilled SSH connection flow.
- Kept Passwords, Private Key paths/content, passphrases, interactive responses, agent data, and credential tokens outside the catalog model and schema.
- Added transactional schema v1-to-v2 migration that assigns each existing Host a stable, unique UUID while preserving IDs, groups, tags, timestamps, and non-secret profile data.
- Added a password-only credential capability boundary with explicit available, unavailable, and locked states plus defensive-copy, clear-on-close secret values.
- Added Windows Credential Manager generic credential storage through JNA 5.19.1, keyed only by the Saved Host profile UUID.
- Added Linux default-collection storage through Freedesktop Secret Service 1.8.1-jdk17 with no plaintext fallback when D-Bus, Secret Service, or the collection is unavailable or locked.
- Added saved-password retrieval and explicit Forget flow off the EDT; the Swing password document is not prefilled with the retrieved secret.
- Added post-success persistence so a replacement password is saved only after SSH authentication and terminal opening both succeed.
- Added credential cleanup when a Password profile is deleted or changes to a different authentication method.
- Added deterministic adapter and lifecycle coverage for platform selection, unavailable/locked capability, UUID keys, create/update/read/delete behavior, caller-array preservation, and profile cleanup.
- Added deterministic SSH orchestration coverage proving passwords are saved only after terminal opening, failed terminal opening never saves, save failure preserves the opened terminal, and non-Password profiles never retrieve saved credentials.
- Validated a disposable random-profile credential against the live Ubuntu 26.04 GNOME Secret Service: create, read, update, delete, and post-delete absence all passed; the test clears its arrays and deletes its item in `finally`.
- Hardened review findings with per-profile credential revisions, so delete/authentication changes invalidate in-flight connection saves and stale dialogs cannot recreate credentials.
- Added clearable rollback compensation around Password profile mutations; catalog failures restore the prior credential when possible and surface a dedicated warning if restoration also fails.
- Tracked and disposed the connection dialog during controller shutdown so retrieved passwords and worker tasks are not retained behind a modal dialog.
- Verified Linux create/update/delete results by reading/searching the Secret Service after each operation, strengthened the Windows probe to call `CredReadW`, and cleared all probe/verification arrays.
- Scoped runtime credential dependencies by build platform: Linux runtime excludes JNA and Windows runtime excludes Secret Service/DBus, while compile and test coverage retain both adapters.

## In Progress

- Complete M1H review and supported-platform integration validation before publishing.

## Next Actions

- Validate the PowerShell bootstrap and launcher scripts on Windows 10/11 x64.
- Validate the build and Swing fallback path on Ubuntu 24.04 X11.
- Manually validate Add/Manage highlight dialogs and color selection on supported desktop environments.
- Characterize heap usage with multiple simultaneous 100,000-line sessions and larger rule sets.
- Validate SQLite native loading, data paths, ACLs/reparse points, and classifier packaging on Windows 10/11 x64.
- Validate SQLite native loading under Ubuntu 24.04 X11 and enterprise `noexec` temporary filesystems.
- Validate Windows Credential Manager read/write/update/delete on Windows 10/11 x64.
- Validate locked GNOME Keyring behavior and repeat the lifecycle against a Secret Service-compatible KWallet setup.
- Decide whether a future catalog revision may store a Private Key File path reference; M1G intentionally does not persist it.
- Validate keyboard-interactive dialogs against external multi-prompt and MFA-capable SSH servers.
- Validate OpenSSH agent authentication on Ubuntu 24.04/26.04 with a desktop-inherited `SSH_AUTH_SOCK`.
- Validate the asynchronous OpenSSH agent named-pipe transport on Windows 10/11 x64 with the pinned Temurin 21 runtime.

## Validation Evidence

- Commands: `EYESHELL_TEST_LIVE_SECRET_SERVICE=1 ./scripts/gradlew-local.sh test --tests "*SystemPasswordCredentialStoreTest.live Linux Secret Service supports the credential lifecycle" --rerun-tasks`; targeted controller/store tests; `./scripts/gradlew-local.sh check --rerun-tasks`; `timeout --signal=TERM 15s ./scripts/gradlew-local.sh run`; `git diff --check`; runtime dependency report; targeted credential-literal search. Gradle dependency verification metadata was generated with `./scripts/gradlew-local.sh --write-verification-metadata sha256 test --rerun-tasks` and its 58-line artifact-only diff was reviewed.
- Executed at: 2026-08-02
- Exit codes: 0 for tests, check, dependency resolution, targeted credential-literal search, and diff checks; 124 expected for the bounded GUI smoke timeout.
- Result: Regular validation ran 72 root tests: 71 passed, 0 failed, and the explicitly opt-in live-platform test was skipped as expected; `check` passed. The same stricter live test passed separately against GNOME Secret Service and removed its disposable item. New M1H coverage includes schema v1-to-v2 preservation, stable UUID CRUD/reopen behavior, clearable credential values, cross-profile isolation, platform selection, unavailable/locked status, Windows UUID-keyed lifecycle, verified Linux lifecycle, profile cleanup/rollback failures, stale-save rejection, and SSH save ordering/failure behavior. Linux runtime dependency resolution excludes JNA. Swing remained alive until the expected timeout. GitHub Advanced Security secret scanning was unavailable for this repository, so a targeted local credential-literal search was used and found no matches.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with XWayland display available.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope: Windows Credential Manager native behavior, locked GNOME Keyring behavior, KWallet integration, manual Saved Password/Host Catalog/highlight dialogs, SQLite Windows native loading/ACL/reparse points and packaged architecture filtering, Ubuntu 24.04 X11 and `noexec` native extraction, migration fault injection and lock contention, multi-session heap characterization, exhaustive search/highlight interleavings, Windows clipboard and atomic-move behavior, Windows scripts/runtime, OpenSSH agent platform I/O, external multi-prompt/MFA servers, native Wayland/JBR 25, packaging, non-RSA agent keys, adverse network behavior, SFTP, and monitoring.

## Known Issues

- PowerShell scripts are present but have not been executed on Windows.
- Monitoring, SFTP, and command input remain placeholders; no host metrics or remote file data are fabricated.
- Native Wayland behavior is not covered by the Temurin 21 M0 runtime; the current Linux baseline can use XWayland fallback until the JBR 25 runtime is evaluated.
- Scrollback clearing remains unavailable while alternate screen is active; Copy All and Save All now operate on the retained main buffer.
- Saved Password OS Credential Store integration passed live GNOME Secret Service lifecycle validation but remains unverified on Windows, locked GNOME Keyring, and KWallet; Private Key Passphrases and all other interactive secrets remain session-only.
- Public Key authentication is covered with a runtime-generated encrypted RSA OpenSSH key; other key formats/providers and external OpenSSH interoperability remain unverified.
- Changed Host Keys require manual verification and Known Hosts file editing outside eyeShell; automatic replacement is intentionally unavailable.
- Windows OpenSSH agent access relies on Temurin/OpenJDK asynchronous file-channel behavior for `\\.\pipe\openssh-ssh-agent`; this is not a portable Java SE named-pipe API and remains unverified on Windows 10/11.
- Linux agent authentication requires eyeShell to inherit a valid `SSH_AUTH_SOCK`; eyeShell intentionally does not discover arbitrary sockets or start an agent.
- Save All requires atomic move support in the selected target file system; unsupported providers fail without replacing the existing target.
- Main search/selection results are intentionally not painted over an active alternate screen; they become visible after returning to the main buffer.
- Highlight rules are Current Session only and are intentionally discarded when the application closes; persistent Global/Host/Workspace scopes require a later catalog migration and scope resolver.
- M1G stores authentication method but intentionally does not store Private Key File paths or any authentication secret.
