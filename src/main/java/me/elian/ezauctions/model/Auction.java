package me.elian.ezauctions.model;

import com.google.inject.Inject;
import me.elian.ezauctions.controller.AuctionPlayerController;
import me.elian.ezauctions.controller.ConfigController;
import me.elian.ezauctions.controller.MessageController;
import me.elian.ezauctions.controller.RewardController;
import me.elian.ezauctions.controller.ScoreboardController;
import me.elian.ezauctions.data.Database;
import me.elian.ezauctions.event.AuctionBidAcceptedEvent;
import me.elian.ezauctions.event.AuctionBidEvent;
import me.elian.ezauctions.event.AuctionEndEvent;
import me.elian.ezauctions.event.AuctionStartEvent;
import me.elian.ezauctions.scheduler.CancellableTask;
import me.elian.ezauctions.scheduler.TaskScheduler;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

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
	private boolean pendingWithdrawn;
	private Runnable deferredTermination;
	private boolean completionNotified;
	private boolean activationPending;
	private String scheduledSessionId;
	private UUID scheduledLotId;
	private BidAuthorization bidAuthorization = BidAuthorization.DENY_ALL;

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

	public synchronized int getAntiSnipeRunTimes() {
		return antiSnipeRunTimes;
	}

	public synchronized boolean isRunning() {
		return running;
	}

	public synchronized @Nullable String getScheduledSessionId() {
		return scheduledSessionId;
	}

	/** Binds a persistent session lot before it starts; scheduled bids are fail-closed without this context. */
	public synchronized void bindScheduledSession(@NotNull String sessionId, @NotNull UUID lotId,
	                                             @NotNull BidAuthorization authorization) {
		if (started || sessionId.isBlank()) {
			throw new IllegalStateException("Scheduled session context must be bound before auction start");
		}
		this.scheduledSessionId = sessionId;
		this.scheduledLotId = lotId;
		this.bidAuthorization = authorization;
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
		loadAuctionData(auctionData);

		AuctionStartEvent event = new AuctionStartEvent(this);
		plugin.getServer().getPluginManager().callEvent(event);

		if (event.isCancelled()) {
			stopTimer();
			List<RewardRecord> settlementRewards = List.of(sellerItemReturn());
			CompletableFuture<Void> settlement = cancelRecord(
					"SELLER_MAILBOX", "NONE", settlementRewards);
			finishLifecycle(settlement, false);
			return;
		}

		autoCancelTime = config.getConfig().getInt("auctions.auto-cancel-no-bids", 0);

		// Do not start the wall-clock timer until escrow ownership is durably ACTIVE. A
		// transient database failure here used to let the in-memory timer expire from QUEUED,
		// after which terminal settlement could never match and the venue remained wedged.
		activateAuctionRecord(auctionData);
	}

	private void activateAuctionRecord(@NotNull AuctionData expectedData) {
		synchronized (this) {
			if (!running || auctionData != expectedData || activationPending || repeatingTask != null) {
				return;
			}
			activationPending = true;
		}
		database.transitionAuction(expectedData.getId(), AuctionRecordStatus.QUEUED,
				AuctionRecordStatus.ACTIVE).whenComplete((activated, error) -> {
			if (error != null) {
				plugin.getLogger().log(Level.SEVERE,
						"Could not mark auction " + expectedData.getId()
								+ " as active; retrying without advancing its timer", error);
				scheduleActivationRetry(expectedData);
				return;
			}
			if (Boolean.TRUE.equals(activated)) {
				scheduler.runSyncTask(() -> beginActivatedAuction(expectedData));
				return;
			}
			// A database/transport failure may have committed the prior CAS even when its
			// callback was lost. Re-read before retrying so ACTIVE is accepted idempotently.
			database.getAuctionRecord(expectedData.getId()).whenComplete((record, lookupError) -> {
				if (lookupError != null) {
					plugin.getLogger().log(Level.SEVERE,
							"Could not verify activation state for auction " + expectedData.getId(),
							lookupError);
					scheduleActivationRetry(expectedData);
					return;
				}
				if (record.isPresent() && record.get().getStatus() == AuctionRecordStatus.ACTIVE) {
					scheduler.runSyncTask(() -> beginActivatedAuction(expectedData));
					return;
				}
				plugin.getLogger().severe("Auction " + expectedData.getId()
						+ " is not durably ACTIVE yet; retrying without advancing its timer");
				scheduleActivationRetry(expectedData);
			});
		});
	}

	private synchronized void beginActivatedAuction(@NotNull AuctionData expectedData) {
		activationPending = false;
		if (!plugin.isEnabled() || !running || auctionData != expectedData || repeatingTask != null) {
			return;
		}
		messages.broadcastAuctionMessage(playerController.getOnlinePlayers(), this,
				false, "auction.info");
		repeatingTask = scheduler.runSyncRepeatingTask(plugin, this, 1, 1);
	}

	private void scheduleActivationRetry(@NotNull AuctionData expectedData) {
		synchronized (this) {
			activationPending = false;
			if (!plugin.isEnabled() || !running || auctionData != expectedData
					|| repeatingTask != null) {
				return;
			}
			activationPending = true;
		}
		scheduler.runAsyncDelayedTask(() -> {
			synchronized (this) {
				activationPending = false;
			}
			activateAuctionRecord(expectedData);
		}, 1L);
	}

	/**
	 * Rehydrates a session lot from durable bid records and a runtime checkpoint. Restored bids are only published
	 * into memory; Vault is deliberately not touched again.
	 */
	public void restoreAuction(@NotNull AuctionData auctionData, @NotNull java.util.List<Bid> restoredBids,
	                          int restoredRemainingSeconds, int restoredAntiSnipeRuns, long restoredRevision,
	                          @NotNull Runnable completedRunnable) {
		synchronized (this) {
			if (started) {
				throw new IllegalStateException("Can not restore an auction that has already been started!");
			}
			started = true;
			running = true;
			this.auctionData = auctionData;
			this.completedRunnable = completedRunnable;
			autoCancelTime = config.getConfig().getInt("auctions.auto-cancel-no-bids", 0);
			loadAuctionData(auctionData);
			for (Bid bid : restoredBids) {
				bidList.restoreBid(bid);
			}
			remainingSeconds = Math.max(1, Math.min(auctionData.getStartingAuctionTime(),
					restoredRemainingSeconds));
			antiSnipeRunTimes = Math.max(0, restoredAntiSnipeRuns);
			revision = Math.max(1L, restoredRevision + 1L);
		}
		messages.broadcastAuctionMessage(playerController.getOnlinePlayers(), this,
				false, "auction.info");
		repeatingTask = scheduler.runSyncRepeatingTask(plugin, this, 1, 1);
	}

	public void cancelAuction(boolean returnMoney) {
		synchronized (this) {
			if (!running)
				return;
			if (pendingBid != null) {
				deferTermination(() -> cancelAuction(returnMoney));
				return;
			}

			stopTimer();
			List<RewardRecord> settlementRewards = cancellationRewards(
					auctionData.getAuctioneer().getUniqueId(), returnMoney);
			CompletableFuture<Void> settlement = cancelRecord(
					"SELLER_MAILBOX", "MAILBOX", settlementRewards);
			finishLifecycle(settlement, false);
		}
	}

	public void cancelAuctionShutdown() {
		synchronized (this) {
			if (!running)
				return;
			abandonPendingBidForLegacyShutdown();

			stopTimer();
			List<RewardRecord> settlementRewards = cancellationRewards(
					auctionData.getAuctioneer().getUniqueId(), true);
			CompletableFuture<Void> settlement = cancelRecord(
					"SELLER_MAILBOX", "MAILBOX", settlementRewards);
			finishLifecycle(settlement, false);
		}
	}

	public void impoundAuction(@NotNull AuctionPlayer impoundingPlayer) {
		synchronized (this) {
			if (!running)
				return;
			if (pendingBid != null) {
				deferTermination(() -> impoundAuction(impoundingPlayer));
				return;
			}

			stopTimer();
			String impoundingPlayerName = impoundingPlayer.getOfflinePlayer().getName();
			if (impoundingPlayerName == null) {
				impoundingPlayerName = "";
			}

			List<RewardRecord> settlementRewards = cancellationRewards(
					impoundingPlayer.getUniqueId(), true);
			CompletableFuture<Void> settlement = cancelRecord(
					"IMPOUNDER_MAILBOX", "MAILBOX", settlementRewards);
			finishLifecycle(settlement, false);
		}
	}

	public void end() {
		synchronized (this) {
			if (!running)
				return;
			if (pendingBid != null) {
				deferTermination(this::end);
				return;
			}

			stopTimer();
			beginAuctionSettlement();
		}
	}

	public synchronized void checkAntiSnipe() {
		if (!running || !config.isAntiSnipeConfigCurrent())
			return;

		if (!config.getConfig().getBoolean("antisnipe.enabled"))
			return;

		int targetRemainingSeconds = AntiSnipePolicy.targetRemainingSeconds(
				remainingSeconds,
				auctionData.getStartingAuctionTime(),
				config.getConfig().getInt("antisnipe.seconds-for-start"),
				config.getConfig().getInt("antisnipe.time"),
				antiSnipeRunTimes,
				config.getConfig().getInt("antisnipe.run-times"));
		if (targetRemainingSeconds <= remainingSeconds)
			return;

		antiSnipeRunTimes++;
		remainingSeconds = targetRemainingSeconds;
		revision++;
		for (Player player : plugin.getServer().getOnlinePlayers()) {
			scheduler.runPlayerRegionTask(() -> {
				if (!player.isOnline()) {
					return;
				}
				player.sendTitle("§c剩余时间重置为 " + targetRemainingSeconds + " 秒",
						"§7触发防秒拍保护", 0, 30, 10);
				player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7F, 1.25F);
			}, player);
		}
	}

	/** Stops only the in-memory timer; durable session state remains ACTIVE for restart recovery. */
	public synchronized void suspendForShutdown() {
		if (!running) {
			return;
		}
		running = false;
		if (repeatingTask != null) {
			repeatingTask.cancel();
			repeatingTask = null;
		}
		pendingBidToken = null;
		pendingBid = null;
		pendingWithdrawMinor = 0L;
		pendingWithdrawn = false;
		activationPending = false;
		deferredTermination = null;
		revision++;
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
				return;
			}

			if (remainingSeconds <= 0) {
				stopTimer();
				beginAuctionSettlement();

				return;
			}

		}
	}

	private void stopTimer() {
		running = false;
		activationPending = false;

		if (repeatingTask != null) {
			repeatingTask.cancel();
		}

		revision++;
	}

	/** Advances the enclosing queue/session only after every deterministic reward and terminal CAS is durable. */
	private void finishLifecycle(@NotNull CompletableFuture<Void> settlement,
	                             boolean publishSoldEvent) {
		settlement.whenComplete((ignored, error) -> {
			if (error != null) {
				plugin.getLogger().log(Level.SEVERE,
						"Could not durably settle auction " + auctionData.getId()
								+ "; the lot will remain recoverable and will not advance",
						error);
				return;
			}
			Runnable completion = () -> {
				if (publishSoldEvent && plugin.isEnabled()) {
					plugin.getServer().getPluginManager().callEvent(new AuctionEndEvent(this));
				}
				notifyCompleted();
			};
			if (plugin.isEnabled()) {
				scheduler.runSyncTask(completion);
			} else {
				completion.run();
			}
		});
	}

	private void notifyCompleted() {
		Runnable callback;
		synchronized (this) {
			if (completionNotified) {
				return;
			}
			completionNotified = true;
			callback = completedRunnable;
		}
		if (callback != null) {
			callback.run();
		}
	}

	private void loadAuctionData(AuctionData auctionData) {
		remainingSeconds = auctionData.getStartingAuctionTime();
		bidList = new BidList(this);
	}

	private void beginAuctionSettlement() {
		Bid winningBid = bidList.getHighestBid();
		if (winningBid == null) {
			messages.broadcastAuctionResultMessage(playerController.getOnlinePlayers(), this,
					false, "auction.finish.no_bids");
			UUID sellerId = auctionData.getAuctioneer().getUniqueId();
			CompletableFuture<Void> settlement = completeRecord(null, 0L, 0L, 0L,
					"SELLER_MAILBOX", List.of(sellerItemReturn()));
			notifyAfterRewardCreated(settlement, sellerId, "seller item return",
					"auction.finish.seller.no_bids");
			finishLifecycle(settlement, false);
			return;
		}

		messages.broadcastAuctionResultMessage(playerController.getOnlinePlayers(), this,
				false, "auction.finish");

		OfflinePlayer offlinePlayer = auctionData.getAuctioneer().getOfflinePlayer();
		long finalPriceMinor = winningBid.amountMinor();
		BigDecimal taxPercentage = BigDecimal.ZERO;

		if (!isTaxExempt(offlinePlayer)) {
			taxPercentage = BigDecimal.valueOf(config.getConfig().getDouble("auctions.fees.tax-percent"));
		}

		long taxMinor = Money.percentage(finalPriceMinor, taxPercentage);
		long payoutMinor = finalPriceMinor - taxMinor;
		UUID winnerId = winningBid.auctionPlayer().getUniqueId();
		UUID sellerId = auctionData.getAuctioneer().getUniqueId();
		List<RewardRecord> settlementRewards = new ArrayList<>();
		settlementRewards.add(RewardRecord.item(winnerId, auctionData.getId(),
				auctionData.getItem(), auctionData.getAmount(), auctionData.getWorld()));
		addMoneyReward(settlementRewards, sellerId, RewardKind.INCOME, payoutMinor);
		Map<UUID, Long> loserRefunds = refundableBidAmounts(false);
		loserRefunds.forEach((ownerId, amountMinor) ->
				addMoneyReward(settlementRewards, ownerId, RewardKind.REFUND, amountMinor));

		CompletableFuture<Void> settlement = completeRecord(winnerId, finalPriceMinor,
				payoutMinor, taxMinor, "WINNER_MAILBOX", settlementRewards);
		notifyAfterRewardCreated(settlement, winnerId, "winner item", "auction.finish.winner");
		if (payoutMinor > 0) {
			notifyAfterRewardCreated(settlement, sellerId, "seller income", "auction.finish.seller",
					Formatter.number("payout", Money.toMajor(payoutMinor)));
		}
		loserRefunds.forEach((ownerId, amountMinor) ->
				notifyAfterRewardCreated(settlement, ownerId, "bidder refund", "auction.finish.loser",
						Formatter.number("refund", Money.toMajor(amountMinor))));
		finishLifecycle(settlement, true);
	}

	private boolean isTaxExempt(OfflinePlayer offlinePlayer) {
		if (permission == null) {
			return offlinePlayer.getPlayer() != null
					&& offlinePlayer.getPlayer().hasPermission("ezauctions.taxexempt");
		}

		return permission.playerHas(auctionData.getWorld(), offlinePlayer, "ezauctions.taxexempt");
	}

	private @NotNull RewardRecord sellerItemReturn() {
		return RewardRecord.item(auctionData.getAuctioneer().getUniqueId(), auctionData.getId(),
				auctionData.getItem(), auctionData.getAmount(), auctionData.getWorld());
	}

	private @NotNull List<RewardRecord> cancellationRewards(@NotNull UUID itemOwner,
	                                                       boolean refundListingFee) {
		List<RewardRecord> result = new ArrayList<>();
		result.add(RewardRecord.item(itemOwner, auctionData.getId(), auctionData.getItem(),
				auctionData.getAmount(), auctionData.getWorld()));

		Map<UUID, Long> refunds = refundableBidAmounts(true);
		if (refundListingFee) {
			long listingFee = Money.fromMajor(config.getConfig().getDouble("auctions.fees.start-price"));
			if (listingFee > 0) {
				refunds.merge(auctionData.getAuctioneer().getUniqueId(), listingFee, Math::addExact);
			}
		}
		refunds.forEach((ownerId, amountMinor) ->
				addMoneyReward(result, ownerId, RewardKind.REFUND, amountMinor));
		return result;
	}

	private @NotNull Map<UUID, Long> refundableBidAmounts(boolean includeHighestBidder) {
		Map<UUID, Long> refundableBids = new LinkedHashMap<>();
		if (bidList == null) {
			return refundableBids;
		}
		for (Map.Entry<AuctionPlayer, Long> entry : bidList.getBidMapMinor().entrySet()) {
			refundableBids.merge(entry.getKey().getUniqueId(), entry.getValue(), Math::max);
		}
		if (!includeHighestBidder && bidList.getHighestBid() != null) {
			refundableBids.remove(bidList.getHighestBid().auctionPlayer().getUniqueId());
		}
		return refundableBids;
	}

	private void addMoneyReward(@NotNull Collection<RewardRecord> settlementRewards,
	                            @NotNull UUID ownerId, @NotNull RewardKind kind,
	                            long amountMinor) {
		if (amountMinor > 0) {
			settlementRewards.add(RewardRecord.money(ownerId, auctionData.getId(), kind, amountMinor));
		}
	}

	private void notifyAfterRewardCreated(@NotNull CompletableFuture<Void> rewardCreation,
	                                      @NotNull UUID recipientId, @NotNull String rewardDescription,
	                                      @NotNull String messageKey, TagResolver... resolvers) {
		rewardCreation.whenComplete((ignored, error) -> {
			if (error != null) {
				logRewardCreationFailure(recipientId, rewardDescription, error);
				return;
			}
			if (!plugin.isEnabled()) {
				return;
			}

			scheduler.runSyncTask(() -> {
				if (!plugin.isEnabled()) {
					return;
				}
				Player recipient = plugin.getServer().getPlayer(recipientId);
				if (recipient == null || !recipient.isOnline()) {
					return;
				}
				scheduler.runPlayerRegionTask(() -> {
					if (plugin.isEnabled() && recipient.isOnline()) {
						messages.sendAuctionResultMessage(recipient, messageKey, this, resolvers);
					}
				}, recipient);
			});
		});
	}

	private void logRewardCreationFailure(@NotNull UUID recipientId, @NotNull String rewardDescription,
	                                      @NotNull Throwable error) {
		plugin.getLogger().log(Level.SEVERE,
				"Could not create " + rewardDescription + " reward for auction "
						+ auctionData.getId() + " and player " + recipientId, error);
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
		AuctionBidTransaction transaction;

		synchronized (this) {
			AuctionView latest = viewFor(auctionPlayer);
			if (!running || !auctionData.getId().equals(expectedAuctionId)) {
				result.complete(BidOutcome.of(BidOutcome.Status.NO_AUCTION, latest));
				return result;
			}
			BidOutcome.Status authorizationFailure = scheduledAuthorizationFailure(player);
			if (authorizationFailure != null) {
				result.complete(BidOutcome.of(authorizationFailure, latest));
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

			// A scheduled lot has already passed the stronger ACTIVE-participant + current-venue
			// authorization above. Legacy per-world and boundary settings describe the seller's
			// original instant-auction context and must not reject a buyer standing in the venue.
			if (scheduledSessionId == null) {
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

			token = UUID.randomUUID();
			pendingBidToken = token;
			pendingBid = bid;
			pendingWithdrawMinor = amountToRemove;
			pendingWithdrawn = false;
			String durableSessionId = scheduledSessionId == null
					? "legacy/" + auctionData.getId() : scheduledSessionId;
			UUID durableLotId = scheduledLotId == null ? auctionData.getId() : scheduledLotId;
			transaction = new AuctionBidTransaction(token, durableSessionId, durableLotId,
					auctionData.getId(), auctionPlayer.getUniqueId(), amountToRemove,
					System.currentTimeMillis());
			revision++;
		}

		long acceptedAmount = bid.amountMinor();
		database.createBidTransaction(transaction)
				.thenCompose(created -> database.transitionBidTransaction(token,
						BidTransactionState.PREPARED, BidTransactionState.WITHDRAWING, "",
						System.currentTimeMillis()))
				.whenComplete((prepared, error) -> scheduler.runSyncTask(() ->
						withdrawPreparedBid(token, acceptedAmount, result,
								Boolean.TRUE.equals(prepared), error)));
		return result;
	}

	private synchronized void withdrawPreparedBid(@NotNull UUID token, long acceptedAmount,
	                                              @NotNull CompletableFuture<BidOutcome> result,
	                                              boolean prepared, @Nullable Throwable error) {
		if (!token.equals(pendingBidToken) || pendingBid == null) {
			result.complete(BidOutcome.of(BidOutcome.Status.STALE_VIEW,
					auctionData == null ? null : viewFor(null)));
			return;
		}
		if (error != null || !prepared) {
			loggerTransactionFailure("prepare", token, error);
			finishPendingWithoutWithdrawal(token, result, BidOutcome.Status.PERSISTENCE_FAILED,
					"could not prepare withdrawal");
			return;
		}
		if (!running) {
			finishPendingWithoutWithdrawal(token, result, BidOutcome.Status.NO_AUCTION,
					"auction stopped before withdrawal");
			return;
		}

		EconomyResponse response;
		try {
			response = economy.withdrawPlayer(pendingBid.auctionPlayer().getOfflinePlayer(),
					Money.toMajor(pendingWithdrawMinor));
		} catch (RuntimeException exception) {
			loggerTransactionFailure("withdraw", token, exception);
			// Vault providers cannot atomically participate in our database transaction. An
			// exception may have been thrown after the provider already debited the account,
			// so WITHDRAWING is deliberately treated as uncertain and compensated through a
			// deterministic mailbox reward instead of being certified as an unpaid failure.
			compensatePendingBid(token, result, BidOutcome.Status.ECONOMY_FAILED,
					"uncertain Vault withdrawal failure", exception);
			return;
		}
		if (response == null) {
			compensatePendingBid(token, result, BidOutcome.Status.ECONOMY_FAILED,
					"Vault returned no withdrawal result", null);
			return;
		}
		if (!response.transactionSuccess()) {
			finishPendingWithoutWithdrawal(token, result, BidOutcome.Status.ECONOMY_FAILED,
					"Vault withdrawal failed");
			return;
		}

		pendingWithdrawn = true;
		UUID auctionId = auctionData.getId();
		UUID bidderId = pendingBid.auctionPlayer().getUniqueId();
		database.transitionBidTransaction(token, BidTransactionState.WITHDRAWING,
				BidTransactionState.WITHDRAWN, "", System.currentTimeMillis())
				.whenComplete((withdrawn, transitionError) -> {
					if (transitionError != null || !Boolean.TRUE.equals(withdrawn)) {
						scheduler.runSyncTask(() -> compensatePendingBid(token, result,
								BidOutcome.Status.PERSISTENCE_FAILED,
								"could not persist successful Vault withdrawal", transitionError));
						return;
					}
					database.createBidRecord(new AuctionBidRecord(token, auctionId, bidderId,
							acceptedAmount, System.currentTimeMillis()))
							.whenComplete((ignored, persistError) -> scheduler.runSyncTask(() ->
									completePendingBid(token, acceptedAmount, result, persistError)));
				});
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

		if (error != null || !running) {
			compensatePendingBid(token, result, error != null
						? BidOutcome.Status.PERSISTENCE_FAILED : BidOutcome.Status.NO_AUCTION,
					error != null ? "bid record persistence failed" : "auction stopped before publication",
					error);
			return;
		}

		Runnable termination = clearPendingBid();
		bidList.placeBid(bid);
		checkAntiSnipe();
		revision++;
		plugin.getServer().getPluginManager().callEvent(new AuctionBidAcceptedEvent(this, bid, token));
		database.transitionBidTransaction(token, BidTransactionState.WITHDRAWN,
				BidTransactionState.COMMITTED, "", System.currentTimeMillis())
				.whenComplete((committed, commitError) -> {
					if (commitError != null || !Boolean.TRUE.equals(committed)) {
						loggerTransactionFailure("commit", token, commitError);
					}
				});
		result.complete(new BidOutcome(BidOutcome.Status.SUCCESS, acceptedAmount,
				viewFor(bid.auctionPlayer())));
		if (termination != null) {
			termination.run();
		}
	}

	private void finishPendingWithoutWithdrawal(@NotNull UUID token,
	                                            @NotNull CompletableFuture<BidOutcome> result,
	                                            @NotNull BidOutcome.Status status,
	                                            @NotNull String reason) {
		Bid bidder = pendingBid;
		Runnable termination = clearPendingBid();
		revision++;
		database.transitionBidTransaction(token, BidTransactionState.PREPARED,
				BidTransactionState.FAILED, reason, System.currentTimeMillis())
				.thenCompose(changed -> Boolean.TRUE.equals(changed)
						? CompletableFuture.completedFuture(true)
						: database.transitionBidTransaction(token, BidTransactionState.WITHDRAWING,
								BidTransactionState.FAILED, reason, System.currentTimeMillis()))
				.whenComplete((changed, error) -> {
					if (error != null) {
						loggerTransactionFailure("fail", token, error);
					}
				});
		result.complete(BidOutcome.of(status, bidder == null ? viewFor(null)
				: viewFor(bidder.auctionPlayer())));
		if (termination != null) {
			termination.run();
		}
	}

	private void compensatePendingBid(@NotNull UUID token,
	                                  @NotNull CompletableFuture<BidOutcome> result,
	                                  @NotNull BidOutcome.Status status,
	                                  @NotNull String reason,
	                                  @Nullable Throwable originalError) {
		Bid bidder;
		AuctionView latest;
		Runnable termination;
		synchronized (this) {
			if (!token.equals(pendingBidToken) || pendingBid == null) {
				result.complete(BidOutcome.of(BidOutcome.Status.STALE_VIEW,
						auctionData == null ? null : viewFor(null)));
				return;
			}
			bidder = pendingBid;
			latest = viewFor(bidder.auctionPlayer());
			termination = clearPendingBid();
			revision++;
		}
		if (originalError != null) {
			loggerTransactionFailure("persist", token, originalError);
		}
		database.compensateBidTransaction(token, reason, System.currentTimeMillis())
				.whenComplete((compensated, compensationError) -> scheduler.runSyncTask(() -> {
					if (compensationError != null || !Boolean.TRUE.equals(compensated)) {
						loggerTransactionFailure("compensate", token, compensationError);
					}
					result.complete(BidOutcome.of(status, latest));
					if (termination != null) {
						termination.run();
					}
				}));
	}

	private synchronized @Nullable Runnable clearPendingBid() {
		pendingBid = null;
		pendingBidToken = null;
		pendingWithdrawMinor = 0L;
		pendingWithdrawn = false;
		Runnable termination = deferredTermination;
		deferredTermination = null;
		return termination;
	}

	private synchronized void deferTermination(@NotNull Runnable termination) {
		if (deferredTermination == null) {
			deferredTermination = termination;
		}
	}

	private synchronized void abandonPendingBidForLegacyShutdown() {
		if (pendingBidToken == null || pendingBid == null) {
			return;
		}
		UUID transactionId = pendingBidToken;
		if (pendingWithdrawn) {
			database.compensateBidTransaction(transactionId,
					"legacy auction shutdown during bid", System.currentTimeMillis());
		} else {
			database.transitionBidTransaction(transactionId, BidTransactionState.PREPARED,
					BidTransactionState.FAILED, "legacy auction shutdown before withdrawal",
					System.currentTimeMillis());
			database.transitionBidTransaction(transactionId, BidTransactionState.WITHDRAWING,
					BidTransactionState.FAILED, "legacy auction shutdown before withdrawal",
					System.currentTimeMillis());
		}
		clearPendingBid();
	}

	private synchronized @Nullable BidOutcome.Status scheduledAuthorizationFailure(
			@NotNull Player player) {
		if (scheduledSessionId == null) {
			return null;
		}
		BidAuthorization.Decision decision;
		try {
			decision = bidAuthorization.authorize(scheduledSessionId, player);
		} catch (RuntimeException exception) {
			plugin.getLogger().log(Level.SEVERE,
					"Scheduled bid authorization failed for session " + scheduledSessionId,
					exception);
			decision = BidAuthorization.Decision.SESSION_NOT_RUNNING;
		}
		return switch (decision) {
			case ALLOWED -> null;
			case SESSION_NOT_RUNNING -> BidOutcome.Status.SESSION_NOT_RUNNING;
			case NOT_PARTICIPANT -> BidOutcome.Status.NOT_PARTICIPANT;
			case NOT_IN_VENUE -> BidOutcome.Status.NOT_IN_VENUE;
		};
	}

	private void loggerTransactionFailure(@NotNull String operation, @NotNull UUID transactionId,
	                                      @Nullable Throwable error) {
		String message = "Bid transaction " + transactionId + " could not " + operation;
		if (error == null) {
			plugin.getLogger().severe(message);
		} else {
			plugin.getLogger().log(Level.SEVERE, message, error);
		}
	}

	private long configuredMaximumMoney() {
		return config.getConfig().getLong("gui.maximum-money-minor", Money.DEFAULT_MAX_MINOR);
	}

	private CompletableFuture<Void> completeRecord(@Nullable UUID winnerId, long finalPriceMinor,
	                                               long payoutMinor, long taxMinor,
	                                               @NotNull String destination,
	                                               @NotNull Collection<RewardRecord> settlementRewards) {
		UUID auctionId = auctionData.getId();
		return rewards.completeAuctionWithRewards(auctionId, winnerId, finalPriceMinor, payoutMinor,
				taxMinor, destination, "MAILBOX", System.currentTimeMillis(), settlementRewards)
				.thenCompose(changed -> requireTerminalTransition(changed, "complete"));
	}

	private CompletableFuture<Void> cancelRecord(@NotNull String destination,
	                                             @NotNull String refundStatus,
	                                             @NotNull Collection<RewardRecord> settlementRewards) {
		UUID auctionId = auctionData.getId();
		long completedAt = System.currentTimeMillis();
		return rewards.cancelAuctionWithRewards(auctionId,
				List.of(AuctionRecordStatus.ACTIVE, AuctionRecordStatus.QUEUED),
				destination, refundStatus, completedAt, settlementRewards)
				.thenCompose(changed -> requireTerminalTransition(changed, "cancel"));
	}

	private CompletableFuture<Void> requireTerminalTransition(boolean changed,
	                                                          @NotNull String operation) {
		if (changed) {
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFuture.failedFuture(new IllegalStateException(
				"Could not " + operation + " auction " + auctionData.getId()
						+ " from its authoritative lifecycle state"));
	}
}
