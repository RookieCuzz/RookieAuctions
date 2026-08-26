package me.elian.ezauctions.session;

import java.util.Objects;
import java.util.UUID;

/** Result of atomically withdrawing an escrowed lot before its session locks. */
public record WithdrawalResult(Status status, String sessionKey, UUID auctionId) {
	public WithdrawalResult {
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(sessionKey, "sessionKey");
		Objects.requireNonNull(auctionId, "auctionId");
		if (sessionKey.isBlank()) {
			throw new IllegalArgumentException("sessionKey must not be blank");
		}
	}

	public boolean withdrawn() {
		return status == Status.SUCCESS;
	}

	public enum Status {
		SUCCESS,
		NOT_FOUND,
		NOT_OWNER,
		SESSION_CLOSED,
		PERSISTENCE_FAILED
	}
}
