package me.elian.ezauctions.session;

/** Sub-phase while the durable session state is {@link SessionState#RUNNING}. */
public enum SessionRunPhase {
	RUNNING,
	INTERMISSION
}
