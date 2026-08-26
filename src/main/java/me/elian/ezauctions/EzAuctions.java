package me.elian.ezauctions;

import co.aikar.commands.PaperCommandManager;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Providers;
import me.elian.ezauctions.command.AuctionCommand;
import me.elian.ezauctions.controller.AuctionController;
import me.elian.ezauctions.controller.MessageController;
import me.elian.ezauctions.controller.ScoreboardController;
import me.elian.ezauctions.controller.UpdateController;
import me.elian.ezauctions.controller.session.AuctionSessionController;
import me.elian.ezauctions.data.Database;
import me.elian.ezauctions.gui.AuctionGuiController;
import me.elian.ezauctions.immersive.AttendanceService;
import me.elian.ezauctions.immersive.ImmersiveAuctionInputListener;
import me.elian.ezauctions.scheduler.BukkitTaskScheduler;
import me.elian.ezauctions.scheduler.TaskScheduler;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

public class EzAuctions extends JavaPlugin {
	private TaskScheduler scheduler;
	private Database database;
	private AuctionController auctionController;
	private MessageController messageController;
	private ScoreboardController scoreboardController;
	private UpdateController updateController;
	private AuctionGuiController auctionGuiController;
	private AuctionSessionController auctionSessionController;
	private ImmersiveAuctionInputListener immersiveInputListener;
	private Injector injector;

	private static Class<? extends TaskScheduler> getSchedulerType() {
		return BukkitTaskScheduler.class;
	}

	public Injector getInjector() {
		return injector;
	}

	@Override
	public void onEnable() {
		migrateLegacyDataFolder();

		Economy economy = getEconomy();
		if (economy == null) {
			setEnabled(false);
			return;
		}

		Permission permission = getPermission();

		injector = createInjector(economy, permission, getSchedulerType());
		registerCommands(injector);

		scheduler = injector.getInstance(TaskScheduler.class);
		database = injector.getInstance(Database.class);
		auctionController = injector.getInstance(AuctionController.class);
		messageController = injector.getInstance(MessageController.class);
		scoreboardController = injector.getInstance(ScoreboardController.class);
		auctionGuiController = injector.getInstance(AuctionGuiController.class);
		auctionSessionController = injector.getInstance(AuctionSessionController.class);
		AttendanceService attendance = injector.getInstance(AttendanceService.class);
		immersiveInputListener = new ImmersiveAuctionInputListener(this, scheduler, attendance,
				auctionGuiController::openBidPanel);
		auctionSessionController.start();
		immersiveInputListener.start();

		updateController = injector.getInstance(UpdateController.class);
		updateController.checkForUpdates();

		if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
			injector.getInstance(RookieAuctionsPlaceholderExpansion.class).register();
			injector.getInstance(EzAuctionsPlaceholderExpansion.class).register();
		}
	}

	@Override
	public void onDisable() {
		if (immersiveInputListener != null) {
			immersiveInputListener.shutdown();
		}

		if (auctionGuiController != null) {
			auctionGuiController.shutdown();
		}

		if (auctionSessionController != null) {
			auctionSessionController.shutdown();
		}

		// Session state is checkpointed before the shared scheduler is stopped.
		if (scheduler != null) {
			scheduler.shutdown();
		}

		if (auctionController != null) {
			auctionController.shutdown();
		}

		if (messageController != null) {
			messageController.shutdown();
		}

		if (scoreboardController != null) {
			scoreboardController.shutdown();
		}

		if (updateController != null) {
			updateController.shutdown();
		}

		// database must be shut down last in case saved items need to be added
		if (database != null) {
			database.shutdown();
		}

	}

	private Economy getEconomy() {
		if (getServer().getPluginManager().getPlugin("Vault") == null) {
			getLogger().severe("Vault plugin not Installed! Disabling RookieAuctions...");
			return null;
		}

		ServicesManager servicesManager = getServer().getServicesManager();
		RegisteredServiceProvider<Economy> rsp = servicesManager.getRegistration(Economy.class);
		if (rsp == null) {
			getLogger().severe("Economy provider plugin not found! " +
					"Make sure you have an economy provider plugin installed that supports Vault! " +
					"Disabling RookieAuctions...");
			return null;
		}

		return rsp.getProvider();
	}

	private Permission getPermission() {
		ServicesManager servicesManager = getServer().getServicesManager();
		RegisteredServiceProvider<Permission> rsp = servicesManager.getRegistration(Permission.class);
		if (rsp == null)
			return null;

		return rsp.getProvider();
	}

	private Injector createInjector(Economy economy, Permission permission,
	                                Class<? extends TaskScheduler> schedulerClass) {
		Plugin plugin = this;
		PaperCommandManager commandManager = new PaperCommandManager(plugin);

		return Guice.createInjector(new AbstractModule() {
			@Override
			protected void configure() {
				bind(Plugin.class).toInstance(plugin);
				bind(Economy.class).toInstance(economy);
				bind(PaperCommandManager.class).toInstance(commandManager);
				bind(TaskScheduler.class).to(schedulerClass);

				if (permission == null) {
					bind(Permission.class).toProvider(Providers.of(null));
				} else {
					bind(Permission.class).toInstance(permission);
				}
			}
		});
	}

	private void registerCommands(Injector injector) {
		AuctionCommand auctionCommand = injector.getInstance(AuctionCommand.class);
		PaperCommandManager manager = injector.getInstance(PaperCommandManager.class);

		manager.registerCommand(auctionCommand);
	}

	private void migrateLegacyDataFolder() {
		Path target = getDataFolder().toPath().toAbsolutePath().normalize();
		Path legacy = target.resolveSibling("ezAuctions");
		try {
			if (!Files.isDirectory(legacy) || (Files.exists(target) && !isDirectoryEmpty(target))) {
				return;
			}

			try (Stream<Path> paths = Files.walk(legacy)) {
				for (Path source : (Iterable<Path>) paths::iterator) {
					Path destination = target.resolve(legacy.relativize(source));
					if (Files.isDirectory(source)) {
						Files.createDirectories(destination);
					} else {
						Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
					}
				}
			}

			Path config = target.resolve("config.yml");
			if (Files.isRegularFile(config)) {
				String contents = Files.readString(config, StandardCharsets.UTF_8);
				String migrated = contents
						.replace("jdbc:sqlite:plugins/ezAuctions/sqlite.db", "jdbc:sqlite:sqlite.db")
						.replace("jdbc:sqlite:plugins/RookieAuctions/sqlite.db", "jdbc:sqlite:sqlite.db");
				if (!contents.equals(migrated)) {
					Files.writeString(config, migrated, StandardCharsets.UTF_8);
				}
			}
			getLogger().info("Migrated legacy ezAuctions data to RookieAuctions.");
		} catch (IOException exception) {
			getLogger().warning("Could not migrate the legacy ezAuctions data folder: "
					+ exception.getMessage());
		}
	}

	private boolean isDirectoryEmpty(Path path) throws IOException {
		try (Stream<Path> entries = Files.list(path)) {
			return entries.findAny().isEmpty();
		}
	}
}
