package me.elian.ezauctions.data;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.jdbc.JdbcPooledConnectionSource;
import com.j256.ormlite.stmt.QueryBuilder;
import com.j256.ormlite.stmt.UpdateBuilder;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.table.TableUtils;
import me.elian.ezauctions.Logger;
import me.elian.ezauctions.controller.ConfigController;
import me.elian.ezauctions.model.AuctionBidRecord;
import me.elian.ezauctions.model.AuctionPlayer;
import me.elian.ezauctions.model.AuctionPlayerIgnore;
import me.elian.ezauctions.model.AuctionRecord;
import me.elian.ezauctions.model.AuctionRecordStatus;
import me.elian.ezauctions.model.RewardKind;
import me.elian.ezauctions.model.RewardRecord;
import me.elian.ezauctions.model.RewardState;
import me.elian.ezauctions.model.SavedItem;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

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

@Singleton
public class OrmLiteDatabase implements Database {
	private final Logger logger;
	private final ConfigController config;
	private final Plugin plugin;
	private final Object connectingMonitor = new Object();
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

	@Inject
	public OrmLiteDatabase(Logger logger, ConfigController config, Plugin plugin) {
		this.logger = logger;
		this.config = config;
		this.plugin = plugin;
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
			bidRecordDao.create(bidRecord);
			return null;
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
				String connectionString = resolveConnectionString(
						config.getConfig().getString("data.connection-string"));
				ensureSqliteParentExists(connectionString);
				String user = config.getConfig().getString("data.username");
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

				auctionRecordDao = DaoManager.createDao(connectionSource, AuctionRecord.class);
				rewardDao = DaoManager.createDao(connectionSource, RewardRecord.class);
				bidRecordDao = DaoManager.createDao(connectionSource, AuctionBidRecord.class);
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
		try {
			databaseExecutor.execute(() -> {
				try {
					future.complete(operation.get());
				} catch (Exception exception) {
					logger.severe("Database operation failed", exception);
					future.completeExceptionally(exception);
				}
			});
		} catch (RejectedExecutionException exception) {
			future.completeExceptionally(exception);
		}
		return future;
	}

	@FunctionalInterface
	private interface CheckedSupplier<T> {
		T get() throws Exception;
	}
}
