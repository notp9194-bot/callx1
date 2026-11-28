# v937 — diagnostics: onCreateViewHolder by viewType (+ poolHadSome)
v936 report still shows ~410 creates (> 282 binds) at ~1.9 ms avg. Report now lists creates per viewType and how many happened
while the shared pool still held a holder of that type (poolHadSome; expected 0 - RecyclerView only creates on an empty pool, so
>0 means the holder was created for another path, e.g. a measuring/prefetch probe).
v936 results: fling-end invalidate gone (255 -> 0), dirty misses 67 -> 8, colour feature cost still negligible (Apply 5 ms once).
