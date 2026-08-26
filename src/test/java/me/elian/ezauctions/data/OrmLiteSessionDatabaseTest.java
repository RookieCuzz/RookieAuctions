package me.elian.ezauctions.data;

import me.elian.ezauctions.Logger;
import me.elian.ezauctions.model.AuctionAttendanceRecord;
import me.elian.ezauctions.model.AuctionBidTransaction;
import me.elian.ezauctions.model.AuctionRuntimeCheckpoint;
import me.elian.ezauctions.model.AuctionRecord;
import me.elian.ezauctions.model.AuctionRecordStatus;
import me.elian.ezauctions.model.AuctionSessionLot;
import me.elian.ezauctions.model.AuctionSessionRecord;
import me.elian.ezauctions.model.BidTransactionState;
import me.elian.ezauctions.session.AttendanceState;
import me.elian.ezauctions.session.LotState;
import me.elian.ezauctions.session.ReservationStatus;
import me.elian.ezauctions.session.SessionState;
import me.elian.ezauctions.session.SubmissionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrmLiteSessionDatabaseTest {
	private static final String SESSION_ID = "2026-08-26/afternoon";
	private static final long START = 2_000_000L;
	private static final long LOCK = 1_400_000L;
	private static final long NOW = 1_000_000L;

	@TempDir
	Path temporaryDirectory;

	private OrmLiteDatabase database;
	private String connectionString;

	@BeforeEach
	void setUp() throws Exception {
		connectionString = "jdbc:sqlite:" + temporaryDirectory.resolve("sessions.sqlite");
		database = new OrmLiteDatabase(new SilentLogger(), connectionString);
	}

	@AfterEach
	void tearDown() {
		if (database != null) {
			database.shutdown();
		}
	}

	@Test
	void sessionCreationQueriesAndCasAreIdempotent() throws Exception {
		AuctionSessionRecord proposed = session(SESSION_ID, 16, 2);
		AuctionSessionRecord first = await(database.createSessionIfAbsent(proposed));
		AuctionSessionRecord repeated = await(database.createSessionIfAbsent(
				new AuctionSessionRecord(SESSION_ID, START + 86_400_000L, LOCK + 86_400_000L,
						8, 1, NOW + 100)));

		assertEquals(SESSION_ID, first.getId());
		assertEquals(first.getScheduledStartMillis(), repeated.getScheduledStartMillis());
		assertEquals(START, repeated.getScheduledStartMillis());
		assertEquals(LOCK, repeated.getLockAtMillis());
		assertEquals(16, repeated.getCapacity());
		assertEquals(2, repeated.getSellerLimit());
		assertEquals(List.of(SESSION_ID), await(database.getSessionsStartingAtOrAfter(START, 2))
				.stream().map(AuctionSessionRecord::getId).toList());
		assertEquals(1, await(database.getSessionsStartingBetween(START, START + 1)).size());
		assertEquals(1, await(database.getSessionsByState(List.of(SessionState.OPEN))).size());

		assertTrue(await(database.transitionSession(SESSION_ID, SessionState.OPEN,
				SessionState.LOCKED, NOW + 1)));
		assertFalse(await(database.transitionSession(SESSION_ID, SessionState.OPEN,
				SessionState.LOCKED, NOW + 2)));
		AuctionSessionRecord locked = await(database.getSession(SESSION_ID)).orElseThrow();
		assertEquals(SessionState.LOCKED, locked.getState());
		assertEquals(1, locked.getRevision());
	}

	@Test
	void sessionPersistenceUsesIndependentTablesWithoutChangingLegacyAuctionSchema() throws Exception {
		Set<String> tableNames = new HashSet<>();
		try (var connection = DriverManager.getConnection(connectionString);
		     var statement = connection.createStatement();
		     var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
			while (rows.next()) {
				tableNames.add(rows.getString(1));
			}
		}
		assertTrue(tableNames.containsAll(List.of(
				"ezAuctions_Session",
				"ezAuctions_SessionLot",
				"ezAuctions_Attendance",
				"ezAuctions_RuntimeCheckpoint",
				"ezAuctions_BidTransaction",
				"ezAuctions_SubmissionTransaction")));

		Set<String> legacyColumns = new HashSet<>();
		try (var connection = DriverManager.getConnection(connectionString);
		     var statement = connection.createStatement();
		     var rows = statement.executeQuery("PRAGMA table_info('ezAuctions_AuctionRecord')")) {
			while (rows.next()) {
				legacyColumns.add(rows.getString("name"));
			}
		}
		assertFalse(legacyColumns.contains("sessionId"));
		assertFalse(legacyColumns.contains("lotState"));
	}

	@Test
	void reservationEnforcesCapacitySellerLimitCancellationAndFifoAtomically() throws Exception {
		await(database.createSessionIfAbsent(session(SESSION_ID, 16, 2)));
		List<UUID> auctionIds = new ArrayList<>();
		List<UUID> sellerIds = new ArrayList<>();
		List<CompletableFuture<SubmissionResult>> reservations = new ArrayList<>();
		for (int index = 0; index < 17; index++) {
			UUID auctionId = UUID.randomUUID();
			UUID sellerId = UUID.randomUUID();
			auctionIds.add(auctionId);
			sellerIds.add(sellerId);
			reservations.add(database.reserveSessionLot(SESSION_ID, auctionId, sellerId,
					NOW + index));
		}
		CompletableFuture.allOf(reservations.toArray(CompletableFuture[]::new)).get(5,
				TimeUnit.SECONDS);
		List<SubmissionResult> results = reservations.stream().map(CompletableFuture::join).toList();

		assertEquals(16, results.stream().filter(SubmissionResult::accepted).count());
		assertEquals(1, results.stream()
				.filter(result -> result.status() == ReservationStatus.FULL).count());
		SubmissionResult retry = await(database.reserveSessionLot(SESSION_ID, auctionIds.getFirst(),
				sellerIds.getFirst(), NOW + 100));
		assertTrue(retry.accepted());
		assertEquals(results.getFirst().lotId(), retry.lotId());
		assertEquals(16, retry.occupiedSlots());

		assertTrue(await(database.cancelSessionLot(SESSION_ID, auctionIds.getFirst(),
				sellerIds.getFirst(), NOW + 200)));
		SubmissionResult replacement = await(database.reserveSessionLot(SESSION_ID,
				UUID.randomUUID(), UUID.randomUUID(), NOW + 201));
		assertTrue(replacement.accepted());
		assertEquals(16, replacement.occupiedSlots());

		List<AuctionSessionLot> lots = await(database.getSessionLots(SESSION_ID));
		assertEquals(17, lots.size());
		for (int index = 0; index < lots.size(); index++) {
			assertEquals(index + 1, lots.get(index).getSequenceNumber());
		}
		assertEquals(LotState.CANCELLED, lots.getFirst().getState());

		String sellerLimitSession = "2026-08-26/evening";
		await(database.createSessionIfAbsent(session(sellerLimitSession, 16, 2)));
		UUID seller = UUID.randomUUID();
		assertTrue(await(database.reserveSessionLot(sellerLimitSession, UUID.randomUUID(), seller,
				NOW)).accepted());
		assertTrue(await(database.reserveSessionLot(sellerLimitSession, UUID.randomUUID(), seller,
				NOW + 1)).accepted());
		assertEquals(ReservationStatus.SELLER_LIMIT,
				await(database.reserveSessionLot(sellerLimitSession, UUID.randomUUID(), seller,
						NOW + 2)).status());
		assertEquals(ReservationStatus.SESSION_CLOSED,
				await(database.reserveSessionLot(sellerLimitSession, UUID.randomUUID(), UUID.randomUUID(),
						LOCK)).status());
	}

	@Test
	void attendanceCheckpointAndBidJournalSurviveRestartAndRejectStaleWrites() throws Exception {
		await(database.createSessionIfAbsent(session(SESSION_ID, 16, 2)));
		UUID playerId = UUID.randomUUID();
		AuctionAttendanceRecord registration = await(database.registerAttendance(SESSION_ID, playerId,
				NOW));
		assertEquals(registration.getId(), await(database.registerAttendance(SESSION_ID, playerId,
				NOW + 1)).getId());
		assertTrue(await(database.beginAttendanceEntry(SESSION_ID, playerId, "world", 1.5, 64, -2,
				90, 5, NOW + 2)));
		assertTrue(await(database.beginAttendanceEntry(SESSION_ID, playerId, "world", 1.5, 64, -2,
				90, 5, NOW + 2)));
		assertTrue(await(database.transitionAttendance(SESSION_ID, playerId,
				AttendanceState.ENTERING, AttendanceState.ACTIVE, NOW + 3)));
		assertEquals(1, await(database.getAttendance(SESSION_ID,
				List.of(AttendanceState.ACTIVE))).size());

		UUID lotId = UUID.randomUUID();
		AuctionRuntimeCheckpoint checkpoint = new AuctionRuntimeCheckpoint(SESSION_ID, lotId, 1,
				119, 4, 1, false, NOW + 20);
		assertTrue(await(database.saveRuntimeCheckpoint(checkpoint)));
		assertFalse(await(database.saveRuntimeCheckpoint(new AuctionRuntimeCheckpoint(SESSION_ID, lotId,
				1, 120, 3, 0, false, NOW + 19))));

		UUID transactionId = UUID.randomUUID();
		UUID auctionId = UUID.randomUUID();
		AuctionBidTransaction transaction = new AuctionBidTransaction(transactionId, SESSION_ID, lotId,
				auctionId, playerId, 12_345, NOW + 30);
		assertEquals(transactionId, await(database.createBidTransaction(transaction)).getId());
		assertEquals(transactionId, await(database.createBidTransaction(new AuctionBidTransaction(
				transactionId, SESSION_ID, lotId, auctionId, playerId, 12_345, NOW + 31))).getId());
		assertTrue(await(database.transitionBidTransaction(transactionId,
				BidTransactionState.PREPARED, BidTransactionState.WITHDRAWING, "", NOW + 32)));
		assertFalse(await(database.transitionBidTransaction(transactionId,
				BidTransactionState.PREPARED, BidTransactionState.WITHDRAWN, "", NOW + 33)));

		database.shutdown();
		database = new OrmLiteDatabase(new SilentLogger(), connectionString);
		AuctionAttendanceRecord restoredAttendance = await(database.getAttendance(SESSION_ID, playerId))
				.orElseThrow();
		assertEquals(AttendanceState.ACTIVE, restoredAttendance.getState());
		assertEquals("world", restoredAttendance.getReturnWorld());
		assertEquals(119, await(database.getRuntimeCheckpoint(SESSION_ID)).orElseThrow()
				.getRemainingSeconds());
		assertEquals(BidTransactionState.WITHDRAWING,
				await(database.getBidTransaction(transactionId)).orElseThrow().getState());
		assertEquals(1, await(database.getBidTransactions(
				List.of(BidTransactionState.WITHDRAWING))).size());
		assertNotNull(await(database.getSession(SESSION_ID)).orElseThrow());
	}

	@Test
	void moveSessionLotDefersSourceAndReservesTargetInOneTransaction() throws Exception {
		String targetSession = "2026-08-26/evening";
		await(database.createSessionIfAbsent(session(SESSION_ID, 16, 2)));
		await(database.createSessionIfAbsent(session(targetSession, 16, 2)));
		UUID auctionId = UUID.randomUUID();
		UUID sellerId = UUID.randomUUID();
		SubmissionResult source = await(database.reserveSessionLot(SESSION_ID, auctionId, sellerId,
				NOW));
		assertTrue(await(database.transitionSessionLot(source.lotId(), LotState.RESERVED,
				LotState.QUEUED, NOW + 1)));

		SubmissionResult moved = await(database.moveSessionLot(SESSION_ID, targetSession, auctionId,
				sellerId, NOW + 2));
		assertTrue(moved.accepted());
		assertEquals(LotState.DEFERRED, await(database.getSessionLot(source.lotId())).orElseThrow()
				.getState());
		assertEquals(LotState.QUEUED, await(database.getSessionLot(moved.lotId())).orElseThrow()
				.getState());
		assertEquals(2, await(database.getSessionLotsByAuctionId(auctionId)).size());
		AuctionSessionLot current = await(database.getSessionLotByAuction(auctionId)).orElseThrow();
		assertEquals(moved.lotId(), current.getId());
		assertEquals(targetSession, current.getSessionId());
		SubmissionResult retry = await(database.moveSessionLot(SESSION_ID, targetSession, auctionId,
				sellerId, NOW + 3));
		assertEquals(moved.lotId(), retry.lotId());
		assertEquals(1, retry.occupiedSlots());
	}

	@Test
	void bidTransactionIdCannotBeReusedForAnotherWithdrawal() throws Exception {
		UUID transactionId = UUID.randomUUID();
		UUID lotId = UUID.randomUUID();
		UUID auctionId = UUID.randomUUID();
		UUID bidderId = UUID.randomUUID();
		await(database.createBidTransaction(new AuctionBidTransaction(transactionId, SESSION_ID, lotId,
				auctionId, bidderId, 100, NOW)));

		ExecutionException exception = assertThrows(ExecutionException.class,
				() -> database.createBidTransaction(new AuctionBidTransaction(transactionId, SESSION_ID,
						lotId, auctionId, bidderId, 101, NOW + 1)).get(5, TimeUnit.SECONDS));
		assertTrue(exception.getCause().getMessage().contains("different payload"));
	}

	@Test
	void terminalAuctionPayloadUsesConditionalStateTransitions() throws Exception {
		UUID auctionId = UUID.randomUUID();
		UUID sellerId = UUID.randomUUID();
		UUID winnerId = UUID.randomUUID();
		insertAuctionRecord(auctionId, sellerId, AuctionRecordStatus.QUEUED);
		assertTrue(await(database.transitionAuction(auctionId, AuctionRecordStatus.QUEUED,
				AuctionRecordStatus.ACTIVE)));

		assertTrue(await(database.completeAuction(auctionId, winnerId, 500, 475, 25,
				"WINNER_MAILBOX", "MAILBOX", NOW + 50)));
		assertFalse(await(database.completeAuction(auctionId, winnerId, 500, 475, 25,
				"WINNER_MAILBOX", "MAILBOX", NOW + 51)));
		assertFalse(await(database.cancelAuction(auctionId, AuctionRecordStatus.ACTIVE,
				"SELLER_MAILBOX", "MAILBOX", NOW + 52)));
		AuctionRecord settled = await(database.getAuctionRecord(auctionId)).orElseThrow();
		assertEquals(AuctionRecordStatus.COMPLETED, settled.getStatus());
		assertEquals(winnerId, settled.getWinnerId());
		assertEquals(500, settled.getFinalPriceMinor());
		assertEquals(475, settled.getPayoutMinor());
		assertEquals(25, settled.getTaxMinor());
		assertEquals("WINNER_MAILBOX", settled.getItemDestination());

		UUID cancelledId = UUID.randomUUID();
		insertAuctionRecord(cancelledId, sellerId, AuctionRecordStatus.QUEUED);
		assertTrue(await(database.cancelAuction(cancelledId, AuctionRecordStatus.QUEUED,
				"SELLER_MAILBOX", "NONE", NOW + 60)));
		assertFalse(await(database.cancelAuction(cancelledId, AuctionRecordStatus.QUEUED,
				"SELLER_MAILBOX", "NONE", NOW + 61)));
		assertEquals(AuctionRecordStatus.CANCELLED,
				await(database.getAuctionRecord(cancelledId)).orElseThrow().getStatus());
	}

	private void insertAuctionRecord(UUID auctionId, UUID sellerId,
	                                 AuctionRecordStatus status) throws Exception {
		try (var connection = DriverManager.getConnection(connectionString);
		     var statement = connection.prepareStatement("""
				INSERT INTO ezAuctions_AuctionRecord
				(id, auctioneerId, serializedItemBytes, amount, sealed, world,
				 startingPriceMinor, incrementMinor, autoBuyMinor, durationSeconds,
				 status, createdAtMillis, itemDestination, refundStatus)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")) {
			statement.setString(1, auctionId.toString());
			statement.setString(2, sellerId.toString());
			statement.setBytes(3, new byte[]{1});
			statement.setInt(4, 1);
			statement.setBoolean(5, false);
			statement.setString(6, "world");
			statement.setLong(7, 100);
			statement.setLong(8, 10);
			statement.setLong(9, 500);
			statement.setInt(10, 120);
			statement.setString(11, status.name());
			statement.setLong(12, NOW);
			statement.setString(13, "ESCROW");
			statement.setString(14, "NONE");
			assertEquals(1, statement.executeUpdate());
		}
	}

	private static AuctionSessionRecord session(String id, int capacity, int sellerLimit) {
		return new AuctionSessionRecord(id, START, LOCK, capacity, sellerLimit, NOW - 1);
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
