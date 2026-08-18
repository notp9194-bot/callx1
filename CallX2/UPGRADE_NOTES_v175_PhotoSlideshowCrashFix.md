# v175 — Photo Slideshow Crash Fix (IllegalStateException: ViewPager2)

## The bug

In `HomeFragment.addFeedPostCard()`, the photo-slideshow `ViewPager2`'s
page (`ImageView`) was given a fixed pixel height instead of
`MATCH_PARENT`:

```java
iv.setLayoutParams(new ViewGroup.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, photoH));   // ❌ fixed height
```

ViewPager2 requires every page view to be **exactly** `MATCH_PARENT` on
both width and height — it enforces this in
`ViewPager2$4.onChildViewAttachedToWindow`. Any other height (even one
that numerically equals the pager's own height) throws:

```
IllegalStateException: Pages must fill the whole ViewPager2 (use match_parent)
```

This crashed the Home feed as soon as a photo-slideshow post scrolled
into view.

## The fix

```java
iv.setLayoutParams(new ViewGroup.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
```

The actual height (`photoH`, 16:9 aspect based on screen width) is
already applied to the `ViewPager2` itself via `pagerLp` a few lines
above — the page just needs to fill that, so `MATCH_PARENT` is correct
and still renders at the same size as before.
