package me.elian.ezauctions.session;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable wall-clock schedule and session policy.
 */
public record ScheduleDefinition(
		ZoneId zoneId,
		List<SessionSlot> slots,
		int lockLeadSeconds,
		int futureSubmissionSessionCount,
		int capacity,
		int maxLotsPerSeller,
		int lotDurationSeconds,
		int intermissionSeconds,
		int missedStartGraceSeconds,
		int antiSnipeThresholdSeconds,
		int antiSnipeTargetSeconds,
		int antiSnipeMaxExtensions
) {
	public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

	public ScheduleDefinition {
		Objects.requireNonNull(zoneId, "zoneId");
		Objects.requireNonNull(slots, "slots");
		if (slots.isEmpty()) {
			throw new IllegalArgumentException("At least one session slot is required");
		}

		List<SessionSlot> normalizedSlots = slots.stream()
				.peek(slot -> Objects.requireNonNull(slot, "slots must not contain null"))
				.sorted(Comparator.comparing(SessionSlot::startTime).thenComparing(SessionSlot::id))
				.toList();
		Set<String> ids = new HashSet<>();
		Set<LocalTime> startTimes = new HashSet<>();
		for (SessionSlot slot : normalizedSlots) {
			if (!ids.add(slot.id())) {
				throw new IllegalArgumentException("Duplicate session slot ID: " + slot.id());
			}
			if (!startTimes.add(slot.startTime())) {
				throw new IllegalArgumentException("Two session slots cannot start at the same time");
			}
		}
		slots = List.copyOf(normalizedSlots);

		requirePositive(lockLeadSeconds, "lockLeadSeconds");
		requirePositive(futureSubmissionSessionCount, "futureSubmissionSessionCount");
		requirePositive(capacity, "capacity");
		requirePositive(maxLotsPerSeller, "maxLotsPerSeller");
		if (maxLotsPerSeller > capacity) {
			throw new IllegalArgumentException("maxLotsPerSeller cannot exceed capacity");
		}
		requirePositive(lotDurationSeconds, "lotDurationSeconds");
		requireNonNegative(intermissionSeconds, "intermissionSeconds");
		requireNonNegative(missedStartGraceSeconds, "missedStartGraceSeconds");
		requireNonNegative(antiSnipeThresholdSeconds, "antiSnipeThresholdSeconds");
		requireNonNegative(antiSnipeTargetSeconds, "antiSnipeTargetSeconds");
		requireNonNegative(antiSnipeMaxExtensions, "antiSnipeMaxExtensions");
		if (antiSnipeThresholdSeconds > lotDurationSeconds
				|| antiSnipeTargetSeconds > lotDurationSeconds) {
			throw new IllegalArgumentException("Anti-snipe times cannot exceed the lot duration");
		}
	}

	public static ScheduleDefinition defaults() {
		return new ScheduleDefinition(
				DEFAULT_ZONE,
				List.of(
						new SessionSlot("afternoon", LocalTime.of(14, 0)),
						new SessionSlot("evening", LocalTime.of(20, 0))
				),
				600,
				2,
				16,
				2,
				120,
				10,
				1_800,
				30,
				30,
				3
		);
	}

	public SessionSlot requireSlot(String id) {
		Objects.requireNonNull(id, "id");
		return slots.stream()
				.filter(slot -> slot.id().equals(id))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown session slot: " + id));
	}

	private static void requirePositive(int value, String name) {
		if (value <= 0) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}

	private static void requireNonNegative(int value, String name) {
		if (value < 0) {
			throw new IllegalArgumentException(name + " must not be negative");
		}
	}
}
