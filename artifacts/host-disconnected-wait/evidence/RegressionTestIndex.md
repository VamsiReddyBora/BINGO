# Added production regression tests

These are exact executed XML names. They exercise actual production code through explicit test ports. Dialog properties tests are not Android tap tests.

| Suite | Executed method | Result |
|---|---|---|
| ReliableMatchClientTest | `coldHostWithPendingCheckpointAndAbsentGuestRecoversTheBrokerThenResumesAlone` | PASS |
| ReliableMatchClientTest | `guestAbsenceStillAllowsTheActiveHostToAdvanceNormally` | PASS |
| ReliableMatchClientTest | `zeroBeforeHostDetectionStaysZeroAndExpiresOnceAfterRecovery` | PASS |
| ReliableMatchClientTest | `durableFinalResultOverridesAnOldHostHoldEvenOnOfflineColdLaunch` | PASS |
| ReliableMatchClientTest | `delayedReadyRetriesWithoutLettingRetainedStateUnlockThePopup` | PASS |
| ReliableMatchClientTest | `staleBrokerPauseSampleCannotUndoAnAcceptedManualPauseWhenHostRestarts` | PASS |
| ReliableMatchClientTest | `hostCrashWithUnpublishedCandidateRecoversAcceptedBrokerStateInsteadOfInventingPickOrResult` | PASS |
| ReliableMatchClientTest | `guestColdLaunchRestoresBlockingHostHoldAndFrozenTime` | PASS |
| ReliableMatchClientTest | `hostAbsenceFreezes14250MillisAndEveryGuestActionUntilConfirmedRecovery` | PASS |
| ReliableMatchClientTest | `duplicateReadyCannotRestartTheResumedCountdown` | PASS |
| ReliableMatchClientTest | `manualPauseAndAcceptedChatSurviveHostHoldWithoutImplicitResume` | PASS |
| ReliableMatchClientTest | `fullSyncWithoutFinalReadyCannotRemoveHostPopupOrEnableOwnerMoves` | PASS |
| ReliableMatchClientTest | `replayedStateAndWrongReadyCannotUnlockHostHold` | PASS |
| ReliableMatchClientTest | `hostReturningWithoutGuestSuspendsOfflineTimeThenContinuesNormalTurns` | PASS |
| ReliableMatchClientTest | `pendingHostCandidateWithNoSharedCheckpointStaysBlockedAndCannotInventAResult` | PASS |
| ReliableMatchClientTest | `guestStorageFailureKeepsPopupAndRetriesInsteadOfUnlocking` | PASS |
| ReliableMatchCoordinatorTest | `recoveryHoldRejectsActionsAndWrongResumeAndKeepsExactRemainder` | PASS |
| ReliableMatchCoordinatorTest | `oldFrozenPositionCannotRewindAnAcceptedPickDuringRecovery` | PASS |
| ReliablePersistenceTest | `hostHoldSaveAndClearAreAccountAndMatchScoped` | PASS |
| HostDisconnectedDialogTest | `hostHoldDialogRejectsBackAndOutsideDismissal` | PASS |
| HostDisconnectedDialogTest | `hostHoldDialogUsesTheAgreedBlockingMessage` | PASS |
