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
- `+` on the tab strip behaves exactly like `Connect...`, is disabled while a connection is in progress, and sits immediately after the last tab.
- Closing the last tab restores the `Start` tab.

## 2. Saved hosts and folders

- `Hosts...` shows a folder tree; ungrouped hosts sit at the top level, folders are sorted, and folder labels show a count.
- Selecting a folder enables only `Add...`, and `Add...` pre-fills that folder into the Group field.
- Selecting a host enables `Edit...`, `Delete`, and `Connect`.
- Expanding/collapsing folders survives an add or edit; the edited host stays selected (its folder expands if the host moved).
- Saving a password works, `Clear saved password` removes it, and leaving the field blank keeps the previous password.
- Editing an existing PASSWORD host opens with a blank Password field showing a `********` mask and the hint `A saved password is set. Leave the field blank to keep it; an entered password is used for this session only.` when a secret exists; the saved secret is never shown. Typing replaces the mask, and `Clear saved password` hides it.
- `Save password in OS credential store` is checked by default for both Add and Edit, so a password typed in the editor is stored in the OS credential store (unless the box is cleared); `Clear saved password` removes a stored one.
- With the OS keyring locked, saving a password shows the desktop unlock prompt; after unlocking, the password is stored and is retrieved on the next launch without retyping.
- With the OS keyring locked, the Password field is still editable, the hint says the keyring is locked, and the entered password is used by the next `Connect` without retyping (it is kept only in memory).
- `Connect after saving` on a new host saves it and starts the connection confirmation for that host.
- A folder with no remaining hosts disappears after its last host moves away or is deleted.

## 3. Connection flows

- Password, Public Key (with and without passphrase), keyboard-interactive, and agent each open a terminal.
- Connecting to a Saved Host with a stored password shows `********` in the empty Password field; typing removes the mask, and leaving it untouched uses the stored password.
- A new host key shows the confirmation screen; rejecting it aborts the connection.
- A changed host key is rejected with no automatic replacement prompt.

## 4. Jump host

- The host editor lists eligible jump hosts and rejects self-reference.
- A target without usable credentials asks for jump-host credentials only.
- First use shows the jump-host key screen; once trusted, the target shows a single combined confirmation that restates the jump trust state.
- The Known Hosts file contains the target and jump `host:port` entries and no loopback forwarding port.

## 5. Session lifecycle

- Typing `exit` marks the tab `Exited` and keeps the scrollback visible.
- Pressing Enter in the exited session reconnects **into the same tab**: the tab title and position stay, the previous scrollback remains above the new shell's output, and the password typed for that session is reused without asking again.
- Cancelling the reconnect dialog and pressing Enter again reopens it; a failed attempt can be retried the same way.
- Exiting while an alternate-screen application (for example `vim` or `less`) was killed leaves no alternate-screen residue after the reconnect.
- Editing the Host/Port before connecting on a reconnect renames the tab to the new endpoint instead of leaving the old name.
- A new session never shows another session's output in the same tab.
- Connection failures show the full cause chain, and monitor/SFTP failures never abort terminal attachment.

## 6. Terminal behaviour

- Scroll through 100,000 lines without freezing the UI.
- Text renders in a monospace font at a comfortable size; Chinese/Japanese/Korean glyphs occupy exactly two Latin cells without visible gaps, and a 120-character log line does not wrap at a 1280 px window.
- Search (Ctrl+F) navigates matches and rejects invalid regex with a message.
- Add/Manage highlight rules: colors, bold/italic/underline, priority, and Merge/Override all render as chosen.
- Select Visible, Select All Output, Copy All Output (8,388,608 limit prompt), and Save All Output behave as documented, including on the alternate screen.
- `Clear Buffer` empties the visible screen and the scrollback, keeps the last line, and is disabled while an alternate-screen application is running.
- Connecting to a Password host with nothing saved does not raise an OS keyring password prompt; connecting to a host with a saved password raises it only when the keyring must be unlocked to read the secret.
- CJK and wide characters select and copy correctly.

## 7. SFTP

- Directory listing, upload, download, new folder, rename, and delete all succeed against the remote host.
- `Download` is enabled only for a selected file and writes it to the chosen local path with progress; double-clicking a file does the same. Selecting a folder disables Download.
- `Clear Transfers` empties the finished upload/download history and is disabled while nothing has finished; a running transfer keeps its row.
- Switching to a tab briefly shows `Opening SFTP...` and then that session's listing; another session's files are never shown.
- A server without the SFTP subsystem shows `SFTP is unavailable for this session.` with the reason and a `Retry` button instead of looking unconnected.
- Dropping local files uploads them to the current directory; dropping a folder shows a rejection message.
- The transfer table shows increasing percentages and reaches 100%.
- A failed transfer is reported and does not leave a partial target file behind.

## 8. Monitoring

- Before connecting, the left panel shows the metric skeleton (Host, Address, OS, Kernel, Uptime, Load, CPU, Memory, Swap, Network, Filesystems, Processes) with `—` values, a `Not connected` status, and the Java/OS footer; no fabricated numbers appear.
- `OS` shows the distribution from `/etc/os-release` (for example `Ubuntu 26.04.1 LTS`) and `Kernel` shows the kernel release on its own row.
- The panel shows connected address, CPU, memory, swap, load, network rates, filesystems, and top processes.
- Clicking the `Host`, `Address`, `OS`, or `Uptime` label (or its value) copies that value to the clipboard and briefly tints it green; there is no separate copy button.
- Shrinking the window height makes the left panel scroll vertically with a thin scrollbar instead of clipping the Processes section.
- The network section per-interface selector defaults to the interface that owns the connected address; switching it re-renders the Down/Up rates for that interface.
- `Load` shows the 1/5/15-minute averages above CPU; hovering explains it, and it turns amber when the 1-minute value exceeds the CPU core count.
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
