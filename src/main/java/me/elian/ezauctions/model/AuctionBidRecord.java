package me.elian.ezauctions.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

@DatabaseTable(tableName = "ezAuctions_BidRecord")
public class AuctionBidRecord {
	@DatabaseField(id = true)
	private String id;

	@DatabaseField(canBeNull = false, index = true)
	private String auctionId;

	@DatabaseField(canBeNull = false, index = true)
	private String bidderId;

	@DatabaseField
	private long amountMinor;

	@DatabaseField
	private long createdAtMillis;

	public AuctionBidRecord() {
	}

	public AuctionBidRecord(@NotNull UUID auctionId, @NotNull UUID bidderId, long amountMinor) {
		this(UUID.randomUUID(), auctionId, bidderId, amountMinor, System.currentTimeMillis());
	}

	public AuctionBidRecord(@NotNull UUID id, @NotNull UUID auctionId, @NotNull UUID bidderId,
	                        long amountMinor, long createdAtMillis) {
		this.id = id.toString();
		this.auctionId = auctionId.toString();
		this.bidderId = bidderId.toString();
		this.amountMinor = amountMinor;
		this.createdAtMillis = createdAtMillis;
	}

	public @NotNull UUID getId() {
		return UUID.fromString(id);
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

	public long getCreatedAtMillis() {
		return createdAtMillis;
	}
}
