package me.elian.ezauctions.data;

import me.elian.ezauctions.Logger;
import me.elian.ezauctions.model.AuctionRecord;
import me.elian.ezauctions.model.AuctionRecordStatus;
import me.elian.ezauctions.model.AuctionSessionLot;
import me.elian.ezauctions.model.AuctionSessionRecord;
import me.elian.ezauctions.model.AuctionSubmissionTransaction;
import me.elian.ezauctions.model.RewardKind;
import me.elian.ezauctions.model.RewardRecord;
import me.elian.ezauctions.model.SubmissionTransactionState;
import me.elian.ezauctions.session.LotState;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrmLiteSubmissionTransactionTest {
	private static final String SESSION_ID = "2026-08-26/afternoon";
	private static final long NOW = 1_000_000L;
	private static final long LOCK = 1_600_000L;
	private static final long START = 2_200_000L;

	@TempDir
	Path temporaryDirectory;

	private OrmLiteDatabase database;

	@BeforeAll
	static void setUpBukkit() {
		MockBukkit.mock();
	}

	@AfterAll
	static void tearDownBukkit() {
		MockBukkit.unmock();
	}

	@BeforeEach
	void setUpDatabase() throws Exception {
		database = new OrmLiteDatabase(new SilentLogger(),
				"jdbc:sqlite:" + temporaryDirectory.resolve("submissions.sqlite"));
		await(database.createSessionIfAbsent(new AuctionSessionRecord(
				SESSION_ID, START, LOCK, 16, 2, NOW - 1)));
	}

	@AfterEach
	void tearDownDatabase() {
		if (database != null) {
			database.shutdown();
		}
	}

	@Test
	void escrowedReservationCanCommitAfterCutoffLockedIt() throws Exception {
		SubmissionFixture fixture = createSubmission(250L);
		advanceToItemEscrowed(fixture.transaction());
		assertTrue(await(database.transitionSessionLot(fixture.lot().getId(),
				LotState.RESERVED, LotState.LOCKED, LOCK)));

		assertTrue(await(database.commitSubmissionTransaction(fixture.transaction().getId(),
				LOCK + 1)));
		assertTrue(await(database.commitSubmissionTransaction(fixture.transaction().getId(),
				LOCK + 2)));

		assertEquals(SubmissionTransactionState.COMMITTED,
				await(database.getSubmissionTransaction(fixture.transaction().getId()))
						.orElseThrow().getState());
		assertEquals(AuctionRecordStatus.QUEUED,
				await(database.getAuctionRecord(fixture.auctionId())).orElseThrow().getStatus());
		assertEquals(LotState.LOCKED,
				await(database.getSessionLot(fixture.lot().getId())).orElseThrow().getState());
		assertTrue(await(database.getRewards(fixture.sellerId(),
				EnumSet.allOf(RewardKind.class), true)).isEmpty());
	}

	@Test
	void compensationCancelsLockedGhostAndCreatesEachMailboxRewardOnce() throws Exception {
		SubmissionFixture fixture = createSubmission(250L);
		advanceToItemEscrowed(fixture.transaction());
		assertTrue(await(database.transitionSessionLot(fixture.lot().getId(),
				LotState.RESERVED, LotState.LOCKED, LOCK)));

		assertTrue(await(database.compensateSubmissionTransaction(fixture.transaction().getId(),
				"test cutoff interruption", LOCK + 1)));
		assertTrue(await(database.compensateSubmissionTransaction(fixture.transaction().getId(),
				"idempotent retry", LOCK + 2)));

		assertEquals(SubmissionTransactionState.COMPENSATED,
				await(database.getSubmissionTransaction(fixture.transaction().getId()))
						.orElseThrow().getState());
		assertEquals(AuctionRecordStatus.CANCELLED,
				await(database.getAuctionRecord(fixture.auctionId())).orElseThrow().getStatus());
		assertEquals(LotState.CANCELLED,
				await(database.getSessionLot(fixture.lot().getId())).orElseThrow().getState());

		List<RewardRecord> rewards = await(database.getRewards(fixture.sellerId(),
				EnumSet.allOf(RewardKind.class), true));
		assertEquals(2, rewards.size());
		assertEquals(1, rewards.stream().filter(reward -> reward.getKind() == RewardKind.ITEM).count());
		assertEquals(1, rewards.stream().filter(reward -> reward.getKind() == RewardKind.REFUND).count());
		assertEquals(250L, rewards.stream()
				.filter(reward -> reward.getKind() == RewardKind.REFUND)
				.findFirst().orElseThrow().getMoneyMinor());
	}

	@Test
	void preparedAndRejectedSubmissionsCloseWithoutInventingRefunds() throws Exception {
		SubmissionFixture prepared = createSubmission(250L);
		assertTrue(await(database.compensateSubmissionTransaction(prepared.transaction().getId(),
				"reservation rejected", NOW + 10)));
		assertTrue(await(database.getRewards(prepared.sellerId(),
				EnumSet.allOf(RewardKind.class), true)).isEmpty());

		SubmissionFixture rejected = createSubmission(250L);
		assertTrue(await(database.transitionSubmissionTransaction(rejected.transaction().getId(),
				SubmissionTransactionState.PREPARED, SubmissionTransactionState.FEE_WITHDRAWING,
				"", NOW + 20)));
		assertTrue(await(database.transitionSubmissionTransaction(rejected.transaction().getId(),
				SubmissionTransactionState.FEE_WITHDRAWING, SubmissionTransactionState.FAILED,
				"Vault rejected", NOW + 21)));
		assertFalse(SubmissionTransactionState.FAILED.isTerminal());
		assertTrue(await(database.compensateSubmissionTransaction(rejected.transaction().getId(),
				"known rejection", NOW + 22)));
		assertTrue(await(database.getRewards(rejected.sellerId(),
				EnumSet.allOf(RewardKind.class), true)).isEmpty());
	}

	@Test
	void interruptedResourcePhasesUseTheConservativeCompensationMatrix() throws Exception {
		for (CompensationExpectation expectation : List.of(
				new CompensationExpectation(SubmissionTransactionState.FEE_WITHDRAWING, false, true),
				new CompensationExpectation(SubmissionTransactionState.FEE_WITHDRAWN, false, true),
				new CompensationExpectation(SubmissionTransactionState.ITEM_ESCROWING, true, true))) {
			SubmissionFixture fixture = createSubmission(250L);
			advanceTo(fixture.transaction(), expectation.state());
			assertTrue(await(database.compensateSubmissionTransaction(fixture.transaction().getId(),
					"phase matrix", NOW + 50)));

			List<RewardRecord> rewards = await(database.getRewards(fixture.sellerId(),
					EnumSet.allOf(RewardKind.class), true));
			assertEquals(expectation.itemReward() ? 1 : 0,
					rewards.stream().filter(reward -> reward.getKind() == RewardKind.ITEM).count());
			assertEquals(expectation.feeReward() ? 1 : 0,
					rewards.stream().filter(reward -> reward.getKind() == RewardKind.REFUND).count());
			assertEquals(LotState.CANCELLED,
					await(database.getSessionLot(fixture.lot().getId())).orElseThrow().getState());
		}
	}

	private SubmissionFixture createSubmission(long feeMinor) throws Exception {
		UUID auctionId = UUID.randomUUID();
		UUID sellerId = UUID.randomUUID();
		AuctionRecord record = new AuctionRecord(auctionId, sellerId,
				new ItemStack(Material.DIAMOND), 3, false, "world",
				1_000L, 100L, 0L, 120);
		await(database.createAuctionRecord(record));
		AuctionSubmissionTransaction transaction = new AuctionSubmissionTransaction(
				auctionId, sellerId, SESSION_ID, feeMinor, NOW);
		await(database.createSubmissionTransaction(transaction));
		var reservation = await(database.reserveSessionLot(SESSION_ID, auctionId, sellerId, NOW + 1));
		assertTrue(reservation.accepted());
		AuctionSessionLot lot = await(database.getSessionLot(reservation.lotId())).orElseThrow();
		return new SubmissionFixture(auctionId, sellerId, transaction, lot);
	}

	private void advanceToItemEscrowed(AuctionSubmissionTransaction transaction) throws Exception {
		advanceTo(transaction, SubmissionTransactionState.ITEM_ESCROWED);
	}

	private void advanceTo(AuctionSubmissionTransaction transaction,
	                       SubmissionTransactionState target) throws Exception {
		assertTrue(await(database.transitionSubmissionTransaction(transaction.getId(),
				SubmissionTransactionState.PREPARED, SubmissionTransactionState.FEE_WITHDRAWING,
				"", NOW + 2)));
		if (target == SubmissionTransactionState.FEE_WITHDRAWING) {
			return;
		}
		assertTrue(await(database.transitionSubmissionTransaction(transaction.getId(),
				SubmissionTransactionState.FEE_WITHDRAWING, SubmissionTransactionState.FEE_WITHDRAWN,
				"", NOW + 3)));
		if (target == SubmissionTransactionState.FEE_WITHDRAWN) {
			return;
		}
		assertTrue(await(database.transitionSubmissionTransaction(transaction.getId(),
				SubmissionTransactionState.FEE_WITHDRAWN, SubmissionTransactionState.ITEM_ESCROWING,
				"", NOW + 4)));
		if (target == SubmissionTransactionState.ITEM_ESCROWING) {
			return;
		}
		assertTrue(await(database.transitionSubmissionTransaction(transaction.getId(),
				SubmissionTransactionState.ITEM_ESCROWING, SubmissionTransactionState.ITEM_ESCROWED,
				"", NOW + 5)));
		if (target != SubmissionTransactionState.ITEM_ESCROWED) {
			throw new IllegalArgumentException("Unsupported target state " + target);
		}
	}

	private static <T> T await(CompletableFuture<T> future) throws Exception {
		return future.get(5, TimeUnit.SECONDS);
	}

	private record SubmissionFixture(UUID auctionId, UUID sellerId,
	                                 AuctionSubmissionTransaction transaction,
	                                 AuctionSessionLot lot) {
	}

	private record CompensationExpectation(SubmissionTransactionState state,
	                                       boolean itemReward, boolean feeReward) {
	}

	private static final class SilentLogger implements Logger {
		@Override
		public void info(String message) {
		}

		@Override
		public void warning(String message) {
		}

		@Override
		public void warning(String message, Exception exception) {
		}

		@Override
		public void severe(String message) {
		}

		@Override
		public void severe(String message, Exception exception) {
		}
	}
}
