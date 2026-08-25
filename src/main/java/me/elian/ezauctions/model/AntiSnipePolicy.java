package me.elian.ezauctions.model;

final class AntiSnipePolicy {
	private AntiSnipePolicy() {
	}

	static int targetRemainingSeconds(int remainingSeconds, int originalDurationSeconds,
	                                  int triggerThresholdSeconds, int configuredTargetSeconds,
	                                  int completedRuns, int maximumRuns) {
		if (maximumRuns <= 0 || completedRuns >= maximumRuns) {
			return remainingSeconds;
		}
		if (triggerThresholdSeconds <= 0 || remainingSeconds >= triggerThresholdSeconds) {
			return remainingSeconds;
		}

		int target = Math.min(originalDurationSeconds, configuredTargetSeconds);
		return target > remainingSeconds ? target : remainingSeconds;
	}
}
