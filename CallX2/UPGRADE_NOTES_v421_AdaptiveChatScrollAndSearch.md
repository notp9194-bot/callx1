# v421 — Adaptive chat scroll budget + visible-only search refresh

Applied to both `ChatActivity` and `GroupChatActivity`:

- Replaced the always-large layout/cache/prefetch budget with a device-aware
  policy that considers scroll state, fling velocity, available RAM and
  metered/offline network state.
- Replaced the stacked fixed media preloaders with one adaptive
  `ChatMediaPreloader`. It uses a bounded hard ceiling, smaller windows during
  fast flings or constrained devices, and larger windows only when useful.
- Changed in-chat search debounce from 300ms to 120ms.
- Changed search highlight refresh from a loaded-list range notification to
  notifications for currently attached RecyclerView children only. Newly
  bound rows still apply the active query during normal binding.

No build or test was run for this upgrade.