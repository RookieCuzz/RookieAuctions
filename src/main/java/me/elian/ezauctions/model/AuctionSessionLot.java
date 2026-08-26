package me.elian.ezauctions.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import me.elian.ezauctions.session.LotState;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** A legacy auction record assigned to one scheduled session. */
@DatabaseTable(tableName = "ezAuctions_SessionLot")
public class AuctionSessionLot {
	@DatabaseField(id = true)
	private String id;

	@DatabaseField(canBeNull = false, index = true)
	private String sessionId;

	@DatabaseField(canBeNull = false, index = true)
	private String auctionId;

	@DatabaseField(canBeNull = false, index = true)
	private String sellerId;

	@DatabaseField(index = true)
	private int sequenceNumber;

	@DatabaseField(canBeNull = false, index = true)
	private String state;

	@DatabaseField
	private long createdAtMillis;

	@DatabaseField
	private long updatedAtMillis;

	public AuctionSessionLot() {
	}

	public AuctionSessionLot(@NotNull String sessionId, @NotNull UUID auctionId,
	                         @NotNull UUID sellerId, int sequenceNumber, long createdAtMillis) {
		if (sessionId.isBlank()) {
			throw new IllegalArgumentException("Session id must not be blank");
		}
		if (sequenceNumber <= 0) {
			throw new IllegalArgumentException("Lot sequence number must be positive");
		}
		this.id = idFor(sessionId, auctionId).toString();
		this.sessionId = sessionId;
		this.auctionId = auctionId.toString();
		this.sellerId = sellerId.toString();
		this.sequenceNumber = sequenceNumber;
		this.state = LotState.RESERVED.name();
		this.createdAtMillis = createdAtMillis;
		this.updatedAtMillis = createdAtMillis;
	}

	public static @NotNull UUID idFor(@NotNull String sessionId, @NotNull UUID auctionId) {
		return UUID.nameUUIDFromBytes(("ezAuctions:session-lot:" + sessionId + ":" + auctionId)
				.getBytes(StandardCharsets.UTF_8));
	}

	public @NotNull UUID getId() {
		return UUID.fromString(id);
	}

	public @NotNull String getSessionId() {
		return sessionId;
	}

	public @NotNull UUID getAuctionId() {
		return UUID.fromString(auctionId);
	}

	public @NotNull UUID getSellerId() {
		return UUID.fromString(sellerId);
	}

	public int getSequenceNumber() {
		return sequenceNumber;
	}

	public @NotNull LotState getState() {
		return LotState.valueOf(state);
	}

	public long getCreatedAtMillis() {
		return createdAtMillis;
	}

	public long getUpdatedAtMillis() {
		return updatedAtMillis;
	}
}
