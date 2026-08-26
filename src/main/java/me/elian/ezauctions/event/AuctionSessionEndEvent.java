package me.elian.ezauctions.event;

import me.elian.ezauctions.session.AuctionSessionView;
import me.elian.ezauctions.session.SessionState;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** Fired after a session reaches COMPLETED or SKIPPED in durable storage. */
public final class AuctionSessionEndEvent extends Event {
	private static final HandlerList HANDLERS = new HandlerList();
	private final AuctionSessionView session;

	public AuctionSessionEndEvent(@NotNull AuctionSessionView session) {
		this.session = Objects.requireNonNull(session, "session");
		if (!session.state().isTerminal()) {
			throw new IllegalArgumentException("A session-end event requires a terminal view");
		}
	}

	public @NotNull AuctionSessionView getSession() {
		return session;
	}

	public boolean isSkipped() {
		return session.state() == SessionState.SKIPPED;
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return HANDLERS;
	}

	public static @NotNull HandlerList getHandlerList() {
		return HANDLERS;
	}
}
