package me.elian.ezauctions.session;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable result returned by the transactional lot-reservation boundary.
 * A failed result deliberately has no lot ID.
 */
public record SubmissionResult(
		ReservationStatus status,
		String sessionKey,
		UUID lotId,
		int occupiedSlots,
		int capacity
) {
	public SubmissionResult {
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(sessionKey, "sessionKey");
		if (sessionKey.isBlank()) {
			throw new IllegalArgumentException("sessionKey must not be blank");
		}
		if (occupiedSlots < 0 || capacity < 0 || occupiedSlots > capacity) {
			throw new IllegalArgumentException("Invalid occupied slot/capacity values");
		}
		if (status == ReservationStatus.SUCCESS && lotId == null) {
			throw new IllegalArgumentException("Successful submission requires a lot ID");
		}
		if (status != ReservationStatus.SUCCESS && lotId != null) {
			throw new IllegalArgumentException("Failed submission must not expose a lot ID");
		}
	}

	public static SubmissionResult success(String sessionKey, UUID lotId, int occupiedSlots, int capacity) {
		return new SubmissionResult(ReservationStatus.SUCCESS, sessionKey,
				Objects.requireNonNull(lotId, "lotId"), occupiedSlots, capacity);
	}

	public static SubmissionResult rejected(ReservationStatus status, String sessionKey,
	                                        int occupiedSlots, int capacity) {
		if (status == ReservationStatus.SUCCESS) {
			throw new IllegalArgumentException("Use success() for a successful reservation");
		}
		return new SubmissionResult(status, sessionKey, null, occupiedSlots, capacity);
	}

	public boolean accepted() {
		return status == ReservationStatus.SUCCESS;
	}

	public Optional<UUID> optionalLotId() {
		return Optional.ofNullable(lotId);
	}
}
