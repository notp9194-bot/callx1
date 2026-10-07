# v468 — Reel comments: input focus state + spacing

4. Focus state: new `bg_comment_input_field` (selector) used ONLY by the comment bar field
   (`bg_comment_input` is shared by 7 other screens - untouched). 1dp stroke, transparent at rest
   (no size jump), brand_primary while focused. Radius 19 -> 20dp = exact pill at the 40dp one-line height,
   soft rounded rect when multi-line.
5. Spacing: emoji strip padding 4/2 -> 8/4dp; input row padding 8 all-round -> 12 horizontal / 4 top / 10 bottom;
   char counter top 2 -> 6dp; photo preview margins 12/6 -> 16 start / 10 top / 2 bottom.
   No extra divider (the existing hairline above the input block is enough once padding breathes).

Files: activity_reel_comment.xml, bg_comment_input_field.xml (new), version.properties (126).
