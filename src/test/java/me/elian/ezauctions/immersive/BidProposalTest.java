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
import static org.junit.jupiter.api.Assertions.assertThrows;

class BidProposalTest {
	@BeforeAll
	static void setUpBukkit() {
		MockBukkit.mock();
	}

	@AfterAll
	static void tearDownBukkit() {
		MockBukkit.unmock();
	}

	@Test
	void publicActionsUseEffectiveMinimumAndKeepAuctionRevision() {
		UUID auctionId = UUID.randomUUID();
		AuctionView view = view(auctionId, true, false, 1_000L, 250L,
				1_750L, UUID.randomUUID(), 10_000L, 0L);

		assertProposal(BidProposal.fromAction(BidMenuAction.MINIMUM, view),
				auctionId, 19L, 2_000L, false, "最低有效价 $20.00");
		assertProposal(BidProposal.fromAction(BidMenuAction.ONE_STEP, view),
				auctionId, 19L, 2_250L, false, "+1 档 $22.50");
		assertProposal(BidProposal.fromAction(BidMenuAction.FIVE_STEPS, view),
				auctionId, 19L, 3_250L, false, "+5 档 $32.50");
		assertProposal(BidProposal.fromAction(BidMenuAction.TEN_STEPS, view),
				auctionId, 19L, 4_500L, false, "+10 档 $45.00");
		assertProposal(BidProposal.fromAction(BidMenuAction.BUYOUT, view),
				auctionId, 19L, 10_000L, true, "一口买下 $100.00");
	}

	@Test
	void bidsAtOrAboveBuyoutConfirmTheActualPurchasePrice() {
		AuctionView view = view(UUID.randomUUID(), true, false, 1_000L, 250L,
				1_750L, UUID.randomUUID(), 2_200L, 0L);

		BidProposal step = BidProposal.fromAction(BidMenuAction.ONE_STEP, view);
		BidProposal custom = BidProposal.custom(5_000L, view);
		for (BidProposal proposal : new BidProposal[]{step, custom}) {
			assertEquals(2_200L, proposal.amountMinor());
			assertEquals(true, proposal.buyout());
			assertEquals("一口买下 $22.00", proposal.label());
		}
		assertEquals("+1 档\n$22.00", BidMenuModel.fromView(view).buttons().get(BidMenuAction.ONE_STEP));
	}

	@Test
	void sealedProposalUsesOnlyTheViewersBidToFindTheMinimum() {
		AuctionView view = view(UUID.randomUUID(), true, true, 500L, 250L,
				987_654L, UUID.randomUUID(), 0L, 1_000L);

		BidProposal proposal = BidProposal.fromAction(BidMenuAction.MINIMUM, view);

		assertEquals(1_250L, proposal.amountMinor());
		assertFalse(proposal.label().contains("9,876.54"));
		assertFalse(proposal.buyout());
	}

	@Test
	void customPriceMustBeAtLeastCurrentMinimum() {
		AuctionView view = view(UUID.randomUUID(), true, false, 1_000L, 250L,
				1_750L, UUID.randomUUID(), 0L, 0L);

		assertThrows(IllegalArgumentException.class, () -> BidProposal.custom(0L, view));
		assertThrows(IllegalArgumentException.class, () -> BidProposal.custom(1_999L, view));
		BidProposal proposal = BidProposal.custom(2_345L, view);
		assertEquals(view.auctionId(), proposal.auctionId());
		assertEquals(view.revision(), proposal.revision());
		assertEquals(2_345L, proposal.amountMinor());
		assertEquals("自定义出价 $23.45", proposal.label());
		assertFalse(proposal.buyout());
	}

	@Test
	void unavailableActionsAndOverflowCannotProduceProposals() {
		AuctionView noBuyout = view(UUID.randomUUID(), true, false, 1_000L, 250L,
				1_000L, null, 0L, 0L);
		AuctionView stopped = view(UUID.randomUUID(), false, false, 1_000L, 250L,
				1_000L, null, 10_000L, 0L);
		AuctionView overflowing = view(UUID.randomUUID(), true, false,
				Long.MAX_VALUE - 1L, 250L, Long.MAX_VALUE - 1L, null, 0L, 0L);

		assertThrows(IllegalArgumentException.class,
				() -> BidProposal.fromAction(BidMenuAction.BUYOUT, noBuyout));
		assertThrows(IllegalArgumentException.class,
				() -> BidProposal.fromAction(BidMenuAction.CUSTOM, noBuyout));
		assertThrows(IllegalArgumentException.class,
				() -> BidProposal.fromAction(BidMenuAction.MINIMUM, stopped));
		assertThrows(IllegalArgumentException.class,
				() -> BidProposal.fromAction(BidMenuAction.ONE_STEP, overflowing));
	}

	private static void assertProposal(BidProposal proposal, UUID auctionId, long revision,
	                                   long amountMinor, boolean buyout, String label) {
		assertEquals(auctionId, proposal.auctionId());
		assertEquals(revision, proposal.revision());
		assertEquals(amountMinor, proposal.amountMinor());
		assertEquals(buyout, proposal.buyout());
		assertEquals(label, proposal.label());
	}

	private static AuctionView view(UUID auctionId, boolean running, boolean sealed,
	                                long startingPriceMinor, long incrementMinor,
	                                long currentPriceMinor, UUID highestBidderId,
	                                long autoBuyMinor, long viewerHighestBidMinor) {
		return new AuctionView(auctionId, 19L, running, sealed, 120, 75,
				new ItemStack(Material.EMERALD), 1, UUID.randomUUID(), "seller",
				"world", startingPriceMinor, incrementMinor, currentPriceMinor,
				highestBidderId, "high bidder", autoBuyMinor,
				viewerHighestBidMinor, 1, 3, false);
	}
}
