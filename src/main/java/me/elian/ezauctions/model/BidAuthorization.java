package me.elian.ezauctions.model;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Synchronous, fail-closed authorization hook for scheduled-session bids.
 * Implementations should read hot in-memory session/attendance/venue state and
 * must not block the server thread.
 */
@FunctionalInterface
public interface BidAuthorization {
	BidAuthorization DENY_ALL = (sessionId, player) -> Decision.SESSION_NOT_RUNNING;

	@NotNull Decision authorize(@NotNull String sessionId, @NotNull Player player);

	enum Decision {
		ALLOWED,
		SESSION_NOT_RUNNING,
		NOT_PARTICIPANT,
		NOT_IN_VENUE
	}
}
