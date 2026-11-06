/* tests/probe/rexxutil.rex: what RexxUtil and rxunixsys return here, one line
   per call. Not a test: run it under native rexx and rexxj and diff (the
   windows-probe workflow runs it on Windows). Arg: a scratch directory. */
parse arg dir
if dir = '' then do
  dir = value('TMPDIR', , 'ENVIRONMENT')
  if dir = '' then dir = value('TEMP', , 'ENVIRONMENT')
  if dir = '' then dir = '/tmp'
  dir = dir'/probe-'SysQueryProcess('PID')
end
call SysMkDir dir
f = dir'/f.txt'; call lineout f, 'hello'; call lineout f
parse source src; call out 'parse source', word(src, 1)
call out '.RexxInfo~platform', .RexxInfo~platform

/* --- RexxUtil, Unix + common --- */
call t 'SysVersion()'
call t 'SysLinVer()'
call t 'SysUtilVersion()'
call t 'SysGetErrorText(2)'
call t 'SysQueryProcess("PID")'
call t 'SysQueryProcess("PPID")'
call t 'SysQueryProcess("PPRIO")'
call t 'SysQueryProcess("PTIME")'
call t 'SysQueryProcess("PMEM")'
call t 'SysQueryProcess("PRCVD")'
call t 'SysQueryProcess("TID")'
call t 'SysQueryProcess("TPRIO")'
call t 'SysQueryProcess("TTIME")'
call t 'SysSetPriority(0, 0)'
call t 'SysGetFileDateTime(f)'
call t 'SysSetFileDateTime(f, "2020-01-02", "03:04:05")'
call t 'SysGetFileDateTime(f)'
call t 'SysGetFileDateTime(f, "A")'
call t 'SysIsFile(f)'
call t 'SysIsFileDirectory(dir)'
call t 'SysIsFileLink(f)'
call t 'SysFileExists(f)'
call t 'SysSearchPath("PATH", "sh")'
call t 'SysTempFileName(dir"/t???")<>""'
call t 'SysFileCopy(f, dir"/g.txt")'
call t 'SysFileMove(dir"/g.txt", dir"/h.txt")'
call t 'SysFileDelete(dir"/h.txt")'
call t 'SysFileDelete(dir"/nonexistent")'
call t 'SysMkDir(dir"/sub")'
call t 'SysRmDir(dir"/sub")'
call t 'SysRmDir(dir"/nonexistent")'
call t 'SysFileTree(dir"/*", "S.", "FO")'
call t 'S.0'
call t 'SysFileSearch("hello", f, "R.")'
call t 'SysFormatMessage("a &1 b", "x")'
call t 'SysGetMessage(1)'
call t 'SysCreatePipe("N")<>""'
call t 'SysCreateEventSem()<>""'
call t 'SysCreateMutexSem()<>""', 'skip'
call t 'SysSleep(0.01)'
call t 'SysFork()<>""', 'skip'
call t 'SysCreateMutexSem("")<>""'
call t 'SysCls()', 'skip'
call t 'SysGetKey()', 'skip'

/* --- RexxUtil, Windows only --- */
call t 'SysWinVer()'
call t 'SysBootDrive()'
call t 'SysSystemDirectory()'
call t 'SysDriveMap()'
call t 'SysDriveInfo("C:")'
call t 'SysFileSystemType("C:")'
call t 'SysVolumeLabel("C:")'
call t 'SysTextScreenSize()'
call t 'SysGetLongPathName(f)<>""'
call t 'SysGetShortPathName(f)<>""'
call t 'SysIsFileCompressed(f)'
call t 'SysIsFileTemporary(f)'
call t 'SysWinGetDefaultPrinter()<>""'
call t 'SysToUnicode("abc", "UTF8", , "U.")'
call t 'SysCurPos()', 'skip'

/* --- rxunixsys (loaded at run time: absent natively on Windows) --- */
.local~probe.unix = .context~package~loadLibrary('rxunixsys')
call out 'loadLibrary("rxunixsys")', .local~probe.unix
call t 'SysUname("S")'
call t 'SysUname("N")'
call t 'SysUname("R")'
call t 'SysUname("V")'
call t 'SysUname("M")'
call t 'SysGethostname()'
call t 'SysGetpid()<>""'
call t 'SysGetppid()<>""'
call t 'SysGettid()<>""'
call t 'SysGetuid()'
call t 'SysGeteuid()'
call t 'SysGetgid()'
call t 'SysGetegid()'
call t 'SysGetpgrp()'
call t 'SysGetpgid(0)'
call t 'SysGetsid(0)'
call t 'SysGetpwuid(SysGetuid(), "N")'
call t 'SysGetpwuid(SysGetuid(), "D")'
call t 'SysGetpwuid(SysGetuid(), "S")'
call t 'SysGetpwuid(SysGetuid(), "G")'
call t 'SysGetpwnam("root", "U")'
call t 'SysGetpwnam("root", "D")'
call t 'SysGetgrgid(0, "N")'
call t 'SysGetgrnam("root", "G")'
call t 'SysGetservbyname("ssh", "tcp", "P")'
call t 'SysGetservbyport(22, "tcp", "N")'
call t 'SysGettzname1()'
call t 'SysGettzname2()'
call t 'SysUmask(022)'
call t 'SysUmask(022)'
call t 'SysAccess(f, 4)'
call t 'SysEuidaccess(f, 4)'
call t 'SysAccess("/etc/shadow", 4)'
call t 'SysChmod(f, "rw-r-----")'
do o over 'P', 'D', 'I', 'N', 'U', 'G', 'S', 'R'
  call t 'SysStat(f, "'o'")'
end
do o over 'P', 'R'
  call t 'SysStat("/dev/null", "'o'")'
end
call t 'SysChown(f, 0, 0)'
call t 'SysLchown(f, 0, 0)'
call t 'SysChroot("/")'
call t 'SysSetuid(0)'
call t 'SysSeteuid(0)'
call t 'SysSetgid(0)'
call t 'SysSetegid(0)'
call t 'SysSetpgid(0, 0)'
call t 'SysSetpgrp()'
call t 'SysSetsid()'
call t 'SysKill(SysGetpid(), 0)'
call t 'SysSignal(15, "D")'
call t 'SysSymlink(f, dir"/l")'
call t 'SysIsFileLink(dir"/l")'
call t 'SysLink(f, dir"/hl")'
call t 'SysUnlink(dir"/hl")'
call t 'SysUnlink(dir"/l")'
call t 'SysMkdirUnix(dir"/u", 448)'
call t 'SysRmdirUnix(dir"/u")'
call t 'SysGetdirlist(dir)~items'
call t 'SysCrypt("pw", "ab")'
call t 'SysWordexp("~")[1]'
call t 'SysGetsizeofptr()'
call t 'SysGeterrno()'
call t 'SysGeterrnomsg(2)'
call t 'SysSetxattr(f, "user.k", "v")'
call t 'SysGetxattr(f, "user.k")'
call t 'SysListxattr(f)~items'
call t 'SysRemovexattr(f, "user.k")'
call t 'SysClose(99)'
call SysFileDelete f
call SysFileTree dir'/*', 'Z.', 'FO'
do i = 1 to Z.0; call SysFileDelete Z.i; end
call SysRmDir dir
exit

out: procedure
  say left(arg(1), 46) '=>' arg(2)
  return

t: procedure expose f dir S. R. ST. SN. DL. W. XA.
  parse arg expr, skip
  if skip <> '' then do; call out expr, '(not run)'; return; end
  signal on syntax name err
  interpret 'r = ' expr
  if .local~probe.unix == .true then r = r '| errno' SysGeterrno()
  call out expr, r
  return
err:
  call out expr, 'SYNTAX' condition('o')~code':' condition('o')~message
  return

