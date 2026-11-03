/* a file in the current directory, by a relative name: write, read, delete */
f = 'rexxj-win-test.txt'
call lineout f, 'line one'; call lineout f, 'line two'; call lineout f
say lines(f, 'C') linein(f, 1) '/' linein(f)
call stream f, 'C', 'close'
say SysFileDelete(f) (stream(f, 'C', 'query exists') == '')
