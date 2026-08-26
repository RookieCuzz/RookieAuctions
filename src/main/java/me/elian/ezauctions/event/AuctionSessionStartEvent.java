package me.elian.ezauctions.event;

import me.elian.ezauctions.session.AuctionSessionView;
import me.elian.ezauctions.session.SessionState;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** Fired after the durable session state has successfully changed to RUNNING. */
public final class AuctionSessionStartEvent extends Event {
	private static final HandlerList HANDLERS = new HandlerList();
	private final AuctionSessionView session;

	public AuctionSessionStartEvent(@NotNull AuctionSessionView session) {
		this.session = Objects.requireNonNull(session, "session");
		if (session.state() != SessionState.RUNNING) {
			throw new IllegalArgumentException("A session-start event requires a RUNNING view");
		}
	}

	public @NotNull AuctionSessionView getSession() {
		return session;
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return HANDLERS;
	}

	public static @NotNull HandlerList getHandlerList() {
		return HANDLERS;
	}
}
