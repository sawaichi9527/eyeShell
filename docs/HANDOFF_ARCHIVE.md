# Handoff Archive

Older milestone plans and superseded validation evidence moved out of `handoff.md` on 2026-10-24.
Nothing here is deleted; keep it for traceability and open historical items.

## Completed milestone plans

### M1M Plan (Packaging)

Implemented in the worktree and validated on Ubuntu 26.04 (94 root tests, 93 passed, 1 opt-in skip). Scope delivered: `jpackage` tasks producing a bundled-runtime app image, a Linux DEB (`eyeshell_0.1.0_amd64.deb`), and a portable `eyeShell-0.1.0.tar.gz`, verified by running all three tasks from clean and inspecting the artifacts (`dpkg-deb -I`, `tar -tzf`, launcher + `lib/runtime` presence). The Windows MSI/ZIP branches are wired behind the existing `isWindowsBuild` platform switch but are unverified until run on the Windows host; per the 2026-08-04 decision, their validation is deferred to the formal pre-release milestone (MSI requires the WiX toolset). Development-stage validation uses `gradlew-local.ps1 run` for GUI smoke checks and milestone `jpackageAppImage` bundles. Note: `outputs.dir` must not be declared on the app-image task because Gradle pre-creates declared output directories, which makes `jpackage` report "destination already exists"; the task deletes the destination in `doFirst` instead.

### M1L Plan (Wayland/X11 startup strategy)

Implemented in the worktree and validated on Ubuntu 26.04 (91 root tests, 90 passed, 1 opt-in skip). Scope delivered: `DesktopSession.detect` (Wayland/X11/unknown from `XDG_SESSION_TYPE`, `WAYLAND_DISPLAY`, `DISPLAY`), `LaunchStrategy.resolve` (Windows platform default; Linux native Wayland only when `sun.awt.WLToolkit` is available, otherwise X toolkit; Safe Mode forces X11), and `main(args)` applying the toolkit property before AWT init. Native Wayland remains unverified on a WLToolkit-capable JBR 25 runtime; the current Temurin 21 baseline uses the X toolkit via XWayland.

### M1K Plan (Safe Mode)

Safe Mode is implemented in the worktree and validated on Ubuntu 26.04 (85 root tests, 84 passed, 1 opt-in skip). Scope delivered: `--safe-mode` argument detection, lower scrollback (10,000) and max refresh rate (10), Linux X toolkit forcing / Windows Direct3D+OpenGL disabling, and FlatLaf animation off. Not yet covered: live monitoring charts are still placeholders (nothing to pause), and non-essential extension gating has no extensions to disable yet.

### M1J Plan (handoff to Windows/opencode environment)

Scope confirmed by user: per-tab lifecycle status + repo write only; feature code was NOT implemented on the Linux side. Design direction researched:

- **Detection seam (already confirmed in code):** natural remote exit already flows through the terminal engine — `MinaSshTerminalSession.read()` returns `-1` on EOF (MinaSshConnection.kt:241), the JediTerm emulator loop stops (`TerminalStarter.doStartEmulator`), and `JediTermTerminalView.isSessionRunning` / `widget.isSessionRunning` becomes `false`. M1J only needs to surface this into the tab UI; no engine change is required to *detect* the event. User-initiated close goes through `TerminalSessionPage.close()` (EyeShellWindow.kt:120) → `outputController.close(terminalView::close)`.
- **Suggested mechanism:** background virtual-thread watcher per session page that observes the session/view natural-stop signal and posts an EDT status update; polling `isSessionRunning` is simplest and deterministic-testable (open design question — see below).
- **Status enum:** `CONNECTED`, `EXITED`, `FAILED`, `CLOSING`.
  - `EXITED`: observed clean EOF while the tab remains listed → repaint tab header only, do **not** close the view or remove the tab, so scrollback is preserved.
  - `FAILED`: unexpected stop / transport exception while listed (open design question — may keep minimal and map any stop to `EXITED` for M1J).
  - `CLOSING`: transient during user-initiated teardown.
- **UI wiring:** `WorkbenchPanel.createSessionTabHeader` (EyeShellWindow.kt:259) currently renders a static header; make it status-updatable (small colored status chip beside the title) and update the selected-session bottom bar (`connectionStatus`). Add `WorkbenchPanel.updateSessionStatus(component, status)`.
- **Config to verify on next session:** `/home/sawaichi/.config/opencode/opencode.json*` and `~/.config/opencode/` project/global settings, and `third-party/jediterm` fork pin — confirm the Windows environment uses the same project-local `.local/` JDK/Gradle and vendored submodule.

#### 2026-08-03 conclusion (Windows review, handoff to Ubuntu)

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

## 2026-08-04 GUI review (fixed 2026-10-23)

### Findings

A manual GUI review on the Windows host reported five issues. All five are now implemented on the Ubuntu host; the root causes below are kept for traceability.

| # | Issue | Root cause / current state | Fix |
|---|---|---|---|
| 1 | The Hosts add/edit dialog has no password-save field. | `HostCatalogController.showHostEditor` only collected name/host/port/username/auth/group/tags. | Added a Password field, a `Save password in OS credential store` checkbox, and a `Clear saved password` button. A blank password keeps the existing saved password; Clear marks it for removal on save. Credentials go only to the OS store, never SQLite. |
| 2 | The first connection attempt fails with a `NullPointerException` dialog; retrying succeeds. | The real trigger was the `WorkbenchPanel` tab-change hook passing a null selected component into the non-null `sftpSelectionChanged` callback while the `Start` tab was being removed, so the first `attachTerminal` died inside `JTabbedPane.remove`. | Made the SFTP selection callback nullable, isolated monitor/SFTP initialization failures so they cannot abort terminal attachment, and surfaced the full cause chain in the error dialog. |
| 3 | After connecting to a Linux host, the monitor panel shows no data. | `MonitorCommands.sample()` joined commands with a raw form-feed character, which the remote shell treats as whitespace. | Emitted `printf '\f'` between sections (with a leading `export LC_ALL=C LANG=C`) and added the connected host address to the monitor panel. |
| 4 | There is no `+` button on the tab strip to open a new connection. | `createSessionWorkspace()` rendered only a `JTabbedPane`. | Added a `+` button via FlatLaf's `JTabbedPane.trailingComponent`; it mirrors `Connect...` and is disabled without a connect action or while connecting. |
| 5 | After typing `exit` in the terminal, pressing Enter cannot restore the connection. | An `EXITED` session only updated the status chip and preserved scrollback; there was no reconnect action. | Enter inside an exited session reconnects with the original endpoint/authentication preset in a new tab, keeping the old scrollback; the Enter hook is installed once and removed after use or close. |

Confirmed decisions for the host editor password handling:
- Blank password keeps the existing saved password (no accidental overwrite).
- A `Clear saved password` button forgets the stored credential.
- Remember/clear are only enabled when the OS credential store is available and the host uses PASSWORD authentication.

Open questions handed to the next session:
- Confirm issue #5 behavior: reconnect opens a NEW tab and keeps the old exited tab's scrollback (recommended), rather than replacing the tab.
- Confirm whether editing an existing PASSWORD host should pre-fill the saved password in the dialog (recommended: no, keep it blank and show "Saved password will be used").

## 2026-08-03 Windows handoff (Ubuntu follow-up)

The M1I baseline worktree is clean (`333b6cc`). An initial uncommitted `EyeShellWindow.kt` attempt at M1J was reviewed on the Windows host, found defective (removed the final-tab Start restore, broke `"Connected to X"`/`"Start"` assertions, had a dead and buggy `markSessionExited`, and no status enum/watcher/tests), and was reverted before handoff. The full analysis and the recommended M1J plan are recorded under `### M1J Plan (handoff to Windows/opencode environment)` above.

## Superseded validation evidence (2026-08-02 to 2026-10-24)

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
- Remaining unverified scope on Windows: live GUI launch (development-stage smoke uses `gradlew-local.ps1 run`; milestone bundles use `jpackageAppImage`), native SQLite loading/ACL/reparse points and packaged architecture filtering, Windows Credential Manager, OpenSSH agent named pipe, clipboard and atomic-move behavior, packaging installers (deferred to pre-release), and re-running the cross-platform XDG path test fix on the Windows host (expected `EyeShellPathsTest` 6/6).

### Windows host validation (M1J/M1K/M1L/M1M + cross-platform XDG re-validation)

- Commands: `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" test`; `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" check`; `git diff --check`.
- Executed at: 2026-08-04
- Exit codes: 0 for test and check; diff clean.
- Result: After fast-forwarding to `c1fb045` (which carries the Ubuntu `0e79ce5` XDG fix plus M1J/M1K/M1L/M1M), the full Windows suite runs 94 root tests: 94 executed, 0 failures, 0 errors, 6 skipped (only the opt-in Secret Service live test and SQLite POSIX-gated tests). `EyeShellPathsTest` now runs 6/6 cross-platform with 0 skipped, confirming the Ubuntu-predicted count. `check` passed. No dependency, verification metadata, schema, or secret-storage change was introduced by the Windows re-validation.
- Test environment: Windows host (win32, PowerShell), Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope on Windows: live GUI launch (development-stage smoke uses `gradlew-local.ps1 run`; milestone bundles use `jpackageAppImage`), native SQLite loading/ACL/reparse points and packaged architecture filtering, Windows Credential Manager, OpenSSH agent named pipe, clipboard and atomic-move behavior, and the MSI/ZIP branches of the packaging tasks (MSI requires the WiX toolset; both deferred to the formal pre-release milestone).

### Windows host validation (Windows-specific platform features)

- Commands: `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" test --tests "io.github.sawaichi9527.eyeshell.storage.SqliteHostCatalogTest"`; `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" test --tests "io.github.sawaichi9527.eyeshell.ui.TerminalOutputControllerTest"`; `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" test --tests "io.github.sawaichi9527.eyeshell.secrets.SystemPasswordCredentialStoreTest"` with `EYESHELL_TEST_LIVE_WINDOWS_CREDENTIAL_MANAGER=1`; `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" run` (live GUI smoke); `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" test`; `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" check`; `git diff --check`.
- Executed at: 2026-08-04
- Exit codes: 0 for all test/check/diff commands; the GUI launched and stayed alive until manually closed.
- Result: Added a Windows-gated `SqliteHostCatalogTest` case that opens the catalog at the real `EyeShellPaths.catalogDatabaseFile()` LocalAppData path and round-trips a host, proving native SQLite loads on Windows; added a Windows-gated `TerminalOutputControllerTest` case proving the system clipboard round-trips collected UTF-8 output on Windows (atomic NTFS `ATOMIC_MOVE` was already covered by the existing suite); and added a Windows-gated live `SystemPasswordCredentialStoreTest` case that, when `EYESHELL_TEST_LIVE_WINDOWS_CREDENTIAL_MANAGER=1`, saves/retrieves/updates/forgets a test credential against the real Windows Credential Manager. The live GUI launched via `gradlew-local.ps1 run` with a window titled `eyeShell` and remained alive until closed. The full suite now runs 97 root tests: 97 executed, 0 failures, 0 errors, 7 skipped (6 opt-in/platform-gated plus the env-gated live Windows Credential Manager test that skips unless the env var is set); `check` passed. No dependency, verification metadata, schema, or secret-storage change was introduced.
- Test environment: Windows host (win32, PowerShell), Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope on Windows: the MSI/ZIP branches of the packaging tasks remain deferred to the formal pre-release milestone (MSI requires the WiX toolset).

### Windows host validation (OpenSSH agent named-pipe transport)

- Commands: temporarily `Set-Service ssh-agent -StartupType Automatic` + `Start-Service ssh-agent` (elevated, via UAC), then `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" test --tests "io.github.sawaichi9527.eyeshell.ssh.MinaSshConnectionTest" --rerun-tasks` with `EYESHELL_TEST_LIVE_WINDOWS_AGENT=1`, then `ssh-add -l`, then `Stop-Service ssh-agent -Force` + `Set-Service ssh-agent -StartupType Disabled` (elevated, restored).
- Executed at: 2026-08-04
- Exit codes: 0 for the test run; the service was returned to its original `Stopped`/`Disabled` state and `ssh-add -l` reported no identities afterward.
- Result: Added a Windows-gated live `MinaSshConnectionTest` case that generates a throwaway RSA key, writes it with the OpenSSH writer, loads it into the real `ssh-agent` via `ssh-add`, starts an embedded `SshServer` authorizing that public key, and authenticates over `SshAuthentication.Agent.system()` (which resolves to the `\\.\pipe\openssh-ssh-agent` named-pipe endpoint consumed by `WindowsOpenSshAgent` through `AsynchronousFileChannel`). The test passed (0.595s) and the shell I/O round-tripped `ready\r\n`, proving the named-pipe transport works on Windows 10 LTSC with the pinned Temurin 21 runtime. The test removes the identity (`ssh-add -d`) and deletes the temp key in `finally`; no key or secret is committed. The named-pipe transport is no longer listed as unverified.
- Test environment: Windows 10 LTSC (win32, PowerShell), Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Note: the Windows OpenSSH agent named-pipe access still relies on Temurin/OpenJDK asynchronous file-channel behavior for `\\.\pipe\openssh-ssh-agent`; this is not a portable Java SE named-pipe API, but the live test confirms it works on the pinned runtime.

### Windows host validation (M2 monitor shared transport, parsers, sampler, and panel)

- Commands: `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" test`; `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" check`; `git diff --check`; `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" run` (live GUI smoke).
- Executed at: 2026-08-04
- Exit codes: 0 for test, check, and diff; the GUI launched with the new monitor panel and stayed alive until closed.
- Result: Refactored the SSH transport into a `HostSession` boundary so one `ClientSession` carries a shell channel plus exec channels (terminal close no longer tears down the whole transport). Added a `monitor` package with immutable `MonitorSnapshot` metrics, `LC_ALL=C LANG=C` commands, parsers for `/proc/stat`, `/proc/meminfo` (including swap), `/proc/loadavg`, `/proc/net/dev`, `df -P`, and `ps`, and a `MonitorSampler` that samples a shared exec channel on a background virtual thread with CPU/network delta computation and exponential backoff on failure. Added a `MonitorPanel` on the left side that shows system info, CPU, memory, swap, load, network rates, and top processes, bound to the selected tab: switching tabs switches the monitored session, and closing a tab stops its sampler and resets the panel. An embedded-SSH integration test (`adb9c3a`) starts an in-process `SshServer` that serves Linux-style `/proc`/`df`/`ps` output, connects through the real `MinaSshConnection`, runs the `MonitorSampler`, and renders the snapshot into the `MonitorPanel` on the EDT, asserting the host, CPU, memory, swap, load, network, and process table display correctly. Full suite runs 116 root tests: 116 executed, 0 failures, 0 errors, 8 skipped (platform/opt-in gated); `check` passed; live GUI launched via `gradlew-local.ps1 run`. No dependency, verification metadata, schema, or secret-storage change was introduced.
- Test environment: Windows 10 LTSC (win32, PowerShell), Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining M2 scope: a live end-to-end monitoring check against a real Linux host (on Ubuntu) to confirm real-world output renders; the sampled pipeline itself is already verified end to end on Windows.

### Windows host validation (SFTP on the shared transport)

- Commands: `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" test`; `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" check`; `git diff --check`; `powershell -ExecutionPolicy Bypass -File ".\scripts\gradlew-local.ps1" run` (live GUI smoke).
- Executed at: 2026-08-04
- Exit codes: 0 for test, check, and diff; the GUI launched with the SFTP dock panel and stayed alive until closed.
- Result: Added `sshd-sftp:2.19.0` with SHA-256 verification metadata and a `MinaSftpClient` boundary that reuses the shared `HostSession` transport for SFTP. Added an `SftpController` that runs directory/stat/mkdir/rename/delete and a background transfer queue (uploads and downloads staged through `.part` files, with retry on transient failure and overwrite rejection). Added an `SftpPanel` in the bottom dock bound to the selected tab: it navigates remote directories, lists files, and offers upload, download, new folder, rename, and delete, with a transfer-queue table. An embedded-SSH integration test starts an in-process `SshServer` with the SFTP subsystem and a virtual filesystem, and verifies list/mkdir/upload/download/rename/delete over a real SFTP channel. A `SftpPanel` UI test verifies the disconnected and connected states. The full suite runs 125 root tests: 125 executed, 0 failures, 0 errors, 8 skipped (platform/opt-in gated); `check` passed; live GUI launched via `gradlew-local.ps1 run`. A window-construction guard was added so the selection hooks never reference the not-yet-assigned workbench. No dependency beyond `sshd-sftp`, schema, or secret-storage change was introduced.
- Test environment: Windows 10 LTSC (win32, PowerShell), Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining SFTP scope: drag-and-drop, transfer progress percentages (currently status-only), and a live check against a real Linux host (on Ubuntu).

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

### Ubuntu host validation (fresh clone build environment setup)

- Commands: `./scripts/bootstrap-jdk.sh`; `./scripts/gradlew-local.sh test --console=plain`; `./scripts/gradlew-local.sh check --console=plain`; `git diff --check`.
- Executed at: 2026-10-23
- Exit codes: 0 for bootstrap, test, check, and diff check.
- Result: A fresh clone on this host had no system JDK, so the project-local Temurin 21.0.12+8 was bootstrapped into `.local/jdk-21` with its pinned SHA-256 verified, and Gradle 9.5.0 downloaded into `.local/gradle-home`. The first `test` run failed during configuration because `gradle/verification-metadata.xml` had no checksum for `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.0` (a Kotlin Gradle plugin classpath artifact). The POM was fetched independently from Maven Central and its SHA-256 (`1239e9dbe1397cd5971342956b2511bc3ace7b641842e4372a088dcfa8b9ad55`) matched both the Gradle cache file and the published `.sha1`; that single artifact entry was added, with no verification relaxation. The full suite then ran 125 root tests: 125 executed, 0 failures, 0 errors, 5 skipped (all opt-in/platform-gated: Windows clipboard, Windows OpenSSH agent, Windows data-path SQLite, Windows Credential Manager, and the env-gated live Linux Secret Service test). `check` passed.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope: live GUI launch on this host, live monitoring and SFTP against a real Linux host, and the Windows-gated cases listed above.

### Ubuntu host validation (2026-08-04 GUI review fixes)

- Commands: `./scripts/gradlew-local.sh test --console=plain`; `./scripts/gradlew-local.sh check --console=plain`; targeted `MonitorCommandsTest`, `MonitorIntegrationTest`, `WorkbenchPanelTest`, `HostCatalogControllerTest`, and `SshConnectionControllerTest`; live GUI smoke via `./scripts/gradlew-local.sh run` plus `wmctrl -l`; `git diff --check`.
- Executed at: 2026-10-23
- Exit codes: 0 for test, check, and diff; the GUI was terminated manually after the smoke window appeared.
- Result: Implemented the five GUI review fixes. Monitoring now emits `printf '\f'` delimiters and shows the connected address; the tab strip exposes a `+` button through FlatLaf's trailing component; the host editor stores/keeps/clears passwords through the OS credential store; monitor/SFTP initialization failures can no longer abort terminal attachment and error dialogs include the full cause chain; Enter in an exited session reconnects with the original preset in a new tab. The null-selection NPE that broke the first connection attempt was traced to the non-null `sftpSelectionChanged` callback and fixed. Full suite runs 137 tests: 137 executed, 0 failures, 0 errors, 5 skipped (the same opt-in/platform-gated set). `check` passed; the live GUI launched with a window titled `eyeShell`.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope: the host-editor password dialog and the Enter-reconnect flow were exercised through unit-level credential and key-dispatch tests, not by an automated GUI walkthrough; live monitoring and SFTP against a real Linux host are still unverified; Windows-gated cases remain unverified on this host.

### Ubuntu host validation (live monitoring and SFTP against the real host)

- Commands: `EYESHELL_TEST_LIVE_LINUX_HOST=1 ./scripts/gradlew-local.sh test --tests "*LiveLinuxHostValidationTest" --rerun-tasks`; `./scripts/gradlew-local.sh test --rerun-tasks`; `./scripts/gradlew-local.sh check`; `git diff --check`.
- Executed at: 2026-10-24
- Exit codes: 0 for all commands.
- Result: Added an opt-in `LiveLinuxHostValidationTest` that starts an in-process Apache MINA `SshServer` on `127.0.0.1` with `ProcessShellCommandFactory` and an SFTP subsystem rooted at a temporary directory. Unlike the scripted integration test, the exec channel runs real shell commands on this host, so the monitor samples the real `/proc/stat`, `/proc/meminfo`, `/proc/loadavg`, `/proc/net/dev`, `df -P`, and `ps`; the test also asserts the real shell returns exactly five `printf '\f'` delimiters, closing the earlier scripted-command blind spot. The sampled snapshot matched `/proc/sys/kernel/hostname`, reported at least one CPU core, a valid 0-100% CPU value, positive memory, load, network, at least one filesystem and one process, and the connected address. The SFTP case uploaded a file to the real temp directory, listed it with the expected size, downloaded and compared the contents, renamed it, deleted it, and confirmed the file was gone from disk. Both cases passed. The default full suite now runs 139 tests: 139 executed, 0 failures, 0 errors, 7 skipped (the previous 5 opt-in/platform-gated skips plus the 2 tests that require `EYESHELL_TEST_LIVE_LINUX_HOST=1`). `check` passed.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope: interoperability with a real OpenSSH server (the live server is Apache MINA), live rendering in the monitor panel (the sampler snapshot is asserted; the panel is covered separately by `MonitorIntegrationTest`/`MonitorPanelTest`), and Windows-gated cases on this host.

### Ubuntu host validation (SFTP drag-and-drop upload and transfer progress)

- Commands: `./scripts/gradlew-local.sh test --tests "*SftpControllerTest" --tests "*SftpPanelTest" --tests "*SftpIntegrationTest"`; `./scripts/gradlew-local.sh test --rerun-tasks`; `EYESHELL_TEST_LIVE_LINUX_HOST=1 ./scripts/gradlew-local.sh test --tests "*LiveLinuxHostValidationTest" --rerun-tasks`; `./scripts/gradlew-local.sh check`; `git diff --check`.
- Executed at: 2026-10-24
- Exit codes: 0 for all commands.
- Result: Added byte-level progress callbacks to `SftpClient.upload`/`download` and implemented them in `MinaSftpClient` with a chunked copy loop. `SftpController` publishes 0% when a job starts, increasing percentages throttled to 50 ms, and 100% on completion; the transfer table gained a `Progress` column. `SftpPanel` accepts local-file drops (panel and file-table handlers) and uploads them to the current directory; dropped directories are rejected with a message, and remote path joining was corrected so the root directory produces `/name` instead of `//name`. New tests cover intermediate progress plus the final 100% state, the drop-to-upload flow through the real `TransferHandler` path, and the progress columns. The default suite now runs 141 tests: 141 executed, 0 failures, 0 errors, 7 skipped (the prior opt-in/platform set). The live SFTP validation also passed again with the new progress-aware client. `check` passed.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope: the OS-level drag gesture and Windows drag-and-drop integration were not automated (the `TransferHandler` import path is tested directly); dragging a remote file out to the desktop is intentionally unsupported.

### Ubuntu host validation (OpenSSH interoperability)

- Commands: a temporary OpenSSH 10.2p1 `sshd` on `127.0.0.1:2222` (RSA 3072 host key, public-key-only auth, temporary 700-permission key directory), then `EYESHELL_TEST_OPENSSH=1 EYESHELL_TEST_OPENSSH_PORT=2222 EYESHELL_TEST_OPENSSH_USER=sawaichi EYESHELL_TEST_OPENSSH_KEY=/tmp/eyeshell-keys/client_rsa ./scripts/gradlew-local.sh test --tests "*OpenSshInteroperabilityTest" --rerun-tasks`; `./scripts/gradlew-local.sh test --rerun-tasks`; `./scripts/gradlew-local.sh check`; `git diff --check`.
- Executed at: 2026-10-24
- Exit codes: 0 for all commands; the temporary sshd and key directory were removed afterwards.
- Result: Added an opt-in `OpenSshInteroperabilityTest` (enable with `EYESHELL_TEST_OPENSSH=1`, optional host/port/user/key variables). Against the real OpenSSH server it verified exec (`uname -n` matched the host, the real shell delivered exactly five `printf '\f'` delimiters), live monitor sampling, an interactive shell channel echoing a marker, and the `internal-sftp` subsystem uploading, listing, downloading, renaming, and deleting real files. Both cases passed. The default suite now runs 143 tests: 143 executed, 0 failures, 0 errors, 9 skipped (the prior opt-in set plus the 2 OpenSSH tests). `check` passed.
- Test environment: Ubuntu 26.04 x86_64, OpenSSH 10.2p1 Ubuntu-2ubuntu3.8, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Interoperability gaps found at the time: Apache MINA SSHD 2.19 rejected `ssh-ed25519` private keys ("Unsupported key type (ssh-ed25519)") and could not negotiate a server that offered only an `ssh-ed25519` host key. These gaps are resolved by the Ed25519 support recorded below. Keyboard-interactive and agent authentication against OpenSSH remain unverified.

### Ubuntu host validation (Ed25519 user and host keys)

- Commands: `./scripts/gradlew-local.sh test --tests "*MinaSshConnectionTest"`; `EYESHELL_TEST_OPENSSH=1 EYESHELL_TEST_OPENSSH_PORT=2222 EYESHELL_TEST_OPENSSH_USER=sawaichi EYESHELL_TEST_OPENSSH_KEY=/tmp/eyeshell-keys/id_ed25519 ./scripts/gradlew-local.sh test --tests "*OpenSshInteroperabilityTest" --rerun-tasks`; `./scripts/gradlew-local.sh test --rerun-tasks`; `./scripts/gradlew-local.sh check`; `git diff --check`.
- Executed at: 2026-10-24
- Exit codes: 0 for all commands; the temporary Ed25519-only `sshd` and key directory were removed afterwards.
- Result: Added `net.i2p.crypto:eddsa:0.3.0` (CC0 1.0, no runtime transitive dependencies) with independently verified SHA-256 checksums for its JAR and POM in `gradle/verification-metadata.xml`. MINA's optional EdDSA provider now loads Ed25519 private keys and negotiates Ed25519 host keys. New deterministic coverage generates an Ed25519 key pair, writes an OpenSSH private key, starts an embedded server with an EdDSA host key, and authenticates through `MinaSshConnection`. The live OpenSSH 10.2 check was repeated with an Ed25519-only host key and the existing `~/.ssh/id_ed25519` user key; exec, monitoring delimiters/sampling, the interactive shell, and SFTP all passed. The default suite runs 144 tests: 144 executed, 0 failures, 0 errors, 9 skipped (the opt-in/platform set). `check` passed.
- Test environment: Ubuntu 26.04 x86_64, OpenSSH 10.2p1 Ubuntu-2ubuntu3.8, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope: keyboard-interactive and agent authentication against OpenSSH, Windows Ed25519 handling, and long-term maintenance of the 2019-era EdDSA-Java provider.

### Ubuntu host validation (saved command snippets)

- Commands: `./scripts/gradlew-local.sh test --tests "*CommandsPanelTest" --tests "*SqliteHostCatalogTest" --tests "*WorkbenchPanelTest"`; `./scripts/gradlew-local.sh test --rerun-tasks`; `./scripts/gradlew-local.sh check`; live GUI smoke via `./scripts/gradlew-local.sh run` plus `wmctrl -l`; read-only JDBC inspection of `~/.local/share/eyeShell/eyeshell.db`; `git diff --check`.
- Executed at: 2026-10-24
- Exit codes: 0 for all commands; the GUI was terminated manually after the smoke window appeared.
- Result: Added SQLite schema v3 with a `command_snippets` table (unique non-blank name, non-blank command, STRICT, timestamps) and implemented `CommandSnippetCatalog` CRUD in `SqliteHostCatalog`. Added a `CommandsController` that serializes catalog work on a virtual-thread executor and publishes on the EDT, plus a `CommandsPanel` in the tool dock that lists snippets, previews the command, adds/edits/deletes through dialogs, and runs the selected snippet against the currently selected session (double-click or Run). `TerminalSessionPage.sendCommand` trims trailing newlines and appends one carriage return. The Commands tab is bound to session selection, so switching or closing tabs retargets or disables Run. New tests cover current/next schema versions (3) and table counts, snippet CRUD with reopening, background/EDT controller behavior, panel rendering, Run without a session, and the terminal carriage-return contract. The suite runs 149 tests: 149 executed, 0 failures, 0 errors, 9 skipped. `check` passed; the live GUI launched with a window titled `eyeShell`. A read-only inspection of the existing `~/.local/share/eyeShell/eyeshell.db` after the smoke run confirmed schema version 3 and the `command_snippets` table alongside the host tables, proving the v2-to-v3 migration on a real catalog.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope: the add/edit dialogs were not exercised by an automated GUI walkthrough, snippets are global rather than per-host, and multi-line snippets are sent verbatim.

### 2026-10-24 Session close re-validation

- Commands: `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --console=plain`; `git diff --check`.
- Executed at: 2026-10-24T09:59:33+08:00
- Duration / exit codes: test 92s, exit 0; check 1s, exit 0; diff check exit 0.
- Result: 149 tests: 149 executed, 0 failures, 0 errors, 9 skipped. Skipped cases: Windows clipboard, Windows OpenSSH agent, Windows data-path SQLite, Windows Credential Manager, live Linux Secret Service, 2 `LiveLinuxHostValidationTest` cases, and 2 `OpenSshInteroperabilityTest` cases (all opt-in or platform-gated). HEAD `85ac245`; working tree clean before this documentation update.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Artifacts: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`; command logs under `/tmp/opencode/session-close-test.log` and `/tmp/opencode/session-close-check.log`.
- Not re-run in this session close: the opt-in live Linux/OpenSSH/Secret Service cases and GUI smoke (previously validated on 2026-10-24), Windows-gated cases, and packaging branches. No runtime behavior changed after `85ac245`; this entry records the close-out verification only.
