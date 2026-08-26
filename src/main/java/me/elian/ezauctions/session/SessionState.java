package me.elian.ezauctions.session;

/** Durable lifecycle state for an auction session. */
public enum SessionState {
	OPEN,
	LOCKED,
	WAITING,
	BLOCKED,
	RUNNING,
	COMPLETED,
	SKIPPED;

	public boolean acceptsSubmissions() {
		return this == OPEN;
	}

	public boolean isTerminal() {
		return this == COMPLETED || this == SKIPPED;
	}
}
