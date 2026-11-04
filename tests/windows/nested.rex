/* a rexx that a command runs (nested), as the suite's tests do */
address system 'rexx -e "say 42"' with output stem o. error stem e.
say 'captured: rc' rc 'out' o.0 o.1 'err' e.0 e.1
'rexx -e "say 43"'
say 'direct: rc' rc
address system 'rexx' with output stem o. error stem e.
say 'no arguments: rc' rc 'out' o.0 o.1 'err' e.0 e.1
