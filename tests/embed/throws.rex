/* throws a USER condition that nothing catches: it goes from caller to caller,
   out of the program, to the Java host (EmbedTest) */
call read 'app.yaml'
return 'not reached'

read: procedure
  use arg file
  throw user badconfig array ('port', file) description ("missing key 'port' in" file)
