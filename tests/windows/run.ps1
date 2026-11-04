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
    # a failure shows its first lines only (a Java stack trace is long)
    $actual = (("$actual" -split '\|') | Select-Object -First 4) -join "|"
    if ("$expected" -ceq "$actual") { "ok   $name" }
    else { "FAIL ${name}: expected [$expected], got [$actual]"; $script:fails++ }
}
function Info($name, $file) {
    "---- info: $name"
    & $rexx $file 2>&1 | Select-Object -First 25 | ForEach-Object { "     $_" }
    "     (exit $LASTEXITCODE)"
}
# the checks read standard output only (JDK 24+ warn on standard error about Unsafe, JEP 498)
$out = & $rexx -e "say 'hi'" 2>$null
Check "say" "hi" ($out -join "|")
# nothing on standard error: no "Picked up", no Unsafe warning (rexxj.cmd allows it on Java 23+)
$err = & $rexx -e "say 'hi'" 2>&1 1>$null
Check "no warnings" "" ($err -join "|")
$out = & $rexx args.rex "x  y" 2>$null
Check "argument" "[x  y]" ($out -join "|")
Check "exit code" 7 $LASTEXITCODE
$out = & $rexx files.rex 2>$null
Check "relative file" "2 line one / line two|0 1" ($out -join "|")
$out = & $rexx drives.rex 2>$null
Check "another drive, SysFileTree, directory()" "on C 1|0 1|1|1" ($out -join "|")
$out = & $rexx cmds.rex 2>$null
Check "commands" "3|1 o|""a b""|127" ($out -join "|")
$out = & $rexx paths.rex 2>$null
Check "HOME, TMPDIR, drives' paths in commands" "1 1 1|1 1|0 0 3" ($out -join "|")
$out = "line1" | & $rexx io.rex 2>&1
Check "standard input, a command's input and error" "[line1]|1 banana|1 oops" ($out -join "|")
$out = & $rexx (Join-Path $PSScriptRoot "args.rex") "abs" 2>$null
Check "program by absolute Windows path" "[abs]" ($out -join "|")
# through rexxjs.exe only: Ctrl+Break, sent on as SIGINT, is a HALT (ctrlbreak.c, built by
# windows.yml as test-ctrlbreak.exe; Java takes Ctrl+Break for a thread dump)
$cb = Join-Path (Split-Path $rexx) "test-ctrlbreak.exe"
$rjs = Join-Path (Split-Path $rexx) "rexxjs.exe"
if ($env:REXXJ_SERVER -and (Test-Path $cb) -and (Test-Path $rjs)) {
    $out = & $cb 4000 $rjs halt.rex 2>$null
    Check "Ctrl+Break, a HALT" "halted" ($out -join "|")
    Check "Ctrl+Break, exit code" 5 $LASTEXITCODE
}
Info "what it sees" info.rex
Info "commands" cmd.rex
Info "BSF4ooRexx" ..\bsf\basics.rex
if ($fails) {
    # what the module asked of WASI (Endive's log), for the first failures
    "---- trace: WASI calls of rexx -e ""say 'hi'"" (Endive's log; paths and fds)"
    $env:REXXJ_JAVA_OPTIONS = "-Drexxjvm.wasi.log=true"
    & $rexx -e "say 'hi'" 2>&1 | Select-String -Pattern "prestat|path_|fd_filestat|fd_open|errno|Error|error" |
        Select-Object -First 80 | ForEach-Object { "     $_" }
    $env:REXXJ_JAVA_OPTIONS = $null
    "$fails check(s) failed"; exit 1
}
"all checks passed"
