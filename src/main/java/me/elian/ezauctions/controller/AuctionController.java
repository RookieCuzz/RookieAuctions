package me.elian.ezauctions.controller;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import me.elian.ezauctions.Logger;
import me.elian.ezauctions.data.Database;
import me.elian.ezauctions.helper.ItemHelper;
import me.elian.ezauctions.model.Auction;
import me.elian.ezauctions.model.AuctionBidRecord;
import me.elian.ezauctions.model.AuctionBidTransaction;
import me.elian.ezauctions.model.AuctionData;
import me.elian.ezauctions.model.BidAuthorization;
import me.elian.ezauctions.model.BidTransactionState;
import me.elian.ezauctions.model.AuctionPlayer;
import me.elian.ezauctions.model.AuctionRecord;
import me.elian.ezauctions.model.AuctionRecordStatus;
import me.elian.ezauctions.model.AuctionSessionLot;
import me.elian.ezauctions.model.AuctionSubmissionTransaction;
import me.elian.ezauctions.model.RewardKind;
import me.elian.ezauctions.model.RewardRecord;
import me.elian.ezauctions.model.SubmissionTransactionState;
import me.elian.ezauctions.scheduler.TaskScheduler;
import me.elian.ezauctions.session.LotState;
import me.elian.ezauctions.session.SubmissionResult;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
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
	private final CompletableFuture<Void> submissionRecovery;

	private boolean auctionsEnabled = true;
	private Auction activeAuction;
	private Runnable scheduledCompletionHook;
	private Function<AuctionData, CompletableFuture<SubmissionResult>> legacyAuctionRouter;
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
		recoverBidTransactions();
		submissionRecovery = recoverSubmissionTransactions();
		submissionRecovery.whenComplete((ignored, error) -> {
			if (error != null) {
				logger.severe("Submission recovery did not complete; scheduled session bootstrap "
						+ "will remain fail-closed", asException(error));
				return;
			}
			recoverPersistedAuctions();
		});
	}

	/** Completion barrier consumed by the session orchestrator before it counts or locks lots. */
	public @NotNull CompletableFuture<Void> submissionRecovery() {
		return submissionRecovery.thenApply(ignored -> null);
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
	/**
	 * @deprecated Immersive submissions must use the persistent session reservation service. While
	 * immersive mode is enabled this compatibility entry point is routed asynchronously through that
	 * same capacity boundary and never starts or appends to the legacy in-memory queue.
	 */
	@Deprecated(since = "3.0", forRemoval = false)
	public synchronized boolean queueAuction(@NotNull AuctionData auctionData) {
		if (config.getConfig().getBoolean("immersive.enabled", false)) {
			Function<AuctionData, CompletableFuture<SubmissionResult>> router = legacyAuctionRouter;
			if (router == null) {
				logger.severe("Rejected legacy queueAuction for " + auctionData.getId()
						+ ": immersive session router is not installed");
				return true;
			}
			try {
				CompletableFuture<SubmissionResult> routed = router.apply(auctionData);
				if (routed == null) {
					logger.severe("Rejected legacy queueAuction for " + auctionData.getId()
							+ ": immersive session router returned no future");
					return true;
				}
				routed.whenComplete((result, error) -> {
					if (error != null) {
						logger.severe("Could not route legacy auction " + auctionData.getId()
								+ " into an immersive session", asException(error));
					} else if (result == null || !result.accepted()) {
						logger.severe("Rejected legacy auction " + auctionData.getId()
								+ " because no immersive session capacity was available"
								+ (result == null ? "" : " (" + result.status() + ")"));
					}
				});
			} catch (RuntimeException error) {
				logger.severe("Could not invoke immersive router for legacy auction "
						+ auctionData.getId(), error);
			}
			// Legacy callers only understand immediate-vs-queued. Routing is durable and asynchronous.
			return true;
		}
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

	/** Installs the fail-closed compatibility router owned by the persistent session controller. */
	public synchronized void installLegacyAuctionRouter(
			@NotNull Function<AuctionData, CompletableFuture<SubmissionResult>> router) {
		legacyAuctionRouter = Objects.requireNonNull(router, "router");
	}

	/** Removes the compatibility router during orderly shutdown. */
	public synchronized void clearLegacyAuctionRouter() {
		legacyAuctionRouter = null;
	}

	/** Starts a single lot controlled by the persistent session orchestrator. */
	public synchronized boolean startScheduledAuction(@NotNull AuctionData auctionData,
	                                                  @NotNull Runnable completionHook) {
		return startScheduledAuction("legacy/" + auctionData.getId(), auctionData.getId(), auctionData,
				BidAuthorization.DENY_ALL, completionHook);
	}

	/** Starts a bound session lot with a synchronous, fail-closed bid authorization policy. */
	public synchronized boolean startScheduledAuction(@NotNull String sessionId, @NotNull UUID lotId,
	                                                  @NotNull AuctionData auctionData,
	                                                  @NotNull BidAuthorization authorization,
	                                                  @NotNull Runnable completionHook) {
		if (activeAuction != null) {
			return false;
		}
		Auction auction = auctionProvider.get();
		auction.bindScheduledSession(sessionId, lotId, authorization);
		activeAuction = auction;
		scheduledCompletionHook = completionHook;
		auction.startAuction(auctionData, this::handleAuctionCompleted);
		logItemMessage(auctionData,
				"Scheduled auction lot starting. Auctioneer: %s Amount: %d Item: %s NBT: %s");
		return true;
	}

	/** Restores a running session lot without charging any bidder a second time. */
	public synchronized boolean restoreScheduledAuction(@NotNull AuctionData auctionData,
	                                                    @NotNull List<me.elian.ezauctions.model.Bid> bids,
	                                                    int remainingSeconds, int antiSnipeRuns,
	                                                    long restoredRevision,
	                                                    @NotNull Runnable completionHook) {
		return restoreScheduledAuction("legacy/" + auctionData.getId(), auctionData.getId(),
				auctionData, bids, remainingSeconds, antiSnipeRuns, restoredRevision,
				BidAuthorization.DENY_ALL, completionHook);
	}

	/** Restores a bound session lot while retaining the same hot-path bid authorization. */
	public synchronized boolean restoreScheduledAuction(@NotNull String sessionId, @NotNull UUID lotId,
	                                                    @NotNull AuctionData auctionData,
	                                                    @NotNull List<me.elian.ezauctions.model.Bid> bids,
	                                                    int remainingSeconds, int antiSnipeRuns,
	                                                    long restoredRevision,
	                                                    @NotNull BidAuthorization authorization,
	                                                    @NotNull Runnable completionHook) {
		if (activeAuction != null) {
			return false;
		}
		Auction auction = auctionProvider.get();
		auction.bindScheduledSession(sessionId, lotId, authorization);
		activeAuction = auction;
		scheduledCompletionHook = completionHook;
		auction.restoreAuction(auctionData, bids, remainingSeconds, antiSnipeRuns,
				restoredRevision, this::handleAuctionCompleted);
		return true;
	}

	/** Suspends only a session-controlled timer so its durable checkpoint can be restored. */
	public synchronized boolean suspendScheduledAuction() {
		if (activeAuction == null || scheduledCompletionHook == null) {
			return false;
		}
		activeAuction.suspendForShutdown();
		activeAuction = null;
		scheduledCompletionHook = null;
		return true;
	}

	public synchronized void shutdown() {
		for (AuctionData queued : auctionQueue) {
			cancelQueuedRecord(queued, "SELLER_MAILBOX", "NONE");
		}

		auctionQueue.clear();

		Auction activeAuction = getActiveAuction();
		if (activeAuction != null) {
			if (scheduledCompletionHook != null) {
				suspendScheduledAuction();
			} else {
				activeAuction.cancelAuctionShutdown();
			}
		}
	}

	private void handleAuctionCompleted() {
		Runnable hook;
		synchronized (this) {
			activeAuction = null;
			lastAuctionEndTimeMillis = System.currentTimeMillis();
			hook = scheduledCompletionHook;
			scheduledCompletionHook = null;
			if (hook == null) {
				pullNextAuctionFromQueue();
			}
		}
		if (hook != null) {
			hook.run();
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
			cancelQueuedRecord(data, "SELLER_MAILBOX", "NONE");
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

	private void recoverBidTransactions() {
		database.getBidTransactions(List.of(BidTransactionState.PREPARED,
					BidTransactionState.WITHDRAWING, BidTransactionState.WITHDRAWN))
				.thenAccept(transactions -> transactions.forEach(this::reconcileBidTransaction))
				.exceptionally(error -> {
					logger.severe("Could not scan unfinished bid transactions", asException(error));
					return null;
				});
	}

	private @NotNull CompletableFuture<Void> recoverSubmissionTransactions() {
		List<SubmissionTransactionState> unfinished = List.of(
				SubmissionTransactionState.PREPARED,
				SubmissionTransactionState.FEE_WITHDRAWING,
				SubmissionTransactionState.FEE_WITHDRAWN,
				SubmissionTransactionState.ITEM_ESCROWING,
				SubmissionTransactionState.ITEM_ESCROWED,
				SubmissionTransactionState.FAILED);
		return database.getSubmissionTransactions(unfinished)
				.thenCompose(transactions -> {
					List<CompletableFuture<Void>> recoveries = new ArrayList<>(transactions.size());
					for (AuctionSubmissionTransaction transaction : transactions) {
						recoveries.add(reconcileSubmissionTransaction(transaction));
					}
					return CompletableFuture.allOf(recoveries.toArray(CompletableFuture[]::new));
				});
	}

	private @NotNull CompletableFuture<Void> reconcileSubmissionTransaction(
			@NotNull AuctionSubmissionTransaction transaction) {
		SubmissionTransactionState state = transaction.getState();
		if (state == SubmissionTransactionState.FEE_WITHDRAWING
				|| state == SubmissionTransactionState.ITEM_ESCROWING) {
			logger.severe("Recovering uncertain submission transaction " + transaction.getId()
					+ " for seller " + transaction.getSellerId() + " from " + state
					+ "; deterministic mailbox compensation will favor preventing player loss");
		}
		return database.getAuctionRecord(transaction.getAuctionId()).thenCompose(optional -> {
			if (optional.isEmpty()) {
				return CompletableFuture.failedFuture(new IllegalStateException(
						"Submission journal has no auction record: " + transaction.getId()));
			}
			AuctionRecordStatus auctionState = optional.get().getStatus();
			if (auctionState == AuctionRecordStatus.ACTIVE
					|| auctionState == AuctionRecordStatus.COMPLETED) {
				return database.transitionSubmissionTransaction(transaction.getId(), state,
						SubmissionTransactionState.COMMITTED,
						"recovered from published auction", System.currentTimeMillis())
						.thenCompose(changed -> requireRecoveryChange(changed, transaction,
								"mark published submission committed"));
			}
			if (state == SubmissionTransactionState.ITEM_ESCROWED
					&& (auctionState == AuctionRecordStatus.PREPARING
					|| auctionState == AuctionRecordStatus.QUEUED)) {
				return database.commitSubmissionTransaction(transaction.getId(),
						System.currentTimeMillis()).thenCompose(committed -> requireRecoveryChange(
						committed, transaction, "commit escrowed submission"));
			}
			String reason = "server restarted during submission phase " + state;
			return database.compensateSubmissionTransaction(transaction.getId(), reason,
					System.currentTimeMillis()).thenCompose(compensated -> requireRecoveryChange(
					compensated, transaction, "compensate interrupted submission"));
		});
	}

	private static @NotNull CompletableFuture<Void> requireRecoveryChange(
			Boolean changed, @NotNull AuctionSubmissionTransaction transaction,
			@NotNull String action) {
		if (Boolean.TRUE.equals(changed)) {
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFuture.failedFuture(new IllegalStateException(
				"Could not " + action + " " + transaction.getId() + " because its state changed"));
	}

	private void reconcileBidTransaction(@NotNull AuctionBidTransaction transaction) {
		switch (transaction.getState()) {
			case PREPARED -> database.transitionBidTransaction(transaction.getId(),
					BidTransactionState.PREPARED, BidTransactionState.FAILED,
					"server restarted before Vault withdrawal", System.currentTimeMillis())
					.exceptionally(error -> {
						logger.severe("Could not close unstarted bid transaction "
								+ transaction.getId(), asException(error));
						return false;
					});
			case WITHDRAWING -> compensateRecoveredBid(transaction,
					"uncertain Vault withdrawal interrupted by restart");
			case WITHDRAWN -> database.getBidRecord(transaction.getId())
					.whenComplete((bidRecord, error) -> {
						if (error != null) {
							logger.severe("Could not inspect bid record for transaction "
									+ transaction.getId(), asException(error));
							return;
						}
						if (bidRecord.isPresent()) {
							database.transitionBidTransaction(transaction.getId(),
									BidTransactionState.WITHDRAWN, BidTransactionState.COMMITTED,
									"recovered from durable bid record", System.currentTimeMillis())
									.exceptionally(transitionError -> {
										logger.severe("Could not commit recovered bid transaction "
												+ transaction.getId(), asException(transitionError));
										return false;
									});
						} else {
							compensateRecoveredBid(transaction,
									"withdrawal persisted without a durable bid record");
						}
					});
			default -> {
			}
		}
	}

	private void compensateRecoveredBid(@NotNull AuctionBidTransaction transaction,
	                                    @NotNull String reason) {
		logger.severe("Compensating uncertain bid transaction " + transaction.getId()
				+ " for player " + transaction.getBidderId() + " (" + reason
				+ "). A deterministic mailbox refund will be created.");
		database.compensateBidTransaction(transaction.getId(), reason, System.currentTimeMillis())
				.whenComplete((compensated, error) -> {
					if (error != null || !Boolean.TRUE.equals(compensated)) {
						logger.severe("Could not compensate bid transaction " + transaction.getId(),
								error == null ? new IllegalStateException("transaction state changed")
									: asException(error));
					}
				});
	}

	private void recoverPersistedAuctions() {
		database.getAuctionsByStatus(List.of(AuctionRecordStatus.PREPARING,
						AuctionRecordStatus.ACTIVE, AuctionRecordStatus.QUEUED))
				.thenCombine(checkpointProtectedAuctionIds(), Map::entry)
				.thenAccept(recovery -> {
					List<AuctionRecord> records = recovery.getKey();
					Set<UUID> checkpointProtected = recovery.getValue();
					for (AuctionRecord record : records) {
						switch (record.getStatus()) {
							case PREPARING -> quarantineInterruptedPreparation(record);
							case ACTIVE -> {
								if (!checkpointProtected.contains(record.getId())) {
									recoverInterruptedActiveAuction(record);
								}
							}
							case QUEUED -> {
								// The persistent session orchestrator migrates and orders queued lots.
							}
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

	/** Scheduled ACTIVE records are owned by the session recovery path, not legacy refund recovery. */
	private CompletableFuture<Set<UUID>> checkpointProtectedAuctionIds() {
		return database.getSessionsByState(List.of(me.elian.ezauctions.session.SessionState.RUNNING))
				.thenCompose(sessions -> {
					List<CompletableFuture<Set<UUID>>> lookups = new ArrayList<>();
					for (me.elian.ezauctions.model.AuctionSessionRecord session : sessions) {
						CompletableFuture<List<AuctionSessionLot>> sessionLots =
								database.getSessionLots(session.getId());
						CompletableFuture<Optional<AuctionSessionLot>> checkpointLot =
								database.getRuntimeCheckpoint(session.getId()).thenCompose(checkpoint -> {
									if (checkpoint.isEmpty()
											|| checkpoint.get().getCurrentLotId() == null) {
										return CompletableFuture.completedFuture(Optional.empty());
									}
									return database.getSessionLot(checkpoint.get().getCurrentLotId());
								});
						lookups.add(sessionLots.thenCombine(checkpointLot,
								AuctionController::protectedAuctionIds));
					}
					return CompletableFuture.allOf(lookups.toArray(CompletableFuture[]::new))
							.thenApply(ignored -> {
								Set<UUID> protectedIds = new HashSet<>();
								for (CompletableFuture<Set<UUID>> lookup : lookups) {
									protectedIds.addAll(lookup.join());
								}
								return Set.copyOf(protectedIds);
							});
				});
	}

	static Set<UUID> protectedAuctionIds(@NotNull Collection<AuctionSessionLot> sessionLots,
	                                      @NotNull Optional<AuctionSessionLot> checkpointLot) {
		Set<UUID> protectedIds = new HashSet<>();
		for (AuctionSessionLot lot : sessionLots) {
			// startScheduledAuction persists the legacy AuctionRecord as ACTIVE before the
			// first per-second checkpoint. Protect that short no-checkpoint recovery window.
			if (lot.getState() == LotState.ACTIVE) {
				protectedIds.add(lot.getAuctionId());
			}
		}
		checkpointLot.map(AuctionSessionLot::getAuctionId).ifPresent(protectedIds::add);
		return Set.copyOf(protectedIds);
	}

	private void quarantineInterruptedPreparation(@NotNull AuctionRecord record) {
		logger.warning("Auction " + record.getId() + " for " + record.getAuctioneerId()
				+ " was interrupted while moving inventory into escrow. It was cancelled as MANUAL_REVIEW "
				+ "without automatically creating an item reward to avoid duplication.");
		record.cancel("MANUAL_REVIEW", "NONE");
		database.saveAuctionRecord(record);
	}

	private void recoverInterruptedActiveAuction(@NotNull AuctionRecord record) {
		RewardRecord itemRecovery;
		try {
			itemRecovery = RewardRecord.item(record.getAuctioneerId(), record.getId(), record.getItem(),
					record.getAmount(), record.getWorld());
		} catch (Exception exception) {
			logger.severe("Could not restore active auction item " + record.getId(), exception);
			return;
		}

		database.getBidRecords(record.getId()).thenCompose(bids -> {
			Map<UUID, Long> highestByBidder = new HashMap<>();
			for (AuctionBidRecord bid : bids) {
				highestByBidder.merge(bid.getBidderId(), bid.getAmountMinor(), Math::max);
			}
			List<RewardRecord> recoveryRewards = new ArrayList<>();
			recoveryRewards.add(itemRecovery);
			long listingFeeMinor = me.elian.ezauctions.model.Money.fromMajor(
					config.getConfig().getDouble("auctions.fees.start-price"));
			if (listingFeeMinor > 0) {
				highestByBidder.merge(record.getAuctioneerId(), listingFeeMinor, Math::addExact);
			}
			for (Map.Entry<UUID, Long> entry : highestByBidder.entrySet()) {
				if (entry.getValue() > 0) {
					recoveryRewards.add(RewardRecord.money(entry.getKey(), record.getId(),
							RewardKind.REFUND, entry.getValue()));
				}
			}
			return rewards.cancelAuctionWithRewards(record.getId(), List.of(AuctionRecordStatus.ACTIVE),
					"SELLER_MAILBOX", "MAILBOX", System.currentTimeMillis(), recoveryRewards);
		}).thenAccept(cancelled -> {
			if (!Boolean.TRUE.equals(cancelled)) {
				logger.severe("Could not atomically recover active auction " + record.getId()
						+ " because its lifecycle state changed");
			}
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

	private void cancelQueuedRecord(@NotNull AuctionData data, @NotNull String destination,
	                                @NotNull String refundStatus) {
		RewardRecord itemReward = RewardRecord.item(data.getAuctioneer().getUniqueId(), data.getId(),
				data.getItem(), data.getAmount(), data.getWorld());
		rewards.cancelAuctionWithRewards(data.getId(), List.of(AuctionRecordStatus.QUEUED),
				destination, refundStatus, System.currentTimeMillis(), List.of(itemReward))
				.whenComplete((cancelled, error) -> {
					if (error != null || !Boolean.TRUE.equals(cancelled)) {
						logger.severe("Could not atomically cancel queued auction " + data.getId(),
								error == null ? new IllegalStateException("auction state changed")
									: asException(error));
					}
				});
	}

	private static @NotNull Exception asException(@NotNull Throwable error) {
		return error instanceof Exception exception ? exception : new RuntimeException(error);
	}
}
