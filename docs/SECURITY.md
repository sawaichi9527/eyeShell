# Security review

Reviewed 2026-11-21 against the `0.2.1-dev` tree. Re-check this page at every release cut, and any time
a vendor advisory names a component below.

## What eyeShell ships

`jpackage` bundles a jlink runtime plus every runtime dependency, so a runtime CVE is a product CVE:

| Component | Version | Ships as | Notes |
|---|---|---|---|
| Eclipse Temurin JDK/JRE (`java.base`, HotSpot) | 21.0.13.1+2 | `runtime/` in the portable ZIP and the AppImage | Pinned in `scripts/bootstrap-jdk.{ps1,sh}`; checksums in `docs/PRODUCT_SPEC.md` |
| Apache MINA SSHD (`sshd-core`, `sshd-sftp`) | 2.20.0 | `app/eyeShell-*.jar` dependencies | SSH transport and SFTP; pure Java |
| JediTerm (`jediterm-core`, `jediterm-ui`, fork `sawaichi9527/jediterm`) | 3.76 (`f2b0f53` + eyeShell patches) | Same | Terminal emulation; pure Java |
| Xerial SQLite JDBC | 3.53.2.1 | Same | Bundles a native SQLite build |
| FlatLaf | 3.7.2 | Same | Look and feel; no network or crypto |
| Google RE2/J | 1.8 | Same | Regex search only, linear time by design |
| EdDSA (`net.i2p.crypto`) | 0.3.0 | Same | Ed25519 support on older JVMs |
| JNA | transitively | Same | Windows credential store plumbing |
| dbus-java | transitively (Linux) | Same | Secret Service password store |

Build-time only (never in the artifact): Gradle 9.5.0, the Kotlin 2.4.10 compiler, JUnit Jupiter,
`appimagetool`, WiX (not used; MSI is deferred).

**OpenSSL is not a component.** TLS and SSH crypto go through the JDK's own providers (`SunJSSE`,
SunEC, SunRsaSign) and MINA's JCE usage; SQLite is reached through JNI, not OpenSSL. A grep of the
repository finds no OpenSSL reference, and the jlink runtime contains no `libssl`/`libcrypto`.

## 2026-11-21 review

### CVE-2026-114938 ("Java4Shell") — fixed by the JDK bump

* **What**: memory-corruption (out-of-bounds heap write of up to 62 bytes) in HotSpot's AVX-512 VBMI
  Base64 decoding path. Oracle CVSS 3.1 score 8.1 (High); CISA advised immediate patching on
  2026-11-17.
* **Affected**: Java 21 up to and including `21.0.13`; fixed in `21.0.13.1`. Java 17 `17.0.6`–`17.0.21`,
  Java 25 up to `25.0.5`, and Java 27 up to `27.0.1` are affected the same way.
* **Conditions**: an affected runtime, an x86-64 CPU with AVX-512 VBMI (Intel Ice Lake or newer,
  AMD Zen 4 or newer), and an application that decodes attacker-supplied Base64 with the standard
  library.
* **eyeShell exposure**: the released `0.2.0` package bundled Temurin `21.0.12+8` and was therefore a
  vulnerable runtime. eyeShell's own code never calls `java.util.Base64`, and MINA/JediTerm carry
  their own encoders, so no attacker-supplied Base64 is decoded through the vulnerable path in the
  product. The hardware condition is also unmet on most desktops. Exposure was low, but a shipped
  runtime is a shipped runtime and the fix is a version pin.
* **Action (done)**: pinned `21.0.13.1+2` in both bootstrap scripts with the vendor SHA-256
  (Windows ZIP `bbebea14…d4fbf2`, Linux x64 tar.gz `bb8a93f9…cf6222e`), updated
  `docs/PRODUCT_SPEC.md`, and re-ran the full suite on the new runtime. `0.2.1-dev` and any later
  package carry the fixed runtime. `0.2.0` remains vulnerable if someone keeps that ZIP; the fix
  ships in the next release.

### OpenSSL advisories (2026-11-10 and 2026-11-17) — not applicable

The 2026-11-17 advisory contains five CVEs, all **Low**: `CVE-2026-107493` (use-after-free in
`PEM_X509_INFO_read_bio_ex()` when the caller passes a non-NULL stack; OpenSSL's own callers pass
NULL), `CVE-2026-124709` (QUIC stream-state retention), `CVE-2026-35190` (TLS 1.3 key-share
out-of-bounds read with the Suite B certificate flag), plus two QUIC issues from the 2026-11-10 batch.
None of the affected code paths exist in the product because OpenSSL is not linked at all. **No patch
is needed**; only a build host's own `openssl`/`appimagetool` matters, and those are not part of the
artifact.

### Other components

* **SQLite (via sqlite-jdbc 3.53.2.1)**: `CVE-2026-11822` / `CVE-2026-11824` (FTS5 memory corruption) affect
  SQLite *before* 3.53.2; this driver already bundles 3.53.2, so the catalog is not affected. The
  schema also creates no FTS5 tables and eyeShell executes only its own statements, never SQL from a
  peer. Re-check at the next sqlite-jdbc bump.
* **MINA SSHD 2.20.0**: `CVE-2026-77185` (asynchronous authentication can skip signature
  verification) and `CVE-2026-93994` (a duplicate public key can satisfy multi-key authentication) both
  live in the **server-side** authentication path, and eyeShell's client never executes either. The
  bump from 2.19.0 was taken anyway as baseline hygiene: the two new artifact families were verified
  against an independent Maven Central download before their SHA-256 values entered
  `gradle/verification-metadata.xml`, and the embedded-client/server SSH tests pass unchanged on
  2.20.0.
* **Gradle 9.5.0 / Kotlin 2.4.10**: build-time only; they never run in a user's process.
* **JediTerm**: the fork tracks upstream 3.76 with local patches; it parses untrusted terminal output
  by design, so a parser CVE in upstream is in scope. Watch upstream releases together with the
  planned rebases.

## Handling rules

* Secrets (passwords, passphrases, private keys) never enter source control, the SQLite catalog, the
  log, a diagnostics dump, or an example. The terminal log holds lifecycle messages only, and
  `--capture-terminal-bytes` stays opt-in because terminal output may contain secrets.
* Dependency verification stays strict (`gradle/verification-metadata.xml`); a new artifact needs a
  reviewed SHA-256, never a relaxed verification mode.
* `mavenLocal()` stays forbidden and no installer is piped into a shell.

## Checklist for the next release

1. Read the Oracle CPU advisory for the pinned Java line and bump the pin if a security release exists.
2. Read <https://openssl-library.org/news/vulnerabilities/> — even though OpenSSL is not shipped, a
   High/Critical entry is worth recording here with the reason it does not apply.
3. Re-check MINA SSHD, SQLite JDBC, FlatLaf, and JediTerm upstream for advisories.
4. Record the outcome in this file with the review date and the artifact versions of that release.
