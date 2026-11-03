# Checks of rexxj on Windows (README.md, "Windows"), run by .github/workflows/windows.yml:
#   pwsh tests/windows/run.ps1 DIST
# Checks that must pass are "ok"/"FAIL"; "info" ones print what they got (the port
# is in progress, step 3 of the warm JVM's plan: docs/java-from-rexx-design.md).
param([string]$Dist = "dist")
$ErrorActionPreference = "Continue"
$rexx = Join-Path (Resolve-Path $Dist) "rexx.cmd"
Set-Location $PSScriptRoot
$fails = 0
function Check($name, $expected, $actual) {
    if ("$expected" -ceq "$actual") { "ok   $name" }
    else { "FAIL ${name}: expected [$expected], got [$actual]"; $script:fails++ }
}
function Info($name, $file) {
    "---- info: $name"
    & $rexx $file 2>&1 | ForEach-Object { "     $_" }
    "     (exit $LASTEXITCODE)"
}
$out = & $rexx -e "say 'hi'" 2>&1
Check "say" "hi" ($out -join "|")
$out = & $rexx args.rex "x  y" 2>&1
Check "argument" "[x  y]" ($out -join "|")
Check "exit code" 7 $LASTEXITCODE
$out = & $rexx files.rex 2>&1
Check "relative file" "2 line one / line two|0 1" ($out -join "|")
Info "what it sees" info.rex
Info "commands" cmd.rex
Info "BSF4ooRexx" ..\bsf\basics.rex
if ($fails) { "$fails check(s) failed"; exit 1 }
"all checks passed"
