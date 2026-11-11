@echo off
rem rexxc.cmd on Windows (launchers/README.md): java directly, not through call (call would
rem expand % and ^ in the arguments a second time).
rem %REXXJ_JAVA_OPTIONS%: more options for the JVM; %CLASSPATH%: Java code that Rexx uses.
setlocal
set "D=%~dp0"
set "D=%D:~0,-1%"
rem Options by the version of the java in PATH, from its release file (none: no options):
rem Java 23 and later, allow sun.misc.Unsafe (FlatMemory) without JDK 24+'s warning on
rem every run (JEP 498), older ones refuse the option; Java 27 and later, C2 inlines on
rem past its node cutoff (DelayAfterInliningCutoff, diagnostic: README.md, "Speed"), older
rem ones do not know it. %REXXJ_JVM_FLAGS% hands them to rexxjs.exe for the server's JVM.
set "JX=" & set "JV=" & set "JM=" & set "UNSAFE=" & set "DELAY="
for %%J in (java.exe) do set "JX=%%~$PATH:J"
if defined JX for %%H in ("%JX%\..\..") do if exist "%%~fH\release" for /f "tokens=2 delims==" %%V in ('findstr /b "JAVA_VERSION=" "%%~fH\release"') do set "JV=%%~V"
if defined JV for /f "delims=." %%M in ("%JV%") do set "JM=%%M"
if defined JM if %JM% GEQ 23 set "UNSAFE=--sun-misc-unsafe-memory-access=allow"
if defined JM if %JM% GEQ 27 set "DELAY=-XX:+UnlockDiagnosticVMOptions -XX:+DelayAfterInliningCutoff"
set "REXXJ_JVM_FLAGS=%UNSAFE% %DELAY%"
rem %REXXJ_SERVER%: the program in a warm JVM, through rexxjs.exe, the native client of
rem rexxjvm.Server (client/rexxjs_win.c; README.md, "A warm JVM: rexxjs"), if it is here
if not defined REXXJ_SERVER goto java
if not exist "%D%\rexxjs.exe" goto java
"%D%\rexxjs.exe" --orx-wasm-rexxc %*
exit /b %ERRORLEVEL%
:java
set "CP=%D%\rexxj.jar;%D%\bsf4oorexx.jar"
if defined CLASSPATH set "CP=%CP%;%CLASSPATH%"
set "PATH=%D%;%PATH%"
java -Xss16m %UNSAFE% %DELAY% %REXXJ_JAVA_OPTIONS% "-Drexxjvm.executable=%D%\rexx.cmd" "-Drexxjvm.home=%D%" "-javaagent:%D%\rexxj.jar" -cp "%CP%" rexxjvm.RunWasm --orx-wasm-rexxc %*
exit /b %ERRORLEVEL%
