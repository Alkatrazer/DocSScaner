[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Stop-WithMessage {
    param([string]$Message)
    Write-Host ""
    Write-Host $Message -ForegroundColor Red
    exit 1
}

function Read-ReleasePassword {
    while ($true) {
        $firstSecure = Read-Host "Create a signing password (at least 12 characters: Latin letters, digits, @ . _ + -)" -AsSecureString
        $secondSecure = Read-Host "Repeat the password" -AsSecureString

        $firstPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($firstSecure)
        $secondPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secondSecure)
        try {
            $first = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($firstPtr)
            $second = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secondPtr)
        }
        finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($firstPtr)
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secondPtr)
        }

        if ($first -ne $second) {
            Write-Host "Passwords do not match. Try again." -ForegroundColor Yellow
            continue
        }
        if ($first -notmatch '^[A-Za-z0-9@._+\-]{12,}$') {
            Write-Host "Use at least 12 allowed characters without spaces." -ForegroundColor Yellow
            continue
        }
        return $first
    }
}

$projectDir = $PSScriptRoot
$signingPropertiesPath = Join-Path $projectDir "release-signing.properties"
$documentsDir = [Environment]::GetFolderPath("MyDocuments")
if ([string]::IsNullOrWhiteSpace($documentsDir)) {
    Stop-WithMessage "The Documents folder was not found."
}

$keyDir = Join-Path $documentsDir "DocSScaner-release"
$keyPath = Join-Path $keyDir "DocSScaner-release.p12"
$keyAlias = "docsscaner"
$userProfileDir = [Environment]::GetFolderPath("UserProfile")
$localAppDataDir = [Environment]::GetFolderPath("LocalApplicationData")

$javaCandidates = @(
    $env:JAVA_HOME,
    (Join-Path $userProfileDir ".jdks\corretto-17.0.13")
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$javaHome = $javaCandidates | Where-Object { Test-Path (Join-Path $_ "bin\java.exe") } | Select-Object -First 1
if (-not $javaHome) {
    Stop-WithMessage "Java was not found. Install JDK 17 and run this file again."
}

$sdkCandidates = @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    (Join-Path $localAppDataDir "Android\Sdk")
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$androidHome = $sdkCandidates | Where-Object { Test-Path (Join-Path $_ "platforms") } | Select-Object -First 1
if (-not $androidHome) {
    Stop-WithMessage "Android SDK was not found. Install Android SDK 36 and run this file again."
}

$password = $null
if (-not (Test-Path $signingPropertiesPath)) {
    $password = Read-ReleasePassword
    New-Item -ItemType Directory -Force $keyDir | Out-Null

    if (-not (Test-Path $keyPath)) {
        Write-Host "Creating the permanent signing key..." -ForegroundColor Cyan
        $keytoolPath = Join-Path $javaHome "bin\keytool.exe"
        & $keytoolPath `
            -genkeypair `
            -v `
            -storetype PKCS12 `
            -keystore $keyPath `
            -storepass $password `
            -keypass $password `
            -alias $keyAlias `
            -keyalg RSA `
            -keysize 4096 `
            -validity 10000 `
            -dname "CN=Alkatrazer, O=Alkatrazer, C=RU"
        if ($LASTEXITCODE -ne 0) {
            Stop-WithMessage "The signing key could not be created."
        }
    }

    $properties = @(
        "RELEASE_STORE_FILE=$($keyPath.Replace('\', '/'))"
        "RELEASE_STORE_PASSWORD=$password"
        "RELEASE_KEY_ALIAS=$keyAlias"
        "RELEASE_KEY_PASSWORD=$password"
    )
    $properties | Set-Content -LiteralPath $signingPropertiesPath -Encoding ASCII
}

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidHome

Write-Host ""
Write-Host "Running tests and building the universal APK. This may take several minutes..." -ForegroundColor Cyan
Push-Location $projectDir
try {
    & ".\gradlew.bat" --no-daemon test :app:lintRelease :app:assembleRelease
    if ($LASTEXITCODE -ne 0) {
        Stop-WithMessage "The build failed. Copy the window text and send it to the assistant."
    }
}
finally {
    Pop-Location
}

$apkOutputDir = Join-Path $projectDir "app\build\outputs\apk\release"
$builtApk = Get-ChildItem -LiteralPath $apkOutputDir -Filter "*-universal.apk" -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $builtApk) {
    Stop-WithMessage "The build completed, but the universal APK file was not found."
}

$releaseDir = Join-Path $projectDir "release"
New-Item -ItemType Directory -Force $releaseDir | Out-Null
$rustoreApk = Join-Path $releaseDir "DocSScaner-RuStore.apk"
Copy-Item -LiteralPath $builtApk.FullName -Destination $rustoreApk -Force

Write-Host ""
Write-Host "Done! The RuStore file is here:" -ForegroundColor Green
Write-Host $rustoreApk -ForegroundColor Green
Write-Host ""
Write-Host "The signing key is stored here:" -ForegroundColor Yellow
Write-Host $keyPath -ForegroundColor Yellow
Write-Host "Back up the key and never share it with anyone." -ForegroundColor Yellow
