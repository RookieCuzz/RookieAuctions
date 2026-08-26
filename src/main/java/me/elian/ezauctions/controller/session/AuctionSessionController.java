package me.elian.ezauctions.controller.session;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.elian.ezauctions.Logger;
import me.elian.ezauctions.controller.AuctionController;
import me.elian.ezauctions.controller.AuctionPlayerController;
import me.elian.ezauctions.controller.ConfigController;
import me.elian.ezauctions.data.Database;
import me.elian.ezauctions.event.AuctionLotFinishedEvent;
import me.elian.ezauctions.event.AuctionLotStartEvent;
import me.elian.ezauctions.event.AuctionSessionEndEvent;
import me.elian.ezauctions.event.AuctionSessionStartEvent;
import me.elian.ezauctions.immersive.AttendanceResult;
import me.elian.ezauctions.immersive.AttendanceService;
import me.elian.ezauctions.immersive.AttendanceSessionPolicy;
import me.elian.ezauctions.immersive.VenueConfig;
import me.elian.ezauctions.immersive.VenueDisplayController;
import me.elian.ezauctions.immersive.VenueDisplayState;
import me.elian.ezauctions.model.Auction;
import me.elian.ezauctions.model.AuctionBidRecord;
import me.elian.ezauctions.model.AuctionData;
import me.elian.ezauctions.model.AuctionPlayer;
import me.elian.ezauctions.model.AuctionRecord;
import me.elian.ezauctions.model.AuctionRecordStatus;
import me.elian.ezauctions.model.AuctionRuntimeCheckpoint;
import me.elian.ezauctions.model.AuctionSessionLot;
import me.elian.ezauctions.model.AuctionSessionRecord;
import me.elian.ezauctions.model.AuctionView;
import me.elian.ezauctions.model.Bid;
import me.elian.ezauctions.model.BidAuthorization;
import me.elian.ezauctions.model.Money;
import me.elian.ezauctions.scheduler.CancellableTask;
import me.elian.ezauctions.scheduler.TaskScheduler;
import me.elian.ezauctions.session.AuctionMode;
import me.elian.ezauctions.session.AuctionPublicLotView;
import me.elian.ezauctions.session.AuctionSessionView;
import me.elian.ezauctions.session.LotState;
import me.elian.ezauctions.session.PlannedSession;
import me.elian.ezauctions.session.PublicBidPrice;
import me.elian.ezauctions.session.ReservationStatus;
import me.elian.ezauctions.session.ScheduleDefinition;
import me.elian.ezauctions.session.ScheduledSessionReference;
import me.elian.ezauctions.session.SessionEtaCalculator;
import me.elian.ezauctions.session.SessionProgress;
import me.elian.ezauctions.session.SessionRunPhase;
import me.elian.ezauctions.session.SessionSchedulePlanner;
import me.elian.ezauctions.session.SessionState;
import me.elian.ezauctions.session.SubmissionResult;
import me.elian.ezauctions.session.WithdrawalResult;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Persistent wall-clock session orchestrator for the single immersive venue.
 *
 * <p>All Bukkit and single-lot engine mutations run on the server thread. Database work is
 * asynchronous and every lifecycle transition uses the database's compare-and-set APIs, so a
 * delayed callback or duplicate scheduler tick cannot start the same session/lot twice.</p>
 */
@Singleton
public final class AuctionSessionController implements AttendanceSessionPolicy, BidAuthorization {
	private static final int ROLLOVER_SEARCH_SESSIONS = 64;
	private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("MM-dd");

	private final Plugin plugin;
	private final Logger logger;
	private final Database database;
	private final AuctionController auctions;
	private final AuctionPlayerController players;
	private final ConfigController config;
	private final TaskScheduler scheduler;
	private final VenueConfig venueConfig;
	private final VenueDisplayController venueDisplay;
	private final AttendanceService attendance;
	private final Clock clock;
	private final AtomicBoolean started = new AtomicBoolean();
	private final AtomicBoolean bootstrapComplete = new AtomicBoolean();
	private final AtomicBoolean maintenanceInFlight = new AtomicBoolean();
	private final AtomicBoolean legacyMigrationComplete = new AtomicBoolean();
	private final Map<String, KnownSession> knownSessions = new ConcurrentHashMap<>();

	private volatile SessionConfigSnapshot sessionConfig;
	private volatile SessionSchedulePlanner planner;
	private volatile RuntimeSession activeRuntime;
	private volatile AuctionSessionView activeView;
	private volatile AuctionPublicLotView currentLotView;
	private volatile CancellableTask tickTask;
	private volatile long lastBlockedRetryMillis;
	private volatile long lastRunningRecoveryRetryMillis;
	private volatile boolean runningRecoveryBlocked;
	private volatile long lastIdleRenderMillis;

	@Inject
	public AuctionSessionController(@NotNull Plugin plugin, @NotNull Logger logger,
	                                @NotNull Database database, @NotNull AuctionController auctions,
	                                @NotNull AuctionPlayerController players,
	                                @NotNull ConfigController config, @NotNull TaskScheduler scheduler,
	                                @NotNull VenueConfig venueConfig,
	                                @NotNull VenueDisplayController venueDisplay,
	                                @NotNull AttendanceService attendance) {
		this(plugin, logger, database, auctions, players, config, scheduler, venueConfig,
				venueDisplay, attendance, Clock.systemUTC());
	}

	AuctionSessionController(@NotNull Plugin plugin, @NotNull Logger logger,
	                         @NotNull Database database, @NotNull AuctionController auctions,
	                         @NotNull AuctionPlayerController players,
	                         @NotNull ConfigController config, @NotNull TaskScheduler scheduler,
	                         @NotNull VenueConfig venueConfig,
	                         @NotNull VenueDisplayController venueDisplay,
	                         @NotNull AttendanceService attendance, @NotNull Clock clock) {
		this.plugin = plugin;
		this.logger = logger;
		this.database = database;
		this.auctions = auctions;
		this.players = players;
		this.config = config;
		this.scheduler = scheduler;
		this.venueConfig = venueConfig;
		this.venueDisplay = venueDisplay;
		this.attendance = attendance;
		this.clock = clock;
		this.sessionConfig = SessionConfigSnapshot.load(config, logger);
		this.planner = new SessionSchedulePlanner(sessionConfig.schedule(), clock);
	}

	/** Starts recovery before enabling registration/return recovery, and is idempotent. */
	public void start() {
		if (!started.compareAndSet(false, true)) {
			return;
		}
		auctions.installLegacyAuctionRouter(this::routeLegacySubmission);
		attendance.setSessionPolicy(this);
		venueDisplay.start();
		tickTask = scheduler.runSyncRepeatingTask(plugin, this::tick, 1L, 1L);
		auctions.submissionRecovery().whenComplete((ignored, error) -> scheduler.runSyncTask(() -> {
			if (!started.get()) {
				return;
			}
			if (error != null) {
				logFailure("recover interrupted submissions before session bootstrap", error);
				return;
			}
			bootstrap();
		}));
	}

	/**
	 * Persists the latest runtime position and suspends (rather than settles) a scheduled lot.
	 * This must be called before the shared scheduler and database are shut down.
	 */
	public void shutdown() {
		if (!started.compareAndSet(true, false)) {
			return;
		}
		CancellableTask task = tickTask;
		tickTask = null;
		if (task != null) {
			task.cancel();
		}
		RuntimeSession runtime = activeRuntime;
		if (runtime != null) {
			persistCheckpoint(runtime);
			auctions.suspendScheduledAuction();
		}
		auctions.clearLegacyAuctionRouter();
		attendance.shutdown();
		venueDisplay.shutdown();
		maintenanceInFlight.set(false);
	}

	/** New settings affect sessions generated after reload; persisted windows remain immutable. */
	public void reloadSchedule() {
		SessionConfigSnapshot loaded = SessionConfigSnapshot.load(config, logger);
		sessionConfig = loaded;
		planner = new SessionSchedulePlanner(loaded.schedule(), clock);
	}

	/**
	 * Clears the blocked-venue backoff and asks the scheduler to re-evaluate persisted sessions now.
	 * Safe to call after an administrator validates or enables a repaired venue.
	 */
	public void requestImmediateMaintenance() {
		lastBlockedRetryMillis = 0L;
		lastRunningRecoveryRetryMillis = 0L;
		requestMaintenance();
	}

	/** Backwards-readable alias for venue administration integrations. */
	public void retryBlockedNow() {
		requestImmediateMaintenance();
	}

	public @NotNull List<PlannedSession> futureSubmissionSessions() {
		return planner.nextSubmissionSessions(clock.instant());
	}

	public @NotNull CompletableFuture<List<AuctionSessionView>> futureSessionViews() {
		List<PlannedSession> plans = futureSubmissionSessions();
		return ensurePlannedSessions(plans, clock.instant())
				.thenCompose(ignored -> mapSequential(plans, plan -> database.getSession(plan.key())
						.thenCompose(optional -> optional.isEmpty()
								? CompletableFuture.completedFuture(null)
								: view(optional.get()).thenApply(Optional::orElseThrow))))
				.thenApply(views -> views.stream().filter(Objects::nonNull).toList());
	}

	public @NotNull CompletableFuture<Optional<AuctionSessionView>> sessionView(
			@NotNull String sessionId) {
		return database.getSession(sessionId).thenCompose(optional -> optional.isEmpty()
				? CompletableFuture.completedFuture(Optional.empty())
				: view(optional.get()));
	}

	public @NotNull Optional<AuctionSessionView> activeSession() {
		return Optional.ofNullable(activeView);
	}

	/**
	 * Returns the next immutable scheduled window even after its submission cutoff. This is the
	 * synchronous source for PAPI/admin countdowns; submission-oriented planner results must not be
	 * used because they intentionally omit LOCKED windows.
	 */
	public @NotNull Optional<ScheduledSessionReference> nextScheduledSession() {
		RuntimeSession running = activeRuntime;
		if (running != null) {
			return Optional.of(new ScheduledSessionReference(running.record.getId(),
					Instant.ofEpochMilli(running.record.getScheduledStartMillis()),
					SessionState.RUNNING));
		}
		Optional<ScheduledSessionReference> known = knownSessions.entrySet().stream()
				.filter(entry -> entry.getValue().state() != SessionState.COMPLETED
						&& entry.getValue().state() != SessionState.SKIPPED)
				.map(entry -> new ScheduledSessionReference(entry.getKey(),
						Instant.ofEpochMilli(entry.getValue().scheduledStartMillis()),
						entry.getValue().state()))
				.min(Comparator.comparing(ScheduledSessionReference::scheduledStart));
		if (known.isPresent()) {
			return known;
		}

		// Only used during the brief bootstrap before persisted immutable rows have been loaded.
		Instant now = clock.instant();
		LocalDate today = now.atZone(planner.definition().zoneId()).toLocalDate();
		return List.of(today, today.plusDays(1)).stream()
				.flatMap(date -> planner.sessionsOn(date).stream())
				.filter(session -> !session.scheduledStart().isBefore(now))
				.findFirst()
				.map(session -> new ScheduledSessionReference(session.key(),
						session.scheduledStart(), session.timingAt(now) == me.elian.ezauctions.session.SessionTiming.OPEN
								? SessionState.OPEN : SessionState.LOCKED));
	}

	public @NotNull Optional<AuctionPublicLotView> currentLot() {
		return Optional.ofNullable(currentLotView);
	}

	public @NotNull Optional<String> activeSessionId() {
		RuntimeSession runtime = activeRuntime;
		return runtime == null ? Optional.empty() : Optional.of(runtime.record.getId());
	}

	public @NotNull Optional<SessionState> stateOf(@NotNull String sessionId) {
		KnownSession known = knownSessions.get(sessionId);
		return known == null ? Optional.empty() : Optional.of(known.state());
	}

	/** Reserves only session capacity; the caller must already have durably escrowed a QUEUED AuctionRecord. */
	public @NotNull CompletableFuture<SubmissionResult> reserveSubmission(
			@NotNull String sessionId, @NotNull UUID auctionId, @NotNull UUID sellerId) {
		return database.reserveSessionLot(sessionId, auctionId, sellerId, clock.millis());
	}

	/**
	 * Compatibility route for deprecated queueAuction callers. It uses the same transactional
	 * capacity/seller-limit reservation and promotes the reservation to QUEUED only after success.
	 */
	public @NotNull CompletableFuture<SubmissionResult> routeLegacySubmission(
			@NotNull AuctionData auctionData) {
		Objects.requireNonNull(auctionData, "auctionData");
		Instant now = clock.instant();
		List<PlannedSession> targets = planner.nextSubmissionSessions(now,
				ROLLOVER_SEARCH_SESSIONS);
		return ensureLegacyAuctionRecord(auctionData)
				.thenCompose(ignored -> ensurePlannedSessions(targets, now))
				.thenCompose(ignored -> database.getSessionLotsByAuctionId(auctionData.getId()))
				.thenCompose(existing -> {
					AuctionSessionLot assigned = existing.stream()
							.filter(lot -> lot.getState() != LotState.CANCELLED
									&& lot.getState() != LotState.DEFERRED)
							.findFirst().orElse(null);
					if (assigned != null) {
						return finishLegacyReservation(assigned).thenCompose(ready -> ready
								? existingSubmissionResult(assigned)
								: CompletableFuture.failedFuture(new IllegalStateException(
										"Existing session lot could not enter QUEUED state: "
												+ assigned.getId())));
					}
					return routeLegacyToFuture(auctionData, targets, 0, now.toEpochMilli(), null);
				});
	}

	private CompletableFuture<Void> ensureLegacyAuctionRecord(AuctionData data) {
		return database.getAuctionRecord(data.getId()).thenCompose(existing -> {
			if (existing.isPresent()) {
				return makeLegacyRecordQueueable(data, existing.get());
			}
			AuctionRecord created = new AuctionRecord(data.getId(),
					data.getAuctioneer().getUniqueId(), data.getItem(), data.getAmount(),
					data.isSealed(), data.getWorld(), data.getStartingPriceMinor(),
					data.getIncrementPriceMinor(), data.getAutoBuyPriceMinor(),
					data.getStartingAuctionTime());
			return database.createAuctionRecord(created)
					.handle((ignored, createError) -> createError)
					.thenCompose(createError -> {
						if (createError == null) {
							return makeLegacyRecordQueueable(data, created);
						}
						// Concurrent deprecated callers may both observe the missing row. Only one
						// creates it; the loser must validate the durable winner rather than bypass it.
						return database.getAuctionRecord(data.getId()).thenCompose(raced -> raced
								.map(record -> makeLegacyRecordQueueable(data, record))
								.orElseGet(() -> CompletableFuture.failedFuture(createError)));
					});
		});
	}

	private CompletableFuture<Void> makeLegacyRecordQueueable(AuctionData data,
	                                                          AuctionRecord record) {
		if (!record.getAuctioneerId().equals(data.getAuctioneer().getUniqueId())) {
			return CompletableFuture.failedFuture(new IllegalStateException(
					"Auction id " + data.getId() + " belongs to another seller"));
		}
		AuctionRecordStatus status = record.getStatus();
		if (!canRouteLegacyRecordStatus(status)) {
			return CompletableFuture.failedFuture(new IllegalStateException(
					"Auction " + data.getId() + " cannot be queued from " + status));
		}
		if (status == AuctionRecordStatus.QUEUED) {
			return CompletableFuture.completedFuture(null);
		}
		return database.transitionAuction(data.getId(), AuctionRecordStatus.PREPARING,
				AuctionRecordStatus.QUEUED).thenCompose(changed -> {
			if (Boolean.TRUE.equals(changed)) {
				return CompletableFuture.completedFuture(null);
			}
			return database.getAuctionRecord(data.getId()).thenCompose(latest -> {
				if (latest.isPresent() && latest.get().getStatus() == AuctionRecordStatus.QUEUED
						&& latest.get().getAuctioneerId().equals(data.getAuctioneer().getUniqueId())) {
					return CompletableFuture.completedFuture(null);
				}
				return CompletableFuture.failedFuture(new IllegalStateException(
						"Auction " + data.getId() + " changed state while entering QUEUED"));
			});
		});
	}

	static boolean canRouteLegacyRecordStatus(@NotNull AuctionRecordStatus status) {
		return status == AuctionRecordStatus.PREPARING || status == AuctionRecordStatus.QUEUED;
	}

	private CompletableFuture<SubmissionResult> routeLegacyToFuture(
			AuctionData auctionData, List<PlannedSession> targets, int targetIndex,
			long createdAtMillis, SubmissionResult lastRejection) {
		if (targetIndex >= targets.size()) {
			if (lastRejection != null) {
				return CompletableFuture.completedFuture(lastRejection);
			}
			return CompletableFuture.failedFuture(new IllegalStateException(
					"No future auction session windows are configured"));
		}
		PlannedSession target = targets.get(targetIndex);
		UUID sellerId = auctionData.getAuctioneer().getUniqueId();
		return database.reserveSessionLot(target.key(), auctionData.getId(), sellerId,
				createdAtMillis).thenCompose(result -> {
			if (!result.accepted()) {
				return routeLegacyToFuture(auctionData, targets, targetIndex + 1,
						createdAtMillis, result);
			}
			return database.getSessionLot(result.lotId()).thenCompose(optional -> {
				if (optional.isEmpty()) {
					return CompletableFuture.failedFuture(new IllegalStateException(
							"Reserved session lot disappeared: " + result.lotId()));
				}
				return finishLegacyReservation(optional.get()).thenCompose(ready -> ready
						? CompletableFuture.completedFuture(result)
						: CompletableFuture.failedFuture(new IllegalStateException(
								"Reserved session lot could not enter QUEUED state: "
										+ result.lotId())));
			});
		});
	}

	private CompletableFuture<Boolean> finishLegacyReservation(AuctionSessionLot lot) {
		if (lot.getState() != LotState.RESERVED) {
			return CompletableFuture.completedFuture(lot.getState() != LotState.CANCELLED
					&& lot.getState() != LotState.DEFERRED);
		}
		return database.transitionSessionLot(lot.getId(), LotState.RESERVED, LotState.QUEUED,
				clock.millis()).thenCompose(changed -> {
			if (Boolean.TRUE.equals(changed)) {
				return CompletableFuture.completedFuture(true);
			}
			return database.getSessionLot(lot.getId()).thenApply(latest -> latest
					.map(current -> current.getState() != LotState.RESERVED
							&& current.getState() != LotState.CANCELLED
							&& current.getState() != LotState.DEFERRED)
					.orElse(false));
		});
	}

	private CompletableFuture<SubmissionResult> existingSubmissionResult(AuctionSessionLot lot) {
		return database.getSession(lot.getSessionId()).thenCombine(
				database.getSessionLots(lot.getSessionId()), (session, lots) -> {
					if (session.isEmpty()) {
						return SubmissionResult.rejected(ReservationStatus.NOT_FOUND,
								lot.getSessionId(), 0, 0);
					}
					return SubmissionResult.success(lot.getSessionId(), lot.getId(),
							occupiedLots(lots).size(), session.get().getCapacity());
				});
	}

	/** Atomically closes the lot/auction and creates its deterministic mailbox item reward. */
	public @NotNull CompletableFuture<WithdrawalResult> withdrawSubmission(
			@NotNull String sessionId, @NotNull UUID auctionId, @NotNull UUID sellerId) {
		return database.withdrawSessionLot(sessionId, auctionId, sellerId, clock.millis())
				.exceptionally(error -> {
			logFailure("withdraw lot " + auctionId + " from " + sessionId, error);
			return new WithdrawalResult(WithdrawalResult.Status.PERSISTENCE_FAILED, sessionId, auctionId);
		});
	}

	public @NotNull CompletableFuture<AttendanceResult> registerBuyer(
			@NotNull String sessionId, @NotNull UUID playerId) {
		return attendance.register(sessionId, playerId);
	}

	public @NotNull CompletableFuture<AttendanceResult> unregisterBuyer(
			@NotNull String sessionId, @NotNull UUID playerId) {
		return attendance.unregister(sessionId, playerId);
	}

	@Override
	public boolean canRegister(@NotNull String sessionId) {
		KnownSession known = knownSessions.get(sessionId);
		return known != null && known.state() == SessionState.OPEN
				&& clock.millis() < known.lockAtMillis();
	}

	@Override
	public boolean isRunning(@NotNull String sessionId) {
		RuntimeSession runtime = activeRuntime;
		return runtime != null && runtime.phase != RuntimePhase.COMPLETING
				&& runtime.record.getId().equals(sessionId);
	}

	@Override
	public @NotNull BidAuthorization.Decision authorize(@NotNull String sessionId,
	                                                   @NotNull Player player) {
		if (!isRunning(sessionId)) {
			return BidAuthorization.Decision.SESSION_NOT_RUNNING;
		}
		if (!attendance.isActive(player.getUniqueId())
				|| attendance.activeSession(player.getUniqueId())
						.filter(sessionId::equals).isEmpty()) {
			return BidAuthorization.Decision.NOT_PARTICIPANT;
		}
		return attendance.isInsideVenue(player)
				? BidAuthorization.Decision.ALLOWED
				: BidAuthorization.Decision.NOT_IN_VENUE;
	}

	private void bootstrap() {
		Instant now = clock.instant();
		ensureCalendar(now)
				.thenCompose(ignored -> refreshKnownSessions())
				.thenCompose(ignored -> recoverRunningSession())
				.thenCompose(ignored -> migrateLegacyQueuedAuctions())
				.whenComplete((ignored, error) -> scheduler.runSyncTask(() -> {
					if (!started.get()) {
						return;
					}
					if (error != null) {
						logFailure("bootstrap persistent auction sessions", error);
					} else {
						bootstrapComplete.set(true);
					}
					// A venue-blocked RUNNING session must retain ACTIVE/ENTERING attendance rows
					// until the venue is repaired. Successful recovery starts attendance itself,
					// after publishing the hot RUNNING policy and before restarting the lot timer.
					if (!runningRecoveryBlocked) {
						attendance.start();
					}
					requestMaintenance();
				}));
	}

	private void tick() {
		if (!started.get()) {
			return;
		}
		RuntimeSession runtime = activeRuntime;
		if (runtime != null) {
			tickRuntime(runtime);
		}
		if (bootstrapComplete.get()) {
			requestMaintenance();
		}
	}

	private void requestMaintenance() {
		if (!started.get() || !bootstrapComplete.get()
				|| !maintenanceInFlight.compareAndSet(false, true)) {
			return;
		}
		Instant now = clock.instant();
		boolean venueReady = venueConfig.isReady();
		ensureCalendar(now)
				.thenCompose(ignored -> refreshKnownSessions())
				.thenCompose(ignored -> lockDueSessions(now, venueReady))
				.thenCompose(ignored -> advanceOneSession(now, venueReady))
				.whenComplete((ignored, error) -> {
					maintenanceInFlight.set(false);
					if (error != null && started.get()) {
						logFailure("advance auction session scheduler", error);
					}
				});
	}

	private CompletableFuture<Void> ensureCalendar(Instant now) {
		SessionSchedulePlanner currentPlanner = planner;
		ScheduleDefinition definition = currentPlanner.definition();
		LocalDate today = now.atZone(definition.zoneId()).toLocalDate();
		Map<String, PlannedSession> unique = new LinkedHashMap<>();
		for (LocalDate date : List.of(today.minusDays(1), today)) {
			for (PlannedSession planned : currentPlanner.sessionsOn(date)) {
				if (!planned.missedStartDeadline().isBefore(now)) {
					unique.put(planned.key(), planned);
				}
			}
		}
		for (PlannedSession planned : currentPlanner.nextSubmissionSessions(now)) {
			unique.put(planned.key(), planned);
		}
		return ensurePlannedSessions(List.copyOf(unique.values()), now);
	}

	private CompletableFuture<Void> ensurePlannedSessions(Collection<PlannedSession> plans, Instant now) {
		ScheduleDefinition definition = sessionConfig.schedule();
		return forEachSequential(plans, plan -> database.createSessionIfAbsent(new AuctionSessionRecord(
				plan.key(), plan.scheduledStart().toEpochMilli(), plan.submissionsLockAt().toEpochMilli(),
				definition.capacity(), definition.maxLotsPerSeller(), now.toEpochMilli()))
				.thenAccept(record -> remember(record, record.getState())));
	}

	private CompletableFuture<Void> refreshKnownSessions() {
		return database.getSessionsByState(EnumSet.allOf(SessionState.class)).thenAccept(records -> {
			Set<String> present = new LinkedHashSet<>();
			for (AuctionSessionRecord record : records) {
				present.add(record.getId());
				remember(record, record.getState());
			}
			knownSessions.keySet().retainAll(present);
		});
	}

	private CompletableFuture<Void> lockDueSessions(Instant now, boolean venueReady) {
		return database.getSessionsByState(List.of(SessionState.OPEN)).thenCompose(records -> {
			List<AuctionSessionRecord> due = records.stream()
					.filter(record -> now.toEpochMilli() >= record.getLockAtMillis())
					.toList();
			return forEachSequential(due, record -> transitionSession(record, SessionState.OPEN,
					SessionState.LOCKED, now.toEpochMilli()).thenCompose(changed -> {
				if (!changed) {
					return CompletableFuture.completedFuture(null);
				}
				return lockLots(record.getId(), now.toEpochMilli()).thenCompose(ignored -> {
					if (venueReady) {
						return CompletableFuture.completedFuture(null);
					}
					return transitionSession(record, SessionState.LOCKED, SessionState.BLOCKED,
							now.toEpochMilli()).thenAccept(blocked -> {
						if (blocked) {
							scheduler.runSyncTask(() -> venueDisplay.update(
									VenueDisplayState.blocked(sessionLabel(record.getId()))));
						}
					});
				});
			}));
		});
	}

	private CompletableFuture<Void> lockLots(String sessionId, long changedAtMillis) {
		return database.getSessionLots(sessionId).thenCompose(lots -> forEachSequential(lots, lot -> {
			if (lot.getState() == LotState.RESERVED || lot.getState() == LotState.QUEUED) {
				return database.transitionSessionLot(lot.getId(), lot.getState(), LotState.LOCKED,
						changedAtMillis).thenAccept(ignored -> {
				});
			}
			return CompletableFuture.completedFuture(null);
		}));
	}

	private CompletableFuture<Void> advanceOneSession(Instant now, boolean venueReady) {
		if (activeRuntime != null) {
			return markDueConflictsWaiting(now);
		}
		List<SessionState> candidates = List.of(SessionState.RUNNING, SessionState.WAITING,
				SessionState.BLOCKED, SessionState.LOCKED);
		return database.getSessionsByState(candidates).thenCompose(records -> {
			records.sort(Comparator.comparingLong(AuctionSessionRecord::getScheduledStartMillis));
			Optional<AuctionSessionRecord> persistedRunning = records.stream()
					.filter(record -> record.getState() == SessionState.RUNNING).findFirst();
			if (persistedRunning.isPresent()) {
				return recoverSpecificSession(persistedRunning.get());
			}

			AuctionSessionRecord selected = records.stream()
					.filter(record -> record.getState() != SessionState.RUNNING)
					.filter(record -> isDueCandidate(record, now))
					.findFirst().orElse(null);
			if (selected == null) {
				renderIdle(now);
				return CompletableFuture.completedFuture(null);
			}
			return database.getSessionLots(selected.getId())
					.thenCompose(lots -> handleCandidate(selected, lots, now, venueReady));
		});
	}

	private CompletableFuture<Void> markDueConflictsWaiting(Instant now) {
		return database.getSessionsByState(List.of(SessionState.LOCKED)).thenCompose(records ->
				forEachSequential(records.stream()
						.filter(record -> isDueCandidate(record, now))
						.sorted(Comparator.comparingLong(
								AuctionSessionRecord::getScheduledStartMillis))
						.toList(), record -> transitionSession(record, SessionState.LOCKED,
						SessionState.WAITING, now.toEpochMilli()).thenAccept(ignored -> {
						})));
	}

	private CompletableFuture<Void> handleCandidate(AuctionSessionRecord session,
	                                                List<AuctionSessionLot> allLots,
	                                                Instant now, boolean venueReady) {
		List<AuctionSessionLot> lots = occupiedLots(allLots);
		if (lots.isEmpty()) {
			return skipSession(session, now.toEpochMilli());
		}

		if (shouldDeferMissed(session.getState(), session.getScheduledStartMillis(), now,
				sessionConfig.schedule().missedStartGraceSeconds())) {
			return deferMissedSession(session, lots, now);
		}

		if (auctions.hasActiveAuction()) {
			if (session.getState() == SessionState.LOCKED) {
				return transitionSession(session, SessionState.LOCKED, SessionState.WAITING,
						now.toEpochMilli()).thenAccept(ignored -> {
				});
			}
			return CompletableFuture.completedFuture(null);
		}

		if (!venueReady) {
			long retryMillis = (long) sessionConfig.blockedRetrySeconds() * 1_000L;
			if (now.toEpochMilli() - lastBlockedRetryMillis < retryMillis
					&& session.getState() == SessionState.BLOCKED) {
				return CompletableFuture.completedFuture(null);
			}
			lastBlockedRetryMillis = now.toEpochMilli();
			if (session.getState() == SessionState.BLOCKED) {
				scheduler.runSyncTask(() -> venueDisplay.update(
						VenueDisplayState.blocked(sessionLabel(session.getId()))));
				return CompletableFuture.completedFuture(null);
			}
			return transitionSession(session, session.getState(), SessionState.BLOCKED,
					now.toEpochMilli()).thenAccept(blocked -> {
				if (blocked) {
					scheduler.runSyncTask(() -> venueDisplay.update(
							VenueDisplayState.blocked(sessionLabel(session.getId()))));
				}
			});
		}

		return beginSession(session, lots, now);
	}

	static boolean isDueCandidate(@NotNull AuctionSessionRecord session, @NotNull Instant now) {
		return isDueCandidate(session.getState(), session.getScheduledStartMillis(), now);
	}

	static boolean isDueCandidate(@NotNull SessionState state, long scheduledStartMillis,
	                              @NotNull Instant now) {
		return state != SessionState.RUNNING && scheduledStartMillis <= now.toEpochMilli();
	}

	static boolean shouldDeferMissed(@NotNull SessionState state, long scheduledStartMillis,
	                                 @NotNull Instant now, int graceSeconds) {
		if (state != SessionState.LOCKED && state != SessionState.BLOCKED) {
			return false;
		}
		long deadline = Math.addExact(scheduledStartMillis,
				Math.multiplyExact((long) graceSeconds, 1_000L));
		return now.toEpochMilli() > deadline;
	}

	private CompletableFuture<Void> skipSession(AuctionSessionRecord session, long changedAtMillis) {
		return transitionSession(session, session.getState(), SessionState.SKIPPED, changedAtMillis)
				.thenCompose(changed -> {
					if (!changed) {
						return CompletableFuture.completedFuture(null);
					}
					AuctionSessionView view = basicView(session, SessionState.SKIPPED, 0);
					return attendance.endSession(session.getId())
							.handle((results, error) -> {
								if (error != null) {
									logFailure("close skipped session attendance for "
											+ session.getId(), error);
								}
								return null;
							})
							.thenCompose(ignored -> onMain(() -> {
						plugin.getServer().getPluginManager().callEvent(new AuctionSessionEndEvent(view));
						renderIdle(clock.instant());
					}));
				});
	}

	private CompletableFuture<Void> deferMissedSession(AuctionSessionRecord session,
	                                                   List<AuctionSessionLot> lots, Instant now) {
		List<PlannedSession> targets = planner.nextSubmissionSessions(now, ROLLOVER_SEARCH_SESSIONS);
		return ensurePlannedSessions(targets, now)
				.thenCompose(ignored -> forEachSequential(lots,
						lot -> moveLotToFuture(lot, targets, 0, now.toEpochMilli())))
				.thenCompose(ignored -> database.getSessionLots(session.getId()))
				.thenCompose(latest -> occupiedLots(latest).isEmpty()
						? skipSession(session, now.toEpochMilli())
						: CompletableFuture.completedFuture(null));
	}

	private CompletableFuture<Void> moveLotToFuture(AuctionSessionLot source,
	                                               List<PlannedSession> targets,
	                                               int targetIndex, long changedAtMillis) {
		if (targetIndex >= targets.size()) {
			logger.warning("Could not defer auction " + source.getAuctionId()
					+ "; no capacity found in the next " + targets.size() + " sessions");
			return CompletableFuture.completedFuture(null);
		}
		PlannedSession target = targets.get(targetIndex);
		return database.moveSessionLot(source.getSessionId(), target.key(), source.getAuctionId(),
				source.getSellerId(),
				changedAtMillis).thenCompose(result -> {
			if (result.accepted()) {
				return CompletableFuture.completedFuture(null);
			}
			if (result.status() == ReservationStatus.FULL
					|| result.status() == ReservationStatus.SELLER_LIMIT
					|| result.status() == ReservationStatus.SESSION_CLOSED) {
				return moveLotToFuture(source, targets, targetIndex + 1, changedAtMillis);
			}
			return CompletableFuture.completedFuture(null);
		});
	}

	private CompletableFuture<Void> beginSession(AuctionSessionRecord session,
	                                             List<AuctionSessionLot> lots, Instant now) {
		return lockLots(session.getId(), now.toEpochMilli())
				.thenCompose(ignored -> database.getSessionLots(session.getId()).thenCombine(
						database.getRuntimeCheckpoint(session.getId()), RecoveryBundle::new))
				.thenCompose(bundle -> transitionSession(session, session.getState(),
						SessionState.RUNNING, now.toEpochMilli()).thenCompose(changed -> {
					if (!changed) {
						return CompletableFuture.completedFuture(null);
					}
					RuntimeSession runtime = new RuntimeSession(session, occupiedLots(bundle.lots()),
							sessionConfig.schedule());
					return onMain(() -> {
						if (!started.get() || activeRuntime != null || auctions.hasActiveAuction()) {
							database.transitionSession(session.getId(), SessionState.RUNNING,
									SessionState.WAITING, clock.millis());
							return;
						}
						activeRuntime = runtime;
						knownSessions.put(session.getId(), new KnownSession(SessionState.RUNNING,
								session.getScheduledStartMillis(), session.getLockAtMillis()));
						AuctionRuntimeCheckpoint checkpoint = bundle.checkpoint().orElse(null);
						if (checkpoint == null) {
							runtime.recoveryAttendancePending = true;
							publishRuntimeViews(runtime);
							plugin.getServer().getPluginManager().callEvent(
									new AuctionSessionStartEvent(activeView));
							attendance.start();
							awaitAttendanceBeforeTimer(session.getId())
									.thenCompose(ignored -> onMain(() -> {
										if (!isCurrent(runtime)) {
											return;
										}
										runtime.recoveryAttendancePending = false;
										startNextLot(runtime);
									}));
						} else if (checkpoint.isIntermission()) {
							resumeIntermission(runtime, checkpoint);
						} else {
							resumeActiveLot(runtime, checkpoint);
						}
					});
			}));
	}

	private CompletableFuture<Void> recoverRunningSession() {
		return database.getSessionsByState(List.of(SessionState.RUNNING)).thenCompose(records -> {
			if (records.isEmpty()) {
				runningRecoveryBlocked = false;
				return CompletableFuture.completedFuture(null);
			}
			runningRecoveryBlocked = true;
			records.sort(Comparator.comparingLong(AuctionSessionRecord::getScheduledStartMillis));
			AuctionSessionRecord recover = records.get(0);
			CompletableFuture<Void> demoteExtras = forEachSequential(records.subList(1, records.size()),
					extra -> database.transitionSession(extra.getId(), SessionState.RUNNING,
							SessionState.WAITING, clock.millis()).thenAccept(changed -> {
						if (changed) {
							logger.warning("Multiple RUNNING auction sessions were found; queued "
									+ extra.getId() + " behind " + recover.getId());
						}
					}));
			return demoteExtras.thenCompose(ignored -> recoverSpecificSession(recover));
		});
	}

	private CompletableFuture<Void> recoverSpecificSession(AuctionSessionRecord session) {
		if (activeRuntime != null) {
			return CompletableFuture.completedFuture(null);
		}
		return database.getSessionLots(session.getId()).thenCombine(
				database.getRuntimeCheckpoint(session.getId()), RecoveryBundle::new)
				.thenCompose(bundle -> {
					List<AuctionSessionLot> lots = occupiedLots(bundle.lots());
					if (lots.isEmpty()) {
						runningRecoveryBlocked = false;
						return skipRunningSession(session);
					}
					long attemptMillis = clock.millis();
					if (!runningRecoveryRetryDue(lastRunningRecoveryRetryMillis, attemptMillis,
							sessionConfig.blockedRetrySeconds())) {
						return CompletableFuture.completedFuture(null);
					}
					return onMainSupply(venueConfig::isReady).thenCompose(venueReady -> {
						if (!venueReady) {
							lastRunningRecoveryRetryMillis = attemptMillis;
							runningRecoveryBlocked = true;
							return onMain(() -> venueDisplay.update(
									VenueDisplayState.blocked(sessionLabel(session.getId()))));
						}

						RuntimeSession runtime = new RuntimeSession(session, lots, sessionConfig.schedule());
						return onMainSupply(() -> {
							if (!started.get() || activeRuntime != null || auctions.hasActiveAuction()) {
								return false;
							}
							activeRuntime = runtime;
							runtime.recoveryAttendancePending = true;
							knownSessions.put(session.getId(), new KnownSession(SessionState.RUNNING,
									session.getScheduledStartMillis(), session.getLockAtMillis()));
							lastRunningRecoveryRetryMillis = 0L;
							runningRecoveryBlocked = false;
							attendance.start();
							return true;
						}).thenCompose(activated -> {
							if (!activated) {
								return CompletableFuture.completedFuture(null);
							}
							// REGISTERED is not part of AttendanceService's crash-return scan. Explicitly
							// enter those online buyers and await their durable ACTIVE/rollback result
							// before the restored lot timer is allowed to tick again.
							return awaitAttendanceBeforeTimer(session.getId())
									.thenCompose(ignored -> onMain(() -> {
										if (!isCurrent(runtime)) {
											return;
										}
										runtime.recoveryAttendancePending = false;
										AuctionRuntimeCheckpoint checkpoint =
												bundle.checkpoint().orElse(null);
										if (checkpoint == null) {
											resumeWithoutCheckpoint(runtime);
										} else if (checkpoint.isIntermission()) {
											resumeIntermission(runtime, checkpoint);
										} else {
											resumeActiveLot(runtime, checkpoint);
										}
									}));
						});
					});
				});
	}

	private CompletableFuture<Void> awaitAttendanceBeforeTimer(String sessionId) {
		return attendance.awaitInitialRecovery()
				.handle((ignored, error) -> {
					if (error != null) {
						logFailure("finish initial attendance recovery for " + sessionId, error);
					}
					return null;
				})
				.thenCompose(ignored -> attendance.enterRegisteredOnline(sessionId))
				.handle((results, error) -> {
					if (error != null) {
						logFailure("enter registered buyers for " + sessionId, error);
					}
					// Teleport/persistence failures are represented in AttendanceResult. Either
					// way the barrier is complete; a failed buyer must not freeze the whole sale.
					return null;
				});
	}

	static boolean runningRecoveryRetryDue(long lastAttemptMillis, long nowMillis,
	                                       int retrySeconds) {
		if (lastAttemptMillis <= 0L) {
			return true;
		}
		long retryMillis = Math.multiplyExact((long) Math.max(1, retrySeconds), 1_000L);
		return nowMillis >= lastAttemptMillis
				&& nowMillis - lastAttemptMillis >= retryMillis;
	}

	private CompletableFuture<Void> skipRunningSession(AuctionSessionRecord session) {
		return transitionSession(session, SessionState.RUNNING, SessionState.SKIPPED, clock.millis())
				.thenCompose(changed -> {
					if (!changed) {
						return CompletableFuture.completedFuture(null);
					}
					return database.deleteRuntimeCheckpoint(session.getId())
							.handle((ignored, cleanupError) -> {
								if (cleanupError != null) {
									logFailure("delete skipped running session checkpoint for "
											+ session.getId(), cleanupError);
								}
								return null;
							})
							.thenCompose(ignored -> attendance.endSession(session.getId()))
							.handle((results, attendanceError) -> {
								if (attendanceError != null) {
									logFailure("close recovered empty session attendance for "
											+ session.getId(), attendanceError);
								}
								return null;
							})
							.thenCompose(ignored -> onMain(() -> {
								knownSessions.put(session.getId(), new KnownSession(SessionState.SKIPPED,
										session.getScheduledStartMillis(), session.getLockAtMillis()));
								AuctionSessionView view = basicView(session, SessionState.SKIPPED, 0);
								plugin.getServer().getPluginManager().callEvent(
										new AuctionSessionEndEvent(view));
								renderIdle(clock.instant());
							}));
				});
	}

	private void resumeWithoutCheckpoint(RuntimeSession runtime) {
		AuctionSessionLot active = runtime.firstInState(LotState.ACTIVE).orElse(null);
		if (active == null) {
			startNextLot(runtime);
			return;
		}
		runtime.currentLot = active;
		runtime.phase = RuntimePhase.STARTING;
		restoreLot(runtime, active, sessionConfig.schedule().lotDurationSeconds(), 0, 1L, false);
	}

	private void resumeIntermission(RuntimeSession runtime, AuctionRuntimeCheckpoint checkpoint) {
		AuctionSessionLot completed = checkpoint.getCurrentLotId() == null ? null
				: runtime.lot(checkpoint.getCurrentLotId()).orElse(null);
		if (completed != null) {
			runtime.states.put(completed.getId(), LotState.SETTLED);
			runtime.lastCompletedLot = completed;
			if (completed.getState() == LotState.ACTIVE) {
				database.transitionSessionLot(completed.getId(), LotState.ACTIVE, LotState.SETTLED,
						clock.millis());
			}
		}
		runtime.currentLot = null;
		runtime.currentData = null;
		runtime.lotOperationInFlight = false;
		runtime.phase = RuntimePhase.INTERMISSION;
		runtime.intermissionEndsAtMillis = clock.millis()
				+ (long) Math.max(0, checkpoint.getRemainingSeconds()) * 1_000L;
		if (runtime.nextUnfinished().isEmpty()) {
			completeSession(runtime);
			return;
		}
		publishRuntimeViews(runtime);
		if (checkpoint.getRemainingSeconds() <= 0) {
			startNextLot(runtime);
		}
	}

	private void resumeActiveLot(RuntimeSession runtime, AuctionRuntimeCheckpoint checkpoint) {
		UUID currentId = checkpoint.getCurrentLotId();
		AuctionSessionLot current = currentId == null ? null : runtime.lot(currentId).orElse(null);
		if (current == null) {
			logger.warning("Runtime checkpoint for " + runtime.record.getId()
					+ " references a missing lot; resuming the first unfinished lot");
			startNextLot(runtime);
			return;
		}
		runtime.currentLot = current;
		runtime.states.put(current.getId(), LotState.ACTIVE);
		runtime.phase = RuntimePhase.STARTING;
		restoreLot(runtime, current, Math.max(1, checkpoint.getRemainingSeconds()),
				checkpoint.getAntiSnipeExtensions(), checkpoint.getRevision(), false);
	}

	private void startNextLot(RuntimeSession runtime) {
		if (!isCurrent(runtime) || runtime.lotOperationInFlight) {
			return;
		}
		AuctionSessionLot next = runtime.nextUnfinished().orElse(null);
		if (next == null) {
			completeSession(runtime);
			return;
		}

		runtime.lotOperationInFlight = true;
		runtime.phase = RuntimePhase.STARTING;
		LotState state = runtime.states.get(next.getId());
		AtomicBoolean relationPromoted = new AtomicBoolean();
		// Confirm the auction journal has atomically published QUEUED before taking the
		// session lot into ACTIVE. In particular, a legally reserved submission may still
		// be PREPARING around cutoff; activating its relation first would make concurrent
		// escrow compensation unable to cancel the slot or return the item.
		loadAuction(next).thenCompose(loaded -> {
			if (loaded.record().getStatus() == AuctionRecordStatus.COMPLETED
					|| loaded.record().getStatus() == AuctionRecordStatus.CANCELLED) {
				return onMainSupply(() -> isCurrent(runtime)
						? new PreparedStart(next, loaded, null, true) : null);
			}
			if (!auctionStateAllowsLotPromotion(loaded.record().getStatus())) {
				throw new IllegalStateException("Scheduled lot " + next.getId()
						+ " cannot start from auction state " + loaded.record().getStatus());
			}
			return promoteLotToActive(next, state).thenCompose(promoted -> {
				if (!promoted) {
					return CompletableFuture.failedFuture(new IllegalStateException(
							"Could not activate scheduled lot " + next.getId()));
				}
				relationPromoted.set(true);
				runtime.states.put(next.getId(), LotState.ACTIVE);
				return onMainSupply(() -> {
					if (!isCurrent(runtime)) {
						return null;
					}
					AuctionData data = loaded.toAuctionData(
							runtime.definition.lotDurationSeconds(), logger);
					runtime.currentLot = next;
					runtime.currentData = data;
					return new PreparedStart(next, loaded, data, false);
				});
			});
		}).thenCompose(prepared -> {
			if (prepared == null) {
				return CompletableFuture.completedFuture(null);
			}
			if (prepared.alreadyFinished()) {
				return onMain(() -> finishAlreadySettled(runtime, prepared.lot(),
						prepared.loaded().record().getStatus()));
			}
			AuctionRuntimeCheckpoint checkpoint = activeCheckpoint(runtime, prepared.lot(),
					runtime.definition.lotDurationSeconds(), 1L, 0);
			return database.saveRuntimeCheckpoint(checkpoint).thenCompose(saved -> onMain(() ->
					startPreparedLot(runtime, prepared)));
		}).whenComplete((ignored, error) -> {
			if (error != null) {
				handleLotStartFailure(runtime, next, relationPromoted.get(), error);
			}
		});
	}

	static boolean auctionStateAllowsLotPromotion(@NotNull AuctionRecordStatus status) {
		return status == AuctionRecordStatus.QUEUED;
	}

	private void handleLotStartFailure(RuntimeSession runtime, AuctionSessionLot lot,
	                                   boolean relationPromoted, Throwable error) {
		logFailure("start scheduled lot " + lot.getAuctionId() + " in "
				+ runtime.record.getId(), error);
		CompletableFuture<?> rollback = relationPromoted
				? database.transitionSessionLot(lot.getId(), LotState.ACTIVE, LotState.LOCKED,
						clock.millis()).exceptionally(rollbackError -> {
					logFailure("roll back failed scheduled lot " + lot.getAuctionId(), rollbackError);
					return false;
				})
				: CompletableFuture.completedFuture(false);
		rollback.thenCompose(ignored -> database.getSessionLot(lot.getId()))
				.whenComplete((persisted, refreshError) -> scheduler.runSyncTask(() -> {
					if (!isCurrent(runtime)) {
						return;
					}
					if (refreshError != null) {
						logFailure("refresh failed scheduled lot " + lot.getAuctionId(), refreshError);
					} else if (persisted.isPresent()) {
						runtime.states.put(lot.getId(), persisted.get().getState());
					} else {
						runtime.states.put(lot.getId(), LotState.CANCELLED);
					}
					runtime.currentLot = null;
					runtime.currentData = null;
					runtime.lotOperationInFlight = false;
					runtime.phase = RuntimePhase.STARTING;
				}));
	}

	private CompletableFuture<Boolean> promoteLotToActive(AuctionSessionLot lot, LotState state) {
		if (state == LotState.ACTIVE) {
			return CompletableFuture.completedFuture(true);
		}
		if (state == LotState.RESERVED || state == LotState.QUEUED) {
			return database.transitionSessionLot(lot.getId(), state, LotState.LOCKED, clock.millis())
					.thenCompose(locked -> Boolean.TRUE.equals(locked)
							? database.transitionSessionLot(lot.getId(), LotState.LOCKED,
									LotState.ACTIVE, clock.millis())
							: CompletableFuture.completedFuture(false));
		}
		if (state == LotState.LOCKED) {
			return database.transitionSessionLot(lot.getId(), LotState.LOCKED, LotState.ACTIVE,
					clock.millis());
		}
		return CompletableFuture.completedFuture(false);
	}

	private void startPreparedLot(RuntimeSession runtime, PreparedStart prepared) {
		if (!isCurrent(runtime) || prepared.data() == null) {
			return;
		}
		AuctionSessionLot lot = prepared.lot();
		boolean accepted = auctions.startScheduledAuction(runtime.record.getId(), lot.getId(),
				prepared.data(), this,
				() -> scheduler.runSyncTask(() -> handleLotCompleted(runtime, lot)));
		runtime.lotOperationInFlight = false;
		if (!accepted) {
			runtime.currentLot = null;
			runtime.currentData = null;
			runtime.states.put(lot.getId(), LotState.LOCKED);
			database.transitionSessionLot(lot.getId(), LotState.ACTIVE, LotState.LOCKED,
					clock.millis());
			return;
		}
		runtime.phase = RuntimePhase.LOT;
		publishRuntimeViews(runtime);
		Auction active = auctions.getActiveAuction();
		if (active != null && active.getAuctionData().getId().equals(lot.getAuctionId())
				&& currentLotView != null) {
			plugin.getServer().getPluginManager().callEvent(new AuctionLotStartEvent(currentLotView));
		}
	}

	private void restoreLot(RuntimeSession runtime, AuctionSessionLot lot, int remainingSeconds,
	                       int antiSnipeExtensions, long revision, boolean announce) {
		runtime.lotOperationInFlight = true;
		runtime.restoreRemainingSeconds = Math.max(1, remainingSeconds);
		runtime.restoreAntiSnipeExtensions = Math.max(0, antiSnipeExtensions);
		runtime.restoreRevision = Math.max(1L, revision);
		runtime.restoreAnnounce = announce;
		loadAuction(lot).thenCombine(loadBids(lot.getAuctionId()), RestoredLot::new)
				.thenCompose(this::prepareRestore)
				.thenCompose(prepared -> onMain(() -> {
					if (!isCurrent(runtime)) {
						return;
					}
					RestoredLot restored = prepared.restored();
					AuctionRecordStatus status = prepared.effectiveStatus();
					if (status == AuctionRecordStatus.COMPLETED || status == AuctionRecordStatus.CANCELLED) {
						finishAlreadySettled(runtime, lot, status);
						return;
					}
					AuctionData data = restored.loaded().toAuctionData(
							runtime.definition.lotDurationSeconds(), logger);
					runtime.currentLot = lot;
					runtime.currentData = data;
					boolean accepted = auctions.restoreScheduledAuction(runtime.record.getId(), lot.getId(),
							data, restored.bids(),
							remainingSeconds, antiSnipeExtensions, revision,
							this,
							() -> scheduler.runSyncTask(() -> handleLotCompleted(runtime, lot)));
					runtime.lotOperationInFlight = false;
					if (!accepted) {
						logger.warning("Could not restore scheduled lot " + lot.getAuctionId()
								+ " because another auction engine is active");
						runtime.restoreRetryAtMillis = clock.millis() + 1_000L;
						return;
					}
					runtime.phase = RuntimePhase.LOT;
					runtime.restoreRetryAtMillis = 0L;
					publishRuntimeViews(runtime);
					if (announce && currentLotView != null) {
						plugin.getServer().getPluginManager().callEvent(
								new AuctionLotStartEvent(currentLotView));
					}
				})).exceptionally(error -> {
					logFailure("restore scheduled lot " + lot.getAuctionId(), error);
					scheduler.runSyncTask(() -> {
						if (isCurrent(runtime)) {
							runtime.lotOperationInFlight = false;
							runtime.restoreRetryAtMillis = clock.millis() + 1_000L;
						}
					});
					return null;
				});
	}

	private CompletableFuture<PreparedRestore> prepareRestore(RestoredLot restored) {
		AuctionRecord record = restored.loaded().record();
		AuctionRecordStatus status = record.getStatus();
		if (status == AuctionRecordStatus.PREPARING) {
			return CompletableFuture.failedFuture(new IllegalStateException(
					"Auction " + record.getId() + " is still PREPARING and cannot be restored"));
		}
		if (status != AuctionRecordStatus.QUEUED) {
			return CompletableFuture.completedFuture(new PreparedRestore(restored, status));
		}

		// A hard stop may land after the session checkpoint but before Auction.startAuction's
		// asynchronous QUEUED -> ACTIVE transition. Claim that durable record before restoring.
		return database.transitionAuction(record.getId(), AuctionRecordStatus.QUEUED,
				AuctionRecordStatus.ACTIVE).thenCompose(activated -> {
			if (Boolean.TRUE.equals(activated)) {
				return CompletableFuture.completedFuture(
						new PreparedRestore(restored, AuctionRecordStatus.ACTIVE));
			}
			return database.getAuctionRecord(record.getId()).thenCompose(latest -> {
				if (latest.isEmpty()) {
					return CompletableFuture.failedFuture(new IllegalStateException(
							"Auction disappeared while preparing restore: " + record.getId()));
				}
				AuctionRecordStatus latestStatus = latest.get().getStatus();
				if (latestStatus == AuctionRecordStatus.ACTIVE
						|| latestStatus == AuctionRecordStatus.COMPLETED
						|| latestStatus == AuctionRecordStatus.CANCELLED) {
					return CompletableFuture.completedFuture(
							new PreparedRestore(restored, latestStatus));
				}
				return CompletableFuture.failedFuture(new IllegalStateException(
						"Auction " + record.getId() + " changed to " + latestStatus
								+ " while preparing restore"));
			});
		});
	}

	private void tickRuntime(RuntimeSession runtime) {
		if (!isCurrent(runtime)) {
			return;
		}
		if (runtime.phase == RuntimePhase.STARTING) {
			if (attendanceAllowsTimer(runtime.recoveryAttendancePending)
					&& !runtime.lotOperationInFlight) {
				if (runtime.currentLot == null) {
					startNextLot(runtime);
				} else if (clock.millis() >= runtime.restoreRetryAtMillis) {
					restoreLot(runtime, runtime.currentLot, runtime.restoreRemainingSeconds,
							runtime.restoreAntiSnipeExtensions, runtime.restoreRevision,
							runtime.restoreAnnounce);
				}
			}
			publishRuntimeViews(runtime);
			return;
		}
		if (runtime.phase == RuntimePhase.INTERMISSION) {
			int remaining = runtime.intermissionRemaining(clock.millis());
			persistCheckpoint(runtime);
			publishRuntimeViews(runtime);
			if (remaining == 0) {
				startNextLot(runtime);
			}
			return;
		}
		if (runtime.phase != RuntimePhase.LOT || runtime.currentLot == null) {
			publishRuntimeViews(runtime);
			return;
		}

		Auction active = auctions.getActiveAuction();
		if (active != null && active.getAuctionData().getId().equals(runtime.currentLot.getAuctionId())) {
			persistCheckpoint(runtime);
			publishRuntimeViews(runtime);
			return;
		}
		if (!runtime.completionQueued) {
			runtime.completionQueued = true;
			scheduler.runSyncTask(() -> handleLotCompleted(runtime, runtime.currentLot));
		}
	}

	static boolean attendanceAllowsTimer(boolean attendancePending) {
		return !attendancePending;
	}

	private void handleLotCompleted(RuntimeSession runtime, AuctionSessionLot lot) {
		if (!started.get() || !isCurrent(runtime) || lot == null
				|| !lot.getId().equals(runtime.currentLot == null ? null : runtime.currentLot.getId())) {
			return;
		}
		if (runtime.finishingLot) {
			return;
		}
		runtime.finishingLot = true;
		runtime.completionQueued = false;
		database.transitionSessionLot(lot.getId(), LotState.ACTIVE, LotState.SETTLED,
				clock.millis()).whenComplete((settled, error) -> scheduler.runSyncTask(() -> {
			if (!isCurrent(runtime)) {
				return;
			}
			if (error != null) {
				runtime.finishingLot = false;
				logFailure("settle scheduled lot " + lot.getAuctionId(), error);
				return;
			}
			runtime.states.put(lot.getId(), LotState.SETTLED);
			runtime.lastCompletedLot = lot;
			runtime.currentLot = null;
			runtime.currentData = null;
			runtime.finishingLot = false;
			if (Boolean.TRUE.equals(settled)) {
				plugin.getServer().getPluginManager().callEvent(new AuctionLotFinishedEvent(
						runtime.record.getId(), lot.getId(), lot.getAuctionId(), lot.getSequenceNumber()));
			}
			if (runtime.nextUnfinished().isEmpty()) {
				completeSession(runtime);
				return;
			}
			runtime.phase = RuntimePhase.INTERMISSION;
			runtime.intermissionEndsAtMillis = clock.millis()
					+ (long) runtime.definition.intermissionSeconds() * 1_000L;
			persistCheckpoint(runtime);
			publishRuntimeViews(runtime);
		}));
	}

	private void finishAlreadySettled(RuntimeSession runtime, AuctionSessionLot lot,
	                                  AuctionRecordStatus auctionStatus) {
		LotState target = auctionStatus == AuctionRecordStatus.COMPLETED
				? LotState.SETTLED : LotState.CANCELLED;
		LotState previous = runtime.states.get(lot.getId());
		runtime.states.put(lot.getId(), target);
		runtime.currentLot = null;
		runtime.currentData = null;
		runtime.lotOperationInFlight = false;
		if (previous != target) {
			database.transitionSessionLot(lot.getId(), previous, target, clock.millis());
		}
		startNextLot(runtime);
	}

	private void completeSession(RuntimeSession runtime) {
		if (!isCurrent(runtime) || runtime.phase == RuntimePhase.COMPLETING) {
			return;
		}
		runtime.phase = RuntimePhase.COMPLETING;
		database.transitionSession(runtime.record.getId(), SessionState.RUNNING,
				SessionState.COMPLETED, clock.millis()).thenCompose(changed -> {
			CompletableFuture<Boolean> committed;
			if (Boolean.TRUE.equals(changed)) {
				committed = CompletableFuture.completedFuture(true);
			} else {
				// A delayed callback or a previous completion attempt may already have made the
				// durable terminal transition. Treat that as success so the in-memory venue is
				// not held forever by a CAS that can no longer match RUNNING.
				committed = database.getSession(runtime.record.getId()).thenApply(latest ->
						completionStateCommitted(false,
								latest.map(AuctionSessionRecord::getState).orElse(null)));
			}
			return committed.thenCompose(isCommitted -> {
				if (!Boolean.TRUE.equals(isCommitted)) {
					return CompletableFuture.completedFuture(false);
				}
				// The COMPLETED row is the source of truth. A stale checkpoint is harmless and
				// will never be recovered as RUNNING, so cleanup failure must not re-open or
				// permanently wedge the venue.
				return database.deleteRuntimeCheckpoint(runtime.record.getId())
						.handle((ignored, cleanupError) -> {
							if (cleanupError != null) {
								logFailure("delete completed session checkpoint for "
										+ runtime.record.getId(), cleanupError);
							}
							return true;
						});
			});
		}).whenComplete((completed, error) -> scheduler.runSyncTask(() -> {
			if (!isCurrent(runtime)) {
				return;
			}
			if (error != null || !Boolean.TRUE.equals(completed)) {
				runtime.phase = RuntimePhase.STARTING;
				if (error != null) {
					logFailure("complete auction session " + runtime.record.getId(), error);
				}
				return;
			}
			AuctionSessionView completedView = basicView(runtime.record, SessionState.COMPLETED,
					runtime.totalLots());
			activeView = completedView;
			currentLotView = null;
			knownSessions.put(runtime.record.getId(), new KnownSession(SessionState.COMPLETED,
					runtime.record.getScheduledStartMillis(), runtime.record.getLockAtMillis()));
			plugin.getServer().getPluginManager().callEvent(new AuctionSessionEndEvent(completedView));
			// Keep the venue exclusively owned by this terminal runtime until every old
			// participant has either returned or acquired a durable PENDING_RETURN row.
			// Otherwise the next WAITING session can enter the same buyer while an old
			// async return teleport is still in flight and move them out of the new sale.
			attendance.endSession(runtime.record.getId())
					.whenComplete((results, attendanceError) -> scheduler.runSyncTask(() -> {
						if (!isCurrent(runtime)) {
							return;
						}
						if (attendanceError != null) {
							logFailure("close completed session attendance for "
									+ runtime.record.getId(), attendanceError);
						}
						activeRuntime = null;
						activeView = null;
						renderIdle(clock.instant());
						requestMaintenance();
					}));
		}));
	}

	static boolean completionStateCommitted(boolean transitioned, SessionState persistedState) {
		return transitioned || persistedState == SessionState.COMPLETED;
	}

	private CompletableFuture<Void> migrateLegacyQueuedAuctions() {
		if (!legacyMigrationComplete.compareAndSet(false, true)) {
			return CompletableFuture.completedFuture(null);
		}
		Instant now = clock.instant();
		List<PlannedSession> targets = planner.nextSubmissionSessions(now, ROLLOVER_SEARCH_SESSIONS);
		return ensurePlannedSessions(targets, now)
				.thenCompose(ignored -> database.getAuctionsByStatus(List.of(AuctionRecordStatus.QUEUED)))
				.thenCompose(records -> forEachSequential(records, record ->
						database.getSessionLotsByAuctionId(record.getId()).thenCompose(existing -> {
							if (!existing.isEmpty()) {
								return CompletableFuture.completedFuture(null);
							}
							return reserveLegacyRecord(record, targets, 0, now.toEpochMilli());
						})))
				.whenComplete((ignored, error) -> {
					if (error != null) {
						legacyMigrationComplete.set(false);
					}
				});
	}

	private CompletableFuture<Void> reserveLegacyRecord(AuctionRecord record,
	                                                   List<PlannedSession> targets,
	                                                   int targetIndex, long createdAtMillis) {
		if (targetIndex >= targets.size()) {
			logger.warning("Could not migrate legacy queued auction " + record.getId()
					+ " into the next " + targets.size() + " session windows");
			return CompletableFuture.completedFuture(null);
		}
		return database.reserveSessionLot(targets.get(targetIndex).key(), record.getId(),
				record.getAuctioneerId(), createdAtMillis).thenCompose(result -> {
			if (result.accepted()) {
				return CompletableFuture.completedFuture(null);
			}
			if (result.status() == ReservationStatus.FULL
					|| result.status() == ReservationStatus.SELLER_LIMIT
					|| result.status() == ReservationStatus.SESSION_CLOSED) {
				return reserveLegacyRecord(record, targets, targetIndex + 1, createdAtMillis);
			}
			return CompletableFuture.completedFuture(null);
		});
	}

	private CompletableFuture<LoadedAuction> loadAuction(AuctionSessionLot lot) {
		return database.getAuctionRecord(lot.getAuctionId()).thenCompose(optional -> {
			if (optional.isEmpty()) {
				return CompletableFuture.failedFuture(new IllegalStateException(
						"Missing AuctionRecord for session lot " + lot.getId()));
			}
			AuctionRecord record = optional.get();
			return players.getPlayer(record.getAuctioneerId()).thenApply(seller -> {
				try {
					return new LoadedAuction(record, seller, record.getItem());
				} catch (IOException error) {
					throw new IllegalStateException("Could not deserialize auction " + record.getId(), error);
				}
			});
		});
	}

	private CompletableFuture<List<Bid>> loadBids(UUID auctionId) {
		return database.getBidRecords(auctionId).thenCompose(records -> mapSequential(records,
				record -> players.getPlayer(record.getBidderId())
						.thenApply(player -> new Bid(player, record.getAmountMinor()))));
	}

	private void persistCheckpoint(RuntimeSession runtime) {
		if (!isCurrent(runtime)) {
			return;
		}
		AuctionRuntimeCheckpoint checkpoint;
		if (runtime.phase == RuntimePhase.LOT && runtime.currentLot != null) {
			Auction active = auctions.getActiveAuction();
			if (active == null || !active.getAuctionData().getId().equals(runtime.currentLot.getAuctionId())) {
				return;
			}
			checkpoint = activeCheckpoint(runtime, runtime.currentLot,
					Math.max(0, active.getRemainingSeconds()), active.getRevision(),
					active.getAntiSnipeRunTimes());
		} else if (runtime.phase == RuntimePhase.INTERMISSION) {
			AuctionSessionLot completed = runtime.lastCompletedLot;
			checkpoint = new AuctionRuntimeCheckpoint(runtime.record.getId(),
					completed == null ? null : completed.getId(),
					completed == null ? runtime.completedCount() : completed.getSequenceNumber(),
					runtime.intermissionRemaining(clock.millis()), 0L, 0, true, clock.millis());
		} else {
			return;
		}
		database.saveRuntimeCheckpoint(checkpoint).whenComplete((saved, error) -> {
			if (error != null && !runtime.checkpointFailureLogged) {
				runtime.checkpointFailureLogged = true;
				logFailure("persist runtime checkpoint for " + runtime.record.getId(), error);
			} else if (error == null) {
				runtime.checkpointFailureLogged = false;
			}
		});
	}

	private AuctionRuntimeCheckpoint activeCheckpoint(RuntimeSession runtime, AuctionSessionLot lot,
	                                                  int remainingSeconds, long revision,
	                                                  int antiSnipeExtensions) {
		return new AuctionRuntimeCheckpoint(runtime.record.getId(), lot.getId(), lot.getSequenceNumber(),
				remainingSeconds, revision, antiSnipeExtensions, false, clock.millis());
	}

	private void publishRuntimeViews(RuntimeSession runtime) {
		if (!isCurrent(runtime) || runtime.totalLots() == 0) {
			return;
		}
		SessionProgress progress;
		int phaseRemaining;
		if (runtime.phase == RuntimePhase.INTERMISSION && runtime.nextUnfinished().isPresent()) {
			phaseRemaining = runtime.intermissionRemaining(clock.millis());
			progress = SessionProgress.intermission(runtime.completedCount(), runtime.totalLots(),
					phaseRemaining);
		} else {
			Auction active = auctions.getActiveAuction();
			phaseRemaining = active != null && runtime.currentLot != null
					&& active.getAuctionData().getId().equals(runtime.currentLot.getAuctionId())
					? Math.max(0, active.getRemainingSeconds())
					: runtime.definition.lotDurationSeconds();
			int ordinal = runtime.currentLot == null
					? runtime.nextOrdinal() : runtime.ordinal(runtime.currentLot);
			progress = SessionProgress.running(Math.max(1, ordinal), runtime.totalLots(), phaseRemaining);
		}
		long eta = new SessionEtaCalculator(runtime.definition).remainingSeconds(progress);
		activeView = new AuctionSessionView(runtime.record.getId(), slotId(runtime.record.getId()),
				Instant.ofEpochMilli(runtime.record.getScheduledStartMillis()),
				Instant.ofEpochMilli(runtime.record.getLockAtMillis()), SessionState.RUNNING,
				runtime.totalLots(), runtime.record.getCapacity(), Optional.of(progress),
				OptionalLong.of(eta));

		if (runtime.phase == RuntimePhase.LOT && runtime.currentLot != null
				&& runtime.currentData != null) {
			Auction active = auctions.getActiveAuction();
			if (active == null) {
				return;
			}
			AuctionView auctionView = active.viewFor(null);
			PublicBidPrice price = auctionView.sealed()
					? PublicBidPrice.sealed()
					: PublicBidPrice.visible(auctionView.currentPriceMinor());
			String itemName = nonBlank(runtime.currentData.getCustomName(),
					runtime.currentData.getItem().getType().getKey().toString());
			String sellerName = nonBlank(auctionView.sellerName(), auctionView.sellerId().toString());
			currentLotView = new AuctionPublicLotView(runtime.currentLot.getId(), runtime.record.getId(),
					runtime.ordinal(runtime.currentLot), LotState.ACTIVE,
					auctionView.sealed() ? AuctionMode.SEALED : AuctionMode.OPEN,
					itemName, auctionView.amount(), sellerName, auctionView.startingPriceMinor(),
					auctionView.incrementMinor(), auctionView.autoBuyMinor(), price,
					Math.max(0, auctionView.remainingSeconds()), auctionView.revision());
			venueDisplay.update(VenueDisplayState.lot(sessionLabel(runtime.record.getId()),
					runtime.ordinal(runtime.currentLot), runtime.totalLots(), auctionView.item(), itemName,
					Math.max(0, auctionView.remainingSeconds()),
					auctionView.sealed() ? "已密封" : Money.format(auctionView.currentPriceMinor()),
					auctionView.sealed(), safeInt(eta)));
		} else if (runtime.phase == RuntimePhase.INTERMISSION) {
			currentLotView = null;
			venueDisplay.update(VenueDisplayState.intermission(sessionLabel(runtime.record.getId()),
					runtime.completedCount(), runtime.totalLots(), phaseRemaining, safeInt(eta)));
		}
	}

	private CompletableFuture<Optional<AuctionSessionView>> view(AuctionSessionRecord record) {
		AuctionSessionView current = activeView;
		if (current != null && current.sessionKey().equals(record.getId())) {
			return CompletableFuture.completedFuture(Optional.of(current));
		}
		return database.getSessionLots(record.getId()).thenCombine(
				database.getRuntimeCheckpoint(record.getId()), (lots, checkpoint) -> {
					List<AuctionSessionLot> occupied = occupiedLots(lots);
					if (record.getState() != SessionState.RUNNING || occupied.isEmpty()) {
						return Optional.of(basicView(record, record.getState(), occupied.size()));
					}
					AuctionRuntimeCheckpoint saved = checkpoint.orElse(null);
					int completed = (int) occupied.stream()
							.filter(lot -> lot.getState() == LotState.SETTLED).count();
					SessionProgress progress;
					if (saved != null && saved.isIntermission() && completed > 0
							&& completed < occupied.size()) {
						progress = SessionProgress.intermission(completed, occupied.size(),
								saved.getRemainingSeconds());
					} else {
						int ordinal = saved == null ? Math.min(occupied.size(), completed + 1)
								: ordinalOf(occupied, saved.getCurrentLotId(), completed + 1);
						progress = SessionProgress.running(Math.max(1, ordinal), occupied.size(),
								saved == null ? sessionConfig.schedule().lotDurationSeconds()
										: Math.max(0, saved.getRemainingSeconds()));
					}
					long eta = calculateEtaUnchecked(progress, sessionConfig.schedule());
					return Optional.of(new AuctionSessionView(record.getId(), slotId(record.getId()),
							Instant.ofEpochMilli(record.getScheduledStartMillis()),
							Instant.ofEpochMilli(record.getLockAtMillis()), record.getState(),
							occupied.size(), record.getCapacity(), Optional.of(progress),
							OptionalLong.of(eta)));
				});
	}

	private AuctionSessionView basicView(AuctionSessionRecord record, SessionState state, int lotCount) {
		return new AuctionSessionView(record.getId(), slotId(record.getId()),
				Instant.ofEpochMilli(record.getScheduledStartMillis()),
				Instant.ofEpochMilli(record.getLockAtMillis()), state, lotCount,
				record.getCapacity(), Optional.empty(), OptionalLong.empty());
	}

	private void renderIdle(Instant now) {
		if (activeRuntime != null || now.toEpochMilli() - lastIdleRenderMillis < 5_000L) {
			return;
		}
		lastIdleRenderMillis = now.toEpochMilli();
		futureSessionViews().whenComplete((views, error) -> {
			if (error != null) {
				logFailure("render next auction session", error);
				return;
			}
			if (views.isEmpty()) {
				return;
			}
			AuctionSessionView next = views.get(0);
			String when = next.scheduledStart().atZone(sessionConfig.schedule().zoneId())
					.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
			scheduler.runSyncTask(() -> {
				if (started.get() && activeRuntime == null) {
					venueDisplay.update(VenueDisplayState.idle(when, next.lotCount(), next.capacity()));
				}
			});
		});
	}

	private CompletableFuture<Boolean> transitionSession(AuctionSessionRecord record,
	                                                     SessionState expected, SessionState next,
	                                                     long changedAtMillis) {
		return database.transitionSession(record.getId(), expected, next, changedAtMillis)
				.thenApply(changed -> {
					if (Boolean.TRUE.equals(changed)) {
						knownSessions.put(record.getId(), new KnownSession(next,
								record.getScheduledStartMillis(), record.getLockAtMillis()));
					}
					return changed;
				});
	}

	private void remember(AuctionSessionRecord record, SessionState state) {
		knownSessions.put(record.getId(), new KnownSession(state,
				record.getScheduledStartMillis(), record.getLockAtMillis()));
	}

	private List<AuctionSessionLot> occupiedLots(List<AuctionSessionLot> lots) {
		return lots.stream()
				.filter(lot -> lot.getState() != LotState.CANCELLED && lot.getState() != LotState.DEFERRED)
				.sorted(Comparator.comparingInt(AuctionSessionLot::getSequenceNumber)
						.thenComparingLong(AuctionSessionLot::getCreatedAtMillis))
				.toList();
	}

	private boolean isCurrent(RuntimeSession runtime) {
		return started.get() && activeRuntime == runtime;
	}

	private String sessionLabel(String sessionId) {
		String slot = slotId(sessionId);
		String date = sessionId.contains("/") ? sessionId.substring(0, sessionId.indexOf('/')) : sessionId;
		return date + " " + sessionConfig.displayName(slot);
	}

	private static String slotId(String sessionId) {
		int slash = sessionId.lastIndexOf('/');
		return slash < 0 || slash == sessionId.length() - 1 ? sessionId : sessionId.substring(slash + 1);
	}

	private static int ordinalOf(List<AuctionSessionLot> lots, UUID lotId, int fallback) {
		if (lotId != null) {
			for (int index = 0; index < lots.size(); index++) {
				if (lots.get(index).getId().equals(lotId)) {
					return index + 1;
				}
			}
		}
		return Math.max(1, Math.min(lots.size(), fallback));
	}

	private static long calculateEtaUnchecked(SessionProgress progress, ScheduleDefinition definition) {
		if (progress.totalLots() <= definition.capacity()) {
			return new SessionEtaCalculator(definition).remainingSeconds(progress);
		}
		int futureLots = progress.phase() == SessionRunPhase.RUNNING
				? progress.totalLots() - progress.completedLots() - 1
				: progress.totalLots() - progress.completedLots();
		long transitions = progress.phase() == SessionRunPhase.RUNNING
				? futureLots : Math.max(0, futureLots - 1L);
		return progress.phaseRemainingSeconds()
				+ (long) futureLots * definition.lotDurationSeconds()
				+ transitions * definition.intermissionSeconds();
	}

	private static int safeInt(long value) {
		return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
	}

	private static String nonBlank(String preferred, String fallback) {
		return preferred == null || preferred.isBlank() ? fallback : preferred;
	}

	private CompletableFuture<Void> onMain(Runnable action) {
		CompletableFuture<Void> future = new CompletableFuture<>();
		scheduler.runSyncTask(() -> {
			try {
				action.run();
				future.complete(null);
			} catch (Throwable error) {
				future.completeExceptionally(error);
			}
		});
		return future;
	}

	private <T> CompletableFuture<T> onMainSupply(java.util.function.Supplier<T> action) {
		CompletableFuture<T> future = new CompletableFuture<>();
		scheduler.runSyncTask(() -> {
			try {
				future.complete(action.get());
			} catch (Throwable error) {
				future.completeExceptionally(error);
			}
		});
		return future;
	}

	private static <T> CompletableFuture<Void> forEachSequential(
			Collection<T> values, Function<T, CompletableFuture<?>> operation) {
		CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
		for (T value : values) {
			chain = chain.thenCompose(ignored -> operation.apply(value).thenAccept(result -> {
			}));
		}
		return chain;
	}

	private static <T, R> CompletableFuture<List<R>> mapSequential(
			Collection<T> values, Function<T, CompletableFuture<R>> operation) {
		List<R> result = new ArrayList<>(values.size());
		return forEachSequential(values, value -> operation.apply(value).thenAccept(result::add))
				.thenApply(ignored -> List.copyOf(result));
	}

	private void logFailure(String action, Throwable error) {
		Throwable cause = error;
		while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
			cause = cause.getCause();
		}
		logger.severe("Could not " + action,
				cause instanceof Exception exception ? exception : new RuntimeException(cause));
	}

	private record KnownSession(SessionState state, long scheduledStartMillis, long lockAtMillis) {
	}

	private record RecoveryBundle(List<AuctionSessionLot> lots,
	                              Optional<AuctionRuntimeCheckpoint> checkpoint) {
	}

	private record LoadedAuction(AuctionRecord record, AuctionPlayer seller, ItemStack item) {
		AuctionData toAuctionData(int durationSeconds, Logger logger) {
			AuctionData data = new AuctionData(record.getId(), seller, item.clone(), record.getAmount(),
					durationSeconds, record.getStartingPriceMinor(), record.getIncrementMinor(),
					record.getAutoBuyMinor(), record.isSealed(), record.getWorld());
			data.gatherAdditionalData(logger);
			return data;
		}
	}

	private record PreparedStart(AuctionSessionLot lot, LoadedAuction loaded, AuctionData data,
	                             boolean alreadyFinished) {
	}

	private record RestoredLot(LoadedAuction loaded, List<Bid> bids) {
	}

	private record PreparedRestore(RestoredLot restored, AuctionRecordStatus effectiveStatus) {
	}

	private enum RuntimePhase {
		STARTING,
		LOT,
		INTERMISSION,
		COMPLETING
	}

	private static final class RuntimeSession {
		private final AuctionSessionRecord record;
		private final List<AuctionSessionLot> lots;
		private final Map<UUID, LotState> states = new ConcurrentHashMap<>();
		private final ScheduleDefinition definition;
		private volatile RuntimePhase phase = RuntimePhase.STARTING;
		private volatile AuctionSessionLot currentLot;
		private volatile AuctionSessionLot lastCompletedLot;
		private volatile AuctionData currentData;
		private volatile long intermissionEndsAtMillis;
		private volatile boolean lotOperationInFlight;
		private volatile boolean finishingLot;
		private volatile boolean completionQueued;
		private volatile boolean checkpointFailureLogged;
		private volatile boolean recoveryAttendancePending;
		private volatile int restoreRemainingSeconds = 1;
		private volatile int restoreAntiSnipeExtensions;
		private volatile long restoreRevision = 1L;
		private volatile boolean restoreAnnounce;
		private volatile long restoreRetryAtMillis;

		private RuntimeSession(AuctionSessionRecord record, List<AuctionSessionLot> lots,
		                       ScheduleDefinition definition) {
			this.record = record;
			this.lots = List.copyOf(lots);
			this.definition = definition;
			for (AuctionSessionLot lot : lots) {
				states.put(lot.getId(), lot.getState());
				if (lot.getState() == LotState.SETTLED) {
					lastCompletedLot = lot;
				}
			}
		}

		private int totalLots() {
			return lots.size();
		}

		private int completedCount() {
			return (int) lots.stream().filter(lot -> {
				LotState state = states.get(lot.getId());
				return state == LotState.SETTLED || state == LotState.CANCELLED;
			}).count();
		}

		private Optional<AuctionSessionLot> nextUnfinished() {
			return lots.stream().filter(lot -> {
				LotState state = states.get(lot.getId());
				return state != LotState.SETTLED && state != LotState.CANCELLED
						&& state != LotState.DEFERRED && state != LotState.ACTIVE;
			}).findFirst();
		}

		private Optional<AuctionSessionLot> firstInState(LotState expected) {
			return lots.stream().filter(lot -> states.get(lot.getId()) == expected).findFirst();
		}

		private Optional<AuctionSessionLot> lot(UUID lotId) {
			return lots.stream().filter(lot -> lot.getId().equals(lotId)).findFirst();
		}

		private int ordinal(AuctionSessionLot lot) {
			for (int index = 0; index < lots.size(); index++) {
				if (lots.get(index).getId().equals(lot.getId())) {
					return index + 1;
				}
			}
			return 1;
		}

		private int nextOrdinal() {
			return nextUnfinished().map(this::ordinal).orElse(Math.max(1, totalLots()));
		}

		private int intermissionRemaining(long nowMillis) {
			long millis = Math.max(0L, intermissionEndsAtMillis - nowMillis);
			return (int) Math.min(Integer.MAX_VALUE, (millis + 999L) / 1_000L);
		}
	}
}
