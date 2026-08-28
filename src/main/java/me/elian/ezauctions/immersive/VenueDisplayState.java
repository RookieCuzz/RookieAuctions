package me.elian.ezauctions.immersive;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** Immutable public projection for the in-world item and information displays. */
public record VenueDisplayState(
		@NotNull VenueDisplayPhase phase,
		@NotNull String sessionLabel,
		int lotNumber,
		int lotCount,
		@NotNull ItemStack item,
		@NotNull String itemName,
		int itemAmount,
		int lotRemainingSeconds,
		@NotNull String currentBidText,
		boolean sealed,
		int sessionRemainingSeconds,
		int phaseRemainingSeconds,
		@NotNull String nextSessionText,
		int submittedLots,
		int capacity
) {
	public VenueDisplayState {
		Objects.requireNonNull(phase, "phase");
		Objects.requireNonNull(sessionLabel, "sessionLabel");
		item = Objects.requireNonNull(item, "item").clone();
		Objects.requireNonNull(itemName, "itemName");
		Objects.requireNonNull(currentBidText, "currentBidText");
		Objects.requireNonNull(nextSessionText, "nextSessionText");
		if (lotNumber < 0 || lotCount < 0 || lotNumber > lotCount
				|| submittedLots < 0 || capacity < 0 || submittedLots > capacity) {
			throw new IllegalArgumentException("Invalid venue display counts");
		}
		if (itemAmount < 0 || (phase == VenueDisplayPhase.LOT && itemAmount <= 0)) {
			throw new IllegalArgumentException("Invalid venue item amount");
		}
	}

	@Override
	public @NotNull ItemStack item() {
		return item.clone();
	}

	public static @NotNull VenueDisplayState lot(@NotNull String sessionLabel, int lotNumber,
	                                              int lotCount, @NotNull ItemStack item,
	                                              @NotNull String itemName, int lotRemainingSeconds,
	                                              @NotNull String currentBidText, boolean sealed,
	                                              int sessionRemainingSeconds) {
		return lot(sessionLabel, lotNumber, lotCount, item, item.getAmount(), itemName,
				lotRemainingSeconds, currentBidText, sealed, sessionRemainingSeconds);
	}

	public static @NotNull VenueDisplayState lot(@NotNull String sessionLabel, int lotNumber,
	                                              int lotCount, @NotNull ItemStack item, int itemAmount,
	                                              @NotNull String itemName, int lotRemainingSeconds,
	                                              @NotNull String currentBidText, boolean sealed,
	                                              int sessionRemainingSeconds) {
		return new VenueDisplayState(VenueDisplayPhase.LOT, sessionLabel, lotNumber, lotCount,
				item, itemName, itemAmount, lotRemainingSeconds, currentBidText, sealed,
				sessionRemainingSeconds, 0, "", 0, 0);
	}

	public static @NotNull VenueDisplayState intermission(@NotNull String sessionLabel,
	                                                       int completedLots, int lotCount,
	                                                       int nextLotSeconds,
	                                                       int sessionRemainingSeconds) {
		return new VenueDisplayState(VenueDisplayPhase.INTERMISSION, sessionLabel,
				completedLots, lotCount, new ItemStack(Material.AIR), "", 0, -1, "",
				false, sessionRemainingSeconds, nextLotSeconds, "", 0, 0);
	}

	public static @NotNull VenueDisplayState idle(@NotNull String nextSessionText,
	                                              int submittedLots, int capacity) {
		return new VenueDisplayState(VenueDisplayPhase.IDLE, "", 0, 0,
				new ItemStack(Material.AIR), "", 0, -1, "", false,
				-1, 0, nextSessionText, submittedLots, capacity);
	}

	public static @NotNull VenueDisplayState blocked(@NotNull String sessionLabel) {
		return new VenueDisplayState(VenueDisplayPhase.BLOCKED, sessionLabel, 0, 0,
				new ItemStack(Material.AIR), "", 0, -1, "", false,
				-1, 0, "", 0, 0);
	}
}
