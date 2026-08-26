package me.elian.ezauctions.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionEtaCalculatorTest {
	private final ScheduleDefinition definition = ScheduleDefinition.defaults();
	private final SessionEtaCalculator calculator = new SessionEtaCalculator(definition);

	@Test
	void fullSessionBaseDurationIsThirtyFourMinutesThirtySeconds() {
		assertEquals(2_070L, calculator.baseDurationSeconds(16));
		assertEquals(0L, calculator.baseDurationSeconds(0));
	}

	@Test
	void runningEtaIncludesCurrentLotAndEveryRemainingTransition() {
		assertEquals(2_070L,
				calculator.remainingSeconds(SessionProgress.running(1, 16, 120)));
		assertEquals(1_940L,
				calculator.remainingSeconds(SessionProgress.running(2, 16, 120)));
		assertEquals(37L,
				calculator.remainingSeconds(SessionProgress.running(16, 16, 37)));
	}

	@Test
	void intermissionEtaDoesNotCountTheCurrentTransitionTwice() {
		assertEquals(1_950L,
				calculator.remainingSeconds(SessionProgress.intermission(1, 16, 10)));
		assertEquals(1_940L,
				calculator.remainingSeconds(SessionProgress.intermission(1, 16, 0)));
		assertEquals(130L,
				calculator.remainingSeconds(SessionProgress.intermission(15, 16, 10)));
	}

	@Test
	void currentCheckpointDrivesDynamicEtaAfterAnAntiSnipeReset() {
		Instant now = Instant.parse("2026-08-26T06:10:00Z");
		SessionProgress reset = SessionProgress.running(2, 4, 30);

		assertEquals(290L, calculator.remainingSeconds(reset));
		assertEquals(Instant.parse("2026-08-26T06:14:50Z"),
				calculator.estimatedEnd(now, reset));
	}

	@Test
	void rejectsImpossibleProgressAndCapacityOverflow() {
		assertThrows(IllegalArgumentException.class,
				() -> SessionProgress.running(17, 16, 120));
		assertThrows(IllegalArgumentException.class,
				() -> SessionProgress.intermission(0, 16, 10));
		assertThrows(IllegalArgumentException.class,
				() -> calculator.baseDurationSeconds(17));
	}
}
