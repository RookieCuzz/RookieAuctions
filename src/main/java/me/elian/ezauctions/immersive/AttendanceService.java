package me.elian.ezauctions.immersive;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.elian.ezauctions.Logger;
import me.elian.ezauctions.data.Database;
import me.elian.ezauctions.model.AuctionAttendanceRecord;
import me.elian.ezauctions.scheduler.TaskScheduler;
import me.elian.ezauctions.session.AttendanceState;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Durable buyer registration and teleport lifecycle.
 *
 * <p>The session controller must install an {@link AttendanceSessionPolicy} before calling
 * {@link #start()}; the default is fail-closed. Database entry starts atomically persist the
 * return point before any teleport occurs.</p>
 */
@Singleton
public final class AttendanceService implements AuctionModeAccess, Listener {
	private static final List<AttendanceState> RETURNABLE_STATES = List.of(
			AttendanceState.ACTIVE, AttendanceState.ENTERING, AttendanceState.PENDING_RETURN);
	private final Plugin plugin;
	private final TaskScheduler scheduler;
	private final Database database;
	private final VenueConfig venueConfig;
	private final Logger logger;
	private final Clock clock;
	private final AtomicReference<AttendanceSessionPolicy> sessionPolicy =
			new AtomicReference<>(AttendanceSessionPolicy.DENY_ALL);
	private final Map<UUID, String> activeSessions = new ConcurrentHashMap<>();
	private final Set<UUID> busyPlayers = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean started = new AtomicBoolean();
	private final AtomicReference<CompletableFuture<Void>> initialRecovery =
			new AtomicReference<>(CompletableFuture.completedFuture(null));

	@Inject
	public AttendanceService(@NotNull Plugin plugin, @NotNull TaskScheduler scheduler,
	                         @NotNull Database database, @NotNull VenueConfig venueConfig,
	                         @NotNull Logger logger) {
		this(plugin, scheduler, database, venueConfig, logger, Clock.systemUTC());
	}

	AttendanceService(@NotNull Plugin plugin, @NotNull TaskScheduler scheduler,
	                  @NotNull Database database, @NotNull VenueConfig venueConfig,
	                  @NotNull Logger logger, @NotNull Clock clock) {
		this.plugin = plugin;
		this.scheduler = scheduler;
		this.database = database;
		this.venueConfig = venueConfig;
		this.logger = logger;
		this.clock = clock;
	}

	public void setSessionPolicy(@NotNull AttendanceSessionPolicy policy) {
		sessionPolicy.set(policy);
	}

	/** Registers join recovery and rebuilds auction mode for players already online. */
	public void start() {
		if (!started.compareAndSet(false, true)) {
			return;
		}
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		List<CompletableFuture<AttendanceResult>> recoveries = new ArrayList<>();
		for (Player player : plugin.getServer().getOnlinePlayers()) {
			recoveries.add(recover(player));
		}
		initialRecovery.set(collect(recoveries).thenApply(ignored -> null));
	}

	/**
	 * Completes after every player who was online at {@link #start()} has finished durable
	 * attendance recovery. Session restoration must await this before resuming its lot timer.
	 */
	public @NotNull CompletableFuture<Void> awaitInitialRecovery() {
		return initialRecovery.get();
	}

	/** Keeps durable ACTIVE/PENDING_RETURN rows intact so a restarted session can recover them. */
	public void shutdown() {
		if (!started.compareAndSet(true, false)) {
			return;
		}
		HandlerList.unregisterAll(this);
		activeSessions.clear();
		busyPlayers.clear();
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		recover(event.getPlayer());
	}

	public @NotNull CompletableFuture<AttendanceResult> register(@NotNull String sessionId,
	                                                             @NotNull UUID playerId) {
		if (!validSessionId(sessionId)) {
			return completed(AttendanceResult.Status.INVALID_SESSION, sessionId, null);
		}
		if (!sessionPolicy.get().canRegister(sessionId)) {
			return completed(AttendanceResult.Status.SESSION_NOT_OPEN, sessionId, null);
		}
		return database.registerAttendance(sessionId, playerId, now())
				.handle((record, error) -> error == null
						? AttendanceResult.of(registrationStatus(record.getState()),
							sessionId, record.getState())
						: persistenceFailure("register buyer " + playerId + " for " + sessionId,
							sessionId, error));
	}

	public @NotNull CompletableFuture<AttendanceResult> unregister(@NotNull String sessionId,
	                                                               @NotNull UUID playerId) {
		if (!validSessionId(sessionId)) {
			return completed(AttendanceResult.Status.INVALID_SESSION, sessionId, null);
		}
		if (!sessionPolicy.get().canRegister(sessionId)) {
			return completed(AttendanceResult.Status.SESSION_NOT_OPEN, sessionId, null);
		}
		return database.removeRegisteredAttendance(sessionId, playerId)
				.handle((removed, error) -> {
					if (error != null) {
						return persistenceFailure("unregister buyer " + playerId + " from " + sessionId,
								sessionId, error);
					}
					return AttendanceResult.of(Boolean.TRUE.equals(removed)
								? AttendanceResult.Status.UNREGISTERED
								: AttendanceResult.Status.NOT_REGISTERED,
							sessionId, Boolean.TRUE.equals(removed) ? null : AttendanceState.REGISTERED);
				});
	}

	/** Joins a running session; unregistered buyers are registered idempotently first. */
	public @NotNull CompletableFuture<AttendanceResult> enter(@NotNull Player player,
	                                                          @NotNull String sessionId) {
		if (!validSessionId(sessionId)) {
			return completed(AttendanceResult.Status.INVALID_SESSION, sessionId, null);
		}
		if (!sessionPolicy.get().isRunning(sessionId)) {
			return completed(AttendanceResult.Status.SESSION_NOT_RUNNING, sessionId, null);
		}
		String active = activeSessions.get(player.getUniqueId());
		if (sessionId.equals(active)) {
			return completed(AttendanceResult.Status.ALREADY_ACTIVE, sessionId, AttendanceState.ACTIVE);
		}
		if (active != null) {
			return completed(AttendanceResult.Status.PLAYER_BUSY, active, AttendanceState.ACTIVE);
		}
		return exclusive(player.getUniqueId(), sessionId, () -> beginEntryOnPlayerThread(player, sessionId));
	}

	/** Teleports all online pre-registered buyers exactly once at actual session start. */
	public @NotNull CompletableFuture<List<AttendanceResult>> enterRegisteredOnline(
			@NotNull String sessionId) {
		if (!sessionPolicy.get().isRunning(sessionId)) {
			return CompletableFuture.completedFuture(List.of(AttendanceResult.of(
					AttendanceResult.Status.SESSION_NOT_RUNNING, sessionId, null)));
		}
		return database.getAttendance(sessionId, List.of(AttendanceState.REGISTERED))
				.thenCompose(records -> onMainThread(() -> {
					List<CompletableFuture<AttendanceResult>> entries = new ArrayList<>();
					for (AuctionAttendanceRecord record : records) {
						Player player = plugin.getServer().getPlayer(record.getPlayerId());
						if (player != null && player.isOnline()) {
							entries.add(enter(player, sessionId));
						}
					}
					return collect(entries);
				}).thenCompose(future -> future))
				.exceptionally(error -> List.of(persistenceFailure(
						"load registered buyers for " + sessionId, sessionId, error)));
	}

	/** Opens no bidding privilege; this only returns an ACTIVE buyer to the configured region. */
	public @NotNull CompletableFuture<AttendanceResult> returnToVenue(@NotNull Player player) {
		String sessionId = activeSessions.get(player.getUniqueId());
		if (sessionId == null || !sessionPolicy.get().isRunning(sessionId)) {
			return completed(AttendanceResult.Status.NOT_ACTIVE, sessionId, null);
		}
		return resolveVenueOnMainThread().thenCompose(access -> {
			if (!access.enabled()) {
				return completed(AttendanceResult.Status.VENUE_DISABLED,
						sessionId, AttendanceState.ACTIVE);
			}
			if (!access.resolution().validation().valid()) {
				return completed(AttendanceResult.Status.VENUE_INVALID,
						sessionId, AttendanceState.ACTIVE);
			}
			return teleport(player, access.resolution().resolvedLayout().orElseThrow().buyerSpawn())
					.handle((success, error) -> AttendanceResult.of(
							error == null && Boolean.TRUE.equals(success)
									? AttendanceResult.Status.RETURNED_TO_VENUE
									: AttendanceResult.Status.TELEPORT_FAILED,
							sessionId, AttendanceState.ACTIVE));
		});
	}

	/** Leaves auction mode immediately, then returns online players to their durable origin. */
	public @NotNull CompletableFuture<AttendanceResult> leave(@NotNull Player player) {
		UUID playerId = player.getUniqueId();
		if (!busyPlayers.add(playerId)) {
			return completed(AttendanceResult.Status.PLAYER_BUSY,
					activeSessions.get(playerId), null);
		}
		CompletableFuture<AttendanceResult> operation = database.getAttendances(playerId, RETURNABLE_STATES)
				.thenCompose(records -> {
					AuctionAttendanceRecord record = chooseReturnRecord(records, activeSessions.get(playerId));
					if (record == null) {
						return completed(AttendanceResult.Status.NOT_ACTIVE, null, null);
					}
					return makePendingAndReturn(record, player);
				})
				.exceptionally(error -> persistenceFailure("leave auction mode for " + playerId,
						activeSessions.get(playerId), error));
		operation.whenComplete((ignored, error) -> busyPlayers.remove(playerId));
		return operation;
	}

	/** Ends registrations and returns every active buyer; offline returns remain durable. */
	public @NotNull CompletableFuture<List<AttendanceResult>> endSession(@NotNull String sessionId) {
		List<AttendanceState> states = List.of(AttendanceState.REGISTERED, AttendanceState.ENTERING,
				AttendanceState.ACTIVE, AttendanceState.PENDING_RETURN);
		return database.getAttendance(sessionId, states)
				.thenCompose(records -> onMainThread(() -> {
					List<CompletableFuture<AttendanceResult>> returns = new ArrayList<>();
					for (AuctionAttendanceRecord record : records) {
						if (record.getState() == AttendanceState.REGISTERED) {
							returns.add(closeRegistration(record));
							continue;
						}
						Player player = plugin.getServer().getPlayer(record.getPlayerId());
						returns.add(makePendingAndReturn(record,
								player != null && player.isOnline() ? player : null));
					}
					return collect(returns);
				}).thenCompose(future -> future))
				.exceptionally(error -> List.of(persistenceFailure(
						"end attendance for " + sessionId, sessionId, error)));
	}

	/** Handles PENDING_RETURN and crash-left ACTIVE/ENTERING state after login. */
	public @NotNull CompletableFuture<AttendanceResult> recover(@NotNull Player player) {
		UUID playerId = player.getUniqueId();
		if (!busyPlayers.add(playerId)) {
			return completed(AttendanceResult.Status.PLAYER_BUSY,
					activeSessions.get(playerId), null);
		}
		CompletableFuture<AttendanceResult> recovery = database.getAttendances(playerId, RETURNABLE_STATES)
				.thenCompose(records -> {
					AuctionAttendanceRecord record = chooseReturnRecord(records, activeSessions.get(playerId));
					if (record == null) {
						return completed(AttendanceResult.Status.NOT_ACTIVE, null, null);
					}
					if (record.getState() == AttendanceState.PENDING_RETURN
							|| !sessionPolicy.get().isRunning(record.getSessionId())) {
						return makePendingAndReturn(record, player);
					}
					return restoreRunningParticipant(record, player);
				})
				.exceptionally(error -> persistenceFailure("recover attendance for " + playerId,
						activeSessions.get(playerId), error));
		recovery.whenComplete((ignored, error) -> busyPlayers.remove(playerId));
		return recovery;
	}

	@Override
	public boolean isActive(@NotNull UUID playerId) {
		return activeSessions.containsKey(playerId);
	}

	@Override
	public @NotNull Optional<String> activeSession(@NotNull UUID playerId) {
		return Optional.ofNullable(activeSessions.get(playerId));
	}

	public boolean isInsideVenue(@NotNull Player player) {
		return venueConfig.resolve().resolvedLayout()
				.map(layout -> layout.bounds().contains(player.getLocation()))
				.orElse(false);
	}

	private CompletableFuture<AttendanceResult> beginEntryOnPlayerThread(Player player, String sessionId) {
		CompletableFuture<AttendanceResult> result = new CompletableFuture<>();
		scheduler.runPlayerRegionTask(() -> {
			if (!player.isOnline()) {
				result.complete(AttendanceResult.of(AttendanceResult.Status.PLAYER_OFFLINE,
						sessionId, null));
				return;
			}
			if (!venueConfig.isEnabled()) {
				result.complete(AttendanceResult.of(AttendanceResult.Status.VENUE_DISABLED,
						sessionId, null));
				return;
			}
			VenueResolution resolution = venueConfig.resolve();
			if (!resolution.validation().valid()) {
				result.complete(AttendanceResult.of(AttendanceResult.Status.VENUE_INVALID,
						sessionId, null));
				return;
			}
			Location origin = player.getLocation().clone();
			World originWorld = origin.getWorld();
			if (originWorld == null) {
				result.complete(AttendanceResult.of(AttendanceResult.Status.TELEPORT_FAILED,
						sessionId, null));
				return;
			}

			database.registerAttendance(sessionId, player.getUniqueId(), now())
					.thenCompose(record -> prepareEntry(record, origin))
					.whenComplete((prepared, error) -> {
						if (error != null) {
							result.complete(persistenceFailure("prepare venue entry for "
									+ player.getUniqueId(), sessionId, error));
							return;
						}
						if (!Boolean.TRUE.equals(prepared)) {
							boolean alreadyActive = sessionId.equals(activeSessions.get(player.getUniqueId()));
							result.complete(AttendanceResult.of(alreadyActive
										? AttendanceResult.Status.ALREADY_ACTIVE
										: AttendanceResult.Status.PLAYER_BUSY,
									sessionId, alreadyActive ? AttendanceState.ACTIVE : AttendanceState.ENTERING));
							return;
						}
						teleportAndActivate(player, sessionId,
								resolution.resolvedLayout().orElseThrow().buyerSpawn(), origin, result);
					});
		}, player);
		return result;
	}

	private CompletableFuture<Boolean> prepareEntry(AuctionAttendanceRecord record, Location origin) {
		if (record.getState() == AttendanceState.ACTIVE) {
			activeSessions.put(record.getPlayerId(), record.getSessionId());
			return CompletableFuture.completedFuture(false);
		}
		if (record.getState() != AttendanceState.REGISTERED) {
			return CompletableFuture.completedFuture(false);
		}
		return database.beginAttendanceEntry(record.getSessionId(), record.getPlayerId(),
				origin.getWorld().getName(), origin.getX(), origin.getY(), origin.getZ(),
				origin.getYaw(), origin.getPitch(), now());
	}

	private void teleportAndActivate(Player player, String sessionId, Location venue,
	                                 Location origin, CompletableFuture<AttendanceResult> result) {
		teleport(player, venue).whenComplete((teleported, teleportError) -> {
			if (teleportError != null || !Boolean.TRUE.equals(teleported)) {
				database.transitionAttendance(sessionId, player.getUniqueId(),
						AttendanceState.ENTERING, AttendanceState.REGISTERED, now())
						.whenComplete((rolledBack, rollbackError) -> result.complete(AttendanceResult.of(
								rollbackError == null && Boolean.TRUE.equals(rolledBack)
										? AttendanceResult.Status.TELEPORT_FAILED
										: AttendanceResult.Status.PERSISTENCE_FAILED,
								sessionId, rollbackError == null && Boolean.TRUE.equals(rolledBack)
										? AttendanceState.REGISTERED : AttendanceState.ENTERING)));
				return;
			}
			if (!sessionPolicy.get().isRunning(sessionId)) {
				database.transitionAttendance(sessionId, player.getUniqueId(),
						AttendanceState.ENTERING, AttendanceState.PENDING_RETURN, now())
						.whenComplete((pending, error) -> {
							if (error != null || !Boolean.TRUE.equals(pending)) {
								result.complete(persistenceFailure("mark interrupted venue entry pending for "
										+ player.getUniqueId(), sessionId,
										error == null ? new IllegalStateException("attendance state changed") : error));
								return;
							}
							teleport(player, origin).whenComplete((returned, returnError) -> {
								if (returnError != null || !Boolean.TRUE.equals(returned)) {
									result.complete(AttendanceResult.of(AttendanceResult.Status.RETURN_DEFERRED,
											sessionId, AttendanceState.PENDING_RETURN));
									return;
								}
								database.transitionAttendance(sessionId, player.getUniqueId(),
										AttendanceState.PENDING_RETURN, AttendanceState.LEFT, now())
										.whenComplete((left, leftError) -> result.complete(AttendanceResult.of(
												leftError == null && Boolean.TRUE.equals(left)
														? AttendanceResult.Status.SESSION_NOT_RUNNING
														: AttendanceResult.Status.PERSISTENCE_FAILED,
												sessionId, leftError == null && Boolean.TRUE.equals(left)
														? AttendanceState.LEFT : AttendanceState.PENDING_RETURN)));
							});
						});
				return;
			}
			database.transitionAttendance(sessionId, player.getUniqueId(),
					AttendanceState.ENTERING, AttendanceState.ACTIVE, now())
					.whenComplete((activated, error) -> {
						if (error == null && Boolean.TRUE.equals(activated)) {
							activeSessions.put(player.getUniqueId(), sessionId);
							result.complete(AttendanceResult.of(AttendanceResult.Status.ENTERED,
									sessionId, AttendanceState.ACTIVE));
							return;
						}
						compensateActivationFailure(player, sessionId, origin, result, error);
					});
		});
	}

	private void compensateActivationFailure(Player player, String sessionId, Location origin,
	                                         CompletableFuture<AttendanceResult> result,
	                                         Throwable activationError) {
		if (activationError != null) {
			logger.warning("Could not activate attendance for " + player.getUniqueId()
					+ " in " + sessionId, asException(activationError));
		}
		// Persist the obligation to return before attempting the teleport. If the return is
		// cancelled or fails exceptionally, recovery must retain enough state to retry it.
		database.transitionAttendance(sessionId, player.getUniqueId(),
				AttendanceState.ENTERING, AttendanceState.PENDING_RETURN, now())
				.whenComplete((pending, pendingError) -> {
					if (pendingError != null || !Boolean.TRUE.equals(pending)) {
						if (pendingError != null) {
							logger.warning("Could not mark failed attendance activation pending for "
									+ player.getUniqueId() + " in " + sessionId,
									asException(pendingError));
						}
						result.complete(AttendanceResult.of(AttendanceResult.Status.PERSISTENCE_FAILED,
								sessionId, AttendanceState.ENTERING));
						return;
					}

					teleport(player, origin).whenComplete((returned, returnError) -> {
						if (returnError != null || !Boolean.TRUE.equals(returned)) {
							if (returnError != null) {
								logger.warning("Could not return buyer after failed attendance activation for "
										+ player.getUniqueId() + " in " + sessionId,
										asException(returnError));
							}
							result.complete(AttendanceResult.of(AttendanceResult.Status.RETURN_DEFERRED,
									sessionId, AttendanceState.PENDING_RETURN));
							return;
						}

						database.transitionAttendance(sessionId, player.getUniqueId(),
								AttendanceState.PENDING_RETURN, AttendanceState.REGISTERED, now())
								.whenComplete((registered, rollbackError) -> {
									if (rollbackError != null) {
										logger.warning("Could not restore buyer registration after failed activation for "
												+ player.getUniqueId() + " in " + sessionId,
												asException(rollbackError));
									}
									result.complete(AttendanceResult.of(
											AttendanceResult.Status.PERSISTENCE_FAILED, sessionId,
											rollbackError == null && Boolean.TRUE.equals(registered)
													? AttendanceState.REGISTERED
													: AttendanceState.PENDING_RETURN));
								});
					});
				});
	}

	private CompletableFuture<AttendanceResult> makePendingAndReturn(AuctionAttendanceRecord record,
	                                                                @Nullable Player player) {
		activeSessions.remove(record.getPlayerId(), record.getSessionId());
		CompletableFuture<Boolean> pending;
		if (record.getState() == AttendanceState.PENDING_RETURN) {
			pending = CompletableFuture.completedFuture(true);
		} else {
			pending = database.transitionAttendance(record.getSessionId(), record.getPlayerId(),
					record.getState(), AttendanceState.PENDING_RETURN, now());
		}
		return pending.thenCompose(changed -> {
			if (!Boolean.TRUE.equals(changed)) {
				return database.getAttendance(record.getSessionId(), record.getPlayerId())
						.thenCompose(latest -> latest.isPresent()
								&& latest.get().getState() == AttendanceState.PENDING_RETURN
								? returnPending(latest.get(), player)
								: completed(AttendanceResult.Status.PERSISTENCE_FAILED,
								record.getSessionId(), record.getState()));
			}
			return returnPending(record, player);
		});
	}

	private CompletableFuture<AttendanceResult> returnPending(AuctionAttendanceRecord record,
	                                                          @Nullable Player player) {
		if (!record.hasReturnLocation()) {
			return completed(AttendanceResult.Status.RETURN_LOCATION_MISSING,
					record.getSessionId(), AttendanceState.PENDING_RETURN);
		}
		if (player == null || !player.isOnline()) {
			return completed(AttendanceResult.Status.RETURN_DEFERRED,
					record.getSessionId(), AttendanceState.PENDING_RETURN);
		}
		return onMainThread(() -> plugin.getServer().getWorld(record.getReturnWorld()))
				.thenCompose(world -> {
			if (world == null) {
				return completed(AttendanceResult.Status.RETURN_DEFERRED,
						record.getSessionId(), AttendanceState.PENDING_RETURN);
			}
			Location destination = new Location(world, record.getReturnX(), record.getReturnY(),
					record.getReturnZ(), record.getReturnYaw(), record.getReturnPitch());
			return teleport(player, destination).thenCompose(returned -> {
				if (!Boolean.TRUE.equals(returned)) {
					return completed(AttendanceResult.Status.TELEPORT_FAILED,
							record.getSessionId(), AttendanceState.PENDING_RETURN);
				}
				return database.transitionAttendance(record.getSessionId(), record.getPlayerId(),
						AttendanceState.PENDING_RETURN, AttendanceState.LEFT, now())
						.thenApply(completed -> AttendanceResult.of(Boolean.TRUE.equals(completed)
									? AttendanceResult.Status.LEFT
									: AttendanceResult.Status.PERSISTENCE_FAILED,
								record.getSessionId(), Boolean.TRUE.equals(completed)
										? AttendanceState.LEFT : AttendanceState.PENDING_RETURN));
			});
		});
	}

	private CompletableFuture<AttendanceResult> closeRegistration(AuctionAttendanceRecord record) {
		return database.transitionAttendance(record.getSessionId(), record.getPlayerId(),
				AttendanceState.REGISTERED, AttendanceState.LEFT, now())
				.thenApply(changed -> AttendanceResult.of(Boolean.TRUE.equals(changed)
						? AttendanceResult.Status.LEFT : AttendanceResult.Status.PERSISTENCE_FAILED,
						record.getSessionId(), Boolean.TRUE.equals(changed)
								? AttendanceState.LEFT : AttendanceState.REGISTERED));
	}

	private CompletableFuture<AttendanceResult> restoreRunningParticipant(AuctionAttendanceRecord record,
	                                                                      Player player) {
		return resolveVenueOnMainThread().thenCompose(access -> {
		if (!access.enabled()) {
			return completed(AttendanceResult.Status.VENUE_DISABLED,
					record.getSessionId(), record.getState());
		}
		if (!access.resolution().validation().valid()) {
			return completed(AttendanceResult.Status.VENUE_INVALID,
					record.getSessionId(), record.getState());
		}
		return teleport(player, access.resolution().resolvedLayout().orElseThrow().buyerSpawn())
				.thenCompose(teleported -> {
					if (!Boolean.TRUE.equals(teleported)) {
						return completed(AttendanceResult.Status.TELEPORT_FAILED,
								record.getSessionId(), record.getState());
					}
					// The async teleport may complete after the session-end callback already ran.
					// Never re-enable auction mode for a session that ended while the player moved.
					if (!sessionPolicy.get().isRunning(record.getSessionId())) {
						return makePendingAndReturn(record, player);
					}
					if (record.getState() == AttendanceState.ACTIVE) {
						activeSessions.put(record.getPlayerId(), record.getSessionId());
						return completed(AttendanceResult.Status.ENTERED,
								record.getSessionId(), AttendanceState.ACTIVE);
					}
					return database.transitionAttendance(record.getSessionId(), record.getPlayerId(),
							AttendanceState.ENTERING, AttendanceState.ACTIVE, now())
							.thenApply(changed -> {
								if (Boolean.TRUE.equals(changed)) {
									activeSessions.put(record.getPlayerId(), record.getSessionId());
								}
								return AttendanceResult.of(Boolean.TRUE.equals(changed)
										? AttendanceResult.Status.ENTERED
										: AttendanceResult.Status.PERSISTENCE_FAILED,
										record.getSessionId(), Boolean.TRUE.equals(changed)
											? AttendanceState.ACTIVE : AttendanceState.ENTERING);
							});
				});
		});
	}

	private CompletableFuture<ResolvedVenue> resolveVenueOnMainThread() {
		return onMainThread(() -> new ResolvedVenue(venueConfig.isEnabled(), venueConfig.resolve()));
	}

	private CompletableFuture<Boolean> teleport(Player player, Location destination) {
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		scheduler.runPlayerRegionTask(() -> {
			if (!player.isOnline()) {
				result.complete(false);
				return;
			}
			try {
				player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
						.whenComplete((success, error) -> {
							if (error != null) {
								result.completeExceptionally(error);
							} else {
								result.complete(Boolean.TRUE.equals(success));
							}
						});
			} catch (RuntimeException error) {
				result.completeExceptionally(error);
			}
		}, player);
		return result;
	}

	private <T> CompletableFuture<T> onMainThread(Supplier<T> supplier) {
		CompletableFuture<T> result = new CompletableFuture<>();
		scheduler.runSyncTask(() -> {
			try {
				result.complete(supplier.get());
			} catch (RuntimeException error) {
				result.completeExceptionally(error);
			}
		});
		return result;
	}

	private CompletableFuture<AttendanceResult> exclusive(UUID playerId, String sessionId,
	                                                       Supplier<CompletableFuture<AttendanceResult>> supplier) {
		if (!busyPlayers.add(playerId)) {
			return completed(AttendanceResult.Status.PLAYER_BUSY, sessionId, null);
		}
		CompletableFuture<AttendanceResult> result;
		try {
			result = supplier.get();
		} catch (RuntimeException error) {
			busyPlayers.remove(playerId);
			return completed(persistenceFailure("start attendance operation for " + playerId,
					sessionId, error));
		}
		result.whenComplete((ignored, error) -> busyPlayers.remove(playerId));
		return result;
	}

	private AuctionAttendanceRecord chooseReturnRecord(List<AuctionAttendanceRecord> records,
	                                                   @Nullable String preferredSession) {
		return records.stream()
				.sorted(Comparator.comparing((AuctionAttendanceRecord record) ->
						!record.getSessionId().equals(preferredSession))
						.thenComparingLong(AuctionAttendanceRecord::getCreatedAtMillis))
				.findFirst().orElse(null);
	}

	private CompletableFuture<List<AttendanceResult>> collect(
			List<CompletableFuture<AttendanceResult>> futures) {
		if (futures.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}
		CompletableFuture<?>[] array = futures.toArray(CompletableFuture[]::new);
		return CompletableFuture.allOf(array)
				.thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
	}

	private AttendanceResult persistenceFailure(String operation, @Nullable String sessionId,
	                                            Throwable error) {
		logger.warning("Could not " + operation, asException(error));
		return AttendanceResult.of(AttendanceResult.Status.PERSISTENCE_FAILED, sessionId, null);
	}

	private Exception asException(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null
				&& (current instanceof java.util.concurrent.CompletionException
				|| current instanceof java.util.concurrent.ExecutionException)) {
			current = current.getCause();
		}
		return current instanceof Exception exception ? exception : new RuntimeException(current);
	}

	private boolean validSessionId(String sessionId) {
		return sessionId != null && !sessionId.isBlank();
	}

	private AttendanceResult.Status registrationStatus(AttendanceState state) {
		return switch (state) {
			case REGISTERED -> AttendanceResult.Status.REGISTERED;
			case ACTIVE -> AttendanceResult.Status.ALREADY_ACTIVE;
			case ENTERING -> AttendanceResult.Status.PLAYER_BUSY;
			case PENDING_RETURN -> AttendanceResult.Status.RETURN_DEFERRED;
			case LEFT -> AttendanceResult.Status.ALREADY_LEFT;
		};
	}

	private long now() {
		return clock.millis();
	}

	private CompletableFuture<AttendanceResult> completed(AttendanceResult.Status status,
	                                                      @Nullable String sessionId,
	                                                      @Nullable AttendanceState state) {
		return CompletableFuture.completedFuture(AttendanceResult.of(status, sessionId, state));
	}

	private CompletableFuture<AttendanceResult> completed(AttendanceResult result) {
		return CompletableFuture.completedFuture(result);
	}

	private record ResolvedVenue(boolean enabled, VenueResolution resolution) {
	}
}
