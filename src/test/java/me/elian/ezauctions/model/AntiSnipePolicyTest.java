package me.elian.ezauctions.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AntiSnipePolicyTest {
	@Test
	void thresholdIsStrictAndTimeIsTheTargetRemainingTime() {
		assertEquals(300, AntiSnipePolicy.targetRemainingSeconds(300, 600, 300, 100, 0, 3));
		assertEquals(100, AntiSnipePolicy.targetRemainingSeconds(99, 600, 300, 100, 0, 3));
	}

	@Test
	void targetNeverExceedsOriginalDurationOrShortensTheAuction() {
		assertEquals(120, AntiSnipePolicy.targetRemainingSeconds(119, 120, 300, 300, 0, 3));
		assertEquals(150, AntiSnipePolicy.targetRemainingSeconds(150, 600, 300, 100, 0, 3));
	}

	@Test
	void firstThreeRunsCanResetButTheFourthCannot() {
		for (int completedRuns = 0; completedRuns < 3; completedRuns++) {
			assertEquals(300,
					AntiSnipePolicy.targetRemainingSeconds(5, 600, 300, 300, completedRuns, 3));
		}
		assertEquals(5, AntiSnipePolicy.targetRemainingSeconds(5, 600, 300, 300, 3, 3));
	}

	@Test
	void disabledRunLimitOrNonPositiveTargetDoesNotReset() {
		assertEquals(5, AntiSnipePolicy.targetRemainingSeconds(5, 600, 300, 300, 0, 0));
		assertEquals(5, AntiSnipePolicy.targetRemainingSeconds(5, 600, 300, 0, 0, 3));
		assertEquals(5, AntiSnipePolicy.targetRemainingSeconds(5, 600, 300, -10, 0, 3));
	}
}
