package me.elian.ezauctions.session;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure calendar planner for stable, twice-daily (or configured) auction sessions.
 */
public final class SessionSchedulePlanner {
	private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

	private final ScheduleDefinition definition;
	private final Clock clock;

	public SessionSchedulePlanner(ScheduleDefinition definition) {
		this(definition, Clock.system(definition.zoneId()));
	}

	public SessionSchedulePlanner(ScheduleDefinition definition, Clock clock) {
		this.definition = Objects.requireNonNull(definition, "definition");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public ScheduleDefinition definition() {
		return definition;
	}

	public PlannedSession plan(LocalDate date, String slotId) {
		Objects.requireNonNull(date, "date");
		SessionSlot slot = definition.requireSlot(slotId);
		ZonedDateTime localStart = ZonedDateTime.of(date, slot.startTime(), definition.zoneId());
		Instant start = localStart.toInstant();
		return new PlannedSession(
				sessionKey(date, slot.id()),
				slot.id(),
				date,
				start.minusSeconds(definition.lockLeadSeconds()),
				start,
				start.plusSeconds(definition.missedStartGraceSeconds())
		);
	}

	public List<PlannedSession> sessionsOn(LocalDate date) {
		Objects.requireNonNull(date, "date");
		return definition.slots().stream().map(slot -> plan(date, slot.id())).toList();
	}

	/** Returns the configured number of chronological sessions that still accept submissions. */
	public List<PlannedSession> nextSubmissionSessions() {
		return nextSubmissionSessions(clock.instant());
	}

	public List<PlannedSession> nextSubmissionSessions(Instant now) {
		return nextSubmissionSessions(now, definition.futureSubmissionSessionCount());
	}

	/**
	 * Returns an explicit number of chronological open windows. The overload is
	 * used by rollover recovery, which may need to search beyond the normal GUI
	 * horizon without changing what buyers see.
	 */
	public List<PlannedSession> nextSubmissionSessions(Instant now, int requested) {
		Objects.requireNonNull(now, "now");
		if (requested <= 0) {
			throw new IllegalArgumentException("requested must be positive");
		}
		List<PlannedSession> result = new ArrayList<>(requested);
		LocalDate date = now.atZone(definition.zoneId()).toLocalDate();

		// At least one slot exists, so this always terminates after a small number of days.
		while (result.size() < requested) {
			for (PlannedSession session : sessionsOn(date)) {
				if (session.acceptsSubmissionsAt(now)) {
					result.add(session);
					if (result.size() == requested) {
						return List.copyOf(result);
					}
				}
			}
			date = date.plusDays(1);
		}
		return List.copyOf(result);
	}

	public StartDisposition startDisposition(PlannedSession session) {
		return startDisposition(session, clock.instant());
	}

	public StartDisposition startDisposition(PlannedSession session, Instant now) {
		Objects.requireNonNull(session, "session");
		return switch (session.timingAt(now)) {
			case OPEN, LOCKED -> StartDisposition.NOT_DUE;
			case DUE -> StartDisposition.START_NOW;
			case MISSED -> StartDisposition.DEFER_LOTS;
		};
	}

	public static String sessionKey(LocalDate date, String slotId) {
		Objects.requireNonNull(date, "date");
		Objects.requireNonNull(slotId, "slotId");
		// Reuse SessionSlot's validation so keys cannot contain path separators or localized labels.
		new SessionSlot(slotId, java.time.LocalTime.MIDNIGHT);
		return KEY_DATE.format(date) + "/" + slotId;
	}
}
