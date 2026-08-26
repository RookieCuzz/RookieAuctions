package me.elian.ezauctions.session;

/** Durable participation and return-teleport state for one buyer. */
public enum AttendanceState {
	REGISTERED,
	ENTERING,
	ACTIVE,
	LEFT,
	PENDING_RETURN;

	public boolean isInAuctionMode() {
		return this == ACTIVE;
	}
}
