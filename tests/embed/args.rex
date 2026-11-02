/* the arguments, as count:list */
parse source . . me
list = ""
do i = 1 to arg()
  list = list || arg(i) || ","
end
return arg()":"strip(list, "T", ",")
