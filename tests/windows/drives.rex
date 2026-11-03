/* another drive, through TEMP (on C: in GitHub's runners, the checkout on D:) */
parse value value('TEMP', , 'ENVIRONMENT') with d ':' rest
temp = '/'lower(d) || translate(rest, '/', '\')
f = temp'/rexxj-drive-test.txt'
call lineout f, 'on' d; call lineout f
say linein(f) (stream(f, 'C', 'query exists') == f)
call stream f, 'C', 'close'
call SysFileDelete f
say SysFileTree('*.rex', 'st.', 'FO') (st.0 >= 5)
old = directory()
say directory(temp) \== ''   /* TEMP has a short name (RUNNER~1): chdir gives the real one */
call directory old
say directory() == old
