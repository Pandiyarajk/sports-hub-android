<#
.SYNOPSIS
  Drive an Android app over adb by element label, refusing to act when another app is in front.

.DESCRIPTION
  Author: Pandiyaraj Karuppasamy
  Date: Oct-18-2026
  Modified: Oct-18-2026 (refuse when nothing is in front: a $null foreground passed the check)
  Modified: Oct-19-2026 (the foreground gate matches the package exactly, an unreachable device is
    named as such instead of blamed on the screen lock, and dump masks long text and password
    fields as the skill says it does)

  Every call first checks that adb can reach the device, then reads topResumedActivity and exits
  1 unless the package in front is -Allow (a regex, matched against the WHOLE package name) or one
  of the system screens an app commonly opens (settings, document picker, permission dialogs).
  A test phone is often shared with other sessions; a blind coordinate tap then lands in
  someone else's app. This was seen twice on Oct-17/18-2026: once a tap switched a tab in
  another app, once the gate caught the other app in front and refused.

  The match is anchored on purpose. The first version matched -Allow anywhere in the activity
  line, so -Allow 'com\.example\.app' let com.example.app.other and org.thirdparty.com.example.app
  through (found Oct-19-2026 by feeding the gate made-up package names).

  Actions:
    dump  list visible labels (text | content-desc | bounds) from uiautomator. Text longer than
          24 characters, and any password field, is masked; pass -Full to print it all.
    tap   tap the centre of the -Index'th element whose text or content-desc matches -Match
    shot  screenshot to -Out (on the device, then pulled, so the PNG is not corrupted)

  Exit codes: 0 done, 1 refused (device unreachable, nothing in front, or the wrong app), 2 no match.

.EXAMPLE
  .\adb-ui.ps1 -Serial $s -Allow 'com\.example\.app' dump
  .\adb-ui.ps1 -Serial $s -Allow 'com\.example\.app' tap -Match '^Save$'
  .\adb-ui.ps1 -Serial $s -Allow 'com\.example\.app\.(debug|beta)' tap -Match '^Save$'
#>
param(
    [Parameter(Mandatory, Position = 0)][ValidateSet('dump', 'tap', 'shot')][string]$Action,
    [Parameter(Mandatory)][string]$Serial,
    # Regex for the app under test, matched against the whole package name (anchored).
    [Parameter(Mandatory)][string]$Allow,
    [string]$Match = '',
    [int]$Index = 0,
    [string]$Out = '',
    # dump and tap print label text in full instead of masking long text.
    [switch]$Full
)
$ErrorActionPreference = 'Stop'

# System screens an app commonly opens, as exact package names. Exact, not a substring: the old
# check matched 'documentsui' and 'permissioncontroller' anywhere in the line.
$SystemPackages = @(
    'com.android.settings',
    'com.android.documentsui', 'com.google.android.documentsui',
    'com.android.permissioncontroller', 'com.google.android.permissioncontroller'
)
$MaskAfter = 24

function Get-ForegroundPackage {
    # The package named in a topResumedActivity line, or $null when the line has no package.
    param([string]$Top)
    if ($Top -match ' u\d+ ([^ /]+)/') { return $Matches[1] }
    return $null
}

function Test-AllowedPackage {
    # True for a system screen, or when the WHOLE package name matches -Allow.
    param([string]$Package, [string]$Allowed)
    if (-not $Package) { return $false }
    if ($SystemPackages -contains $Package) { return $true }
    return $Package -match "^(?:$Allowed)$"
}

function Format-Label {
    # Mask long text so a secret on screen is not echoed into a terminal or a transcript.
    param([string]$Text, [bool]$IsPassword = $false)
    if ($IsPassword) { return '[password field hidden]' }
    if ($Full -or $Text.Length -le $MaskAfter) { return $Text }
    return '{0}... [{1} characters hidden, use -Full]' -f $Text.Substring(0, 12), $Text.Length
}

# Dot-sourcing for tests defines the functions above and stops here.
if ($MyInvocation.InvocationName -eq '.') { return }

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

# Reachability comes first. Without it, an unreachable device prints nothing for the foreground
# query below, and the empty-output guard would report "phone asleep or locked?" for a phone
# that is simply on another port (seen Oct-19-2026, with `device offline` printed just above it).
# adb prints "* daemon not running; starting now" lines on a cold start; only its last line is the verdict.
$raw = @(& $adb -s $Serial get-state 2>&1 | ForEach-Object { "$_".Trim() } | Where-Object { $_ -and $_ -notmatch '^\*' })
$state = if ($raw.Count) { $raw[-1] } else { '' }
if ($state -ne 'device') {
    "REFUSED: adb cannot use '$Serial' (adb says: $state). The device is off the network, its wireless-debugging port has moved, or the entry is offline: run 'adb devices' and 'adb mdns services'. This is a connection problem, not a screen check."
    exit 1
}

# Coerce to a string: with nothing resumed (phone asleep or locked) adb prints nothing, $top is
# $null, and `$null -notmatch $re` yields an empty result that is FALSE, so an unguarded check
# lets the action through exactly when no app is in front. Seen Oct-18-2026 on a locked phone.
$top = [string](& $adb -s $Serial shell "dumpsys activity activities | grep topResumedActivity | head -1")
if ([string]::IsNullOrWhiteSpace($top)) {
    'REFUSED: the device is reachable but no activity is resumed (screen off or locked?). Wake and unlock it first.'
    exit 1
}
$pkg = Get-ForegroundPackage $top
if (-not $pkg) {
    "REFUSED: could not read a package name from: $($top.Trim())"
    exit 1
}
"top: $pkg"
if (-not (Test-AllowedPackage $pkg $Allow)) {
    "REFUSED: '$pkg' is in front, not the app under test or a screen it opened."
    exit 1
}

if ($Action -eq 'shot') {
    if (-not $Out) { throw '-Out is required for shot' }
    & $adb -s $Serial shell screencap -p /sdcard/adb_ui_shot.png
    & $adb -s $Serial pull /sdcard/adb_ui_shot.png $Out | Out-Null
    & $adb -s $Serial shell rm /sdcard/adb_ui_shot.png
    exit 0
}

$local = Join-Path $env:TEMP "adb_ui_$PID.xml"
& $adb -s $Serial shell uiautomator dump /sdcard/adb_ui.xml | Out-Null
& $adb -s $Serial pull /sdcard/adb_ui.xml $local | Out-Null
& $adb -s $Serial shell rm /sdcard/adb_ui.xml
try {
    $xml = [xml](Get-Content $local -Raw)
} finally {
    Remove-Item $local -ErrorAction SilentlyContinue
}
$nodes = $xml.SelectNodes('//node') | Where-Object { $_.text -or $_.'content-desc' }

if ($Action -eq 'dump') {
    $nodes | ForEach-Object {
        $secret = ($_.password -eq 'true')
        '{0} | {1} | {2}' -f (Format-Label $_.text $secret), (Format-Label $_.'content-desc' $secret), $_.bounds
    }
    exit 0
}

# Matching uses the full text; only what is printed is masked.
$hits = @($nodes | Where-Object { $_.text -match $Match -or $_.'content-desc' -match $Match })
if ($hits.Count -le $Index) { "NO MATCH for '$Match' (a dialog may have closed, or the screen changed)"; exit 2 }
$hit = $hits[$Index]
$b = $hit.bounds -replace '[\[\]]', ',' -split ',' | Where-Object { $_ -ne '' } | ForEach-Object { [int]$_ }
$cx = [int](($b[0] + $b[2]) / 2); $cy = [int](($b[1] + $b[3]) / 2)
$secret = ($hit.password -eq 'true')
"tap '$(Format-Label $hit.text $secret)$(Format-Label $hit.'content-desc' $secret)' at $cx,$cy"
& $adb -s $Serial shell input tap $cx $cy
