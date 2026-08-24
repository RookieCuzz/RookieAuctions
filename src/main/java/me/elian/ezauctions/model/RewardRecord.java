package me.elian.ezauctions.model;

import com.j256.ormlite.field.DataType;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import me.elian.ezauctions.helper.ItemHelper;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@DatabaseTable(tableName = "ezAuctions_Reward")
public class RewardRecord {
	@DatabaseField(id = true)
	private String id;

	@DatabaseField(canBeNull = false, index = true)
	private String ownerId;

	@DatabaseField(index = true)
	private String auctionId;

	@DatabaseField(canBeNull = false, index = true)
	private String kind;

	@DatabaseField(canBeNull = false, index = true)
	private String state;

	@DatabaseField(dataType = DataType.BYTE_ARRAY)
	private byte[] serializedItemBytes;

	@DatabaseField
	private int amount;

	@DatabaseField
	private long moneyMinor;

	@DatabaseField
	private String world;

	@DatabaseField
	private long createdAtMillis;

	@DatabaseField
	private long claimedAtMillis;

	public RewardRecord() {
	}

	public static @NotNull RewardRecord item(@NotNull UUID ownerId, @Nullable UUID auctionId,
	                                         @NotNull ItemStack item, int amount, @NotNull String world) {
		RewardRecord reward = base(ownerId, auctionId, RewardKind.ITEM);
		reward.serializedItemBytes = ItemHelper.serialize(item);
		reward.amount = amount;
		reward.world = world;
		return reward;
	}

	public static @NotNull RewardRecord legacyItem(@NotNull UUID ownerId, int legacyId,
	                                               @NotNull ItemStack item, int amount,
	                                               @NotNull String world) {
		RewardRecord reward = item(ownerId, null, item, amount, world);
		reward.id = UUID.nameUUIDFromBytes(("ezAuctions:legacy:" + ownerId + ":" + legacyId)
				.getBytes(StandardCharsets.UTF_8)).toString();
		return reward;
	}

	public static @NotNull RewardRecord money(@NotNull UUID ownerId, @Nullable UUID auctionId,
	                                          @NotNull RewardKind kind, long moneyMinor) {
		if (kind == RewardKind.ITEM) {
			throw new IllegalArgumentException("Use item() for item rewards");
		}
		if (moneyMinor <= 0) {
			throw new IllegalArgumentException("Money reward must be positive");
		}
		RewardRecord reward = base(ownerId, auctionId, kind);
		reward.moneyMinor = moneyMinor;
		reward.world = "";
		return reward;
	}

	private static @NotNull RewardRecord base(@NotNull UUID ownerId, @Nullable UUID auctionId,
	                                          @NotNull RewardKind kind) {
		RewardRecord reward = new RewardRecord();
		reward.id = auctionId == null
				? UUID.randomUUID().toString()
				: UUID.nameUUIDFromBytes(("ezAuctions:reward:" + auctionId + ":" + ownerId + ":" + kind)
				.getBytes(StandardCharsets.UTF_8)).toString();
		reward.ownerId = ownerId.toString();
		reward.auctionId = auctionId == null ? null : auctionId.toString();
		reward.kind = kind.name();
		reward.state = RewardState.PENDING.name();
		reward.createdAtMillis = System.currentTimeMillis();
		return reward;
	}

	public @NotNull UUID getId() {
		return UUID.fromString(id);
	}

	public @NotNull UUID getOwnerId() {
		return UUID.fromString(ownerId);
	}

	public @Nullable UUID getAuctionId() {
		return auctionId == null || auctionId.isBlank() ? null : UUID.fromString(auctionId);
	}

	public @NotNull RewardKind getKind() {
		return RewardKind.valueOf(kind);
	}

	public @NotNull RewardState getState() {
		return RewardState.valueOf(state);
	}

	public void setState(@NotNull RewardState state) {
		this.state = state.name();
		if (state == RewardState.DONE) {
			claimedAtMillis = System.currentTimeMillis();
		}
	}

	public @Nullable ItemStack getItem() throws IOException {
		return serializedItemBytes == null ? null : ItemHelper.deserialize(serializedItemBytes);
	}

	public int getAmount() {
		return amount;
	}

	public long getMoneyMinor() {
		return moneyMinor;
	}

	public @NotNull String getWorld() {
		return world == null ? "" : world;
	}

	public long getCreatedAtMillis() {
		return createdAtMillis;
	}

	public long getClaimedAtMillis() {
		return claimedAtMillis;
	}
}
