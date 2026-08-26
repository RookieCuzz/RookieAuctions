package me.elian.ezauctions.session;

/**
 * A wall-clock projection. This is intentionally separate from persisted
 * {@link SessionState}; elapsed time must not silently overwrite durable state.
 */
public enum SessionTiming {
	OPEN,
	LOCKED,
	DUE,
	MISSED
}
