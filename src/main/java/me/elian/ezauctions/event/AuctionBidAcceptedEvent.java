package me.elian.ezauctions.event;

import me.elian.ezauctions.model.Auction;
import me.elian.ezauctions.model.Bid;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/** Fired on the server thread only after the accepted bid has been durably recorded. */
public final class AuctionBidAcceptedEvent extends Event {
	private static final HandlerList handlers = new HandlerList();

	private final Auction auction;
	private final Bid bid;
	private final UUID transactionId;

	public AuctionBidAcceptedEvent(@NotNull Auction auction, @NotNull Bid bid,
	                              @NotNull UUID transactionId) {
		super(false);
		this.auction = Objects.requireNonNull(auction, "auction");
		this.bid = Objects.requireNonNull(bid, "bid");
		this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
	}

	public @NotNull Auction getAuction() {
		return auction;
	}

	public @NotNull Bid getBid() {
		return bid;
	}

	public @NotNull UUID getTransactionId() {
		return transactionId;
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return handlers;
	}

	public static @NotNull HandlerList getHandlerList() {
		return handlers;
	}
}
