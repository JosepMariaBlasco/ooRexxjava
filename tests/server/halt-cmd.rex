/* a HALT while a command runs: the program traps it once the command ends (as natively: the signal is the client's alone) */
signal on halt
say "start"; "sleep 5"; say "after sleep"
do 600; call SysSleep 0.1; end; say "no HALT"
halt: say "HALT"; exit 9
