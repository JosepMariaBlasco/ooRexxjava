/* The Rexx API server shared between processes: external data queues and
   the macrospace seen by other processes, as natively.  Run it with
   dist/rexxj; with ORX_WASM_RXAPI=inprocess the queues are the process's own
   and the checks across processes fail. */
fails = 0
name = rxqueue('create')

-- a child process writes into the queue: the rxqueue utility
'echo from rxqueue | rxqueue' name
call check rc == 0, 'rxqueue ran'
-- and a child interpreter, with a program that queues into it
child = .File~temporaryPath'/rxapi-child-'SysQueryProcess('PID')'.rex'
call lineout child, 'call rxqueue "set", arg(1); queue "from a child"'
call lineout child
'rexx' child name
call check rc == 0, 'the child interpreter ran'
call SysFileDelete child

call rxqueue 'set', name
lines = .array~new
do while queued() > 0
  parse pull line
  lines~append(line)
end
call check lines~hasItem('from rxqueue'), 'what rxqueue queued arrived'
call check lines~hasItem('from a child'), 'what the child interpreter queued arrived'
call rxqueue 'delete', name

say (fails = 0)~?('all passed', fails 'failed')
exit fails

check: procedure expose fails
  use arg ok, what
  if \ok then do
    fails += 1
    say 'FAIL:' what
  end
  else say 'ok:  ' what
  return
