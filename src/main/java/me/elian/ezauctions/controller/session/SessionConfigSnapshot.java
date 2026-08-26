package me.elian.ezauctions.controller.session;

import me.elian.ezauctions.Logger;
import me.elian.ezauctions.controller.ConfigController;
import me.elian.ezauctions.session.ScheduleDefinition;
import me.elian.ezauctions.session.SessionSlot;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validated immutable adapter from Bukkit configuration to the pure schedule domain. */
record SessionConfigSnapshot(
		ScheduleDefinition schedule,
		Map<String, String> displayNames,
		int blockedRetrySeconds
) {
	SessionConfigSnapshot {
		displayNames = Map.copyOf(displayNames);
		if (blockedRetrySeconds <= 0) {
			throw new IllegalArgumentException("blockedRetrySeconds must be positive");
		}
	}

	static SessionConfigSnapshot load(ConfigController controller, Logger logger) {
		FileConfiguration config = controller.getConfig();
		try {
			ZoneId zone = ZoneId.of(config.getString("immersive.timezone", "Asia/Shanghai"));
			ConfigurationSection schedules = config.getConfigurationSection("immersive.schedules");
			List<SessionSlot> slots = new ArrayList<>();
			Map<String, String> names = new LinkedHashMap<>();
			if (schedules != null) {
				for (String id : schedules.getKeys(false)) {
					String rawTime = schedules.getString(id + ".time");
					if (rawTime == null || rawTime.isBlank()) {
						throw new IllegalArgumentException("Missing time for immersive.schedules." + id);
					}
					slots.add(new SessionSlot(id, LocalTime.parse(rawTime.trim())));
					names.put(id, schedules.getString(id + ".display-name", id));
				}
			}
			if (slots.isEmpty()) {
				throw new IllegalArgumentException("immersive.schedules must define at least one slot");
			}

			ScheduleDefinition definition = new ScheduleDefinition(
					zone,
					slots,
					config.getInt("immersive.submission-cutoff-seconds", 600),
					config.getInt("immersive.booking-horizon-sessions", 2),
					config.getInt("immersive.capacity-per-session", 16),
					config.getInt("immersive.max-lots-per-player-per-session", 2),
					config.getInt("immersive.lot-duration-seconds", 120),
					config.getInt("immersive.intermission-seconds", 10),
					config.getInt("immersive.missed-start-grace-seconds", 1_800),
					config.getInt("antisnipe.seconds-for-start", 30),
					config.getInt("antisnipe.time", 30),
					config.getInt("antisnipe.run-times", 3)
			);
			return new SessionConfigSnapshot(definition, names,
					Math.max(1, config.getInt("immersive.blocked-retry-seconds", 30)));
		} catch (IllegalArgumentException error) {
			// Schedule mistakes must never create sessions at an unintended location/time.
			logger.warning("Invalid immersive session schedule; using built-in safe defaults", error);
			ScheduleDefinition defaults = ScheduleDefinition.defaults();
			return new SessionConfigSnapshot(defaults,
					Map.of("afternoon", "午场", "evening", "晚场"), 30);
		}
	}

	String displayName(String slotId) {
		return displayNames.getOrDefault(slotId, slotId);
	}
}
