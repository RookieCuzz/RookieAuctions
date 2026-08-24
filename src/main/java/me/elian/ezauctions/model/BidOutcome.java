package me.elian.ezauctions.model;

import org.jetbrains.annotations.Nullable;

public record BidOutcome(Status status, long acceptedAmountMinor, @Nullable AuctionView latestView) {
	public static BidOutcome of(Status status, @Nullable AuctionView latestView) {
		return new BidOutcome(status, 0L, latestView);
	}

	public enum Status {
		SUCCESS,
		NO_AUCTION,
		STALE_VIEW,
		BID_PROCESSING,
		SELF_BID,
		BLOCKED_WORLD,
		WRONG_WORLD,
		OUTSIDE_BOUNDARY,
		TOO_LOW,
		NO_BUYOUT,
		MAX_BIDS,
		CONSECUTIVE_LIMIT,
		INSUFFICIENT_FUNDS,
		ECONOMY_FAILED,
		EVENT_CANCELLED,
		PERSISTENCE_FAILED,
		INVALID_AMOUNT
	}
}
