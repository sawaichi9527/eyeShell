# eyeShell

**A visual SSH console workbench for clearer log inspection.**

`eyeShell` 是一套 Local-first、跨 Windows 與 Linux 的 SSH Console 工作台，面向遠端 Linux 主機、現代嵌入式設備、實驗室設備與產品測試環境。

## Why eyeShell?

`eye` 具有雙重含義：

- 發音接近 **AI**，代表專案採用 AI 輔助程式碼產生與開發流程。
- 代表眼睛與視覺，呼應 SSH Console Log 的閱讀、搜尋與顏色辨識。

產品核心差異化能力包括：

- 全選目前 Session 的完整 scrollback 輸出
- 一鍵複製或匯出全部 Console 內容
- 使用者自訂 Regex 顏色與樣式標記
- SSH Terminal、SFTP、Jump Host 與 Port Forwarding
- Linux Agentless Monitoring
- Terminal 背景圖片與可切換字元編碼（UTF-8／GB18030／GBK／BIG5 等）
- Windows 10/11 x64 與 Ubuntu 24.04/26.04 x86_64
- Linux Wayland 優先，XWayland／X11 fallback

## Scope

### Included

- SSH Terminal
- SFTP
- Password／Public Key／`ssh-agent`
- Known Hosts／Host Key 驗證
- Terminal Search／Scrollback／Copy／Export
- 介面多語言（English／繁體中文／简体中文／日本語）
- 終端機英文字型／中文字型選擇
- 34 組配色（含 iTerm2-Color-Schemes 精選，已驗證可搭配 bg1 背景）
- About 列出實際打包的開源元件、版本與授權
- Terminal 背景圖片（內建 `bg1`–`bg5` 預設，預設 `bg1`）
- Terminal 字元編碼（預設 UTF-8，可切換 GB18030／GBK／BIG5 等）
- Regex Highlight
- Jump Host／Proxy／Port Forwarding
- Linux Agentless Monitoring
- Local SQLite storage
- OS Credential Store

### Excluded

- Telnet
- UART／Serial／TTY
- Cloud Sync
- 32-bit OS／JVM／Native Library

RDP 與雲端 SSH Relay 暫列低優先，首版不實作。

## Supported platform baseline

| Platform | Baseline |
|---|---|
| Windows | Windows 10 Build 17763+ / Windows 11, x64 only |
| Linux minimum | Ubuntu 24.04 LTS x86_64 + X11 |
| Linux primary | Ubuntu 26.04 LTS x86_64 + Wayland |
| Linux fallback | XWayland or full X11/Xorg |

## Proposed technology baseline

- Kotlin/JVM
- Java 21 bytecode baseline
- Swing + FlatLaf
- JediTerm minimal fork
- Apache MINA SSHD 2.19.0
- SQLite + Xerial JDBC
- Google RE2/J 1.8
- Windows Credential Manager
- Freedesktop Secret Service
- `jlink` + `jpackage`

## Development environment isolation

eyeShell 的本機開發工具鏈採 project-local 隔離，不要求開發者將 JDK、Gradle 或 Kotlin 安裝到作業系統全域：

- JDK 21 放在專案的 `.local/jdk-21/`。
- Gradle distribution、dependency cache 與 daemon state 放在 `.local/gradle-home/`。
- Kotlin compiler 由 Gradle Plugin 提供，不安裝全域 Kotlin。
- 專案啟動腳本只在該次程序設定 `JAVA_HOME`、`PATH` 與 `GRADLE_USER_HOME`，不修改使用者或系統設定。
- Toolchain archive 必須鎖定版本並驗證官方 checksum；不得使用未驗證的 pipe-to-shell 安裝方式。
- Kotlin Plugin 與 Maven dependencies 由 `gradle/verification-metadata.xml` 記錄 SHA-256，後續解析採 Gradle strict dependency verification。
- `.local/` 不提交 Git，刪除該目錄即可移除本機 toolchain 與 cache。

CI runner 與正式發布所附帶的 Runtime 是獨立環境，不依賴開發者電腦的全域 Java／Gradle／Kotlin 安裝。

Pinned M0 development versions:

| Component | Version |
|---|---|
| Eclipse Temurin JDK | 21.0.12+8 |
| Gradle Wrapper | 9.5.0 |
| Kotlin JVM Plugin | 2.4.10 |
| FlatLaf | 3.7.2 |
| JUnit Jupiter | 6.1.2 |
| JediTerm | 3.74 (`377b76e682a5f86bcbb18a318386f530dbebf5c1`) |
| Apache MINA SSHD | 2.19.0 |
| Google RE2/J | 1.8 |
| Xerial SQLite JDBC | 3.53.2.1 |
| JNA | 5.19.1 |
| Secret Service | 1.8.1-jdk17 |

### Local setup and validation

Linux x86_64:

```bash
git submodule update --init --recursive
./scripts/bootstrap-jdk.sh
./scripts/gradlew-local.sh test
./scripts/gradlew-local.sh check
./scripts/gradlew-local.sh run
```

Windows x64 PowerShell 7 (`pwsh`):

```powershell
git submodule update --init --recursive
.\scripts\bootstrap-jdk.ps1
.\scripts\gradlew-local.ps1 test
.\scripts\gradlew-local.ps1 check
.\scripts\gradlew-local.ps1 run
```

Windows x64 `cmd.exe`:

```bat
git submodule update --init --recursive
scripts\bootstrap-jdk.cmd
scripts\gradlew-local.cmd test
scripts\gradlew-local.cmd check
scripts\gradlew-local.cmd run
```

`cmd.exe` cannot run a `.ps1` file: the file association opens it in an editor instead of executing it. Always use the `.cmd` wrappers from `cmd.exe`, or start `pwsh` first. The wrappers look for `pwsh` on `PATH` and in the usual install locations, and stop with an explicit error when PowerShell 7 is missing; they never fall back to Windows PowerShell 5.1.

Use `./scripts/gradlew-local.sh --stop`, `.\scripts\gradlew-local.ps1 --stop`, or `scripts\gradlew-local.cmd --stop` to stop the project-local Gradle daemon. The standard `gradlew` and `gradlew.bat` remain available for isolated CI runners.

### Packaging

Linux x86_64 AppImage（單一可執行檔，內含 bundled runtime，不需系統 JDK）：

```bash
./scripts/bootstrap-appimagetool.sh   # 一次即可：鎖定 appimagetool 1.9.0 並驗證 SHA-256
./scripts/gradlew-local.sh appImage
```

輸出為 `build/package/eyeShell-<version>-x86_64.AppImage`。`scripts/bootstrap-appimagetool.sh` 會把 appimagetool 解壓到 `.local/appimagetool/`，打包時不需要 FUSE，也不會安裝系統套件。`jpackageAppImage`（app image 目錄）與 `jpackagePortable`／`jpackageInstaller`（tar.gz／DEB、Windows ZIP／MSI）仍保留，但 AppImage 是本專案 Linux 端的發佈格式。

安裝桌面捷徑與圖示（僅寫入 `$XDG_DATA_HOME`，預設 `~/.local/share`）：

```bash
./scripts/install-linux-shortcut.sh [path/to/eyeShell-<version>-x86_64.AppImage]
./scripts/install-linux-shortcut.sh --remove
```

腳本會安裝 16–512 px 圖示，並以 `eyeshell.desktop` 作為唯一可見的捷徑（`Icon=eyeshell`、`StartupWMClass` 對應 AWT 的視窗類別、以 `APPIMAGELAUNCHER_DISABLE=1` 啟動）。若 AppImageLauncher 已經產生 `appimagekit_*eyeShell*.desktop`，腳本會保留它但加上 `NoDisplay=true`：`appimagelauncherd` 只要看到自己的 entry 還在就不會重建，而 GNOME 只會顯示我們建立的 `eyeshell.desktop`，因此不會再出現 `eyeShell (1)` 齒輪圖示。

AppImage 檔本身可以放在任何資料夾（例如 `~/Applications/` 或隨身碟），不需要安裝，也不綁定資料位置：

```bash
cp build/package/eyeShell-<version>-x86_64.AppImage ~/Applications/
./scripts/install-linux-shortcut.sh ~/Applications/eyeShell-<version>-x86_64.AppImage
```

所有使用者資料都依 XDG 慣例放在 AppImage 之外，因此替換新版本或搬移 AppImage 後仍會沿用同一份資料：

| 內容 | 位置 |
|---|---|
| Saved Hosts、schema（`eyeshell.db`） | `$XDG_DATA_HOME/eyeShell/`（預設 `~/.local/share/eyeShell/`） |
| `settings.properties`、`known_hosts`、`backgrounds/` | `$XDG_CONFIG_HOME/eyeShell/`（預設 `~/.config/eyeShell/`） |
| 密碼 | 作業系統憑證儲存區（GNOME Secret Service／Windows Credential Manager） |

若 AppImageLauncher 在檔案更新後重新整合出新的 `appimagekit_*eyeShell*.desktop`，再執行一次 `install-linux-shortcut.sh`，它會把新 entry 設為 `NoDisplay=true`，只留下 `eyeshell.desktop`。

GNOME 是透過視窗的 `WM_CLASS` 對應 `.desktop` 檔，而 AWT 會把 `WM_CLASS` 設成主類別名稱（`io.github.sawaichi9527.eyeshell.MainKt` → `io-github-sawaichi9527-eyeshell-MainKt`）。因此 `.desktop` 的 `StartupWMClass` 必須是這個值，且 `Icon=eyeshell` 要搭配已安裝的 `eyeshell.png`；只憑視窗的 `_NET_WM_ICON` 不足，GNOME 在找不到對應的 `.desktop` 時會顯示通用齒輪圖示。`TryExec` 刻意不寫，因為 GLib 會拒絕含引號的 `TryExec` 並忽略整個 entry。

若主機安裝了 AppImageLauncher，首次執行新產生的 AppImage 會被它搬移到 `~/Applications/`；自動化驗證時請加上 `APPIMAGELAUNCHER_DISABLE=1`。

## Workbench layout

eyeShell 的桌面工作台參考 [FinalShell 官方介紹頁](https://www.hostbuf.com/t/988.html) 所呈現的資訊架構：左側常駐 Monitor，右側工作區頂部顯示 Session tabs，中央是 Terminal workspace；Command Input 同列右方提供快捷工具按鈕，底部 SFTP／Commands dock 預設收合。這項參考僅限布局概念，不複製 FinalShell 的程式碼、圖示、圖片、品牌或其他介面資產。

## Documentation

- [產品規格書](docs/PRODUCT_SPEC.md)

## Status

M1I Multi-session Tabs baseline 已在 worktree 建立：每次成功連線建立獨立 terminal tab，各自擁有 JediTerm view、完整 scrollback、search、Current Session highlighting、copy/export 與 close lifecycle；關閉單一 tab 不影響其他 sessions。Saved Host schema v2 UUID、Windows Credential Manager／Linux Secret Service password flow、Password／Public Key／keyboard-interactive／`ssh-agent` 與 Known Hosts 已有實作。SFTP、Monitoring、Packaging 與 natural remote-exit tab status 尚未實作；Windows Credential Manager 與其他支援桌面環境仍待驗證。
