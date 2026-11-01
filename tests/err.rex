say "1. error in the main activity, trapped:"
signal on syntax name s1
x = 1 / 0
s1: say "   trapped" condition("O")~code
say "2. error in a started activity:"
m = .T~new~start("boom")
m~wait
say "   hasError:" m~hasError
if m~hasError then say "   code:" m~errorCondition~code
say "3. still alive"
exit
::class T
::method boom
  return 1 / 0
