package me.elian.ezauctions.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Independent submission journal. It deliberately does not add columns to the legacy auction table.
 * The transaction ID is deterministic so retries cannot create two compensation streams.
 */
@DatabaseTable(tableName = "ezAuctions_SubmissionTransaction")
public class AuctionSubmissionTransaction {
	@DatabaseField(id = true)
	private String id;

	@DatabaseField(canBeNull = false, uniqueIndex = true)
	private String auctionId;

	@DatabaseField(canBeNull = false, index = true)
	private String sellerId;

	@DatabaseField(canBeNull = false, index = true)
	private String sessionId;

	@DatabaseField
	private long listingFeeMinor;

	@DatabaseField(canBeNull = false, index = true)
	private String state;

	@DatabaseField
	private String failureReason;

	@DatabaseField
	private long createdAtMillis;

	@DatabaseField
	private long updatedAtMillis;

	public AuctionSubmissionTransaction() {
	}

	public AuctionSubmissionTransaction(@NotNull UUID auctionId, @NotNull UUID sellerId,
	                                    String sessionId, long listingFeeMinor,
	                                    long createdAtMillis) {
		if (listingFeeMinor < 0) {
			throw new IllegalArgumentException("Listing fee must not be negative");
		}
		this.id = idFor(auctionId).toString();
		this.auctionId = auctionId.toString();
		this.sellerId = sellerId.toString();
		this.sessionId = sessionId == null ? "" : sessionId;
		this.listingFeeMinor = listingFeeMinor;
		this.state = SubmissionTransactionState.PREPARED.name();
		this.createdAtMillis = createdAtMillis;
		this.updatedAtMillis = createdAtMillis;
	}

	public static @NotNull UUID idFor(@NotNull UUID auctionId) {
		return UUID.nameUUIDFromBytes(("ezAuctions:submission:" + auctionId)
				.getBytes(StandardCharsets.UTF_8));
	}

	public @NotNull UUID getId() {
		return UUID.fromString(id);
	}

	public @NotNull UUID getAuctionId() {
		return UUID.fromString(auctionId);
	}

	public @NotNull UUID getSellerId() {
		return UUID.fromString(sellerId);
	}

	/** Empty for the legacy immediate queue. */
	public @NotNull String getSessionId() {
		return sessionId;
	}

	public long getListingFeeMinor() {
		return listingFeeMinor;
	}

	public @NotNull SubmissionTransactionState getState() {
		return SubmissionTransactionState.valueOf(state);
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
