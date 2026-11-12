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
| `Terminal emulator thread <session> has not moved for 5s (<state>)` + stack frames | The first repeated blocked sample. Recorded even if the tab is closed before the 15-second confirmation. |
| `Terminal emulator stopped while <session> reported the session still open` | The emulator thread died (usually on a parse exception); the tab is marked **Failed**. |
| `Terminal read failed on <session>` with a stack trace | The channel or the decoder threw while the session was open. |
| `No terminal output for <n>s on <session>; … (localWindow=…, remoteWindow=…)` | The emulator is alive and polling but no bytes arrived for a minute. `localWindow` at or near 0 means the server is blocked because eyeShell stopped consuming; a healthy `localWindow` means the peer simply had nothing to send. |
| `Uncaught exception in thread "…"` with a stack trace | Any uncaught failure, including a Swing paint that threw. Previously this went to a stderr nobody can read in a packaged build. |
| `Terminal session attached/ended/closed …` | Lifecycle timeline: when a tab started, when the remote side ended it (`exitStatus=`), and when the user closed it. |
| `Terminal input is queued on <session>: the SSH remote window is 0 …` | The user typed, but the server has not consumed the previous bytes, so the channel queued them and the shell never saw the keystroke. Logged at most once per 30 s. |
| `Terminal output is being consumed but the panel has not repainted` | The repaint watchdog: characters arrive and are parsed, but the panel never paints. A `thread-dump-*.txt` is written. |
| `Settings → About → Write diagnostics` | Writes every open tab's model state: size, cursor, scroll-region top, visible rows, screen/history line counts, alternate-screen flag, paint counter, last paint age, consumed characters. Use this while the tab is frozen. |

A failed tab keeps its scrollback and can be reconnected in place with Enter. When it fails, the
session is closed, so a keystroke can no longer reach a remote shell the user cannot see.

## What to send after a freeze

1. Reproduce the freeze (for example `sudo apt autoremove` on the host).
2. **Wait 20–30 seconds** before copying: the watchdog confirms a stall after 15 s and writes the
   stack, and the 60-second silence warning adds the channel windows.
3. **While the tab is frozen, use `Settings → About → Write diagnostics`** and include the
   `thread-dump-manual-*.txt` it writes: it contains each tab's cursor, scroll region, visible rows,
   screen/history counts, alternate-screen flag, and paint counters, which is what separates a stuck
   cursor/scroll state from a renderer or transport problem.
4. Copy the whole `%LOCALAPPDATA%\eyeShell\logs\` folder. Closing the tab first is **not** required —
   the file handler flushes after every record, so the log is current while eyeShell runs. Keeping
   the tab open also preserves the status chip (Connected / Failed) as evidence.
5. Note the wall-clock time and what the last visible output was.

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
| `stalled … at SessionTtyConnector.readLoop` → `MinaSshTerminalSession.read` | The channel stopped delivering bytes while the window still looked open. | The 60-second line reports `localWindow`; near 0 means the server was blocked on us. |
| No `stalled` line, but `No terminal output for 60s … (localWindow=0/…)` | The emulator kept polling while the peer was blocked: the classic back-pressure loop where the terminal stops consuming and the whole SSH connection (Monitor included) goes silent. | The tab is marked Failed only after the watchdog confirms; otherwise report the log as-is. |
| Screen updates but new lines overwrite the last row, Enter does not advance, typed letters restart at column 0 | **Confirmed root cause**: a shortened scroll region (DECSTBM) left behind by a program that exited mid-mode. Reproduced with `printf '\033[%d;%dr' "$(tput lines)" "$(tput lines)"; printf 'one\ntwo\nthree\n'`; `printf '\033[r'` restores normal scrolling. The state survives an in-place reconnect, so a reused widget stays broken while a fresh tab works. | Fixed twice over: `resetForReattach()` restores the region on reconnect, and `ScrollRegionWatchdog` detects `top > 1` or `bottom < height` on the main screen for 30 s and restores it automatically, logging `Restoring a shortened terminal scroll region (N..N of M)`. |
| `Terminal output is being consumed but the panel has not repainted` | A renderer failure: the model advances but the panel never paints. | The accompanying dump names the paint path; the tab stays Connected by design until a stall is confirmed. |
| `Uncaught exception in thread "…"` on the EDT | A failing paint or event handler: the screen stops updating while the tab still says **Connected**. | Send that stack; it names the exact component. |
| `emulator stopped` + `Exception running terminal` with a stack trace | The emulator threw while parsing output. | The stack trace names the failing component; add the raw byte capture to reproduce it. |

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
