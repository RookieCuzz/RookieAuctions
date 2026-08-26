package me.elian.ezauctions.controller.session;

import me.elian.ezauctions.model.AuctionRecordStatus;
import me.elian.ezauctions.session.SessionState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionSessionControllerPolicyTest {
	private static final Instant START = Instant.parse("2026-08-26T12:00:00Z");
	private static final Clock AT_START = Clock.fixed(START, ZoneOffset.UTC);

	@Test
	void nonRunningSessionsCannotAdvanceBeforeTheirWallClockStart() {
		long oneSecondLater = START.plusSeconds(1).toEpochMilli();
		for (SessionState state : new SessionState[]{
				SessionState.LOCKED, SessionState.BLOCKED, SessionState.WAITING}) {
			assertFalse(AuctionSessionController.isDueCandidate(
					state, oneSecondLater, AT_START.instant()), state.name());
		}
	}

	@Test
	void allNonRunningCandidatesBecomeDueAtTheInclusiveStartBoundary() {
		long exactStart = START.toEpochMilli();
		for (SessionState state : new SessionState[]{
				SessionState.LOCKED, SessionState.BLOCKED, SessionState.WAITING}) {
			assertTrue(AuctionSessionController.isDueCandidate(
					state, exactStart, AT_START.instant()), state.name());
		}
	}

	@Test
	void persistedRunningIsHandledOnlyByTheDedicatedRecoveryPath() {
		assertFalse(AuctionSessionController.isDueCandidate(SessionState.RUNNING,
				START.minusSeconds(60).toEpochMilli(), AT_START.instant()));
	}

	@Test
	void missedGraceBoundaryIsInclusiveAndWaitingConflictsAreNeverDeferred() {
		long scheduled = START.toEpochMilli();
		Instant exactDeadline = START.plusSeconds(1_800);
		assertFalse(AuctionSessionController.shouldDeferMissed(
				SessionState.LOCKED, scheduled, exactDeadline, 1_800));
		assertFalse(AuctionSessionController.shouldDeferMissed(
				SessionState.BLOCKED, scheduled, exactDeadline, 1_800));
		assertTrue(AuctionSessionController.shouldDeferMissed(
				SessionState.LOCKED, scheduled, exactDeadline.plusMillis(1), 1_800));
		assertTrue(AuctionSessionController.shouldDeferMissed(
				SessionState.BLOCKED, scheduled, exactDeadline.plusMillis(1), 1_800));
		assertFalse(AuctionSessionController.shouldDeferMissed(
				SessionState.WAITING, scheduled, exactDeadline.plusSeconds(3_600), 1_800));
	}

	@Test
	void runningVenueRecoveryUsesInclusiveThirtySecondRetryBoundary() {
		long firstAttempt = START.toEpochMilli();
		assertTrue(AuctionSessionController.runningRecoveryRetryDue(
				0L, firstAttempt, 30));
		assertFalse(AuctionSessionController.runningRecoveryRetryDue(
				firstAttempt, firstAttempt + 29_999L, 30));
		assertTrue(AuctionSessionController.runningRecoveryRetryDue(
				firstAttempt, firstAttempt + 30_000L, 30));
		assertFalse(AuctionSessionController.runningRecoveryRetryDue(
				firstAttempt, firstAttempt - 1L, 30));
	}

	@Test
	void legacyRouterOnlyAcceptsPreparingOrAlreadyQueuedRecords() {
		assertTrue(AuctionSessionController.canRouteLegacyRecordStatus(
				AuctionRecordStatus.PREPARING));
		assertTrue(AuctionSessionController.canRouteLegacyRecordStatus(
				AuctionRecordStatus.QUEUED));
		assertFalse(AuctionSessionController.canRouteLegacyRecordStatus(
				AuctionRecordStatus.ACTIVE));
		assertFalse(AuctionSessionController.canRouteLegacyRecordStatus(
				AuctionRecordStatus.COMPLETED));
		assertFalse(AuctionSessionController.canRouteLegacyRecordStatus(
				AuctionRecordStatus.CANCELLED));
	}

	@Test
	void attendanceBarrierPreventsEitherFreshOrRecoveredTimerFromStartingEarly() {
		assertFalse(AuctionSessionController.attendanceAllowsTimer(true));
		assertTrue(AuctionSessionController.attendanceAllowsTimer(false));
	}

	@Test
	void anAlreadyPersistedCompletedSessionReleasesTheRuntimeAfterACasRetry() {
		assertTrue(AuctionSessionController.completionStateCommitted(true, SessionState.RUNNING));
		assertTrue(AuctionSessionController.completionStateCommitted(false, SessionState.COMPLETED));
		assertFalse(AuctionSessionController.completionStateCommitted(false, SessionState.RUNNING));
		assertFalse(AuctionSessionController.completionStateCommitted(false, null));
	}

	@Test
	void aReservedLotCannotBecomeActiveBeforeItsSubmissionJournalPublishesQueued() {
		assertTrue(AuctionSessionController.auctionStateAllowsLotPromotion(
				AuctionRecordStatus.QUEUED));
		assertFalse(AuctionSessionController.auctionStateAllowsLotPromotion(
				AuctionRecordStatus.PREPARING));
		assertFalse(AuctionSessionController.auctionStateAllowsLotPromotion(
				AuctionRecordStatus.ACTIVE));
		assertFalse(AuctionSessionController.auctionStateAllowsLotPromotion(
				AuctionRecordStatus.CANCELLED));
	}
}
