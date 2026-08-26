package me.elian.ezauctions.immersive;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmersiveVenueTest {
	@BeforeAll
	static void setUpBukkit() {
		MockBukkit.mock();
	}

	@AfterAll
	static void tearDownBukkit() {
		MockBukkit.unmock();
	}

	@Test
	void cuboidNormalizesCornersAndIncludesAllFaces() {
		UUID world = UUID.randomUUID();
		InclusiveCuboid cuboid = new InclusiveCuboid(world, "world",
				10D, 20D, 30D, -10D, 0D, -30D);

		assertTrue(cuboid.contains(world, -10D, 0D, -30D));
		assertTrue(cuboid.contains(world, 10D, 20D, 30D));
		assertTrue(cuboid.contains(world, 0D, 10D, 0D));
		assertFalse(cuboid.contains(world, 10.00001D, 20D, 30D));
		assertFalse(cuboid.contains(UUID.randomUUID(), 0D, 10D, 0D));
		assertFalse(cuboid.contains(Double.NaN, 10D, 0D));
	}

	@Test
	void cuboidRejectsNonFiniteBounds() {
		assertThrows(IllegalArgumentException.class, () -> new InclusiveCuboid(
				UUID.randomUUID(), "world", 0D, 0D, 0D,
				Double.POSITIVE_INFINITY, 1D, 1D));
	}

	@Test
	void sealedWorldDisplayNeverContainsTheBidText() {
		VenueDisplayState state = VenueDisplayState.lot("午场", 1, 16,
				new ItemStack(Material.DIAMOND), "钻石", 29,
				"THIS_MUST_NOT_LEAK", true, 1_829);
		String rendered = LegacyComponentSerializer.legacySection()
				.serialize(VenueDisplayController.buildInformation(state));

		assertTrue(rendered.contains("已密封"));
		assertFalse(rendered.contains("THIS_MUST_NOT_LEAK"));
		assertTrue(rendered.contains("00:29"));
		assertTrue(rendered.contains("30:29"));
	}

	@Test
	void publicWorldDisplayIncludesFormattedBidAndDurations() {
		VenueDisplayState state = VenueDisplayState.lot("晚场", 16, 16,
				new ItemStack(Material.EMERALD), "祖母绿", 120,
				"¥ 2,500", false, 3_661);
		String rendered = LegacyComponentSerializer.legacySection()
				.serialize(VenueDisplayController.buildInformation(state));

		assertTrue(rendered.contains("¥ 2,500"));
		assertTrue(rendered.contains("02:00"));
		assertTrue(rendered.contains("1:01:01"));
		assertEquals("--:--", VenueDisplayController.formatDuration(-1));
	}

	@Test
	void venueLocationCommandNamesAreStable() {
		assertEquals(VenueLocationType.BUYER_SPAWN,
				VenueLocationType.fromCommandArgument("buyer-spawn").orElseThrow());
		assertEquals(VenueLocationType.CORNER_1,
				VenueLocationType.fromCommandArgument("corner_1").orElseThrow());
		assertTrue(VenueLocationType.fromCommandArgument("unknown").isEmpty());
	}

	@Test
	void displaySpinPeriodIsMeasuredInServerTicks() {
		assertEquals(4.5F, VenueDisplayController.degreesPerTick(80), 0.0001F);
		assertEquals(360F, VenueDisplayController.degreesPerTick(0), 0.0001F);
	}
}
