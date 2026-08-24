package me.elian.ezauctions.data;

import com.google.inject.ImplementedBy;
import me.elian.ezauctions.model.AuctionBidRecord;
import me.elian.ezauctions.model.AuctionPlayer;
import me.elian.ezauctions.model.AuctionRecord;
import me.elian.ezauctions.model.AuctionRecordStatus;
import me.elian.ezauctions.model.RewardKind;
import me.elian.ezauctions.model.RewardRecord;
import org.jetbrains.annotations.NotNull;

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

	@NotNull CompletableFuture<List<AuctionBidRecord>> getBidRecords(@NotNull UUID auctionId);

	void reconnect() throws SQLException;

	void shutdown();
}
