package me.elian.ezauctions.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public final class AuctionGuiHolder implements InventoryHolder {
	private final UUID sessionToken;
	private final GuiPage page;
	private Inventory inventory;
	private UUID auctionId;
	private long revision;

	public AuctionGuiHolder(@NotNull UUID sessionToken, @NotNull GuiPage page,
	                        @Nullable UUID auctionId, long revision) {
		this.sessionToken = sessionToken;
		this.page = page;
		this.auctionId = auctionId;
		this.revision = revision;
	}

	public @NotNull UUID getSessionToken() {
		return sessionToken;
	}

	public @NotNull GuiPage getPage() {
		return page;
	}

	public @Nullable UUID getAuctionId() {
		return auctionId;
	}

	public long getRevision() {
		return revision;
	}

	public void updateState(@Nullable UUID auctionId, long revision) {
		this.auctionId = auctionId;
		this.revision = revision;
	}

	public void attach(@NotNull Inventory inventory) {
		this.inventory = inventory;
	}

	@Override
	public @NotNull Inventory getInventory() {
		return inventory;
	}
}
