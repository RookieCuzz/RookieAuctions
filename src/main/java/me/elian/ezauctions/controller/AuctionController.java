package me.elian.ezauctions.controller;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import me.elian.ezauctions.Logger;
import me.elian.ezauctions.data.Database;
import me.elian.ezauctions.helper.ItemHelper;
import me.elian.ezauctions.model.Auction;
import me.elian.ezauctions.model.AuctionBidRecord;
import me.elian.ezauctions.model.AuctionData;
import me.elian.ezauctions.model.AuctionPlayer;
import me.elian.ezauctions.model.AuctionRecord;
import me.elian.ezauctions.model.AuctionRecordStatus;
import me.elian.ezauctions.model.RewardKind;
import me.elian.ezauctions.scheduler.TaskScheduler;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.UnaryOperator;

@Singleton
public class AuctionController implements Listener {
	private final Logger logger;
	private final AuctionPlayerController playerController;
	private final TaskScheduler scheduler;
	private final ConfigController config;
	private final MessageController messages;
	private final Database database;
	private final RewardController rewards;
	private final Provider<Auction> auctionProvider;
	private final Deque<AuctionData> auctionQueue = new ArrayDeque<>();
	private final Map<UUID, Long> queueCooldown = new HashMap<>();

	private boolean auctionsEnabled = true;
	private Auction activeAuction;
	private long lastAuctionEndTimeMillis;

	@Inject
	public AuctionController(Plugin plugin, Logger logger, AuctionPlayerController playerController,
	                         TaskScheduler scheduler, ConfigController config,
	                         MessageController messages, Provider<Auction> auctionProvider,
	                         Database database, RewardController rewards) {
		this.logger = logger;
		this.playerController = playerController;
		this.scheduler = scheduler;
		this.config = config;
		this.messages = messages;
		this.auctionProvider = auctionProvider;
		this.database = database;
		this.rewards = rewards;
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		recoverPersistedAuctions();
	}

	public synchronized boolean isAuctionsEnabled() {
		return auctionsEnabled;
	}

	public synchronized void setAuctionsEnabled(boolean auctionsEnabled) {
		this.auctionsEnabled = auctionsEnabled;
	}

	public void withSync(Runnable runnable) {
		synchronized (this) {
			runnable.run();
		}
	}

	public synchronized @NotNull List<AuctionData> getAuctionQueue() {
		return List.copyOf(auctionQueue);
	}

	public synchronized boolean hasActiveAuction() {
		return activeAuction != null;
	}

	public synchronized @Nullable Auction getActiveAuction() {
		return activeAuction;
	}

	public synchronized long getCooldownTime(@NotNull UUID uniqueId) {
		return queueCooldown.getOrDefault(uniqueId, 0L);
	}

	public synchronized boolean hasCooldown(@NotNull UUID uniqueId) {
		if (!queueCooldown.containsKey(uniqueId))
			return false;

		long time = queueCooldown.get(uniqueId);
		long timeSince = System.currentTimeMillis() - time;
		long cooldown = config.getConfig().getLong("general.queue-cooldown-time");
		if (timeSince >= cooldown) {
			queueCooldown.remove(uniqueId);
			return false;
		}

		return true;
	}

	public synchronized void setCooldown(@NotNull UUID uniqueId) {
		queueCooldown.put(uniqueId, System.currentTimeMillis());
	}

	public synchronized int getPositionInQueue(@NotNull AuctionData auctionData) {
		int position = 1;
		for (AuctionData data : auctionQueue) {
			if (data == auctionData)
				return position;

			position++;
		}

		return 0;
	}

	public synchronized @Nullable AuctionData removeFirstItemFromQueue(@NotNull AuctionPlayer auctionPlayer) {
		Iterator<AuctionData> iterator = auctionQueue.iterator();
		while (iterator.hasNext()) {
			AuctionData data = iterator.next();
			if (data.getAuctioneer().getUniqueId().equals(auctionPlayer.getUniqueId())) {
				iterator.remove();
				logItemMessage(data, "Item removed from auction queue. Auctioneer: %s Amount: %d Item: %s NBT: %s");
				return data;
			}
		}

		return null;
	}

	@EventHandler
	public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
		UUID id = e.getPlayer().getUniqueId();

		Auction activeAuction = getActiveAuction();
		if (activeAuction == null)
			return;

		if (!activeAuction.getAuctionData().getAuctioneer().getUniqueId().equals(id)
				&& !activeAuction.getBidList().playerHasAnyBids(id))
			return;

		String command = e.getMessage().split(" ")[0];

		for (String blockedCommand : config.getConfig().getStringList("auctions.blocked-commands")) {
			if (command.equalsIgnoreCase("/" + blockedCommand)) {
				e.setCancelled(true);
				messages.sendAuctionMessage(e.getPlayer(), "auction.blocked_command", activeAuction);

				return;
			}
		}
	}

	/***
	 * Queues the auction to run
	 * @param auctionData the auction data associated with the auction
	 * @return true if queued, false if executing immediately
	 */
	public synchronized boolean queueAuction(@NotNull AuctionData auctionData) {
		if (hasActiveAuction() || auctionQueue.size() != 0) {
			auctionQueue.add(auctionData);
			logItemMessage(auctionData,
					"Item added to auction queue. Auctioneer: %s Amount: %d Item: %s NBT: %s");
			return true;
		}

		int delay = config.getConfig().getInt("general.time-between");
		long timeSinceLastAuction = System.currentTimeMillis() - lastAuctionEndTimeMillis;
		if (timeSinceLastAuction < delay * 1000L) {
			auctionQueue.add(auctionData);
			logItemMessage(auctionData,
					"Item added to auction queue. Auctioneer: %s Amount: %d Item: %s NBT: %s");
			pullNextAuctionFromQueue();
			return true;
		}

		Auction auction = auctionProvider.get();
		activeAuction = auction;
		auction.startAuction(auctionData, this::handleAuctionCompleted);
		logItemMessage(auctionData, "Item starting in auction. Auctioneer: %s Amount: %d Item: %s NBT: %s");
		return false;
	}

	public synchronized void shutdown() {
		for (AuctionData queued : auctionQueue) {
			rewards.createItemReward(queued.getAuctioneer().getUniqueId(), queued.getId(),
					queued.getItem(), queued.getAmount(), queued.getWorld());
			cancelRecord(queued.getId(), "SELLER_MAILBOX", "NONE");
		}

		auctionQueue.clear();

		Auction activeAuction = getActiveAuction();
		if (activeAuction != null) {
			activeAuction.cancelAuctionShutdown();
		}
	}

	private void handleAuctionCompleted() {
		synchronized (this) {
			activeAuction = null;
			lastAuctionEndTimeMillis = System.currentTimeMillis();
			pullNextAuctionFromQueue();
		}
	}

	private synchronized void pullNextAuctionFromQueue() {
		if (auctionQueue.isEmpty())
			return;

		int delay = config.getConfig().getInt("general.time-between");
		if (lastAuctionEndTimeMillis != 0) {
			long timeSinceLastAuction = (System.currentTimeMillis() - lastAuctionEndTimeMillis) / 1000;
			if (timeSinceLastAuction < delay) {
				delay -= timeSinceLastAuction;
			}
		}

		scheduler.runAsyncDelayedTask(
				() -> scheduler.runSyncTask(() -> withSync(this::startNextAuctionFromQueue)), delay);
	}

	private synchronized void startNextAuctionFromQueue() {
		if (auctionQueue.isEmpty())
			return;

		AuctionData nextAuctionData = auctionQueue.remove();
		Auction nextAuction = auctionProvider.get();
		activeAuction = nextAuction;
		nextAuction.startAuction(nextAuctionData, this::handleAuctionCompleted);
		logItemMessage(nextAuctionData, "Item starting in auction. Auctioneer: %s Amount: %d Item: %s NBT: %s");
	}

	private void logItemMessage(AuctionData data, String message) {
		if (!config.getConfig().getBoolean("general.log-items-to-console"))
			return;

		String itemNbt;
		try {
			itemNbt = ItemHelper.getItemNBT(data.getItem());
		} catch (Exception e) {
			logger.warning("Unable to get auction item's NBT! Exception: " + e);
			itemNbt = "{}";
		}

		logger.info(String.format(
				message,
				data.getAuctioneer().getOfflinePlayer().getName(),
				data.getAmount(),
				data.getItem().getType(),
				itemNbt));
	}

	public synchronized @Nullable AuctionData getQueuedAuction(@NotNull UUID auctionId) {
		return auctionQueue.stream().filter(data -> data.getId().equals(auctionId)).findFirst().orElse(null);
	}

	public synchronized boolean ownsActiveOrQueuedAuction(@NotNull UUID playerId) {
		if (activeAuction != null
				&& activeAuction.getAuctionData().getAuctioneer().getUniqueId().equals(playerId)) {
			return true;
		}
		return auctionQueue.stream()
				.anyMatch(data -> data.getAuctioneer().getUniqueId().equals(playerId));
	}

	public synchronized int getQueuePosition(@NotNull UUID auctionId) {
		int position = 1;
		for (AuctionData data : auctionQueue) {
			if (data.getId().equals(auctionId)) {
				return position;
			}
			position++;
		}
		return 0;
	}

	public synchronized long estimateStartAtMillis(@NotNull UUID auctionId) {
		long seconds = 0L;
		if (activeAuction != null) {
			seconds += Math.max(0, activeAuction.getRemainingSeconds());
			seconds += config.getConfig().getInt("general.time-between");
		}
		for (AuctionData data : auctionQueue) {
			if (data.getId().equals(auctionId)) {
				return System.currentTimeMillis() + seconds * 1000L;
			}
			seconds += data.getStartingAuctionTime();
			seconds += config.getConfig().getInt("general.time-between");
		}
		return 0L;
	}

	public synchronized @Nullable AuctionData cancelQueuedAuction(@NotNull UUID auctionId,
	                                                              @NotNull UUID ownerId) {
		Iterator<AuctionData> iterator = auctionQueue.iterator();
		while (iterator.hasNext()) {
			AuctionData data = iterator.next();
			if (!data.getId().equals(auctionId)
					|| !data.getAuctioneer().getUniqueId().equals(ownerId)) {
				continue;
			}
			iterator.remove();
			rewards.createItemReward(ownerId, data.getId(), data.getItem(), data.getAmount(), data.getWorld());
			cancelRecord(data.getId(), "SELLER_MAILBOX", "NONE");
			return data;
		}
		return null;
	}

	public synchronized boolean cancelActiveAuction(@NotNull UUID auctionId, @NotNull UUID ownerId,
	                                                boolean refundListingFee) {
		if (activeAuction == null
				|| !activeAuction.getAuctionData().getId().equals(auctionId)
				|| !activeAuction.getAuctionData().getAuctioneer().getUniqueId().equals(ownerId)) {
			return false;
		}
		activeAuction.cancelAuction(refundListingFee);
		return true;
	}

	private void recoverPersistedAuctions() {
		database.getAuctionsByStatus(List.of(AuctionRecordStatus.PREPARING,
						AuctionRecordStatus.ACTIVE, AuctionRecordStatus.QUEUED))
				.thenAccept(records -> {
					for (AuctionRecord record : records) {
						switch (record.getStatus()) {
							case PREPARING -> quarantineInterruptedPreparation(record);
							case ACTIVE -> recoverInterruptedActiveAuction(record);
							case QUEUED -> restoreQueuedAuction(record);
							default -> {
							}
						}
					}
				})
				.exceptionally(error -> {
					logger.severe("Could not recover persisted auctions",
							error instanceof Exception exception ? exception : new RuntimeException(error));
					return null;
				});
	}

	private void quarantineInterruptedPreparation(@NotNull AuctionRecord record) {
		logger.warning("Auction " + record.getId() + " for " + record.getAuctioneerId()
				+ " was interrupted while moving inventory into escrow. It was cancelled as MANUAL_REVIEW "
				+ "without automatically creating an item reward to avoid duplication.");
		record.cancel("MANUAL_REVIEW", "NONE");
		database.saveAuctionRecord(record);
	}

	private void recoverInterruptedActiveAuction(@NotNull AuctionRecord record) {
		java.util.concurrent.CompletableFuture<Void> itemRecovery;
		try {
			itemRecovery = rewards.createItemReward(record.getAuctioneerId(), record.getId(), record.getItem(),
					record.getAmount(), record.getWorld());
		} catch (Exception exception) {
			logger.severe("Could not restore active auction item " + record.getId(), exception);
			return;
		}

		itemRecovery.thenCompose(ignored -> database.getBidRecords(record.getId())).thenAccept(bids -> {
			Map<UUID, Long> highestByBidder = new HashMap<>();
			for (AuctionBidRecord bid : bids) {
				highestByBidder.merge(bid.getBidderId(), bid.getAmountMinor(), Math::max);
			}
			for (Map.Entry<UUID, Long> entry : highestByBidder.entrySet()) {
				rewards.createMoneyReward(entry.getKey(), record.getId(), RewardKind.REFUND, entry.getValue());
			}
			database.transitionAuction(record.getId(), AuctionRecordStatus.ACTIVE, AuctionRecordStatus.CANCELLED);
		}).exceptionally(error -> {
			logger.severe("Could not recover active auction " + record.getId(),
					error instanceof Exception exception ? exception : new RuntimeException(error));
			return null;
		});
	}

	private void restoreQueuedAuction(@NotNull AuctionRecord record) {
		playerController.getPlayer(record.getAuctioneerId()).thenAccept(auctionPlayer -> {
			try {
				AuctionData data = new AuctionData(record.getId(), auctionPlayer, record.getItem(),
						record.getAmount(), record.getDurationSeconds(), record.getStartingPriceMinor(),
						record.getIncrementMinor(), record.getAutoBuyMinor(), record.isSealed(), record.getWorld());
				data.gatherAdditionalData(logger);
				scheduler.runSyncTask(() -> queueAuction(data));
			} catch (Exception exception) {
				logger.severe("Could not restore queued auction " + record.getId(), exception);
				record.cancel("MANUAL_REVIEW", "NONE");
				database.saveAuctionRecord(record);
			}
		}).exceptionally(error -> {
			logger.severe("Could not load auctioneer while restoring queued auction " + record.getId(),
					error instanceof Exception exception ? exception : new RuntimeException(error));
			return null;
		});
	}

	private void cancelRecord(@NotNull UUID auctionId, @NotNull String destination,
	                          @NotNull String refundStatus) {
		database.getAuctionRecord(auctionId).thenAccept(optional -> optional.ifPresent(record -> {
			record.cancel(destination, refundStatus);
			database.saveAuctionRecord(record);
		}));
	}
}
