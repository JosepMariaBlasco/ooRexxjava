call time 'R'
do
  call lineout arg(1), 'ready'; call lineout arg(1)
  call SysSleep 30
  say 'not halted'
  exit 1
catch halt
  say 'HALT result='result 'quick=' || (time('E') < 10)
  exit 0
end
