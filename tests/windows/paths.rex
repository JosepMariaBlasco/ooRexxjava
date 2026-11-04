/* the module's paths reaching cmd.exe, HOME and TMPDIR (README.md, "Windows") */
home = value('HOME', , 'ENVIRONMENT')
say (home \== '') (qualify('~') == home) (left(home, 1) == '/')
tmp = value('TMPDIR', , 'ENVIRONMENT')
f = SysTempFileName(tmp'/rexxj-????.tmp')
call lineout f, 'temp'; call lineout f
say (left(tmp, 1) == '/') (linein(f, 1) == 'temp')
call stream f, 'C', 'close'
/* a drive's path in a command line, quoted and redirected; the interpreter itself as a command */
'type "'f'" > NUL'; a = rc
'type' f '> NUL'; b = rc
'"'.RexxInfo~executable'" -e "exit 3"'; c = rc
say a b c
call SysFileDelete f
