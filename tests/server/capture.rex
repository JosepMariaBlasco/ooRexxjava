/* commands with captured streams, large: input to cat, seq's output, an error stem */
do i = 1 to 50000; in.i = "line" i; end; in.0 = 50000
address system "cat" with input stem in. output stem out.
address system "seq 1 300000" with output stem s.
address system "sh -c 'echo err >&2; exit 3'" with error stem e.
say out.0 out.50000 "|" s.0 s.300000 "|" rc e.0 e.1
