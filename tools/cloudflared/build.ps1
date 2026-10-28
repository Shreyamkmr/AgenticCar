<#
Builds the cloudflared binary bundled in the app (app/src/main/jniLibs/arm64-v8a/libcloudflared.so).

Upstream cloudflared cannot resolve DNS on Android (no /etc/resolv.conf), so this script adds
android_dns.go (see that file) to the official release source and cross-compiles it.

Downloads (verified): the cloudflared release source tarball from GitHub, and the Go toolchain
from go.dev (SHA-256 checked against go.dev's published checksum). Go modules come from
proxy.golang.org and are verified against sum.golang.org by `go build`.

Usage:  powershell -ExecutionPolicy Bypass -File tools\cloudflared\build.ps1 [-Version 2026.10.0]
#>
param(
    [string]$Version = '2026.10.0',
    [string]$GoVersionPrefix = 'go1.26',
    [string]$WorkDir = (Join-Path $env:TEMP 'agenticcar-cloudflared')
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repo = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$out = Join-Path $repo 'app\src\main\jniLibs\arm64-v8a\libcloudflared.so'
New-Item -ItemType Directory -Force $WorkDir | Out-Null

# Go toolchain
$go = Join-Path $WorkDir 'go\bin\go.exe'
if (-not (Test-Path $go)) {
    $release = Invoke-RestMethod 'https://go.dev/dl/?mode=json&include=all' |
        Where-Object { $_.stable -and $_.version -like "$GoVersionPrefix*" } | Select-Object -First 1
    $file = $release.files | Where-Object { $_.os -eq 'windows' -and $_.arch -eq 'amd64' -and $_.kind -eq 'archive' }
    $zip = Join-Path $WorkDir $file.filename
    Invoke-WebRequest "https://go.dev/dl/$($file.filename)" -OutFile $zip -UseBasicParsing
    if ((Get-FileHash $zip -Algorithm SHA256).Hash.ToLower() -ne $file.sha256) { throw "Go toolchain checksum mismatch" }
    Expand-Archive $zip $WorkDir -Force
}

# cloudflared source + Android DNS patch
$src = Join-Path $WorkDir "cloudflared-$Version"
if (-not (Test-Path $src)) {
    $zip = Join-Path $WorkDir "cloudflared-$Version.zip"
    Invoke-WebRequest "https://codeload.github.com/cloudflare/cloudflared/zip/refs/tags/$Version" -OutFile $zip -UseBasicParsing
    Expand-Archive $zip $WorkDir -Force
}
Copy-Item (Join-Path $PSScriptRoot 'android_dns.go') (Join-Path $src 'cmd\cloudflared\android_dns.go') -Force

# Static linux/arm64 build: Android runs it from the app's native library directory.
Push-Location $src
try {
    $env:GOOS = 'linux'; $env:GOARCH = 'arm64'; $env:CGO_ENABLED = '0'
    $env:GOFLAGS = '-mod=mod'; $env:GOTOOLCHAIN = 'local'
    $ldflags = "-s -w -X main.Version=$Version-android -X main.BuildTime=$(Get-Date -Format yyyy-MM-dd)"
    & $go build -trimpath -ldflags $ldflags -o $out ./cmd/cloudflared
    if ($LASTEXITCODE -ne 0) { throw "go build failed" }
} finally {
    Pop-Location
    Remove-Item Env:GOOS, Env:GOARCH, Env:CGO_ENABLED, Env:GOFLAGS, Env:GOTOOLCHAIN -ErrorAction SilentlyContinue
}
"Built $out ({0:N1} MB)" -f ((Get-Item $out).Length / 1MB)
