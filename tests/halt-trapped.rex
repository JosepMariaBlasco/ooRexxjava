signal on halt name halted
call time 'R'
call lineout arg(1), 'ready'; call lineout arg(1)
call SysSleep 30
say 'not halted'
exit 1
halted:
  say 'HALT result='result 'quick=' || (time('E') < 10)
  exit 0
