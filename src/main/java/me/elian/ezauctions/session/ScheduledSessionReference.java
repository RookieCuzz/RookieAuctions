package me.elian.ezauctions.session;

import java.time.Instant;
import java.util.Objects;

/** Minimal synchronous projection for countdowns that must include locked/waiting sessions. */
public record ScheduledSessionReference(
		String sessionKey,
		Instant scheduledStart,
		SessionState state
) {
	public ScheduledSessionReference {
		Objects.requireNonNull(sessionKey, "sessionKey");
		Objects.requireNonNull(scheduledStart, "scheduledStart");
		Objects.requireNonNull(state, "state");
		if (sessionKey.isBlank()) {
			throw new IllegalArgumentException("sessionKey must not be blank");
		}
	}
}
