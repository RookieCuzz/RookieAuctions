package me.elian.ezauctions.session;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Immutable public projection of a lot. It intentionally contains neither a
 * bidder identity nor a viewer's private sealed bids.
 */
public record AuctionPublicLotView(
		UUID lotId,
		String sessionKey,
		int sequenceNumber,
		LotState state,
		AuctionMode mode,
		String itemDisplayName,
		int itemAmount,
		String sellerDisplayName,
		long startingPriceMinor,
		long minimumIncrementMinor,
		long buyoutPriceMinor,
		PublicBidPrice publicBidPrice,
		int remainingSeconds,
		long revision
) {
	public AuctionPublicLotView {
		Objects.requireNonNull(lotId, "lotId");
		Objects.requireNonNull(sessionKey, "sessionKey");
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(mode, "mode");
		Objects.requireNonNull(itemDisplayName, "itemDisplayName");
		Objects.requireNonNull(sellerDisplayName, "sellerDisplayName");
		Objects.requireNonNull(publicBidPrice, "publicBidPrice");
		if (sessionKey.isBlank() || itemDisplayName.isBlank() || sellerDisplayName.isBlank()) {
			throw new IllegalArgumentException("Public lot text fields must not be blank");
		}
		if (sequenceNumber <= 0 || itemAmount <= 0) {
			throw new IllegalArgumentException("Lot sequence and item amount must be positive");
		}
		if (startingPriceMinor < 0 || minimumIncrementMinor < 0 || buyoutPriceMinor < 0) {
			throw new IllegalArgumentException("Lot prices must not be negative");
		}
		if (remainingSeconds < 0 || revision < 0) {
			throw new IllegalArgumentException("Remaining time and revision must not be negative");
		}
		if (mode == AuctionMode.SEALED && publicBidPrice.amountMinor().isPresent()) {
			throw new IllegalArgumentException("A sealed public lot must not disclose its current price");
		}
		if (mode == AuctionMode.OPEN && publicBidPrice.amountMinor().isEmpty()) {
			throw new IllegalArgumentException("An open public lot must disclose its current price");
		}
	}

	public OptionalLong currentPriceMinor() {
		return publicBidPrice.amountMinor();
	}

	public boolean sealed() {
		return mode == AuctionMode.SEALED;
	}
}
