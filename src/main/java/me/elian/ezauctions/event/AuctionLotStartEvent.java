package me.elian.ezauctions.event;

import me.elian.ezauctions.session.AuctionPublicLotView;
import me.elian.ezauctions.session.LotState;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** Fired after a scheduled lot is durable and accepted by the single-lot engine. */
public final class AuctionLotStartEvent extends Event {
	private static final HandlerList HANDLERS = new HandlerList();
	private final AuctionPublicLotView lot;

	public AuctionLotStartEvent(@NotNull AuctionPublicLotView lot) {
		this.lot = Objects.requireNonNull(lot, "lot");
		if (lot.state() != LotState.ACTIVE) {
			throw new IllegalArgumentException("A lot-start event requires an ACTIVE view");
		}
	}

	public @NotNull AuctionPublicLotView getLot() {
		return lot;
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return HANDLERS;
	}

	public static @NotNull HandlerList getHandlerList() {
		return HANDLERS;
	}
}
