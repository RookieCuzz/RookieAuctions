package me.elian.ezauctions.model;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import me.elian.ezauctions.InMemoryEconomy;
import me.elian.ezauctions.Logger;
import me.elian.ezauctions.RookieAuctions;
import me.elian.ezauctions.controller.AuctionPlayerController;
import me.elian.ezauctions.controller.ConfigController;
import me.elian.ezauctions.controller.MessageController;
import me.elian.ezauctions.controller.RewardController;
import me.elian.ezauctions.controller.ScoreboardController;
import me.elian.ezauctions.data.Database;
import me.elian.ezauctions.event.AuctionStartEvent;
import me.elian.ezauctions.scheduler.TaskScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuctionBroadcastTest {
	private final AtomicReference<UUID> cancelledStart = new AtomicReference<>();
	private ServerMock server;
	private RookieAuctions plugin;
	private InMemoryEconomy economy;
	private Database database;
	private AuctionPlayerController players;
	private ConfigController config;
	private Logger logger;

	@BeforeAll
	void setup() {
		server = MockBukkit.mock();
		Vault vault = MockBukkit.load(Vault.class);
		economy = new InMemoryEconomy();
		server.getServicesManager().register(Economy.class, economy, vault, ServicePriority.Normal);
		plugin = MockBukkit.load(RookieAuctions.class);
		database = plugin.getInjector().getInstance(Database.class);
		players = plugin.getInjector().getInstance(AuctionPlayerController.class);
		config = plugin.getInjector().getInstance(ConfigController.class);
		logger = plugin.getInjector().getInstance(Logger.class);
		server.getPluginManager().registerEvents(new Listener() {
			@EventHandler(priority = EventPriority.HIGHEST)
			public void cancelSelectedStart(AuctionStartEvent event) {
				if (event.getAuction().getAuctionData().getId().equals(cancelledStart.get())) {
					event.setCancelled(true);
				}
			}
		}, plugin);
	}

	@AfterAll
	void cleanup() throws IOException {
		Path dataDirectory = plugin == null ? null
				: plugin.getDataFolder().toPath().toAbsolutePath().normalize();
		try {
			MockBukkit.unmock();
		} catch (RuntimeException exception) {
			if (!isUnimplementedOperationException(exception)
					&& !isUnimplementedOperationException(exception.getCause())) {
				throw exception;
			}
		} finally {
			if (dataDirectory != null) {
				deleteTree(dataDirectory);
				Files.deleteIfExists(dataDirectory.resolveSibling("ezAuctions"));
			}
		}
	}

	@Test
	void activeAuctionBroadcastIsChineseAndRunsAuctionCommand() throws Exception {
		PlayerMock seller = addOnlinePlayer("StartSeller");
		PlayerMock observer = addOnlinePlayer("StartObserver");
		drainMessages(seller);
		drainMessages(observer);

		AuctionFixture fixture = newAuction(seller, false);
		fixture.auction().startAuction(fixture.data(), () -> { });
		awaitStatus(fixture.data().getId(), AuctionRecordStatus.ACTIVE);
		server.getScheduler().performTicks(4);

		Component announcement = drainMessages(observer).stream()
				.filter(component -> plain(component).contains("正在拍卖"))
				.findFirst().orElseThrow();
		assertTrue(hasRunAuctionClick(announcement));

		fixture.auction().cancelAuction(false);
	}

	@Test
	void cancelledStartEventDoesNotBroadcast() throws Exception {
		PlayerMock seller = addOnlinePlayer("CancelledSeller");
		PlayerMock observer = addOnlinePlayer("CancelledObserver");
		drainMessages(seller);
		drainMessages(observer);

		AuctionFixture fixture = newAuction(seller, false);
		cancelledStart.set(fixture.data().getId());
		try {
			fixture.auction().startAuction(fixture.data(), () -> { });
			awaitStatus(fixture.data().getId(), AuctionRecordStatus.CANCELLED);
			server.getScheduler().performTicks(4);
			assertTrue(drainMessages(observer).stream()
					.noneMatch(component -> plain(component).contains("正在拍卖")));
		} finally {
			cancelledStart.set(null);
		}
	}

	@Test
	void noBidNoticeWaitsForSellerItemReward() throws Exception {
		PlayerMock seller = addOnlinePlayer("NoBidSeller");
		PlayerMock observer = addOnlinePlayer("NoBidObserver");
		AuctionPlayer sellerData = players.getOnlinePlayer(seller.getUniqueId());
		assertNotNull(sellerData);
		sellerData.setIgnoringAll(true);

		AuctionFixture fixture = newAuction(seller, false);
		fixture.auction().startAuction(fixture.data(), () -> { });
		awaitStatus(fixture.data().getId(), AuctionRecordStatus.ACTIVE);
		server.getScheduler().performTicks(4);
		drainMessages(seller);
		drainMessages(observer);

		fixture.auction().end();
		awaitReward(seller.getUniqueId(), fixture.data().getId(), RewardKind.ITEM);
		server.getScheduler().performTicks(6);

		assertTrue(drainMessages(observer).stream()
				.anyMatch(component -> plain(component).contains("无人出价")));
		List<String> sellerMessages = drainMessages(seller).stream().map(this::plain).toList();
		assertEquals(1L, sellerMessages.stream().filter(message -> message.contains("物品已退回领奖箱")).count());
	}

	@Test
	void sealedResultRevealsWinnerAndNotifiesEachOnlineRoleOnce() throws Exception {
		PlayerMock seller = addOnlinePlayer("ResultSeller");
		PlayerMock winner = addOnlinePlayer("ResultWinner");
		PlayerMock loser = addOnlinePlayer("ResultLoser");
		PlayerMock observer = addOnlinePlayer("ResultObserver");
		AuctionPlayer sellerData = players.getOnlinePlayer(seller.getUniqueId());
		AuctionPlayer winnerData = players.getOnlinePlayer(winner.getUniqueId());
		AuctionPlayer loserData = players.getOnlinePlayer(loser.getUniqueId());
		assertNotNull(sellerData);
		assertNotNull(winnerData);
		assertNotNull(loserData);
		sellerData.setIgnoringAll(true);
		config.getConfig().set("sealed-auctions.max-bids", 2);
		economy.setBalance(winner, 100D);
		economy.setBalance(loser, 100D);

		AuctionFixture fixture = newAuction(seller, true);
		fixture.auction().startAuction(fixture.data(), () -> { });
		awaitStatus(fixture.data().getId(), AuctionRecordStatus.ACTIVE);
		server.getScheduler().performTicks(4);
		drainMessages(seller);
		drainMessages(winner);
		drainMessages(loser);
		drainMessages(observer);

		assertSuccessfulBid(fixture.auction(), loser, loserData, 1_000L);
		assertSuccessfulBid(fixture.auction(), winner, winnerData, 2_000L);
		assertSuccessfulBid(fixture.auction(), loser, loserData, 1_500L);
		fixture.auction().end();

		RewardRecord winnerItem = awaitReward(winner.getUniqueId(), fixture.data().getId(), RewardKind.ITEM);
		RewardRecord sellerIncome = awaitReward(seller.getUniqueId(), fixture.data().getId(), RewardKind.INCOME);
		RewardRecord loserRefund = awaitReward(loser.getUniqueId(), fixture.data().getId(), RewardKind.REFUND);
		assertEquals(1, winnerItem.getAmount());
		assertEquals(1_950L, sellerIncome.getMoneyMinor());
		assertEquals(1_500L, loserRefund.getMoneyMinor());
		server.getScheduler().performTicks(8);

		List<String> observerMessages = drainMessages(observer).stream().map(this::plain).toList();
		assertTrue(observerMessages.stream().anyMatch(message -> message.contains("ResultWinner")
				&& message.contains("$20.00") && message.contains("赢得了本场拍卖")));

		List<String> sellerMessages = drainMessages(seller).stream().map(this::plain).toList();
		assertEquals(1L, sellerMessages.stream().filter(message -> message.contains("税后收入")).count());
		assertTrue(sellerMessages.stream().anyMatch(message -> message.contains("$19.50")));

		List<String> winnerMessages = drainMessages(winner).stream().map(this::plain).toList();
		assertEquals(1L, winnerMessages.stream().filter(message -> message.contains("物品已存入领奖箱")).count());

		List<String> loserMessages = drainMessages(loser).stream().map(this::plain).toList();
		assertEquals(1L, loserMessages.stream().filter(message -> message.contains("退款已存入领奖箱")).count());
		assertTrue(loserMessages.stream().anyMatch(message -> message.contains("$15.00")));
	}

	@Test
	void failedRewardCreationLogsSevereAndDoesNotSendArrivalNotice() throws Exception {
		PlayerMock seller = addOnlinePlayer("FailedRewardSeller");
		AuctionPlayer sellerData = players.getOnlinePlayer(seller.getUniqueId());
		assertNotNull(sellerData);
		sellerData.setIgnoringAll(true);
		AuctionFixture fixture = newAuction(seller, false);
		RewardController failingRewards = new RewardController(database, economy,
				plugin.getInjector().getInstance(TaskScheduler.class), config, logger) {
			@Override
			public CompletableFuture<Void> createItemReward(UUID ownerId, UUID auctionId, ItemStack item,
			                                                int amount, String world) {
				return CompletableFuture.failedFuture(new IllegalStateException("simulated reward failure"));
			}
		};
		Auction auction = new Auction(plugin, plugin.getInjector().getInstance(TaskScheduler.class), economy,
				null, players, config, plugin.getInjector().getInstance(MessageController.class),
				plugin.getInjector().getInstance(ScoreboardController.class), database, failingRewards);

		AtomicReference<LogRecord> failureLog = new AtomicReference<>();
		Handler handler = new Handler() {
			@Override
			public void publish(LogRecord record) {
				if (record.getLevel().intValue() >= Level.SEVERE.intValue()
						&& record.getMessage().contains("seller item return reward")) {
					failureLog.set(record);
				}
			}

			@Override public void flush() { }
			@Override public void close() { }
		};
		plugin.getLogger().addHandler(handler);
		try {
			auction.startAuction(fixture.data(), () -> { });
			awaitStatus(fixture.data().getId(), AuctionRecordStatus.ACTIVE);
			server.getScheduler().performTicks(4);
			drainMessages(seller);
			auction.end();
			server.getScheduler().performTicks(6);
			assertNotNull(failureLog.get());
			assertTrue(drainMessages(seller).stream()
					.noneMatch(component -> plain(component).contains("物品已退回领奖箱")));
		} finally {
			plugin.getLogger().removeHandler(handler);
		}
	}

	private AuctionFixture newAuction(PlayerMock seller, boolean sealed) throws Exception {
		AuctionPlayer sellerData = players.getOnlinePlayer(seller.getUniqueId());
		assertNotNull(sellerData);
		UUID id = UUID.randomUUID();
		ItemStack item = new ItemStack(Material.DIAMOND);
		AuctionData data = new AuctionData(id, sellerData, item, 1, 60,
				1_000L, 100L, 0L, sealed, seller.getWorld().getName());
		data.gatherAdditionalData(logger);

		AuctionRecord record = new AuctionRecord(id, seller.getUniqueId(), item, 1, sealed,
				seller.getWorld().getName(), 1_000L, 100L, 0L, 60);
		await(database.createAuctionRecord(record));
		assertTrue(await(database.transitionAuction(id, AuctionRecordStatus.PREPARING, AuctionRecordStatus.QUEUED)));
		return new AuctionFixture(plugin.getInjector().getInstance(Auction.class), data);
	}

	private PlayerMock addOnlinePlayer(String name) throws Exception {
		PlayerMock player = server.addPlayer(name);
		awaitCondition(() -> players.getOnlinePlayer(player.getUniqueId()) != null);
		return player;
	}

	private void assertSuccessfulBid(Auction auction, PlayerMock player, AuctionPlayer auctionPlayer,
	                                 long amountMinor) throws Exception {
		AuctionView view = auction.viewFor(auctionPlayer);
		BidOutcome outcome = await(auction.submitBid(player, auctionPlayer, view.auctionId(), view.revision(),
				amountMinor, false));
		assertEquals(BidOutcome.Status.SUCCESS, outcome.status());
	}

	private RewardRecord awaitReward(UUID ownerId, UUID auctionId, RewardKind kind) throws Exception {
		AtomicReference<RewardRecord> found = new AtomicReference<>();
		awaitCondition(() -> {
			Optional<RewardRecord> matching = await(database.getRewards(ownerId, List.of(kind), false)).stream()
					.filter(reward -> auctionId.equals(reward.getAuctionId()))
					.findFirst();
			matching.ifPresent(found::set);
			return matching.isPresent();
		});
		return found.get();
	}

	private void awaitStatus(UUID auctionId, AuctionRecordStatus status) throws Exception {
		awaitCondition(() -> await(database.getAuctionRecord(auctionId))
				.map(record -> record.getStatus() == status).orElse(false));
	}

	private List<Component> drainMessages(PlayerMock player) {
		List<Component> messages = new ArrayList<>();
		Component next;
		while ((next = player.nextComponentMessage()) != null) {
			messages.add(next);
		}
		return messages;
	}

	private String plain(Component component) {
		return LegacyComponentSerializer.legacySection().serialize(component)
				.replaceAll("§[0-9A-FK-ORa-fk-or]", "");
	}

	private boolean hasRunAuctionClick(Component component) {
		ClickEvent click = component.clickEvent();
		if (click != null && click.action() == ClickEvent.Action.RUN_COMMAND && "/auction".equals(click.value())) {
			return true;
		}
		return component.children().stream().anyMatch(this::hasRunAuctionClick);
	}

	private void awaitCondition(CheckedBooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			server.getScheduler().performOneTick();
			Thread.sleep(10L);
		}
		assertTrue(condition.getAsBoolean(), "Condition did not become true before timeout");
	}

	private <T> T await(CompletableFuture<T> future) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!future.isDone() && System.nanoTime() < deadline) {
			server.getScheduler().performOneTick();
			Thread.sleep(10L);
		}
		return future.get(1, TimeUnit.SECONDS);
	}

	private void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private boolean isUnimplementedOperationException(Throwable exception) {
		return exception != null
				&& "UnimplementedOperationException".equals(exception.getClass().getSimpleName());
	}

	private record AuctionFixture(Auction auction, AuctionData data) { }

	@FunctionalInterface
	private interface CheckedBooleanSupplier {
		boolean getAsBoolean() throws Exception;
	}
}
