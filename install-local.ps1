param(
    [string]$Tv = "192.168.1.128:5555",
    [string]$Apk = "out\darsha-home.apk",
    [switch]$Launch,
    [switch]$SetHome
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$apkPath = if ([System.IO.Path]::IsPathRooted($Apk)) { $Apk } else { Join-Path $root $Apk }
if (-not (Test-Path $apkPath)) { throw "APK not found: $apkPath. Run build.ps1 first." }

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found: $adb" }

& $adb connect $Tv | Out-Null
$state = (& $adb -s $Tv get-state 2>&1 | Out-String).Trim()
if ($state -ne "device") {
    throw "TV not ready ($state). Wake it, check $Tv, and allow USB/network debugging."
}

$hash = (Get-FileHash $apkPath -Algorithm SHA256).Hash
Write-Output "APK:    $apkPath"
Write-Output "SHA256: $hash"

$install = & $adb -s $Tv install -r $apkPath 2>&1 | Out-String
if ($install -match "UPDATE_INCOMPATIBLE") {
    & $adb -s $Tv uninstall com.darsha.tvhome | Out-Null
    $install = & $adb -s $Tv install $apkPath 2>&1 | Out-String
}
if ($install -notmatch "Success") { throw "Install failed: $install" }
Write-Output "Installed com.darsha.tvhome"

if ($Launch) {
    & $adb -s $Tv shell am start -n com.darsha.tvhome/.MainActivity | Out-Null
    Write-Output "Launched preview. Press Home to return to the TV's own launcher."
}

if ($SetHome) {
    $before = (& $adb -s $Tv shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" 2>&1 | Out-String).Trim()
    $before = ($before -split "`r?`n" | Where-Object { $_ -match "/" } | Select-Object -Last 1)
    if ($before) {
        $backup = Join-Path $root "home-before.txt"
        Set-Content -Path $backup -Value $before -Encoding ASCII
        Write-Output "Previous home saved: $backup"
    }
    & $adb -s $Tv shell cmd role add-role-holder --user 0 android.app.role.HOME com.darsha.tvhome 2>$null | Out-Null
    & $adb -s $Tv shell cmd package set-home-activity com.darsha.tvhome/.MainActivity | Out-Null
    Write-Output "Darsha Home set as the default home."
}
