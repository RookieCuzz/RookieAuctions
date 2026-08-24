package me.elian.ezauctions.model;

import org.jetbrains.annotations.NotNull;

public record Bid(@NotNull AuctionPlayer auctionPlayer, long amountMinor) {
	public Bid(@NotNull AuctionPlayer auctionPlayer, double amount) {
		this(auctionPlayer, Money.fromMajor(amount));
	}

	public double amount() {
		return Money.toMajor(amountMinor);
	}
}
