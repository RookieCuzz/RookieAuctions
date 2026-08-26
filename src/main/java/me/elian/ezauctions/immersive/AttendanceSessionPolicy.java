package me.elian.ezauctions.immersive;

import org.jetbrains.annotations.NotNull;

/** Synchronous, in-memory session gate supplied by the session orchestrator. */
public interface AttendanceSessionPolicy {
	AttendanceSessionPolicy DENY_ALL = new AttendanceSessionPolicy() {
		@Override
		public boolean canRegister(@NotNull String sessionId) {
			return false;
		}

		@Override
		public boolean isRunning(@NotNull String sessionId) {
			return false;
		}
	};

	boolean canRegister(@NotNull String sessionId);

	boolean isRunning(@NotNull String sessionId);
}
