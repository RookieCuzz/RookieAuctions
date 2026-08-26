package me.elian.ezauctions;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceContractTest {
	@Test
	void immersiveDefaultsMatchThePublishedScheduleContract() {
		YamlConfiguration config = resource("config.yml");

		assertEquals(3, config.getInt("config-version"));
		assertFalse(config.getBoolean("immersive.enabled"));
		assertEquals("Asia/Shanghai", config.getString("immersive.timezone"));
		assertEquals(2, config.getInt("immersive.booking-horizon-sessions"));
		assertEquals(16, config.getInt("immersive.capacity-per-session"));
		assertEquals(2, config.getInt("immersive.max-lots-per-player-per-session"));
		assertEquals(600, config.getInt("immersive.submission-cutoff-seconds"));
		assertEquals(120, config.getInt("immersive.lot-duration-seconds"));
		assertEquals(10, config.getInt("immersive.intermission-seconds"));
		assertEquals(1_800, config.getInt("immersive.missed-start-grace-seconds"));
		assertEquals(30, config.getInt("immersive.blocked-retry-seconds"));
		assertEquals("14:00", config.getString("immersive.schedules.afternoon.time"));
		assertEquals("20:00", config.getString("immersive.schedules.evening.time"));
		assertEquals(30, config.getInt("antisnipe.seconds-for-start"));
		assertEquals(30, config.getInt("antisnipe.time"));
		assertEquals(3, config.getInt("antisnipe.run-times"));
	}

	@Test
	void venueHasNoDangerousCoordinateDefaults() {
		YamlConfiguration config = resource("config.yml");

		for (String point : new String[]{"buyer-spawn", "item-display", "info-display",
				"corner1", "corner2"}) {
			String prefix = "immersive.venue." + point;
			assertFalse(config.contains(prefix + ".world"), prefix + " must not default a world");
			assertFalse(config.contains(prefix + ".x"), prefix + " must not default x");
			assertFalse(config.contains(prefix + ".y"), prefix + " must not default y");
			assertFalse(config.contains(prefix + ".z"), prefix + " must not default z");
		}
	}

	@Test
	void pluginMetadataTargetsPaperAndMapsBothPermissionNamespaces() {
		YamlConfiguration plugin = resource("plugin.yml");

		assertEquals("1.21", plugin.getString("api-version"));
		assertFalse(plugin.contains("folia-supported"));
		assertEquals("me.elian.ezauctions.RookieAuctions", plugin.getString("main"));
		assertTrue(plugin.getStringList("provides").contains("ezAuctions"));

		assertTrue(plugin.getBoolean("permissions.rookieauctions.*.children.ezauctions.*"));
		assertTrue(plugin.getBoolean("permissions.rookieauctions.player.children.ezauctions.player"));
		assertTrue(plugin.getBoolean("permissions.ezauctions.player.children.rookieauctions.session.submit"));
		assertTrue(plugin.getBoolean("permissions.ezauctions.player.children.rookieauctions.session.join"));
		assertTrue(plugin.getBoolean("permissions.ezauctions.player.children.rookieauctions.session.leave"));
		assertTrue(plugin.getBoolean("permissions.ezauctions.admin.children.rookieauctions.admin.venue"));
		assertTrue(plugin.getBoolean("permissions.ezauctions.admin.children.rookieauctions.admin.session"));

		for (String permission : new String[]{"rookieauctions.session.submit",
				"rookieauctions.session.join", "rookieauctions.session.leave",
				"rookieauctions.admin.venue", "rookieauctions.admin.session",
				"ezauctions.session.submit", "ezauctions.session.join", "ezauctions.session.leave",
				"ezauctions.admin.venue", "ezauctions.admin.session"}) {
			assertNotNull(plugin.getConfigurationSection("permissions." + permission), permission);
		}
	}

	private static YamlConfiguration resource(String name) {
		InputStream stream = ResourceContractTest.class.getClassLoader().getResourceAsStream(name);
		assertNotNull(stream, name);
		try (stream; InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			return YamlConfiguration.loadConfiguration(reader);
		} catch (java.io.IOException error) {
			throw new AssertionError("Could not close " + name, error);
		}
	}
}
