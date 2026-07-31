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
- Apache MINA SSHD 2.x
- SQLite + Xerial JDBC
- RE2/J-compatible Regex engine
- Windows Credential Manager
- Freedesktop Secret Service
- `jlink` + `jpackage`

## Documentation

- [產品規格書](docs/PRODUCT_SPEC.md)

## Status

目前處於產品規格與架構基線整理階段，尚未開始正式功能實作。
