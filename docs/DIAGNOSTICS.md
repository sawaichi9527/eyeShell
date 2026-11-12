# Terminal freeze diagnostics

This document covers the "connected but frozen" terminal tab: the session is alive, the sidebar
still samples, a new tab connects normally, and yet the frozen tab never updates again. The steps
below collect enough evidence to distinguish a dead emulator thread from a stalled stream in a few
minutes, without rebuilding eyeShell.

## Where the evidence lives

eyeShell writes a rotating log through `java.util.logging` (the same framework `slf4j-jdk14` uses for
Apache MINA SSHD and the bundled JediTerm):

| Platform | Directory | File |
|---|---|---|
| Linux | `$XDG_STATE_HOME/eyeShell/logs` (default `~/.local/state/eyeShell/logs`) | `eyeshell.log` |
| Windows | `%LOCALAPPDATA%\eyeShell\logs` | `eyeshell.log` |

* The active file is `eyeshell.log`; a restart shifts it to `.1` and `.2` (two siblings, 2 MiB per
  run). Delete the directory to start over.
* The first line identifies the build: `eyeShell=<version> java=<runtime> os=<name>`.
* The log never contains passwords, passphrases, key material, or terminal output. It contains
  connection and terminal *lifecycle* messages only.

What a freeze now records by itself:

| Message | Meaning |
|---|---|
| `Terminal emulator stopped while <session> reported the session still open` | The emulator thread died (usually a parse exception). The tab is marked **Failed** and can be reconnected with Enter. |
| `Terminal read failed on <session>` with a stack trace | The SSH channel or the decoder threw while the session was open. |
| `No terminal output for <n>s on <session>; the session is still open and the emulator is waiting` | The emulator is alive and polling, but the server sent nothing for a minute. Reported once per silent episode. |
| `Exception running terminal` with a stack trace | JediTerm's own report for the same emulator failure; the file handler captures what a packaged build would otherwise print to a nonexistent console. |

## 3-minute capture while the tab is frozen

Run these on the machine that runs eyeShell, in this order. Replace `<pid>` with the eyeShell
process id (`pgrep -f eyeShell` or the app image process).

```bash
date -Is
ps -o pid,etime,args -p <pid>

# 1. Does the frozen tab still consume bytes?
#    (blind-type "echo hi" + Enter in the frozen tab first)
ss -tnip | grep -E ':22\b'
```

Read the socket line for that session's TCP connection:

* `Recv-Q` keeps growing after typing → bytes arrive but nobody reads them (emulator thread dead or
  blocked inside eyeShell).
* `Recv-Q` stays 0 and the peer's `Send-Q` grows → the server or TCP is not delivering.

```bash
# 2. Two thread dumps, ten seconds apart.
jcmd <pid> Thread.print -l > /tmp/eyeshell-dump1.txt
sleep 10
jcmd <pid> Thread.print -l > /tmp/eyeshell-dump2.txt

# 3. Did the tab's status chip change to Failed or Exited?
#    (compare with the log tail)
tail -50 ~/.local/state/eyeShell/logs/eyeshell.log
```

The packaged AppImage ships a jlink runtime **without** `jdk.jcmd`, so `jcmd` has to come from a
JDK 21 on the same machine (the development clone has one under `.local/jdk-21`). Any JDK 21 of the
same user can attach to the eyeShell process:

```bash
/path/to/jdk-21/bin/jcmd <pid> Thread.print -l
```

`kill -3 <pid>` also produces a thread dump, but the JVM writes it to file descriptor 1/2. That only
lands in a file if eyeShell was started from a terminal with `2> dump.txt`, so it is a fallback, not
the primary path.

## Reading the dump

| Observation | Cause | Handling |
|---|---|---|
| No thread named `TerminalEmulator-<session>`; the `JediTerm-*` pool thread is idle | The emulator thread died | Look for `Terminal emulator stopped` / `Exception running terminal` in the log; fixed by reporting the tab as Failed. |
| `TerminalEmulator-<session>` inside `SynchronizedOutput.await` → `TerminalDataStream.getChar` | A `CSI ? 2026` synchronized-update block was started on the wrong sequence (upstream 3.76 fixed this; eyeShell rebased onto it) | Confirm with the raw-byte capture below; a regression means the submodule was pinned back. |
| `TerminalEmulator-<session>` inside `TerminalTextBuffer.lock` / `modify`, with a highlight or export thread holding the buffer lock | Lock contention on the 100k-line buffer | Note whether the tab had highlight rules; the snapshot path only runs when rules exist. |
| `TerminalEmulator-<session>` inside `SessionTtyConnector.read` → `MinaSshTerminalSession.read` → blocking channel read | The stream is open but the server stopped sending | Compare with `ss` (Recv-Q = 0); look at the server's sshd and the network. |
| No `TerminalEmulator-*` at all and the log has no message | The dump was taken after cleanup, or the tab was already closed | Capture again on the next occurrence; the log rotation keeps two previous runs. |

## Raw byte capture on the server

The only way to prove which control sequences an application sent is to record the pty stream
before it reaches eyeShell. On the Linux host, run the suspect command under `script`:

```bash
script -f -q /tmp/apt-raw.log -c "sudo apt dist-upgrade"
grep -aoP '\x1b\[\?2026[hl]' /tmp/apt-raw.log | sort | uniq -c
```

A lone `ESC[?2026l` (the *end* of a synchronized block) is the sequence that used to stall the
emulator. If the capture contains it, the freeze mechanism is confirmed rather than suspected.

## Reproducing the fixed cases on demand

With a live session, a deterministic check of the synchronized-output path:

```bash
printf '\033[?2026l'; sleep 5; echo after
```

Before the 3.76 rebase this stopped rendering until the next output byte arrived; it now renders
`after` immediately.

## Related documentation

* `handoff.md` — current status and known issues.
* `MANUAL_GUI_CHECKLIST.md` — the human pass over dialogs and visual behaviour.
