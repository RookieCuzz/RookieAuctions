package me.elian.ezauctions.immersive;

import me.elian.ezauctions.Logger;
import me.elian.ezauctions.controller.ConfigController;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionStairSeatControllerTest {
	private static ServerMock server;
	private static Plugin plugin;
	private static World world;
	private static ConfigController configController;
	private static VenueConfig venueConfig;
	private static Path pluginDataDirectory;

	@BeforeAll
	static void setUp() {
		server = MockBukkit.mock();
		plugin = MockBukkit.createMockPlugin("AuctionStairSeatTest");
		pluginDataDirectory = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
		world = server.addSimpleWorld("seat-world");
		configController = new ConfigController(plugin, new SilentLogger());
		FileConfiguration config = configController.getConfig();
		config.set("immersive.enabled", true);
		point(config, VenueLocationType.BUYER_SPAWN, 0, 65, 0);
		point(config, VenueLocationType.ITEM_DISPLAY, 2, 66, 0);
		point(config, VenueLocationType.INFO_DISPLAY, 0, 67, 2);
		point(config, VenueLocationType.CORNER_1, -10.4, 64, -10.3);
		point(config, VenueLocationType.CORNER_2, 10.2, 80, 10.8);
		venueConfig = new VenueConfig(configController, plugin);
		assertTrue(venueConfig.isReady());
	}

	@AfterAll
	static void tearDown() throws IOException {
		MockBukkit.unmock();
		if (pluginDataDirectory != null && Files.exists(pluginDataDirectory)) {
			try (Stream<Path> paths = Files.walk(pluginDataDirectory)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	@Test
	void onePlayerPerStairAndShutdownRemovesTemporarySeat() {
		Block stair = world.getBlockAt(0, 65, 0);
		stair.setType(Material.OAK_STAIRS);
		PlayerMock first = server.addPlayer();
		PlayerMock second = server.addPlayer();
		AtomicInteger sitCount = new AtomicInteger();
		AtomicInteger leaveCount = new AtomicInteger();
		AuctionStairSeatController controller = new AuctionStairSeatController(plugin, venueConfig,
				player -> sitCount.incrementAndGet(), player -> leaveCount.incrementAndGet());
		controller.start();
		try {
			click(controller, first, stair);
			assertTrue(controller.isSeated(first));
			assertEquals(1, sitCount.get());
			assertEquals(1, world.getEntitiesByClass(ArmorStand.class).size());
			assertFalse(world.getEntitiesByClass(ArmorStand.class).iterator().next().isPersistent());
			PlayerInteractEvent offhand = new PlayerInteractEvent(first, Action.RIGHT_CLICK_BLOCK,
					first.getInventory().getItemInOffHand(), stair, BlockFace.UP,
					EquipmentSlot.OFF_HAND);
			controller.onInteract(offhand);
			assertTrue(offhand.isCancelled());

			click(controller, second, stair);
			assertFalse(controller.isSeated(second));
			assertEquals(1, sitCount.get());

			controller.shutdown();
			assertFalse(controller.isSeated(first));
			assertEquals(1, leaveCount.get());
			assertTrue(world.getEntitiesByClass(ArmorStand.class).isEmpty());
		} finally {
			controller.shutdown();
			stair.setType(Material.AIR);
		}
	}

	@Test
	void brokenChairAndDismountReleaseOccupancyOnce() {
		Block stair = world.getBlockAt(2, 65, 0);
		stair.setType(Material.OAK_STAIRS);
		PlayerMock first = server.addPlayer();
		PlayerMock second = server.addPlayer();
		AtomicInteger leaveCount = new AtomicInteger();
		AuctionStairSeatController controller = new AuctionStairSeatController(plugin, venueConfig,
				ignored -> {}, ignored -> leaveCount.incrementAndGet());
		controller.start();
		try {
			click(controller, first, stair);
			assertTrue(controller.isSeated(first));
			controller.onBlockBreak(new BlockBreakEvent(stair, second));
			assertFalse(controller.isSeated(first));
			assertEquals(1, leaveCount.get());
			assertTrue(world.getEntitiesByClass(ArmorStand.class).isEmpty());

			click(controller, second, stair);
			assertTrue(controller.isSeated(second));
			ArmorStand stand = world.getEntitiesByClass(ArmorStand.class).iterator().next();
			controller.onDismount(new EntityDismountEvent(second, stand));
			assertFalse(controller.isSeated(second));
			assertEquals(2, leaveCount.get());
			assertTrue(world.getEntitiesByClass(ArmorStand.class).isEmpty());
		} finally {
			controller.shutdown();
			stair.setType(Material.AIR);
		}
	}

	@Test
	void floorStairTouchingVenueLowerFaceCanBeSatOn() {
		Block stair = world.getBlockAt(0, 63, 0);
		stair.setType(Material.OAK_STAIRS);
		PlayerMock player = server.addPlayer();
		AuctionStairSeatController controller = new AuctionStairSeatController(plugin, venueConfig,
				ignored -> {}, ignored -> {});
		controller.start();
		try {
			click(controller, player, stair);
			assertTrue(controller.isSeated(player));
		} finally {
			controller.shutdown();
			stair.setType(Material.AIR);
		}
	}

	@Test
	void ignoresStairsOutsideVenueWhileDisabledOrWhileSneaking() {
		Block outside = world.getBlockAt(30, 65, 0);
		Block inside = world.getBlockAt(1, 65, 0);
		outside.setType(Material.OAK_STAIRS);
		inside.setType(Material.OAK_STAIRS);
		PlayerMock player = server.addPlayer();
		AtomicInteger sitCount = new AtomicInteger();
		AuctionStairSeatController controller = new AuctionStairSeatController(plugin, venueConfig,
				ignored -> sitCount.incrementAndGet(), ignored -> {});
		controller.start();
		try {
			click(controller, player, outside);
			assertEquals(0, sitCount.get());
			configController.getConfig().set("immersive.enabled", false);
			click(controller, player, inside);
			assertEquals(0, sitCount.get());
			configController.getConfig().set("immersive.enabled", true);
			player.setSneaking(true);
			PlayerInteractEvent sneakClick = click(controller, player, inside);
			assertFalse(sneakClick.isCancelled());
			assertEquals(0, sitCount.get());
		} finally {
			configController.getConfig().set("immersive.enabled", true);
			controller.shutdown();
			outside.setType(Material.AIR);
			inside.setType(Material.AIR);
		}
	}

	private static PlayerInteractEvent click(AuctionStairSeatController controller,
	                                         PlayerMock player, Block block) {
		PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
				player.getInventory().getItemInMainHand(), block, BlockFace.UP, EquipmentSlot.HAND);
		controller.onInteract(event);
		return event;
	}

	private static void point(FileConfiguration config, VenueLocationType type,
	                          double x, double y, double z) {
		String path = "immersive.venue." + type.configKey();
		config.set(path + ".world", world.getName());
		config.set(path + ".world-uuid", world.getUID().toString());
		config.set(path + ".x", x);
		config.set(path + ".y", y);
		config.set(path + ".z", z);
		config.set(path + ".yaw", 0D);
		config.set(path + ".pitch", 0D);
	}

	private static final class SilentLogger implements Logger {
		@Override public void info(String message) {}
		@Override public void warning(String message) {}
		@Override public void warning(String message, Exception exception) {}
		@Override public void severe(String message) {}
		@Override public void severe(String message, Exception exception) {}
	}
}
