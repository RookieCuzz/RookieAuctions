package me.elian.ezauctions.immersive;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** Fully resolved venue state. Returned only after validation succeeds. */
public final class VenueLayout {
	private final Location buyerSpawn;
	private final Location itemDisplay;
	private final Location infoDisplay;
	private final InclusiveCuboid bounds;
	private final float itemScale;

	public VenueLayout(@NotNull Location buyerSpawn, @NotNull Location itemDisplay,
	                   @NotNull Location infoDisplay, @NotNull InclusiveCuboid bounds,
	                   float itemScale) {
		this.buyerSpawn = Objects.requireNonNull(buyerSpawn, "buyerSpawn").clone();
		this.itemDisplay = Objects.requireNonNull(itemDisplay, "itemDisplay").clone();
		this.infoDisplay = Objects.requireNonNull(infoDisplay, "infoDisplay").clone();
		this.bounds = Objects.requireNonNull(bounds, "bounds");
		if (!Float.isFinite(itemScale) || itemScale <= 0F) {
			throw new IllegalArgumentException("Item display scale must be positive and finite");
		}
		this.itemScale = itemScale;
	}

	public @NotNull Location buyerSpawn() {
		return buyerSpawn.clone();
	}

	public @NotNull Location itemDisplay() {
		return itemDisplay.clone();
	}

	public @NotNull Location infoDisplay() {
		return infoDisplay.clone();
	}

	public @NotNull InclusiveCuboid bounds() {
		return bounds;
	}

	public float itemScale() {
		return itemScale;
	}
}
