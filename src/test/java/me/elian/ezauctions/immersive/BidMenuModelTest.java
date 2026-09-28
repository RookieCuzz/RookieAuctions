package me.elian.ezauctions.immersive;

import me.elian.ezauctions.model.AuctionView;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BidMenuModelTest {
	@BeforeAll
	static void setUpBukkit() {
		MockBukkit.mock();
	}

	@AfterAll
	static void tearDownBukkit() {
		MockBukkit.unmock();
	}

	@Test
	void publicBidShowsCurrentPriceAndMinimumStepAndBuyoutLabels() {
		UUID auctionId = UUID.randomUUID();
		AuctionView view = view(auctionId, true, false, 1_000, 250,
				1_750, UUID.randomUUID(), 10_000, 0, Integer.MAX_VALUE, false);

		BidMenuModel model = BidMenuModel.fromView(view);

		assertEquals(auctionId, model.auctionId());
		assertEquals(7L, model.revision());
		assertTrue(model.heading().contains("当前价 $17.50"));
		assertFalse(model.heading().contains("你的最高价"));
		assertEquals(2_000L, BidMenuModel.minimumBid(view));
		assertEquals("最低有效价\n$20.00", model.buttons().get(BidMenuAction.MINIMUM));
		assertEquals("+1 档\n$22.50", model.buttons().get(BidMenuAction.ONE_STEP));
		assertEquals("+5 档\n$32.50", model.buttons().get(BidMenuAction.FIVE_STEPS));
		assertEquals("+10 档\n$45.00", model.buttons().get(BidMenuAction.TEN_STEPS));
		assertEquals("一口买下\n$100.00", model.buttons().get(BidMenuAction.BUYOUT));
		assertTrue(model.enabled(BidMenuAction.MINIMUM));
		assertTrue(model.enabled(BidMenuAction.BUYOUT));
	}

	@Test
	void sealedBidShowsOnlyViewersPrivatePriceAndDisablesExhaustedBidder() {
		AuctionView view = view(UUID.randomUUID(), true, true, 500, 250,
				987_654, UUID.randomUUID(), 0, 1_000, 0, false);

		BidMenuModel model = BidMenuModel.fromView(view);

		assertTrue(model.heading().contains("密封"));
		assertTrue(model.heading().contains("你的最高价 $10.00"));
		assertFalse(model.heading().contains("9,876.54"));
		assertFalse(model.heading().contains("TOP_BIDDER_PRIVATE"));
		assertEquals(1_250L, BidMenuModel.minimumBid(view));
		assertEquals("最低有效价\n$12.50", model.buttons().get(BidMenuAction.MINIMUM));
		assertEquals("+1 档\n$15.00", model.buttons().get(BidMenuAction.ONE_STEP));
		assertEquals("一口价\n未启用", model.buttons().get(BidMenuAction.BUYOUT));
		for (BidMenuAction action : BidMenuAction.values()) {
			assertFalse(model.enabled(action));
		}
	}

	@Test
	void sealedFirstBidStartsAtOpeningPriceWithoutEnablingMissingBuyout() {
		AuctionView view = view(UUID.randomUUID(), true, true, 500, 250,
				987_654, UUID.randomUUID(), 0, 0, 1, false);

		BidMenuModel model = BidMenuModel.fromView(view);

		assertTrue(model.heading().contains("你的最高价 暂无"));
		assertEquals(500L, BidMenuModel.minimumBid(view));
		assertEquals("最低有效价\n$5.00", model.buttons().get(BidMenuAction.MINIMUM));
		assertTrue(model.enabled(BidMenuAction.MINIMUM));
		assertFalse(model.enabled(BidMenuAction.BUYOUT));
	}

	@Test
	void waitingMenuHasNoAuctionOrClickableActions() {
		BidMenuModel waiting = BidMenuModel.waiting("等待下一件拍品");
		BidMenuModel ended = BidMenuModel.fromView(view(UUID.randomUUID(), false, false,
				1_000, 250, 1_750, null, 10_000, 0, Integer.MAX_VALUE, false));

		for (BidMenuModel model : new BidMenuModel[]{waiting, ended}) {
			assertTrue(model.heading().contains("等待下一件拍品"));
			assertNull(model.auctionId());
			for (BidMenuAction action : BidMenuAction.values()) {
				assertEquals("", model.buttons().get(action));
				assertFalse(model.enabled(action));
			}
		}
	}

	private static AuctionView view(UUID auctionId, boolean running, boolean sealed,
	                                long startingPriceMinor, long incrementMinor,
	                                long currentPriceMinor, UUID highestBidderId,
	                                long autoBuyMinor, long viewerHighestBidMinor,
	                                int viewerRemainingBidCount, boolean bidProcessing) {
		return new AuctionView(auctionId, 7L, running, sealed, 120, 75,
				new ItemStack(Material.DIAMOND), 3, UUID.randomUUID(), "SELLER",
				"world", startingPriceMinor, incrementMinor, currentPriceMinor,
				highestBidderId, "TOP_BIDDER_PRIVATE", autoBuyMinor,
				viewerHighestBidMinor, 1, viewerRemainingBidCount, bidProcessing);
	}
}
