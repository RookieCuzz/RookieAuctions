package me.elian.ezauctions.command;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Description;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.elian.ezauctions.gui.AuctionGuiController;
import org.bukkit.entity.Player;

@Singleton
@CommandAlias("auction")
@Description("Open the auction GUI")
public final class AuctionCommand extends BaseCommand {
	private final AuctionGuiController gui;

	@Inject
	public AuctionCommand(AuctionGuiController gui) {
		this.gui = gui;
	}

	@Default
	public void open(Player player) {
		gui.open(player);
	}
}
