package me.elian.ezauctions.immersive;

import me.elian.ezauctions.session.AttendanceState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Non-exceptional result for registration, entry, venue return and leave operations. */
public record AttendanceResult(@NotNull Status status, @Nullable String sessionId,
		@Nullable AttendanceState state) {
	public AttendanceResult {
		Objects.requireNonNull(status, "status");
		if (sessionId != null && sessionId.isBlank()) {
			throw new IllegalArgumentException("sessionId must not be blank");
		}
	}

	public static @NotNull AttendanceResult of(@NotNull Status status, @Nullable String sessionId,
	                                           @Nullable AttendanceState state) {
		return new AttendanceResult(status, sessionId, state);
	}

	public boolean successful() {
		return switch (status) {
			case REGISTERED, UNREGISTERED, ENTERED, ALREADY_ACTIVE,
					RETURNED_TO_VENUE, LEFT, RETURN_DEFERRED -> true;
			default -> false;
		};
	}

	public enum Status {
		REGISTERED,
		UNREGISTERED,
		ENTERED,
		ALREADY_ACTIVE,
		RETURNED_TO_VENUE,
		LEFT,
		RETURN_DEFERRED,
		SESSION_NOT_OPEN,
		SESSION_NOT_RUNNING,
		NOT_REGISTERED,
		NOT_ACTIVE,
		ALREADY_LEFT,
		VENUE_DISABLED,
		VENUE_INVALID,
		PLAYER_OFFLINE,
		PLAYER_BUSY,
		TELEPORT_FAILED,
		RETURN_LOCATION_MISSING,
		PERSISTENCE_FAILED,
		INVALID_SESSION
	}
}
