package me.elian.ezauctions.session;

/** Outcome of atomically reserving capacity for a submitted lot. */
public enum ReservationStatus {
	SUCCESS,
	FULL,
	SELLER_LIMIT,
	SESSION_CLOSED,
	NOT_FOUND
}
