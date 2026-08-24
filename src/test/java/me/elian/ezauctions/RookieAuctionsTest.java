package me.elian.ezauctions;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.Economy;
import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import me.elian.ezauctions.controller.AuctionController;
import me.elian.ezauctions.controller.AuctionPlayerController;
import me.elian.ezauctions.data.Database;
import me.elian.ezauctions.gui.AuctionGuiHolder;
import me.elian.ezauctions.gui.GuiPage;
import me.elian.ezauctions.helper.ItemHelper;
import me.elian.ezauctions.model.Auction;
import me.elian.ezauctions.model.AuctionPlayer;
import me.elian.ezauctions.model.AuctionRecord;
import me.elian.ezauctions.model.AuctionView;
import me.elian.ezauctions.model.BidOutcome;
import me.elian.ezauctions.model.RewardKind;
import me.elian.ezauctions.model.RewardRecord;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RookieAuctionsTest {
	private ServerMock server;
	private RookieAuctions plugin;
	private InMemoryEconomy economy;

	@BeforeAll
	public void setup() {
		server = MockBukkit.mock();
		Vault vaultPlugin = MockBukkit.load(Vault.class);
		economy = new InMemoryEconomy();
		server.getServicesManager().register(Economy.class, economy, vaultPlugin, ServicePriority.Normal);
		plugin = MockBukkit.load(RookieAuctions.class);
	}

	@AfterAll
	public void cleanup() {
		Path pluginDataDirectory = plugin == null ? null
				: plugin.getDataFolder().toPath().toAbsolutePath().normalize();
		try {
			MockBukkit.unmock();
		} catch (Exception exception) {
			if (!isUnimplementedOperationException(exception)
					&& !isUnimplementedOperationException(exception.getCause())) {
				throw exception;
			}
		} finally {
			cleanupPluginData(pluginDataDirectory);
			cleanupMisplacedTestData();
		}
	}

	private void cleanupPluginData(Path pluginDataDirectory) {
		if (pluginDataDirectory == null) {
			return;
		}
		try {
			deleteTree(pluginDataDirectory);
			Files.deleteIfExists(pluginDataDirectory.resolveSibling("ezAuctions"));
			Path pluginsDirectory = pluginDataDirectory.getParent();
			if (pluginsDirectory != null && Files.isDirectory(pluginsDirectory)) {
				try (Stream<Path> entries = Files.list(pluginsDirectory)) {
					if (entries.findAny().isEmpty()) {
						Files.deleteIfExists(pluginsDirectory);
					}
				}
			}
		} catch (IOException exception) {
			throw new AssertionError("Could not clean MockBukkit plugin data", exception);
		}
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

	private void cleanupMisplacedTestData() {
		Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		Path pluginsDirectory = projectRoot.resolve("plugins").normalize();
		if (!Files.isRegularFile(projectRoot.resolve("pom.xml"))
				|| !pluginsDirectory.startsWith(projectRoot)) {
			return;
		}
		try {
			deleteTree(pluginsDirectory.resolve("RookieAuctions"));
			Files.deleteIfExists(pluginsDirectory.resolve("ezAuctions"));
			if (Files.isDirectory(pluginsDirectory)) {
				try (Stream<Path> entries = Files.list(pluginsDirectory)) {
					if (entries.findAny().isEmpty()) {
						Files.deleteIfExists(pluginsDirectory);
					}
				}
			}
		} catch (IOException exception) {
			throw new AssertionError("Could not clean misplaced MockBukkit data", exception);
		}
	}

	private boolean isUnimplementedOperationException(Throwable exception) {
		return exception != null && "UnimplementedOperationException".equals(exception.getClass().getSimpleName());
	}

	@Test
	void getInjector() {
		// ensure injector was successfully created
		assertNotNull(plugin.getInjector());
	}

	@Test
	void onEnable() {
		// ensure plugin enabled successfully
		assertTrue(plugin.isEnabled());
	}

	@Test
	void auctionCommandOpensFiftyFourSlotGui() {
		PlayerMock player = server.addPlayer();
		assertTrue(server.dispatchCommand(player, "auction"));
		server.getScheduler().performTicks(5);
		assertNotNull(player.getOpenInventory().getTopInventory());
		assertEquals(54, player.getOpenInventory().getTopInventory().getSize());
		assertInstanceOf(AuctionGuiHolder.class, player.getOpenInventory().getTopInventory().getHolder());
		assertNull(server.getCommandMap().getCommand("bid"));
	}

	@Test
	void exactRemovalNeverPartiallyConsumesInventory() {
		PlayerMock player = server.addPlayer();
		ItemStack diamonds = new ItemStack(Material.DIAMOND, 5);
		player.getInventory().setItem(0, diamonds);

		assertFalse(ItemHelper.removeItemFromPlayerInventoryExact(player, new ItemStack(Material.DIAMOND), 6));
		assertEquals(5, player.getInventory().getItem(0).getAmount());

		assertTrue(ItemHelper.removeItemFromPlayerInventoryExact(player, new ItemStack(Material.DIAMOND), 4));
		assertEquals(1, player.getInventory().getItem(0).getAmount());
	}

	@Test
	void rewardClaimUsesCompareAndSetState() throws Exception {
		Database database = plugin.getInjector().getInstance(Database.class);
		UUID owner = UUID.randomUUID();
		RewardRecord reward = RewardRecord.money(owner, null, RewardKind.REFUND, 1_000L);
		await(database.createReward(reward));

		Optional<RewardRecord> first = await(database.tryBeginRewardClaim(reward.getId(), owner));
		Optional<RewardRecord> second = await(database.tryBeginRewardClaim(reward.getId(), owner));

		assertTrue(first.isPresent());
		assertTrue(second.isEmpty());
		assertTrue(await(database.finishRewardClaim(reward.getId(), owner)));
		assertFalse(await(database.releaseRewardClaim(reward.getId(), owner)));
	}

	@Test
	void rewardCreationForAnAuctionIsIdempotent() throws Exception {
		Database database = plugin.getInjector().getInstance(Database.class);
		UUID owner = UUID.randomUUID();
		UUID auctionId = UUID.randomUUID();
		RewardRecord first = RewardRecord.money(owner, auctionId, RewardKind.REFUND, 1_000L);
		RewardRecord retry = RewardRecord.money(owner, auctionId, RewardKind.REFUND, 1_000L);

		assertEquals(first.getId(), retry.getId());
		await(database.createReward(first));
		await(database.createReward(retry));

		List<RewardRecord> rewards = await(database.getRewards(owner, List.of(RewardKind.REFUND), false));
		assertEquals(1, rewards.stream().filter(record -> auctionId.equals(record.getAuctionId())).count());
	}

	@Test
	void guiWizardOnlyRemovesItemAtFinalConfirmationAndBidRejectsStaleView() throws Exception {
		Database database = plugin.getInjector().getInstance(Database.class);
		AuctionController auctions = plugin.getInjector().getInstance(AuctionController.class);
		AuctionPlayerController players = plugin.getInjector().getInstance(AuctionPlayerController.class);
		PlayerMock seller = server.addPlayer();
		seller.setOp(true);
		economy.setBalance(seller, 100D);

		assertTrue(server.dispatchCommand(seller, "auction"));
		server.getScheduler().performTicks(5);
		assertPage(seller, GuiPage.CURRENT);

		seller.simulateInventoryClick(seller.getOpenInventory(), ClickType.LEFT, 47);
		assertPage(seller, GuiPage.WIZARD_ITEM);
		seller.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 2));
		seller.simulateInventoryClick(seller.getOpenInventory(), ClickType.LEFT, 81);
		assertPage(seller, GuiPage.WIZARD_ITEM);
		assertEquals(2, seller.getInventory().getItem(0).getAmount(),
				"Selecting an item must not remove it");

		seller.simulateInventoryClick(44);
		assertPage(seller, GuiPage.WIZARD_MODE);
		seller.simulateInventoryClick(53);
		assertPage(seller, GuiPage.WIZARD_PRICE);
		seller.simulateInventoryClick(53);
		assertPage(seller, GuiPage.WIZARD_REVIEW);
		assertEquals(2, seller.getInventory().getItem(0).getAmount(),
				"Reviewing the listing must not remove it");

		seller.simulateInventoryClick(53);
		seller.simulateInventoryClick(53);
		awaitCondition(() -> {
			ItemStack remaining = seller.getInventory().getItem(0);
			return remaining != null && remaining.getAmount() == 1 && auctions.hasActiveAuction();
		});

		List<AuctionRecord> sellerRecords = await(database.getAuctionRecords(seller.getUniqueId()));
		assertEquals(1, sellerRecords.size(), "Double click must create only one auction");
		Auction active = auctions.getActiveAuction();
		assertNotNull(active);

		PlayerMock bidder = server.addPlayer();
		economy.setBalance(bidder, 100D);
		AuctionPlayer auctionBidder = await(players.getPlayer(bidder));
		AuctionView view = active.viewFor(auctionBidder);
		BidOutcome stale = await(active.submitBid(bidder, auctionBidder, view.auctionId(),
				view.revision() + 1, view.startingPriceMinor(), false));
		assertEquals(BidOutcome.Status.STALE_VIEW, stale.status());
		assertEquals(100D, economy.getBalance(bidder), 0.0001D);

		BidOutcome accepted = await(active.submitBid(bidder, auctionBidder, view.auctionId(),
				view.revision(), view.startingPriceMinor(), false));
		assertEquals(BidOutcome.Status.SUCCESS, accepted.status());
		assertEquals(99D, economy.getBalance(bidder), 0.0001D);

		BidOutcome replay = await(active.submitBid(bidder, auctionBidder, view.auctionId(),
				view.revision(), view.startingPriceMinor(), false));
		assertEquals(BidOutcome.Status.STALE_VIEW, replay.status());
		assertTrue(auctions.cancelActiveAuction(view.auctionId(), seller.getUniqueId(), false));
	}

	private void assertPage(PlayerMock player, GuiPage expected) {
		assertInstanceOf(AuctionGuiHolder.class, player.getOpenInventory().getTopInventory().getHolder());
		AuctionGuiHolder holder =
				(AuctionGuiHolder) player.getOpenInventory().getTopInventory().getHolder();
		assertEquals(expected, holder.getPage());
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

	@FunctionalInterface
	private interface CheckedBooleanSupplier {
		boolean getAsBoolean() throws Exception;
	}
}
