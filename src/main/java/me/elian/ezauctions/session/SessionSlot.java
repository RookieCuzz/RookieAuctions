package me.elian.ezauctions.session;

import java.time.LocalTime;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A stable daily slot in the auction calendar.
 *
 * <p>The ID is persisted as part of a session key, so it deliberately excludes
 * localized display text.</p>
 */
public record SessionSlot(String id, LocalTime startTime) {
	private static final Pattern VALID_ID = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

	public SessionSlot {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(startTime, "startTime");
		if (!VALID_ID.matcher(id).matches()) {
			throw new IllegalArgumentException("Session slot ID must be a stable lowercase identifier");
		}
	}
}
