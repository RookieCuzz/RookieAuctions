package me.elian.ezauctions.controller;

import me.elian.ezauctions.model.AuctionSessionLot;
import me.elian.ezauctions.session.LotState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AuctionControllerRecoveryPolicyTest {
	private static final String SESSION_ID = "2026-08-26/evening";

	@Test
	void activeSessionLotIsProtectedBeforeItsFirstCheckpointExists() throws Exception {
		AuctionSessionLot active = lot(1, LotState.ACTIVE);
		AuctionSessionLot queued = lot(2, LotState.QUEUED);

		assertEquals(Set.of(active.getAuctionId()), AuctionController.protectedAuctionIds(
				List.of(active, queued), Optional.empty()));
	}

	@Test
	void checkpointLotRemainsProtectedAlongsideEveryActiveLot() throws Exception {
		AuctionSessionLot firstActive = lot(1, LotState.ACTIVE);
		AuctionSessionLot secondActive = lot(2, LotState.ACTIVE);
		AuctionSessionLot checkpointLocked = lot(3, LotState.LOCKED);
		AuctionSessionLot unrelatedQueued = lot(4, LotState.QUEUED);

		Set<UUID> protectedIds = AuctionController.protectedAuctionIds(
				List.of(firstActive, secondActive, checkpointLocked, unrelatedQueued),
				Optional.of(checkpointLocked));

		assertEquals(Set.of(firstActive.getAuctionId(), secondActive.getAuctionId(),
				checkpointLocked.getAuctionId()), protectedIds);
		assertFalse(protectedIds.contains(unrelatedQueued.getAuctionId()));
	}

	private AuctionSessionLot lot(int sequence, LotState state) throws ReflectiveOperationException {
		AuctionSessionLot lot = new AuctionSessionLot(SESSION_ID, UUID.randomUUID(), UUID.randomUUID(),
				sequence, 1_000L + sequence);
		Field stateField = AuctionSessionLot.class.getDeclaredField("state");
		stateField.setAccessible(true);
		stateField.set(lot, state.name());
		return lot;
	}
}
