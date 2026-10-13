# v600 — Chat poll polish 2 (percent clarity, voters list in 1:1, shared controller, strings, haptics)

4. Percent clarity: % is always "share of voters" (clamped to 100). Multi-choice polls now say so:
   subtitle "Select one or more · % of voters" and footer "N voters" (single-choice keeps "N votes").
5. 1:1 voters list: ChatActivity now handles onPollVotersTap. In 1:1 there is no avatar strip, so the
   "N votes ›" footer itself is tappable (non-anonymous polls with >= 1 vote) and opens the same
   "Poll votes" dialog (new chat/ui/PollVotersDialog, shared with groups; anonymous polls never open it).
6. De-dupe: GroupChatActivity.castPollVote / applyPollVotesLocally / togglePollClosed / showGroupPollVoters /
   poll create are gone; group uses ChatPollController through a small ChatPollController.Host interface
   (ChatActivityDelegate extends it, so 1:1 is unchanged). One place to fix poll bugs now.
   Side effect: group now also shows the "offline - vote will sync" toast, same as 1:1.
7. Strings: all poll UI text moved to strings_poll.xml (create sheet, POLL/LIVE/CLOSED, subtitles, footer,
   toasts, voters dialog, legacy bubble). Canvas resolves them once per bind, not per draw frame.
8. Haptics: light tick on vote tap and on close/reopen, confirm tick when a poll is sent.

9. Close confirmation: closing a poll now shows "Close this poll?" (Cancel / Close poll). Reopening
   stays one tap. Shared by 1:1 and group via ChatPollController.toggleClosed.
10. TalkBack: new canvas/PollAccessibilityHelper (ExploreByTouchHelper). Each option row is a virtual
   radio/checkbox node: "Pizza, 40 percent, 4 votes, selected, leading" with a click action that votes
   (disabled when closed). The bubble itself reads "Poll: question. subtitle. N votes". Installed lazily in
   bindPoll(); inert for other bubble types. Added androidx.customview dependency to feature-chat.
