param(
    [string]$AppList = "apps.txt",
    [string]$Package = "com.darsha.tvhome",
    [string]$OutDir = "out"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
if (-not (Test-Path $sdk)) { throw "Android SDK not found: $sdk" }

$javaHome = $env:JAVA_HOME
if (-not $javaHome) { $javaHome = "C:\Users\sharath\scoop\apps\temurin17-jdk\current" }
$javac = Join-Path $javaHome "bin\javac.exe"
$keytool = Join-Path $javaHome "bin\keytool.exe"
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
$lambda = Join-Path $bt.FullName "core-lambda-stubs.jar"

$jar = Join-Path $sdk "platforms\android-34\android.jar"
if (-not (Test-Path $jar)) {
    $p = Get-ChildItem (Join-Path $sdk "platforms") -Directory |
        Sort-Object { [version]($_.Name -replace '^android-', '') } -Descending | Select-Object -First 1
    if (-not $p) { throw "No Android platform found under $sdk" }
    $jar = Join-Path $p.FullName "android.jar"
}

$out = Join-Path $root $OutDir
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
$classes = Join-Path $out "classes"
$dex = Join-Path $out "dex"
$pkgPath = $Package -replace '\.', '\'
$gen = Join-Path (Join-Path $out "gen") $pkgPath
New-Item -ItemType Directory -Force -Path $classes, $dex, $gen | Out-Null

$apps = Get-Content (Join-Path $root $AppList) |
    Where-Object { $_ -notmatch '^\s*#' -and $_.Trim() -ne "" } |
    ForEach-Object { $_.Trim() }
$java = @("package $Package;", "final class Apps { static final String[] LIST = {")
foreach ($app in $apps) { $java += "    `"$app`"," }
$java += "}; }"
Set-Content -Path (Join-Path $gen "Apps.java") -Value $java -Encoding ASCII

& $aapt2 link -o (Join-Path $out "base.apk") -I $jar --manifest (Join-Path $root "AndroidManifest.xml") --min-sdk-version 21 --target-sdk-version 34
if ($LASTEXITCODE -ne 0) { throw "aapt2 failed" }

$srcFiles = @(Get-ChildItem (Join-Path $root "src"), $gen -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })
& $javac -nowarn -Xlint:-options -source 1.8 -target 1.8 -bootclasspath "$jar;$lambda" -d $classes $srcFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$classFiles = @(Get-ChildItem $classes -Recurse -Filter "*.class" | ForEach-Object { $_.FullName })
& $d8 --min-api 21 --release --lib $jar --output $dex $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

$unsigned = Join-Path $out "unsigned.apk"
Copy-Item (Join-Path $out "base.apk") $unsigned
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, (Join-Path $dex "classes.dex"), "classes.dex",
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
$zip.Dispose()

& $zipalign -f 4 $unsigned (Join-Path $out "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$ks = Join-Path $root "key.jks"
$pass = $env:TVHOME_KEY_PASS
$envFile = Join-Path $root "keystore.env.ps1"
if (-not $pass -and (Test-Path $envFile)) {
    . $envFile
    $pass = $env:TVHOME_KEY_PASS
}
if (-not $pass) {
    throw "Keystore password not set. Set TVHOME_KEY_PASS or create keystore.env.ps1."
}
if (-not (Test-Path $ks)) {
    & $keytool -genkeypair -keystore $ks -storepass $pass -keypass $pass -alias tvhome `
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

$final = Join-Path $out "darsha-home.apk"
& $apksigner sign --ks $ks --ks-pass "pass:$pass" --out $final (Join-Path $out "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

$item = Get-Item $final
$hash = (Get-FileHash $final -Algorithm SHA256).Hash
Write-Output ""
Write-Output "Built: $($item.FullName)"
Write-Output "Size:  $($item.Length) bytes"
Write-Output "SHA256: $hash"
