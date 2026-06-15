# Generates a release signing keystore for the app.
# Run ONCE. Keep keystore/release.jks and keystore.properties out of version control.
#
# Usage (from project root):
#   .\scripts\generate-keystore.ps1
#
# Then enter passwords and your name when prompted. Same passwords for
# keystore + key are fine for personal projects.

param(
    [string]$KeystorePath = "keystore/release.jks",
    [string]$KeyAlias = "steamcontroller",
    [int]$ValidityYears = 25
)

$ErrorActionPreference = "Stop"

# Resolve keytool from JAVA_HOME, JDK_HOME, or PATH
$keytool = $null
foreach ($candidate in @($env:JAVA_HOME, $env:JDK_HOME)) {
    if ($candidate -and (Test-Path "$candidate\bin\keytool.exe")) {
        $keytool = "$candidate\bin\keytool.exe"
        break
    }
}
if (-not $keytool) {
    $keytool = (Get-Command keytool.exe -ErrorAction SilentlyContinue)?.Source
}
if (-not $keytool) {
    Write-Error "keytool not found. Install a JDK (Android Studio bundles one in jbr/) and set JAVA_HOME, or add keytool to PATH."
    exit 1
}
Write-Host "Using keytool: $keytool"

if (Test-Path $KeystorePath) {
    Write-Error "Keystore already exists at $KeystorePath. Delete it manually if you really want to regenerate (this invalidates all previous signed APKs)."
    exit 1
}

New-Item -ItemType Directory -Force -Path (Split-Path $KeystorePath -Parent) | Out-Null

Write-Host ""
Write-Host "=== Release keystore generation ===" -ForegroundColor Cyan
Write-Host "You'll be prompted for two passwords (use the SAME for both — simpler) and identity fields."
Write-Host "REMEMBER these passwords. Losing them means losing the ability to update your app."
Write-Host ""

& $keytool -genkey -v `
    -keystore $KeystorePath `
    -keyalg RSA `
    -keysize 4096 `
    -validity ($ValidityYears * 365) `
    -alias $KeyAlias

if ($LASTEXITCODE -ne 0) {
    Write-Error "keytool failed."
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "Keystore created at $KeystorePath" -ForegroundColor Green
Write-Host ""
Write-Host "Next step: create keystore.properties at the project root with:" -ForegroundColor Yellow
@"
storeFile=$KeystorePath
storePassword=<your-password>
keyAlias=$KeyAlias
keyPassword=<your-password>
"@ | Write-Host
Write-Host ""
Write-Host "Both keystore.properties and keystore/ are gitignored — they MUST stay local." -ForegroundColor Yellow
