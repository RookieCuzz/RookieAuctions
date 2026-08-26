package me.elian.ezauctions.session;

/** Durable lifecycle state for a lot assigned to a scheduled session. */
public enum LotState {
	RESERVED,
	QUEUED,
	LOCKED,
	ACTIVE,
	SETTLED,
	CANCELLED,
	DEFERRED;

	public boolean isTerminal() {
		// DEFERRED is terminal for this session-lot relation; the auction itself
		// continues through a newly reserved relation in a later session.
		return this == SETTLED || this == CANCELLED || this == DEFERRED;
	}
}
