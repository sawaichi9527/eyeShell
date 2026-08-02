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
- Windows 10/11 x64 與 Ubuntu 24.04/26.04 x86_64
- Linux Wayland 優先，XWayland／X11 fallback

## Scope

### Included

- SSH Terminal
- SFTP
- Password／Public Key／`ssh-agent`
- Known Hosts／Host Key 驗證
- Terminal Search／Scrollback／Copy／Export
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

Windows x64 PowerShell:

```powershell
git submodule update --init --recursive
.\scripts\bootstrap-jdk.ps1
.\scripts\gradlew-local.ps1 test
.\scripts\gradlew-local.ps1 check
.\scripts\gradlew-local.ps1 run
```

Use `./scripts/gradlew-local.sh --stop` or `.\scripts\gradlew-local.ps1 --stop` to stop the project-local Gradle daemon. The standard `gradlew` and `gradlew.bat` remain available for isolated CI runners.

## Workbench layout

eyeShell 的桌面工作台參考 [FinalShell 官方介紹頁](https://www.hostbuf.com/t/988.html) 所呈現的資訊架構：左側常駐 Monitor，右側工作區頂部顯示 Session tabs，中央是 Terminal workspace；Command Input 同列右方提供快捷工具按鈕，底部 SFTP／Commands dock 預設收合。這項參考僅限布局概念，不複製 FinalShell 的程式碼、圖示、圖片、品牌或其他介面資產。

## Documentation

- [產品規格書](docs/PRODUCT_SPEC.md)

## Status

M1I Multi-session Tabs baseline 已在 worktree 建立：每次成功連線建立獨立 terminal tab，各自擁有 JediTerm view、完整 scrollback、search、Current Session highlighting、copy/export 與 close lifecycle；關閉單一 tab 不影響其他 sessions。Saved Host schema v2 UUID、Windows Credential Manager／Linux Secret Service password flow、Password／Public Key／keyboard-interactive／`ssh-agent` 與 Known Hosts 已有實作。SFTP、Monitoring、Packaging 與 natural remote-exit tab status 尚未實作；Windows Credential Manager 與其他支援桌面環境仍待驗證。
