/* another drive, through TEMP (on C: in GitHub's runners, the checkout on D:) */
parse value value('TEMP', , 'ENVIRONMENT') with d ':' rest
temp = '/'lower(d) || translate(rest, '/', '\')
f = temp'/rexxj-drive-test.txt'
do
  call lineout f, 'on' d; call lineout f
  say linein(f) (stream(f, 'C', 'query exists') == f)
finally
  call stream f, 'C', 'close'
  call SysFileDelete f
end
say SysFileTree('*.rex', 'st.', 'FO') (st.0 >= 5)
old = directory()
do
  say directory(temp) \== ''   /* TEMP has a short name (RUNNER~1): chdir gives the real one */
finally
  call directory old
end
say directory() == old
