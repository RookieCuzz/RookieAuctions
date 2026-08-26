package me.elian.ezauctions.session;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Immutable calendar window for one auction session. */
public record PlannedSession(
		String key,
		String slotId,
		LocalDate localDate,
		Instant submissionsLockAt,
		Instant scheduledStart,
		Instant missedStartDeadline
) {
	public PlannedSession {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(slotId, "slotId");
		Objects.requireNonNull(localDate, "localDate");
		Objects.requireNonNull(submissionsLockAt, "submissionsLockAt");
		Objects.requireNonNull(scheduledStart, "scheduledStart");
		Objects.requireNonNull(missedStartDeadline, "missedStartDeadline");
		if (!key.equals(localDate + "/" + slotId)) {
			throw new IllegalArgumentException("Session key must be yyyy-MM-dd/{slotId}");
		}
		if (!submissionsLockAt.isBefore(scheduledStart)) {
			throw new IllegalArgumentException("Submission lock must precede session start");
		}
		if (missedStartDeadline.isBefore(scheduledStart)) {
			throw new IllegalArgumentException("Missed-start deadline cannot precede session start");
		}
	}

	public SessionTiming timingAt(Instant now) {
		Objects.requireNonNull(now, "now");
		if (now.isBefore(submissionsLockAt)) {
			return SessionTiming.OPEN;
		}
		if (now.isBefore(scheduledStart)) {
			return SessionTiming.LOCKED;
		}
		// The product rule says no more than 30 minutes late, so the deadline is inclusive.
		if (!now.isAfter(missedStartDeadline)) {
			return SessionTiming.DUE;
		}
		return SessionTiming.MISSED;
	}

	public boolean acceptsSubmissionsAt(Instant now) {
		return timingAt(now) == SessionTiming.OPEN;
	}

	/** Persistence-oriented alias for {@link #key()}. */
	public String sessionKey() {
		return key;
	}

	/** Persistence-oriented alias for {@link #scheduledStart()}. */
	public Instant start() {
		return scheduledStart;
	}

	/** Persistence-oriented alias for {@link #submissionsLockAt()}. */
	public Instant lockAt() {
		return submissionsLockAt;
	}
}
