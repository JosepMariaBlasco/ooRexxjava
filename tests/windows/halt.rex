/* a HALT (Ctrl+Break through rexxjs.exe, tests/windows/ctrlbreak.c) ends the loop */
do
    do 600
        call SysSleep 0.1
    end
    say 'not halted'
    exit 1
catch halt
    say 'halted'
    exit 5
end
