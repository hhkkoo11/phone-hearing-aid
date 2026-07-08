param(
    [string]$ApkPath = "app\build\outputs\apk\debug\app-debug.apk"
)

$ErrorActionPreference = "Stop"

gradle assembleDebug

if (-not (Test-Path $ApkPath)) {
    throw "APK not found: $ApkPath"
}

$devices = adb devices |
    Select-Object -Skip 1 |
    Where-Object { $_ -match "\tdevice$" } |
    ForEach-Object { ($_ -split "\s+")[0] }

if (-not $devices -or $devices.Count -eq 0) {
    throw "No online ADB devices found."
}

foreach ($device in $devices) {
    Write-Host "Installing to $device ..."
    adb -s $device install -r $ApkPath
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "adb install failed on $device. Trying package installer path."
        adb -s $device push $ApkPath /data/local/tmp/hearing-aid-update.apk
        adb -s $device shell pm install -r /data/local/tmp/hearing-aid-update.apk
    }

    Write-Host "Installed package info for ${device}:"
    adb -s $device shell dumpsys package com.daicg.hearingaid |
        Select-String -Pattern "versionCode=|versionName=|lastUpdateTime="
}
