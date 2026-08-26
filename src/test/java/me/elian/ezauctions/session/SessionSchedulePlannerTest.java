package me.elian.ezauctions.session;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionSchedulePlannerTest {
	private static final ScheduleDefinition DEFAULTS = ScheduleDefinition.defaults();

	@Test
	void defaultsDescribeTwoShanghaiSessionsAndTheFixedPolicy() {
		assertEquals("Asia/Shanghai", DEFAULTS.zoneId().getId());
		assertEquals(List.of("afternoon", "evening"),
				DEFAULTS.slots().stream().map(SessionSlot::id).toList());
		assertEquals(List.of("14:00", "20:00"),
				DEFAULTS.slots().stream().map(slot -> slot.startTime().toString()).toList());
		assertEquals(600, DEFAULTS.lockLeadSeconds());
		assertEquals(2, DEFAULTS.futureSubmissionSessionCount());
		assertEquals(16, DEFAULTS.capacity());
		assertEquals(2, DEFAULTS.maxLotsPerSeller());
		assertEquals(120, DEFAULTS.lotDurationSeconds());
		assertEquals(10, DEFAULTS.intermissionSeconds());
		assertEquals(1_800, DEFAULTS.missedStartGraceSeconds());
		assertEquals(30, DEFAULTS.antiSnipeThresholdSeconds());
		assertEquals(30, DEFAULTS.antiSnipeTargetSeconds());
		assertEquals(3, DEFAULTS.antiSnipeMaxExtensions());
	}

	@Test
	void stableKeyAndWindowUseShanghaiNaturalDate() {
		SessionSchedulePlanner planner = new SessionSchedulePlanner(DEFAULTS);
		PlannedSession session = planner.plan(LocalDate.of(2026, 8, 26), "afternoon");

		assertEquals("2026-08-26/afternoon", session.key());
		assertEquals(Instant.parse("2026-08-26T05:50:00Z"), session.submissionsLockAt());
		assertEquals(Instant.parse("2026-08-26T06:00:00Z"), session.scheduledStart());
		assertEquals(Instant.parse("2026-08-26T06:30:00Z"), session.missedStartDeadline());
	}

	@Test
	void fixedClockReturnsExactlyTwoSessionsThatStillAcceptSubmissions() {
		Clock beforeAfternoonLock = Clock.fixed(
				Instant.parse("2026-08-26T05:49:59Z"), ZoneOffset.UTC);
		SessionSchedulePlanner planner = new SessionSchedulePlanner(DEFAULTS, beforeAfternoonLock);

		assertEquals(List.of("2026-08-26/afternoon", "2026-08-26/evening"),
				planner.nextSubmissionSessions().stream().map(PlannedSession::key).toList());

		List<PlannedSession> atLock = planner.nextSubmissionSessions(
				Instant.parse("2026-08-26T05:50:00Z"));
		assertEquals(List.of("2026-08-26/evening", "2026-08-27/afternoon"),
				atLock.stream().map(PlannedSession::key).toList());
		assertThrows(UnsupportedOperationException.class, () -> atLock.add(atLock.get(0)));
	}

	@Test
	void afterBothCutoffsProjectionCrossesTheNaturalDayBoundary() {
		SessionSchedulePlanner planner = new SessionSchedulePlanner(DEFAULTS);

		List<String> keys = planner.nextSubmissionSessions(
						Instant.parse("2026-08-26T12:00:00Z")) // 20:00 in Shanghai
				.stream().map(PlannedSession::key).toList();

		assertEquals(List.of("2026-08-27/afternoon", "2026-08-27/evening"), keys);
	}

	@Test
	void lockStartAndGraceBoundariesAreExplicitAndGraceDeadlineIsInclusive() {
		SessionSchedulePlanner planner = new SessionSchedulePlanner(DEFAULTS);
		PlannedSession session = planner.plan(LocalDate.of(2026, 8, 26), "afternoon");

		assertEquals(SessionTiming.OPEN,
				session.timingAt(Instant.parse("2026-08-26T05:49:59Z")));
		assertEquals(SessionTiming.LOCKED,
				session.timingAt(Instant.parse("2026-08-26T05:50:00Z")));
		assertEquals(SessionTiming.DUE,
				session.timingAt(Instant.parse("2026-08-26T06:00:00Z")));
		assertEquals(StartDisposition.START_NOW,
				planner.startDisposition(session, Instant.parse("2026-08-26T06:30:00Z")));
		assertEquals(StartDisposition.DEFER_LOTS,
				planner.startDisposition(session, Instant.parse("2026-08-26T06:30:00.001Z")));
	}
}
