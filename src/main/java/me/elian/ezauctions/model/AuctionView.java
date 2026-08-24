package me.elian.ezauctions.model;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Immutable GUI projection. Inventory clicks never trust this object; the auction revalidates the ID and revision.
 */
public record AuctionView(
		@NotNull UUID auctionId,
		long revision,
		boolean running,
		boolean sealed,
		int startingSeconds,
		int remainingSeconds,
		@NotNull ItemStack item,
		int amount,
		@NotNull UUID sellerId,
		@NotNull String sellerName,
		@NotNull String world,
		long startingPriceMinor,
		long incrementMinor,
		long currentPriceMinor,
		@Nullable UUID highestBidderId,
		@NotNull String highestBidderName,
		long autoBuyMinor,
		long viewerHighestBidMinor,
		int viewerBidCount,
		int viewerRemainingBidCount,
		boolean bidProcessing
) {
}
