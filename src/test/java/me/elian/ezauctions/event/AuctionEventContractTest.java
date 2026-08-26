package me.elian.ezauctions.event;

import me.elian.ezauctions.session.AuctionMode;
import me.elian.ezauctions.session.AuctionPublicLotView;
import me.elian.ezauctions.session.AuctionSessionView;
import me.elian.ezauctions.session.LotState;
import me.elian.ezauctions.session.PublicBidPrice;
import me.elian.ezauctions.session.SessionProgress;
import me.elian.ezauctions.session.SessionState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionEventContractTest {
	private static final Instant START = Instant.parse("2026-08-26T06:00:00Z");

	@Test
	void sessionEventsOnlyAcceptTheirDurableLifecycleStates() {
		AuctionSessionView running = session(SessionState.RUNNING);
		AuctionSessionView completed = session(SessionState.COMPLETED);
		AuctionSessionView skipped = session(SessionState.SKIPPED);

		assertDoesNotThrow(() -> new AuctionSessionStartEvent(running));
		assertThrows(IllegalArgumentException.class, () -> new AuctionSessionStartEvent(completed));
		assertDoesNotThrow(() -> new AuctionSessionEndEvent(completed));
		assertTrue(new AuctionSessionEndEvent(skipped).isSkipped());
		assertThrows(IllegalArgumentException.class, () -> new AuctionSessionEndEvent(running));
	}

	@Test
	void lotEventsOnlyAcceptActiveViewsAndPositiveSequences() {
		AuctionPublicLotView active = lot(LotState.ACTIVE);
		AuctionPublicLotView queued = lot(LotState.QUEUED);

		assertDoesNotThrow(() -> new AuctionLotStartEvent(active));
		assertThrows(IllegalArgumentException.class, () -> new AuctionLotStartEvent(queued));
		assertDoesNotThrow(() -> new AuctionLotFinishedEvent(
				active.sessionKey(), active.lotId(), UUID.randomUUID(), active.sequenceNumber()));
		assertThrows(IllegalArgumentException.class, () -> new AuctionLotFinishedEvent(
				active.sessionKey(), active.lotId(), UUID.randomUUID(), 0));
	}

	@Test
	void eachReliableEventUsesAStableBukkitHandlerList() {
		assertSame(AuctionSessionStartEvent.getHandlerList(),
				new AuctionSessionStartEvent(session(SessionState.RUNNING)).getHandlers());
		assertSame(AuctionSessionEndEvent.getHandlerList(),
				new AuctionSessionEndEvent(session(SessionState.COMPLETED)).getHandlers());
		assertSame(AuctionLotStartEvent.getHandlerList(),
				new AuctionLotStartEvent(lot(LotState.ACTIVE)).getHandlers());
		assertSame(AuctionLotFinishedEvent.getHandlerList(),
				new AuctionLotFinishedEvent("2026-08-26/afternoon", UUID.randomUUID(),
						UUID.randomUUID(), 1).getHandlers());
	}

	private static AuctionSessionView session(SessionState state) {
		boolean running = state == SessionState.RUNNING;
		return new AuctionSessionView(
				"2026-08-26/afternoon", "afternoon", START, START.minusSeconds(600),
				state, running ? 1 : 0, 16,
				running ? Optional.of(SessionProgress.running(1, 1, 120)) : Optional.empty(),
				running ? OptionalLong.of(120) : OptionalLong.empty());
	}

	private static AuctionPublicLotView lot(LotState state) {
		return new AuctionPublicLotView(
				UUID.randomUUID(), "2026-08-26/afternoon", 1, state, AuctionMode.OPEN,
				"钻石", 1, "Seller", 10_000L, 100L, 0,
				PublicBidPrice.visible(10_000L), 120, 1);
	}
}
