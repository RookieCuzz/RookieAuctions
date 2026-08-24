package me.elian.ezauctions;

import com.google.inject.Inject;
import me.elian.ezauctions.controller.AuctionController;
import me.elian.ezauctions.controller.ConfigController;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Primary PlaceholderAPI namespace for RookieAuctions.
 */
public final class RookieAuctionsPlaceholderExpansion extends EzAuctionsPlaceholderExpansion {
	@Inject
	public RookieAuctionsPlaceholderExpansion(Plugin plugin, AuctionController auctionController,
	                                          ConfigController configController, Economy economy) {
		super(plugin, auctionController, configController, economy);
	}

	@Override
	public @NotNull String getIdentifier() {
		return "rookieauctions";
	}
}
