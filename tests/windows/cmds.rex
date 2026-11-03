/* commands through cmd.exe: rc, captured output, a quoted argument (no ADDRESS CMD: the Unix interpreter has no such environment) */
'exit 3'
say rc
address system 'echo o' with output stem o.
say o.0 o.1
address system 'echo "a b"' with output stem q.
say q.1
address path 'nonexistent_command_xyz'
say rc
