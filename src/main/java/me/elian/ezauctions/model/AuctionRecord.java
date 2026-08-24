package me.elian.ezauctions.model;

import com.j256.ormlite.field.DataType;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import me.elian.ezauctions.helper.ItemHelper;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.UUID;

@DatabaseTable(tableName = "ezAuctions_AuctionRecord")
public class AuctionRecord {
	@DatabaseField(id = true)
	private String id;

	@DatabaseField(canBeNull = false, index = true)
	private String auctioneerId;

	@DatabaseField(dataType = DataType.BYTE_ARRAY, canBeNull = false)
	private byte[] serializedItemBytes;

	@DatabaseField
	private int amount;

	@DatabaseField
	private boolean sealed;

	@DatabaseField(canBeNull = false)
	private String world;

	@DatabaseField
	private long startingPriceMinor;

	@DatabaseField
	private long incrementMinor;

	@DatabaseField
	private long autoBuyMinor;

	@DatabaseField
	private int durationSeconds;

	@DatabaseField(canBeNull = false, index = true)
	private String status;

	@DatabaseField
	private long createdAtMillis;

	@DatabaseField
	private long startedAtMillis;

	@DatabaseField
	private long completedAtMillis;

	@DatabaseField
	private String winnerId;

	@DatabaseField
	private long finalPriceMinor;

	@DatabaseField
	private long payoutMinor;

	@DatabaseField
	private long taxMinor;

	@DatabaseField
	private String itemDestination;

	@DatabaseField
	private String refundStatus;

	public AuctionRecord() {
	}

	public AuctionRecord(@NotNull UUID id, @NotNull UUID auctioneerId, @NotNull ItemStack item, int amount,
	                     boolean sealed, @NotNull String world, long startingPriceMinor, long incrementMinor,
	                     long autoBuyMinor, int durationSeconds) {
		this.id = id.toString();
		this.auctioneerId = auctioneerId.toString();
		this.serializedItemBytes = ItemHelper.serialize(item);
		this.amount = amount;
		this.sealed = sealed;
		this.world = world;
		this.startingPriceMinor = startingPriceMinor;
		this.incrementMinor = incrementMinor;
		this.autoBuyMinor = autoBuyMinor;
		this.durationSeconds = durationSeconds;
		this.status = AuctionRecordStatus.PREPARING.name();
		this.createdAtMillis = System.currentTimeMillis();
		this.itemDestination = "ESCROW";
		this.refundStatus = "NONE";
	}

	public @NotNull UUID getId() {
		return UUID.fromString(id);
	}

	public @NotNull UUID getAuctioneerId() {
		return UUID.fromString(auctioneerId);
	}

	public @NotNull ItemStack getItem() throws IOException {
		return ItemHelper.deserialize(serializedItemBytes);
	}

	public int getAmount() {
		return amount;
	}

	public boolean isSealed() {
		return sealed;
	}

	public @NotNull String getWorld() {
		return world;
	}

	public long getStartingPriceMinor() {
		return startingPriceMinor;
	}

	public long getIncrementMinor() {
		return incrementMinor;
	}

	public long getAutoBuyMinor() {
		return autoBuyMinor;
	}

	public int getDurationSeconds() {
		return durationSeconds;
	}

	public @NotNull AuctionRecordStatus getStatus() {
		return AuctionRecordStatus.valueOf(status);
	}

	public void setStatus(@NotNull AuctionRecordStatus status) {
		this.status = status.name();
		if (status == AuctionRecordStatus.ACTIVE) {
			startedAtMillis = System.currentTimeMillis();
		}
		if (status == AuctionRecordStatus.COMPLETED || status == AuctionRecordStatus.CANCELLED) {
			completedAtMillis = System.currentTimeMillis();
		}
	}

	public long getCreatedAtMillis() {
		return createdAtMillis;
	}

	public long getStartedAtMillis() {
		return startedAtMillis;
	}

	public long getCompletedAtMillis() {
		return completedAtMillis;
	}

	public @Nullable UUID getWinnerId() {
		return winnerId == null || winnerId.isBlank() ? null : UUID.fromString(winnerId);
	}

	public long getFinalPriceMinor() {
		return finalPriceMinor;
	}

	public long getPayoutMinor() {
		return payoutMinor;
	}

	public long getTaxMinor() {
		return taxMinor;
	}

	public @NotNull String getItemDestination() {
		return itemDestination == null ? "" : itemDestination;
	}

	public @NotNull String getRefundStatus() {
		return refundStatus == null ? "" : refundStatus;
	}

	public void complete(@Nullable UUID winnerId, long finalPriceMinor, long payoutMinor, long taxMinor,
	                     @NotNull String itemDestination, @NotNull String refundStatus) {
		this.winnerId = winnerId == null ? null : winnerId.toString();
		this.finalPriceMinor = finalPriceMinor;
		this.payoutMinor = payoutMinor;
		this.taxMinor = taxMinor;
		this.itemDestination = itemDestination;
		this.refundStatus = refundStatus;
		setStatus(AuctionRecordStatus.COMPLETED);
	}

	public void cancel(@NotNull String itemDestination, @NotNull String refundStatus) {
		this.itemDestination = itemDestination;
		this.refundStatus = refundStatus;
		setStatus(AuctionRecordStatus.CANCELLED);
	}
}
