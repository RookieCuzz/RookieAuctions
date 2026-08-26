package me.elian.ezauctions.data;

import me.elian.ezauctions.Logger;
import me.elian.ezauctions.model.AuctionRecord;
import me.elian.ezauctions.model.AuctionRecordStatus;
import me.elian.ezauctions.model.RewardKind;
import me.elian.ezauctions.model.RewardRecord;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrmLiteAuctionSettlementTest {
	private static final long COMPLETED_AT = 2_000_000L;

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
				"jdbc:sqlite:" + temporaryDirectory.resolve("settlement.sqlite"));
	}

	@AfterEach
	void tearDownDatabase() {
		if (database != null) {
			database.shutdown();
		}
	}

	@Test
	void completedAuctionAndRewardsCommitTogetherAndRetryDoesNotDuplicateRewards() throws Exception {
		UUID auctionId = UUID.randomUUID();
		UUID sellerId = UUID.randomUUID();
		UUID winnerId = UUID.randomUUID();
		createAuction(auctionId, sellerId, AuctionRecordStatus.ACTIVE);

		List<RewardRecord> firstRewards = List.of(
				RewardRecord.item(winnerId, auctionId, new ItemStack(Material.DIAMOND), 1, "world"),
				RewardRecord.money(sellerId, auctionId, RewardKind.INCOME, 9_000L));
		assertTrue(await(database.completeAuctionWithRewards(auctionId, winnerId,
				10_000L, 9_000L, 1_000L, "WINNER_MAILBOX", "NOT_REQUIRED",
				COMPLETED_AT, firstRewards)));

		AuctionRecord completed = await(database.getAuctionRecord(auctionId)).orElseThrow();
		assertEquals(AuctionRecordStatus.COMPLETED, completed.getStatus());
		assertEquals(COMPLETED_AT, completed.getCompletedAtMillis());
		assertEquals(winnerId, completed.getWinnerId());
		assertEquals(10_000L, completed.getFinalPriceMinor());
		assertEquals(9_000L, completed.getPayoutMinor());
		assertEquals(1_000L, completed.getTaxMinor());
		assertEquals("WINNER_MAILBOX", completed.getItemDestination());
		assertEquals("NOT_REQUIRED", completed.getRefundStatus());
		assertSingleReward(winnerId, RewardKind.ITEM, auctionId);
		assertSingleReward(sellerId, RewardKind.INCOME, auctionId);

		List<RewardRecord> retryRewards = List.of(
				RewardRecord.item(winnerId, auctionId, new ItemStack(Material.DIAMOND), 1, "world"),
				RewardRecord.money(sellerId, auctionId, RewardKind.INCOME, 9_000L));
		assertTrue(await(database.completeAuctionWithRewards(auctionId, winnerId,
				10_000L, 9_000L, 1_000L, "WINNER_MAILBOX", "NOT_REQUIRED",
				COMPLETED_AT, retryRewards)));
		assertSingleReward(winnerId, RewardKind.ITEM, auctionId);
		assertSingleReward(sellerId, RewardKind.INCOME, auctionId);
	}

	@Test
	void cancelledAuctionAndItemReturnCommitTogetherAndRetryDoesNotDuplicateReward() throws Exception {
		UUID auctionId = UUID.randomUUID();
		UUID sellerId = UUID.randomUUID();
		createAuction(auctionId, sellerId, AuctionRecordStatus.QUEUED);

		RewardRecord firstReturn = RewardRecord.item(sellerId, auctionId,
				new ItemStack(Material.EMERALD), 1, "world");
		assertTrue(await(database.cancelAuctionWithRewards(auctionId,
				List.of(AuctionRecordStatus.QUEUED), "SELLER_MAILBOX", "NOT_REQUIRED",
				COMPLETED_AT, List.of(firstReturn))));

		AuctionRecord cancelled = await(database.getAuctionRecord(auctionId)).orElseThrow();
		assertEquals(AuctionRecordStatus.CANCELLED, cancelled.getStatus());
		assertEquals(COMPLETED_AT, cancelled.getCompletedAtMillis());
		assertEquals("SELLER_MAILBOX", cancelled.getItemDestination());
		assertEquals("NOT_REQUIRED", cancelled.getRefundStatus());
		assertSingleReward(sellerId, RewardKind.ITEM, auctionId);

		RewardRecord retryReturn = RewardRecord.item(sellerId, auctionId,
				new ItemStack(Material.EMERALD), 1, "world");
		assertTrue(await(database.cancelAuctionWithRewards(auctionId,
				List.of(AuctionRecordStatus.QUEUED), "SELLER_MAILBOX", "NOT_REQUIRED",
				COMPLETED_AT, List.of(retryReturn))));
		assertSingleReward(sellerId, RewardKind.ITEM, auctionId);
	}

	@Test
	void invalidSettlementRewardRollsBackEarlierRewardAndLeavesAuctionActive() throws Exception {
		UUID auctionId = UUID.randomUUID();
		UUID sellerId = UUID.randomUUID();
		UUID foreignOwner = UUID.randomUUID();
		createAuction(auctionId, sellerId, AuctionRecordStatus.ACTIVE);

		RewardRecord valid = RewardRecord.money(sellerId, auctionId, RewardKind.INCOME, 9_000L);
		RewardRecord foreign = RewardRecord.money(foreignOwner, UUID.randomUUID(),
				RewardKind.REFUND, 1_000L);
		ExecutionException failure = assertThrows(ExecutionException.class,
				() -> await(database.completeAuctionWithRewards(auctionId, foreignOwner,
						10_000L, 9_000L, 1_000L, "WINNER_MAILBOX", "NOT_REQUIRED",
						COMPLETED_AT, List.of(valid, foreign))));
		assertTrue(failure.getCause().getMessage().contains("does not belong"));

		assertEquals(AuctionRecordStatus.ACTIVE,
				await(database.getAuctionRecord(auctionId)).orElseThrow().getStatus());
		assertTrue(await(database.getRewards(sellerId, List.of(RewardKind.INCOME), true)).isEmpty());
		assertTrue(await(database.getRewards(foreignOwner, List.of(RewardKind.REFUND), true)).isEmpty());
	}

	@Test
	void invalidCancellationRewardRollsBackEarlierReturnAndLeavesAuctionQueued() throws Exception {
		UUID auctionId = UUID.randomUUID();
		UUID sellerId = UUID.randomUUID();
		UUID foreignOwner = UUID.randomUUID();
		createAuction(auctionId, sellerId, AuctionRecordStatus.QUEUED);

		RewardRecord validReturn = RewardRecord.item(sellerId, auctionId,
				new ItemStack(Material.EMERALD), 1, "world");
		RewardRecord foreign = RewardRecord.money(foreignOwner, UUID.randomUUID(),
				RewardKind.REFUND, 1_000L);
		ExecutionException failure = assertThrows(ExecutionException.class,
				() -> await(database.cancelAuctionWithRewards(auctionId,
						List.of(AuctionRecordStatus.QUEUED), "SELLER_MAILBOX", "NOT_REQUIRED",
						COMPLETED_AT, List.of(validReturn, foreign))));
		assertTrue(failure.getCause().getMessage().contains("does not belong"));

		assertEquals(AuctionRecordStatus.QUEUED,
				await(database.getAuctionRecord(auctionId)).orElseThrow().getStatus());
		assertTrue(await(database.getRewards(sellerId, List.of(RewardKind.ITEM), true)).isEmpty());
		assertTrue(await(database.getRewards(foreignOwner, List.of(RewardKind.REFUND), true)).isEmpty());
	}

	private void createAuction(UUID auctionId, UUID sellerId,
	                           AuctionRecordStatus targetStatus) throws Exception {
		AuctionRecord record = new AuctionRecord(auctionId, sellerId,
				new ItemStack(Material.EMERALD), 1, false, "world",
				1_000L, 100L, 0L, 120);
		await(database.createAuctionRecord(record));
		if (targetStatus != AuctionRecordStatus.PREPARING) {
			assertTrue(await(database.transitionAuction(auctionId,
					AuctionRecordStatus.PREPARING, targetStatus)));
		}
	}

	private void assertSingleReward(UUID ownerId, RewardKind kind, UUID auctionId) throws Exception {
		List<RewardRecord> rewards = await(database.getRewards(ownerId, List.of(kind), true));
		assertEquals(1, rewards.size());
		assertEquals(auctionId, rewards.getFirst().getAuctionId());
		assertEquals(kind, rewards.getFirst().getKind());
	}

	private static <T> T await(CompletableFuture<T> future) throws Exception {
		return future.get(5, TimeUnit.SECONDS);
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
