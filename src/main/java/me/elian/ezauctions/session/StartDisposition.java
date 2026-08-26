package me.elian.ezauctions.session;

/** Action a scheduler should take for a session that has not started yet. */
public enum StartDisposition {
	NOT_DUE,
	START_NOW,
	DEFER_LOTS
}
