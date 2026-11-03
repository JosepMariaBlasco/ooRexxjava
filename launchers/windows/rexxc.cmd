@echo off
rem rexxc.cmd on Windows (launchers/README.md): java directly, not through call (call would
rem expand % and ^ in the arguments a second time).
rem %REXXJ_JAVA_OPTIONS%: more options for the JVM; %CLASSPATH%: Java code that Rexx uses.
setlocal
set "D=%~dp0"
set "D=%D:~0,-1%"
set "CP=%D%\rexxj.jar;%D%\bsf4oorexx.jar"
if defined CLASSPATH set "CP=%CP%;%CLASSPATH%"
set "PATH=%D%;%PATH%"
java -Xss16m %REXXJ_JAVA_OPTIONS% "-Drexxjvm.executable=%D%\rexx.cmd" "-Drexxjvm.home=%D%" "-javaagent:%D%\rexxj.jar" -cp "%CP%" rexxjvm.RunWasm --orx-wasm-rexxc %*
exit /b %ERRORLEVEL%
