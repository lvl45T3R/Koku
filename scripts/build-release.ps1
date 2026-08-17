[CmdletBinding()]
param(
    [ValidateSet('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')]
    [string[]]$Abis = @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'),
    [switch]$SkipNative,
    [switch]$SkipApk
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = Split-Path -Parent $PSScriptRoot
$ndk = Join-Path $repo '.tooling\android-ndk-r26b'
$cmakeDir = Join-Path $repo '.tooling\cmake-3.27.9-windows-x86_64\bin'
$ninjaDir = Join-Path $repo '.tooling\ninja-1.13.2'
$llvmDir = Join-Path $repo '.tooling\llvm-18.1.8\bin'
$cargoDir = Join-Path $env:USERPROFILE '.cargo\bin'

$required = @(
    (Join-Path $ndk 'source.properties'),
    (Join-Path $cmakeDir 'cmake.exe'),
    (Join-Path $ninjaDir 'ninja.exe'),
    (Join-Path $llvmDir 'libclang.dll'),
    (Join-Path $cargoDir 'cargo.exe')
)
foreach ($path in $required) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing pinned build tool: $path. Follow BUILD_TOOLCHAIN.md once; this script never downloads tools."
    }
}

$ndkSha1 = (Get-FileHash (Join-Path $repo '.tooling\android-ndk-r26b-windows.zip') -Algorithm SHA1).Hash
if ($ndkSha1 -ne '17453C61A59E848CFFB8634F2C7B322417F1732E') {
    throw "NDK r26b archive checksum mismatch: $ndkSha1"
}

$env:ANDROID_NDK_HOME = $ndk.Replace('\', '/')
$env:CMAKE = (Join-Path $cmakeDir 'cmake.exe').Replace('\', '/')
$env:CMAKE_GENERATOR = 'Ninja'
$env:CMAKE_MAKE_PROGRAM = (Join-Path $ninjaDir 'ninja.exe').Replace('\', '/')
$env:LIBCLANG_PATH = $llvmDir
$env:Path = "$cmakeDir;$ninjaDir;$llvmDir;$cargoDir;$env:Path"

$toolchainBin = (Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin').Replace('\', '/')
$matrix = @{
    'arm64-v8a'   = @{ Target = 'aarch64-linux-android'; Linker = 'aarch64-linux-android24-clang.cmd' }
    'armeabi-v7a' = @{ Target = 'armv7-linux-androideabi'; Linker = 'armv7a-linux-androideabi24-clang.cmd' }
    'x86'         = @{ Target = 'i686-linux-android'; Linker = 'i686-linux-android24-clang.cmd' }
    'x86_64'      = @{ Target = 'x86_64-linux-android'; Linker = 'x86_64-linux-android24-clang.cmd' }
}

if (-not $SkipNative) {
    foreach ($abi in $Abis) {
        $target = $matrix[$abi].Target
        $targetKey = $target.Replace('-', '_')
        [Environment]::SetEnvironmentVariable("CC_$targetKey", "$toolchainBin/clang.exe", 'Process')
        [Environment]::SetEnvironmentVariable("CXX_$targetKey", "$toolchainBin/clang++.exe", 'Process')
        [Environment]::SetEnvironmentVariable("AR_$targetKey", "$toolchainBin/llvm-ar.exe", 'Process')
        [Environment]::SetEnvironmentVariable("CARGO_TARGET_$($targetKey.ToUpperInvariant())_LINKER", "$toolchainBin/$($matrix[$abi].Linker)", 'Process')

        Push-Location (Join-Path $repo 'native')
        try {
            & cargo build --locked --target $target --release
            if ($LASTEXITCODE -ne 0) { throw "Native build failed for $abi" }
        } finally {
            Pop-Location
        }

        $source = Join-Path $repo "native\target\$target\release\libaether_android.so"
        $destination = Join-Path $repo "app\src\main\jniLibs\$abi\libaether_android.so"
        New-Item -ItemType Directory -Force (Split-Path -Parent $destination) | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination -Force
    }
}

if (-not $SkipApk) {
    $requested = $Abis -join ','
    Push-Location $repo
    try {
        & .\gradlew.bat assembleRelease "-PkokuAbis=$requested" --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'Gradle release build failed' }
    } finally {
        Pop-Location
    }
}

Write-Host "Build complete for: $($Abis -join ', ')"
