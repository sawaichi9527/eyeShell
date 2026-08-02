# eyeShell 產品規格書

- 文件版本：v0.7
- 狀態：M1C SSH Authentication Baseline
- 更新日期：2026-08-01
- 專案：`sawaichi9527/eyeShell`

## 1. 產品定位

**eyeShell** 是一套 Local-first、跨 Windows 與 Linux 的 SSH Console 工作台，面向遠端 Linux 主機、現代嵌入式設備、實驗室設備與產品測試環境。

產品核心不是單純「連得上 SSH」，而是強化大量 Console Log 的閱讀、搜尋、完整複製、匯出、Regex 顏色標記，以及 SSH 延伸能力整合。

### 1.1 名稱含義

`eye` 具有雙重含義：

1. 發音接近 `AI`，代表本專案將採用 AI 輔助程式碼產生與開發流程。
2. `eye` 代表眼睛與視覺，呼應使用者查看 SSH Console Log，並透過顏色與樣式快速辨識重要輸出。

`Shell` 代表使用者透過 SSH 進入遠端 Shell 或 CLI 的主要操作情境。

> AI-assisted development, clearer visibility into shell output.

### 1.2 一句話描述

> eyeShell 是專注於 SSH 的視覺化遠端 Console 工作台，強化 Log 搜尋、完整複製、Regex 顏色標記、SFTP 與主機監控。

---

## 2. 產品原則

1. **SSH only**：核心協定僅支援 SSH。
2. **Local-first**：主機、設定、規則與歷史資料預設保存在本機。
3. **Console visibility first**：輸出閱讀、搜尋、標記、複製與匯出是主要差異化能力。
4. **核心功能不依賴 GPU**：GPU 僅為效能增強；軟體渲染下核心功能仍需可用。
5. **64-bit only**：Windows 與 Linux 僅支援 64-bit 作業系統與 Runtime。
6. **Wayland first, X11 fallback**：Linux 以 Wayland 為主要方向，同時接受 XWayland 與完整 X11 環境。
7. **Protocol capability separation**：Terminal、SFTP、Remote Exec、Monitoring 與 Port Forwarding 應使用清楚的能力介面。
8. **安全預設**：密碼、Private Key Passphrase 等 Secret 不得明文存入 SQLite。
9. **不為低優先功能增加首版依賴**：RDP 與雲端 Relay 不得影響核心架構與安裝包。
10. **開發工具鏈隔離**：本機開發所需的 JDK、Gradle、Kotlin compiler 與 dependency cache 預設限制在專案目錄，不修改作業系統全域環境。

---

## 3. 功能範圍

### 3.1 核心功能

- SSH Terminal
- Host、群組與標籤管理
- Password、Public Key、Keyboard-interactive 與 `ssh-agent` 驗證
- Known Hosts 與 Host Key Fingerprint 驗證
- 多分頁 Session
- Terminal scrollback
- 全選目前 Session 的完整輸出
- 一鍵複製全部輸出
- 另存全部輸出
- Terminal 內容搜尋
- 自訂 Regex 顏色與樣式標記
- 本地 SQLite 設定儲存
- OS Credential Store 整合
- Windows／Linux 64-bit 安裝包
- 顯示後端偵測與相容模式

### 3.2 高優先功能

- SFTP 檔案瀏覽與傳輸
- Jump Host／ProxyJump
- SOCKS／HTTP Proxy
- Local／Remote／Dynamic Port Forwarding
- Linux Agentless Monitoring
- Command Snippets
- Workspace／Host Set

### 3.3 中優先功能

- 多主機批次命令
- Session Log
- Log Analyzer View
- Highlight 規則範本
- Host 狀態總覽
- 本地設定匯入／匯出

### 3.4 低優先、後續重新評估

- RDP
- 廠商雲端 SSH Relay／加速服務
- Linux ARM64
- 原生 GPU Terminal Renderer
- 加密離線 Credential Vault

### 3.5 明確移除或不支援

- 雲端同步
- Telnet
- UART／Serial Port／COM Port
- 本機 TTY Device
- Windows 32-bit
- Linux i386／i686
- 32-bit JVM
- 32-bit Native Library
- Windows 7／8／8.1
- 首版 Windows ARM64
- 首版 Linux ARM64
- 首版 Snap／Flatpak

---

## 4. 支援平台

## 4.1 Windows

### 正式支援

- Windows 10 x64
- Windows 10 Enterprise LTSC 2019 x64 以上
- Windows 11 x64

### 建議最低基線

- Windows 10 Build 17763
- 64-bit 作業系統
- 能正常安裝與流暢操作 Windows 10 的硬體

### 相容性原則

- 不以特定 CPU 或 GPU 型號建立白名單。
- 不為已無法正常操作 Windows 10 的舊電腦提供專門相容版本。
- 不主動要求 AVX、AVX2 或 SSE4.2；Native Binary 應避免不必要的現代指令集門檻。
- 顯示加速失敗時，核心功能必須可切換至 Java2D/GDI 或 Software 路徑。

## 4.2 Linux

### 主要開發與驗證平台

- Ubuntu 26.04.x LTS
- x86_64
- GNOME Wayland Session

### 最低相容平台

- Ubuntu 24.04.x LTS
- x86_64
- X11／Xorg Session

### 支援路徑

1. 原生 Wayland：主要演進方向。
2. Wayland Desktop + XWayland：正式 fallback。
3. 完整 X11／Xorg：最低正式支援環境。

### 初期不保證

- 所有 Linux Distribution
- 所有 Wayland Compositor
- ARM64
- Snap／Flatpak 沙盒環境

---

## 5. 技術棧基線

| 項目 | 建議方案 |
|---|---|
| 主要語言 | Kotlin/JVM 2.4.10 |
| Build | Gradle 9.5.0 + Kotlin DSL |
| Bytecode baseline | Java 21 |
| Development JDK | Eclipse Temurin 21.0.12+8 x64 |
| Windows Runtime | 經驗證的 JBR/OpenJDK 21 x64 |
| Linux Runtime | 支援 WLToolkit 的經驗證 JBR 25 x64，或後續等效 Runtime |
| UI | Swing + FlatLaf 3.7.2 |
| Test | JUnit Jupiter 6.1.2 |
| Layout | MigLayout 或自有 Layout abstraction |
| Terminal | JediTerm 3.74 pinned core/ui source |
| SSH/SFTP | Apache MINA SSHD 2.19.0 |
| 即時 Regex | Google RE2/J 1.8 |
| Database | SQLite + Xerial SQLite JDBC |
| Windows Secret | Windows Credential Manager |
| Linux Secret | Freedesktop Secret Service |
| Packaging | `jlink` + `jpackage` |

### 5.1 不採用 Compose 作為首版主 UI

首版不以 Compose Multiplatform Desktop 作為主 UI，原因如下：

- JediTerm 原生為 Swing 元件。
- Compose + Swing Interop 會增加 Focus、IME、Popup 與渲染層問題。
- Skiko／Skia 增加 Native Library 與 GPU 相容矩陣。
- 首版優先目標是 Terminal 穩定、大量輸出處理與平台相容性。

### 5.2 Project-local 開發工具鏈

本機開發環境預設採完整 project-local 隔離。除非使用者明確同意，不得透過系統套件管理器或全域工具管理器安裝 JDK、Gradle 或 Kotlin，也不得永久修改系統或使用者層級的 `JAVA_HOME`、`KOTLIN_HOME`、`PATH`。

預定目錄：

```text
eyeShell/
├─ .local/
│  ├─ jdk-21/
│  └─ gradle-home/
├─ gradle/wrapper/
├─ gradlew
├─ gradlew.bat
└─ scripts/
   ├─ bootstrap-jdk.sh
   ├─ bootstrap-jdk.ps1
   ├─ gradlew-local.sh
   └─ gradlew-local.ps1
```

必須遵守：

- Project-local JDK 放在 `.local/jdk-21/`，不得提交 Binary 到 Git。
- `GRADLE_USER_HOME` 指向 `.local/gradle-home/`，使 Gradle distribution、dependency cache、daemon state 與 Kotlin Plugin 留在專案內。
- Kotlin compiler 由鎖定版本的 Gradle Plugin 提供，不要求全域 Kotlin 安裝。
- Local launcher 只能為該次子程序設定 `JAVA_HOME`、`PATH` 與 `GRADLE_USER_HOME`，不得修改 shell profile、Registry 或系統環境變數。
- JDK 與其他下載型 toolchain 必須鎖定來源與版本，並驗證供應商發布的 SHA-256 或等效 checksum。
- Gradle Plugin、Kotlin compiler 與 Maven dependencies 必須使用 Repository 內的 Gradle dependency verification metadata 記錄 SHA-256；新增或升級依賴時必須審查 metadata diff。
- 不使用 `curl | sh`、`wget | sh` 或其他未先驗證內容的 pipe-to-shell 安裝方式。
- Gradle repositories 不使用 `mavenLocal()`，避免隱性依賴開發者家目錄中的 artifact。
- 刪除 `.local/`、`.gradle/` 與各 module 的 `build/` 後，應可移除所有專案開發 toolchain、cache 與編譯產物。
- CI 可使用 runner 提供的隔離式 JDK/tool cache，但 Build 必須由 Repository 內的 Gradle Wrapper 驅動。
- 正式安裝包透過 `jlink`／`jpackage` 附帶經驗證 Runtime，不依賴使用者已安裝 Java。
- 若 Wayland、Secret Service 或 Packaging 驗證需要額外系統套件，必須先說明用途並取得使用者同意，不得由 bootstrap script 自動安裝。

M0 toolchain checksum：

| Artifact | SHA-256 |
|---|---|
| Temurin 21.0.12+8 Linux x64 tar.gz | `e4446ff06a276155697597cc0f1b15da004ff083f4964a35271ecee567177370` |
| Temurin 21.0.12+8 Windows x64 ZIP | `9ba963ee2371874a74185d18bc7bb2ab9407df7683300855ed7606e0662321d0` |
| Gradle 9.5.0 binary distribution | `553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746` |
| Gradle 9.5.0 Wrapper JAR | `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7` |

---

## 6. 整體架構

### 6.1 Desktop Workbench Layout

桌面框架參考 [FinalShell 官方介紹頁](https://www.hostbuf.com/t/988.html) 所呈現的高密度伺服器工作台資訊架構，但採用 eyeShell 自有實作、視覺語言與產品範圍：

```text
┌─ Monitor ─┬────────── Session Tabs ──────────┐
│           │                                  │
│ 常駐左欄   │         Terminal Workspace       │
│           │                                  │
│           ├─ Command Input ─── Quick Tools ──┤
│           │ SFTP / Commands 可收合工具區      │
└───────────┴──────────────────────────────────┘
```

布局原則：

- 左側 Monitor 區位於 Session pages 外層並保持常駐，不提供整欄收合；寬度可由使用者調整。
- Session tabs 只顯示在右側工作區頂部，不得跨越或包住 Monitor 區。
- 中央 Terminal workspace 是主要伸展區域，優先保留顯示寬度與高度。
- Quick Tools 按鈕位於 Command Input 同一列右方，不建立額外的獨立按鈕列。
- 底部 SFTP／Commands dock 預設收合，需要時向上展開。
- 未連線時只顯示明確 Empty State，不顯示偽造的 CPU、記憶體、網路、磁碟或檔案資料。
- Host／Connection Manager 由工作台入口開啟，不永久占用 Monitor 欄位。
- 首版預設使用 FlatLaf 深色工作台，後續可提供主題切換。
- M0 只建立布局、可調整分隔與 dock 展開／收合；SSH、Terminal emulation、SFTP、Monitoring 與 Commands 功能在後續里程碑實作。

參考邊界：

- 不複製 FinalShell 的程式碼、圖示、圖片、配色資產、文案、品牌或其他受保護素材。
- 不因布局參考加入雲端同步、RDP、SSH 加速、Zmodem、macOS／ARM 支援或其他 eyeShell 規格外能力。

### 6.2 Capability Architecture

```text
eyeShell Desktop Application
│
├─ UI Shell
│  ├─ Host Tree
│  ├─ Terminal Tabs
│  ├─ SFTP Panel
│  ├─ Monitoring Dashboard
│  └─ Settings / Highlight Rule Editor
│
├─ Application Services
│  ├─ Session Manager
│  ├─ Transfer Manager
│  ├─ Highlight Manager
│  ├─ Monitor Scheduler
│  └─ Workspace / Command Manager
│
├─ Terminal Layer
│  ├─ Terminal Parser
│  ├─ Main Screen Buffer
│  ├─ Alternate Screen Buffer
│  ├─ Scrollback Buffer
│  ├─ Selection / Copy / Export
│  └─ Regex Highlight Overlay
│
├─ SSH Layer
│  ├─ SSH Transport
│  ├─ Shell Channel
│  ├─ SFTP Channel Pool
│  ├─ Remote Exec Channel
│  ├─ Proxy / Jump Host
│  └─ Port Forwarding
│
├─ Platform Services
│  ├─ Credential Store
│  ├─ Clipboard
│  ├─ File Chooser
│  ├─ Notifications
│  └─ Display Backend Detection
│
└─ Persistence
   ├─ SQLite
   ├─ Local Session Logs
   └─ Import / Export
```

---

## 7. 模組拆分

```text
app-desktop
app-ui
app-settings

terminal-api
terminal-buffer
terminal-jediterm
terminal-highlight
terminal-export

ssh-api
ssh-mina
ssh-agent
ssh-proxy
ssh-sftp

monitor-core
monitor-linux

storage-api
storage-sqlite
storage-migration

secrets-api
secrets-windows
secrets-linux

platform-api
platform-windows
platform-linux

packaging-windows
packaging-linux

optional-rdp-api
optional-relay-api
```

`optional-rdp-api` 與 `optional-relay-api` 只允許保留介面，不得在首版加入 FreeRDP、Relay SDK 或雲端後端依賴。

---

## 8. SSH 核心方案

### 8.1 SSH Engine

採用 Apache MINA SSHD 2.19.0 作為主要 SSH/SFTP Engine。

### 8.2 驗證方式

第一階段支援：

- Password
- Public Key
- Private Key Passphrase
- Keyboard-interactive
- `ssh-agent`
- Known Hosts
- Host Key Fingerprint 確認

M1B 先完成以下最小 vertical slice：

- Direct TCP
- Password authentication；Password 僅存在本次連線記憶體，不寫入設定、SQLite 或 Log
- 每次連線顯示並確認 Host Key Algorithm 與 SHA-256 Fingerprint
- Session-only Host Key acceptance；尚未宣告為持久化 Known Hosts
- 單一 authenticated transport 上的 interactive PTY shell
- UTF-8 雙向 I/O 與 terminal resize
- connect、host-key confirmation wait 與 authentication 不阻塞 Swing EDT

M1C 增加：

- Public Key authentication，由使用者明確選取 Private Key File
- Optional Private Key Passphrase，僅存在本次連線記憶體，不寫入設定、SQLite 或 Log
- POSIX Private Key File 必須通過 OpenSSH-style strict permission validation
- eyeShell 專用、OpenSSH-compatible Known Hosts file
- Linux 路徑：`$XDG_CONFIG_HOME/eyeShell/known_hosts`，未設定時使用 `~/.config/eyeShell/known_hosts`
- Windows 路徑：`%APPDATA%\eyeShell\known_hosts`，未設定時使用 `%USERPROFILE%\AppData\Roaming\eyeShell\known_hosts`
- Unknown Host Key 經使用者確認後才 append；若無法安全寫入則連線失敗
- Changed Host Key 一律拒絕，顯示 expected／actual SHA-256 fingerprint，不提供自動覆寫
- POSIX app directory／file 權限分別限制為 `0700`／`0600`；拒絕 app directory 或 Known Hosts file symbolic link

M1D 增加：

- Keyboard-interactive server challenge；name、instruction、prompt 與 echo flag 顯示給使用者，response 僅存在本次 authentication 記憶體
- Keyboard-interactive 的 human-response wait 不計入 authentication network timeout；network phase 仍有明確 timeout，dialog 會在 timeout、disconnect 或 controller close 時關閉
- 明確選取的 `ssh-agent` authentication，不自動 fallback 至其他驗證方式
- Linux 僅使用繼承的 `SSH_AUTH_SOCK` Unix-domain socket；不掃描其他 socket、不啟動或設定 agent
- Windows 僅使用既有 OpenSSH `\\.\pipe\openssh-ssh-agent` named pipe；不安裝、啟動或設定服務
- Agent 僅允許列舉 public identity 與簽章；不支援新增／移除 key 或 agent forwarding
- Agent frame 上限為 256 KiB，I/O wait 有明確 timeout

Windows named pipe 透過 pinned Temurin 21／OpenJDK 的 asynchronous file-channel 實作，屬於 runtime-specific 行為；必須通過 Windows 10／11 x64 實機驗證才可宣告 Windows agent support 完成。

### 8.3 Transport 共用

每一個已登入 Host Session 應以一條 SSH Transport 為主，並透過多個 Channel 提供不同能力：

```text
SSH Transport
├─ Shell Channel
├─ SFTP Channel Pool
├─ Monitor Exec Channel
├─ Local Forward Channel
├─ Remote Forward Channel
└─ Dynamic Forward Channel
```

Terminal、SFTP 與監控不得預設各自建立完全獨立的 SSH TCP 連線。

### 8.4 Transport Provider

```text
TransportProvider
├─ Direct TCP
├─ SOCKS Proxy
├─ HTTP Proxy
└─ Jump Host
```

Cloud Relay Transport 不列入首版。

---

## 9. Terminal 解決方案

### 9.1 Terminal Engine

首版採用 JediTerm。M1A 以官方 Repository 的 pinned submodule 提供未修改的 core/ui source。M1E-A 因 alternate screen 期間必須安全讀取 retained main buffer，建立 project-controlled minimal fork。

M1A 鎖定：

- Upstream：`JetBrains/jediterm`
- Version：`3.74`
- Commit：`377b76e682a5f86bcbb18a318386f530dbebf5c1`
- License option：Apache-2.0
- Included source：`core`、`ui`
- Excluded：JediTerm standalone、Pty4J 與 local terminal support

M1E-A fork：

- Repository：`sawaichi9527/jediterm`
- Upstream base：`377b76e682a5f86bcbb18a318386f530dbebf5c1`
- Pinned fork commit：`38413f9707accfd389d00ceba8cd1f6678b14192`
- Buffer patch：在 model lock 內擷取 immutable main-buffer line snapshot，alternate screen active 時讀取 retained main buffer
- Selection patch：revisioned retained-main bounds、active／all-main／search-main selection scope
- Search patch：popup override、coordinate result rendering 與 custom Find handler

Fork 原則：

- 鎖定經驗證 Commit。
- 僅維護必要修改。
- 不直接追蹤 upstream master。
- Buffer、Selection、Copy 與 Highlight patch 需獨立紀錄。

### 9.2 Terminal 抽象介面

UI 不得直接依賴 JediTerm 具體實作。M1A 先建立目前 vertical slice 所需的最小邊界：

```kotlin
interface TerminalView : AutoCloseable {
    val component: JComponent
    fun attach(session: TerminalSession)
    fun captureAllOutput(): TerminalOutputSnapshot
    fun writeAllOutput(writer: Writer) = captureAllOutput().writeTo(writer)
    fun setContextActions(actions: TerminalContextActions)
    fun selectVisible()
    fun selectAllOutput()
    fun showSearch()
    fun clearScrollback()
}
```

Phase 1 依功能實作進度擴充 `selectVisible`、`selectAllOutput`、`copySelection`、`copyAllOutput` 與 `applyHighlightRules`，不預先加入空實作。第一版實作為 `JediTermTerminalView`。未來若有足夠效益，才評估 Skia 或 WebGL Renderer。

### 9.3 Buffer 分離

Terminal 必須區分：

- Main Screen Buffer
- Alternate Screen Buffer
- Scrollback Buffer
- Raw Session Log

Alternate Screen 用於 `vim`、`top`、`less`、`tmux` 等程式，不應將每次畫面刷新全部累積進一般 scrollback。

---

## 10. 完整輸出選取、複製與匯出

### 10.1 右鍵選單

```text
複製
貼上
────────────
全選可見畫面
全選所有輸出
複製所有輸出
另存所有輸出...
────────────
搜尋...
新增顏色標記規則...
管理顏色標記規則...
────────────
清空緩存
```

### 10.2 全選所有輸出

選取範圍為：

```text
scrollback 第一個邏輯字元
→
目前主畫面最後一個字元
```

不是只選取目前可視畫面。

### 10.3 複製所有輸出

- 不需先建立視覺 Selection。
- 預設輸出純文字。
- 預設移除 ANSI 控制碼。
- 還原因畫面寬度造成的 soft-wrap。
- 保留遠端實際輸出的 hard line break。

### 10.4 大量輸出

- 不在 Swing Event Dispatch Thread 組合大型字串。
- 大型複製與匯出使用背景工作。
- 超過剪貼簿合理大小時，提示改用「另存所有輸出」。
- 寫檔由 immutable retained-line snapshot 逐行串流，不再組合第二個單一完整 `String`；snapshot memory 納入大量輸出效能驗收。

M1E-A 增加：

- `Copy All Output` 與 `Save All Output...` terminal context actions
- Main／alternate screen 都從 immutable retained-main-buffer text snapshot 輸出；snapshot 後的 Writer／檔案 I/O 不持有 terminal model lock
- Copy All 在背景組合文字，不建立視覺 Selection；上限為 8,388,608 UTF-16 code units，超限時提示改用 Save All
- Save All 使用 UTF-8、同目錄 temporary file 與 atomic replace；檔案系統不支援 atomic move 時 fail closed 並保留既有目標檔
- Window close 取消背景工作；若 snapshot capture 正在進行，只延後 `TerminalView.close` 至 capture 完成，不阻塞 EDT
- 完整 context-menu 排序、全選可見、全選所有輸出與 Search hardening 留在 M1E-B

M1E-B 增加：

- 依 10.1 順序建立單一 terminal popup provider；尚未實作的 Highlight actions 顯示為 disabled，不開啟假流程
- Select Visible 只選目前 active viewport；Select All Output 使用 O(screen-height) retained-main bounds，不在 EDT 複製完整文字
- Select All Output 即使在 alternate screen active 時仍保留 main selection；不將 main selection 畫到 `vim`／`top` 上，Copy 走背景 Copy All
- Search 使用 150 ms debounce、background immutable main snapshot、cooperative cancellation、query generation 與 main-buffer revision 防止 stale result
- Search 還原 soft-wrap、阻止 hard-line 跨行 match、移除 DWC marker 並將 Unicode/CJK match 映射回 terminal cell spans
- Alternate screen active 時保留 main search count/result 但不覆蓋 alternate renderer；回到 main screen 後才顯示與導覽
- Search keyboard：Escape 關閉、Enter／Down 下一筆、Up 上一筆

---

## 11. Regex 顏色標記

### 11.1 功能定位

使用者可建立 Regex 規則，對 Console 輸出套用額外的視覺樣式。

每條規則至少包含：

- 名稱
- Regex Pattern
- Case sensitivity
- Enabled
- Priority
- Scope
- Foreground color
- Background color
- Bold
- Italic
- Underline
- Merge／Override mode

### 11.2 Scope

- Global
- Host Group
- Specific Host
- Workspace
- Current Session

### 11.3 處理流程

```text
SSH Bytes
  ↓
Character Decoder
  ↓
ANSI / VT Parser
  ↓
Terminal Logical Line
  ↓
Regex Highlight Engine
  ↓
Render Overlay
```

Regex 標記只能作為 Renderer Overlay，不可把新的 ANSI Code 寫回原始輸出或 Buffer。

### 11.4 第一版限制

- 單行 Regex。
- 增量分析新增或修改行。
- 不支援跨行 Regex。
- 不支援高風險 backtracking 特性。
- 新增規則後先掃描可視範圍，其餘內容以低優先背景工作處理。

M1F Current Session baseline：

- 規則 model 明確限定 `Current Session` scope；Global、Host Group、Specific Host 與 Workspace 留待 persistence/host model 完成後加入
- 使用 Google RE2/J 1.8，拒絕 backreference 等非 RE2-compatible、高風險 backtracking 語法
- RE2 match 在背景 virtual thread 執行，規則替換與 renderer publication 在 Swing EDT；invalid pattern 不替換既有 active rules
- 先以 O(viewport + soft-wrap boundary) 掃描並發布目前 viewport spans，再掃描完整 retained main buffer；未變更 logical-line text 透過 bounded one-generation、每行單一 entry 的 match cache 重用各規則結果
- 沿用 main-buffer revision、generation、cooperative cancellation 與 coalesced model notification，拒絕 stale renderer overlay
- soft-wrap 視為同一 logical line、hard break 阻止跨行 match，DWC marker 不參與 regex 並映射回 Unicode/CJK terminal cells
- Highlight 是獨立 coordinate renderer overlay，繪製順序為 Highlight、Search、Selection、Cursor；alternate screen active 時不顯示 retained-main highlight
- `Merge` 保留原 ANSI style 並疊加規則；`Override` 清除原 ANSI style 與較低優先 highlight，再由規則指定 color/bold/italic/underline
- 規則只存在目前 application session，不寫入 SQLite、log 或其他持久儲存
- Phase 1 預設保留最多 100,000 行 scrollback；持續輸出期間保持 EDT 可回應，highlight 保證在輸出停止後收斂至最終 main-buffer revision

---

## 12. SFTP

使用 Apache MINA SSHD 的 SFTP Client。

第一版目標：

- 目錄瀏覽
- 上傳／下載
- 拖曳
- 新增目錄
- Rename
- Delete
- 權限顯示
- 傳輸佇列
- 失敗重試
- 覆寫確認
- `.part` 暫存檔

設計原則：

- 與 Shell 共用 SSH Transport。
- 使用獨立 SFTP Channel。
- 每台主機可建立小型 Channel Pool。
- 目錄瀏覽與大型傳輸不共用單一 Channel。
- 檔案操作不可阻塞 Terminal UI。

---

## 13. Linux Agentless Monitoring

### 13.1 資料來源

優先使用：

```text
/proc/stat
/proc/meminfo
/proc/loadavg
/proc/net/dev
/proc/diskstats
/proc/<pid>/stat
df -P
ss -H
uname
```

遠端命令固定：

```text
LC_ALL=C
LANG=C
```

避免不同語系造成 Parser 不一致。

### 13.2 採樣策略

- 前景主機：較短週期。
- 背景主機：降低更新頻率。
- 非可視 Tab 自動降頻。
- 連線錯誤使用 exponential backoff。
- 不因每次採樣重新建立 SSH Transport。

---

## 14. 本地資料與 Secret

### 14.1 SQLite 儲存內容

- hosts
- host_groups
- tags
- host_tags
- workspaces
- workspace_hosts
- settings
- highlight_rules
- command_snippets
- recent_sessions
- transfer_jobs
- monitor_profiles
- schema_versions

### 14.2 不得存入 SQLite

- Password
- Private Key 內容
- Private Key Passphrase
- Credential Master Key

### 14.3 Secret 儲存

Windows：

- Windows Credential Manager

Linux：

- Freedesktop Secret Service
- GNOME Keyring／KWallet backend

優先順序：

```text
1. ssh-agent
2. OS Credential Store
3. 使用者每次輸入
```

Linux 若沒有 Secret Service，不得退回明文設定檔；只能提供「本次 Session 記住」或每次輸入。

---

## 15. 顯示與相容模式

## 15.1 Windows

正常路徑：

```text
Swing / Java2D
  ↓
Direct3D / DirectDraw
```

相容路徑：

```text
Swing / Java2D
  ↓
GDI / Software
```

相容模式可停用可能有問題的硬體 Pipeline：

```text
-Dsun.java2d.d3d=false
-Dsun.java2d.opengl=false
```

必要時才加入：

```text
-Dsun.java2d.noddraw=true
```

## 15.2 Linux

Wayland 正常路徑：

```text
Swing / AWT
  ↓
WLToolkit
  ↓
Native Wayland
```

Wayland fallback：

```text
Swing / AWT
  ↓
XToolkit
  ↓
XWayland
  ↓
Wayland Compositor
```

X11 環境：

```text
Swing / AWT
  ↓
XToolkit
  ↓
Xorg
```

## 15.3 Safe Mode

提供：

```text
eyeshell --safe-mode
```

Safe Mode 行為：

- Windows 停用 Direct3D/OpenGL。
- Linux 強制 XToolkit。
- 停用動畫與透明效果。
- 降低 Terminal 最大重繪頻率。
- 暫停即時監控圖表。
- 採用較低預設 scrollback。
- 停用非必要擴充功能。

---

## 16. 效能原則

```text
SSH Data Packets
  ↓
Input Queue
  ↓
批次 Character / ANSI Parser
  ↓
Buffer Update
  ↓
Incremental Regex
  ↓
Dirty Region
  ↓
Repaint
```

必須遵守：

- 不逐字 repaint。
- 合併短時間內多個 SSH Packet。
- Terminal 更新率設上限。
- 只 repaint Dirty Region。
- Regex 只分析新增或修改行。
- 匯出使用串流。
- Session Log 使用背景磁碟寫入。
- SSH、SFTP、監控工作不得阻塞 Swing EDT。

初期工程驗證至少應包含：

- 高速 `journalctl -f`。
- Docker Log 大量輸出。
- 10 萬行以上 scrollback。
- 多 Terminal Tab。
- 同時啟用多條 Highlight 規則。
- 大型輸出完整複製與匯出。
- 視窗 Resize 與 HiDPI。

---

## 17. 診斷資訊

「關於／系統資訊」應顯示：

- Operating System
- Architecture
- Java Runtime
- Java Vendor
- AWT Toolkit
- Desktop Session
- `DISPLAY`
- `WAYLAND_DISPLAY`
- `XDG_SESSION_TYPE`
- Java2D Pipeline
- GPU／Driver
- SSH Library Version
- Terminal Engine Version
- Database Version

若上一次未完成 UI 初始化，下一次啟動應提供相容模式選項。

---

## 18. 封裝與發布

### Windows

- MSI
- Portable ZIP：可選
- Bundled 64-bit Runtime

### Linux

- DEB
- Portable `tar.gz`：可選
- Bundled 64-bit Runtime

不發布：

- 32-bit MSI
- 32-bit DEB
- i386 Repository
- ARM64 首版
- Snap 首版
- Flatpak 首版

---

## 19. 階段規劃

### Phase 1：核心 SSH 工作台

- Windows／Linux x86_64 安裝包
- Host、群組、標籤
- SSH Terminal
- Password／Key／Agent
- Known Hosts
- 多 Tab
- 全選所有輸出
- 複製所有輸出
- 另存輸出
- Terminal 搜尋
- 單行 Regex Highlight
- SQLite
- OS Credential Store
- Wayland／XWayland／X11 啟動策略
- Safe Mode

### Phase 2：SFTP 與主機管理

- SFTP
- Transfer Queue
- Jump Host
- SOCKS／HTTP Proxy
- Port Forwarding
- Linux Agentless Monitoring
- Command Snippets
- Workspace

### Phase 3：進階效率工具

- 多主機批次命令
- Session Log
- Log Analyzer
- 跨行規則
- 規則範本
- Host 狀態總覽
- 本地設定匯入／匯出

### Phase 4：低優先重新評估

- RDP 是否仍有需求
- SSH Relay 是否仍有需求
- Linux ARM64 是否仍有需求
- 原生 GPU Terminal Renderer 是否具有足夠效益
- 是否需要加密離線 Credential Vault

---

## 20. 已確認的架構決策

| 決策 | 狀態 |
|---|---|
| 產品名稱 | eyeShell |
| 核心協定 | SSH only |
| Telnet | 移除 |
| UART／Serial／TTY | 移除 |
| 雲端同步 | 移除 |
| RDP | 低優先，後續評估 |
| SSH Relay 加速 | 低優先，後續評估 |
| Windows | Windows 10/11 x64 |
| Linux 最低 | Ubuntu 24.04 LTS x86_64 + X11 |
| Linux 主力 | Ubuntu 26.04 LTS x86_64 + Wayland |
| XWayland | 正式 fallback |
| 32-bit | 完全移除 |
| 主 UI | Swing + FlatLaf |
| Terminal | JediTerm 3.74 project fork pinned at `38413f9` (upstream base `377b76e`) |
| SSH/SFTP | Apache MINA SSHD 2.19.0 |
| 即時 Regex | Google RE2/J 1.8 |
| Database | SQLite |
| Cloud backend | 不建立 |
| 開發工具鏈 | Project-local JDK／Gradle／Kotlin，不修改系統全域環境 |
| Repository License | MIT |
| Desktop 工作台 | 左側 Monitor 位於 pages 外層；右側頂部 Session tabs、中央 Terminal、Command Input 同列 Quick Tools、底部可收合 SFTP／Commands |

---

## 21. 待後續確認

- 正式 Logo、圖示與品牌色。
- Java/JBR 的具體 Distribution 與更新政策。
- Windows Installer 是否同時提供 MSI 與 Portable ZIP。
- Linux 是否同時提供 DEB 與 Portable tar.gz。
- Phase 1 的量化效能門檻與驗收設備矩陣。
- 貢獻政策。
