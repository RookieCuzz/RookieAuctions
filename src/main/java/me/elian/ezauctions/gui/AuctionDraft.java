package me.elian.ezauctions.gui;

import me.elian.ezauctions.helper.ItemHelper;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class AuctionDraft {
	private ItemStack selectedItem;
	private String fingerprint;
	private int amount = 1;
	private boolean sealed;
	private int durationSeconds = 60;
	private long startingPriceMinor = 100L;
	private long incrementMinor = 100L;
	private long autoBuyMinor;
	private boolean autoBuyEnabled;

	public void select(@NotNull ItemStack item) {
		int stackAmount = item.getAmount();
		selectedItem = item.clone();
		selectedItem.setAmount(1);
		fingerprint = ItemHelper.fingerprint(selectedItem);
		amount = Math.max(1, stackAmount);
	}

	/** Removes only the selected item so price/mode settings can be reused deliberately. */
	public void clearSelectedItem() {
		selectedItem = null;
		fingerprint = null;
		amount = 1;
	}

	/** Restores a new submission to safe server defaults. */
	public void reset() {
		clearSelectedItem();
		sealed = false;
		durationSeconds = 60;
		startingPriceMinor = 100L;
		incrementMinor = 100L;
		autoBuyMinor = 0L;
		autoBuyEnabled = false;
	}

	/** Copies only listing settings; the selected item remains unchanged. */
	public void copySettingsFrom(@NotNull AuctionDraft source) {
		sealed = source.sealed;
		durationSeconds = source.durationSeconds;
		startingPriceMinor = source.startingPriceMinor;
		incrementMinor = source.incrementMinor;
		autoBuyMinor = source.autoBuyMinor;
		autoBuyEnabled = source.autoBuyEnabled;
	}

	/** Creates an immutable-enough snapshot for the per-player "reuse last settings" action. */
	public @NotNull AuctionDraft copy() {
		AuctionDraft copy = new AuctionDraft();
		copy.selectedItem = selectedItem == null ? null : selectedItem.clone();
		copy.fingerprint = fingerprint;
		copy.amount = amount;
		copy.copySettingsFrom(this);
		return copy;
	}

	public boolean matches(@Nullable ItemStack item) {
		return item != null && selectedItem != null
				&& fingerprint.equals(ItemHelper.fingerprint(item));
	}

	public @Nullable ItemStack getSelectedItem() {
		return selectedItem == null ? null : selectedItem.clone();
	}

	public @Nullable String getFingerprint() {
		return fingerprint;
	}

	public int getAmount() {
		return amount;
	}

	public boolean isSealed() {
		return sealed;
	}

	public void setSealed(boolean sealed) {
		this.sealed = sealed;
	}

	public int getDurationSeconds() {
		return durationSeconds;
	}

	public void setDurationSeconds(int durationSeconds) {
		this.durationSeconds = durationSeconds;
	}

	public long getStartingPriceMinor() {
		return startingPriceMinor;
	}

	public void setStartingPriceMinor(long startingPriceMinor) {
		this.startingPriceMinor = startingPriceMinor;
	}

	public long getIncrementMinor() {
		return incrementMinor;
	}

	public void setIncrementMinor(long incrementMinor) {
		this.incrementMinor = incrementMinor;
	}

	public long getAutoBuyMinor() {
		return autoBuyEnabled ? autoBuyMinor : 0L;
	}

	public void setAutoBuyMinor(long autoBuyMinor) {
		this.autoBuyMinor = autoBuyMinor;
		this.autoBuyEnabled = autoBuyMinor > 0;
	}

	public boolean isAutoBuyEnabled() {
		return autoBuyEnabled;
	}

	public void setAutoBuyEnabled(boolean autoBuyEnabled) {
		this.autoBuyEnabled = autoBuyEnabled;
	}
}
