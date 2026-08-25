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

@Singleton
public class ConfigController extends FileHandler {
	private static final String RESOURCE_NAME = "config.yml";
	private static final int ANTI_SNIPE_CONFIG_VERSION = 2;
	private FileConfiguration fileConfiguration;

	@Inject
	public ConfigController(Plugin plugin, Logger logger) {
		super(plugin, logger, RESOURCE_NAME);

		try {
			reload();
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
}
