@echo off
setlocal

rem Runs gradlew-local.ps1 from cmd.exe, which cannot execute .ps1 files directly
rem (their file association opens an editor). PowerShell 7 is required: Windows
rem PowerShell 5.1 is deliberately not used.

set "SCRIPT_DIR=%~dp0"
set "PS_SCRIPT=%SCRIPT_DIR%gradlew-local.ps1"

if not exist "%PS_SCRIPT%" (
  echo gradlew-local.ps1 was not found next to this wrapper: %PS_SCRIPT%
  exit /b 1
)

set "PWSH="
for %%I in (pwsh.exe) do if not "%%~$PATH:I"=="" set "PWSH=%%~$PATH:I"
if not defined PWSH if exist "%ProgramFiles%\PowerShell\7\pwsh.exe" set "PWSH=%ProgramFiles%\PowerShell\7\pwsh.exe"
if not defined PWSH if exist "%LOCALAPPDATA%\Microsoft\WindowsApps\pwsh.exe" set "PWSH=%LOCALAPPDATA%\Microsoft\WindowsApps\pwsh.exe"

if not defined PWSH (
  echo PowerShell 7 ^(pwsh^) was not found on PATH or in the default install locations.
  echo Install PowerShell 7, then retry. Windows PowerShell 5.1 is not supported.
  exit /b 1
)

"%PWSH%" -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%" %*
exit /b %ERRORLEVEL%
