/* tests/timezone/tz.rex: local time both ways, for run.sh to compare with native rexx.
   Arg: a scratch file. Its times are set by the host (touch -d @EPOCH) and read
   back, and set by RexxUtil and read back by the host (stat). */
parse arg f
call lineout f, 'x'; call lineout f
say 'TIME("O")' time('O')
/* epoch -> local: localtime() */
epochs = '0 -315619200 2216160000',      -- 1970, 1960, 2040
         '1774745999 1774746000 1774749600', -- around Europe's spring change, 29/3/2026 01:00 UTC
         '1792889999 1792890000 1792893600', -- around Europe's autumn change, 25/10/2026 01:00 UTC
         '1772953199 1772953200 1793512799 1793512800 1793516400' -- America's, 8/3 and 1/11/2026
do e over epochs~makeArray(' ')
  'touch -d @'e f
  say 'epoch' right(e, 11) '->' SysGetFileDateTime(f) '|' stream(f, 'c', 'query datetime')
end
/* local -> epoch: mktime(), with the hours that do not exist or exist twice */
locals = '1970-01-01 00:00:00|1960-01-01 12:00:00|2026-03-29 01:30:00|2026-03-29 02:30:00|' ||,
         '2026-03-29 03:30:00|2026-10-25 01:30:00|2026-10-25 02:30:00|2026-10-25 03:30:00|' ||,
         '2026-03-08 02:30:00|2026-11-01 01:30:00|2040-07-01 12:00:00'
do l over locals~makeArray('|')
  parse var l d t
  call SysSetFileDateTime f, d, t
  'stat -c %Y' f '| rxqueue'
  parse pull s
  say 'local' l '->' s '->' SysGetFileDateTime(f)
end
