package me.elian.ezauctions.data;

import com.google.inject.ImplementedBy;
import me.elian.ezauctions.model.AuctionBidRecord;
import me.elian.ezauctions.model.AuctionBidTransaction;
import me.elian.ezauctions.model.AuctionAttendanceRecord;
import me.elian.ezauctions.model.AuctionPlayer;
import me.elian.ezauctions.model.AuctionRecord;
import me.elian.ezauctions.model.AuctionRecordStatus;
import me.elian.ezauctions.model.AuctionRuntimeCheckpoint;
import me.elian.ezauctions.model.AuctionSessionLot;
import me.elian.ezauctions.model.AuctionSessionRecord;
import me.elian.ezauctions.model.AuctionSubmissionTransaction;
import me.elian.ezauctions.model.BidTransactionState;
import me.elian.ezauctions.model.RewardKind;
import me.elian.ezauctions.model.RewardRecord;
import me.elian.ezauctions.model.SubmissionTransactionState;
import me.elian.ezauctions.session.AttendanceState;
import me.elian.ezauctions.session.LotState;
import me.elian.ezauctions.session.SessionState;
import me.elian.ezauctions.session.SubmissionResult;
import me.elian.ezauctions.session.WithdrawalResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@ImplementedBy(OrmLiteDatabase.class)
public interface Database {
	@NotNull CompletableFuture<AuctionPlayer> getAuctionPlayer(@NotNull UUID id);

	void saveAuctionPlayer(@NotNull AuctionPlayer ap);

	@NotNull CompletableFuture<Void> createAuctionRecord(@NotNull AuctionRecord record);

	@NotNull CompletableFuture<Boolean> transitionAuction(@NotNull UUID auctionId,
	                                                      @NotNull AuctionRecordStatus expected,
	                                                      @NotNull AuctionRecordStatus next);

	/** Atomically writes the complete settlement payload only while the auction is ACTIVE. */
	@NotNull CompletableFuture<Boolean> completeAuction(@NotNull UUID auctionId,
	                                                    @Nullable UUID winnerId,
	                                                    long finalPriceMinor,
	                                                    long payoutMinor,
	                                                    long taxMinor,
	                                                    @NotNull String itemDestination,
	                                                    @NotNull String refundStatus,
	                                                    long completedAtMillis);

	/** Atomically creates deterministic rewards and writes the ACTIVE -> COMPLETED payload. */
	@NotNull CompletableFuture<Boolean> completeAuctionWithRewards(
			@NotNull UUID auctionId, @Nullable UUID winnerId,
			long finalPriceMinor, long payoutMinor, long taxMinor,
			@NotNull String itemDestination, @NotNull String refundStatus,
			long completedAtMillis, @NotNull Collection<RewardRecord> rewards);

	/** Atomically records cancellation from one expected lifecycle state. */
	@NotNull CompletableFuture<Boolean> cancelAuction(@NotNull UUID auctionId,
	                                                  @NotNull AuctionRecordStatus expected,
	                                                  @NotNull String itemDestination,
	                                                  @NotNull String refundStatus,
	                                                  long completedAtMillis);

	/** Atomically creates deterministic rewards and writes an allowed state -> CANCELLED payload. */
	@NotNull CompletableFuture<Boolean> cancelAuctionWithRewards(
			@NotNull UUID auctionId, @NotNull Collection<AuctionRecordStatus> expected,
			@NotNull String itemDestination, @NotNull String refundStatus,
			long completedAtMillis, @NotNull Collection<RewardRecord> rewards);

	@NotNull CompletableFuture<Boolean> cancelAuction(@NotNull UUID auctionId,
	                                                  @NotNull Collection<AuctionRecordStatus> expected,
	                                                  @NotNull String itemDestination,
	                                                  @NotNull String refundStatus,
	                                                  long completedAtMillis);

	@NotNull CompletableFuture<Void> saveAuctionRecord(@NotNull AuctionRecord record);

	@NotNull CompletableFuture<Optional<AuctionRecord>> getAuctionRecord(@NotNull UUID auctionId);

	@NotNull CompletableFuture<List<AuctionRecord>> getAuctionRecords(@NotNull UUID ownerId);

	@NotNull CompletableFuture<List<AuctionRecord>> getAuctionsByStatus(
			@NotNull Collection<AuctionRecordStatus> statuses);

	@NotNull CompletableFuture<Void> createReward(@NotNull RewardRecord reward);

	@NotNull CompletableFuture<List<RewardRecord>> getRewards(@NotNull UUID ownerId,
	                                                          @NotNull Collection<RewardKind> kinds,
	                                                          boolean includeClaimed);

	@NotNull CompletableFuture<Optional<RewardRecord>> tryBeginRewardClaim(@NotNull UUID rewardId,
	                                                                       @NotNull UUID ownerId);

	@NotNull CompletableFuture<Boolean> finishRewardClaim(@NotNull UUID rewardId, @NotNull UUID ownerId);

	@NotNull CompletableFuture<Boolean> releaseRewardClaim(@NotNull UUID rewardId, @NotNull UUID ownerId);

	@NotNull CompletableFuture<Void> createBidRecord(@NotNull AuctionBidRecord bidRecord);

	@NotNull CompletableFuture<Optional<AuctionBidRecord>> getBidRecord(@NotNull UUID bidRecordId);

	@NotNull CompletableFuture<List<AuctionBidRecord>> getBidRecords(@NotNull UUID auctionId);

	@NotNull CompletableFuture<AuctionSessionRecord> createSessionIfAbsent(
			@NotNull AuctionSessionRecord session);

	@NotNull CompletableFuture<Optional<AuctionSessionRecord>> getSession(@NotNull String sessionId);

	@NotNull CompletableFuture<List<AuctionSessionRecord>> getSessionsStartingAtOrAfter(
			long startInclusiveMillis, int limit);

	@NotNull CompletableFuture<List<AuctionSessionRecord>> getSessionsStartingBetween(
			long startInclusiveMillis, long startExclusiveMillis);

	@NotNull CompletableFuture<List<AuctionSessionRecord>> getSessionsByState(
			@NotNull Collection<SessionState> states);

	@NotNull CompletableFuture<Boolean> transitionSession(@NotNull String sessionId,
	                                                      @NotNull SessionState expected,
	                                                      @NotNull SessionState next,
	                                                      long changedAtMillis);

	@NotNull CompletableFuture<SubmissionResult> reserveSessionLot(@NotNull String sessionId,
	                                                               @NotNull UUID auctionId,
	                                                               @NotNull UUID sellerId,
	                                                               long createdAtMillis);

	@NotNull CompletableFuture<Boolean> cancelSessionLot(@NotNull String sessionId,
	                                                     @NotNull UUID auctionId,
	                                                     @NotNull UUID sellerId,
	                                                     long cancelledAtMillis);

	@NotNull CompletableFuture<Optional<AuctionSessionLot>> getSessionLot(@NotNull UUID lotId);

	@NotNull CompletableFuture<Optional<AuctionSessionLot>> getSessionLot(
			@NotNull String sessionId, @NotNull UUID auctionId);

	@NotNull CompletableFuture<List<AuctionSessionLot>> getSessionLots(@NotNull String sessionId);

	@NotNull CompletableFuture<List<AuctionSessionLot>> getSessionLotsByAuctionId(
			@NotNull UUID auctionId);

	/**
	 * Returns the current (non-cancelled and non-deferred) session lot for an auction.
	 * Historical rows left behind by a deferral are deliberately ignored.
	 */
	@NotNull CompletableFuture<Optional<AuctionSessionLot>> getSessionLotByAuction(
			@NotNull UUID auctionId);

	@NotNull CompletableFuture<WithdrawalResult> withdrawSessionLot(@NotNull String sessionId,
	                                                               @NotNull UUID auctionId,
	                                                               @NotNull UUID sellerId,
	                                                               long withdrawnAtMillis);

	@NotNull CompletableFuture<SubmissionResult> moveSessionLot(@NotNull String sourceSessionId,
	                                                            @NotNull String targetSessionId,
	                                                            @NotNull UUID auctionId,
	                                                            @NotNull UUID sellerId,
	                                                            long movedAtMillis);

	@NotNull CompletableFuture<Boolean> transitionSessionLot(@NotNull UUID lotId,
	                                                         @NotNull LotState expected,
	                                                         @NotNull LotState next,
	                                                         long changedAtMillis);

	@NotNull CompletableFuture<AuctionAttendanceRecord> registerAttendance(
			@NotNull String sessionId, @NotNull UUID playerId, long createdAtMillis);

	@NotNull CompletableFuture<Void> saveAttendance(@NotNull AuctionAttendanceRecord attendance);

	@NotNull CompletableFuture<Optional<AuctionAttendanceRecord>> getAttendance(
			@NotNull String sessionId, @NotNull UUID playerId);

	@NotNull CompletableFuture<List<AuctionAttendanceRecord>> getAttendance(
			@NotNull String sessionId, @NotNull Collection<AttendanceState> states);

	@NotNull CompletableFuture<List<AuctionAttendanceRecord>> getAttendances(
			@NotNull UUID playerId, @NotNull Collection<AttendanceState> states);

	@NotNull CompletableFuture<Boolean> beginAttendanceEntry(@NotNull String sessionId,
	                                                         @NotNull UUID playerId,
	                                                         @NotNull String returnWorld,
	                                                         double returnX, double returnY, double returnZ,
	                                                         float returnYaw, float returnPitch,
	                                                         long changedAtMillis);

	@NotNull CompletableFuture<Boolean> transitionAttendance(@NotNull String sessionId,
	                                                         @NotNull UUID playerId,
	                                                         @NotNull AttendanceState expected,
	                                                         @NotNull AttendanceState next,
	                                                         long changedAtMillis);

	@NotNull CompletableFuture<Boolean> removeRegisteredAttendance(@NotNull String sessionId,
	                                                               @NotNull UUID playerId);

	@NotNull CompletableFuture<Boolean> saveRuntimeCheckpoint(
			@NotNull AuctionRuntimeCheckpoint checkpoint);

	@NotNull CompletableFuture<Optional<AuctionRuntimeCheckpoint>> getRuntimeCheckpoint(
			@NotNull String sessionId);

	@NotNull CompletableFuture<Boolean> deleteRuntimeCheckpoint(@NotNull String sessionId);

	@NotNull CompletableFuture<AuctionBidTransaction> createBidTransaction(
			@NotNull AuctionBidTransaction transaction);

	@NotNull CompletableFuture<Optional<AuctionBidTransaction>> getBidTransaction(
			@NotNull UUID transactionId);

	@NotNull CompletableFuture<List<AuctionBidTransaction>> getBidTransactions(
			@NotNull Collection<BidTransactionState> states);

	@NotNull CompletableFuture<List<AuctionBidTransaction>> getBidTransactions(
			@NotNull String sessionId, @NotNull Collection<BidTransactionState> states);

	@NotNull CompletableFuture<Boolean> transitionBidTransaction(@NotNull UUID transactionId,
	                                                             @NotNull BidTransactionState expected,
	                                                             @NotNull BidTransactionState next,
	                                                             @NotNull String failureReason,
	                                                             long changedAtMillis);

	@NotNull CompletableFuture<Boolean> compensateBidTransaction(@NotNull UUID transactionId,
	                                                            @NotNull String reason,
	                                                            long changedAtMillis);

	@NotNull CompletableFuture<AuctionSubmissionTransaction> createSubmissionTransaction(
			@NotNull AuctionSubmissionTransaction transaction);

	@NotNull CompletableFuture<Optional<AuctionSubmissionTransaction>> getSubmissionTransaction(
			@NotNull UUID transactionId);

	@NotNull CompletableFuture<List<AuctionSubmissionTransaction>> getSubmissionTransactions(
			@NotNull Collection<SubmissionTransactionState> states);

	@NotNull CompletableFuture<Boolean> transitionSubmissionTransaction(
			@NotNull UUID transactionId, @NotNull SubmissionTransactionState expected,
			@NotNull SubmissionTransactionState next, @NotNull String failureReason,
			long changedAtMillis);

	/**
	 * Atomically publishes an escrowed submission. For scheduled submissions the reservation may
	 * already be {@code LOCKED} when the cutoff task wins the race; that still represents the same
	 * legally acquired slot and must remain locked while the auction and journal are committed.
	 */
	@NotNull CompletableFuture<Boolean> commitSubmissionTransaction(
			@NotNull UUID transactionId, long changedAtMillis);

	/** Atomically cancels the auction/slot, creates deterministic refunds and closes the journal. */
	@NotNull CompletableFuture<Boolean> compensateSubmissionTransaction(
			@NotNull UUID transactionId, @NotNull String reason, long changedAtMillis);

	void reconnect() throws SQLException;

	void shutdown();
}
