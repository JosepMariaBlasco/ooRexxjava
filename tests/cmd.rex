/* Commands as processes of the JVM's host (ProcessBuilder), behind
   oorexxwasm's SysWasmHost.hpp: ADDRESS SYSTEM and PATH, RC, unknown
   commands, ADDRESS ... WITH redirection, CD and the environment. */
say '1. executable:' .RexxInfo~executable
say '2. libraryPath:' .RexxInfo~libraryPath
address system 'echo 3. from sh; echo 3. to stderr >&2'
say '3. rc' rc
'exit 3'
say '4. rc after exit 3:' rc
address path 'nonexistent_command_xyz'
say '5. rc unknown:' rc '(expect 127)'
in.0 = 2; in.1 = 'abc'; in.2 = 'def'
address system 'tr a-z A-Z' with input stem in. output stem out. error stem err.
say '6. out.0' out.0 '->' out.1 '/' out.2 '; err.0' err.0
address system 'echo o; echo e >&2' with output stem both. error stem both.
say '7. both.0' both.0':' both.1 both.2
'cd /tmp'
address system 'echo 8. $(pwd)'
call value 'RXJTEST', 'xyz', 'ENVIRONMENT'
address system 'echo 9. env=$RXJTEST'
