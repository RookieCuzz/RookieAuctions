package me.elian.ezauctions.model;

public enum BidTransactionState {
	PREPARED,
	WITHDRAWING,
	WITHDRAWN,
	COMMITTED,
	FAILED,
	COMPENSATED
}
