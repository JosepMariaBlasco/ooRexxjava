/* commands through cmd.exe: rc, captured output, ADDRESS CMD, a quoted argument */
'exit 3'
say rc
address system 'echo o' with output stem o.
say o.0 o.1
address cmd 'echo "a b"' with output stem q.
say q.1
address path 'nonexistent_command_xyz'
say rc
