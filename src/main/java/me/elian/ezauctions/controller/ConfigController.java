package me.elian.ezauctions.controller;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.elian.ezauctions.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Reader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Singleton
public class ConfigController extends FileHandler {
	private static final String RESOURCE_NAME = "config.yml";
	private static final int ANTI_SNIPE_CONFIG_VERSION = 2;
	private static final int CURRENT_CONFIG_VERSION = 3;
	private final Plugin plugin;
	private final Logger logger;
	private FileConfiguration fileConfiguration;

	@Inject
	public ConfigController(Plugin plugin, Logger logger) {
		super(plugin, logger, RESOURCE_NAME);
		this.plugin = plugin;
		this.logger = logger;

		try {
			reload();
			mergeDefaultsAndMigrate();
			if (!isAntiSnipeConfigCurrent()) {
				logger.warning("Anti-snipe is disabled because config.yml uses the legacy add-time format. "
						+ "Update antisnipe.config-version to 2 and review seconds-for-start/time.");
			}
		} catch (IOException e) {
			logger.severe("Could not load config file!", e);
		}
	}

	@Override
	protected void loadFile(@NotNull Reader reader) {
		fileConfiguration = YamlConfiguration.loadConfiguration(reader);
	}

	public @NotNull FileConfiguration getConfig() {
		return fileConfiguration;
	}

	public boolean isAntiSnipeConfigCurrent() {
		return fileConfiguration != null
				&& fileConfiguration.getInt("antisnipe.config-version", 0) >= ANTI_SNIPE_CONFIG_VERSION;
	}

	/** Saves the live configuration through a temporary file so venue setup is never half-written. */
	public synchronized void save() throws IOException {
		Path target = getPath();
		Files.createDirectories(target.getParent());
		Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
		fileConfiguration.save(temporary.toFile());
		try {
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private void mergeDefaultsAndMigrate() throws IOException {
		FileConfiguration defaults;
		try (InputStream stream = plugin.getResource(RESOURCE_NAME)) {
			if (stream == null) {
				throw new IOException("Bundled config.yml is missing");
			}
			defaults = YamlConfiguration.loadConfiguration(
					new InputStreamReader(stream, StandardCharsets.UTF_8));
		}

		int oldVersion = fileConfiguration.getInt("config-version", 0);
		fileConfiguration.setDefaults(defaults);
		fileConfiguration.options().copyDefaults(true);
		if (oldVersion < CURRENT_CONFIG_VERSION) {
			fileConfiguration.set("config-version", CURRENT_CONFIG_VERSION);
			fileConfiguration.set("immersive.enabled", false);
			fileConfiguration.set("antisnipe.config-version", ANTI_SNIPE_CONFIG_VERSION);
			fileConfiguration.set("antisnipe.enabled", true);
			fileConfiguration.set("antisnipe.seconds-for-start", 30);
			fileConfiguration.set("antisnipe.time", 30);
			fileConfiguration.set("antisnipe.run-times", 3);
			logger.info("Migrated config.yml to immersive session format version " + CURRENT_CONFIG_VERSION
					+ "; venue setup remains disabled until validated.");
		}
		save();
	}
}
