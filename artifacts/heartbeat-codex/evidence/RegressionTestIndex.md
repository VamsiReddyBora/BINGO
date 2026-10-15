# Executed production regression tests

These tests execute the actual coordinator, client protocol, heartbeat reducer and storage/statistics adapters. Fake transports/preferences are explicit test ports. Live broker evidence is separate. These are not Android UI tests.

| Suite | Exact executed test | Result |
|---|---|---|
| OnlineMatchHeartbeatPolicyTest | `recoveryGapOf5000MillisPreservesStabilityBut5001MillisRestartsIt` | PASS |
| OnlineMatchHeartbeatPolicyTest | `duplicateSyncDuringLateRecoveryDoesNotMoveTheStableWindowInEitherCallbackOrder` | PASS |
| ReliableMatchClientTest | `guestCannotPromoteItselfWhenHostStopsResponding` | PASS |
| ReliableMatchClientTest | `chatUsesAcceptedStableIdsAndSurvivesASeparateReturningClient` | PASS |
| ReliableMatchClientTest | `failedLocalAcceptanceAfterPublishRecoversTheBrokersCommittedSnapshot` | PASS |
| ReliableMatchClientTest | `exactPauseSurvivesWireAndResumeWithoutThirtySecondReset` | PASS |
| ReliableMatchClientTest | `twentyReconnectCyclesForEachRoleKeepOneHistoryAndNeverInventAResult` | PASS |
| ReliableMatchClientTest | `failedPublicationKeepsPendingStateAndBlocksMutationsUntilBrokerRecovery` | PASS |
| ReliableMatchClientTest | `forgedClaimAndStaleSyncAckCannotCreateAFinalResult` | PASS |
| ReliableMatchClientTest | `lateUnpausedSnapshotCannotClearADeliberatePause` | PASS |
| ReliableMatchClientTest | `absentPeerGetsFinalResultFromRetainedBrokerAfterWinnerClientCloses` | PASS |
| ReliableMatchClientTest | `inconsistentSavedBoardRemainsWaitingInsteadOfCrashingOrGeneratingANewBoard` | PASS |
| ReliableMatchClientTest | `duplicateSequenceWithDifferentPauseDataCannotOverwriteTheAcceptedCheckpoint` | PASS |
| ReliableMatchClientTest | `actualProtocolNegotiatesAndReconcilesAPickWithSeparateStores` | PASS |
| ReliableMatchClientTest | `wrongProtocolOrIncompleteSnapshotCannotUnlockRecovery` | PASS |
| ReliableMatchClientTest | `delayedSyncAckStillAuthorizesThePeerAfterTheOwnerAdvancesAndDuplicateRequestArrives` | PASS |
| ReliableMatchCoordinatorTest | `existingTenByTenBoardOptionRemainsSupported` | PASS |
| ReliableMatchCoordinatorTest | `failedRequiredCommitBlocksFurtherMutationAndLeavesAcceptedState` | PASS |
| ReliableMatchCoordinatorTest | `restartedOwnerSuspendsInsteadOfReplayingTimeoutBacklog` | PASS |
| ReliableMatchCoordinatorTest | `duplicateTimeoutNeverAdvancesTheNextTurn` | PASS |
| ReliableMatchCoordinatorTest | `claimRequiresTheActualCurrentThirdOpenEpisodeAndHealthyOwner` | PASS |
| ReliableMatchCoordinatorTest | `chatDisplayIdsDoNotUseCollidingThirtyTwoBitStringHashes` | PASS |
| ReliableMatchCoordinatorTest | `fullHistoryKeepsRealTurnsAndRejectsMissingBoardOrStalePause` | PASS |
| ReliableMatchCoordinatorTest | `completeLegitimateBingoMatchUsesTheAcceptedWinningPickAndCannotRestart` | PASS |
| ReliableMatchCoordinatorTest | `unavailablePlayerKeepsItsNormalTurnsWithoutAutomaticDefeat` | PASS |
| ReliableMatchCoordinatorTest | `surrenderAndConflictingTerminalActionsProduceOneResult` | PASS |
| ReliableMatchCoordinatorTest | `pauseResumePreserves14250MillisAndNextTurnIsExactly30000` | PASS |
| ReliableMatchCoordinatorTest | `pickTimeoutRaceHasOneAcceptedOutcomeInBothOrders` | PASS |
| ReliableMatchCoordinatorTest | `guestCannotBecomeOwnerOrAdvanceAHostTimeout` | PASS |
| ReliablePersistenceTest | `emptyAcceptedHistoryCanReplaceAnOldHistory` | PASS |
| ReliablePersistenceTest | `cancelledControllerCannotOverwriteANewerMatchOrResurrectDismissedData` | PASS |
| ReliablePersistenceTest | `scopedCleanupNeverRemovesTheOtherAccountsCheckpoint` | PASS |
| ReliablePersistenceTest | `ownerlessHostCheckpointIsNotAdoptedByGuestAndLegacyDataIsPreserved` | PASS |
| ReliablePolicyAgreementTest | `capabilitySurvivesTheActualCompactHeartbeatWireFormat` | PASS |
| ReliablePolicyAgreementTest | `missingOrLegacyAdvertisementsDoNotProveVersionTwoSupport` | PASS |
| ReliableMatchStatisticsTest | `resultHistoryAndCareerAreAtomicAndIdempotentAcrossRepositoryRestart` | PASS |
