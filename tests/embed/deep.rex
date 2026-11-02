/* recursion n deep */
use arg n
return depth(n)
depth: procedure
  use arg k
  if k = 0 then return 0
  return depth(k - 1) + 1
