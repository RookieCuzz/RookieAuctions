package me.elian.ezauctions.session;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Public, immutable session summary suitable for GUIs and placeholders. */
public record AuctionSessionView(
		String sessionKey,
		String slotId,
		Instant scheduledStart,
		Instant submissionsLockAt,
		SessionState state,
		int lotCount,
		int capacity,
		Optional<SessionProgress> progress,
		OptionalLong estimatedRemainingSeconds
) {
	public AuctionSessionView {
		Objects.requireNonNull(sessionKey, "sessionKey");
		Objects.requireNonNull(slotId, "slotId");
		Objects.requireNonNull(scheduledStart, "scheduledStart");
		Objects.requireNonNull(submissionsLockAt, "submissionsLockAt");
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(progress, "progress");
		Objects.requireNonNull(estimatedRemainingSeconds, "estimatedRemainingSeconds");
		if (sessionKey.isBlank() || slotId.isBlank()) {
			throw new IllegalArgumentException("Session identifiers must not be blank");
		}
		if (!submissionsLockAt.isBefore(scheduledStart)) {
			throw new IllegalArgumentException("Submission lock must precede session start");
		}
		if (lotCount < 0 || capacity <= 0 || lotCount > capacity) {
			throw new IllegalArgumentException("Invalid lot count/capacity values");
		}
		if (state == SessionState.RUNNING && progress.isEmpty()) {
			throw new IllegalArgumentException("A running session requires progress");
		}
		if (state != SessionState.RUNNING && progress.isPresent()) {
			throw new IllegalArgumentException("Only a running session can expose progress");
		}
		if (progress.isPresent() != estimatedRemainingSeconds.isPresent()) {
			throw new IllegalArgumentException("Progress and ETA must be present together");
		}
		if (progress.isPresent() && progress.get().totalLots() != lotCount) {
			throw new IllegalArgumentException("Progress total must equal the session lot count");
		}
		if (estimatedRemainingSeconds.isPresent() && estimatedRemainingSeconds.getAsLong() < 0) {
			throw new IllegalArgumentException("ETA must not be negative");
		}
	}

	public int remainingCapacity() {
		return capacity - lotCount;
	}

	public OptionalInt activeLotNumber() {
		return progress.isPresent() ? progress.get().activeLotNumber() : OptionalInt.empty();
	}
}
