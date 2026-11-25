# v864 - Bubble Perf: full frame-stage breakdown
First measurement (vivo V2338, API 35): bubble record+measure+bind ~1 ms/frame vs frame P50 19.5 ms -> bubble work is
NOT the bottleneck. Report now adds per-stage avg ms (input, animation, layout/measure, draw, sync, cmdIssue, swap,
unknownDelay, gpu on API31+), the same for frames > 2x budget, which stage dominates over-budget frames, and whether
the build is DEBUG.
