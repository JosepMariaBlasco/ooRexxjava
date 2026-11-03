@echo off
rem rexxj.cmd on Windows (launchers/README.md): java directly, not through call (call would
rem expand % and ^ in the arguments a second time).
rem %REXXJ_JAVA_OPTIONS%: more options for the JVM; %CLASSPATH%: Java code that Rexx uses.
setlocal
set "D=%~dp0"
set "D=%D:~0,-1%"
set "CP=%D%\rexxj.jar;%D%\bsf4oorexx.jar"
if defined CLASSPATH set "CP=%CP%;%CLASSPATH%"
rem Java 23 and later: allow sun.misc.Unsafe (FlatMemory) without JDK 24+'s warning on
rem every run (JEP 498); older ones refuse the option. The version comes from the
rem release file of the java in PATH (none: no option, and the warning shows).
set "JX=" & set "JV=" & set "JM=" & set "UNSAFE="
for %%J in (java.exe) do set "JX=%%~$PATH:J"
if defined JX for %%H in ("%JX%\..\..") do if exist "%%~fH\release" for /f "tokens=2 delims==" %%V in ('findstr /b "JAVA_VERSION=" "%%~fH\release"') do set "JV=%%~V"
if defined JV for /f "delims=." %%M in ("%JV%") do set "JM=%%M"
if defined JM if %JM% GEQ 23 set "UNSAFE=--sun-misc-unsafe-memory-access=allow"
set "PATH=%D%;%PATH%"
java -Xss16m %UNSAFE% %REXXJ_JAVA_OPTIONS% "-Drexxjvm.executable=%D%\rexx.cmd" "-Drexxjvm.home=%D%" "-javaagent:%D%\rexxj.jar" -cp "%CP%" rexxjvm.RunWasm %*
exit /b %ERRORLEVEL%
