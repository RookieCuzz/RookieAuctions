package me.elian.ezauctions;

import com.google.inject.Inject;
import me.elian.ezauctions.controller.AuctionController;
import me.elian.ezauctions.controller.ConfigController;
import me.elian.ezauctions.controller.session.AuctionSessionController;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Primary PlaceholderAPI namespace for RookieAuctions.
 */
public final class RookieAuctionsPlaceholderExpansion extends EzAuctionsPlaceholderExpansion {
	/** Retains the constructor exposed by pre-session RookieAuctions builds. */
	@Deprecated(forRemoval = false)
	public RookieAuctionsPlaceholderExpansion(Plugin plugin, AuctionController auctionController,
	                                          ConfigController configController, Economy economy) {
		super(plugin, auctionController, configController, economy);
	}

	@Inject
	public RookieAuctionsPlaceholderExpansion(Plugin plugin, AuctionController auctionController,
	                                          ConfigController configController, Economy economy,
	                                          AuctionSessionController sessionController) {
		super(plugin, auctionController, configController, economy, sessionController);
	}

	@Override
	public @NotNull String getIdentifier() {
		return "rookieauctions";
	}
}
