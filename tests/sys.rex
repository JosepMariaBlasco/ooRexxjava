f = "test-out.txt"
call lineout f, "line one"; call lineout f, "line two"; call lineout f
say "1. file:" lines(f) "lines, first =" linein(f, 1)
s = .stream~new(f); s~open("read"); say "   via .Stream:" s~arrayIn~items "lines"; s~close
call SysFileDelete f
push "pushed"; queue "queued"
say "2. queue:" queued() "items:" linein("QUEUE:") "/" linein("QUEUE:")
say "3. SysFileTree:" SysFileTree("*.rex", "st.", "F") st.0 "file(s)"
interpret 'x = 6 * 7'; say "4. interpret:" x
re = .RegularExpression~new("[a-z]+[0-9]")
say "5. rxregexp:" re~match("abc1")
say "6. date/time:" date("S") length(time("L"))
say "7. env:" (value("HOME", , "ENVIRONMENT") \== "")
address system "echo hi"
say "8. command rc =" rc
::requires "rxregexp.cls"
