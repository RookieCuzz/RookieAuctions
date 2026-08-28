package me.elian.ezauctions.gui;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.inventory.AnvilInventoryMock;
import me.elian.ezauctions.InMemoryEconomy;
import me.elian.ezauctions.RookieAuctions;
import me.elian.ezauctions.controller.AuctionController;
import me.elian.ezauctions.controller.ConfigController;
import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuctionGuiAnvilTest {
	private ServerMock server;
	private RookieAuctions plugin;
	private InMemoryEconomy economy;
	private AuctionGuiController gui;
	private AuctionController auctions;

	@BeforeAll
	void setup() {
		server = MockBukkit.mock();
		Vault vaultPlugin = MockBukkit.load(Vault.class);
		economy = new InMemoryEconomy();
		server.getServicesManager().register(Economy.class, economy, vaultPlugin, ServicePriority.Normal);
		plugin = MockBukkit.load(RookieAuctions.class);
		gui = plugin.getInjector().getInstance(AuctionGuiController.class);
		auctions = plugin.getInjector().getInstance(AuctionController.class);
	}

	@AfterAll
	void cleanup() throws IOException {
		Path pluginDataDirectory = plugin == null ? null
				: plugin.getDataFolder().toPath().toAbsolutePath().normalize();
		try {
			MockBukkit.unmock();
		} catch (Exception exception) {
			if (!(exception instanceof UnimplementedOperationException)
					&& !(exception.getCause() instanceof UnimplementedOperationException)) {
				throw exception;
			}
		} finally {
			if (pluginDataDirectory != null) {
				deleteTree(pluginDataDirectory);
				Files.deleteIfExists(pluginDataDirectory.resolveSibling("ezAuctions"));
			}
			cleanupMisplacedTestData();
		}
	}

	@Test
	void zeroLevelPlayerCanSubmitRenamedDurationWithoutExperienceCost() throws Exception {
		PlayerMock player = newSeller();
		openWizardMode(player);

		player.simulateInventoryClick(40);
		assertPage(player, GuiPage.ANVIL_INPUT);
		AnvilInventoryMock anvil = assertInstanceOf(AnvilInventoryMock.class,
				player.getOpenInventory().getTopInventory());
		anvil.setRepairCost(39);
		anvil.setRepairCostAmount(2);
		ItemStack result = prepareAnvil(player, "123");

		assertEquals("123", result.getItemMeta().getDisplayName());
		assertEquals(0, anvil.getRepairCost());
		assertEquals(0, anvil.getRepairCostAmount());
		assertEquals(Integer.MAX_VALUE, anvil.getMaximumRepairCost());

		anvil.setItem(2, result);
		player.simulateInventoryClick(2);

		assertPage(player, GuiPage.WIZARD_MODE);
		GuiSession session = session(player);
		assertEquals(123, session.draft.getDurationSeconds());
		assertNull(session.inputTarget);
		assertEquals(0, player.getLevel());
	}

	@Test
	void everyPriceInputReadsRenameTextInsteadOfTheResultItemName() throws Exception {
		PlayerMock player = newSeller();
		openWizardMode(player);
		player.simulateInventoryClick(53);
		assertPage(player, GuiPage.WIZARD_PRICE);

		submitAnvilValueWithStaleResult(player, 20, "12.34", "1.00");
		assertEquals(1_234L, session(player).draft.getStartingPriceMinor());

		submitAnvilValueWithStaleResult(player, 22, "3.21", "1.00");
		assertEquals(321L, session(player).draft.getIncrementMinor());

		submitAnvilValueWithStaleResult(player, 24, "50.50", "11.00");
		GuiSession session = session(player);
		assertEquals(5_050L, session.draft.getAutoBuyMinor());
		assertNull(session.inputTarget);
		assertEquals(0, player.getLevel());
	}

	@Test
	void selectedStackAmountIsLockedAndQuantityAnvilIsUnavailable() throws Exception {
		PlayerMock player = newSeller();
		openCurrent(player);
		player.simulateInventoryClick(47);
		player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 20));
		player.simulateInventoryClick(player.getOpenInventory(), ClickType.LEFT, 54);
		assertPage(player, GuiPage.WIZARD_ITEM);
		assertEquals(20, session(player).draft.getAmount());

		player.simulateInventoryClick(42);
		assertPage(player, GuiPage.WIZARD_ITEM);
		assertEquals(20, session(player).draft.getAmount());
		assertEquals(20, player.getInventory().getItem(0).getAmount());
	}

	@Test
	void quickOptionSubmenusApplyChoiceAndReturnToParent() throws Exception {
		ConfigController config = plugin.getInjector().getInstance(ConfigController.class);
		boolean previousImmersive = config.getConfig().getBoolean("immersive.enabled");
		config.getConfig().set("immersive.enabled", true);
		PlayerMock player = newSeller();
		try {
			openCurrent(player);
			player.simulateInventoryClick(47);
			assertPage(player, GuiPage.WIZARD_QUICK);

			GuiSession session = session(player);
			session.draft.setSealed(true);
			player.simulateInventoryClick(16);
			assertPage(player, GuiPage.WIZARD_QUICK_MODE);
			player.simulateInventoryClick(20);
			assertPage(player, GuiPage.WIZARD_QUICK);
			assertFalse(session.draft.isSealed());

			player.simulateInventoryClick(41);
			assertPage(player, GuiPage.WIZARD_QUICK_BUYOUT);
			player.simulateInventoryClick(22);
			assertPage(player, GuiPage.ANVIL_INPUT);
			AnvilInventoryMock anvil = assertInstanceOf(AnvilInventoryMock.class,
					player.getOpenInventory().getTopInventory());
			anvil.setItem(2, prepareAnvil(player, "50.00"));
			player.simulateInventoryClick(2);
			assertPage(player, GuiPage.WIZARD_QUICK);
			assertTrue(session.draft.isAutoBuyEnabled());
			assertEquals(5_000L, session.draft.getAutoBuyMinor());

			player.simulateInventoryClick(41);
			assertPage(player, GuiPage.WIZARD_QUICK_BUYOUT);
			player.simulateInventoryClick(20);
			assertPage(player, GuiPage.WIZARD_QUICK);
			assertFalse(session.draft.isAutoBuyEnabled());

			player.simulateInventoryClick(10);
			assertPage(player, GuiPage.WIZARD_QUICK_SESSION);
			player.simulateInventoryClick(45);
			assertPage(player, GuiPage.WIZARD_QUICK);
		} finally {
			config.getConfig().set("immersive.enabled", previousImmersive);
		}
	}

	@Test
	void invalidInputStaysOpenUntilAValidReplacementIsSubmitted() throws Exception {
		PlayerMock player = newSeller();
		openWizardMode(player);
		player.simulateInventoryClick(40);

		for (String invalid : new String[]{"", "not-a-number", "0", "301"}) {
			AnvilInventoryMock anvil = assertInstanceOf(AnvilInventoryMock.class,
					player.getOpenInventory().getTopInventory());
			anvil.setItem(2, prepareAnvil(player, invalid));
			player.simulateInventoryClick(2);
			assertPage(player, GuiPage.ANVIL_INPUT);
			assertEquals(GuiSession.InputTarget.DURATION, session(player).inputTarget);
			assertEquals(60, session(player).draft.getDurationSeconds());
		}

		AnvilInventoryMock anvil = assertInstanceOf(AnvilInventoryMock.class,
				player.getOpenInventory().getTopInventory());
		anvil.setItem(2, prepareAnvil(player, "300"));
		player.simulateInventoryClick(2);
		assertPage(player, GuiPage.WIZARD_MODE);
		assertEquals(300, session(player).draft.getDurationSeconds());
		assertNull(session(player).inputTarget);
	}

	@Test
	void customBidReadsRenameTextAndClearsTheInputTarget() throws Exception {
		PlayerMock seller = newSeller();
		openWizardMode(seller);
		seller.simulateInventoryClick(53);
		seller.simulateInventoryClick(53);
		assertPage(seller, GuiPage.WIZARD_REVIEW);
		seller.simulateInventoryClick(53);
		awaitCondition(auctions::hasActiveAuction);

		PlayerMock bidder = server.addPlayer();
		economy.setBalance(bidder, 100D);
		openCurrent(bidder);
		bidder.simulateInventoryClick(40);
		assertPage(bidder, GuiPage.ANVIL_INPUT);

		AnvilInventoryMock anvil = assertInstanceOf(AnvilInventoryMock.class,
				bidder.getOpenInventory().getTopInventory());
		ItemStack result = prepareAnvil(bidder, "7.25");
		setDisplayName(result, "1.00");
		anvil.setItem(2, result);
		bidder.simulateInventoryClick(2);

		assertPage(bidder, GuiPage.BID_CONFIRM);
		GuiSession session = session(bidder);
		assertEquals(725L, session.proposedBidMinor);
		assertNull(session.inputTarget);
		assertEquals(0, bidder.getLevel());

		assertTrue(auctions.cancelActiveAuction(session.selectedAuctionId, seller.getUniqueId(), false));
	}

	private PlayerMock newSeller() {
		PlayerMock player = server.addPlayer();
		player.setOp(true);
		player.setLevel(0);
		economy.setBalance(player, 100D);
		return player;
	}

	private void openWizardMode(PlayerMock player) throws Exception {
		openCurrent(player);
		player.simulateInventoryClick(47);
		assertPage(player, GuiPage.WIZARD_ITEM);
		player.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
		player.simulateInventoryClick(player.getOpenInventory(), ClickType.LEFT, 54);
		player.simulateInventoryClick(44);
		assertPage(player, GuiPage.WIZARD_MODE);
	}

	private void openCurrent(PlayerMock player) throws Exception {
		assertTrue(server.dispatchCommand(player, "auction"));
		awaitCondition(() -> session(player).isReady()
				&& player.getOpenInventory().getTopInventory().getItem(47) != null
				&& player.getOpenInventory().getTopInventory().getItem(47).getType() == Material.SMITHING_TABLE);
		assertPage(player, GuiPage.CURRENT);
	}

	private void submitAnvilValueWithStaleResult(PlayerMock player, int inputSlot,
	                                             String renameText, String staleResultName) {
		player.simulateInventoryClick(inputSlot);
		assertPage(player, GuiPage.ANVIL_INPUT);
		AnvilInventoryMock anvil = assertInstanceOf(AnvilInventoryMock.class,
				player.getOpenInventory().getTopInventory());
		ItemStack result = prepareAnvil(player, renameText);
		setDisplayName(result, staleResultName);
		anvil.setItem(2, result);
		player.simulateInventoryClick(2);
		assertPage(player, GuiPage.WIZARD_PRICE);
	}

	private ItemStack prepareAnvil(PlayerMock player, String renameText) {
		AnvilInventoryMock anvil = assertInstanceOf(AnvilInventoryMock.class,
				player.getOpenInventory().getTopInventory());
		anvil.setRenameText(renameText);
		AnvilView view = anvilView(player.getOpenInventory(), anvil);
		PrepareAnvilEvent event = new PrepareAnvilEvent(view, null);
		gui.onPrepareAnvil(event);
		ItemStack result = event.getResult();
		assertNotNull(result);
		return result;
	}

	/**
	 * MockBukkit 4 exposes an {@link AnvilInventoryMock} as the top inventory, but its
	 * open view is still the generic {@link InventoryView}. Paper 1.21 requires an
	 * {@link AnvilView} when constructing {@link PrepareAnvilEvent}, so adapt the two
	 * test doubles until MockBukkit supplies a specialised view implementation.
	 */
	private AnvilView anvilView(InventoryView delegate, AnvilInventoryMock anvil) {
		return (AnvilView) Proxy.newProxyInstance(
				AnvilView.class.getClassLoader(),
				new Class<?>[]{AnvilView.class},
				(proxy, method, args) -> invokeViewMethod(delegate, anvil, method, args));
	}

	private Object invokeViewMethod(InventoryView delegate, AnvilInventoryMock anvil,
	                                Method method, Object[] args) throws Throwable {
		try {
			Method inventoryMethod = anvil.getClass().getMethod(method.getName(), method.getParameterTypes());
			return inventoryMethod.invoke(anvil, args);
		} catch (NoSuchMethodException ignored) {
			try {
				Method viewMethod = delegate.getClass().getMethod(method.getName(), method.getParameterTypes());
				return viewMethod.invoke(delegate, args);
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		} catch (InvocationTargetException exception) {
			throw exception.getCause();
		}
	}

	private void setDisplayName(ItemStack item, String name) {
		ItemMeta meta = item.getItemMeta();
		meta.setDisplayName(name);
		item.setItemMeta(meta);
	}

	@SuppressWarnings("unchecked")
	private GuiSession session(PlayerMock player) throws ReflectiveOperationException {
		Field sessionsField = AuctionGuiController.class.getDeclaredField("sessions");
		sessionsField.setAccessible(true);
		Map<UUID, GuiSession> sessions = (Map<UUID, GuiSession>) sessionsField.get(gui);
		GuiSession session = sessions.get(player.getUniqueId());
		assertNotNull(session);
		return session;
	}

	private void assertPage(PlayerMock player, GuiPage expected) {
		AuctionGuiHolder holder = assertInstanceOf(AuctionGuiHolder.class,
				player.getOpenInventory().getTopInventory().getHolder());
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

	private void cleanupMisplacedTestData() throws IOException {
		Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		Path pluginsDirectory = projectRoot.resolve("plugins").normalize();
		if (!Files.isRegularFile(projectRoot.resolve("pom.xml")) || !pluginsDirectory.startsWith(projectRoot)) {
			return;
		}
		deleteTree(pluginsDirectory.resolve("RookieAuctions"));
		Files.deleteIfExists(pluginsDirectory.resolve("ezAuctions"));
		if (Files.isDirectory(pluginsDirectory)) {
			try (Stream<Path> entries = Files.list(pluginsDirectory)) {
				if (entries.findAny().isEmpty()) {
					Files.deleteIfExists(pluginsDirectory);
				}
			}
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

	@FunctionalInterface
	private interface CheckedBooleanSupplier {
		boolean getAsBoolean() throws Exception;
	}
}
