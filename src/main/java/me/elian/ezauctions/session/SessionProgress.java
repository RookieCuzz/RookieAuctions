package me.elian.ezauctions.session;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Immutable progress checkpoint used to calculate a dynamic session ETA.
 * completedLots excludes the currently active lot in RUNNING phase.
 */
public record SessionProgress(
		SessionRunPhase phase,
		int totalLots,
		int completedLots,
		int phaseRemainingSeconds
) {
	public SessionProgress {
		Objects.requireNonNull(phase, "phase");
		if (totalLots <= 0) {
			throw new IllegalArgumentException("totalLots must be positive");
		}
		if (completedLots < 0 || completedLots >= totalLots) {
			throw new IllegalArgumentException("completedLots must leave work remaining");
		}
		if (phaseRemainingSeconds < 0) {
			throw new IllegalArgumentException("phaseRemainingSeconds must not be negative");
		}
		if (phase == SessionRunPhase.INTERMISSION && completedLots == 0) {
			throw new IllegalArgumentException("Intermission requires at least one completed lot");
		}
	}

	public static SessionProgress running(int currentLotNumber, int totalLots, int lotRemainingSeconds) {
		if (currentLotNumber <= 0) {
			throw new IllegalArgumentException("currentLotNumber must be positive");
		}
		return new SessionProgress(SessionRunPhase.RUNNING, totalLots,
				currentLotNumber - 1, lotRemainingSeconds);
	}

	public static SessionProgress intermission(int completedLots, int totalLots,
	                                           int intermissionRemainingSeconds) {
		return new SessionProgress(SessionRunPhase.INTERMISSION, totalLots,
				completedLots, intermissionRemainingSeconds);
	}

	public OptionalInt activeLotNumber() {
		return phase == SessionRunPhase.RUNNING
				? OptionalInt.of(completedLots + 1)
				: OptionalInt.empty();
	}

	/** Active lot while bidding, or the upcoming lot while changing displays. */
	public int currentOrUpcomingLotNumber() {
		return completedLots + 1;
	}

	public OptionalInt upcomingLotNumber() {
		return phase == SessionRunPhase.INTERMISSION
				? OptionalInt.of(completedLots + 1)
				: OptionalInt.empty();
	}
}
