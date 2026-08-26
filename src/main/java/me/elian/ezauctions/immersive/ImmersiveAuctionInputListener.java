package me.elian.ezauctions.immersive;

import me.elian.ezauctions.scheduler.TaskScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Converts the swap-hands key (default F) into the immersive bid panel only in ACTIVE mode. */
public final class ImmersiveAuctionInputListener implements Listener {
	private final Plugin plugin;
	private final TaskScheduler scheduler;
	private final AuctionModeAccess auctionMode;
	private final BidPanelOpener bidPanelOpener;
	private final Set<UUID> pendingOpen = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean started = new AtomicBoolean();

	public ImmersiveAuctionInputListener(@NotNull Plugin plugin, @NotNull TaskScheduler scheduler,
	                                     @NotNull AuctionModeAccess auctionMode,
	                                     @NotNull BidPanelOpener bidPanelOpener) {
		this.plugin = plugin;
		this.scheduler = scheduler;
		this.auctionMode = auctionMode;
		this.bidPanelOpener = bidPanelOpener;
	}

	public void start() {
		if (started.compareAndSet(false, true)) {
			plugin.getServer().getPluginManager().registerEvents(this, plugin);
		}
	}

	public void shutdown() {
		if (started.compareAndSet(true, false)) {
			HandlerList.unregisterAll(this);
			pendingOpen.clear();
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onSwapHands(PlayerSwapHandItemsEvent event) {
		Player player = event.getPlayer();
		UUID playerId = player.getUniqueId();
		if (!auctionMode.isActive(playerId)) {
			return;
		}
		// A chest/anvil/etc. keeps vanilla/container semantics and is never hijacked.
		if (player.getOpenInventory().getType() != InventoryType.CRAFTING) {
			return;
		}

		event.setCancelled(true);
		if (!pendingOpen.add(playerId)) {
			return;
		}
		scheduler.runPlayerRegionTask(() -> {
			try {
				if (started.get() && player.isOnline() && auctionMode.isActive(playerId)
						&& player.getOpenInventory().getType() == InventoryType.CRAFTING) {
					bidPanelOpener.openBidPanel(player);
				}
			} finally {
				pendingOpen.remove(playerId);
			}
		}, player);
	}
}
