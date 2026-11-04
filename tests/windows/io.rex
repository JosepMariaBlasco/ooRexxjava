/* standard input, and a command's input, output and error captured (through rexxjs.exe they are relayed) */
parse pull line
say '['line']'
i.0 = 2; i.1 = 'apple'; i.2 = 'banana'
address system 'findstr ban' with input stem i. output stem o.
say o.0 o.1
address system 'echo oops 1>&2' with error stem e.
say e.0 strip(e.1)
