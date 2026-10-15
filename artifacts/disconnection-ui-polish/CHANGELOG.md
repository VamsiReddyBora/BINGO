# Disconnection UI polish

Completed 2026-10-15T23:06:13+05:30. Source branch `feat/disconnection-popup-polish`, base `1120c2d`. Uses the same active worktree `/home/bob/workspace/bingo_gameplay_feedback_fix` to reuse build caches. Previous working source remains on `fix/gameplay-feedback-dialogs`; its APK remains unchanged.

- Add display-only formatting for existing system notices. Copy includes “🔄 Updating your match…”, “✅ Let’s play!”, named red disconnect warnings, green reconnection/online notices, and amber pause/connection checks. Saved message text and IDs are unchanged.
- Colour centered system chat with readable text and subtle matching backgrounds/borders for light/dark themes. Player chat alignment/content is unchanged.
- Restyle the disconnect decision card with the opponent avatar/status ring, compact warning badge, prominent Continue, outlined Pause and an eligible red-outline Claim action. Add a smoothly shrinking bar derived from the existing deadline and “Auto-continue in Ns”.
- Keep exactly the same decision deadline, automatic Continue callback, claim confirmation, eligibility, host-only actions and dismissal properties. No new game timer or timeout command.
- Restyle Host Disconnected with the host avatar, red title, host badge, reassuring board/time copy and gently animated waiting dots. No action buttons or new countdown. Existing synchronized state alone removes the hold.
- Show coloured live opponent status in the pause card; update Pause/Resume wording to “match”.
- Gameplay, heartbeat, networking, persistence and RootNavGraph files are byte-for-byte unchanged from the previous working commit. Existing dialog copy assertions were updated; no extra UI-only tests were added.
- Signed release output uses a new filename/directory; protected baseline and previous working APK were rechecked unchanged.

Rollback: the previous working source is `fix/gameplay-feedback-dialogs` at `1120c2d`; APK is `artifacts/gameplay-feedback-fix/Bingo-gameplay-feedback-fix.apk`. Keep app data. No push, deployment, real invitation or profile write.
