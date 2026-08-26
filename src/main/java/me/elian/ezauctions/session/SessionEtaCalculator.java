package me.elian.ezauctions.session;

import java.time.Instant;
import java.util.Objects;

/** Calculates the dynamic end time, including all remaining lot transitions. */
public final class SessionEtaCalculator {
	private final ScheduleDefinition definition;

	public SessionEtaCalculator(ScheduleDefinition definition) {
		this.definition = Objects.requireNonNull(definition, "definition");
	}

	public long baseDurationSeconds(int lotCount) {
		if (lotCount < 0 || lotCount > definition.capacity()) {
			throw new IllegalArgumentException("lotCount is outside session capacity");
		}
		if (lotCount == 0) {
			return 0L;
		}
		return (long) lotCount * definition.lotDurationSeconds()
				+ (long) (lotCount - 1) * definition.intermissionSeconds();
	}

	public long remainingSeconds(SessionProgress progress) {
		Objects.requireNonNull(progress, "progress");
		if (progress.totalLots() > definition.capacity()) {
			throw new IllegalArgumentException("Progress exceeds session capacity");
		}

		int futureLots;
		long futureIntermissions;
		if (progress.phase() == SessionRunPhase.RUNNING) {
			futureLots = progress.totalLots() - progress.completedLots() - 1;
			// One transition precedes each future lot.
			futureIntermissions = futureLots;
		} else {
			futureLots = progress.totalLots() - progress.completedLots();
			// The current phase already represents the first transition.
			futureIntermissions = Math.max(0, futureLots - 1L);
		}

		return progress.phaseRemainingSeconds()
				+ (long) futureLots * definition.lotDurationSeconds()
				+ futureIntermissions * definition.intermissionSeconds();
	}

	public Instant estimatedEnd(Instant now, SessionProgress progress) {
		Objects.requireNonNull(now, "now");
		return now.plusSeconds(remainingSeconds(progress));
	}
}
