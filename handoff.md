# Handoff

Older milestone plans and superseded validation evidence are archived in `docs/HANDOFF_ARCHIVE.md` (moved 2026-10-24). The manual GUI walkthrough checklist lives in `docs/MANUAL_GUI_CHECKLIST.md`.

## Current Status

eyeShell has completed Phase 1 and the Phase 2 high-priority set except the items in Deferred / Backlog: SFTP, single-hop Jump Host, Linux agentless monitoring, Command Snippets, and the Saved Hosts folder tree are implemented, validated, and pushed (`28ae170` on `origin/main`).

Current validation baseline (Ubuntu 26.04, 2026-10-27): 232 tests, 0 failures, 0 errors, 12 skips (all opt-in or platform-gated), `check` passes, and the GUI launches. Multi-session resources are characterized: three simultaneous 100,000-line sessions retain 160 MiB inside the default 512 MiB test heap.

Windows 10/11 x64 baseline (2026-10-27): the toolchain was rebuilt from a fresh clone (Temurin 21.0.12+8, Gradle 9.5.0, Kotlin 2.4.10, submodule `third-party/jediterm` at `38413f9`), and the suite now runs 243 tests, 0 failures, 0 errors, 16 skips, with `check` passing, the GUI launching, and the portable package built as `eyeShell-0.1.1.zip`. The owner then ran a real SSH session from that packaged build (terminal, Monitor, SFTP, reconnect). Two platform-coupled tests had to be corrected first (see Known Issues): the Windows data-path SQLite test no longer asserts against, or deletes, the real catalog, and the typography test no longer asserts the family AWT returns for a font that is not installed. The terminal now falls back per glyph (`TerminalFontFallback`), so CJK text, emoji, and Nerd Font icons render even when the primary font lacks them. The window title carries the build version, the Monitor header has a Settings entry (terminal font size, persisted), and the Saved hosts dialog is a searchable tree table.

A UX/robustness change is committed and pushed (`4421b17` on `origin/main`): reconnecting an exited session now happens inside the same tab (the JediTerm widget, its scrollback, highlight rules, and search/selection state survive), Enter stays available for the tab's lifetime, the second/abandoned shell channel opened per connection was removed, the password field shows a `********` mask when a saved password exists, the keyring is no longer unlocked just to discover that nothing is stored, and `Clear Buffer` now clears the visible screen as well as the scrollback. A terminal typography change is committed and pushed (`5844a90`): a resolved CJK-aligned monospace font at 16 pt and an initial 120x30 PTY. SFTP is also committed and pushed (`eaa1bb9`): the file list was being laid out at a negative height, failures were hidden, and the browser now starts in the server home with an inline status line.

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
- Added a `TransportProvider` boundary (`Direct`, `JumpHost`) and routed `MinaSshConnection` through it; a jump host transport is opened first, an ephemeral loopback `direct-tcpip` forwarding tracker is created on it, and the returned `HostSession` owns and releases the target session, the tracker, and the jump connection.
- Pinned Known Hosts verification to the trusted target `host:port`: the verifier accepts an explicit verification address and no longer adds the dialed socket address as an alternate host identity, so jump-host connections never record or match an ephemeral loopback port.
- Added SQLite schema v4 with `hosts.jump_host_uuid`, jump-reference validation (self, dangling, nested chain), and delete protection for profiles used as jump hosts.
- Added a jump host selector to the saved host editor and jump-host credential inputs to the SSH connection dialog (OS credential store password, session-only public key/passphrase, keyboard-interactive, agent) without persisting new secrets.
- Added `HostKeyReview`, which keeps the jump host key separate from the target key and produces a combined single target confirmation screen that restates the jump trust status; changed keys are still rejected for both hops.
- Added deterministic coverage for jump transport routing, Known Hosts identity mapping, changed target keys through a jump host, jump reference validation and delete protection, preset/secret boundaries, and host-key review messaging.
- Added a Swing-free `HostCatalogTree` boundary that groups saved hosts into folders, keeps ungrouped hosts at the tree root, orders groups and hosts case-insensitively with deterministic tie-breaks, and owns the group/host label text.
- Replaced the flat Saved Hosts list with a `JTree` (group folders → host leaves) with a single-selection model, expand-state preservation across reloads, selection restoration after add/edit, and `Add...` pre-filling the selected folder into the Group field.
- Kept `Edit`, `Delete`, and `Connect` limited to host rows; selecting a folder only enables `Add...`, so a folder can never be edited or connected as if it were a host.
- Added deterministic coverage for grouping/ordering/labels plus a dialog-level test proving the tree shape, folder labels, and the host-only action enablement.
- M2 Linux Agentless Monitoring: shared-transport refactor, monitor parsers/sampler, and the selected-tab monitor panel are committed and Windows-validated (116 tests, 8 skips), including an embedded-SSH end-to-end test that confirms sampled output renders in the panel. Live validation against this real Linux host is recorded under Validation Evidence (2026-10-24).
- SFTP: shared-transport SFTP client, controller with a background transfer queue, and the dock SFTP panel are committed and Windows-validated (125 tests, 8 skips). Live SFTP validation against this host's real filesystem is recorded under Validation Evidence (2026-10-24). Drag-and-drop upload and per-transfer progress percentages are implemented and validated (2026-10-24).
- Five GUI review findings from 2026-08-04 are fixed (see `docs/HANDOFF_ARCHIVE.md`); validation is recorded under Validation Evidence (2026-10-23).
- The `+` tab-strip button now sits immediately after the last tab: it lives at the leading edge of a transparent trailing wrapper instead of the far right of the tab area.
- The Linux Secret Service store now unlocks a locked collection on demand (`unlockWithUserPermission`) before reading or writing, so a locked login keyring produces the desktop unlock prompt instead of silently forcing session-only passwords; `Remember` stays available in that state and the hints say saving will ask to unlock. Unlock refusal still fails closed.
- A password used to connect is now kept in the session cache for reconnect when it was not saved to the store (including ad-hoc connections and jump-host passwords), so `exit` followed by Enter no longer asks for the password again.
- M2 Jump Host is implemented and validated on Ubuntu with in-process bastion/target servers and against two real OpenSSH 10.2 servers (2026-10-24).
- Saved Host editor credential UX: the Password field is always editable for Password authentication instead of being disabled whenever the OS credential store is not `AVAILABLE`, so a locked or unavailable keyring no longer blocks password entry. Credential-store state is now three-valued (`AVAILABLE`, `LOCKED`, `UNAVAILABLE`) and each state has its own hint instead of one misleading "unavailable" message.
- Passwords typed in the Saved Host editor when they cannot be stored are held in a new `SessionPasswordCache` (in-memory, per profile, zeroed on delete, authentication change, store save, or application close) and reused by the next SSH connection, including the reconnect-after-exit path; the Connect dialog shows a dedicated session-password hint and withholds `Forget saved password` for session values.
- The new host dialog gained `Connect after saving`, which saves the profile (including the session-only password) and immediately starts the normal connection flow for it.
- Exited-session reconnect now reuses the tab (owner decision 2026-10-26): `TerminalSessionPage.reconnect` swaps the `HostSession`/`TerminalSession` inside the existing page, so the `JediTermWidget`, its 100,000-line scrollback, highlight rules, search/selection state, and context actions survive. `TerminalView` gained `canAttach()` and `resetForReattach()`; `resetForReattach()` returns a widget left on the alternate screen (for example a killed `vim`) to the main buffer before the new shell starts. The tab title follows the endpoint actually used, so editing Host/Port in the reconnect dialog renames the tab.
- Enter-to-reconnect is now installed once per page and removed only when the tab closes, instead of being consumed after the first request. A cancelled or failed reconnect can therefore be retried, and the existing in-flight guard still prevents duplicate connection attempts. A stale exit from the replaced session can no longer mark the reconnected tab `Exited` (`exitGeneration`).
- Removed the abandoned second shell channel per connection: the controller already opens a terminal to prove the session works before saving a password, and the page now attaches that same `TerminalSession` (`attachTerminal(hostSession, terminal, …)`) instead of opening another one. Covered by a test asserting `openTerminal` is not called a second time.
- Host-editor password hint corrected to `A saved password is set. Leave the field blank to keep it; an entered password is used for this session only.` and the saved-password state is now confirmed through a new non-prompting `PasswordCredentialStore.contains(profileId)` instead of assuming every existing Password host has a secret.
- The host editor now shows a `********` FlatLaf placeholder in an empty Password field when a saved password exists, so the user can confirm one is stored without putting the secret (or a fake secret) into the document. Typing replaces the placeholder; leaving it untouched keeps the saved password; `Clear saved password` also hides it.
- The host editor's `Save password in OS credential store` checkbox is now checked by default whenever the OS store is usable, including when editing an existing host. Previously it defaulted to unchecked for edits, so a password typed into the editor was silently kept session-only and disappeared after a restart.
- The connection dialog can now save a password for a profile that has no stored credential yet: `loadStoredPassword` carries the profile revision without reading the store, so ticking `Remember password in OS credential store` on that first connect actually persists it. The checkbox stays unticked by default, so nothing is written unless asked.
- `SshConnectionController.loadStoredPassword` checks `contains` before `retrieve`, so a locked keyring is no longer unlocked (no OS password prompt) merely to discover that a profile has no saved password. Reading an existing credential still unlocks on demand, and saving still unlocks when the user asks to remember.
- The terminal `Clear Buffer` action now calls `TerminalPanel.clearBuffer()` (clear history + visible screen, keep the last line) instead of only `clearHistory()`, which looked like a no-op once the visible screen was full. It stays disabled while the alternate screen is active.
- Reworked the left Monitor sidebar after the FinalShell reference: a two-column GridBag metric grid with aligned values, muted row labels, section rules, coloured CPU/Memory/Swap meters with the percentage inside the bar, a muted absolute-value line (`used / total`), compact Down/Up network rows, a Filesystems section (previously parsed but never shown, capped at four rows), and a Processes table with a visible header and right-aligned numeric columns. Size values now pick GB/MB/KB/B units instead of always printing megabytes, and the duplicated `CPU: CPU …` / `Uptime: Uptime: …` prefixes are gone. Typography now derives from the UI base font only: the panel title is the single larger size, section headings are bold at the base size, and secondary text is distinguished by colour rather than a smaller size (the old 12 pt headings were visibly smaller than the 15 pt body). The sidebar content is wrapped in a `ScrollableContent` inside a `JScrollPane` using FlatLaf's 9 px vertical scrollbar (arrow buttons hidden, horizontal scrolling disabled), so a short window scrolls the panel instead of clipping the Processes section. The idle state keeps the metric skeleton visible — `Host`, `Address`, `OS`, `Kernel`, `Uptime`, `Load`, `CPU`, `Memory`, `Swap`, the `Network` `Down`/`Up` rows, and the `Filesystems`/`Processes` headings all show `—`, followed by a `Not connected` status, a `Use Connect... or Hosts...` hint, and a real `Java … · Linux …` footer — so nothing is fabricated while the full structure stays visible. The `OS` row now shows the distribution parsed from `/etc/os-release` (falling back to the `uname` operating-system field) and a separate `Kernel` row shows the kernel release, instead of concatenating both into `OS`.
- Rebuilt the Windows development environment from a fresh clone (2026-10-27): submodule `third-party/jediterm` at `38413f9`, project-local Temurin 21.0.12+8 and Gradle 9.5.0, strict dependency verification intact, and no global JDK/Gradle/Kotlin required.
- Fixed the Windows data-path SQLite test: it opened the real `%LOCALAPPDATA%\eyeShell\eyeshell.db`, asserted `listHosts().single()`, and deleted the database file at the end, so any machine with real Saved Hosts failed the test and a passing run would have destroyed the user's catalog. It now reloads only the row it created, asserts that row's full round trip, removes just that row in `finally`, and never deletes the database or its journal sidecars.
- Fixed the typography test so it no longer asserts `Font.getFamily()` for a family that is not installed on the host (AWT silently substitutes `Dialog`); the requested family is asserted through `resolveFamily` and the font only for style and size, matching the documented contract in `TerminalTypography`.
- Replaced the Windows portable-ZIP branch's `powershell -NoProfile -Command Compress-Archive` (Windows PowerShell 5.1) with the project-local JDK's `jar --create --no-manifest`, so packaging needs no PowerShell at all and the repository rule against `powershell.exe` 5.1 holds.
- Added per-run terminal font fallback: `EyeShellTerminalWidget` overrides `JediTermWidget.createTerminalPanel()` and `EyeShellTerminalPanel` overrides `getFontToDisplay()`, so a glyph the primary font lacks is drawn with the first installed family that covers the whole run (`TerminalFontFallback`). The chain tries Nerd Font families first, then CJK families, then symbol/emoji families per platform; cell metrics still come from the primary font.
- Kept the fallback logic font-independent and testable: `TerminalGlyphFallback` owns code-point iteration (double-width continuation marker and controls ignored, surrogate pairs treated as one glyph), family ordering, and single-glyph detection, with per-code-point family caching and an ASCII fast path so log-heavy screens pay nothing.
- Added the localized Sarasa family names (`等距更紗黑體 TC`, `等距更纱黑体 SC`) and `Maple Mono NF CN` to the primary preference list, because a zh-TW Windows exposes Sarasa under its Chinese name and the English `Sarasa Mono TC` never matches there.
- Enumerated installed font families once per process in `TerminalTypography`, so opening each session tab no longer scans the system font list.
- Installed two fonts per-user on this Windows host after verifying the vendors' published SHA-256: Sarasa Mono TC 1.0.43 (Regular/Bold, SIL OFL) as the CJK-complete terminal font with an exact 2:1 full-width advance, and Maple Mono NF 7.9 (Regular/Bold, SIL OFL) used only through the fallback chain for Nerd Font icons.
- Added `scripts\gradlew-local.cmd` and `scripts\bootstrap-jdk.cmd` so `cmd.exe` can run the project launchers: `cmd.exe` cannot execute a `.ps1` (the `.ps1` association here is `notepad.exe`, so `.\scripts\gradlew-local.ps1 run` opened the file instead of running it). The wrappers resolve `pwsh` from `PATH`, then `%ProgramFiles%\PowerShell\7`, then the WindowsApps alias, and stop with an explicit error and exit 1 when PowerShell 7 is missing instead of falling back to 5.1. `.gitattributes` now pins `*.cmd` to CRLF.
- Bumped the Gradle project version to `0.1.1-SNAPSHOT` and built the Windows portable package as `eyeShell-0.1.1.zip` (63.1 MB, 448 entries, `app/eyeShell-0.1.1-SNAPSHOT.jar`); the packaged `eyeShell.exe` launches with no JDK on `PATH`.
- Re-checked the saved-host data before packaging: the real catalog holds only the owner's `Freelancer` profile, whose Windows credential is the single `eyeShell/ssh/<uuid>` entry, and `known_hosts` holds only three real LAN hosts. The Windows data-path SQLite test now removes the row it creates, so a forced full suite leaves the catalog unchanged.
- Added the build version to the window title (`eyeShell v0.1.1`): Gradle expands `src/main/resources/eyeshell-build.properties`, and `AppInfo` strips the snapshot suffix the same way the package version does, so the title, the Settings page, and the ZIP file name cannot drift apart.
- Replaced the Saved hosts tree with a FinalShell-style tree table: `HostTableModel` shows Name / Host / Port / User / Authentication / Group, folder rows expand and collapse on click with a drawn disclosure triangle, hosts connect on double-click, and a filter field on top matches name, host, port, user, authentication, or group while keeping matches under their folder.
- Added the gear button at the right edge of the Monitor header, wired to a Settings dialog. Terminal font size is the one implemented setting: it applies to every open session immediately (JediTerm re-initialises its metrics and resizes the PTY) and is persisted to `settings.properties` in the config directory; the language row is visible but disabled with an explicit "not available yet" hint.

## In Progress

Nothing is in progress. The terminal typography and SFTP diagnostics work is committed and pushed (`5844a90`, `eaa1bb9`), and the 2026-10-27 Windows host setup (environment rebuild, two platform-coupled test fixes, portable ZIP packaging) is recorded below. Remaining Phase 2 and Phase 3 candidates are explicitly deferred; see Deferred / Backlog.

## Deferred / Backlog

Deferred by the owner on 2026-10-24. Do not start these without a new explicit decision.

| Item | Note |
|---|---|
| SOCKS5／HTTP CONNECT proxy (M2B) | Not started; would extend `TransportProvider`. |
| Port forwarding (M2C) | Not started; needs schema v5 rules bound to saved hosts. |
| Windows host re-validation (Jump Host, MSI packaging) | The portable ZIP path is validated on Windows, including a live SSH session (2026-10-27). MSI generation is deferred by the owner on 2026-10-27; it requires the WiX toolset that `jpackage` invokes. Jump Host and the live Windows Credential Manager lifecycle are still pending on that host. |
| Broadcast a command to all open sessions | FinalShell's core batch feature; builds on multi-tab + `sendCommand`. |
| Multi-select host connect | Needs a queued connection flow; M1I still serializes connection attempts. |
| Persisted Workspace／Host Set model | FinalShell has no such concept; the folder tree landed instead, and a persisted workspace model is not justified yet. |

## Token Usage Tracking

Convention: at the end of each milestone (or when a session reaches its natural rotation point), run the `tokenscope` tool for that session and append a one-line summary to this section so the project's cumulative token/cost usage stays visible in the handoff instead of being reconstructed from old sessions later.

How to read the figures: tokenscope reports per-session totals (including that session's subagents). To get the project total, sum the `Recorded Total` tokens and `Recorded Cost` lines across sessions listed here. `Cache read` dominates (roughly 84% in this session), which keeps effective cost far below raw-token cost; per-model pricing varies.

Cumulative project usage (each line = one session/milestone):

| Session / milestone | Recorded total tokens | Recorded cost | Date |
|---|---|---|---|
| M2 monitor + SFTP + Windows validation + GUI review (session `ses_03b045f65ffeXifNFIud1iPjQb`) | 236,817,579 (≈84% cache read; 953 provider steps) | $1.10 | 2026-08-06 |

## Next Actions

Active work is validation, quality debt, and documentation (2026-10-24 decision).

- M1J, M1K, M1L, and M1M are implemented and validated on Ubuntu 26.04 and re-validated on the Windows host (97 tests, 7 skips, `check` passed); Windows-specific platform validation is complete except the MSI/ZIP installers, which are deferred.
- M2 monitoring and SFTP are implemented and Windows-validated (125 tests, 8 skips); both are also validated live against this Linux host (2026-10-24). SFTP drag-and-drop upload and transfer progress percentages are implemented (2026-10-24). Command Snippets are implemented with SQLite persistence and session-bound Run (2026-10-24). Saved Hosts folder tree is implemented (2026-10-24).
- Multi-session resources: the opt-in heap probe measures 3 × 100,000-line sessions at 160 MiB retained (53 MiB per session) inside a 512 MiB test heap (2026-10-24). Repeat it with the highlight rule set grown beyond three rules.
- Complete the manual GUI checklist in `docs/MANUAL_GUI_CHECKLIST.md` on both a Wayland/XWayland session and an X11 session.
- Validate the native Wayland path on a WLToolkit-capable JBR 25 runtime (Ubuntu 24.04/26.04 Wayland session) and confirm the X toolkit still launches under XWayland and full X11.
- Validate the build and Swing fallback path on Ubuntu 24.04 X11.
- Validate SQLite native loading under Ubuntu 24.04 X11 and enterprise `noexec` temporary filesystems.
- Validate locked GNOME Keyring behavior and repeat the lifecycle against a Secret Service-compatible KWallet setup.
- Decide whether a future catalog revision may store a Private Key File path reference; M1G intentionally does not persist it.
- Validate keyboard-interactive dialogs against external multi-prompt and MFA-capable SSH servers.
- Validate OpenSSH agent authentication on Ubuntu 24.04/26.04 with a desktop-inherited `SSH_AUTH_SOCK`.
- Decide whether to add AppImage packaging (jpackage app-image wrapped with a pinned `appimagetool` plus an application icon) or keep the DEB / portable tar.gz only.

## Known Issues

- The Windows environment was rebuilt and validated from a fresh clone on 2026-10-27: JDK 21 bootstrap, Gradle 9.5.0 strict dependency verification, 232 tests with 15 skips, `check`, GUI launch, and a jpackage app image plus portable ZIP built from the committed sources. Working tree: clean. The Windows skips are the Linux-only live cases; Jump Host, the live Windows Credential Manager lifecycle, and MSI generation remain unverified on this host.
- The Windows data-path SQLite test writes one row into the real `%LOCALAPPDATA%\eyeShell\eyeshell.db` and removes it again, because its purpose is to prove native SQLite loads through the production path resolution. It can therefore contend with a running eyeShell instance for the database lock, and it leaves the (possibly pre-existing) catalog file in place by design.
- Command snippets are global (not scoped per host or workspace) and Run sends the command text with a single carriage return; multi-line snippets are sent verbatim.
- SFTP drag-and-drop supports local-to-remote upload only; dragging a remote file out to the desktop is not supported (use the Download action).
- Native Wayland behavior is not covered by the Temurin 21 M0 runtime; the M1L launch strategy probes for `sun.awt.WLToolkit` and falls back to the X toolkit (XWayland) when it is unavailable, so native Wayland selection is unverified until a WLToolkit-capable JBR 25 runtime is evaluated.
- Scrollback clearing remains unavailable while alternate screen is active; Copy All and Save All now operate on the retained main buffer.
- Saved Password OS Credential Store integration passed live GNOME Secret Service lifecycle validation but remains unverified on Windows, locked GNOME Keyring, and KWallet; Private Key Passphrases and all other interactive secrets remain session-only.
- A password typed in the Saved Host editor when the store is locked or unavailable is kept in memory for the rest of the application run, not written to SQLite, logs, or the credential store. Deleting the profile, changing its authentication method, saving a password to the store, or closing eyeShell zeroes it; a jump-host password entered through the profile editor is cached the same way.
- The Monitor sidebar is a compact summary: network rates follow the interface selected in the per-interface drop-down (defaulting to the interface that owns the connected IP, otherwise the busiest one), at most four filesystems and six processes are listed, and long host/OS/mount strings are elided with a tooltip.
- Public Key authentication is covered with a runtime-generated encrypted RSA OpenSSH key, a runtime-generated Ed25519 key, and live interoperability against OpenSSH 10.2 with both RSA and Ed25519 user keys. Ed25519 relies on the `net.i2p.crypto:eddsa:0.3.0` provider (CC0 1.0, last released 2019); BouncyCastle is a possible maintained alternative if the provider becomes a concern.
- Changed Host Keys require manual verification and Known Hosts file editing outside eyeShell; automatic replacement is intentionally unavailable.
- Windows OpenSSH agent access relies on Temurin/OpenJDK asynchronous file-channel behavior for `\\.\pipe\openssh-ssh-agent`; this is not a portable Java SE named-pipe API, but the live Windows validation confirmed it works on the pinned Temurin 21 runtime.
- Linux agent authentication requires eyeShell to inherit a valid `SSH_AUTH_SOCK`; eyeShell intentionally does not discover arbitrary sockets or start an agent.
- Save All requires atomic move support in the selected target file system; unsupported providers fail without replacing the existing target.
- Main search/selection results are intentionally not painted over an active alternate screen; they become visible after returning to the main buffer.
- Highlight rules are Current Session only and are intentionally discarded when the application closes; persistent Global/Host/Workspace scopes require a later catalog migration and scope resolver.
- M1G stores authentication method but intentionally does not store Private Key File paths or any authentication secret.
- M1I supports multiple established terminal tabs but intentionally serializes connection dialogs/attempts. M1J adds natural-exit tab status (`Exited`) but maps any unexpected stop to `Exited`; `Failed`/`Closing` status transitions are not yet produced.
- The M1M packaging tasks' Windows branches are wired and the portable ZIP is validated on a Windows host, including a live SSH session (terminal, Monitor, SFTP, reconnect) through the packaged runtime. MSI generation is deferred by the owner (2026-10-27) because it requires the WiX toolset that `jpackage` invokes on Windows; the portable ZIP is the supported Windows distribution meanwhile.
- Jump Host supports exactly one hop. Nested jump hosts are rejected by catalog validation, and an ad-hoc (unsaved) jump host is intentionally unavailable: save it as a Saved Host first.
- A replacement jump-host password typed in the connection dialog is session-only; save jump credentials through the Host editor so they land in the OS credential store.
- Port forwarding rules are not implemented yet (M2C, schema v5 planned) and the SOCKS5/HTTP CONNECT proxy transport (M2B) is not implemented yet.
- Saved Host folders are derived from the hosts that name them: there is no create/rename/delete UI yet, a folder disappears when its last host leaves it, and the Group field in the host editor remains the only way to move a host between folders.
- A folder row in the Saved Hosts tree cannot be connected, edited, or deleted; only `Add...` is enabled there.
- The 2026-08-04 GUI review open questions are resolved (2026-10-26, owner decision): Enter on an exited session reconnects **inside the same tab** instead of opening a new one, and the host editor keeps a saved Password **blank with a hint** rather than pre-filling it. Editing a PASSWORD host therefore never loads the stored secret into a Swing document.
- Reconnecting in place keeps the previous session's output above the new shell output; there is no separator line inserted between sessions (eyeShell does not fabricate terminal output). Sessions that ended mid-line continue from that column.
- If the terminal widget cannot accept the new session (still tearing down its previous emulator), the reconnect transparently falls back to a new tab; the tab-reuse path is best-effort, not guaranteed.
- If the exited tab is closed while the reconnect dialog is open, a successful connection opens a new tab rather than being discarded.
- A reconnect keeps the tab's identity only; the Monitoring panel resets to the idle skeleton and re-samples the new session, and the SFTP panel rebinds to the new connection (the previous controller is closed).
- The keyring existence check is non-prompting, so while the login collection is locked eyeShell does not unlock it to discover a saved password. If the collection cannot list its items while locked, a saved password is not offered until the collection is unlocked by the desktop or by an explicit save; the connect dialog keeps saying the keyring is locked. This trades a possible automatic unlock prompt for the previous behaviour of prompting even when nothing was stored.
- The `********` mask is a FlatLaf placeholder (`JTextField.placeholderText`), not stored text: it never enters the password document, and a non-FlatLaf LAF falls back to an empty field plus the hint text.
- `Clear Buffer` keeps the last line (the upstream JediTerm behaviour) so the prompt line survives; the retained main buffer used by `Copy All Output` is cleared with it.
- Linux packaging is verified for the committed code: the jpackage app image runs with no system JDK, and the portable tar.gz and DEB are built from the same inputs. AppImage packaging is **not implemented**: `jpackage --type` has no AppImage target, so it would need `appimagetool` plus an application icon; the owner deferred that decision on 2026-10-27.
- The terminal font family and size are compile-time defaults (`TerminalTypography.DEFAULT_FONT_SIZE`, `INITIAL_COLUMNS`/`INITIAL_ROWS`); there is no in-app font or zoom control yet. A primary font without CJK now falls back per glyph instead of drawing boxes, but CJK text still looks best with a CJK-complete primary (Sarasa Mono or `fonts-noto-cjk` on Linux). A fallback run is drawn with the fallback font's own ascent/descent, so it can sit a pixel or two off the primary baseline.
- Nerd Font icons only render when a Nerd Font is installed: eyeShell bundles none and Windows ships none, so `U+F0xx` icons stay empty boxes on a machine that has no patched mono font (Maple, JetBrains, Hack, or a Sarasa Nerd variant).
- On a zh-TW Windows, Sarasa exposes its localized family name (`等距更紗黑體 TC`), so `TerminalTypography` lists both the English and localized names; matching only the English name silently skips the font there.
- Installed font families are enumerated once per process, so a font installed while eyeShell is running is only picked up after a restart.
- The Settings dialog currently implements only the terminal font size (10–28 pt). The language row is deliberately disabled with a hint because no translation catalog exists; adding one is a separate piece of work.
- Preferences live in `%APPDATA%\eyeShell\settings.properties` on Windows and `$XDG_CONFIG_HOME/eyeShell/settings.properties` on Linux. Only non-secret values belong there; passwords stay in the OS credential store and the database.
- The Saved hosts tree table has no column-header sorting (rows follow folder/name order) and no drag-to-move between folders; the Group field in the host editor remains the only way to move a host. Folder rows toggle on a single click, and only host rows enable Edit/Delete/Connect.
- `cmd.exe` cannot run `.ps1` files, and on this host `.ps1` is associated with `notepad.exe`; the `.cmd` wrappers exist for that shell. They require PowerShell 7 and fail instead of using Windows PowerShell 5.1.
- Font metrics are computed with a 1x1 `BufferedImage` graphics context, as JediTerm does, so the chosen size assumes the default UI scale; a HiDPI scale factor is not re-applied to the font selection.
- No automated test drives real mouse or keyboard gestures: the GUI checklist in `docs/MANUAL_GUI_CHECKLIST.md` still needs a human pass for dialog flows, drag-and-drop, and visual styling.

## Validation Evidence

### M2 Jump Host transport (implementation)

- Commands: `./scripts/gradlew-local.sh test --tests "*MinaSshConnectionTest" --tests "*HostKeyReviewTest" --tests "*SqliteHostCatalogTest" --tests "*HostCatalogControllerTest" --tests "*SshConnectionControllerTest"`; `./scripts/gradlew-local.sh test`; `./scripts/gradlew-local.sh check`; `git diff --check`.
- Executed at: 2026-10-24T10:34+08:00
- Exit codes: 0 for the targeted tests, the full suite, `check`, and the diff check.
- Result: Added the single-hop jump-host transport provider, SQLite schema v4 jump references, the saved-host jump selector, jump credentials in the connection dialog, and the combined target host-key review. The full suite runs 159 tests: 159 executed, 0 failures, 0 errors, 9 skipped (the existing opt-in/platform-gated set). New coverage: a terminal opened through an in-process bastion to a target server; both presented host keys reported at their real `host:port`; exactly two Known Hosts entries with no ephemeral loopback alias; no re-prompt once both keys are trusted; a changed target key rejected through the jump host; jump reference validation (self, dangling, nested) and delete protection; preset boundaries without secrets; and the host-key review messages.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope: the live jump host item was subsequently validated below (including SFTP/monitoring); Windows re-validation is deferred, and the jump selector/credential dialog click-through is tracked in `docs/MANUAL_GUI_CHECKLIST.md`.

### Ubuntu host validation (live OpenSSH jump host)

- Commands: temporary OpenSSH 10.2p1 `sshd` target on `127.0.0.1:2222` and bastion on `127.0.0.1:2223` (`AllowTcpForwarding yes`, `PermitOpen 127.0.0.1:2222`, `LogLevel DEBUG1`, throwaway Ed25519 user + host keys, 700 key directory), then `EYESHELL_TEST_OPENSSH_JUMP=1 EYESHELL_TEST_JUMP_PORT=2223 EYESHELL_TEST_TARGET_PORT=2222 EYESHELL_TEST_JUMP_KEY=/tmp/.../client_key EYESHELL_TEST_TARGET_KEY=/tmp/.../client_key EYESHELL_TEST_JUMP_LOG=/tmp/.../jump.log ./scripts/gradlew-local.sh test --tests "*LiveJumpHostValidationTest" --rerun-tasks`; `./scripts/gradlew-local.sh test --rerun-tasks`; `./scripts/gradlew-local.sh check`; `git diff --check`.
- Executed at: 2026-10-24T11:05+08:00
- Exit codes: 0 for the live validation, the full suite, `check`, and the diff check; both temporary `sshd` processes and the throwaway keys were removed afterwards.
- Result: Added an opt-in `LiveJumpHostValidationTest` (enable with `EYESHELL_TEST_OPENSSH_JUMP=1`) that connects through a real OpenSSH bastion to a real OpenSSH target. It verified an interactive shell marker, `uname -n`, all monitor delimiters plus a real `MonitorSampler` snapshot, and a full SFTP upload/list/download/rename/delete cycle over the jump transport. It asserted that both hops were verified at their own `host:port` and that Known Hosts contains exactly `[127.0.0.1]:2222` and `[127.0.0.1]:2223` with no loopback forwarding port. The bastion log independently shows `server_input_channel_open: ctype direct-tcpip` and `channel 0: new direct-tcpip` for each connection, proving the target traffic was forwarded rather than dialed directly. Both cases passed.
- Test environment: Ubuntu 26.04 x86_64, OpenSSH 10.2p1 Ubuntu-2ubuntu3.8, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope: the Windows host, a GUI walkthrough of the jump selector and jump credential dialog, a bastion that restricts the target to a non-loopback interface, and jump-host keyboard-interactive/agent credentials against OpenSSH.

### 2026-10-24 Session close (M2 Jump Host)

- Commands: `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --console=plain`; `git diff --check`.
- Executed at: 2026-10-24T12:07:50+08:00
- Duration / exit codes: test 35s, exit 0; check 2s, exit 0; diff check exit 0.
- Result: 161 tests: 161 executed, 0 failures, 0 errors, 11 skipped. The 11 skips are opt-in or platform-gated: Windows clipboard, Windows OpenSSH agent, Windows data-path SQLite, live Windows Credential Manager, live Linux Secret Service, 2 `LiveLinuxHostValidationTest`, 2 `OpenSshInteroperabilityTest`, and the 2 env-gated `LiveJumpHostValidationTest` cases (which passed when run with `EYESHELL_TEST_OPENSSH_JUMP=1` earlier in this session). HEAD `00f18e3`; working tree clean at the start of this close-out.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Artifacts: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`; command logs under `/tmp/opencode/session-close-jump-test.log` and `/tmp/opencode/session-close-jump-check.log`.
- Not re-run in this session close: the opt-in live OpenSSH jump validation (passed earlier in this session and recorded above), the other opt-in live cases, GUI smoke, and all Windows-gated cases. No runtime behavior changed after `00f18e3`; this entry records the close-out verification only.

### Ubuntu host validation (Saved Hosts folder tree)

- Commands: `./scripts/gradlew-local.sh test --tests "*HostCatalogTreeTest" --tests "*HostCatalogControllerTest"`; `./scripts/gradlew-local.sh test --rerun-tasks`; `./scripts/gradlew-local.sh check`; `./scripts/gradlew-local.sh run` (GUI smoke) + `wmctrl -l`; `git diff --check`.
- Executed at: 2026-10-24
- Exit codes: 0 for the targeted tests, the full suite, `check`, and the diff check; the GUI smoke was bounded by `timeout --signal=TERM 25s` and ended with the expected 124.
- Result: Replaced the flat Saved Hosts list with a one-level folder tree. `HostCatalogTree` (no Swing components) groups hosts by their catalog group, keeps ungrouped hosts at the tree root, orders group and host names case-insensitively with an exact tie-break so `Lab` and `lab` never collapse into one entry, and owns the `Group (n)` / `name  user@host:port [tags]` label text. The dialog uses a root-hidden `JTree` with single selection, preserves expanded folders across reloads, restores the previously selected host after add/edit, and pre-fills the selected folder into the Group field on `Add...`. `Edit`/`Delete`/`Connect` are enabled only for host rows. Full suite now runs 167 tests: 167 executed, 0 failures, 0 errors, 11 skipped (the existing opt-in/platform-gated set). New coverage: 5 `HostCatalogTreeTest` cases (ordering, tie-breaks, empty catalog, root layout, labels) and 1 `HostCatalogControllerTest` dialog case asserting the tree shape, folder labels, and host-only action enablement. `check` passed and the live GUI opened a window titled `eyeShell`. No schema, catalog API, dependency, or credential change was introduced.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`.
- Remaining unverified scope: the tree was not clicked through manually (the dialog-level test covers structure and enablement, and the GUI smoke only proves the app launches); folder create/rename/delete and drag-to-move are not implemented; Windows-gated cases were not re-run on this host.

### Ubuntu host validation (multi-session EDT stress and heap probe)

- Commands: `./scripts/gradlew-local.sh test --tests "*JediTermTerminalViewTest"`; `EYESHELL_TEST_HEAP_PROBE=1 ./scripts/gradlew-local.sh test --tests "*JediTermTerminalViewTest" --rerun-tasks`; `./scripts/gradlew-local.sh test --rerun-tasks`; `./scripts/gradlew-local.sh check`; `git diff --check`.
- Executed at: 2026-10-24
- Exit codes: 0 for all commands.
- Result: Added a default-suite test that runs three concurrent `JediTermTerminalView` sessions under continuous output with three highlight rules, asserts the EDT keeps servicing `invokeLater` within 2 s during the stream, and asserts each view exports only its own session output (three distinct exports, each containing its own session marker). Added an opt-in heap probe (`EYESHELL_TEST_HEAP_PROBE=1`) that builds three 100,010-line sessions, waits for the 100,000-line history cap, and reports retained heap after three explicit GCs. Measured **160 MiB retained for 300,000 lines (53 MiB per session) inside a 512 MiB test heap**; the probe fails above a 128 MiB-per-session guardrail. Default suite is now 169 tests: 169 executed, 0 failures, 0 errors, 12 skipped (the 11 existing opt-in/platform-gated cases plus the env-gated heap probe).
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10, default test heap (512 MiB).
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`; the probe figure is printed to the test's `system-out`.
- Remaining unverified scope: the probe covers three sessions with three highlight rules, not a larger rule set, more tabs, or search/selection running concurrently; heap figures are GC-dependent and JVM-specific; Windows was not re-measured; no GUI gesture automation exists.

### 2026-10-24 Session close (Saved Hosts tree, multi-session stress, handoff split)

- Commands: `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --console=plain`; `EYESHELL_TEST_HEAP_PROBE=1 ./scripts/gradlew-local.sh test --tests "*JediTermTerminalViewTest" --rerun-tasks`; `git diff --check`.
- Executed at: 2026-10-24T14:52:33+08:00
- Duration / exit codes: full test 32s, exit 0; check 1s, exit 0; heap probe 26s, exit 0; diff check exit 0.
- Result: Full suite at HEAD `73b476f` runs 169 tests: 169 executed, 0 failures, 0 errors, 12 skipped (Windows clipboard, Windows OpenSSH agent, Windows data-path SQLite, live Windows Credential Manager, live Linux Secret Service, 2 `LiveLinuxHostValidationTest`, 2 `OpenSshInteroperabilityTest`, 2 env-gated `LiveJumpHostValidationTest`, and the env-gated heap probe). `check` passed. The heap probe passed and reported 151 MiB retained for 300,000 lines (50 MiB per session) inside the 512 MiB test heap; the same probe reported 160 MiB (53 MiB per session) earlier in this session, which is normal GC variance and stays far below the 128 MiB-per-session ceiling.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10, default 512 MiB test heap.
- Artifacts: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`; logs under `/tmp/opencode/session-close-tree-test.log`, `/tmp/opencode/session-close-tree-check.log`, and `/tmp/opencode/session-close-tree-heap.log`.
- Not re-run in this close-out: the opt-in live Linux/OpenSSH/Secret Service/jump-host cases, GUI smoke, and all Windows-gated cases. The manual GUI walkthrough in `docs/MANUAL_GUI_CHECKLIST.md` has not been performed by a human; the tree's visual appearance and dialog gestures remain unverified beyond the automated dialog test.

### Ubuntu host validation (Saved Host editor credential UX)

- Commands: `./scripts/gradlew-local.sh compileKotlin compileTestKotlin --console=plain`; `./scripts/gradlew-local.sh test --tests "*HostCatalogControllerTest" --tests "*SshConnectionControllerTest" --console=plain`; `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --console=plain`; `timeout --signal=TERM 25s ./scripts/gradlew-local.sh run`; `wmctrl -l`; `git diff --check`.
- Executed at: 2026-10-24T15:51+08:00
- Exit codes: 0 for compilation, the targeted controller tests, the full suite, `check`, and the diff check; the GUI smoke ended with the expected 124 after `wmctrl` reported the `eyeShell` window.
- Result: The Saved Host editor Password field is now editable whenever authentication is Password, regardless of OS credential store state, and the credential hint distinguishes `AVAILABLE`, `LOCKED`, and `UNAVAILABLE`. Input that cannot be stored is cached per profile by the new `SessionPasswordCache` and reused by `SshConnectionController` (including reconnect), never persisted, and zeroed on profile delete, authentication change, successful store save, or controller close. `Connect after saving` was added to the new-host dialog. Full suite runs 180 tests: 180 executed, 0 failures, 0 errors, 12 skipped (the existing opt-in/platform-gated set). The two controller test classes now run 20 and 11 tests; new coverage asserts locked/unavailable/available hint states, that a locked keyring keeps the field enabled, session-only caching and zeroing, session precedence over a stored password, cache invalidation on save/delete, and controller-close clearing.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Runtime probe: a classpath probe against the exported `SystemPasswordCredentialStore` reported `status=LOCKED` on this host (the default `login` collection reports `Locked=true` over D-Bus), confirming the three-state path is the one this environment exercises.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`; GUI smoke log at `/tmp/opencode/eyeshell-run.log`.
- Remaining unverified scope: the dialog was not clicked through by hand (no mouse/keyboard automation exists); unlocking the keyring and then saving/reading back a password was not repeated in this session; Windows was not re-run.

### Ubuntu host validation (Monitor sidebar layout)

- Commands: `./scripts/gradlew-local.sh compileKotlin --console=plain`; `./scripts/gradlew-local.sh test --tests "*MonitorPanelTest" --tests "*MonitorIntegrationTest" --tests "*WorkbenchPanelTest" --console=plain`; `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --console=plain`; `timeout --signal=TERM 22s ./scripts/gradlew-local.sh run` + `wmctrl -l`; `git diff --check`.
- Executed at: 2026-10-24T16:30+08:00
- Exit codes: 0 for compilation, the targeted monitor tests, the full suite, `check`, and the diff check; the GUI smoke ended with the expected 124 and `wmctrl` reported the `eyeShell` window.
- Result: Rebuilt `MonitorPanel` as an aligned GridBag layout with section rules, muted labels, coloured meters, a Filesystems section, and a headed Processes table; size formatting now selects B/KB/MB/GB. Full suite runs 183 tests: 183 executed, 0 failures, 0 errors, 12 skipped (the existing opt-in/platform-gated set). New `MonitorPanelTest` cases cover GB formatting, the removed duplicated `Uptime` prefix, filesystem and CPU-detail rendering, the process table values, and the idle state. Layout was additionally rendered offscreen to `/tmp/opencode/monitor-preview.png` (full snapshot), `monitor-idle.png`, `monitor-minimal.png`, `monitor-narrow.png` (204 px), and `monitor-wide.png` (320 px) with FlatDarkLaf and reviewed visually.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`; GUI smoke log at `/tmp/opencode/eyeshell-run2.log`.
- Remaining unverified scope: the panel was not checked against a live SSH session in this change (the integration test renders a sampled snapshot headlessly); the screenshots use synthetic values; Windows and high-DPI font scaling were not rendered; no mouse/keyboard gesture automation exists.

### Ubuntu host validation (Monitor network selector, address copy, load placement)

- Commands: `./scripts/gradlew-local.sh test --tests "*MonitorPanelTest" --tests "*MonitorParsersTest" --tests "*MonitorCommandsTest" --tests "*MonitorIntegrationTest" --console=plain`; `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --console=plain`; `timeout --signal=TERM 22s ./scripts/gradlew-local.sh run` + `wmctrl -l`; `git diff --check`.
- Executed at: 2026-10-24T17:05+08:00
- Exit codes: 0 for the targeted monitor tests, the full suite, `check`, and the diff check; the GUI smoke ended with the expected 124 and `wmctrl` reported the `eyeShell` window.
- Result: The Network section gained a per-interface drop-down that defaults to the interface owning the connected IP (new `ip -o -4 addr show` sample section parsed into `NetworkUsage.interfaceAddresses`) and otherwise falls back to the busiest non-loopback interface; switching it re-renders that interface's Down/Up rates. The Address value drops the `:port`; `Host`, `Address`, `OS`, and `Uptime` are copy targets — clicking either the label or the value puts the text on the clipboard and tints the value green for a moment, with no separate copy button. A 6 px top spacer plus the 12 px panel border keeps the `Monitor` title clear of the window edge, and the idle skeleton is rebuilt in `addNotify` so the LAF's final font scale applies to it. `Load` sits above CPU and keeps its explanatory tooltip plus an amber warning when the 1-minute value exceeds the CPU core count. Full suite runs 187 tests: 187 executed, 0 failures, 0 errors, 12 skipped (the existing opt-in/platform-gated set). New coverage: interface-address parsing (including a missing `ip` command), the default selector matching the endpoint IP, switching interfaces re-renders the rates, the address stripping and copy button, and the integration fixture now carries an `ip` section and asserts `eth0` is selected for `127.0.0.1`.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Visual check: offscreen FlatDarkLaf renders were regenerated at `/tmp/opencode/monitor-preview.png` (and the idle/minimal/narrow/wide variants) to confirm the selector, copy button, and Load position.
- Test report: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`; GUI smoke log at `/tmp/opencode/eyeshell-run3.log`.
- Remaining unverified scope: clipboard copy was not exercised through a real mouse click (the button component is asserted, the clipboard write path is covered only by the code path itself), the selector was not driven against a real multi-NIC host, and Windows/high-DPI were not re-checked.

### Ubuntu host validation (Monitor typography)

- Commands: `./scripts/gradlew-local.sh test --tests "*MonitorPanelTest" --console=plain`; `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --console=plain`; `git diff --check`.
- Executed at: 2026-10-24T17:35+08:00
- Exit codes: 0 for the targeted test, the full suite, `check`, and the diff check.
- Result: A font dump of the rendered sidebar showed the old scale mixed absolute sizes: title 14 pt, row labels 15 pt, secondary detail lines 14 pt, and section headings 12 pt. The panel now derives every font from `UIManager.getFont("Label.font")`: title = base + 2 bold, headings = base bold, row labels/values/secondary/detail/table header = base, with hierarchy carried by colour. A new `MonitorPanelTest` case asserts the CPU detail, Memory, Swap, and Filesystems labels all share the row-label font size. Full suite runs 188 tests: 188 executed, 0 failures, 0 errors, 12 skipped.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Visual check: `/tmp/opencode/monitor-preview.png`, `monitor-idle.png`, `monitor-minimal.png`, `monitor-narrow.png`, `monitor-wide.png` regenerated after the change.
- Remaining unverified scope: high-DPI/font-scale settings other than the current desktop were not rendered; Windows was not re-checked.

### Ubuntu host validation (Monitor vertical scrolling)

- Commands: `./scripts/gradlew-local.sh test --tests "*MonitorPanelTest" --tests "*MonitorIntegrationTest" --tests "*WorkbenchPanelTest" --console=plain`; `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --console=plain`; `timeout --signal=TERM 22s ./scripts/gradlew-local.sh run` + `wmctrl -l`; `git diff --check`.
- Executed at: 2026-10-24T18:05+08:00
- Exit codes: 0 for the targeted tests, the full suite, `check`, and the diff check; the GUI smoke ended with the expected 124 and `wmctrl` reported the `eyeShell` window.
- Result: The sidebar grid moved into a `ScrollableContent` view that tracks the viewport width, wrapped in a `JScrollPane` with `VERTICAL_SCROLLBAR_AS_NEEDED`, `HORIZONTAL_SCROLLBAR_NEVER`, FlatLaf arrow buttons disabled, and a 9 px scrollbar width set on that instance only (no global `UIManager` change). Short windows now scroll the panel instead of clipping Processes; at full height no scrollbar is shown. Full suite runs 189 tests: 189 executed, 0 failures, 0 errors, 12 skipped. A new `MonitorPanelTest` case asserts the scroll policies, the <= 10 px scrollbar width, and that the content is taller than a short viewport.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Visual check: `/tmp/opencode/monitor-short.png` (244x420, scrollbar visible) and `/tmp/opencode/monitor-preview.png` (244x812, no scrollbar) rendered with FlatDarkLaf; the thumb was inspected at 4x zoom.
- Remaining unverified scope: mouse-wheel scrolling over the nested Processes table was not exercised interactively, and Windows/high-DPI were not re-checked.

### Ubuntu host validation (Monitor idle skeleton)

- Commands: `./scripts/gradlew-local.sh test --tests "*MonitorPanelTest" --console=plain`; `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --console=plain`; `timeout --signal=TERM 22s ./scripts/gradlew-local.sh run` + `wmctrl -l`; `git diff --check`.
- Executed at: 2026-10-24T18:40+08:00
- Exit codes: 0 for the targeted test, the full suite, `check`, and the diff check; the GUI smoke ended with the expected 124 and `wmctrl` reported the `eyeShell` window.
- Result: The pre-connection Monitor panel now renders the same row skeleton with `—` placeholders (`Host`, `Address`, `OS`, `Uptime`, `Load`, `CPU`, `Memory`, `Swap`, `Network` → `Down`/`Up`) plus a `● Not connected` status, a short connect hint, and a real `Java … · Linux …` footer. No metric values, bars, filesystems, or processes are shown while disconnected, keeping `PRODUCT_SPEC.md` §6.1 ("no fabricated CPU/memory/network/disk data") and §6.2 ("no permanent host manager in the Monitor column") satisfied. Full suite runs 189 tests: 189 executed, 0 failures, 0 errors, 12 skipped; the idle-state test now asserts the retained labels, the status line, and the absence of the previous session's address.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Visual check: `/tmp/opencode/monitor-idle.png` (244x760) regenerated with FlatDarkLaf.
- Remaining unverified scope: the idle panel was not screenshotted from the live app window (offscreen render only); Windows/high-DPI were not re-checked.

### Ubuntu host validation (Monitor copy targets and top spacing)

- Commands: `./scripts/gradlew-local.sh test --tests "*MonitorPanelTest" --console=plain`; `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --console=plain`; `git diff --check`.
- Executed at: 2026-10-24T22:10+08:00
- Exit codes: 0 for the targeted test, the full suite, `check`, and the diff check.
- Result: The `Copy` button was removed. `Host`, `Address`, `OS`, and `Uptime` are now copy targets: a left click on either the row label or the value writes that value to the clipboard and tints the value green for 1.2 s, and both carry a `Click to copy: <value>` tooltip and a hand cursor. A 6 px spacer row plus the 12 px content border puts the `Monitor` title 18 px below the panel top (a layout dump confirms `title.y = 18`), and `addNotify` rebuilds the idle skeleton once the LAF has applied its final font scale. Full suite runs 189 tests: 189 executed, 0 failures, 0 errors, 12 skipped; the panel test now asserts the copy button is gone, the address label uses a hand cursor, and its tooltip carries the value.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Visual check: `/tmp/opencode/monitor-idle.png` (244x760) regenerated; the connected layout was verified through a component-bounds dump because the offscreen print harness resolves LAF fonts differently from a shown window.
- Remaining unverified scope: the clipboard write itself was not performed in the automated test (to avoid overwriting the desktop clipboard); mouse-wheel scrolling and Windows/high-DPI were not re-checked.

### Ubuntu host validation (OS and Kernel rows)

- Commands: `./scripts/gradlew-local.sh test --tests "*MonitorParsersTest" --tests "*MonitorIntegrationTest" --tests "*MonitorPanelTest" --console=plain`; `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --console=plain`; `timeout --signal=TERM 22s ./scripts/gradlew-local.sh run` + `wmctrl -l`; `git diff --check`.
- Executed at: 2026-10-24T22:40+08:00
- Exit codes: 0 for the targeted tests, the full suite, `check`, and the diff check; the GUI smoke ended with the expected 124 and `wmctrl` reported the `eyeShell` window.
- Result: `systemInfo()` now also reads `/etc/os-release`; `parseSystemInfo` fills a new `SystemInfo.distribution` (PRETTY_NAME, then NAME + VERSION) and leaves it empty when the file is missing. The panel shows `OS` = distribution with a fallback to the `uname` operating-system field, plus a separate `Kernel` row, instead of concatenating kernel into OS. Both rows are copy targets and the idle skeleton includes `Kernel`. `PRODUCT_SPEC.md` §13.1 lists `/etc/os-release`. Full suite runs 192 tests: 192 executed, 0 failures, 0 errors, 12 skipped. New coverage: os-release parsing, the no-os-release fallback, and panel assertions that OS and Kernel are separate.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Visual check: `/tmp/opencode/monitor-preview.png` and `monitor-idle.png` regenerated; OS shows `Ubuntu 26.04.1 LTS` and Kernel shows `7.0.0-38-generic`.
- Remaining unverified scope: `/etc/os-release` was not sampled from a live SSH session in this change (the embedded integration fixture supplies it); Windows hosts are not covered by the agentless monitor.

### Ubuntu host validation (idle skeleton sections)

- Commands: `./scripts/gradlew-local.sh test --tests "*MonitorPanelTest" --console=plain`; `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --console=plain`; `git diff --check`.
- Executed at: 2026-10-24T23:00+08:00
- Exit codes: 0 for the targeted test, the full suite, `check`, and the diff check.
- Result: The pre-connection skeleton now also renders the `Filesystems` and `Processes` headings with a `—` placeholder row, matching the connected layout; no values are fabricated. Full suite runs 192 tests: 192 executed, 0 failures, 0 errors, 12 skipped, and the idle-state test asserts both headings are present.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Visual check: `/tmp/opencode/monitor-idle.png` (244x760) regenerated with FlatDarkLaf.

### Ubuntu host validation (password persistence, tab strip, reconnect)

- Commands: `./scripts/gradlew-local.sh test --tests "*SystemPasswordCredentialStoreTest" --tests "*SshConnectionControllerTest" --tests "*HostCatalogControllerTest" --tests "*WorkbenchPanelTest" --console=plain`; `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --console=plain`; `timeout --signal=TERM 22s ./scripts/gradlew-local.sh run` + `wmctrl -l`; `git diff --check`.
- Executed at: 2026-10-24T23:12+08:00
- Exit codes: 0 for the targeted tests, the full suite, `check`, and the diff check; the GUI smoke ended with the expected 124 and `wmctrl` reported the `eyeShell` window.
- Result: (1) `LinuxSecretServicePasswordStore` unlocks a locked default collection before any operation via the new `SecretCollection.unlock()` (`unlockWithUserPermission`), so a locked login keyring now raises the desktop unlock prompt instead of forcing session-only passwords; an unlock refusal still throws and the UI reports the failure, and `Remember` stays enabled for the `LOCKED` state with updated hints. (2) The `+` button is wrapped in a transparent `BorderLayout` panel at the leading edge of the tab strip's trailing slot, so it renders immediately after the last tab. (3) `SshConnectionController.rememberForReconnect` caches the connect password (and jump-host password) when it was not persisted, keyed by the same profile id used by the reconnect preset, so `exit` + Enter reuses it. Full suite runs 197 tests: 197 executed, 0 failures, 0 errors, 12 skipped. New coverage: unlock-before-write, fail-closed when the unlock is refused, reconnect password caching (and no duplicate cache when the store save succeeded), and the trailing-wrapper arrangement of the `+` button.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10.
- Remaining unverified scope: the GNOME unlock prompt itself was not exercised interactively (that would pop a desktop dialog); the `+` position was verified structurally and with a FlatLaf layout probe rather than a screenshot; Windows credential storage was not re-run.

### 2026-10-24 push

- Commands: `git push origin main`.
- Result: pushed `05309a1..28ae170` (4 commits: credential handling, monitor sidebar, new-tab placement, documentation) to `origin/main`, then `28ae170..b9c81ac` for the handoff baseline refresh; working tree clean and `main` in sync with `origin/main` afterwards at 2026-10-24T23:16+08:00.
- Not re-run for the push: no code changed after the validated `197 tests / 0 failures / 12 skipped` run recorded above, except this documentation update.

### 2026-10-24 Session close (credential handling, monitor sidebar, push)

- Commands: `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --console=plain`; `git diff --check`.
- Executed at: 2026-10-24T23:18:41+08:00
- Duration / exit codes: test 31s, exit 0; check 2s, exit 0; diff check exit 0.
- Result: HEAD `b9c81ac`, working tree clean and `main` in sync with `origin/main`. Full suite runs 197 tests: 197 executed, 0 failures, 0 errors, 12 skipped (Windows clipboard, Windows OpenSSH agent, Windows data-path SQLite, live Windows Credential Manager, live Linux Secret Service, 2 `LiveLinuxHostValidationTest`, 2 `OpenSshInteroperabilityTest`, 2 env-gated `LiveJumpHostValidationTest`, and the env-gated heap probe). `check` passed with no task re-execution needed.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10, default 512 MiB test heap.
- Artifacts: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`; logs at `/tmp/opencode/close-test.log`, `/tmp/opencode/close-check.log`, and `/tmp/opencode/close-diffcheck.log`.
- Not re-run in this close-out: the opt-in live Linux/OpenSSH/Secret Service/jump-host cases, the interactive GNOME keyring unlock prompt, the manual GUI walkthrough, and all Windows-gated cases. The monitor panel was verified offscreen and by component-bounds dumps, not on a shown window.
- Token usage: `tokenscope` was not available in this session, so no row was appended to Token Usage Tracking; the cumulative table therefore still stops at the 2026-08-06 session.

### Ubuntu host validation (same-tab reconnect, Enter retry, single shell channel)

- Commands: `./scripts/gradlew-local.sh compileKotlin compileTestKotlin`; `./scripts/gradlew-local.sh test --tests "*JediTermTerminalViewTest"`; `./scripts/gradlew-local.sh test --tests "*WorkbenchPanelTest"`; `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh run` (GUI smoke) + `wmctrl -l`; `git diff --check`.
- Executed at: 2026-10-26
- Exit codes: 0 for compilation, both targeted test runs, the full suite, `check`, and the diff check; the GUI smoke was started in the background and its `eyeShell` window was confirmed with `wmctrl -l`, then the process was terminated.
- Result: Exited-session reconnect now happens inside the same tab. `TerminalView` gained `canAttach()`/`resetForReattach()`; `JediTermTerminalView` implements them over `JediTermWidget.canOpenSession()` and `TerminalTextBuffer.useAlternateBuffer(false)`. `TerminalSessionPage` swaps `HostSession`/`TerminalSession` in place, re-arms its exit watcher through an `exitGeneration` guard, rebuilds the `MonitorSampler`, closes the previous SFTP controller, and closes the replaced connection. `EyeShellWindow.reconnectTerminal` renames the tab to the endpoint that was actually used, restores the `Connected` status, and rebinds Monitoring/SFTP for the selected tab. `WorkbenchPanel` gained `updateSessionTitle` with stable title/close-button references. Enter is installed once per page and removed only on close, so a cancelled or failed attempt can be retried. `SshConnectionController` now passes the already-opened `TerminalSession` into the page, removing the second (previously abandoned) shell channel per connection. The host-editor hint is now `Leave blank to keep a saved password…`.
- Full suite runs 207 tests: 207 executed, 0 failures, 0 errors, 12 skipped (the existing opt-in/platform-gated set: Windows clipboard, Windows OpenSSH agent, Windows data-path SQLite, live Windows Credential Manager, live Linux Secret Service, 2 `LiveLinuxHostValidationTest`, 2 `OpenSshInteroperabilityTest`, 2 env-gated `LiveJumpHostValidationTest`, and the env-gated heap probe). New coverage: a real JediTerm widget reattaches a second synthetic session and keeps the first session's scrollback; reattach preparation leaves an alternate screen; page-level in-place reconnect keeps the view and closes the replaced connection; reconnect is refused when the view is busy; a stale exit from the replaced session cannot mark the tab exited; the window reuses the tab, renames it, and never calls `openTerminal` a second time; Enter fires on every press, survives a refused request, is ignored after close, and never reconnects a live session.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10, default 512 MiB test heap.
- Artifacts: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`; logs at `/tmp/opencode/reconnect-test.log`, `/tmp/opencode/reconnect-check.log`, and `/tmp/opencode/reconnect-run2.log`.
- Remaining unverified scope: no automated test drives real mouse/keyboard gestures, so the click-through of Enter → dialog → Cancel → Enter again, the visual continuity of the old scrollback above the new shell, and the alternate-screen reconnect after a killed `vim` still need a human pass from `docs/MANUAL_GUI_CHECKLIST.md` §2 and §5. The reconnect path was not exercised against a live external SSH server in this change (embedded/in-process fakes only), Windows was not re-run, and `tokenscope` was unavailable so no Token Usage row was appended.

### Ubuntu host validation (password mask, keyring prompt, Clear Buffer)

- Commands: `./scripts/gradlew-local.sh test --tests "*JediTermTerminalViewTest" --tests "*HostCatalogControllerTest" --tests "*SshConnectionControllerTest" --tests "*SystemPasswordCredentialStoreTest"`; `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --rerun-tasks --console=plain`; `git diff --check`.
- Executed at: 2026-10-26
- Exit codes: 0 for the targeted tests, the full suite, `check`, and the diff check.
- Result: (1) The Saved Host editor shows a `********` placeholder in an empty Password field only when `PasswordCredentialStore.contains(profileId)` confirms a saved secret; the value never enters the document, typing replaces it, and `Clear saved password` hides it. The same mask is shown in the connection dialog's Password field (and the jump-host password field) whenever a stored or session password will be used. The hint now reads `A saved password is set.…` and the old `existingHost` proxy is gone, so a Password host without a stored secret shows the session-only hint instead. (2) `loadStoredPassword` calls the new non-prompting `contains()` before `retrieve()`, so a locked keyring is no longer unlocked (no OS password prompt) just to find out that a profile has no saved password; `LinuxSecretServicePasswordStore.contains` lists items without calling `unlock()`, and `WindowsPasswordCredentialStore.contains` uses `CredReadW`/`CredFree` without decoding the blob. A live read-only probe on this host reported `status=AVAILABLE`, `contains(random)=false`, and no unlock. (3) `Clear Buffer` now calls `TerminalPanel.clearBuffer()`, so it clears the visible screen and the scrollback and keeps the last line; it was previously `clearHistory()` only, which cleared nothing visible and looked like a no-op.
- Full suite runs 213 tests: 213 executed, 0 failures, 0 errors, 12 skipped (the existing opt-in/platform-gated set). New coverage: the placeholder appears only for an enabled Password field with a confirmed saved secret; a locked Linux collection is not unlocked by an existence check; the Windows existence check works without reading the secret; a locked store with no stored credential is never read; the connect path still skips a saved credential when the store reports none; `Clear Buffer` empties the history and the visible screen through the real JediTerm widget.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10, default 512 MiB test heap. The placeholder was additionally rendered offscreen with FlatDarkLaf to `/tmp/opencode/password-placeholder.png` and inspected.
- Artifacts: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`; logs at `/tmp/opencode/fix-test.log`.
- Remaining unverified scope: the locked-keyring case was exercised with a fake `SecretCollection` and a live read-only probe on an unlocked collection, not by locking the real login keyring; whether a real locked GNOME collection lists its items to the existence check is unverified, so a saved password may not be offered while the ring is locked. The mask and the Clear Buffer result were verified offscreen/widget-level, not by hand in a shown window; Windows was not re-run (the Windows existence path is covered only by its fake).

### Diagnosis: "Host -> Edit shows an empty Password field"

- A live read-only inspection of this host (2026-10-26) found **no eyeShell credential at all** in the OS keyring: `SimpleCollection().getItems({"application":"eyeShell"})` returned an empty list, and `contains()`/`retrieve()` for the saved host `Gorgon Halo` (`db0a0da5-cac3-4434-a0e5-f38c780919bb`, PASSWORD) reported `false`/absent while `status()` was `AVAILABLE`. The blank field was therefore correct: there was nothing stored to mask.
- Root cause of the missing credential: the host editor's `Save password in OS credential store` checkbox defaulted to unchecked when editing an existing host, so a password typed in Edit mode was stored only in `SessionPasswordCache` and vanished on restart. It now defaults to checked whenever the store is usable (Add and Edit alike).
- `contains()` was verified against the live service with a disposable random profile: `false` -> save -> `true` -> forget -> `false`. The item was deleted and its `CharArray` zeroed in `finally`; no secret was printed.
- Remaining caveat: a credential saved by an older build before this fix does not exist, so a host used with a session-only password still shows a blank field until it is saved once through the editor or the connect dialog's `Remember` option.

### Ubuntu host validation (saved-password mask follow-up)

- Commands: `EYESHELL_TEST_LIVE_SECRET_SERVICE=1 ./scripts/gradlew-local.sh test --tests "*SystemPasswordCredentialStoreTest" --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --rerun-tasks --console=plain`; `git diff --check`.
- Executed at: 2026-10-26
- Exit codes: 0 for the live Secret Service test, the full suite, `check`, and the diff check.
- Result: The host editor's `Save password in OS credential store` checkbox now defaults to checked whenever the OS store is usable, including edits, and the connection dialog can persist a first credential because `loadStoredPassword` carries a profile revision for the empty case. The opt-in `live Linux Secret Service supports the credential lifecycle` test now also asserts `contains()` transitions (`false` -> save -> `true` -> forget -> `false`) against the real service; it passed with a disposable random profile that was deleted in `finally` and whose arrays were zeroed. No eyeShell credential existed on this host before the run, which explains the empty Password field that prompted this follow-up.
- Full suite runs 213 tests: 213 executed, 0 failures, 0 errors, 12 skipped (the live Secret Service case stays skipped in the default suite).
- Remaining unverified scope: the dialog itself was not clicked through; the mask is proven by the pure placeholder function plus an offscreen FlatLaf render, and `contains()` by the live lifecycle test. Windows was not re-run.

### 2026-10-26 commit and push

- Commands: `git add -A`; `git commit` (single commit); `git push origin main`.
- Result: pushed `459e209..4421b17` to `origin/main` — 16 files, 896 insertions, 138 deletions. `main` is in sync with `origin/main` afterwards; working tree clean at 2026-10-26T22:35+08:00.
- Not re-run for the push: no code changed after the validated `213 tests / 0 failures / 12 skipped` run recorded above, except these documentation updates.
- Terminal typography is now an explicit policy (`terminal/TerminalTypography.kt`) instead of JediTerm's `Monospaced`/Consolas default: the first installed family from a per-platform list (`Sarasa Mono TC/SC`, `Noto Sans Mono CJK TC/SC`, then Cascadia/Noto/DejaVu/Liberation/Ubuntu, then the logical `Monospaced`) is used at 16 pt. Sarasa and Noto CJK advance full-width glyphs at exactly two cells, which removes the gaps Chinese/Japanese/Korean text had with the logical font. Resolution never trusts `Font(family)` (an unknown family silently becomes `Dialog`) and falls back to `Monospaced` when nothing matches.
- The shell is opened at the terminal's initial size (120x30) instead of 80x24, so the first prompt already matches the workbench width; JediTerm still resizes the PTY to the real component size on layout. At a 1280 px window this is about 129 columns at 16 pt.
- `JediTermTerminalView` accepts an optional `font` for tests; `EyeShellTerminalSettings` reports that font through `getTerminalFont()`/`getTerminalFontSize()`.

### Ubuntu host validation (terminal typography)

- Commands: `./scripts/gradlew-local.sh test --tests "*TerminalTypographyTest" --tests "*JediTermTerminalViewTest" --tests "*SshConnectionControllerTest"`; `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --rerun-tasks --console=plain`; `git diff --check`; `./scripts/gradlew-local.sh run` + `wmctrl -l`.
- Executed at: 2026-10-26
- Exit codes: 0 for compilation, the targeted tests, the full suite, `check`, and the diff check; the GUI smoke window was confirmed with `wmctrl` and then terminated.
- Result: The terminal now resolves `Noto Sans Mono CJK TC` on this host (Sarasa Mono would win if installed) at 16 pt, and the shell opens at 120x30 instead of 80x24. Font metrics measured with the project JDK: `Noto Sans Mono CJK TC` at 16 pt gives a 8x24 px cell, an exact 2:1 full-width advance, and about 129 columns in a 1040 px terminal area; the previous logical `Monospaced` at 14 pt gave an 8x17 px cell with a 14 px full-width advance, i.e. visibly narrower CJK glyphs. Real-widget offscreen renders at 14/16/18 pt were compared: 16 pt keeps the previous column count while enlarging glyphs and line spacing, 18 pt drops to roughly 115 columns.
- Full suite runs 220 tests: 220 executed, 0 failures, 0 errors, 12 skipped (the existing opt-in/platform-gated set). New coverage: Linux/Windows/macOS family preference order, case-insensitive family matching, the `Monospaced` fallback, font style/size resolution, the initial-size constants, that `EyeShellTerminalSettings` reports the resolved font, and that `openTerminal` requests 120x30.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10, default 512 MiB test heap.
- Artifacts: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`; comparison renders at `/tmp/opencode/terminal-14.png`, `terminal-16.png`, and `terminal-18.png`; log at `/tmp/opencode/typo-test.log`.
- Remaining unverified scope: the font was rendered offscreen from the real widget, not inspected in a shown window against a live SSH session; Windows was not run (its family list is covered only by unit tests); HiDPI scaling and a host without any CJK-complete font were not exercised; there is no font/zoom preference UI.
- Added a `Clear Transfers` button to the SFTP panel. It removes completed, failed, and cancelled jobs from the upload/download history (and the transfer table), keeps queued or running transfers so an in-flight transfer never loses its row, and is enabled only when at least one finished job exists. The history is also reset when a different session's controller is bound.
- Added an explicit `Download` button to the SFTP panel (download was previously reachable only by double-clicking a file). It is enabled only for a selected file, writes through the same transfer queue as uploads, and keeps double-click as a shortcut. `Rename`/`Delete` are now also disabled until a row is selected, and a new session clears the previous listing.
- Fixed entering a remote subdirectory: a double-click called `navigate(file.name)`, which resolved the name against the root (`/eye` instead of `/home/eye`) and failed with "No such file"; it now navigates through `remotePathFor(file.name)`, and a relative path typed into the path field is resolved against the current directory too.
- Fixed the SFTP panel never showing files: `transferTable.preferredSize = Dimension(0, 90)` was a no-op because `JTable.getPreferredScrollableViewportSize()` always reports its default 450x400, so the southern transfer pane claimed 427 px inside the ~220 px tool dock and BorderLayout gave the file list a negative height (measured `-330`). The transfer pane now constrains its own `JScrollPane` (84 px, minimum 60) and the tool dock is 300 px high, so the file list gets roughly 120 px (header + 4 rows).
- SFTP now starts in the server-reported home directory (`SftpClient.homeDirectory()`, `SftpClient.canonicalPath(".")`) instead of `/`, and an inline status line reports `Listing <path>...`, `<n> entries in <path>`, `<path> is empty.`, or `Could not list <path>: <reason>`; a listing failure no longer only appears as a modal dialog.
- SFTP binding no longer swallows failures. `TerminalSessionPage.loadSftp` opens the subsystem on a virtual thread (it used to call `hostSession.sftp()` on the EDT), caches either the controller or a one-line cause chain, and `resetSftp` clears a cached failure for an explicit retry. `EyeShellWindow.bindSftpFor` shows an `Opening SFTP...` card while the subsystem is created and, on failure, an `SFTP is unavailable for this session.` card with the cause and a `Retry` button instead of the previous "connect to a host" empty state. Switching tabs no longer leaves the previous session's file listing visible while the new subsystem opens.

### Ubuntu host validation (SFTP list layout, home directory, diagnostics)

- Commands: `./scripts/gradlew-local.sh test --tests "*SftpPanelTest" --tests "*SftpWindowIntegrationTest" --tests "*WorkbenchPanelTest"`; `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh run` + `wmctrl -l`; `git diff --check`.
- Executed at: 2026-10-26
- Exit codes: 0 for the targeted tests, the full suite, `check`, and the diff check; the GUI smoke window appeared and was terminated.
- Result: The reported symptom was an SFTP panel showing the connected toolbar (path `/`, Refresh, Up, action buttons) with a permanently empty file area. Root cause: `transferTable.preferredSize = Dimension(0, 90)` never constrained the pane because `JTable.getPreferredScrollableViewportSize()` returns its fixed 450x400 default, so the transfer `JScrollPane` asked for 427 px inside the ~220 px tool dock; BorderLayout then laid the file list out with a **negative height** (measured `-330`), leaving no rows or header visible even though the table model held the entries. Fixes: the transfer pane now sets its own `JScrollPane` preferred/minimum size (84/60 px), the tool dock is 300 px high, the browser starts at the server home (`canonicalPath(".")`) instead of `/`, an inline status line reports listing state and failures, and the subsystem opens off the EDT with an `Opening SFTP...` card plus a `Retry`-able `SFTP is unavailable...` card. Verified with real-widget renders: the panel now shows `3 entries in /home/eye`, the file table header and rows, the transfer table header, and the action buttons; the unavailable card shows the cause and Retry.
- Full suite runs 232 tests: 232 executed, 0 failures, 0 errors, 12 skipped. New coverage: `Clear Transfers` removes a completed upload from the table and the controller, keeps a running transfer, and disables itself when nothing is left; the Download button writes the selected file to the chosen path and reports progress/COMPLETED, and stays disabled without a selection or for a directory; double-clicking a directory inside `/home` lists `/home/eye` (not `/eye`) and a typed relative path resolves against the current directory; the file list is laid out with a positive height above the transfer table (regression for the negative-height bug), the home directory is used and the entry count is reported, empty and failing listings are reported inline, the unavailable card and its Retry callback render, the loading card renders, the window lists a real embedded SFTP server's directory, and the page reports and caches an SFTP failure without reopening the subsystem.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10, default 512 MiB test heap.
- Artifacts: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`; panel renders at `/tmp/opencode/sftp-connected.png`, `sftp-unavailable.png`, `sftp-loading.png`; log at `/tmp/opencode/sftp3-test.log`.
- Remaining unverified scope: the user's own server was not available; the success path was exercised against an embedded MINA server and the failure path with a fake client. The Retry button was not clicked by hand, a subsystem open that hangs stays on `Opening SFTP...`, and the panel was rendered offscreen rather than inspected on the user's machine.

### 2026-10-27 commit and push

- Commands: `git push origin main`.
- Result: pushed `80e362b..eaa1bb9` (2 commits: `5844a90` terminal typography, `eaa1bb9` SFTP panel) to `origin/main`; working tree clean and `main` in sync afterwards at 2026-10-27T00:23+08:00.
- Not re-run for the push: no code changed after the validated `232 tests / 0 failures / 12 skipped` run recorded above, except these documentation updates.

### 2026-10-27 Session close (packaging verification)

- Commands: `./scripts/gradlew-local.sh test --rerun-tasks --console=plain`; `./scripts/gradlew-local.sh check --rerun-tasks --console=plain`; `git diff --check`; `./scripts/gradlew-local.sh jpackageAppImage`; `./scripts/gradlew-local.sh jpackagePortable jpackageInstaller`; `env -u JAVA_HOME -u JDK_HOME PATH=/usr/bin:/bin ./build/package/eyeShell/bin/eyeShell` + `wmctrl -l`.
- Executed at: 2026-10-27T00:40+08:00
- Duration / exit codes: test 113 s, exit 0; check 63 s, exit 0; diff check exit 0; `jpackageAppImage` 35 s, exit 0; `jpackagePortable` + `jpackageInstaller` 97 s, exit 0; the packaged launcher was started without `JAVA_HOME`/`JDK_HOME` and its `eyeShell` window was confirmed, then the process was terminated.
- Result: HEAD `c8c3a85`, working tree clean and `main` in sync with `origin/main`. Full suite runs 232 tests: 232 executed, 0 failures, 0 errors, 12 skipped (the existing opt-in/platform-gated set). `check` passed. Packaging artifacts built from the committed sources: `build/package/eyeShell/` (app image, 177 MB, bundled runtime 162 MB), `build/package/eyeShell-0.1.0.tar.gz` (69 MB), `build/package/eyeshell_0.1.0_amd64.deb` (50 MB, installs to `/opt/eyeshell` with a desktop entry and declared dependencies). The app image launched successfully with no system JDK on `PATH`.
- Test environment: Ubuntu 26.04 x86_64, GNOME Wayland session with `DISPLAY=:0`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10, default 512 MiB test heap. `libfuse2t64`, `dpkg-deb`, and `fakeroot` are present; `appimagetool` is not installed.
- Artifacts: `build/reports/tests/test/index.html`; XML results under `build/test-results/test/`; logs at `/tmp/opencode/close-test.log`, `/tmp/opencode/close-check.log`, `/tmp/opencode/pkg-build.log`, and `/tmp/opencode/appimage-run.log`.
- Not verified: the DEB was built but not installed on a clean machine; the packaged app was only checked for launching (no SSH/SFTP session through the packaged runtime); AppImage packaging was not implemented (owner deferred it on 2026-10-27); Windows packaging (MSI/ZIP) was not run; `tokenscope` is unavailable, so no Token Usage row was appended.

### 2026-10-27 Session close push

- Commands: `git add handoff.md`; `git commit`; `git push origin main`.
- Result: pushed `c8c3a85..2a9eba0` to `origin/main`; working tree clean and `main` in sync afterwards at 2026-10-27T00:48+08:00.
- Not re-run for the push: no code changed after the validated `232 tests / 0 failures / 12 skipped` run recorded above; only this documentation update was added.

### Windows host setup (fresh clone, test isolation, portable ZIP)

- Commands: `git clone https://github.com/sawaichi9527/eyeShell.git .`; `git submodule update --init --recursive`; `.\scripts\bootstrap-jdk.ps1`; `.\scripts\gradlew-local.ps1 test --console=plain`; `.\scripts\gradlew-local.ps1 test --tests "*TerminalTypographyTest" --tests "*SqliteHostCatalogTest" --console=plain`; `.\scripts\gradlew-local.ps1 check --console=plain`; `.\scripts\gradlew-local.ps1 run --console=plain`; `.\scripts\gradlew-local.ps1 jpackagePortable --console=plain`; `jar --list --file build/package/eyeShell-0.1.0.zip`; `build\package\eyeShell\eyeShell.exe` with `JAVA_HOME`/`JDK_HOME` unset and `PATH` reduced to `%SystemRoot%\system32;%SystemRoot%`; `git diff --check`.
- Executed at: 2026-10-27
- Duration / exit codes: first full `test` 11 min 11 s, exit 1 (2 failures, both platform-coupled tests); targeted re-run 8 s, exit 0; full `test` 24 s, exit 0; `check` 2 s, exit 0; `jpackagePortable` 28 s, exit 0; `git diff --check` exit 0. The GUI smokes were terminated manually after the `eyeShell` window was confirmed, so their Gradle `run` task reports the kill as a build failure by design.
- Result: The toolchain was rebuilt from a fresh clone with no global JDK/Gradle/Kotlin: submodule `third-party/jediterm` at `38413f9` (`eyeshell-main-buffer-read`), Temurin 21.0.12+8 under `.local/jdk-21` (vendor SHA-256 verified), and Gradle 9.5.0 plus all dependencies under `.local/gradle-home` with strict verification intact. The two initial failures were test defects, not product defects. (1) `SqliteHostCatalogTest` opened the real `%LOCALAPPDATA%\eyeShell\eyeshell.db` (which already held the owner's `Freelancer` profile), asserted `listHosts().single()`, and ended by deleting the database file — so it failed on any populated machine and a passing run would have deleted the user's catalog. It now reloads only its own row, asserts that row's full round trip, removes just that row in `finally`, and never deletes the catalog or its journal sidecars. (2) `TerminalTypographyTest` asserted `Font.getFamily()` after resolving a family that is not installed on this host, where AWT substitutes `Dialog`; the requested family is now asserted through `resolveFamily` and the font only for style and size, which is what `TerminalTypography` documents. The Windows `jpackagePortable` branch was also changed from `powershell -NoProfile -Command Compress-Archive` to the project-local JDK's `jar --create --no-manifest`, because Windows PowerShell 5.1 is not permitted and this removes the shell dependency entirely. After the fixes the suite runs 232 tests: 232 executed, 0 failures, 0 errors, 15 skipped (the Linux-only live cases plus the other opt-in/platform-gated cases), `check` passes, the GUI opens with the `eyeShell` window title, `jpackagePortable` builds `build/package/eyeShell/` (app image with bundled `runtime/`) and `build/package/eyeShell-0.1.0.zip` (66,136,214 bytes, 448 entries, no fake `META-INF`), and the packaged `eyeShell.exe` launches with no JDK on `PATH`.
- Test environment: Microsoft Windows 10 IoT Enterprise LTSC 10.0.19044 x64, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 distribution/caches under `.local/gradle-home`, Kotlin 2.4.10, default test heap. `gh` and a system `pwsh` are not required by the project launchers.
- Test report: `build\reports\tests\test\index.html`; XML results under `build\test-results\test\`.
- Remaining unverified scope: the tests were not run against a real SSH server from this host (the opt-in live cases are Linux-targeted), Jump Host and the live Windows Credential Manager lifecycle were not re-validated, MSI generation was not attempted (it needs the WiX toolset), and no GUI gesture automation exists, so the manual checklist still needs a human pass on Windows.

### 2026-10-27 Windows session commit

- Commands: `git add build.gradle.kts src/test/kotlin/... handoff.md`; `git commit`.
- Result: committed the two test-isolation fixes, the `jar`-based Windows portable ZIP, and these handoff updates on top of `42302de`; the working tree is clean afterwards and `main` is ahead of `origin/main`.
- Not re-run for the commit: no runtime behaviour changed after the validated `232 tests / 0 failures / 15 skipped` run and `jpackagePortable` recorded above.

### 2026-10-27 Windows session push

- Commands: `git push origin main`.
- Result: pushed `42302de..4773478` (2 commits: `111bd14` test isolation and the `jar`-based portable ZIP, `4773478` handoff) to `origin/main`; the working tree is clean and `main` is in sync with `origin/main` afterwards at 2026-10-27T11:54+08:00.
- Not re-run for the push: no code changed after the validated `232 tests / 0 failures / 15 skipped` run recorded above; only documentation was added.
- Windows packaging on this push: only the app image and the portable ZIP were built. MSI generation was not attempted because it requires the WiX toolset.

### Windows host validation (terminal font fallback, CJK and Nerd Font icons)

- Commands: `curl` download of the vendor archives, `Get-FileHash` against the published `SHA-256.txt` / `.sha256`, `7z e` and `ZipFile` extraction, per-user install into `%LOCALAPPDATA%\Microsoft\Windows\Fonts` plus `HKCU\Software\Microsoft\Windows NT\CurrentVersion\Fonts`, and `AddFontResourceW` to publish them to the running session; `.\scripts\gradlew-local.ps1 test --tests "*TerminalFontFallbackTest" --tests "*TerminalTypographyTest"`; `$env:EYESHELL_TEST_FONT_RENDER=1` + `.\scripts\gradlew-local.ps1 test --tests "*TerminalFontFallbackRenderTest"`; `.\scripts\gradlew-local.ps1 test`; `.\scripts\gradlew-local.ps1 check`; `.\scripts\gradlew-local.ps1 run`; `git diff --check`.
- Executed at: 2026-10-27
- Exit codes: 0 for the targeted tests, the render probe, the full suite, `check`, and the diff check. The GUI smoke was terminated manually after the `eyeShell` window appeared, so its Gradle `run` task reports the kill as a build failure by design.
- Result: The Windows report was empty boxes in an SSH session's fastfetch block. Diagnosis on the client host: `TerminalTypography` resolved `Consolas`, which contains no CJK, no emoji, and no Private Use Area glyphs, and JediTerm has no cross-family fallback (`getFontToDisplay()` only handled bold/italic), so every missing glyph became tofu. The suite now contains a per-run fallback: `EyeShellTerminalWidget` returns an `EyeShellTerminalPanel` whose `getFontToDisplay()` asks `TerminalFontFallback` for the first installed family covering the whole run (Nerd Fonts → CJK → symbol/emoji), with cell metrics unchanged and the primary font still used for covered text. Verified with two offscreen renders in `build/reports/`: with a deliberately Latin-only primary font (`font-fallback-latin-primary.png`) and with the application's resolved default font (`font-fallback-default.png`). Both show `ASCII Applications`, the Nerd Font icons (`U+F015`, `U+F17C`, `U+F303`, `U+E0B0`, `U+E0A0`) drawn from Maple Mono NF, CJK `中文測試 臺裡`, and emoji `😀 📦` drawn from Segoe UI Emoji, with no boxes; the default-font render takes the CJK glyphs from Sarasa and the icons from the fallback chain, which is the configuration this host now runs. On this host the primary font now resolves to Sarasa Mono TC, measured at 16 pt as `M=8 px`, `i=8 px`, CJK `=16 px` (exact 2:1), `ascent=16`, `descent=5`. Full suite runs 243 tests: 243 executed, 0 failures, 0 errors, 16 skipped (the existing opt-in/platform-gated set plus the new `EYESHELL_TEST_FONT_RENDER` probe).
- Test environment: Microsoft Windows 10 IoT Enterprise LTSC 10.0.19044 x64, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 under `.local/gradle-home`, Kotlin 2.4.10. Fonts: Sarasa Mono TC 1.0.43 (vendor SHA-256 `4e5fe59e2437f5de25e745565904e46a915d368d3fef8f1285f4032b239193df` for the archive) and Maple Mono NF 7.9 (vendor SHA-256 `59098b87c895d871635d37680e88000ae2b2b25b55428195b228ec589e35fb89`), both SIL OFL 1.1, installed per-user only.
- Test report: `build\reports\tests\test\index.html`; XML results under `build\test-results\test\`; render proofs at `build\reports\font-fallback-latin-primary.png` and `build\reports\font-fallback-default.png`.
- Remaining unverified scope: the Windows console reported the boxes in a live SSH session and that exact fastfetch/MOTD output was not reproduced here (no credentials are used by the agent), so the evidence is the offscreen render plus the font-coverage measurements; a fallback run keeps its own ascent/descent and can sit a pixel or two off the primary baseline; per-user font persistence across a logoff/logon was not re-checked; and no Nerd Font is bundled with the application, so another machine still needs one installed for icons.

### 2026-10-27 font fallback push

- Commands: `git push origin main`.
- Result: pushed `d598acf..fcb7521` (2 commits: `f9e57a1` per-run terminal font fallback, `fcb7521` handoff) to `origin/main`; the working tree is clean and `main` is in sync with `origin/main` afterwards at 2026-10-27T15:39+08:00.
- Not re-run for the push: no code changed after the validated `243 tests / 0 failures / 16 skipped` run recorded above; only documentation was added.

### Windows host validation (cmd.exe wrappers)

- Commands: `cmd /c "scripts\gradlew-local.cmd --version"`; `cmd /c "scripts\bootstrap-jdk.cmd"`; `cmd /c "set PATH=C:\Windows\System32&& set ProgramFiles=C:\nope&& set LOCALAPPDATA=C:\nope&& scripts\gradlew-local.cmd --version"`; `cmd /c "scripts\gradlew-local.cmd run"`; `cmd /c assoc .ps1`; `cmd /c ftype Microsoft.PowerShellScript.1`; `git diff --check`.
- Executed at: 2026-10-27
- Exit codes: 0 for `--version` and `bootstrap-jdk.cmd`; 1 with the expected message for the missing-`pwsh` case; the `run` smoke was terminated manually after the window appeared. `assoc`/`ftype` confirm `.ps1` is associated with `notepad.exe` on this host.
- Result: The reported symptom was `.\scripts\gradlew-local.ps1 run` opening a Notepad window instead of the application. `cmd.exe` does not execute `.ps1` files; it hands them to the file association, which here is `notepad.exe`. Added `scripts\gradlew-local.cmd` and `scripts\bootstrap-jdk.cmd`, which resolve `pwsh` (`PATH` → `%ProgramFiles%\PowerShell\7` → WindowsApps alias) and forward the arguments to the existing PowerShell launchers, so `JAVA_HOME`, `PATH`, and `GRADLE_USER_HOME` stay scoped to the child process. With `pwsh` made unavailable the wrapper exits 1 and prints `PowerShell 7 (pwsh) was not found... Windows PowerShell 5.1 is not supported.` rather than silently using 5.1. `scripts\gradlew-local.cmd --version` reports Gradle 9.5.0, `scripts\bootstrap-jdk.cmd` reports the existing Temurin 21.0.12+8, and `scripts\gradlew-local.cmd run` opens the `eyeShell` window from `cmd.exe`. The README now documents the `cmd.exe` block and why the `.ps1` cannot be run directly there; `*.cmd` is pinned to CRLF in `.gitattributes`.
- Test environment: Microsoft Windows 10 IoT Enterprise LTSC 10.0.19044 x64, `cmd.exe` (not PowerShell), `pwsh` 7.4.6 on `PATH`, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 under `.local/gradle-home`.
- Remaining unverified scope: the wrappers were exercised on this host only; a machine whose `pwsh` lives outside `PATH`, `%ProgramFiles%\PowerShell\7`, and the WindowsApps alias would be rejected even though PowerShell 7 is installed, and argument forwarding was checked with simple arguments only (no quoting stress test).

### 2026-10-27 cmd wrapper push

- Commands: `git push origin main`.
- Result: pushed `9674f82..d7bf290` (2 commits: `dd402aa` cmd.exe wrappers, `d7bf290` handoff) to `origin/main`; the working tree is clean and `main` is in sync with `origin/main` afterwards at 2026-10-27T15:59+08:00.
- Not re-run for the push: no runtime code changed after the validated `243 tests / 0 failures / 16 skipped` run; the wrappers were verified directly in `cmd.exe` as recorded above.

### Windows host validation (0.1.1 portable package)

- Commands: `.\scripts\gradlew-local.ps1 test --rerun-tasks --console=plain`; `.\scripts\gradlew-local.ps1 check --console=plain`; `.\scripts\gradlew-local.ps1 jpackagePortable --console=plain`; `jar --list --file build/package/eyeShell-0.1.1.zip`; `build\package\eyeShell\eyeShell.exe` with `JAVA_HOME`/`JDK_HOME` unset and `PATH` reduced to `%SystemRoot%\system32;%SystemRoot%`; catalog inspection before and after the suite; `git diff --check`.
- Executed at: 2026-10-27
- Exit codes: 0 for the forced test run, `check`, and `jpackagePortable`; the packaged app was terminated manually after its window appeared.
- Result: The project version is now `0.1.1-SNAPSHOT`, so the package version (`substringBefore('-')`) is `0.1.1`. `jpackagePortable` produced `build/package/eyeShell/` (app image with the bundled runtime) and `build/package/eyeShell-0.1.1.zip`: 63.1 MB, 448 entries, containing `app/eyeShell-0.1.1-SNAPSHOT.jar`, `app/` with 19 dependency jars, and `runtime/`. The packaged `eyeShell.exe` started from the app image with no JDK on `PATH` and opened the `eyeShell` window. The forced full suite runs 243 tests: 243 executed, 0 failures, 0 errors, 16 skipped. Host-data check requested by the owner: before and after the suite the real catalog contains exactly one row (`Freelancer`, `192.168.23.217`, profile `b080eba1-07cd-40ca-b432-f078678e63d0`), the Windows Credential Manager holds exactly one matching `eyeShell/ssh/b080eba1-07cd-40ca-b432-f078678e63d0` entry, and `known_hosts` holds three real LAN hosts (`192.168.23.165`, `.217`, `.219`); no test host (`windows.example`/`Windows host`) remains, because the Windows data-path test deletes only the row it creates.
- Test environment: Microsoft Windows 10 IoT Enterprise LTSC 10.0.19044 x64, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 under `.local/gradle-home`, Kotlin 2.4.10.
- Artifacts: `build\reports\tests\test\index.html`; XML results under `build\test-results\test\`; `build\package\eyeShell-0.1.1.zip`; app image at `build\package\eyeShell\`.
- Remaining unverified scope: the ZIP was not extracted on a second clean machine (it was extracted and used on this host), MSI generation still needs the WiX toolset, and no automated test drives the packaged GUI.

### Windows host validation (0.1.1 packaged build, owner live session)

- Evidence source: owner-reported live session on 2026-10-27 after extracting `eyeShell-0.1.1.zip` into a separate folder and starting the packaged `eyeShell.exe` (the agent did not drive this session, so no command log exists for it).
- Result: The packaged build connected to a real SSH host and the owner exercised the terminal, the Monitor sidebar, SFTP, and reconnect (exit followed by Enter). This closes the "the package only launches" gap recorded for the 0.1.0/0.1.1 builds: the bundled `runtime/` and the `app/` classpath are sufficient for a real session on Windows, not just for starting the window.
- Not covered by this report: the saved-password path through Windows Credential Manager (the packaged run used the existing host profile), jump hosts, keyboard-interactive prompts, and MSI installation. Those remain listed under Known Issues / Deferred.

### 2026-10-27 packaged-build live-session record push

- Commands: `git push origin main`.
- Result: pushed `6b95d8c..275a5ac` (`275a5ac` records the owner's live session on the packaged 0.1.1 build) to `origin/main`; the working tree is clean and `main` is in sync with `origin/main` afterwards at 2026-10-27T17:11+08:00.
- Not re-run: documentation only; no code changed since the validated `243 tests / 0 failures / 16 skipped` run.

### 2026-10-27 0.1.1 package push

- Commands: `git push origin main`; `Remove-Item build\package\eyeShell-0.1.0.zip`.
- Result: pushed `f5ffa5a..b2227e6` (2 commits: `02e62e0` version 0.1.1, `b2227e6` handoff) to `origin/main`; the working tree is clean and `main` is in sync with `origin/main` afterwards at 2026-10-27T16:09+08:00. The stale `build\package\eyeShell-0.1.0.zip` was deleted on the owner's request, leaving only `eyeShell-0.1.1.zip` (63.1 MB) in the package directory.
- Not re-run for the push: only the version string and documentation changed after the validated `243 tests / 0 failures / 16 skipped` run and the `jpackagePortable` build recorded above; the pushed source is what produced `eyeShell-0.1.1.zip`.

### Windows host validation (window title, Monitor settings entry, hosts tree table)

- Commands: `.\scripts\gradlew-local.ps1 test --console=plain`; `.\scripts\gradlew-local.ps1 check --console=plain`; `.\scripts\gradlew-local.ps1 run` (real window, captured with `PrintWindow` into `build\reports\eyeshell-window.png`); `$env:EYESHELL_TEST_DIALOG_RENDER=1` + `.\scripts\gradlew-local.ps1 test --tests "*HostCatalogControllerTest"` (renders `build\reports\hosts-dialog.png`); `git diff --check`.
- Executed at: 2026-10-27
- Exit codes: 0 for the full suite, `check`, and the render probes; the GUI smoke was terminated manually after the window was captured.
- Result: (1) The window title now reads `eyeShell v0.1.1`, produced from the Gradle version through `eyeshell-build.properties` + `AppInfo`; the captured window confirms it, and `AppInfoTest` fails if the resource is not expanded. (2) The Monitor header carries a drawn gear button at its right edge (`monitorHeader` with `BorderLayout.EAST`); the capture shows it next to the title, and clicking it opens the Settings dialog whose font-size spinner applies live and persists. (3) The Saved hosts dialog is now a tree table: the rendered dialog shows the six columns, ungrouped hosts first, `Lab (3)` and `Spark (3)` folder rows with disclosure triangles, and the host rows indented under them; the new `HostTableRowsTest` covers collapse/expand, filtering by name/host/port/user/authentication, hiding empty folders, and the user-facing authentication labels, while `HostCatalogControllerTest` asserts the table contents and that only host rows enable Edit/Delete/Connect. Full suite runs 264 tests: 264 executed, 0 failures, 0 errors, 17 skipped (the existing opt-in/platform-gated set plus the new `EYESHELL_TEST_DIALOG_RENDER` probe).
- Test environment: Microsoft Windows 10 IoT Enterprise LTSC 10.0.19044 x64, Temurin 21.0.12+8 under `.local/jdk-21`, Gradle 9.5.0 under `.local/gradle-home`, Kotlin 2.4.10.
- Artifacts: `build\reports\tests\test\index.html`; XML results under `build\test-results\test\`; window capture at `build\reports\eyeshell-window.png`; dialog render at `build\reports\hosts-dialog.png`.
- Remaining unverified scope: the dialog render uses the default Swing look-and-feel (the real app runs FlatDarkLaf), so the filter placeholder text and theme colours still need a human look; the gear/Settings flow was exercised through component tests, not by clicking in the captured window; font-size changes were verified by unit tests and by the terminal view's re-flow, not by a live SSH session at a different size; and the Settings dialog's language row is intentionally disabled.
