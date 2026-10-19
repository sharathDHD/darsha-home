param(
    [string]$OutDir = "out",
    [string]$Keystore = "",
    [string]$KeyAlias = "tvhome"
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$repo = Split-Path -Parent $root
Set-Location $root

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
if (-not (Test-Path $sdk)) { throw "Android SDK not found: $sdk" }

$javaHome = $env:JAVA_HOME
if (-not $javaHome) { $javaHome = "C:\Users\sharath\scoop\apps\temurin17-jdk\current" }
$javac = Join-Path $javaHome "bin\javac.exe"
if (-not (Test-Path $javac)) { throw "javac not found: $javac" }
$env:JAVA_HOME = $javaHome
$env:Path = (Join-Path $javaHome "bin") + ";" + $env:Path

$bt = Get-ChildItem (Join-Path $sdk "build-tools") -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $bt) { throw "No build-tools found under $sdk" }
$aapt2 = Join-Path $bt.FullName "aapt2.exe"
$d8 = Join-Path $bt.FullName "d8.bat"
$zipalign = Join-Path $bt.FullName "zipalign.exe"
$apksigner = Join-Path $bt.FullName "apksigner.bat"

$jar = Join-Path $sdk "platforms\android-34\android.jar"
if (-not (Test-Path $jar)) {
    $p = Get-ChildItem (Join-Path $sdk "platforms") -Directory |
        Sort-Object { [version]($_.Name -replace '^android-', '') } -Descending | Select-Object -First 1
    if (-not $p) { throw "No Android platform found under $sdk" }
    $jar = Join-Path $p.FullName "android.jar"
}

$out = if ([System.IO.Path]::IsPathRooted($OutDir)) { $OutDir } else { Join-Path $root $OutDir }
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
$classes = Join-Path $out "classes"
$dex = Join-Path $out "dex"
New-Item -ItemType Directory -Force -Path $classes, $dex | Out-Null

$resZip = Join-Path $out "res.zip"
$base = Join-Path $out "base.apk"
& $aapt2 compile --dir (Join-Path $root "res") -o $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
& $aapt2 link -o $base -I $jar --manifest (Join-Path $root "AndroidManifest.xml") `
    --min-sdk-version 21 --target-sdk-version 28 $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

$srcFiles = @(Get-ChildItem (Join-Path $root "src") -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })
& $javac --release 8 -classpath $jar -d $classes $srcFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$classFiles = @(Get-ChildItem $classes -Recurse -Filter "*.class" | ForEach-Object { $_.FullName })
& $d8 --min-api 21 --release --lib $jar --output $dex $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

$unsigned = Join-Path $out "unsigned.apk"
Copy-Item $base $unsigned
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, (Join-Path $dex "classes.dex"), "classes.dex",
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
$zip.Dispose()

& $zipalign -f 4 $unsigned (Join-Path $out "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$ks = if ($Keystore) {
    if ([System.IO.Path]::IsPathRooted($Keystore)) { $Keystore } else { Join-Path $repo $Keystore }
} else {
    Join-Path $repo "key.jks"
}
$pass = $env:TVHOME_KEY_PASS
$envFile = Join-Path $repo "keystore.env.ps1"
if (-not $pass -and (Test-Path $envFile)) {
    . $envFile
    $pass = $env:TVHOME_KEY_PASS
}
if (-not $pass) {
    throw "Keystore password not set. Set TVHOME_KEY_PASS or create keystore.env.ps1 in the repo root."
}
if (-not (Test-Path $ks)) {
    throw "Keystore not found: $ks. Run ..\build.ps1 once to create it, or pass -Keystore."
}

$final = Join-Path $out "tv-screensaver.apk"
& $apksigner sign --ks $ks --ks-pass "pass:$pass" --ks-key-alias $KeyAlias --out $final (Join-Path $out "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

$item = Get-Item $final
$hash = (Get-FileHash $final -Algorithm SHA256).Hash
Write-Output ""
Write-Output "Built: $($item.FullName)"
Write-Output "Size:  $($item.Length) bytes"
Write-Output "SHA256: $hash"
