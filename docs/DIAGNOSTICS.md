# Terminal freeze diagnostics

This document covers the "connected but frozen" terminal tab: the session is alive, the sidebar
still samples, a new tab connects normally, and yet the frozen tab never updates again — including
keyboard input. Everything below happens on the machine that runs eyeShell (normally Windows); no
tool has to be installed and nothing has to be collected on the SSH host.

## What eyeShell records by itself

A rotating log (`java.util.logging`, the same framework `slf4j-jdk14` uses for Apache MINA SSHD and
the bundled JediTerm):

| Platform | Directory | File |
|---|---|---|
| Windows | `%LOCALAPPDATA%\eyeShell\logs` | `eyeshell.log` |
| Linux | `$XDG_STATE_HOME/eyeShell/logs` (default `~/.local/state/eyeShell/logs`) | `eyeshell.log` |

* The active file is `eyeshell.log`; a restart shifts it to `.1` and `.2`. Delete the directory to
  start over. The first line identifies the build (`eyeShell=… java=… os=…`).
* `Settings → About` shows the folder and offers **Open log folder** and **Write diagnostics**
  (a manual thread dump).
* The log never contains terminal output, passwords, passphrases, or key material.

A freeze produces these lines without any user action:

| Message | Meaning |
|---|---|
| `Terminal emulator stalled on <session>; the tab stopped updating` + stack frames | The watchdog saw the emulator thread repeat a blocked stack for 15 s. A `thread-dump-*.txt` is written next to the log, the tab is marked **Failed**, and the SSH channel is closed. |
| `Terminal emulator stopped while <session> reported the session still open` | The emulator thread died (usually on a parse exception); the tab is marked **Failed**. |
| `Terminal read failed on <session>` with a stack trace | The channel or the decoder threw while the session was open. |
| `No terminal output for <n>s on <session>; …` | The emulator is alive and polling but the server sent nothing for a minute (once per silent episode). |
| `Exception running terminal` with a stack trace | JediTerm's own report for the same failure; the file handler captures what a packaged build would otherwise print to a nonexistent console. |

A failed tab keeps its scrollback and can be reconnected in place with Enter. When it fails, the
session is closed, so a keystroke can no longer reach a remote shell the user cannot see.

## What to send after a freeze

1. Reproduce the freeze (for example `sudo apt dist-upgrade` on the host).
2. Note the wall-clock time and what the last visible output was.
3. Close the frozen tab (or press Enter to reconnect) and stop eyeShell normally so the log is fully
   flushed.
4. Zip `%LOCALAPPDATA%\eyeShell\logs\` and attach it, together with the two notes above.

Nothing else is required. If the whole application (not just the tab) is unresponsive, the log still
holds the last lines written before it froze.

## Optional: capture the raw terminal bytes

A content-triggered emulator failure can only be reproduced in a test when the byte stream is known.
eyeShell already retains the last 8 MiB of raw output per session; with the opt-in flag it writes the
last 256 KiB when a tab fails:

```bat
eyeShell.exe --capture-terminal-bytes
```

The capture lands next to the log as `terminal-<session>-<timestamp>.bin` and the log records the
path. **Treat that file as sensitive**: it is terminal output and may contain whatever was on screen
(command output, paths, possibly secrets).

## Reading the evidence

| Observation | Cause | Handling |
|---|---|---|
| `stalled … at SynchronizedOutput.await` → `TerminalDataStream.getChar` | A `CSI ? 2026` synchronized-update block was entered and the stream went quiet. JediTerm 3.76 starts a block only on `CSI ? 2026 h`; a regression means the submodule was pinned back. | Confirm with the optional `script` capture below; otherwise upgrade. |
| `stalled … at TerminalTextBuffer.lock` / `modify` | Lock contention on the buffer (paint, export, or highlight scan). Only possible while a highlight rule exists. | Check whether the tab had rules; the snapshot path is otherwise unused. |
| `stalled … at SessionTtyConnector.readLoop` → `MinaSshTerminalSession.read` | The channel stopped delivering bytes. | Look at the host's sshd and the network. |
| `emulator stopped` + `Exception running terminal` with a stack trace | The emulator threw while parsing output. | The stack trace names the failing component; add the raw byte capture to reproduce it. |
| No message but the tab is frozen | The dump was written before the stall threshold (15 s), or the tab was closed. | Reproduce again; the report is automatic. |

## Appendix: cross-check on the SSH host

Only needed to prove *which* control sequences an application sent. `script(1)` records the pty
stream before it reaches eyeShell:

```bash
script -f -q /tmp/apt-raw.log -c "sudo apt dist-upgrade"
grep -aoP '\x1b\[\?2026[hl]' /tmp/apt-raw.log | sort | uniq -c
```

A lone `ESC[?2026l` (the *end* of a synchronized block) is the sequence that used to stall the
emulator. With a live session, `printf '\033[?2026l'; sleep 5; echo after` renders `after`
immediately on 3.76; before the rebase it stopped until the next byte arrived.

The packaged AppImage ships a jlink runtime without `jdk.jcmd`, so a thread dump from a JDK 21 on the
same machine is the fallback when the built-in capture is unavailable:

```bash
/path/to/jdk-21/bin/jcmd <pid> Thread.print -l
```

## Related documentation

* `handoff.md` — current status, known issues, and validation evidence.
* `MANUAL_GUI_CHECKLIST.md` — the human pass over dialogs and visual behaviour.
