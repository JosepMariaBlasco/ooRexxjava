/* waits for ever, where no HALT is seen: on a semaphore nobody posts (tests/halt.sh) */
parse arg ready
call lineout ready, 'ready'; call lineout ready
s = .EventSemaphore~new
s~wait
say 'never'
