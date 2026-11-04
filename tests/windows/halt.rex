/* a HALT (Ctrl+Break through rexxjs.exe, tests/windows/ctrlbreak.c) ends the loop */
signal on halt
do 600
    call SysSleep 0.1
end
say 'not halted'
exit 1
halt:
say 'halted'
exit 5
