/* a rexx that a command runs (nested), as the suite's tests do; its exit code -1
   (rexx alone: the usage) is a 32-bit one on Windows, not "could not start" */
address system 'rexx -e "say 42"' with output stem o.
say rc o.1
'rexx -e "say 43"'
say rc
address system 'rexx' with output stem o. error stem e.
say rc
