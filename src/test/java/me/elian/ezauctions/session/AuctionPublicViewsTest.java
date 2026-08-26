package me.elian.ezauctions.session;

import me.elian.ezauctions.immersive.AttendanceResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionPublicViewsTest {
	@Test
	void deferredRelationIsTerminalEvenThoughTheAuctionContinuesInANewSession() {
		assertTrue(LotState.DEFERRED.isTerminal());
		assertTrue(LotState.SETTLED.isTerminal());
		assertTrue(LotState.CANCELLED.isTerminal());
		assertFalse(LotState.QUEUED.isTerminal());
	}

	@Test
	void attendanceResultRejectsInvalidPublicInput() {
		assertThrows(NullPointerException.class,
				() -> new AttendanceResult(null, null, null));
		assertThrows(IllegalArgumentException.class,
				() -> new AttendanceResult(AttendanceResult.Status.INVALID_SESSION, " ", null));
	}

	@Test
	void sealedProjectionCannotCarryTheAuthoritativeHighestPrice() {
		AuctionPublicLotView sealed = lot(AuctionMode.SEALED, PublicBidPrice.sealed());

		assertTrue(sealed.sealed());
		assertTrue(sealed.currentPriceMinor().isEmpty());
		assertThrows(IllegalArgumentException.class,
				() -> lot(AuctionMode.SEALED, PublicBidPrice.visible(999_99L)));
		assertFalse(Arrays.stream(AuctionPublicLotView.class.getRecordComponents())
				.map(RecordComponent::getName)
				.anyMatch(name -> name.toLowerCase().contains("highest")
						|| name.toLowerCase().contains("bidder")));
	}

	@Test
	void openProjectionRequiresAndExposesOnlyItsPublicPrice() {
		AuctionPublicLotView open = lot(AuctionMode.OPEN, PublicBidPrice.visible(125_00L));

		assertEquals(OptionalLong.of(125_00L), open.currentPriceMinor());
		assertThrows(IllegalArgumentException.class,
				() -> lot(AuctionMode.OPEN, PublicBidPrice.sealed()));
	}

	@Test
	void runningSessionViewCarriesAConsistentProgressAndEtaSnapshot() {
		SessionProgress progress = SessionProgress.running(3, 16, 30);
		AuctionSessionView view = new AuctionSessionView(
				"2026-08-26/afternoon",
				"afternoon",
				Instant.parse("2026-08-26T06:00:00Z"),
				Instant.parse("2026-08-26T05:50:00Z"),
				SessionState.RUNNING,
				16,
				16,
				Optional.of(progress),
				OptionalLong.of(1_720L)
		);

		assertEquals(3, view.activeLotNumber().orElseThrow());
		assertEquals(0, view.remainingCapacity());
		assertThrows(IllegalArgumentException.class, () -> new AuctionSessionView(
				view.sessionKey(), view.slotId(), view.scheduledStart(), view.submissionsLockAt(),
				SessionState.RUNNING, 16, 16, Optional.empty(), OptionalLong.empty()));
	}

	@Test
	void submissionResultOnlyProvidesALotIdAfterAnAcceptedReservation() {
		UUID lotId = UUID.randomUUID();
		SubmissionResult accepted = SubmissionResult.success(
				"2026-08-26/afternoon", lotId, 16, 16);
		SubmissionResult full = SubmissionResult.rejected(
				ReservationStatus.FULL, "2026-08-26/afternoon", 16, 16);

		assertTrue(accepted.accepted());
		assertEquals(lotId, accepted.optionalLotId().orElseThrow());
		assertFalse(full.accepted());
		assertTrue(full.optionalLotId().isEmpty());
		assertThrows(IllegalArgumentException.class, () -> new SubmissionResult(
				ReservationStatus.FULL, "2026-08-26/afternoon", lotId, 16, 16));
	}

	private static AuctionPublicLotView lot(AuctionMode mode, PublicBidPrice price) {
		return new AuctionPublicLotView(
				UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
				"2026-08-26/afternoon",
				1,
				LotState.ACTIVE,
				mode,
				"钻石",
				1,
				"Seller",
				100_00L,
				5_00L,
				500_00L,
				price,
				120,
				1L
		);
	}
}
