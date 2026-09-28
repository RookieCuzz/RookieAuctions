package me.elian.ezauctions.immersive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HolographicBidMenuControllerWheelTest {
	@Test
	void adjacentHotbarSlotsAdjustDistanceAcrossWrap() {
		assertEquals(1, HolographicBidMenuController.wheelDirection(0, 1));
		assertEquals(1, HolographicBidMenuController.wheelDirection(8, 0));
		assertEquals(-1, HolographicBidMenuController.wheelDirection(0, 8));
		assertEquals(-1, HolographicBidMenuController.wheelDirection(4, 3));
	}

	@Test
	void nonAdjacentOrUnchangedSlotsKeepVanillaBehavior() {
		assertEquals(0, HolographicBidMenuController.wheelDirection(4, 2));
		assertEquals(0, HolographicBidMenuController.wheelDirection(0, 0));
		assertEquals(0, HolographicBidMenuController.wheelDirection(-1, 0));
	}
}
