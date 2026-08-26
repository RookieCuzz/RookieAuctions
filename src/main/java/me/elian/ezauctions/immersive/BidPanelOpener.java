package me.elian.ezauctions.immersive;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Adapter implemented by the 27-slot immersive bid GUI. */
@FunctionalInterface
public interface BidPanelOpener {
	void openBidPanel(@NotNull Player player);
}
