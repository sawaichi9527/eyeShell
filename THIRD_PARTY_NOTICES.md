# Third-Party Notices

## iTerm2-Color-Schemes

- Project: iTerm2-Color-Schemes
- Source: https://github.com/mbadolato/iTerm2-Color-Schemes
- Copyright: Michael Badolato and contributors
- License: MIT License (https://github.com/mbadolato/iTerm2-Color-Schemes/blob/master/LICENSE)
- Usage: `src/main/resources/themes/iterm2.properties` is generated from the upstream `schemes/*.itermcolors` files. Only schemes whose foreground keeps at least 4.5:1 contrast against both their own background and the bundled `bg1.jpg` artwork are imported; upstream schemas that duplicate an eyeShell palette are skipped. The generated file keeps the upstream names and is the only runtime dependency.

## Bundled terminal background presets

- Files: `src/main/resources/backgrounds/bg1.jpg` … `bg5.jpg`
- Origin: extracted from the FinalShell 4.6.5 installation on the project owner's machine (`finalshell.jar` → `resources/images/`)
- Copyright: hostbuf.com / FinalShell authors
- License: **not covered by eyeShell's MIT license**
- Status: bundled as the default terminal background presets (`bg1.jpg` is the default) at the project owner's explicit request on 2026-10-30
- Removal path: delete the five files, then adjust `BackgroundImages.BUNDLED_PRESETS` / `DEFAULT_PRESET` and the `Preset` row in `SettingsController` if redistribution ever has to stop

## JediTerm

- Project: JediTerm
- Source: https://github.com/sawaichi9527/jediterm
- Upstream: https://github.com/JetBrains/jediterm
- Version: 3.74
- Upstream base commit: `377b76e682a5f86bcbb18a318386f530dbebf5c1`
- Pinned fork commit: `38413f9707accfd389d00ceba8cd1f6678b14192`
- Copyright: JetBrains and JediTerm contributors
- Selected license: Apache License 2.0
- License text: `third-party/jediterm/LICENSE-APACHE-2.0.txt`

eyeShell compiles only JediTerm's `core` and `ui` source directories. The fork adds immutable retained-main-buffer snapshots, revisioned selection bounds, popup overrides, coordinate-based search rendering, and styled highlight overlays. The standalone application, Pty4J, and local terminal support are not included.

## Apache MINA SSHD

- Project: Apache MINA SSHD
- Source: https://github.com/apache/mina-sshd
- Version: 2.19.0
- Copyright: The Apache Software Foundation
- License: Apache License 2.0
- License text: https://www.apache.org/licenses/LICENSE-2.0

## RE2/J

- Project: RE2/J
- Source: https://github.com/google/re2j
- Version: 1.8
- Copyright: The RE2/J Authors
- License: BSD 3-Clause License
- License text: https://github.com/google/re2j/blob/1.8/LICENSE

## Xerial SQLite JDBC

- Project: Xerial SQLite JDBC
- Source: https://github.com/xerial/sqlite-jdbc
- Version: 3.53.2.1
- Copyright: Xerial Project
- License: Apache License 2.0
- License text: https://github.com/xerial/sqlite-jdbc/blob/3.53.2.1/LICENSE

eyeShell resolves the `without-natives` classes artifact plus the current build platform's Linux or Windows native artifact. Published packages must additionally filter unsupported architectures to preserve the x86_64-only product baseline.

## Java Native Access

- Project: JNA
- Source: https://github.com/java-native-access/jna
- Version: 5.19.1
- Copyright: JNA contributors
- Selected license: Apache License 2.0
- License text: https://github.com/java-native-access/jna/blob/5.19.1/AL2.0

eyeShell uses JNA only for the Windows Credential Manager API boundary.

## Secret Service

- Project: secret-service
- Source: https://github.com/swiesend/secret-service
- Version: 1.8.1-jdk17
- Copyright: Sebastian Wiesendahl and contributors
- License: MIT License
- License text: https://github.com/swiesend/secret-service/blob/v1.8.1-jdk17/LICENSE

eyeShell uses this library only on Linux to access a Freedesktop Secret Service provider over the session D-Bus. It does not provide a plaintext fallback.
