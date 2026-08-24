package me.elian.ezauctions.model;

import com.google.inject.Inject;
import me.elian.ezauctions.controller.AuctionPlayerController;
import me.elian.ezauctions.controller.ConfigController;
import me.elian.ezauctions.controller.MessageController;
import me.elian.ezauctions.controller.RewardController;
import me.elian.ezauctions.controller.ScoreboardController;
import me.elian.ezauctions.data.Database;
import me.elian.ezauctions.event.AuctionBidEvent;
import me.elian.ezauctions.event.AuctionEndEvent;
import me.elian.ezauctions.event.AuctionStartEvent;
import me.elian.ezauctions.scheduler.CancellableTask;
import me.elian.ezauctions.scheduler.TaskScheduler;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class Auction implements Runnable {
	private final Plugin plugin;
	private final TaskScheduler scheduler;
	private final Economy economy;
	private final Permission permission;
	private final AuctionPlayerController playerController;
	private final ConfigController config;
	private final MessageController messages;
	private final ScoreboardController scoreboard;
	private final Database database;
	private final RewardController rewards;
	private AuctionData auctionData;
	private BidList bidList;
	private Runnable completedRunnable;
	private CancellableTask repeatingTask;

	private boolean started;
	private boolean running;
	private int autoCancelTime;
	private int remainingSeconds;
	private int antiSnipeRunTimes;
	private long revision;
	private UUID pendingBidToken;
	private Bid pendingBid;
	private long pendingWithdrawMinor;

	@Inject
	public Auction(Plugin plugin, TaskScheduler scheduler, Economy economy, Permission permission,
	               AuctionPlayerController playerController, ConfigController config,
	               MessageController messages, ScoreboardController scoreboard, Database database,
	               RewardController rewards) {
		this.plugin = plugin;
		this.scheduler = scheduler;
		this.economy = economy;
		this.permission = permission;
		this.playerController = playerController;
		this.config = config;
		this.messages = messages;
		this.scoreboard = scoreboard;
		this.database = database;
		this.rewards = rewards;
	}

	public synchronized AuctionData getAuctionData() {
		return auctionData;
	}

	public synchronized BidList getBidList() {
		return bidList;
	}

	public synchronized int getRemainingSeconds() {
		return remainingSeconds;
	}

	public synchronized boolean isCompleted() {
		return !running;
	}

	public synchronized long getRevision() {
		return revision;
	}

	public synchronized boolean isRunning() {
		return running;
	}

	public void startAuction(@NotNull AuctionData auctionData, @NotNull Runnable completedRunnable) {
		if (started) {
			throw new IllegalStateException("Can not start an auction that has already been started!");
		}

		started = true;
		running = true;
		revision = 1;

		this.auctionData = auctionData;
		this.completedRunnable = completedRunnable;

		AuctionStartEvent event = new AuctionStartEvent(this);
		plugin.getServer().getPluginManager().callEvent(event);

		if (event.isCancelled()) {
			running = false;
			rewards.createItemReward(auctionData.getAuctioneer().getUniqueId(), auctionData.getId(),
					auctionData.getItem(), auctionData.getAmount(), auctionData.getWorld());
			cancelRecord("SELLER_MAILBOX", "NONE");
			completedRunnable.run();
			return;
		}

		autoCancelTime = config.getConfig().getInt("auctions.auto-cancel-no-bids", 0);

		loadAuctionData(auctionData);
		database.transitionAuction(auctionData.getId(), AuctionRecordStatus.QUEUED, AuctionRecordStatus.ACTIVE);
		repeatingTask = scheduler.runSyncRepeatingTask(plugin, this, 1, 1);
	}

	public void cancelAuction(boolean returnMoney) {
		synchronized (this) {
			if (!running)
				return;

			cancelRepeatingTask();
			rewards.createItemReward(auctionData.getAuctioneer().getUniqueId(), auctionData.getId(),
					auctionData.getItem(), auctionData.getAmount(), auctionData.getWorld());

			if (returnMoney) {
				returnStartPriceToAuctioneer();
			}

			returnBidderMoney(true);
			cancelRecord("SELLER_MAILBOX", "MAILBOX");
		}
	}

	public void cancelAuctionShutdown() {
		synchronized (this) {
			if (!running)
				return;

			cancelRepeatingTask();
			rewards.createItemReward(auctionData.getAuctioneer().getUniqueId(), auctionData.getId(),
					auctionData.getItem(), auctionData.getAmount(), auctionData.getWorld());

			returnStartPriceToAuctioneer();

			returnBidderMoney(true);
			cancelRecord("SELLER_MAILBOX", "MAILBOX");
		}
	}

	public void impoundAuction(@NotNull AuctionPlayer impoundingPlayer) {
		synchronized (this) {
			if (!running)
				return;

			cancelRepeatingTask();
			String impoundingPlayerName = impoundingPlayer.getOfflinePlayer().getName();
			if (impoundingPlayerName == null) {
				impoundingPlayerName = "";
			}

			rewards.createItemReward(impoundingPlayer.getUniqueId(), auctionData.getId(),
					auctionData.getItem(), auctionData.getAmount(), auctionData.getWorld());
			returnStartPriceToAuctioneer();
			returnBidderMoney(true);
			cancelRecord("IMPOUNDER_MAILBOX", "MAILBOX");
		}
	}

	public void end() {
		synchronized (this) {
			if (!running)
				return;

			cancelRepeatingTask();
			handleAuctionTimeCompleted();
		}
	}

	public void checkAntiSnipe() {
		if (!running)
			return;

		if (!config.getConfig().getBoolean("antisnipe.enabled"))
			return;

		if (antiSnipeRunTimes >= config.getConfig().getInt("antisnipe.run-times"))
			return;

		if (remainingSeconds > config.getConfig().getInt("antisnipe.seconds-for-start"))
			return;

		antiSnipeRunTimes++;
		remainingSeconds += config.getConfig().getInt("antisnipe.time");
		revision++;
		int added = config.getConfig().getInt("antisnipe.time");
		for (Player player : plugin.getServer().getOnlinePlayers()) {
			scheduler.runPlayerRegionTask(() -> {
				player.sendTitle("§c延长 " + added + " 秒", "§7触发反狙击保护", 0, 30, 10);
				player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7F, 1.25F);
			}, player);
		}
	}

	@Override
	public void run() {
		synchronized (this) {
			if (!running)
				return;
			if (pendingBid != null) {
				return;
			}

			remainingSeconds -= 1;

			// automatically cancel auction after a set number of seconds with no bids
			if (autoCancelTime != 0
					&& auctionData.getStartingAuctionTime() - remainingSeconds >= autoCancelTime
					&& bidList.hasNoBids()) {
				cancelAuction(false);
			}

			if (remainingSeconds == 0) {
				cancelRepeatingTask();
				handleAuctionTimeCompleted();

				return;
			}

		}
	}

	private void cancelRepeatingTask() {
		running = false;

		if (repeatingTask != null) {
			repeatingTask.cancel();
		}

		revision++;
		completedRunnable.run();
	}

	private void loadAuctionData(AuctionData auctionData) {
		remainingSeconds = auctionData.getStartingAuctionTime();
		bidList = new BidList(this);
	}

	private void handleAuctionTimeCompleted() {
		Bid winningBid = bidList.getHighestBid();
		if (winningBid == null) {
			rewards.createItemReward(auctionData.getAuctioneer().getUniqueId(), auctionData.getId(),
					auctionData.getItem(), auctionData.getAmount(), auctionData.getWorld());
			completeRecord(null, 0L, 0L, 0L, "SELLER_MAILBOX");
			return;
		}

		rewards.createItemReward(winningBid.auctionPlayer().getUniqueId(), auctionData.getId(),
				auctionData.getItem(), auctionData.getAmount(), auctionData.getWorld());

		OfflinePlayer offlinePlayer = auctionData.getAuctioneer().getOfflinePlayer();
		long finalPriceMinor = winningBid.amountMinor();
		BigDecimal taxPercentage = BigDecimal.ZERO;

		if (!isTaxExempt(offlinePlayer)) {
			taxPercentage = BigDecimal.valueOf(config.getConfig().getDouble("auctions.fees.tax-percent"));
		}

		long taxMinor = Money.percentage(finalPriceMinor, taxPercentage);
		long payoutMinor = finalPriceMinor - taxMinor;
		rewards.createMoneyReward(auctionData.getAuctioneer().getUniqueId(), auctionData.getId(),
				RewardKind.INCOME, payoutMinor);

		returnBidderMoney(false);
		completeRecord(winningBid.auctionPlayer().getUniqueId(), finalPriceMinor, payoutMinor, taxMinor,
				"WINNER_MAILBOX");

		AuctionEndEvent event = new AuctionEndEvent(this);
		plugin.getServer().getPluginManager().callEvent(event);
	}

	private boolean isTaxExempt(OfflinePlayer offlinePlayer) {
		if (permission == null) {
			return offlinePlayer.getPlayer() != null
					&& offlinePlayer.getPlayer().hasPermission("ezauctions.taxexempt");
		}

		return permission.playerHas(auctionData.getWorld(), offlinePlayer, "ezauctions.taxexempt");
	}

	private void returnStartPriceToAuctioneer() {
		long startPrice = Money.fromMajor(config.getConfig().getDouble("auctions.fees.start-price"));
		if (startPrice > 0) {
			rewards.createMoneyReward(auctionData.getAuctioneer().getUniqueId(), auctionData.getId(),
					RewardKind.REFUND, startPrice);
		}
	}

	private void returnBidderMoney(boolean returnToHighestBidder) {
		if (bidList == null) {
			return;
		}
		Map<AuctionPlayer, Long> bidMap = bidList.getBidMapMinor();

		if (bidMap.isEmpty())
			return;

		if (!returnToHighestBidder) {
			bidMap.remove(bidList.getHighestBid().auctionPlayer());
		}

		for (Map.Entry<AuctionPlayer, Long> entry : bidMap.entrySet()) {
			rewards.createMoneyReward(entry.getKey().getUniqueId(), auctionData.getId(),
					RewardKind.REFUND, entry.getValue());
		}
	}

	public synchronized @NotNull AuctionView viewFor(@Nullable AuctionPlayer viewer) {
		Bid highest = bidList == null ? null : bidList.getHighestBid();
		Bid viewerHighest = viewer == null || bidList == null ? null : bidList.getHighestBidForPlayer(viewer);
		int viewerBidCount = viewer == null || bidList == null ? 0 : bidList.getNumberOfBids(viewer);
		int maxBids = config.getConfig().getInt("sealed-auctions.max-bids");
		int remainingBids = auctionData.isSealed() && maxBids > 0
				? Math.max(0, maxBids - viewerBidCount)
				: Integer.MAX_VALUE;
		String sellerName = auctionData.getAuctioneer().getOfflinePlayer().getName();
		String highestName = highest == null ? "" : highest.auctionPlayer().getOfflinePlayer().getName();
		return new AuctionView(
				auctionData.getId(),
				revision,
				running,
				auctionData.isSealed(),
				auctionData.getStartingAuctionTime(),
				remainingSeconds,
				auctionData.getItem().clone(),
				auctionData.getAmount(),
				auctionData.getAuctioneer().getUniqueId(),
				sellerName == null ? auctionData.getAuctioneer().getUniqueId().toString() : sellerName,
				auctionData.getWorld(),
				auctionData.getStartingPriceMinor(),
				auctionData.getIncrementPriceMinor(),
				highest == null ? auctionData.getStartingPriceMinor() : highest.amountMinor(),
				highest == null ? null : highest.auctionPlayer().getUniqueId(),
				highestName == null ? "" : highestName,
				auctionData.getAutoBuyPriceMinor(),
				viewerHighest == null ? 0L : viewerHighest.amountMinor(),
				viewerBidCount,
				remainingBids,
				pendingBid != null);
	}

	/**
	 * Validates against authoritative state, reserves the Vault money, persists the bid and only then publishes it.
	 */
	public @NotNull CompletableFuture<BidOutcome> submitBid(@NotNull Player player,
	                                                       @NotNull AuctionPlayer auctionPlayer,
	                                                       @NotNull UUID expectedAuctionId,
	                                                       long expectedRevision,
	                                                       long requestedAmountMinor,
	                                                       boolean buyout) {
		CompletableFuture<BidOutcome> result = new CompletableFuture<>();
		UUID token;
		Bid bid;
		long amountToRemove;

		synchronized (this) {
			AuctionView latest = viewFor(auctionPlayer);
			if (!running || !auctionData.getId().equals(expectedAuctionId)) {
				result.complete(BidOutcome.of(BidOutcome.Status.NO_AUCTION, latest));
				return result;
			}
			if (revision != expectedRevision) {
				result.complete(BidOutcome.of(BidOutcome.Status.STALE_VIEW, latest));
				return result;
			}
			if (pendingBid != null) {
				result.complete(BidOutcome.of(BidOutcome.Status.BID_PROCESSING, latest));
				return result;
			}
			if (requestedAmountMinor <= 0 || requestedAmountMinor > configuredMaximumMoney()) {
				result.complete(BidOutcome.of(BidOutcome.Status.INVALID_AMOUNT, latest));
				return result;
			}
			if (auctionData.getAuctioneer().getUniqueId().equals(player.getUniqueId())) {
				result.complete(BidOutcome.of(BidOutcome.Status.SELF_BID, latest));
				return result;
			}

			String playerWorld = player.getWorld().getName();
			if (config.getConfig().getStringList("auctions.blocked-worlds").stream()
					.anyMatch(playerWorld::equalsIgnoreCase)) {
				result.complete(BidOutcome.of(BidOutcome.Status.BLOCKED_WORLD, latest));
				return result;
			}
			if (config.getConfig().getBoolean("auctions.per-world-auctions")
					&& !playerWorld.equalsIgnoreCase(auctionData.getWorld())) {
				result.complete(BidOutcome.of(BidOutcome.Status.WRONG_WORLD, latest));
				return result;
			}
			if (!auctionPlayer.withinBoundary(config)) {
				result.complete(BidOutcome.of(BidOutcome.Status.OUTSIDE_BOUNDARY, latest));
				return result;
			}

			long amount = requestedAmountMinor;
			if (buyout) {
				if (auctionData.getAutoBuyPriceMinor() == 0) {
					result.complete(BidOutcome.of(BidOutcome.Status.NO_BUYOUT, latest));
					return result;
				}
				amount = auctionData.getAutoBuyPriceMinor();
			} else {
				long minimum = bidList.getMinimumRequiredBidMinor(auctionPlayer);
				if (amount < minimum) {
					result.complete(BidOutcome.of(BidOutcome.Status.TOO_LOW, latest));
					return result;
				}
				if (auctionData.getAutoBuyPriceMinor() > 0) {
					amount = Math.min(amount, auctionData.getAutoBuyPriceMinor());
				}
			}

			if (auctionData.isSealed()) {
				int maxBids = config.getConfig().getInt("sealed-auctions.max-bids");
				if (maxBids > 0 && bidList.getNumberOfBids(auctionPlayer) >= maxBids) {
					result.complete(BidOutcome.of(BidOutcome.Status.MAX_BIDS, latest));
					return result;
				}
			}

			int maxConsecutive = config.getConfig().getInt("auctions.maximum.consecutive-bids");
			if (maxConsecutive > 0 && bidList.getConsecutiveBids(auctionPlayer) >= maxConsecutive) {
				result.complete(BidOutcome.of(BidOutcome.Status.CONSECUTIVE_LIMIT, latest));
				return result;
			}

			Bid existing = bidList.getHighestBidForPlayer(auctionPlayer);
			long existingAmount = existing == null ? 0L : existing.amountMinor();
			amountToRemove = amount - existingAmount;
			if (amountToRemove <= 0) {
				result.complete(BidOutcome.of(BidOutcome.Status.TOO_LOW, latest));
				return result;
			}

			long balanceMinor;
			try {
				balanceMinor = Money.fromMajor(economy.getBalance(auctionPlayer.getOfflinePlayer()));
			} catch (IllegalArgumentException exception) {
				result.complete(BidOutcome.of(BidOutcome.Status.ECONOMY_FAILED, latest));
				return result;
			}
			if (balanceMinor < amountToRemove) {
				result.complete(BidOutcome.of(BidOutcome.Status.INSUFFICIENT_FUNDS, latest));
				return result;
			}

			bid = new Bid(auctionPlayer, amount);
			AuctionBidEvent event = new AuctionBidEvent(this, bid);
			plugin.getServer().getPluginManager().callEvent(event);
			if (event.isCancelled()) {
				result.complete(BidOutcome.of(BidOutcome.Status.EVENT_CANCELLED, latest));
				return result;
			}

			EconomyResponse response = economy.withdrawPlayer(auctionPlayer.getOfflinePlayer(),
					Money.toMajor(amountToRemove));
			if (response == null || !response.transactionSuccess()) {
				result.complete(BidOutcome.of(BidOutcome.Status.ECONOMY_FAILED, latest));
				return result;
			}

			token = UUID.randomUUID();
			pendingBidToken = token;
			pendingBid = bid;
			pendingWithdrawMinor = amountToRemove;
			revision++;
		}

		long acceptedAmount = bid.amountMinor();
		database.createBidRecord(new AuctionBidRecord(auctionData.getId(), auctionPlayer.getUniqueId(),
				acceptedAmount)).whenComplete((ignored, error) -> scheduler.runSyncTask(() ->
				completePendingBid(token, acceptedAmount, result, error)));
		return result;
	}

	private synchronized void completePendingBid(@NotNull UUID token, long acceptedAmount,
	                                             @NotNull CompletableFuture<BidOutcome> result,
	                                             @Nullable Throwable error) {
		if (!token.equals(pendingBidToken) || pendingBid == null) {
			result.complete(BidOutcome.of(BidOutcome.Status.STALE_VIEW,
					auctionData == null ? null : viewFor(null)));
			return;
		}

		Bid bid = pendingBid;
		long withdrawn = pendingWithdrawMinor;
		pendingBid = null;
		pendingBidToken = null;
		pendingWithdrawMinor = 0L;

		if (error != null || !running) {
			EconomyResponse refund = economy.depositPlayer(bid.auctionPlayer().getOfflinePlayer(),
					Money.toMajor(withdrawn));
			if (refund == null || !refund.transactionSuccess()) {
				rewards.createMoneyReward(bid.auctionPlayer().getUniqueId(), auctionData.getId(),
						RewardKind.REFUND, withdrawn);
			}
			revision++;
			result.complete(BidOutcome.of(error != null
							? BidOutcome.Status.PERSISTENCE_FAILED
							: BidOutcome.Status.NO_AUCTION,
					viewFor(bid.auctionPlayer())));
			return;
		}

		bidList.placeBid(bid);
		revision++;
		result.complete(new BidOutcome(BidOutcome.Status.SUCCESS, acceptedAmount,
				viewFor(bid.auctionPlayer())));
	}

	private long configuredMaximumMoney() {
		return config.getConfig().getLong("gui.maximum-money-minor", Money.DEFAULT_MAX_MINOR);
	}

	private void completeRecord(@Nullable UUID winnerId, long finalPriceMinor, long payoutMinor, long taxMinor,
	                            @NotNull String destination) {
		database.getAuctionRecord(auctionData.getId()).thenAccept(optional -> optional.ifPresent(record -> {
			record.complete(winnerId, finalPriceMinor, payoutMinor, taxMinor, destination, "MAILBOX");
			database.saveAuctionRecord(record);
		}));
	}

	private void cancelRecord(@NotNull String destination, @NotNull String refundStatus) {
		database.getAuctionRecord(auctionData.getId()).thenAccept(optional -> optional.ifPresent(record -> {
			record.cancel(destination, refundStatus);
			database.saveAuctionRecord(record);
		}));
	}
}
