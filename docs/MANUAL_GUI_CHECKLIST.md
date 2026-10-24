# Manual GUI Checklist

Automated tests cover layout construction, controller wiring, and terminal buffers, but they do not drive real mouse or keyboard gestures. Run this checklist by hand before a release, on each target session type.

## Preconditions

- Project-local toolchain is available (`./scripts/bootstrap-jdk.sh` on Linux, `.\scripts\bootstrap-jdk.ps1` on Windows).
- A disposable remote host is reachable with Password, Public Key, and keyboard-interactive available; an OpenSSH agent socket is present for the agent case.
- Start with `./scripts/gradlew-local.sh run` (Linux) or `.\scripts\gradlew-local.ps1 run` (Windows).

## Sessions to cover

| Platform | Session type | Result |
|---|---|---|
| Ubuntu 26.04 | GNOME Wayland (XWayland) | |
| Ubuntu 24.04 | X11 | |
| Windows 10/11 | native | |

## 1. Launch and layout

- The window titled `eyeShell` opens with the Monitor panel on the left, session tabs on the right, and the bottom dock collapsed.
- `+` on the tab strip behaves exactly like `Connect...` and is disabled while a connection is in progress.
- Closing the last tab restores the `Start` tab.

## 2. Saved hosts and folders

- `Hosts...` shows a folder tree; ungrouped hosts sit at the top level, folders are sorted, and folder labels show a count.
- Selecting a folder enables only `Add...`, and `Add...` pre-fills that folder into the Group field.
- Selecting a host enables `Edit...`, `Delete`, and `Connect`.
- Expanding/collapsing folders survives an add or edit; the edited host stays selected (its folder expands if the host moved).
- Saving a password works, `Clear saved password` removes it, and leaving the field blank keeps the previous password.
- A folder with no remaining hosts disappears after its last host moves away or is deleted.

## 3. Connection flows

- Password, Public Key (with and without passphrase), keyboard-interactive, and agent each open a terminal.
- A new host key shows the confirmation screen; rejecting it aborts the connection.
- A changed host key is rejected with no automatic replacement prompt.

## 4. Jump host

- The host editor lists eligible jump hosts and rejects self-reference.
- A target without usable credentials asks for jump-host credentials only.
- First use shows the jump-host key screen; once trusted, the target shows a single combined confirmation that restates the jump trust state.
- The Known Hosts file contains the target and jump `host:port` entries and no loopback forwarding port.

## 5. Session lifecycle

- Typing `exit` marks the tab `Exited` and keeps the scrollback visible.
- Pressing Enter in the exited session opens a new tab with the original endpoint/authentication and preserves the old tab.
- Connection failures show the full cause chain, and monitor/SFTP failures never abort terminal attachment.

## 6. Terminal behaviour

- Scroll through 100,000 lines without freezing the UI.
- Search (Ctrl+F) navigates matches and rejects invalid regex with a message.
- Add/Manage highlight rules: colors, bold/italic/underline, priority, and Merge/Override all render as chosen.
- Select Visible, Select All Output, Copy All Output (8,388,608 limit prompt), and Save All Output behave as documented, including on the alternate screen.
- CJK and wide characters select and copy correctly.

## 7. SFTP

- Directory listing, upload, download, new folder, rename, and delete all succeed against the remote host.
- Dropping local files uploads them to the current directory; dropping a folder shows a rejection message.
- The transfer table shows increasing percentages and reaches 100%.
- A failed transfer is reported and does not leave a partial target file behind.

## 8. Monitoring

- The panel shows connected address, CPU, memory, swap, load, network rates, filesystems, and top processes.
- Switching tabs retargets monitoring; closing a tab stops it and resets the panel.
- A host without `/proc` (or a non-Linux host) degrades gracefully with a clear message.

## 9. Commands

- Add, edit, and delete a snippet; the list and preview update.
- Run sends the command to the active session with a single carriage return.
- Run is disabled when no session is selected.

## 10. Launch strategy and Safe Mode

- `--safe-mode` lowers scrollback to 10,000, caps refresh at 10, disables FlatLaf animations, and still launches under XWayland and X11.
- Windows applies its platform-default toolkit; Linux picks Wayland only when `sun.awt.WLToolkit` is available and otherwise X11.

## 11. Packaging

- The DEB installs, launches from the desktop entry, and opens the same window.
- The portable `tar.gz` runs from a directory without a system JDK.

## Recording results

Append a short entry to `handoff.md` under Validation Evidence with: date, session type, checklist sections run, and any divergence. Do not mark a section as passed without performing it.
