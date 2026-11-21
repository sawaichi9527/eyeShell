$ErrorActionPreference = "Stop"

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$JdkVersion = "21.0.13.1+2"
$ArchiveName = "OpenJDK21U-jdk_x64_windows_hotspot_21.0.13.1_2.zip"
$DownloadUrl = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13.1%2B2/$ArchiveName"
$ExpectedSha256 = "bbebea1469996b276683c9299c227b294f85c847039c82633218186a4ad4fbf2"
$LocalDir = Join-Path $RootDir ".local"
$JdkDir = Join-Path $LocalDir "jdk-21"
$DownloadDir = Join-Path $LocalDir "downloads"
$ArchivePath = Join-Path $DownloadDir $ArchiveName
$JavaPath = Join-Path $JdkDir "bin\java.exe"
$ReleasePath = Join-Path $JdkDir "release"

if ([Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne [Runtime.InteropServices.Architecture]::X64) {
    throw "This bootstrap supports Windows x64 only."
}

if (Test-Path $JavaPath) {
    $ReleaseMetadata = if (Test-Path $ReleasePath) { Get-Content $ReleasePath } else { @() }
    if (
        $ReleaseMetadata -contains 'IMPLEMENTOR="Eclipse Adoptium"' -and
        $ReleaseMetadata -contains 'IMPLEMENTOR_VERSION="Temurin-21.0.13.1+2"'
    ) {
        & $JavaPath -version 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw "The pinned JDK metadata is present, but java.exe could not execute."
        }
        Write-Host "Temurin $JdkVersion is already installed at $JdkDir"
        exit 0
    }

    throw "A different JDK already exists at $JdkDir. Remove it explicitly before bootstrapping."
}

New-Item -ItemType Directory -Force $DownloadDir | Out-Null
$TmpDir = Join-Path $LocalDir "tmp"
New-Item -ItemType Directory -Force $TmpDir | Out-Null

if (-not (Test-Path $ArchivePath)) {
    $PartialPath = "$ArchivePath.part"
    Write-Host "Downloading Temurin $JdkVersion to $ArchivePath"
    Invoke-WebRequest -Uri $DownloadUrl -OutFile $PartialPath
    Move-Item $PartialPath $ArchivePath
}

$ActualSha256 = (Get-FileHash -Algorithm SHA256 $ArchivePath).Hash.ToLowerInvariant()
if ($ActualSha256 -ne $ExpectedSha256) {
    throw "Checksum mismatch for $ArchivePath`nExpected: $ExpectedSha256`nActual:   $ActualSha256"
}

$StagingDir = Join-Path $TmpDir ("jdk." + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory $StagingDir | Out-Null

try {
    Expand-Archive -Path $ArchivePath -DestinationPath $StagingDir
    $ExtractedDir = Get-ChildItem -Path $StagingDir -Directory | Select-Object -First 1
    if ($null -eq $ExtractedDir -or -not (Test-Path (Join-Path $ExtractedDir.FullName "bin\java.exe"))) {
        throw "The downloaded archive did not contain a usable JDK."
    }

    Move-Item $ExtractedDir.FullName $JdkDir
}
finally {
    if (Test-Path $StagingDir) {
        Remove-Item -Recurse -Force $StagingDir
    }
}

& $JavaPath -version
Write-Host "Installed project-local Temurin $JdkVersion at $JdkDir"
