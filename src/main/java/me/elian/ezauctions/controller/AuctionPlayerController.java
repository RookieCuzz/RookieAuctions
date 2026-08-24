package me.elian.ezauctions.controller;

import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.elian.ezauctions.Logger;
import me.elian.ezauctions.data.Database;
import me.elian.ezauctions.helper.ItemHelper;
import me.elian.ezauctions.model.AuctionPlayer;
import me.elian.ezauctions.model.SavedItem;
import me.elian.ezauctions.model.RewardRecord;
import me.elian.ezauctions.scheduler.TaskScheduler;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Singleton
public class AuctionPlayerController implements Listener {
	private final Logger logger;
	private final Database database;
	private final TaskScheduler scheduler;
	private final ConfigController config;
	private final MessageController messages;
	private final ScoreboardController scoreboard;
	private final Set<AuctionPlayer> onlinePlayers = Sets.newConcurrentHashSet();

	@Inject
	public AuctionPlayerController(Plugin plugin, Logger logger, Database database, TaskScheduler scheduler,
	                               ConfigController config, MessageController messages,
	                               ScoreboardController scoreboard) {
		this.logger = logger;
		this.database = database;
		this.scheduler = scheduler;
		this.config = config;
		this.messages = messages;
		this.scoreboard = scoreboard;
		plugin.getServer().getPluginManager().registerEvents(this, plugin);

		scheduler.runAsyncTask(() -> {
			for (Player player : plugin.getServer().getOnlinePlayers()) {
				database.getAuctionPlayer(player.getUniqueId()).thenAccept(onlinePlayers::add);
			}
		});
	}

	public @NotNull CompletableFuture<AuctionPlayer> getPlayer(@NotNull Player player) {
		return getPlayer(player.getUniqueId());
	}

	public @NotNull CompletableFuture<AuctionPlayer> getPlayer(@NotNull UUID id) {
		AuctionPlayer online = getOnlinePlayer(id);
		if (online != null) {
			return CompletableFuture.completedFuture(online);
		}

		return database.getAuctionPlayer(id);
	}

	public AuctionPlayer getOnlinePlayer(@NotNull UUID id) {
		for (AuctionPlayer auctionPlayer : onlinePlayers) {
			if (auctionPlayer.getUniqueId().equals(id)) {
				return auctionPlayer;
			}
		}
		return null;
	}

	/**
	 * For instances where a new AuctionPlayer record is required from a fresh connection to the database
	 * @param id Player UUID
	 * @return CompletableFuture for AuctionPlayer record
	 */
	public @NotNull CompletableFuture<AuctionPlayer> getPlayerFromDatabase(@NotNull UUID id) {
		return database.getAuctionPlayer(id);
	}

	public void savePlayer(@NotNull AuctionPlayer auctionPlayer) {
		scheduler.runAsyncTask(() -> database.saveAuctionPlayer(auctionPlayer));
	}

	public @NotNull Set<AuctionPlayer> getOnlinePlayers() {
		return Collections.unmodifiableSet(onlinePlayers);
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent e) {
		scheduler.runAsyncTask(() -> {
			Player p = e.getPlayer();
			UUID id = p.getUniqueId();

			database.getAuctionPlayer(id).thenAccept(ap -> {
				onlinePlayers.add(ap);
				scoreboard.addPlayer(p);
				migrateLegacySavedItems(ap);
			});
		});
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent e) {
		UUID id = e.getPlayer().getUniqueId();

		for (AuctionPlayer ap : onlinePlayers) {
			if (ap.getUniqueId().equals(id)) {
				onlinePlayers.remove(ap);
				break;
			}
		}
	}

	private void migrateLegacySavedItems(@NotNull AuctionPlayer auctionPlayer) {
		if (auctionPlayer.getSavedItems() == null || auctionPlayer.getSavedItems().isEmpty()) {
			return;
		}
		List<SavedItem> snapshot = new ArrayList<>(auctionPlayer.getSavedItems());
		for (SavedItem legacy : snapshot) {
			try {
				RewardRecord reward = RewardRecord.legacyItem(auctionPlayer.getUniqueId(), legacy.getId(),
						legacy.getItemStack(), legacy.getAmount(), legacy.getWorld());
				database.createReward(reward).thenRun(() -> auctionPlayer.getSavedItems().remove(legacy));
			} catch (IOException exception) {
				logger.severe("Could not migrate legacy saved item " + legacy.getId(), exception);
			}
		}
	}
}
