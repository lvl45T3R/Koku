[CmdletBinding()]
param([ValidateSet('arm64-v8a','armeabi-v7a','x86','x86_64')][string[]]$Abis=@('arm64-v8a','armeabi-v7a','x86','x86_64'))
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$go=Join-Path $repo '.tooling/go-1.26.8/go/bin/go.exe'
$ndk=Join-Path $repo '.tooling/android-ndk-r26b/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
$psiphon=Join-Path $repo '.tooling/psiphon-source'
$lyrebird=Join-Path $repo '.tooling/lyrebird-source/gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird@v0.0.0-20260312101154-fc105a03c0e0'
foreach($path in @($go,$ndk,(Join-Path $psiphon 'go.mod'),(Join-Path $lyrebird 'go.mod'))){if(-not(Test-Path -LiteralPath $path)){throw "Missing helper tool/source: $path. See BUILD_TOOLCHAIN.md."}}
$psiphonRevision=git -C $psiphon rev-parse HEAD
if($psiphonRevision -ne '83aa73b9b982e7421e00117f5b0c5aceb5dda452'){throw 'Unexpected Psiphon source revision'}
$env:GOCACHE=Join-Path $repo '.tooling/go-cache'
$env:GOPATH=Join-Path $repo '.tooling/go-path'
$env:GOTOOLCHAIN='local'
$env:GOTELEMETRY='off'
$env:GOOS='android'
$env:CGO_ENABLED='1'
$matrix=@{
 'arm64-v8a'=@{Arch='arm64';Target='aarch64-linux-android24';Arm=''}
 'armeabi-v7a'=@{Arch='arm';Target='armv7a-linux-androideabi24';Arm='7'}
 'x86'=@{Arch='386';Target='i686-linux-android24';Arm=''}
 'x86_64'=@{Arch='amd64';Target='x86_64-linux-android24';Arm=''}
}
foreach($abi in $Abis){
 $env:GOARCH=$matrix[$abi].Arch
 $env:GOARM=$matrix[$abi].Arm
 $env:CC=$ndk.Replace('\','/')+' --target='+$matrix[$abi].Target
 $out=Join-Path $repo "app/src/main/jniLibs/$abi"
 New-Item -ItemType Directory -Force $out | Out-Null
 foreach($helper in @(@{Name='psiphon';Source=$psiphon;Package='./ConsoleClient';Flags='-mod=vendor'},@{Name='lyrebird';Source=$lyrebird;Package='./cmd/lyrebird';Flags='-mod=readonly'})){
  $env:GOFLAGS=$helper.Flags
  Push-Location $helper.Source
  try {
   & $go build -buildmode=pie -trimpath -ldflags '-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384' -o (Join-Path $out "lib$($helper.Name).so") $helper.Package
   if($LASTEXITCODE -ne 0){throw "$($helper.Name) build failed for $abi"}
  } finally {Pop-Location}
 }
 Write-Host "Helpers built: $abi"
}
