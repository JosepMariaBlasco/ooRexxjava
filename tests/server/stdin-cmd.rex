/* stdin-cmd.rex: a command reads standard input after the program has read some of it */
say "rexx:" linein()
address system "head -n 2"
say "rexx:" linein()
say "rexx:" linein()
