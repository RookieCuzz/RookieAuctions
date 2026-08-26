package me.elian.ezauctions.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Durable journal entry bridging a Vault withdrawal and an accepted bid. */
@DatabaseTable(tableName = "ezAuctions_BidTransaction")
public class AuctionBidTransaction {
	@DatabaseField(id = true)
	private String id;

	@DatabaseField(canBeNull = false, index = true)
	private String sessionId;

	@DatabaseField(canBeNull = false, index = true)
	private String lotId;

	@DatabaseField(canBeNull = false, index = true)
	private String auctionId;

	@DatabaseField(canBeNull = false, index = true)
	private String bidderId;

	@DatabaseField
	private long amountMinor;

	@DatabaseField(canBeNull = false, index = true)
	private String state;

	@DatabaseField
	private String failureReason;

	@DatabaseField
	private long createdAtMillis;

	@DatabaseField
	private long updatedAtMillis;

	public AuctionBidTransaction() {
	}

	public AuctionBidTransaction(@NotNull UUID id, @NotNull String sessionId,
	                             @NotNull UUID lotId, @NotNull UUID auctionId,
	                             @NotNull UUID bidderId, long amountMinor, long createdAtMillis) {
		if (sessionId.isBlank()) {
			throw new IllegalArgumentException("Session id must not be blank");
		}
		if (amountMinor <= 0) {
			throw new IllegalArgumentException("Bid transaction amount must be positive");
		}
		this.id = id.toString();
		this.sessionId = sessionId;
		this.lotId = lotId.toString();
		this.auctionId = auctionId.toString();
		this.bidderId = bidderId.toString();
		this.amountMinor = amountMinor;
		this.state = BidTransactionState.PREPARED.name();
		this.createdAtMillis = createdAtMillis;
		this.updatedAtMillis = createdAtMillis;
	}

	public @NotNull UUID getId() {
		return UUID.fromString(id);
	}

	public @NotNull String getSessionId() {
		return sessionId;
	}

	public @NotNull UUID getLotId() {
		return UUID.fromString(lotId);
	}

	public @NotNull UUID getAuctionId() {
		return UUID.fromString(auctionId);
	}

	public @NotNull UUID getBidderId() {
		return UUID.fromString(bidderId);
	}

	public long getAmountMinor() {
		return amountMinor;
	}

	public @NotNull BidTransactionState getState() {
		return BidTransactionState.valueOf(state);
	}

	public @NotNull String getFailureReason() {
		return failureReason == null ? "" : failureReason;
	}

	public long getCreatedAtMillis() {
		return createdAtMillis;
	}

	public long getUpdatedAtMillis() {
		return updatedAtMillis;
	}
}
