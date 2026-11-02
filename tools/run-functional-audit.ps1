param(
    [Parameter(Mandatory=$true)][string]$GradlePath,
    [Parameter(Mandatory=$true)][string]$JdkHome,
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [string]$Device = 'emulator-5554',
    [string]$OutputName = 'latest'
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $project
$env:JAVA_HOME = $JdkHome
$shortTemp = Join-Path (Split-Path $project -Parent) '.tmp'
New-Item -ItemType Directory -Path $shortTemp -Force | Out-Null
$env:TEMP = $shortTemp
$env:TMP = $shortTemp
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$shortTemp -Djdk.net.unixdomain.tmpdir=$shortTemp"
$out = Join-Path $project "qa/$OutputName"
New-Item -ItemType Directory -Path $out -Force | Out-Null
& $GradlePath :app:assembleDebug :app:assembleDebugAndroidTest *> (Join-Path $out 'build.log')
if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $out 'build.log') -Tail 50; throw 'Build failed; no APK installed' }
$adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
foreach ($apk in @('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
    & $adb -s $Device install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "Install failed: $apk" }
}
& $adb -s $Device shell am clear-debug-app
& $adb -s $Device shell am instrument -w com.spektralab.android.test/com.spektralab.android.FunctionalAuditInstrumentation *> (Join-Path $out 'instrumentation.log')
& $adb -s $Device pull /sdcard/Android/data/com.spektralab.android/files/functional-audit $out
& $adb -s $Device logcat -d -b crash *> (Join-Path $out 'crash.log')
$report = Join-Path $out 'functional-audit/results.json'
if (Test-Path -LiteralPath $report) {
    $rows = Get-Content -LiteralPath $report -Raw | ConvertFrom-Json
    $rows | Group-Object status | Select-Object Name,Count | Format-Table
    $rows | Where-Object status -eq 'FAIL' | Select-Object case,detail | Format-List
}
Get-Content (Join-Path $out 'instrumentation.log') -Tail 6
