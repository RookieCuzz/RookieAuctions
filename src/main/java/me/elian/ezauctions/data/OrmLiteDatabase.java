package me.elian.ezauctions.data;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.jdbc.JdbcPooledConnectionSource;
import com.j256.ormlite.misc.TransactionManager;
import com.j256.ormlite.stmt.QueryBuilder;
import com.j256.ormlite.stmt.UpdateBuilder;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.table.TableUtils;
import me.elian.ezauctions.Logger;
import me.elian.ezauctions.controller.ConfigController;
import me.elian.ezauctions.model.AuctionBidRecord;
import me.elian.ezauctions.model.AuctionBidTransaction;
import me.elian.ezauctions.model.AuctionAttendanceRecord;
import me.elian.ezauctions.model.AuctionPlayer;
import me.elian.ezauctions.model.AuctionPlayerIgnore;
import me.elian.ezauctions.model.AuctionRecord;
import me.elian.ezauctions.model.AuctionRecordStatus;
import me.elian.ezauctions.model.AuctionRuntimeCheckpoint;
import me.elian.ezauctions.model.AuctionSessionLot;
import me.elian.ezauctions.model.AuctionSessionRecord;
import me.elian.ezauctions.model.AuctionSubmissionTransaction;
import me.elian.ezauctions.model.BidTransactionState;
import me.elian.ezauctions.model.RewardKind;
import me.elian.ezauctions.model.RewardRecord;
import me.elian.ezauctions.model.RewardState;
import me.elian.ezauctions.model.SavedItem;
import me.elian.ezauctions.model.SubmissionTransactionState;
import me.elian.ezauctions.session.AttendanceState;
import me.elian.ezauctions.session.LotState;
import me.elian.ezauctions.session.ReservationStatus;
import me.elian.ezauctions.session.SessionState;
import me.elian.ezauctions.session.SubmissionResult;
import me.elian.ezauctions.session.WithdrawalResult;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Singleton
public class OrmLiteDatabase implements Database {
	private final Logger logger;
	private final ConfigController config;
	private final Plugin plugin;
	private final String fixedConnectionString;
	private final Object connectingMonitor = new Object();
	private final Object pendingOperationsMonitor = new Object();
	private final AtomicInteger pendingOperations = new AtomicInteger();
	private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "RookieAuctions-database");
		thread.setDaemon(true);
		return thread;
	});
	private boolean connecting;
	private ConnectionSource connectionSource;
	private Dao<AuctionPlayer, UUID> auctionPlayerDao;
	private Dao<AuctionRecord, String> auctionRecordDao;
	private Dao<RewardRecord, String> rewardDao;
	private Dao<AuctionBidRecord, String> bidRecordDao;
	private Dao<AuctionSessionRecord, String> sessionDao;
	private Dao<AuctionSessionLot, String> sessionLotDao;
	private Dao<AuctionAttendanceRecord, String> attendanceDao;
	private Dao<AuctionRuntimeCheckpoint, String> runtimeCheckpointDao;
	private Dao<AuctionBidTransaction, String> bidTransactionDao;
	private Dao<AuctionSubmissionTransaction, String> submissionTransactionDao;

	@Inject
	public OrmLiteDatabase(Logger logger, ConfigController config, Plugin plugin) {
		this.logger = logger;
		this.config = config;
		this.plugin = plugin;
		this.fixedConnectionString = null;
		databaseExecutor.execute(() -> {
			try {
				reconnect();
			} catch (Exception e) {
				logger.severe("Could not connect to database! Check your connection string!", e);
			}
		});
	}

	@Override
	public @NotNull CompletableFuture<AuctionPlayer> getAuctionPlayer(@NotNull UUID id) {
		return submit(() -> {
			awaitConnection();
			AuctionPlayer auctionPlayer = auctionPlayerDao.queryForId(id);
			if (auctionPlayer == null) {
				auctionPlayerDao.create(new AuctionPlayer(id));
				auctionPlayer = auctionPlayerDao.queryForId(id);
			}
			return auctionPlayer;
		});
	}

	/** Synchronous, package-private SQLite entry point used by data-layer tests. */
	OrmLiteDatabase(@NotNull Logger logger, @NotNull String connectionString) throws SQLException {
		this.logger = logger;
		this.config = null;
		this.plugin = null;
		this.fixedConnectionString = connectionString;
		reconnect();
	}

	@Override
	public void saveAuctionPlayer(@NotNull AuctionPlayer ap) {
		submit(() -> {
			awaitConnection();
			auctionPlayerDao.createOrUpdate(ap);
			return null;
		});
	}

	@Override
	public @NotNull CompletableFuture<Void> createAuctionRecord(@NotNull AuctionRecord record) {
		return submit(() -> {
			awaitConnection();
			auctionRecordDao.create(record);
			return null;
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> transitionAuction(@NotNull UUID auctionId,
	                                                            @NotNull AuctionRecordStatus expected,
	                                                            @NotNull AuctionRecordStatus next) {
		return submit(() -> {
			awaitConnection();
			UpdateBuilder<AuctionRecord, String> update = auctionRecordDao.updateBuilder();
			update.updateColumnValue("status", next.name());
			if (next == AuctionRecordStatus.ACTIVE) {
				update.updateColumnValue("startedAtMillis", System.currentTimeMillis());
			}
			if (next == AuctionRecordStatus.COMPLETED || next == AuctionRecordStatus.CANCELLED) {
				update.updateColumnValue("completedAtMillis", System.currentTimeMillis());
			}
			update.where()
					.eq("id", auctionId.toString())
					.and()
					.eq("status", expected.name());
			return update.update() == 1;
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> completeAuction(@NotNull UUID auctionId,
	                                                          @Nullable UUID winnerId,
	                                                          long finalPriceMinor,
	                                                          long payoutMinor,
	                                                          long taxMinor,
	                                                          @NotNull String itemDestination,
	                                                          @NotNull String refundStatus,
	                                                          long completedAtMillis) {
		return submit(() -> {
			awaitConnection();
			UpdateBuilder<AuctionRecord, String> update = auctionRecordDao.updateBuilder();
			update.updateColumnValue("status", AuctionRecordStatus.COMPLETED.name());
			update.updateColumnValue("completedAtMillis", completedAtMillis);
			update.updateColumnValue("winnerId", winnerId == null ? null : winnerId.toString());
			update.updateColumnValue("finalPriceMinor", finalPriceMinor);
			update.updateColumnValue("payoutMinor", payoutMinor);
			update.updateColumnValue("taxMinor", taxMinor);
			update.updateColumnValue("itemDestination", itemDestination);
			update.updateColumnValue("refundStatus", refundStatus);
			update.where().eq("id", auctionId.toString()).and()
					.eq("status", AuctionRecordStatus.ACTIVE.name());
			return update.update() == 1;
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> completeAuctionWithRewards(
			@NotNull UUID auctionId, @Nullable UUID winnerId,
			long finalPriceMinor, long payoutMinor, long taxMinor,
			@NotNull String itemDestination, @NotNull String refundStatus,
			long completedAtMillis, @NotNull Collection<RewardRecord> rewards) {
		List<RewardRecord> durableRewards = List.copyOf(rewards);
		return submit(() -> {
			awaitConnection();
			return TransactionManager.callInTransaction(connectionSource, () -> {
				AuctionRecord current = auctionRecordDao.queryForId(auctionId.toString());
				if (current == null) {
					return false;
				}
				if (current.getStatus() == AuctionRecordStatus.COMPLETED) {
					return true;
				}
				if (current.getStatus() != AuctionRecordStatus.ACTIVE) {
					return false;
				}
				createSettlementRewards(auctionId, durableRewards);
				UpdateBuilder<AuctionRecord, String> update = auctionRecordDao.updateBuilder();
				update.updateColumnValue("status", AuctionRecordStatus.COMPLETED.name());
				update.updateColumnValue("completedAtMillis", completedAtMillis);
				update.updateColumnValue("winnerId", winnerId == null ? null : winnerId.toString());
				update.updateColumnValue("finalPriceMinor", finalPriceMinor);
				update.updateColumnValue("payoutMinor", payoutMinor);
				update.updateColumnValue("taxMinor", taxMinor);
				update.updateColumnValue("itemDestination", itemDestination);
				update.updateColumnValue("refundStatus", refundStatus);
				update.where().eq("id", auctionId.toString()).and()
						.eq("status", AuctionRecordStatus.ACTIVE.name());
				if (update.update() != 1) {
					throw new SQLException("Auction state changed during settlement: " + auctionId);
				}
				return true;
			});
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> cancelAuction(@NotNull UUID auctionId,
	                                                        @NotNull AuctionRecordStatus expected,
	                                                        @NotNull String itemDestination,
	                                                        @NotNull String refundStatus,
	                                                        long completedAtMillis) {
		return cancelAuction(auctionId, List.of(expected), itemDestination, refundStatus,
				completedAtMillis);
	}

	@Override
	public @NotNull CompletableFuture<Boolean> cancelAuction(@NotNull UUID auctionId,
	                                                        @NotNull Collection<AuctionRecordStatus> expected,
	                                                        @NotNull String itemDestination,
	                                                        @NotNull String refundStatus,
	                                                        long completedAtMillis) {
		if (expected.isEmpty()) {
			return CompletableFuture.completedFuture(false);
		}
		return submit(() -> {
			awaitConnection();
			UpdateBuilder<AuctionRecord, String> update = auctionRecordDao.updateBuilder();
			update.updateColumnValue("status", AuctionRecordStatus.CANCELLED.name());
			update.updateColumnValue("completedAtMillis", completedAtMillis);
			update.updateColumnValue("itemDestination", itemDestination);
			update.updateColumnValue("refundStatus", refundStatus);
			update.where().eq("id", auctionId.toString()).and()
					.in("status", expected.stream().map(Enum::name).toList());
			return update.update() == 1;
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> cancelAuctionWithRewards(
			@NotNull UUID auctionId, @NotNull Collection<AuctionRecordStatus> expected,
			@NotNull String itemDestination, @NotNull String refundStatus,
			long completedAtMillis, @NotNull Collection<RewardRecord> rewards) {
		List<AuctionRecordStatus> allowed = List.copyOf(expected);
		List<RewardRecord> durableRewards = List.copyOf(rewards);
		if (allowed.isEmpty()) {
			return CompletableFuture.completedFuture(false);
		}
		return submit(() -> {
			awaitConnection();
			return TransactionManager.callInTransaction(connectionSource, () -> {
				AuctionRecord current = auctionRecordDao.queryForId(auctionId.toString());
				if (current == null) {
					return false;
				}
				if (current.getStatus() == AuctionRecordStatus.CANCELLED) {
					return true;
				}
				if (!allowed.contains(current.getStatus())) {
					return false;
				}
				createSettlementRewards(auctionId, durableRewards);
				UpdateBuilder<AuctionRecord, String> update = auctionRecordDao.updateBuilder();
				update.updateColumnValue("status", AuctionRecordStatus.CANCELLED.name());
				update.updateColumnValue("completedAtMillis", completedAtMillis);
				update.updateColumnValue("itemDestination", itemDestination);
				update.updateColumnValue("refundStatus", refundStatus);
				update.where().eq("id", auctionId.toString()).and()
						.in("status", allowed.stream().map(Enum::name).toList());
				if (update.update() != 1) {
					throw new SQLException("Auction state changed during cancellation: " + auctionId);
				}
				return true;
			});
		});
	}

	private void createSettlementRewards(UUID auctionId,
	                                     Collection<RewardRecord> rewards) throws SQLException {
		for (RewardRecord reward : rewards) {
			if (!auctionId.equals(reward.getAuctionId())) {
				throw new SQLException("Settlement reward does not belong to auction " + auctionId);
			}
			rewardDao.createIfNotExists(reward);
		}
	}

	@Override
	public @NotNull CompletableFuture<Void> saveAuctionRecord(@NotNull AuctionRecord record) {
		return submit(() -> {
			awaitConnection();
			auctionRecordDao.createOrUpdate(record);
			return null;
		});
	}

	@Override
	public @NotNull CompletableFuture<Optional<AuctionRecord>> getAuctionRecord(@NotNull UUID auctionId) {
		return submit(() -> {
			awaitConnection();
			return Optional.ofNullable(auctionRecordDao.queryForId(auctionId.toString()));
		});
	}

	@Override
	public @NotNull CompletableFuture<List<AuctionRecord>> getAuctionRecords(@NotNull UUID ownerId) {
		return submit(() -> {
			awaitConnection();
			QueryBuilder<AuctionRecord, String> query = auctionRecordDao.queryBuilder();
			query.orderBy("createdAtMillis", false);
			query.where().eq("auctioneerId", ownerId.toString());
			return query.query();
		});
	}

	@Override
	public @NotNull CompletableFuture<List<AuctionRecord>> getAuctionsByStatus(
			@NotNull Collection<AuctionRecordStatus> statuses) {
		return submit(() -> {
			awaitConnection();
			if (statuses.isEmpty()) {
				return List.of();
			}
			List<String> names = statuses.stream().map(Enum::name).toList();
			QueryBuilder<AuctionRecord, String> query = auctionRecordDao.queryBuilder();
			query.orderBy("createdAtMillis", true);
			query.where().in("status", names);
			return query.query();
		});
	}

	@Override
	public @NotNull CompletableFuture<Void> createReward(@NotNull RewardRecord reward) {
		return submit(() -> {
			awaitConnection();
			rewardDao.createIfNotExists(reward);
			return null;
		});
	}

	@Override
	public @NotNull CompletableFuture<List<RewardRecord>> getRewards(@NotNull UUID ownerId,
	                                                                @NotNull Collection<RewardKind> kinds,
	                                                                boolean includeClaimed) {
		return submit(() -> {
			awaitConnection();
			if (kinds.isEmpty()) {
				return List.of();
			}
			QueryBuilder<RewardRecord, String> query = rewardDao.queryBuilder();
			query.orderBy("createdAtMillis", false);
			var where = query.where()
					.eq("ownerId", ownerId.toString())
					.and()
					.in("kind", kinds.stream().map(Enum::name).toList());
			if (!includeClaimed) {
				where.and().ne("state", RewardState.DONE.name());
			}
			return query.query();
		});
	}

	@Override
	public @NotNull CompletableFuture<Optional<RewardRecord>> tryBeginRewardClaim(@NotNull UUID rewardId,
	                                                                             @NotNull UUID ownerId) {
		return submit(() -> {
			awaitConnection();
			UpdateBuilder<RewardRecord, String> update = rewardDao.updateBuilder();
			update.updateColumnValue("state", RewardState.CLAIMING.name());
			update.where()
					.eq("id", rewardId.toString())
					.and()
					.eq("ownerId", ownerId.toString())
					.and()
					.eq("state", RewardState.PENDING.name());
			if (update.update() != 1) {
				return Optional.empty();
			}
			return Optional.ofNullable(rewardDao.queryForId(rewardId.toString()));
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> finishRewardClaim(@NotNull UUID rewardId,
	                                                            @NotNull UUID ownerId) {
		return transitionReward(rewardId, ownerId, RewardState.CLAIMING, RewardState.DONE);
	}

	@Override
	public @NotNull CompletableFuture<Boolean> releaseRewardClaim(@NotNull UUID rewardId,
	                                                             @NotNull UUID ownerId) {
		return transitionReward(rewardId, ownerId, RewardState.CLAIMING, RewardState.PENDING);
	}

	@Override
	public @NotNull CompletableFuture<Void> createBidRecord(@NotNull AuctionBidRecord bidRecord) {
		return submit(() -> {
			awaitConnection();
			TransactionManager.callInTransaction(connectionSource, () -> {
				AuctionBidRecord existing = bidRecordDao.queryForId(bidRecord.getId().toString());
				if (existing == null) {
					bidRecordDao.create(bidRecord);
				} else if (!sameBidRecord(existing, bidRecord)) {
					throw new SQLException("Bid record id reused with a different payload: "
							+ bidRecord.getId());
				}
				return null;
			});
			return null;
		});
	}

	@Override
	public @NotNull CompletableFuture<Optional<AuctionBidRecord>> getBidRecord(
			@NotNull UUID bidRecordId) {
		return submit(() -> {
			awaitConnection();
			return Optional.ofNullable(bidRecordDao.queryForId(bidRecordId.toString()));
		});
	}

	@Override
	public @NotNull CompletableFuture<List<AuctionBidRecord>> getBidRecords(@NotNull UUID auctionId) {
		return submit(() -> {
			awaitConnection();
			QueryBuilder<AuctionBidRecord, String> query = bidRecordDao.queryBuilder();
			query.orderBy("createdAtMillis", true);
			query.where().eq("auctionId", auctionId.toString());
			return query.query();
		});
	}

	@Override
	public @NotNull CompletableFuture<AuctionSessionRecord> createSessionIfAbsent(
			@NotNull AuctionSessionRecord session) {
		return submit(() -> {
			awaitConnection();
			return TransactionManager.callInTransaction(connectionSource, () -> {
				AuctionSessionRecord existing = sessionDao.queryForId(session.getId());
				if (existing == null) {
					sessionDao.create(session);
					return session;
				}
				// A generated session is immutable. A schedule/config reload can propose different
				// values for the same wall-clock key, but those values only apply to sessions which
				// have not yet been persisted.
				return existing;
			});
		});
	}

	@Override
	public @NotNull CompletableFuture<Optional<AuctionSessionRecord>> getSession(
			@NotNull String sessionId) {
		return submit(() -> {
			awaitConnection();
			return Optional.ofNullable(sessionDao.queryForId(sessionId));
		});
	}

	@Override
	public @NotNull CompletableFuture<List<AuctionSessionRecord>> getSessionsStartingAtOrAfter(
			long startInclusiveMillis, int limit) {
		if (limit <= 0) {
			return CompletableFuture.completedFuture(List.of());
		}
		return submit(() -> {
			awaitConnection();
			QueryBuilder<AuctionSessionRecord, String> query = sessionDao.queryBuilder();
			query.orderBy("scheduledStartMillis", true).limit((long) limit);
			query.where().ge("scheduledStartMillis", startInclusiveMillis);
			return query.query();
		});
	}

	@Override
	public @NotNull CompletableFuture<List<AuctionSessionRecord>> getSessionsStartingBetween(
			long startInclusiveMillis, long startExclusiveMillis) {
		if (startExclusiveMillis <= startInclusiveMillis) {
			return CompletableFuture.completedFuture(List.of());
		}
		return submit(() -> {
			awaitConnection();
			QueryBuilder<AuctionSessionRecord, String> query = sessionDao.queryBuilder();
			query.orderBy("scheduledStartMillis", true);
			query.where()
					.ge("scheduledStartMillis", startInclusiveMillis)
					.and()
					.lt("scheduledStartMillis", startExclusiveMillis);
			return query.query();
		});
	}

	@Override
	public @NotNull CompletableFuture<List<AuctionSessionRecord>> getSessionsByState(
			@NotNull Collection<SessionState> states) {
		if (states.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}
		return submit(() -> {
			awaitConnection();
			QueryBuilder<AuctionSessionRecord, String> query = sessionDao.queryBuilder();
			query.orderBy("scheduledStartMillis", true);
			query.where().in("state", enumNames(states));
			return query.query();
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> transitionSession(@NotNull String sessionId,
	                                                            @NotNull SessionState expected,
	                                                            @NotNull SessionState next,
	                                                            long changedAtMillis) {
		return submit(() -> {
			awaitConnection();
			UpdateBuilder<AuctionSessionRecord, String> update = sessionDao.updateBuilder();
			update.updateColumnValue("state", next.name());
			update.updateColumnExpression("revision", "revision + 1");
			if (next == SessionState.RUNNING) {
				update.updateColumnValue("startedAtMillis", changedAtMillis);
			}
			if (next == SessionState.COMPLETED || next == SessionState.SKIPPED) {
				update.updateColumnValue("completedAtMillis", changedAtMillis);
			}
			update.where()
					.eq("id", sessionId)
					.and()
					.eq("state", expected.name());
			return update.update() == 1;
		});
	}

	@Override
	public @NotNull CompletableFuture<SubmissionResult> reserveSessionLot(
			@NotNull String sessionId, @NotNull UUID auctionId, @NotNull UUID sellerId,
			long createdAtMillis) {
		return submit(() -> {
			awaitConnection();
			return TransactionManager.callInTransaction(connectionSource,
					() -> reserveSessionLotInTransaction(sessionId, auctionId, sellerId,
							createdAtMillis));
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> cancelSessionLot(@NotNull String sessionId,
	                                                           @NotNull UUID auctionId,
	                                                           @NotNull UUID sellerId,
	                                                           long cancelledAtMillis) {
		return submit(() -> {
			awaitConnection();
			return TransactionManager.callInTransaction(connectionSource, () -> {
				touchSessionRow(sessionId);
				AuctionSessionRecord session = sessionDao.queryForId(sessionId);
				if (session == null || session.getState() != SessionState.OPEN
						|| cancelledAtMillis >= session.getLockAtMillis()) {
					return false;
				}
				UpdateBuilder<AuctionSessionLot, String> update = sessionLotDao.updateBuilder();
				update.updateColumnValue("state", LotState.CANCELLED.name());
				update.updateColumnValue("updatedAtMillis", cancelledAtMillis);
				update.where()
						.eq("id", AuctionSessionLot.idFor(sessionId, auctionId).toString())
						.and()
						.eq("sessionId", sessionId)
						.and()
						.eq("sellerId", sellerId.toString())
						.and()
						.in("state", List.of(LotState.RESERVED.name(), LotState.QUEUED.name()));
				return update.update() == 1;
			});
		});
	}

	@Override
	public @NotNull CompletableFuture<Optional<AuctionSessionLot>> getSessionLot(@NotNull UUID lotId) {
		return submit(() -> {
			awaitConnection();
			return Optional.ofNullable(sessionLotDao.queryForId(lotId.toString()));
		});
	}

	@Override
	public @NotNull CompletableFuture<Optional<AuctionSessionLot>> getSessionLot(
			@NotNull String sessionId, @NotNull UUID auctionId) {
		return getSessionLot(AuctionSessionLot.idFor(sessionId, auctionId));
	}

	@Override
	public @NotNull CompletableFuture<List<AuctionSessionLot>> getSessionLots(
			@NotNull String sessionId) {
		return submit(() -> {
			awaitConnection();
			QueryBuilder<AuctionSessionLot, String> query = sessionLotDao.queryBuilder();
			query.orderBy("sequenceNumber", true).orderBy("createdAtMillis", true);
			query.where().eq("sessionId", sessionId);
			return query.query();
		});
	}

	@Override
	public @NotNull CompletableFuture<List<AuctionSessionLot>> getSessionLotsByAuctionId(
			@NotNull UUID auctionId) {
		return submit(() -> {
			awaitConnection();
			QueryBuilder<AuctionSessionLot, String> query = sessionLotDao.queryBuilder();
			query.orderBy("createdAtMillis", true);
			query.where().eq("auctionId", auctionId.toString());
			return query.query();
		});
	}

	@Override
	public @NotNull CompletableFuture<Optional<AuctionSessionLot>> getSessionLotByAuction(
			@NotNull UUID auctionId) {
		return submit(() -> {
			awaitConnection();
			QueryBuilder<AuctionSessionLot, String> query = sessionLotDao.queryBuilder();
			query.orderBy("createdAtMillis", false).limit(1L);
			query.where()
					.eq("auctionId", auctionId.toString())
					.and()
					.notIn("state", unoccupiedLotStates());
			return Optional.ofNullable(query.queryForFirst());
		});
	}

	@Override
	public @NotNull CompletableFuture<WithdrawalResult> withdrawSessionLot(
			@NotNull String sessionId, @NotNull UUID auctionId, @NotNull UUID sellerId,
			long withdrawnAtMillis) {
		return submit(() -> {
			awaitConnection();
			try {
				return TransactionManager.callInTransaction(connectionSource,
						() -> withdrawSessionLotInTransaction(sessionId, auctionId, sellerId,
								withdrawnAtMillis));
			} catch (Exception exception) {
				logger.severe("Could not atomically withdraw auction " + auctionId
						+ " from session " + sessionId, asException(exception));
				return new WithdrawalResult(WithdrawalResult.Status.PERSISTENCE_FAILED,
						sessionId, auctionId);
			}
		});
	}

	@Override
	public @NotNull CompletableFuture<SubmissionResult> moveSessionLot(
			@NotNull String sourceSessionId, @NotNull String targetSessionId,
			@NotNull UUID auctionId, @NotNull UUID sellerId, long movedAtMillis) {
		return submit(() -> {
			awaitConnection();
			return TransactionManager.callInTransaction(connectionSource,
					() -> moveSessionLotInTransaction(sourceSessionId, targetSessionId,
							auctionId, sellerId, movedAtMillis));
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> transitionSessionLot(@NotNull UUID lotId,
	                                                               @NotNull LotState expected,
	                                                               @NotNull LotState next,
	                                                               long changedAtMillis) {
		return submit(() -> {
			awaitConnection();
			UpdateBuilder<AuctionSessionLot, String> update = sessionLotDao.updateBuilder();
			update.updateColumnValue("state", next.name());
			update.updateColumnValue("updatedAtMillis", changedAtMillis);
			update.where()
					.eq("id", lotId.toString())
					.and()
					.eq("state", expected.name());
			return update.update() == 1;
		});
	}

	@Override
	public @NotNull CompletableFuture<AuctionAttendanceRecord> registerAttendance(
			@NotNull String sessionId, @NotNull UUID playerId, long createdAtMillis) {
		return submit(() -> {
			awaitConnection();
			AuctionAttendanceRecord attendance = new AuctionAttendanceRecord(sessionId, playerId,
					createdAtMillis);
			attendanceDao.createIfNotExists(attendance);
			return attendanceDao.queryForId(attendance.getId().toString());
		});
	}

	@Override
	public @NotNull CompletableFuture<Void> saveAttendance(
			@NotNull AuctionAttendanceRecord attendance) {
		return submit(() -> {
			awaitConnection();
			attendanceDao.createOrUpdate(attendance);
			return null;
		});
	}

	@Override
	public @NotNull CompletableFuture<Optional<AuctionAttendanceRecord>> getAttendance(
			@NotNull String sessionId, @NotNull UUID playerId) {
		return submit(() -> {
			awaitConnection();
			return Optional.ofNullable(attendanceDao.queryForId(
					AuctionAttendanceRecord.idFor(sessionId, playerId).toString()));
		});
	}

	@Override
	public @NotNull CompletableFuture<List<AuctionAttendanceRecord>> getAttendance(
			@NotNull String sessionId, @NotNull Collection<AttendanceState> states) {
		if (states.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}
		return submit(() -> {
			awaitConnection();
			QueryBuilder<AuctionAttendanceRecord, String> query = attendanceDao.queryBuilder();
			query.orderBy("createdAtMillis", true);
			query.where()
					.eq("sessionId", sessionId)
					.and()
					.in("state", enumNames(states));
			return query.query();
		});
	}

	@Override
	public @NotNull CompletableFuture<List<AuctionAttendanceRecord>> getAttendances(
			@NotNull UUID playerId, @NotNull Collection<AttendanceState> states) {
		if (states.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}
		return submit(() -> {
			awaitConnection();
			QueryBuilder<AuctionAttendanceRecord, String> query = attendanceDao.queryBuilder();
			query.orderBy("createdAtMillis", true);
			query.where()
					.eq("playerId", playerId.toString())
					.and()
					.in("state", enumNames(states));
			return query.query();
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> beginAttendanceEntry(@NotNull String sessionId,
	                                                               @NotNull UUID playerId,
	                                                               @NotNull String returnWorld,
	                                                               double returnX, double returnY,
	                                                               double returnZ, float returnYaw,
	                                                               float returnPitch,
	                                                               long changedAtMillis) {
		if (returnWorld.isBlank() || !Double.isFinite(returnX) || !Double.isFinite(returnY)
				|| !Double.isFinite(returnZ) || !Float.isFinite(returnYaw)
				|| !Float.isFinite(returnPitch)) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid return location"));
		}
		return submit(() -> {
			awaitConnection();
			UpdateBuilder<AuctionAttendanceRecord, String> update = attendanceDao.updateBuilder();
			update.updateColumnValue("state", AttendanceState.ENTERING.name());
			update.updateColumnValue("returnWorld", returnWorld);
			update.updateColumnValue("returnX", returnX);
			update.updateColumnValue("returnY", returnY);
			update.updateColumnValue("returnZ", returnZ);
			update.updateColumnValue("returnYaw", returnYaw);
			update.updateColumnValue("returnPitch", returnPitch);
			update.updateColumnValue("updatedAtMillis", changedAtMillis);
			update.where()
					.eq("id", AuctionAttendanceRecord.idFor(sessionId, playerId).toString())
					.and()
					.eq("state", AttendanceState.REGISTERED.name());
			if (update.update() == 1) {
				return true;
			}
			AuctionAttendanceRecord existing = attendanceDao.queryForId(
					AuctionAttendanceRecord.idFor(sessionId, playerId).toString());
			return existing != null
					&& existing.getState() == AttendanceState.ENTERING
					&& returnWorld.equals(existing.getReturnWorld())
					&& Double.compare(returnX, existing.getReturnX()) == 0
					&& Double.compare(returnY, existing.getReturnY()) == 0
					&& Double.compare(returnZ, existing.getReturnZ()) == 0
					&& Float.compare(returnYaw, existing.getReturnYaw()) == 0
					&& Float.compare(returnPitch, existing.getReturnPitch()) == 0;
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> transitionAttendance(@NotNull String sessionId,
	                                                               @NotNull UUID playerId,
	                                                               @NotNull AttendanceState expected,
	                                                               @NotNull AttendanceState next,
	                                                               long changedAtMillis) {
		return submit(() -> {
			awaitConnection();
			UpdateBuilder<AuctionAttendanceRecord, String> update = attendanceDao.updateBuilder();
			update.updateColumnValue("state", next.name());
			update.updateColumnValue("updatedAtMillis", changedAtMillis);
			update.where()
					.eq("id", AuctionAttendanceRecord.idFor(sessionId, playerId).toString())
					.and()
					.eq("state", expected.name());
			return update.update() == 1;
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> removeRegisteredAttendance(@NotNull String sessionId,
	                                                                     @NotNull UUID playerId) {
		return submit(() -> {
			awaitConnection();
			var delete = attendanceDao.deleteBuilder();
			delete.where()
					.eq("id", AuctionAttendanceRecord.idFor(sessionId, playerId).toString())
					.and()
					.eq("state", AttendanceState.REGISTERED.name());
			return delete.delete() == 1;
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> saveRuntimeCheckpoint(
			@NotNull AuctionRuntimeCheckpoint checkpoint) {
		return submit(() -> {
			awaitConnection();
			return TransactionManager.callInTransaction(connectionSource, () -> {
				AuctionRuntimeCheckpoint existing = runtimeCheckpointDao.queryForId(
						checkpoint.getSessionId());
				if (existing != null
						&& (checkpoint.getUpdatedAtMillis() < existing.getUpdatedAtMillis()
						|| (checkpoint.getUpdatedAtMillis() == existing.getUpdatedAtMillis()
						&& checkpoint.getRevision() < existing.getRevision()))) {
					return false;
				}
				runtimeCheckpointDao.createOrUpdate(checkpoint);
				return true;
			});
		});
	}

	@Override
	public @NotNull CompletableFuture<Optional<AuctionRuntimeCheckpoint>> getRuntimeCheckpoint(
			@NotNull String sessionId) {
		return submit(() -> {
			awaitConnection();
			return Optional.ofNullable(runtimeCheckpointDao.queryForId(sessionId));
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> deleteRuntimeCheckpoint(@NotNull String sessionId) {
		return submit(() -> {
			awaitConnection();
			return runtimeCheckpointDao.deleteById(sessionId) == 1;
		});
	}

	@Override
	public @NotNull CompletableFuture<AuctionBidTransaction> createBidTransaction(
			@NotNull AuctionBidTransaction transaction) {
		return submit(() -> {
			awaitConnection();
			return TransactionManager.callInTransaction(connectionSource, () -> {
				AuctionBidTransaction existing = bidTransactionDao.queryForId(
						transaction.getId().toString());
				if (existing == null) {
					bidTransactionDao.create(transaction);
					return transaction;
				}
				if (!sameBidTransaction(existing, transaction)) {
					throw new SQLException("Bid transaction id reused with a different payload: "
							+ transaction.getId());
				}
				return existing;
			});
		});
	}

	@Override
	public @NotNull CompletableFuture<Optional<AuctionBidTransaction>> getBidTransaction(
			@NotNull UUID transactionId) {
		return submit(() -> {
			awaitConnection();
			return Optional.ofNullable(bidTransactionDao.queryForId(transactionId.toString()));
		});
	}

	@Override
	public @NotNull CompletableFuture<List<AuctionBidTransaction>> getBidTransactions(
			@NotNull Collection<BidTransactionState> states) {
		if (states.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}
		return submit(() -> {
			awaitConnection();
			QueryBuilder<AuctionBidTransaction, String> query = bidTransactionDao.queryBuilder();
			query.orderBy("createdAtMillis", true);
			query.where().in("state", enumNames(states));
			return query.query();
		});
	}

	@Override
	public @NotNull CompletableFuture<List<AuctionBidTransaction>> getBidTransactions(
			@NotNull String sessionId, @NotNull Collection<BidTransactionState> states) {
		if (states.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}
		return submit(() -> {
			awaitConnection();
			QueryBuilder<AuctionBidTransaction, String> query = bidTransactionDao.queryBuilder();
			query.orderBy("createdAtMillis", true);
			query.where()
					.eq("sessionId", sessionId)
					.and()
					.in("state", enumNames(states));
			return query.query();
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> transitionBidTransaction(
			@NotNull UUID transactionId, @NotNull BidTransactionState expected,
			@NotNull BidTransactionState next, @NotNull String failureReason,
			long changedAtMillis) {
		return submit(() -> {
			awaitConnection();
			UpdateBuilder<AuctionBidTransaction, String> update = bidTransactionDao.updateBuilder();
			update.updateColumnValue("state", next.name());
			update.updateColumnValue("failureReason", failureReason);
			update.updateColumnValue("updatedAtMillis", changedAtMillis);
			update.where()
					.eq("id", transactionId.toString())
					.and()
					.eq("state", expected.name());
			return update.update() == 1;
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> compensateBidTransaction(@NotNull UUID transactionId,
	                                                                   @NotNull String reason,
	                                                                   long changedAtMillis) {
		return submit(() -> {
			awaitConnection();
			return TransactionManager.callInTransaction(connectionSource, () -> {
				AuctionBidTransaction transaction = bidTransactionDao.queryForId(transactionId.toString());
				if (transaction == null) {
					return false;
				}
				if (transaction.getState() == BidTransactionState.COMPENSATED) {
					return true;
				}
				if (transaction.getState() != BidTransactionState.WITHDRAWING
						&& transaction.getState() != BidTransactionState.WITHDRAWN) {
					return false;
				}
				RewardRecord compensation = RewardRecord.bidCompensation(transaction.getBidderId(),
						transaction.getAuctionId(), transaction.getId(), transaction.getAmountMinor());
				rewardDao.createIfNotExists(compensation);
				// The bid record uses the transaction id.  If publication was aborted,
				// deleting it prevents a restored session treating the compensated bid as accepted.
				bidRecordDao.deleteById(transactionId.toString());
				UpdateBuilder<AuctionBidTransaction, String> update = bidTransactionDao.updateBuilder();
				update.updateColumnValue("state", BidTransactionState.COMPENSATED.name());
				update.updateColumnValue("failureReason", reason);
				update.updateColumnValue("updatedAtMillis", changedAtMillis);
				update.where()
						.eq("id", transactionId.toString())
						.and()
						.in("state", List.of(BidTransactionState.WITHDRAWING.name(),
								BidTransactionState.WITHDRAWN.name()));
				if (update.update() != 1) {
					throw new SQLException("Bid transaction state changed during compensation: "
							+ transactionId);
				}
				return true;
			});
		});
	}

	@Override
	public @NotNull CompletableFuture<AuctionSubmissionTransaction> createSubmissionTransaction(
			@NotNull AuctionSubmissionTransaction transaction) {
		return submit(() -> {
			awaitConnection();
			return TransactionManager.callInTransaction(connectionSource, () -> {
				AuctionSubmissionTransaction existing = submissionTransactionDao.queryForId(
						transaction.getId().toString());
				if (existing == null) {
					submissionTransactionDao.create(transaction);
					return transaction;
				}
				if (!sameSubmissionTransaction(existing, transaction)) {
					throw new SQLException("Submission transaction id reused with a different payload: "
							+ transaction.getId());
				}
				return existing;
			});
		});
	}

	@Override
	public @NotNull CompletableFuture<Optional<AuctionSubmissionTransaction>> getSubmissionTransaction(
			@NotNull UUID transactionId) {
		return submit(() -> {
			awaitConnection();
			return Optional.ofNullable(submissionTransactionDao.queryForId(transactionId.toString()));
		});
	}

	@Override
	public @NotNull CompletableFuture<List<AuctionSubmissionTransaction>> getSubmissionTransactions(
			@NotNull Collection<SubmissionTransactionState> states) {
		if (states.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}
		return submit(() -> {
			awaitConnection();
			QueryBuilder<AuctionSubmissionTransaction, String> query =
					submissionTransactionDao.queryBuilder();
			query.orderBy("createdAtMillis", true);
			query.where().in("state", enumNames(states));
			return query.query();
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> transitionSubmissionTransaction(
			@NotNull UUID transactionId, @NotNull SubmissionTransactionState expected,
			@NotNull SubmissionTransactionState next, @NotNull String failureReason,
			long changedAtMillis) {
		return submit(() -> {
			awaitConnection();
			UpdateBuilder<AuctionSubmissionTransaction, String> update =
					submissionTransactionDao.updateBuilder();
			update.updateColumnValue("state", next.name());
			update.updateColumnValue("failureReason", failureReason);
			update.updateColumnValue("updatedAtMillis", changedAtMillis);
			update.where()
					.eq("id", transactionId.toString())
					.and()
					.eq("state", expected.name());
			return update.update() == 1;
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> commitSubmissionTransaction(
			@NotNull UUID transactionId, long changedAtMillis) {
		return submit(() -> {
			awaitConnection();
			return TransactionManager.callInTransaction(connectionSource, () -> {
				AuctionSubmissionTransaction transaction = submissionTransactionDao.queryForId(
						transactionId.toString());
				if (transaction == null) {
					return false;
				}
				AuctionRecord auction = auctionRecordDao.queryForId(
						transaction.getAuctionId().toString());
				if (auction == null || !auction.getAuctioneerId().equals(transaction.getSellerId())) {
					throw new SQLException("Submission auction is missing or has a different seller: "
							+ transaction.getAuctionId());
				}
				if (transaction.getState() == SubmissionTransactionState.COMMITTED) {
					return auction.getStatus() == AuctionRecordStatus.QUEUED
							|| auction.getStatus() == AuctionRecordStatus.ACTIVE
							|| auction.getStatus() == AuctionRecordStatus.COMPLETED;
				}
				if (transaction.getState() != SubmissionTransactionState.ITEM_ESCROWED
						|| (auction.getStatus() != AuctionRecordStatus.PREPARING
						&& auction.getStatus() != AuctionRecordStatus.QUEUED)) {
					return false;
				}

				if (!transaction.getSessionId().isBlank()) {
					AuctionSessionLot lot = sessionLotDao.queryForId(AuctionSessionLot.idFor(
							transaction.getSessionId(), transaction.getAuctionId()).toString());
					if (lot == null || !lot.getSellerId().equals(transaction.getSellerId())) {
						return false;
					}
					LotState lotState = lot.getState();
					if (lotState == LotState.RESERVED) {
						UpdateBuilder<AuctionSessionLot, String> lotUpdate = sessionLotDao.updateBuilder();
						lotUpdate.updateColumnValue("state", LotState.QUEUED.name());
						lotUpdate.updateColumnValue("updatedAtMillis", changedAtMillis);
						lotUpdate.where().eq("id", lot.getId().toString()).and()
								.eq("state", LotState.RESERVED.name());
						if (lotUpdate.update() != 1) {
							throw new SQLException("Submission lot changed while being committed: "
									+ lot.getId());
						}
					} else if (lotState != LotState.QUEUED && lotState != LotState.LOCKED) {
						return false;
					}
				}

				if (auction.getStatus() == AuctionRecordStatus.PREPARING) {
					UpdateBuilder<AuctionRecord, String> auctionUpdate = auctionRecordDao.updateBuilder();
					auctionUpdate.updateColumnValue("status", AuctionRecordStatus.QUEUED.name());
					auctionUpdate.where().eq("id", transaction.getAuctionId().toString()).and()
							.eq("status", AuctionRecordStatus.PREPARING.name());
					if (auctionUpdate.update() != 1) {
						throw new SQLException("Submission auction changed while being committed: "
								+ transaction.getAuctionId());
					}
				}

				UpdateBuilder<AuctionSubmissionTransaction, String> transactionUpdate =
						submissionTransactionDao.updateBuilder();
				transactionUpdate.updateColumnValue("state", SubmissionTransactionState.COMMITTED.name());
				transactionUpdate.updateColumnValue("failureReason", "");
				transactionUpdate.updateColumnValue("updatedAtMillis", changedAtMillis);
				transactionUpdate.where().eq("id", transactionId.toString()).and()
						.eq("state", SubmissionTransactionState.ITEM_ESCROWED.name());
				if (transactionUpdate.update() != 1) {
					throw new SQLException("Submission journal changed while being committed: "
							+ transactionId);
				}
				return true;
			});
		});
	}

	@Override
	public @NotNull CompletableFuture<Boolean> compensateSubmissionTransaction(
			@NotNull UUID transactionId, @NotNull String reason, long changedAtMillis) {
		return submit(() -> {
			awaitConnection();
			return TransactionManager.callInTransaction(connectionSource, () -> {
				AuctionSubmissionTransaction transaction = submissionTransactionDao.queryForId(
						transactionId.toString());
				if (transaction == null) {
					return false;
				}
				if (transaction.getState() == SubmissionTransactionState.COMPENSATED) {
					return true;
				}
				if (transaction.getState() == SubmissionTransactionState.COMMITTED) {
					return false;
				}

				AuctionRecord auction = auctionRecordDao.queryForId(
						transaction.getAuctionId().toString());
				if (auction == null || !auction.getAuctioneerId().equals(transaction.getSellerId())) {
					throw new SQLException("Submission auction is missing or has a different seller: "
							+ transaction.getAuctionId());
				}
				if (auction.getStatus() == AuctionRecordStatus.ACTIVE
						|| auction.getStatus() == AuctionRecordStatus.COMPLETED) {
					return false;
				}

				SubmissionTransactionState state = transaction.getState();
				boolean itemRefund = state.itemMayBeEscrowed();
				boolean feeRefund = state.feeMayHaveBeenWithdrawn()
						&& transaction.getListingFeeMinor() > 0;
				if (itemRefund) {
					rewardDao.createIfNotExists(RewardRecord.item(transaction.getSellerId(),
							transaction.getAuctionId(), auction.getItem(), auction.getAmount(),
							auction.getWorld()));
				}
				if (feeRefund) {
					rewardDao.createIfNotExists(RewardRecord.money(transaction.getSellerId(),
							transaction.getAuctionId(), RewardKind.REFUND,
							transaction.getListingFeeMinor()));
				}

				if (!transaction.getSessionId().isBlank()) {
					AuctionSessionLot lot = sessionLotDao.queryForId(AuctionSessionLot.idFor(
							transaction.getSessionId(), transaction.getAuctionId()).toString());
					if (lot != null && (lot.getState() == LotState.RESERVED
							|| lot.getState() == LotState.QUEUED
							|| lot.getState() == LotState.LOCKED)) {
						UpdateBuilder<AuctionSessionLot, String> lotUpdate = sessionLotDao.updateBuilder();
						lotUpdate.updateColumnValue("state", LotState.CANCELLED.name());
						lotUpdate.updateColumnValue("updatedAtMillis", changedAtMillis);
						lotUpdate.where().eq("id", lot.getId().toString()).and()
								.in("state", List.of(LotState.RESERVED.name(), LotState.QUEUED.name(),
										LotState.LOCKED.name()));
						if (lotUpdate.update() != 1) {
							throw new SQLException("Submission lot changed during compensation: "
									+ lot.getId());
						}
					} else if (lot != null && lot.getState() != LotState.CANCELLED
							&& lot.getState() != LotState.DEFERRED) {
						return false;
					}
				}

				if (auction.getStatus() != AuctionRecordStatus.CANCELLED) {
					UpdateBuilder<AuctionRecord, String> auctionUpdate = auctionRecordDao.updateBuilder();
					auctionUpdate.updateColumnValue("status", AuctionRecordStatus.CANCELLED.name());
					auctionUpdate.updateColumnValue("completedAtMillis", changedAtMillis);
					auctionUpdate.updateColumnValue("itemDestination",
							itemRefund ? "SELLER_MAILBOX" : "PLAYER_INVENTORY");
					auctionUpdate.updateColumnValue("refundStatus",
							feeRefund ? "SELLER_MAILBOX" : "NOT_REQUIRED");
					auctionUpdate.where().eq("id", transaction.getAuctionId().toString()).and()
							.in("status", List.of(AuctionRecordStatus.PREPARING.name(),
									AuctionRecordStatus.QUEUED.name()));
					if (auctionUpdate.update() != 1) {
						throw new SQLException("Submission auction changed during compensation: "
								+ transaction.getAuctionId());
					}
				}

				UpdateBuilder<AuctionSubmissionTransaction, String> transactionUpdate =
						submissionTransactionDao.updateBuilder();
				transactionUpdate.updateColumnValue("state", SubmissionTransactionState.COMPENSATED.name());
				transactionUpdate.updateColumnValue("failureReason", reason);
				transactionUpdate.updateColumnValue("updatedAtMillis", changedAtMillis);
				transactionUpdate.where().eq("id", transactionId.toString()).and()
						.eq("state", state.name());
				if (transactionUpdate.update() != 1) {
					throw new SQLException("Submission transaction changed during compensation: "
							+ transactionId);
				}
				return true;
			});
		});
	}

	private @NotNull SubmissionResult reserveSessionLotInTransaction(
			@NotNull String sessionId, @NotNull UUID auctionId, @NotNull UUID sellerId,
			long createdAtMillis) throws SQLException {
		if (sessionId.isBlank()) {
			throw new IllegalArgumentException("Session id must not be blank");
		}
		touchSessionRow(sessionId);
		AuctionSessionRecord session = sessionDao.queryForId(sessionId);
		if (session == null) {
			return SubmissionResult.rejected(ReservationStatus.NOT_FOUND, sessionId, 0, 0);
		}

		int occupiedSlots = Math.toIntExact(countOccupiedLots(sessionId));
		String lotId = AuctionSessionLot.idFor(sessionId, auctionId).toString();
		AuctionSessionLot existing = sessionLotDao.queryForId(lotId);
		if (existing != null && existing.getSellerId().equals(sellerId)
				&& existing.getState() != LotState.CANCELLED
				&& existing.getState() != LotState.DEFERRED) {
			return SubmissionResult.success(sessionId, existing.getId(), occupiedSlots,
					session.getCapacity());
		}

		if (!session.getState().acceptsSubmissions()
				|| createdAtMillis >= session.getLockAtMillis()) {
			return SubmissionResult.rejected(ReservationStatus.SESSION_CLOSED, sessionId,
					occupiedSlots, session.getCapacity());
		}
		if (occupiedSlots >= session.getCapacity()) {
			return SubmissionResult.rejected(ReservationStatus.FULL, sessionId,
					occupiedSlots, session.getCapacity());
		}
		if (countOccupiedLotsForSeller(sessionId, sellerId) >= session.getSellerLimit()) {
			return SubmissionResult.rejected(ReservationStatus.SELLER_LIMIT, sessionId,
					occupiedSlots, session.getCapacity());
		}
		if (existing != null) {
			// A withdrawn lot keeps its immutable audit row.  A new AuctionRecord id is
			// required for a fresh submission to the same session.
			return SubmissionResult.rejected(ReservationStatus.SESSION_CLOSED, sessionId,
					occupiedSlots, session.getCapacity());
		}

		int sequenceNumber = nextLotSequence(sessionId);
		AuctionSessionLot lot = new AuctionSessionLot(sessionId, auctionId, sellerId,
				sequenceNumber, createdAtMillis);
		sessionLotDao.create(lot);
		return SubmissionResult.success(sessionId, lot.getId(), occupiedSlots + 1,
				session.getCapacity());
	}

	private @NotNull WithdrawalResult withdrawSessionLotInTransaction(
			@NotNull String sessionId, @NotNull UUID auctionId, @NotNull UUID sellerId,
			long withdrawnAtMillis) throws Exception {
		if (sessionId.isBlank()) {
			throw new IllegalArgumentException("Session id must not be blank");
		}
		touchSessionRow(sessionId);
		AuctionSessionRecord session = sessionDao.queryForId(sessionId);
		AuctionSessionLot lot = sessionLotDao.queryForId(
				AuctionSessionLot.idFor(sessionId, auctionId).toString());
		if (session == null || lot == null) {
			return new WithdrawalResult(WithdrawalResult.Status.NOT_FOUND, sessionId, auctionId);
		}
		if (!lot.getSellerId().equals(sellerId)) {
			return new WithdrawalResult(WithdrawalResult.Status.NOT_OWNER, sessionId, auctionId);
		}
		AuctionRecord auction = auctionRecordDao.queryForId(auctionId.toString());
		if (auction == null) {
			return new WithdrawalResult(WithdrawalResult.Status.NOT_FOUND, sessionId, auctionId);
		}
		if (!auction.getAuctioneerId().equals(sellerId)) {
			return new WithdrawalResult(WithdrawalResult.Status.NOT_OWNER, sessionId, auctionId);
		}

		RewardRecord reward = RewardRecord.item(sellerId, auctionId, auction.getItem(),
				auction.getAmount(), auction.getWorld());
		if (lot.getState() == LotState.CANCELLED
				&& auction.getStatus() == AuctionRecordStatus.CANCELLED
				&& rewardDao.queryForId(reward.getId().toString()) != null) {
			return new WithdrawalResult(WithdrawalResult.Status.SUCCESS, sessionId, auctionId);
		}
		if (!session.getState().acceptsSubmissions()
				|| withdrawnAtMillis >= session.getLockAtMillis()) {
			return new WithdrawalResult(WithdrawalResult.Status.SESSION_CLOSED, sessionId, auctionId);
		}
		if (lot.getState() != LotState.RESERVED && lot.getState() != LotState.QUEUED) {
			return new WithdrawalResult(WithdrawalResult.Status.SESSION_CLOSED, sessionId, auctionId);
		}

		UpdateBuilder<AuctionSessionLot, String> lotUpdate = sessionLotDao.updateBuilder();
		lotUpdate.updateColumnValue("state", LotState.CANCELLED.name());
		lotUpdate.updateColumnValue("updatedAtMillis", withdrawnAtMillis);
		lotUpdate.where()
				.eq("id", lot.getId().toString())
				.and()
				.in("state", List.of(LotState.RESERVED.name(), LotState.QUEUED.name()));
		if (lotUpdate.update() != 1) {
			throw new SQLException("Lot state changed during withdrawal: " + lot.getId());
		}

		UpdateBuilder<AuctionRecord, String> auctionUpdate = auctionRecordDao.updateBuilder();
		auctionUpdate.updateColumnValue("status", AuctionRecordStatus.CANCELLED.name());
		auctionUpdate.updateColumnValue("completedAtMillis", withdrawnAtMillis);
		auctionUpdate.updateColumnValue("itemDestination", "SELLER_MAILBOX");
		auctionUpdate.updateColumnValue("refundStatus", "NONE");
		auctionUpdate.where()
				.eq("id", auctionId.toString())
				.and()
				.eq("auctioneerId", sellerId.toString())
				.and()
				.in("status", List.of(AuctionRecordStatus.PREPARING.name(),
						AuctionRecordStatus.QUEUED.name()));
		if (auctionUpdate.update() != 1) {
			throw new SQLException("Auction state changed during withdrawal: " + auctionId);
		}
		rewardDao.createIfNotExists(reward);
		return new WithdrawalResult(WithdrawalResult.Status.SUCCESS, sessionId, auctionId);
	}

	private @NotNull SubmissionResult moveSessionLotInTransaction(
			@NotNull String sourceSessionId, @NotNull String targetSessionId,
			@NotNull UUID auctionId, @NotNull UUID sellerId, long movedAtMillis) throws SQLException {
		if (sourceSessionId.isBlank() || targetSessionId.isBlank()) {
			throw new IllegalArgumentException("Session ids must not be blank");
		}
		if (sourceSessionId.equals(targetSessionId)) {
			AuctionSessionRecord sameSession = sessionDao.queryForId(sourceSessionId);
			AuctionSessionLot sameLot = sessionLotDao.queryForId(
					AuctionSessionLot.idFor(sourceSessionId, auctionId).toString());
			if (sameSession == null || sameLot == null || !sameLot.getSellerId().equals(sellerId)) {
				return SubmissionResult.rejected(ReservationStatus.NOT_FOUND, targetSessionId, 0,
						sameSession == null ? 0 : sameSession.getCapacity());
			}
			return SubmissionResult.success(targetSessionId, sameLot.getId(),
					Math.toIntExact(countOccupiedLots(targetSessionId)), sameSession.getCapacity());
		}

		// Stable lock order prevents two cross-session deferrals deadlocking on MariaDB.
		if (sourceSessionId.compareTo(targetSessionId) < 0) {
			touchSessionRow(sourceSessionId);
			touchSessionRow(targetSessionId);
		} else {
			touchSessionRow(targetSessionId);
			touchSessionRow(sourceSessionId);
		}
		AuctionSessionRecord target = sessionDao.queryForId(targetSessionId);
		AuctionSessionLot sourceLot = sessionLotDao.queryForId(
				AuctionSessionLot.idFor(sourceSessionId, auctionId).toString());
		if (target == null || sourceLot == null) {
			return SubmissionResult.rejected(ReservationStatus.NOT_FOUND, targetSessionId, 0,
					target == null ? 0 : target.getCapacity());
		}
		int occupiedSlots = Math.toIntExact(countOccupiedLots(targetSessionId));
		AuctionSessionLot existingTarget = sessionLotDao.queryForId(
				AuctionSessionLot.idFor(targetSessionId, auctionId).toString());
		if (existingTarget != null && existingTarget.getSellerId().equals(sellerId)
				&& existingTarget.getState() != LotState.CANCELLED
				&& existingTarget.getState() != LotState.DEFERRED) {
			markSourceDeferred(sourceLot, sellerId, movedAtMillis);
			return SubmissionResult.success(targetSessionId, existingTarget.getId(), occupiedSlots,
					target.getCapacity());
		}
		if (!sourceLot.getSellerId().equals(sellerId)) {
			return SubmissionResult.rejected(ReservationStatus.SESSION_CLOSED, targetSessionId,
					occupiedSlots, target.getCapacity());
		}
		if (!target.getState().acceptsSubmissions() || movedAtMillis >= target.getLockAtMillis()) {
			return SubmissionResult.rejected(ReservationStatus.SESSION_CLOSED, targetSessionId,
					occupiedSlots, target.getCapacity());
		}
		if (occupiedSlots >= target.getCapacity()) {
			return SubmissionResult.rejected(ReservationStatus.FULL, targetSessionId,
					occupiedSlots, target.getCapacity());
		}
		if (countOccupiedLotsForSeller(targetSessionId, sellerId) >= target.getSellerLimit()) {
			return SubmissionResult.rejected(ReservationStatus.SELLER_LIMIT, targetSessionId,
					occupiedSlots, target.getCapacity());
		}
		if (existingTarget != null || (sourceLot.getState() != LotState.RESERVED
				&& sourceLot.getState() != LotState.QUEUED && sourceLot.getState() != LotState.LOCKED)) {
			return SubmissionResult.rejected(ReservationStatus.SESSION_CLOSED, targetSessionId,
					occupiedSlots, target.getCapacity());
		}

		AuctionSessionLot targetLot = new AuctionSessionLot(targetSessionId, auctionId, sellerId,
				nextLotSequence(targetSessionId), movedAtMillis);
		sessionLotDao.create(targetLot);
		UpdateBuilder<AuctionSessionLot, String> targetUpdate = sessionLotDao.updateBuilder();
		targetUpdate.updateColumnValue("state", LotState.QUEUED.name());
		targetUpdate.updateColumnValue("updatedAtMillis", movedAtMillis);
		targetUpdate.where().eq("id", targetLot.getId().toString());
		if (targetUpdate.update() != 1) {
			throw new SQLException("Could not activate deferred target lot " + targetLot.getId());
		}
		markSourceDeferred(sourceLot, sellerId, movedAtMillis);
		return SubmissionResult.success(targetSessionId, targetLot.getId(), occupiedSlots + 1,
				target.getCapacity());
	}

	private void markSourceDeferred(@NotNull AuctionSessionLot sourceLot, @NotNull UUID sellerId,
	                                long movedAtMillis) throws SQLException {
		if (sourceLot.getState() == LotState.DEFERRED) {
			return;
		}
		UpdateBuilder<AuctionSessionLot, String> sourceUpdate = sessionLotDao.updateBuilder();
		sourceUpdate.updateColumnValue("state", LotState.DEFERRED.name());
		sourceUpdate.updateColumnValue("updatedAtMillis", movedAtMillis);
		sourceUpdate.where()
				.eq("id", sourceLot.getId().toString())
				.and()
				.eq("sellerId", sellerId.toString())
				.and()
				.in("state", List.of(LotState.RESERVED.name(), LotState.QUEUED.name(),
						LotState.LOCKED.name()));
		if (sourceUpdate.update() != 1) {
			throw new SQLException("Source lot state changed during deferral: " + sourceLot.getId());
		}
	}

	private void touchSessionRow(@NotNull String sessionId) throws SQLException {
		UpdateBuilder<AuctionSessionRecord, String> lock = sessionDao.updateBuilder();
		lock.updateColumnExpression("revision", "revision");
		lock.where().eq("id", sessionId);
		lock.update();
	}

	private long countOccupiedLots(@NotNull String sessionId) throws SQLException {
		QueryBuilder<AuctionSessionLot, String> query = sessionLotDao.queryBuilder();
		query.where()
				.eq("sessionId", sessionId)
				.and()
				.notIn("state", unoccupiedLotStates());
		return query.countOf();
	}

	private long countOccupiedLotsForSeller(@NotNull String sessionId, @NotNull UUID sellerId)
			throws SQLException {
		QueryBuilder<AuctionSessionLot, String> query = sessionLotDao.queryBuilder();
		query.where()
				.eq("sessionId", sessionId)
				.and()
				.eq("sellerId", sellerId.toString())
				.and()
				.notIn("state", unoccupiedLotStates());
		return query.countOf();
	}

	private int nextLotSequence(@NotNull String sessionId) throws SQLException {
		QueryBuilder<AuctionSessionLot, String> query = sessionLotDao.queryBuilder();
		query.orderBy("sequenceNumber", false).limit(1L);
		query.where().eq("sessionId", sessionId);
		AuctionSessionLot last = query.queryForFirst();
		return last == null ? 1 : Math.addExact(last.getSequenceNumber(), 1);
	}

	private static @NotNull List<String> unoccupiedLotStates() {
		return List.of(LotState.CANCELLED.name(), LotState.DEFERRED.name());
	}

	private static @NotNull List<String> enumNames(
			@NotNull Collection<? extends Enum<?>> values) {
		List<String> names = new ArrayList<>(values.size());
		for (Enum<?> value : values) {
			names.add(value.name());
		}
		return names;
	}

	private static boolean sameBidTransaction(@NotNull AuctionBidTransaction first,
	                                          @NotNull AuctionBidTransaction second) {
		return first.getSessionId().equals(second.getSessionId())
				&& first.getLotId().equals(second.getLotId())
				&& first.getAuctionId().equals(second.getAuctionId())
				&& first.getBidderId().equals(second.getBidderId())
				&& first.getAmountMinor() == second.getAmountMinor();
	}

	private static boolean sameSubmissionTransaction(
			@NotNull AuctionSubmissionTransaction first,
			@NotNull AuctionSubmissionTransaction second) {
		return first.getAuctionId().equals(second.getAuctionId())
				&& first.getSellerId().equals(second.getSellerId())
				&& first.getSessionId().equals(second.getSessionId())
				&& first.getListingFeeMinor() == second.getListingFeeMinor();
	}

	private static boolean sameBidRecord(@NotNull AuctionBidRecord first,
	                                     @NotNull AuctionBidRecord second) {
		return first.getAuctionId().equals(second.getAuctionId())
				&& first.getBidderId().equals(second.getBidderId())
				&& first.getAmountMinor() == second.getAmountMinor();
	}

	private static @NotNull Exception asException(@NotNull Throwable throwable) {
		return throwable instanceof Exception exception
				? exception : new RuntimeException(throwable);
	}

	public void reconnect() throws SQLException {
		synchronized (connectingMonitor) {
			connecting = true;
			if (connectionSource != null) {
				ConnectionSource connectionSourceTemp = connectionSource;
				connectionSource = null;
				try {
					connectionSourceTemp.close();
				} catch (Exception ignored) {
				}
			}

			try {
				String connectionString = fixedConnectionString == null
						? resolveConnectionString(config.getConfig().getString("data.connection-string"))
						: fixedConnectionString;
				ensureSqliteParentExists(connectionString);
				String user = fixedConnectionString == null
						? config.getConfig().getString("data.username") : "";
				if (user != null && !user.isBlank()) {
					String pass = config.getConfig().getString("data.password");
					connectionSource = new JdbcPooledConnectionSource(connectionString, user, pass);
				} else {
					connectionSource = new JdbcPooledConnectionSource(connectionString);
				}

				auctionPlayerDao = DaoManager.createDao(connectionSource, AuctionPlayer.class);
				TableUtils.createTableIfNotExists(connectionSource, AuctionPlayer.class);
				TableUtils.createTableIfNotExists(connectionSource, AuctionPlayerIgnore.class);
				TableUtils.createTableIfNotExists(connectionSource, SavedItem.class);
				TableUtils.createTableIfNotExists(connectionSource, AuctionRecord.class);
				TableUtils.createTableIfNotExists(connectionSource, RewardRecord.class);
				TableUtils.createTableIfNotExists(connectionSource, AuctionBidRecord.class);
				TableUtils.createTableIfNotExists(connectionSource, AuctionSessionRecord.class);
				TableUtils.createTableIfNotExists(connectionSource, AuctionSessionLot.class);
				TableUtils.createTableIfNotExists(connectionSource, AuctionAttendanceRecord.class);
				TableUtils.createTableIfNotExists(connectionSource, AuctionRuntimeCheckpoint.class);
				TableUtils.createTableIfNotExists(connectionSource, AuctionBidTransaction.class);
				TableUtils.createTableIfNotExists(connectionSource, AuctionSubmissionTransaction.class);

				auctionRecordDao = DaoManager.createDao(connectionSource, AuctionRecord.class);
				rewardDao = DaoManager.createDao(connectionSource, RewardRecord.class);
				bidRecordDao = DaoManager.createDao(connectionSource, AuctionBidRecord.class);
				sessionDao = DaoManager.createDao(connectionSource, AuctionSessionRecord.class);
				sessionLotDao = DaoManager.createDao(connectionSource, AuctionSessionLot.class);
				attendanceDao = DaoManager.createDao(connectionSource, AuctionAttendanceRecord.class);
				runtimeCheckpointDao = DaoManager.createDao(connectionSource,
						AuctionRuntimeCheckpoint.class);
				bidTransactionDao = DaoManager.createDao(connectionSource, AuctionBidTransaction.class);
				submissionTransactionDao = DaoManager.createDao(connectionSource,
						AuctionSubmissionTransaction.class);
				connecting = false;
				connectingMonitor.notifyAll();
			} catch (Exception e) {
				connecting = false;
				connectingMonitor.notifyAll();
				throw e;
			}
		}
	}

	@Override
	public void shutdown() {
		long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		synchronized (pendingOperationsMonitor) {
			while (pendingOperations.get() > 0) {
				long remainingNanos = deadlineNanos - System.nanoTime();
				if (remainingNanos <= 0) {
					logger.warning("Timed out while waiting for chained database operations during shutdown");
					break;
				}
				try {
					TimeUnit.NANOSECONDS.timedWait(pendingOperationsMonitor, remainingNanos);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		databaseExecutor.shutdown();
		try {
			if (!databaseExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
				logger.warning("Timed out while flushing database operations during shutdown");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
		if (connectionSource != null) {
			try {
				connectionSource.close();
				connectionSource = null;
			} catch (Exception ignored) {
			}
		}
	}

	private @NotNull CompletableFuture<Boolean> transitionReward(@NotNull UUID rewardId,
	                                                            @NotNull UUID ownerId,
	                                                            @NotNull RewardState expected,
	                                                            @NotNull RewardState next) {
		return submit(() -> {
			awaitConnection();
			UpdateBuilder<RewardRecord, String> update = rewardDao.updateBuilder();
			update.updateColumnValue("state", next.name());
			if (next == RewardState.DONE) {
				update.updateColumnValue("claimedAtMillis", System.currentTimeMillis());
			}
			update.where()
					.eq("id", rewardId.toString())
					.and()
					.eq("ownerId", ownerId.toString())
					.and()
					.eq("state", expected.name());
			return update.update() == 1;
		});
	}

	private void ensureSqliteParentExists(String connectionString) throws SQLException {
		if (connectionString == null || !connectionString.startsWith("jdbc:sqlite:")
				|| connectionString.equals("jdbc:sqlite::memory:")) {
			return;
		}
		String fileName = connectionString.substring("jdbc:sqlite:".length());
		Path parent = Path.of(fileName).toAbsolutePath().getParent();
		if (parent != null) {
			try {
				Files.createDirectories(parent);
			} catch (Exception exception) {
				throw new SQLException("Could not create SQLite parent directory " + parent, exception);
			}
		}
	}

	private String resolveConnectionString(String connectionString) {
		if ("jdbc:sqlite:sqlite.db".equals(connectionString)) {
			if (plugin == null) {
				return connectionString;
			}
			Path databasePath = plugin.getDataFolder().toPath().resolve("sqlite.db")
					.toAbsolutePath().normalize();
			return "jdbc:sqlite:" + databasePath;
		}
		return connectionString;
	}

	private void awaitConnection() throws SQLException, InterruptedException {
		if (connectionSource != null) {
			return;
		}
		synchronized (connectingMonitor) {
			while (connecting) {
				connectingMonitor.wait();
			}
		}
		if (connectionSource == null) {
			throw new SQLException("Database connection is unavailable");
		}
	}

	private <T> @NotNull CompletableFuture<T> submit(@NotNull CheckedSupplier<T> operation) {
		CompletableFuture<T> future = new CompletableFuture<>();
		pendingOperations.incrementAndGet();
		try {
			databaseExecutor.execute(() -> {
				try {
					future.complete(operation.get());
				} catch (Exception exception) {
					logger.severe("Database operation failed", exception);
					future.completeExceptionally(exception);
				} finally {
					finishPendingOperation();
				}
			});
		} catch (RejectedExecutionException exception) {
			future.completeExceptionally(exception);
			finishPendingOperation();
		}
		return future;
	}

	private void finishPendingOperation() {
		if (pendingOperations.decrementAndGet() == 0) {
			synchronized (pendingOperationsMonitor) {
				pendingOperationsMonitor.notifyAll();
			}
		}
	}

	@FunctionalInterface
	private interface CheckedSupplier<T> {
		T get() throws Exception;
	}
}
