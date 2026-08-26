package me.elian.ezauctions.immersive;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.elian.ezauctions.controller.ConfigController;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Reads, writes and validates the intentionally unset immersive venue coordinates. */
@Singleton
public final class VenueConfig {
	private static final String ROOT = "immersive.venue.";
	private final ConfigController configController;
	private final Server server;

	@Inject
	public VenueConfig(@NotNull ConfigController configController, @NotNull Plugin plugin) {
		this.configController = configController;
		this.server = plugin.getServer();
	}

	public synchronized boolean isEnabled() {
		return config().getBoolean("immersive.enabled", false);
	}

	public synchronized boolean isReady() {
		return isEnabled() && resolve().validation().valid();
	}

	/** Enabling is refused until all five locations resolve and the buyer spawn is inside the region. */
	public synchronized void setEnabled(boolean enabled) throws IOException {
		if (enabled) {
			VenueValidation validation = resolve().validation();
			if (!validation.valid()) {
				throw new IllegalStateException(validation.summary());
			}
		}
		config().set("immersive.enabled", enabled);
		saveOrReload();
	}

	public synchronized @NotNull Optional<VenuePoint> getPoint(@NotNull VenueLocationType type) {
		return readPoint(type, new ArrayList<>());
	}

	/** Persists the caller's exact position and look direction. */
	public synchronized void setPoint(@NotNull VenueLocationType type,
	                                  @NotNull Location location) throws IOException {
		VenuePoint point = VenuePoint.from(location);
		String path = ROOT + type.configKey();
		FileConfiguration config = config();
		config.set(path + ".world", point.worldName());
		config.set(path + ".world-uuid", point.worldId() == null ? null : point.worldId().toString());
		config.set(path + ".x", point.x());
		config.set(path + ".y", point.y());
		config.set(path + ".z", point.z());
		config.set(path + ".yaw", point.yaw());
		config.set(path + ".pitch", point.pitch());
		saveOrReload();
	}

	public synchronized void clearPoint(@NotNull VenueLocationType type) throws IOException {
		String path = ROOT + type.configKey();
		// Keep display styling while clearing coordinates from item/info display nodes.
		for (String key : List.of("world", "world-uuid", "x", "y", "z", "yaw", "pitch")) {
			config().set(path + "." + key, null);
		}
		config().set("immersive.enabled", false);
		saveOrReload();
	}

	public synchronized float itemDisplayScale() {
		double configured = configuredItemDisplayScale();
		return Double.isFinite(configured) && configured > 0D ? (float) configured : 1.25F;
	}

	public synchronized int itemSpinPeriodTicks() {
		return Math.max(20, config().getInt(ROOT + "item-display.spin-period-ticks", 80));
	}

	public synchronized int infoLineWidth() {
		return Math.max(40, Math.min(1_000,
				config().getInt(ROOT + "info-display.line-width", 240)));
	}

	public synchronized boolean infoShadowed() {
		return config().getBoolean(ROOT + "info-display.shadowed", true);
	}

	public synchronized boolean infoSeeThrough() {
		return config().getBoolean(ROOT + "info-display.see-through", false);
	}

	public synchronized @NotNull Color infoBackgroundColor() {
		String configured = config().getString(ROOT + "info-display.background-color", "#66000000");
		if (configured == null) {
			return Color.fromARGB(0x66000000);
		}
		try {
			String value = configured.trim();
			if (value.startsWith("#")) {
				value = value.substring(1);
			}
			long parsed = Long.parseUnsignedLong(value, 16);
			if (value.length() == 6) {
				parsed |= 0xFF000000L;
			}
			if (value.length() != 6 && value.length() != 8) {
				throw new NumberFormatException("Expected RRGGBB or AARRGGBB");
			}
			return Color.fromARGB((int) parsed);
		} catch (IllegalArgumentException ignored) {
			return Color.fromARGB(0x66000000);
		}
	}

	public synchronized @NotNull VenueValidation validate() {
		return resolve().validation();
	}

	/** Reads the complete config once so validation and the returned layout cannot disagree. */
	public synchronized @NotNull VenueResolution resolve() {
		List<String> errors = new ArrayList<>();
		Map<VenueLocationType, VenuePoint> points = new EnumMap<>(VenueLocationType.class);
		for (VenueLocationType type : VenueLocationType.values()) {
			readPoint(type, errors).ifPresent(point -> points.put(type, point));
		}

		Map<VenueLocationType, Location> locations = new EnumMap<>(VenueLocationType.class);
		for (Map.Entry<VenueLocationType, VenuePoint> entry : points.entrySet()) {
			Optional<Location> resolved = entry.getValue().resolve(server);
			if (resolved.isEmpty()) {
				errors.add(entry.getKey().configKey() + " 的世界未加载或不存在："
						+ entry.getValue().worldName());
			} else {
				locations.put(entry.getKey(), resolved.get());
			}
		}

		double configuredScale = configuredItemDisplayScale();
		float scale = (float) configuredScale;
		if (!Double.isFinite(configuredScale) || !Float.isFinite(scale) || scale <= 0F) {
			errors.add("item-display.scale 必须是大于 0 的有限数字");
		}

		if (locations.size() == VenueLocationType.values().length) {
			UUID expectedWorld = locations.get(VenueLocationType.BUYER_SPAWN).getWorld().getUID();
			for (Map.Entry<VenueLocationType, Location> entry : locations.entrySet()) {
				World world = entry.getValue().getWorld();
				if (world == null || !expectedWorld.equals(world.getUID())) {
					errors.add("所有场地点必须位于同一个世界（冲突点："
							+ entry.getKey().configKey() + "）");
					break;
				}
			}
		}

		InclusiveCuboid bounds = null;
		Location first = locations.get(VenueLocationType.CORNER_1);
		Location second = locations.get(VenueLocationType.CORNER_2);
		if (first != null && second != null && first.getWorld() != null && second.getWorld() != null
				&& first.getWorld().getUID().equals(second.getWorld().getUID())) {
			bounds = new InclusiveCuboid(first, second);
			if (first.distanceSquared(second) == 0D) {
				errors.add("corner1 与 corner2 不能是同一个点");
			}
			Location buyerSpawn = locations.get(VenueLocationType.BUYER_SPAWN);
			if (buyerSpawn != null && !bounds.contains(buyerSpawn)) {
				errors.add("buyer-spawn 必须位于 corner1/corner2 定义的闭区间内");
			}
		}

		if (!errors.isEmpty() || locations.size() != VenueLocationType.values().length || bounds == null) {
			if (errors.isEmpty()) {
				errors.add("场地配置不完整");
			}
			return new VenueResolution(VenueValidation.failure(errors), null);
		}

		VenueLayout layout = new VenueLayout(
				locations.get(VenueLocationType.BUYER_SPAWN),
				locations.get(VenueLocationType.ITEM_DISPLAY),
				locations.get(VenueLocationType.INFO_DISPLAY), bounds, scale);
		return new VenueResolution(VenueValidation.success(), layout);
	}

	private Optional<VenuePoint> readPoint(VenueLocationType type, List<String> errors) {
		String path = ROOT + type.configKey();
		FileConfiguration config = config();
		String worldName = config.getString(path + ".world");
		if (worldName == null || worldName.isBlank()
				|| !config.isSet(path + ".x") || !config.isSet(path + ".y")
				|| !config.isSet(path + ".z")) {
			errors.add("缺少场地点：" + type.configKey());
			return Optional.empty();
		}

		UUID worldId = null;
		String rawWorldId = config.getString(path + ".world-uuid");
		if (rawWorldId != null && !rawWorldId.isBlank()) {
			try {
				worldId = UUID.fromString(rawWorldId);
			} catch (IllegalArgumentException error) {
				errors.add(type.configKey() + " 的 world-uuid 无效");
				return Optional.empty();
			}
		}

		try {
			return Optional.of(new VenuePoint(worldName, worldId,
					config.getDouble(path + ".x"), config.getDouble(path + ".y"),
					config.getDouble(path + ".z"),
					(float) config.getDouble(path + ".yaw", 0D),
					(float) config.getDouble(path + ".pitch", 0D)));
		} catch (IllegalArgumentException error) {
			errors.add(type.configKey() + " 包含无效坐标或朝向");
			return Optional.empty();
		}
	}

	private FileConfiguration config() {
		return configController.getConfig();
	}

	private double configuredItemDisplayScale() {
		return config().getDouble(ROOT + "item-display.scale", 1.25D);
	}

	private void saveOrReload() throws IOException {
		try {
			configController.save();
		} catch (IOException error) {
			try {
				configController.reload();
			} catch (IOException suppressed) {
				error.addSuppressed(suppressed);
			}
			throw error;
		}
	}
}
