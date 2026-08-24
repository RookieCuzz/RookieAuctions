package me.elian.ezauctions.controller;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.elian.ezauctions.Logger;
import me.elian.ezauctions.data.Database;
import me.elian.ezauctions.helper.ItemHelper;
import me.elian.ezauctions.model.Money;
import me.elian.ezauctions.model.RewardKind;
import me.elian.ezauctions.model.RewardRecord;
import me.elian.ezauctions.scheduler.TaskScheduler;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Persistent mailbox with an atomic PENDING -> CLAIMING -> DONE claim state.
 */
@Singleton
public class RewardController {
	private final Database database;
	private final Economy economy;
	private final TaskScheduler scheduler;
	private final ConfigController config;
	private final Logger logger;

	@Inject
	public RewardController(Database database, Economy economy, TaskScheduler scheduler,
	                        ConfigController config, Logger logger) {
		this.database = database;
		this.economy = economy;
		this.scheduler = scheduler;
		this.config = config;
		this.logger = logger;
	}

	public @NotNull CompletableFuture<Void> createItemReward(@NotNull UUID ownerId, @Nullable UUID auctionId,
	                                                        @NotNull ItemStack item, int amount,
	                                                        @NotNull String world) {
		return database.createReward(RewardRecord.item(ownerId, auctionId, item, amount, world));
	}

	public @NotNull CompletableFuture<Void> createMoneyReward(@NotNull UUID ownerId, @Nullable UUID auctionId,
	                                                         @NotNull RewardKind kind, long amountMinor) {
		if (amountMinor <= 0) {
			return CompletableFuture.completedFuture(null);
		}
		return database.createReward(RewardRecord.money(ownerId, auctionId, kind, amountMinor));
	}

	public @NotNull CompletableFuture<List<RewardRecord>> getRewards(@NotNull UUID ownerId,
	                                                                @NotNull Collection<RewardKind> kinds,
	                                                                boolean includeClaimed) {
		return database.getRewards(ownerId, kinds, includeClaimed);
	}

	public @NotNull CompletableFuture<ClaimResult> claim(@NotNull Player player, @NotNull UUID rewardId) {
		CompletableFuture<ClaimResult> result = new CompletableFuture<>();
		database.tryBeginRewardClaim(rewardId, player.getUniqueId()).whenComplete((optional, error) -> {
			if (error != null) {
				result.complete(ClaimResult.DATABASE_ERROR);
				return;
			}
			if (optional.isEmpty()) {
				result.complete(ClaimResult.NOT_AVAILABLE);
				return;
			}

			RewardRecord reward = optional.get();
			scheduler.runPlayerRegionTask(() -> claimOnPlayerRegion(player, reward, result), player);
		});
		return result;
	}

	private void claimOnPlayerRegion(@NotNull Player player, @NotNull RewardRecord reward,
	                                 @NotNull CompletableFuture<ClaimResult> result) {
		if (!player.isOnline()) {
			release(reward, result, ClaimResult.NOT_AVAILABLE);
			return;
		}

		if (reward.getKind() == RewardKind.ITEM) {
			claimItem(player, reward, result);
		} else {
			claimMoney(player, reward, result);
		}
	}

	private void claimItem(@NotNull Player player, @NotNull RewardRecord reward,
	                       @NotNull CompletableFuture<ClaimResult> result) {
		String playerWorld = player.getWorld().getName();
		if (config.getConfig().getStringList("auctions.blocked-worlds")
				.stream().anyMatch(world -> world.equalsIgnoreCase(playerWorld))) {
			release(reward, result, ClaimResult.WRONG_WORLD);
			return;
		}
		if (config.getConfig().getBoolean("auctions.per-world-auctions")
				&& !playerWorld.equalsIgnoreCase(reward.getWorld())) {
			release(reward, result, ClaimResult.WRONG_WORLD);
			return;
		}

		ItemStack item;
		try {
			item = reward.getItem();
		} catch (IOException exception) {
			logger.severe("Could not deserialize mailbox item " + reward.getId(), exception);
			// Leave the record CLAIMING. It cannot be safely retried until an administrator repairs it.
			result.complete(ClaimResult.CORRUPT_ITEM);
			return;
		}

		if (item == null || !ItemHelper.addItemToPlayerInventoryNoDrop(player, item, reward.getAmount())) {
			release(reward, result, ClaimResult.NO_SPACE);
			return;
		}

		finish(reward, result);
	}

	private void claimMoney(@NotNull Player player, @NotNull RewardRecord reward,
	                        @NotNull CompletableFuture<ClaimResult> result) {
		EconomyResponse response = economy.depositPlayer(player, Money.toMajor(reward.getMoneyMinor()));
		if (response == null || !response.transactionSuccess()) {
			release(reward, result, ClaimResult.ECONOMY_FAILED);
			return;
		}
		finish(reward, result);
	}

	private void finish(@NotNull RewardRecord reward, @NotNull CompletableFuture<ClaimResult> result) {
		database.finishRewardClaim(reward.getId(), reward.getOwnerId()).whenComplete((updated, error) -> {
			if (error != null || !Boolean.TRUE.equals(updated)) {
				// Never release after delivery: CLAIMING is intentionally sticky to prevent duplicate delivery.
				result.complete(ClaimResult.DATABASE_ERROR);
			} else {
				result.complete(ClaimResult.SUCCESS);
			}
		});
	}

	private void release(@NotNull RewardRecord reward, @NotNull CompletableFuture<ClaimResult> result,
	                     @NotNull ClaimResult releasedResult) {
		database.releaseRewardClaim(reward.getId(), reward.getOwnerId()).whenComplete((ignored, error) ->
				result.complete(error == null ? releasedResult : ClaimResult.DATABASE_ERROR));
	}

	public enum ClaimResult {
		SUCCESS,
		NOT_AVAILABLE,
		NO_SPACE,
		WRONG_WORLD,
		ECONOMY_FAILED,
		CORRUPT_ITEM,
		DATABASE_ERROR
	}
}
