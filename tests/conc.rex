-- activities: START, GUARD, REPLY, a queue of messages, SysSleep
c = .Counter~new
msgs = .array~new
do i = 1 to 4
  msgs~append(c~start("addMany", 1000))
end
do m over msgs; m~wait; end
say "counter =" c~value "(expect 4000)"

g = .Gate~new
m = g~start("waitOpen")
call SysSleep 0.2
say "gate opened by main"
g~open
say "waiter said:" m~result

r = .Replier~new~go
say "reply returned first:" r
call SysSleep 0.3
exit

::class Counter
::attribute value
::method init
  expose value; value = 0
::method addMany
  expose value
  use arg n
  do n; value += 1; end

::class Gate
::method init
  expose isOpen; isOpen = .false
::method waitOpen
  expose isOpen
  guard on when isOpen
  return "the gate is open"
::method open
  expose isOpen; isOpen = .true

::class Replier
::method go
  reply "early"
  call SysSleep 0.1
  say "  (still running after REPLY on another activity)"


