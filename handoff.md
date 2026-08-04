# Handoff

## Current Status

M1I Multi-session Tabs (`333b6cc`), the cross-platform XDG path test fix (`0e79ce5`), M1J Session Lifecycle Status (`2d27169`), M1K Safe Mode (`f433259`), and M1L Wayland/X11 launch strategy (`6402bb8`) are all committed and pushed to `origin/main`; the worktree is clean on those baselines.

M1M Packaging is implemented in the worktree. `jpackage` tasks produce a bundled-runtime app image, a Linux DEB installer, and a portable `tar.gz` (Windows MSI/ZIP variants are wired for the Windows host), all driven by the project-local JDK with a small testable artifact-naming boundary.

The Ubuntu-implemented M1J/M1K/M1L/M1M changes have been re-validated on the Windows host (94 tests, 6 opt-in/platform-gated skips, `check` passed); see the Windows validation evidence below. The MSI/ZIP packaging branches and live GUI launch remain unverified on Windows.

### 2026-08-03 Windows handoff (Ubuntu follow-up)

The M1I baseline worktree is clean (`333b6cc`). An initial uncommitted `EyeShellWindow.kt` attempt at M1J was reviewed on the Windows host, found defective (removed the final-tab Start restore, broke `"Connected to X"`/`"Start"` assertions, had a dead and buggy `markSessionExited`, and no status enum/watcher/tests), and was reverted before handoff. The full analysis and the recommended M1J plan are recorded under `## M1J Plan` below.

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
- Replaced the singleton terminal view with a `TerminalView` factory and one view/output/highlight aggregate per successful SSH session tab.
- Kept established sessions independent while retaining one in-flight connection attempt at a time; Connect remains available after attachment and a busy Saved Host request remains visible with an explanation.
- Added close controls that tear down only the selected session, restore the Start tab after the final close, and continue window-wide cleanup after individual close failures.
- Preserved connection-attempt status without overwriting a selected live session after an additional attempt is cancelled or fails.
- Added deterministic two-tab UI ownership, selection/status, isolated close, shared-failure cleanup, and two-JediTerm-buffer isolation coverage.
- Bootstrapped the project-local environment on Windows 10/11 x64: Temurin 21.0.12+8 under `.local/jdk-21` and Gradle 9.5.0 plus dependencies under `.local/gradle-home`, driven only by the PowerShell scripts.
- Added Windows-side strict SHA-256 verification entries for `spring-framework-bom-5.3.39.module` and `junit-bom-6.1.2.pom` so dependency verification passes on the Windows host.
- Marked the Linux-only XDG config/data home tests with `@EnabledOnOs(OS.LINUX)` (the Windows JVM treats drive-less `/tmp/...` as non-absolute, so those assertions are Linux-semantics-only), and made the active-stream close test stop requiring a highlight publication while the stream is still running.
- Replaced the two OS-gated XDG path tests with `@TempDir`-based cross-platform assertions (`EyeShellPathsTest` now runs all 6 tests with 0 skips on both Linux and Windows), removing the platform-specific skip while keeping the absolute-XDG-path resolution covered.
- Added a `SessionStatus` lifecycle enum (`CONNECTED`, `EXITED`, `FAILED`, `CLOSING`) with label/color, and made the workbench tab header carry a per-session status chip plus a status-aware selected-session bottom bar.
- Added a per-session background virtual-thread exit watcher (`TerminalSessionPage.startExitMonitor`) that blocks on `session.awaitExit()` and posts an EDT `EXITED` update only if the page was not explicitly closed, so natural remote exit repaints the tab without closing the view or clearing scrollback and user-initiated close never publishes a stale update.
- Wired the exit watcher from `EyeShellWindow.attachTerminal` after a successful tab attach, keeping the existing `WorkbenchPanelTest` cases (which attach pages directly) unaffected.
- Added deterministic M1J coverage: natural exit marks the tab `Exited` and preserves the view, explicit close suppresses the pending exit update, one exited tab leaves another live tab connected, and a connection failure does not overwrite an exited selected session.
- Added a `SafeMode` boundary (`platform/SafeMode.kt`) that detects the `--safe-mode` launch argument, exposes safe scrollback/refresh-rate overrides, and computes platform startup system properties (Linux forces `sun.awt.X11.XToolkit`; Windows disables `sun.java2d.d3d`/`sun.java2d.opengl`).
- Made `EyeShellTerminalSettings` accept configurable scrollback and max refresh rate (single default source `MAX_SCROLLBACK_LINES`/`MAX_REFRESH_RATE`), and passed both through `JediTermTerminalView`.
- Wired `main(args)` to parse `--safe-mode`, apply safe system properties before AWT initialization, disable FlatLaf animations (`flatlaf.animation=false`), and build terminal views with the safe scrollback/refresh-rate.
- Added deterministic SafeMode coverage: argument detection, safe-vs-default overrides, Windows/Linux startup property maps, and the terminal settings honoring the safe scrollback/refresh-rate.
- Added a `LaunchStrategy` boundary (`platform/LaunchStrategy.kt`) that detects the desktop session (`XDG_SESSION_TYPE`/`WAYLAND_DISPLAY`/`DISPLAY`) and selects the AWT toolkit: native Wayland (`sun.awt.WLToolkit`) when the runtime supports it, otherwise the X toolkit (`sun.awt.X11.XToolkit`); Windows stays on the platform default.
- Wired `main(args)` to resolve the launch strategy from the environment before AWT initialization and apply its toolkit property, while Safe Mode forces the X toolkit through `forceX11`.
- Moved the Linux `awt.toolkit` override out of `SafeMode` so the launch strategy is the single owner of toolkit selection; Safe Mode still disables Windows Direct3D/OpenGL and FlatLaf animations.
- Added deterministic M1L coverage: session-type detection from environment variables, Windows platform-default selection, Linux X11/Wayland selection, Wayland fallback to X11 when the toolkit is unavailable, and Safe Mode forcing the X toolkit.
- Added `jpackage`-based packaging tasks in `build.gradle.kts`: `preparePackageInput` (assembles the runtime classpath plus the application jar), `jpackageAppImage` (bundled-runtime app image), `jpackageInstaller` (Linux DEB / Windows MSI), and `jpackagePortable` (Linux `tar.gz` / Windows ZIP), all invoked with the project-local JDK's `jpackage`/`jlink`.
- Added a `PackageArtifacts` boundary (`platform/PackageArtifacts.kt`) that owns the app/package names, main class, package version (snapshot stripped), and platform archive file names shared by the build.
- Added deterministic M1M coverage: snapshot-stripped package version, Linux/Windows portable archive names, and Linux/Windows installer names.

## In Progress

- None.

## M1M Plan (Packaging)

Implemented in the worktree and validated on Ubuntu 26.04 (94 root tests, 93 passed, 1 opt-in skip). Scope delivered: `jpackage` tasks producing a bundled-runtime app image, a Linux DEB (`eyeshell_0.1.0_amd64.deb`), and a portable `eyeShell-0.1.0.tar.gz`, verified by running all three tasks from clean and inspecting the artifacts (`dpkg-deb -I`, `tar -tzf`, launcher + `lib/runtime` presence). The Windows MSI/ZIP branches are wired behind the existing `isWindowsBuild` platform switch but are unverified until run on the Windows host. Note: `outputs.dir` must not be declared on the app-image task because Gradle pre-creates declared output directories, which makes `jpackage` report "destination already exists"; the task deletes the destination in `doFirst` instead.

## M1L Plan (Wayland/X11 startup strategy)

Implemented in the worktree and validated on Ubuntu 26.04 (91 root tests, 90 passed, 1 opt-in skip). Scope delivered: `DesktopSession.detect` (Wayland/X11/unknown from `XDG_SESSION_TYPE`, `WAYLAND_DISPLAY`, `DISPLAY`), `LaunchStrategy.resolve` (Windows platform default; Linux native Wayland only when `sun.awt.WLToolkit` is available, otherwise X toolkit; Safe Mode forces X11), and `main(args)` applying the toolkit property before AWT init. Native Wayland remains unverified on a WLToolkit-capable JBR 25 runtime; the current Temurin 21 baseline uses the X toolkit via XWayland.

## M1K Plan (Safe Mode)

Safe Mode is implemented in the worktree and validated on Ubuntu 26.04 (85 root tests, 84 passed, 1 opt-in skip). Scope delivered: `--safe-mode` argument detection, lower scrollback (10,000) and max refresh rate (10), Linux X toolkit forcing / Windows Direct3D+OpenGL disabling, and FlatLaf animation off. Not yet covered: live monitoring charts are still placeholders (nothing to pause), and non-essential extension gating has no extensions to disable yet.

## M1J Plan (handoff to Windows/opencode environment)

Scope confirmed by user: per-tab lifecycle status + repo write only; feature code was NOT implemented on the Linux side. Design direction researched:

- **Detection seam (already confirmed in code):** natural remote exit already flows through the terminal engine — `MinaSshTerminalSession.read()` returns `-1` on EOF (MinaSshConnection.kt:241), the JediTerm emulator loop stops (`TerminalStarter.doStartEmulator`), and `JediTermTerminalView.isSessionRunning` / `widget.isSessionRunning` becomes `false`. M1J only needs to surface this into the tab UI; no engine change is required to *detect* the event. User-initiated close goes through `TerminalSessionPage.close()` (EyeShellWindow.kt:120) → `outputController.close(terminalView::close)`.
- **Suggested mechanism:** background virtual-thread watcher per session page that observes the session/view natural-stop signal and posts an EDT status update; polling `isSessionRunning` is simplest and deterministic-testable (open design question — see below).
- **Status enum:** `CONNECTED`, `EXITED`, `FAILED`, `CLOSING`.
  - `EXITED`: observed clean EOF while the tab remains listed → repaint tab header only, do **not** close the view or remove the tab, so scrollback is preserved.
  - `FAILED`: unexpected stop / transport exception while listed (open design question — may keep minimal and map any stop to `EXITED` for M1J).
  - `CLOSING`: transient during user-initiated teardown.
- **UI wiring:** `WorkbenchPanel.createSessionTabHeader` (EyeShellWindow.kt:259) currently renders a static header; make it status-updatable (small colored status chip beside the title) and update the selected-session bottom bar (`connectionStatus`). Add `WorkbenchPanel.updateSessionStatus(component, status)`.
- **Config to verify on next session:** `/home/sawaichi/.config/opencode/opencode.json*` and `~/.config/opencode/` project/global settings, and `third-party/jediterm` fork pin — confirm the Windows environment uses the same project-local `.local/` JDK/Gradle and vendored submodule.

### 2026-08-03 conclusion (Windows review, handoff to Ubuntu)

- **Decision: discard the draft.** The prior session left an uncommitted `EyeShellWindow.kt` change that was broken and out of scope; it has been reverted (`git checkout --`), so M1J starts from the clean `333b6cc` baseline.
- **Facts confirmed on the current tree:**
  - `TerminalView` (TerminalView.kt) exposes **no** lifecycle signal; `JediTermTerminalView.isSessionRunning` (JediTermTerminalView.kt:89) is `internal` and not reachable from the UI layer.
  - `TerminalSession` exposes `isOpen` and `awaitExit()` (TerminalSession.kt:6,18) — `awaitExit()` returns on natural EOF, so it is a clean, deterministic blocking detection point.
  - Existing tests pin exact strings that must be preserved: `connectionStatus` text `"Connected to <name>"` and the restored `"Start"` tab title after the final close (WorkbenchPanelTest.kt), plus `closeSession`'s final-tab restore behavior. Any M1J UI change must keep these green.
  - `closeSession` must keep its existing `finally` restore of the empty/Start tab; the discarded draft had removed it, which would break `closing all tabs continues after one close failure` and `closing one terminal tab leaves the other session active`.
- **Recommended detection design (Decision A):** per-session background virtual-thread watcher started from `EyeShellWindow.attachTerminal` after a successful attach; the watcher blocks on `session.awaitExit()` and, on return, posts an EDT update to `WorkbenchPanel.updateSessionStatus(component, EXITED)`. It does **not** close the view or remove the tab, so scrollback is preserved. Starting the watcher at the `EyeShellWindow` layer (not in `TerminalSessionPage.attach()`) keeps existing `WorkbenchPanelTest` cases unaffected.
  - Alternative Decision B (more invasive): add lifecycle exposure to the `TerminalView` interface (would require updating `TestTerminalView`).
- **Status scope for M1J (Decision C):** minimal — map any observed natural stop to `EXITED`; `FAILED`/`CLOSING` are deferred to a later milestone.
- **Test strategy:** new deterministic tests use a controllable fake `TerminalSession` whose `awaitExit()` blocks until the test signals exit; verify the tab stays listed, the view is not closed (scrollback preserved), the header chip and bottom bar update, the close button still closes, and an unrelated session is unaffected.

## Next Actions

- M1J, M1K, M1L, and M1M are implemented and validated on Ubuntu 26.04 and re-validated on the Windows host (94 tests, 6 skips, `check` passed); the Windows-side test re-validation is complete and recorded in the validation evidence below.
- Validate the MSI and ZIP branches of the packaging tasks on Windows 10/11 x64 (MSI requires the WiX toolset, which jpackage invokes on Windows).
- Validate the native Wayland path on a WLToolkit-capable JBR 25 runtime (Ubuntu 24.04/26.04 Wayland session) and confirm the X toolkit still launches under XWayland and full X11.
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

- Commands: `./scripts/gradlew-local.sh test --rerun-tasks`; `./scripts/gradlew-local.sh check`; targeted `WorkbenchPanelTest`, `HostCatalogControllerTest`, `SshConnectionControllerTest`, and two-view JediTerm isolation tests; `timeout --signal=TERM 15s ./scripts/gradlew-local.sh run`; `git diff --check`.
- Executed at: 2026-08-02
- Exit codes: 0 for tests, check, targeted tests, and diff checks; 124 expected for the bounded GUI smoke timeout.
- Result: Regular validation ran 75 root tests: 74 passed, 0 failed, and the explicitly opt-in Secret Service live-platform test was skipped as expected; `check` passed. New M1I coverage proves two independent tabs/views attach distinct sessions, selection restores per-session status, closing one tab leaves the other active, window-level cleanup continues after repeated close failures, and separate JediTerm buffers do not mix output. Swing launched and remained alive until the expected timeout. No dependency, verification metadata, schema, or secret-storage change was introduced by M1I.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with XWayland display available.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope: natural remote-exit tab status (M1J future scope), multiple simultaneous connection attempts, multi-session heap characterization, live GUI launch on Windows, Windows Credential Manager, OpenSSH agent named pipe, clipboard and atomic-move behavior, catalog persistence, and multi-platform continuous integrations (both Ubuntu Linux and Windows platforms).

### Windows host validation (local workbench setup + cross-platform test fixes)

- Commands: `powershell -ExecutionPolicy Bypass -File ".\scripts\bootstrap-jdk.ps1"`; `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" test`; `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" check`; `git diff --check`.
- Executed at: 2026-08-03
- Exit codes: 0 for test and check; bootstrap succeeded with JDK SHA-256 verification.
- Result: 75 root tests: 73 passed, 0 failed, 0 errors; 8 skipped (2 newly Linux-gated XDG path tests plus the pre-existing platform-gated Secret Service/SQLite skips). The previously failing `EyeShellPathsTest` XDG tests and the `JediTermTerminalViewTest` active-stream close test now pass. `check` passed. This 8-skip count reflects the state on 2026-08-03; after the later `@TempDir` cross-platform fix, the next Windows run is expected to report 6 skipped (only the opt-in Secret Service live test and SQLite POSIX-gated tests) with `EyeShellPathsTest` at 6/6.
- Test environment: Windows host (win32, PowerShell), Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10, dependency verification metadata extended for the Windows host.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope on Windows: live GUI launch, native SQLite loading/ACL/reparse points and packaged architecture filtering, Windows Credential Manager, OpenSSH agent named pipe, clipboard and atomic-move behavior, packaging, and re-running the cross-platform XDG path test fix on the Windows host (expected `EyeShellPathsTest` 6/6).

### Windows host validation (M1J/M1K/M1L/M1M + cross-platform XDG re-validation)

- Commands: `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" test`; `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" check`; `git diff --check`.
- Executed at: 2026-08-04
- Exit codes: 0 for test and check; diff clean.
- Result: After fast-forwarding to `c1fb045` (which carries the Ubuntu `0e79ce5` XDG fix plus M1J/M1K/M1L/M1M), the full Windows suite runs 94 root tests: 94 executed, 0 failures, 0 errors, 6 skipped (only the opt-in Secret Service live test and SQLite POSIX-gated tests). `EyeShellPathsTest` now runs 6/6 cross-platform with 0 skipped, confirming the Ubuntu-predicted count. `check` passed. No dependency, verification metadata, schema, or secret-storage change was introduced by the Windows re-validation.
- Test environment: Windows host (win32, PowerShell), Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope on Windows: live GUI launch, native SQLite loading/ACL/reparse points and packaged architecture filtering, Windows Credential Manager, OpenSSH agent named pipe, clipboard and atomic-move behavior, and the MSI/ZIP branches of the packaging tasks (MSI requires the WiX toolset).

### Ubuntu host validation (cross-platform XDG path test fix)

- Commands: `./scripts/gradlew-local.sh test --tests "*EyeShellPathsTest" --rerun-tasks`; `./scripts/gradlew-local.sh test`; `./scripts/gradlew-local.sh check`; `git diff --check`.
- Executed at: 2026-08-03
- Exit codes: 0 for all commands.
- Result: Replaced the two OS-gated XDG path tests with `@TempDir`-based cross-platform assertions; `EyeShellPathsTest` now runs 6 tests with 0 skipped on this host. Full `test` and `check` passed. Because the new assertions are platform-neutral, the next Windows `gradlew-local.ps1 test` run is expected to also execute `EyeShellPathsTest` 6/6 with 0 skipped.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with XWayland display available.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.

### Ubuntu host validation (M1J Session Lifecycle Status)

- Commands: `./scripts/gradlew-local.sh test --tests "*WorkbenchPanelTest"`; `./scripts/gradlew-local.sh test`; `./scripts/gradlew-local.sh check`; `git diff --check`.
- Executed at: 2026-08-03
- Exit codes: 0 for all commands.
- Result: Added a `SessionStatus` enum and a per-session `awaitExit()`-based exit watcher wired from `EyeShellWindow.attachTerminal`; `WorkbenchPanel` gained a status chip and a status-aware bottom bar. Full suite now runs 79 root tests: 78 passed, 0 failed, and the explicitly opt-in Secret Service live test was skipped as expected; `check` passed. New M1J coverage proves natural exit marks the tab `Exited` without closing the view, explicit close suppresses the pending update, one exited tab leaves another live tab connected, and a connection failure does not overwrite an exited selected session. No dependency, verification metadata, schema, or secret-storage change was introduced.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with XWayland display available.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.

### Ubuntu host validation (M1K Safe Mode)

- Commands: `./scripts/gradlew-local.sh test --tests "*SafeModeTest"`; `./scripts/gradlew-local.sh test`; `./scripts/gradlew-local.sh check`; `git diff --check`.
- Executed at: 2026-08-03
- Exit codes: 0 for all commands.
- Result: Added a `SafeMode` boundary, made `EyeShellTerminalSettings`/`JediTermTerminalView` accept configurable scrollback and refresh rate, and wired `main(args)` to apply safe startup properties before AWT initialization and disable FlatLaf animations. Full suite now runs 85 root tests: 84 passed, 0 failed, and the explicitly opt-in Secret Service live test was skipped as expected; `check` passed. New M1K coverage proves argument detection, safe-vs-default overrides, Windows/Linux startup property maps, and the terminal settings honoring the safe scrollback/refresh-rate. No dependency, verification metadata, schema, or secret-storage change was introduced.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with XWayland display available.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.

### Ubuntu host validation (M1L Wayland/X11 startup strategy)

- Commands: `./scripts/gradlew-local.sh test --tests "*LaunchStrategyTest"`; `./scripts/gradlew-local.sh test`; `./scripts/gradlew-local.sh check`; `git diff --check`.
- Executed at: 2026-08-03
- Exit codes: 0 for all commands.
- Result: Added a `LaunchStrategy` boundary that detects the desktop session and selects the AWT toolkit, moved the Linux `awt.toolkit` override out of `SafeMode`, and wired `main(args)` to apply the toolkit property before AWT initialization. Full suite now runs 91 root tests: 90 passed, 0 failed, and the explicitly opt-in Secret Service live test was skipped as expected; `check` passed. New M1L coverage proves session-type detection, Windows platform-default selection, Linux X11/Wayland selection, Wayland fallback to X11 when the toolkit is unavailable, and Safe Mode forcing the X toolkit. No dependency, verification metadata, schema, or secret-storage change was introduced.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with XWayland display available.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.

### Ubuntu host validation (M1M Packaging)

- Commands: `./scripts/gradlew-local.sh jpackageAppImage jpackageInstaller jpackagePortable` (from a clean `build/package`); `./scripts/gradlew-local.sh test --tests "*PackageArtifactsTest"`; `./scripts/gradlew-local.sh test`; `./scripts/gradlew-local.sh check`; `git diff --check`; `dpkg-deb -I build/package/eyeshell_0.1.0_amd64.deb`; `tar -tzf build/package/eyeShell-0.1.0.tar.gz`.
- Executed at: 2026-08-03
- Exit codes: 0 for all commands.
- Result: `jpackage` produced a bundled-runtime app image with a working `bin/eyeShell` launcher and `lib/runtime`, a valid Linux DEB (`eyeshell_0.1.0_amd64.deb`, `Architecture: amd64`, correct maintainer), and a valid portable `eyeShell-0.1.0.tar.gz`; all three tasks ran successfully from a clean state. Full suite now runs 94 root tests: 93 passed, 0 failed, and the explicitly opt-in Secret Service live test was skipped as expected; `check` passed. New M1M coverage proves snapshot-stripped package version and platform archive naming. The `outputs.dir` caveat (Gradle pre-creates declared output directories, causing `jpackage` to fail with "destination already exists") is documented; the app-image task deletes the destination in `doFirst` instead. No dependency, verification metadata, schema, or secret-storage change was introduced.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with XWayland display available.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Packaging artifacts: `build/package/eyeShell/` (app image), `build/package/eyeshell_0.1.0_amd64.deb`, `build/package/eyeShell-0.1.0.tar.gz`.

## Known Issues

- The Windows environment has been set up and validated, covering: JDK 21 bootstrap, Gradle 9.5.0 dependency verification, code validation, and cross-platform test hardening. Working tree: clean.
- Monitoring, SFTP, and command input remain placeholders; no host metrics or remote file data are fabricated.
- Native Wayland behavior is not covered by the Temurin 21 M0 runtime; the M1L launch strategy probes for `sun.awt.WLToolkit` and falls back to the X toolkit (XWayland) when it is unavailable, so native Wayland selection is unverified until a WLToolkit-capable JBR 25 runtime is evaluated.
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
- M1I supports multiple established terminal tabs but intentionally serializes connection dialogs/attempts. M1J adds natural-exit tab status (`Exited`) but maps any unexpected stop to `Exited`; `Failed`/`Closing` status transitions are not yet produced.
- The M1M packaging tasks' Windows branches (MSI/ZIP) are wired but unverified until run on a Windows host; MSI generation requires the WiX toolset that `jpackage` invokes on Windows.
