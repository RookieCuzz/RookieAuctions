package me.elian.ezauctions.model;

/** Durable phase while a submitted item crosses Vault, inventory and database boundaries. */
public enum SubmissionTransactionState {
	PREPARED,
	FEE_WITHDRAWING,
	FEE_WITHDRAWN,
	ITEM_ESCROWING,
	ITEM_ESCROWED,
	COMMITTED,
	FAILED,
	COMPENSATED;

	/** A conservative recovery refund is required from this phase onward. */
	public boolean feeMayHaveBeenWithdrawn() {
		return this == FEE_WITHDRAWING || this == FEE_WITHDRAWN
				|| this == ITEM_ESCROWING || this == ITEM_ESCROWED;
	}

	/** The item may already have left the inventory from this phase onward. */
	public boolean itemMayBeEscrowed() {
		return this == ITEM_ESCROWING || this == ITEM_ESCROWED;
	}

	public boolean isTerminal() {
		// FAILED still needs the atomic auction/lot close step; it is only terminal after
		// compensation has durably completed.
		return this == COMMITTED || this == COMPENSATED;
	}
}
