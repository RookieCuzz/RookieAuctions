package me.elian.ezauctions.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/** Fired after a lot's session relation has durably changed from ACTIVE to SETTLED. */
public final class AuctionLotFinishedEvent extends Event {
	private static final HandlerList HANDLERS = new HandlerList();
	private final String sessionId;
	private final UUID lotId;
	private final UUID auctionId;
	private final int sequenceNumber;

	public AuctionLotFinishedEvent(@NotNull String sessionId, @NotNull UUID lotId,
	                               @NotNull UUID auctionId, int sequenceNumber) {
		this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
		this.lotId = Objects.requireNonNull(lotId, "lotId");
		this.auctionId = Objects.requireNonNull(auctionId, "auctionId");
		this.sequenceNumber = sequenceNumber;
		if (sessionId.isBlank()) {
			throw new IllegalArgumentException("sessionId must not be blank");
		}
		if (sequenceNumber <= 0) {
			throw new IllegalArgumentException("sequenceNumber must be positive");
		}
	}

	public @NotNull String getSessionId() {
		return sessionId;
	}

	public @NotNull UUID getLotId() {
		return lotId;
	}

	public @NotNull UUID getAuctionId() {
		return auctionId;
	}

	public int getSequenceNumber() {
		return sequenceNumber;
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return HANDLERS;
	}

	public static @NotNull HandlerList getHandlerList() {
		return HANDLERS;
	}
}
