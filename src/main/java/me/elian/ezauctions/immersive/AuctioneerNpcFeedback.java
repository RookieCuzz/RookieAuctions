package me.elian.ezauctions.immersive;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.elian.ezauctions.controller.ConfigController;
import me.elian.ezauctions.event.AuctionEndEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Sends a post-settlement signal to the configured Adyeshach auctioneer.
 *
 * <p>The integration is deliberately reflective: Adyeshach and ModelEngine remain optional
 * runtime dependencies, and their source or binaries do not need to be modified.</p>
 */
@Singleton
public final class AuctioneerNpcFeedback implements Listener {
	private static final String ROOT = "immersive.auctioneer-feedback.";
	private static final String ADYESHACH_PLUGIN = "Adyeshach";
	private static final String ADYESHACH_API = "ink.ptms.adyeshach.core.Adyeshach";
	private static final String ADYESHACH_API_TYPE = "ink.ptms.adyeshach.core.AdyeshachAPI";
	private static final String ENTITY_FINDER_TYPE = "ink.ptms.adyeshach.core.AdyeshachEntityFinder";
	private static final String ENTITY_INSTANCE_TYPE = "ink.ptms.adyeshach.core.entity.EntityInstance";
	private static final String MODEL_ENGINE_TYPE = "ink.ptms.adyeshach.core.entity.ModelEngine";
	private static final String ANIMATION_EXTENSIONS =
			"ink.ptms.adyeshach.compat.modelengine4.ActiveModelExtensionKt";
	private static final String LOOP_MODE_TYPE =
			"com.ticxo.modelengine.api.animation.BlueprintAnimation$LoopMode";

	private final Plugin plugin;
	private final ConfigController config;
	private final AtomicBoolean started = new AtomicBoolean();
	private final Set<String> warned = ConcurrentHashMap.newKeySet();
	private volatile boolean readyLogged;

	@Inject
	public AuctioneerNpcFeedback(@NotNull Plugin plugin, @NotNull ConfigController config) {
		this.plugin = plugin;
		this.config = config;
	}

	public void start() {
		if (!started.compareAndSet(false, true)) {
			return;
		}
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		plugin.getServer().getScheduler().runTaskLater(plugin, this::validateIntegration, 20L);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onAuctionSold(@NotNull AuctionEndEvent event) {
		if (!enabled() || event.getAuction().getScheduledSessionId() == null) {
			return;
		}
		signalDeal();
	}

	/** Plays the configured deal animation and both villager hums on the auctioneer. */
	public void signalDeal() {
		if (!Bukkit.isPrimaryThread()) {
			plugin.getServer().getScheduler().runTask(plugin, this::signalDeal);
			return;
		}
		signalAuctioneer();
	}

	private void validateIntegration() {
		if (!enabled() || !plugin.isEnabled()) {
			return;
		}
		try {
			NpcHandle handle = resolveNpc(false);
			verifyAnimationBridge(handle);
			if (!readyLogged) {
				readyLogged = true;
				plugin.getLogger().info("Auctioneer feedback ready: Adyeshach NPC '"
						+ npcId() + "', model '" + handle.modelId() + "', animation '"
						+ animationId() + "'.");
			}
		} catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
			warnOnce("validation", "Auctioneer feedback is not ready: "
					+ rootMessage(error), error);
		}
	}

	private void signalAuctioneer() {
		if (!plugin.isEnabled() || !enabled()) {
			return;
		}
		try {
			NpcHandle handle = resolveNpc(true);
			playAnimation(handle);
			playVillagerHums(handle.location());
			plugin.getLogger().info("Auctioneer NPC '" + npcId() + "' received deal signal: "
					+ animationId());
		} catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
			warnOnce("signal", "Could not signal Adyeshach auctioneer '" + npcId()
					+ "': " + rootMessage(error), error);
		}
	}

	private @NotNull NpcHandle resolveNpc(boolean ensureModel)
			throws ReflectiveOperationException {
		Plugin adyeshach = plugin.getServer().getPluginManager().getPlugin(ADYESHACH_PLUGIN);
		if (adyeshach == null || !adyeshach.isEnabled()) {
			throw new IllegalStateException("Adyeshach is not enabled");
		}

		ClassLoader loader = adyeshach.getClass().getClassLoader();
		Class<?> adyeshachClass = Class.forName(ADYESHACH_API, true, loader);
		Object singleton = adyeshachClass.getField("INSTANCE").get(null);
		Object api = adyeshachClass.getMethod("api").invoke(singleton);

		Class<?> apiType = Class.forName(ADYESHACH_API_TYPE, true, loader);
		Object finder = apiType.getMethod("getEntityFinder").invoke(api);
		Class<?> finderType = Class.forName(ENTITY_FINDER_TYPE, true, loader);
		Object found = finderType.getMethod("getEntitiesFromId", String.class, Player.class)
				.invoke(finder, npcId(), null);
		if (!(found instanceof List<?> entities) || entities.isEmpty()) {
			throw new IllegalStateException("NPC id was not found");
		}

		Class<?> modelEngineType = Class.forName(MODEL_ENGINE_TYPE, true, loader);
		Object entity = entities.stream().filter(modelEngineType::isInstance).findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"NPC does not expose the Adyeshach ModelEngine interface"));
		String modelId = (String) modelEngineType.getMethod("getModelEngineName").invoke(entity);
		UUID modelUniqueId = (UUID) modelEngineType.getMethod("getModelEngineUniqueId").invoke(entity);
		if (modelId == null || modelId.isBlank()) {
			throw new IllegalStateException("NPC has no ModelEngine model name");
		}
		if (modelUniqueId == null && ensureModel) {
			modelEngineType.getMethod("refreshModelEngine").invoke(entity);
			modelUniqueId = (UUID) modelEngineType.getMethod("getModelEngineUniqueId")
					.invoke(entity);
		}
		if (modelUniqueId == null && ensureModel) {
			throw new IllegalStateException(
					"NPC ModelEngine model is not visible to any player and could not be created");
		}

		Class<?> entityType = Class.forName(ENTITY_INSTANCE_TYPE, true, loader);
		Location location = (Location) entityType.getMethod("getLocation").invoke(entity);
		return new NpcHandle(loader, modelEngineType, entity, modelId, location.clone());
	}

	private void verifyAnimationBridge(@NotNull NpcHandle handle)
			throws ReflectiveOperationException {
		Class<?> loopModeType = Class.forName(LOOP_MODE_TYPE, true, handle.loader());
		Class<?> extensions = Class.forName(ANIMATION_EXTENSIONS, true, handle.loader());
		extensions.getMethod("playAnimation",
				handle.modelEngineType(), String.class, String.class,
				int.class, int.class, double.class, boolean.class, boolean.class,
				loopModeType, int.class);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void playAnimation(@NotNull NpcHandle handle) throws ReflectiveOperationException {
		Class<?> loopModeType = Class.forName(LOOP_MODE_TYPE, true, handle.loader());
		Object once = Enum.valueOf((Class) loopModeType.asSubclass(Enum.class), "ONCE");
		Class<?> extensions = Class.forName(ANIMATION_EXTENSIONS, true, handle.loader());
		Method playAnimation = extensions.getMethod("playAnimation",
				handle.modelEngineType(), String.class, String.class,
				int.class, int.class, double.class, boolean.class, boolean.class,
				loopModeType, int.class);
		playAnimation.invoke(null, handle.entity(), handle.modelId(), animationId(),
				boundedInt("animation.lerp-in-ticks", 0, 100, 2),
				boundedInt("animation.lerp-out-ticks", 0, 100, 4),
				boundedDouble("animation.speed", 0.05D, 8D, 1D),
				true, true, once,
				boundedInt("animation.priority", 1, 100, 10));
	}

	private void playVillagerHums(@NotNull Location location) {
		if (!config.getConfig().getBoolean(ROOT + "sound.enabled", true)) {
			return;
		}
		float volume = (float) boundedDouble("sound.volume", 0D, 16D, 1.1D);
		playVillagerHum(location, volume,
				(float) boundedDouble("sound.first-pitch", 0.5D, 2D, 0.95D));
		long delay = boundedInt("sound.interval-ticks", 1, 100, 8);
		Location secondLocation = location.clone();
		plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
			if (plugin.isEnabled()) {
				playVillagerHum(secondLocation, volume,
						(float) boundedDouble("sound.second-pitch", 0.5D, 2D, 1.05D));
			}
		}, delay);
	}

	private void playVillagerHum(@NotNull Location location, float volume, float pitch) {
		World world = location.getWorld();
		if (world != null) {
			world.playSound(location, Sound.ENTITY_VILLAGER_AMBIENT,
					SoundCategory.NEUTRAL, volume, pitch);
		}
	}

	private boolean enabled() {
		return config.getConfig().getBoolean(ROOT + "enabled", true);
	}

	private @NotNull String npcId() {
		return configuredText("npc-id", "auctioneer");
	}

	private @NotNull String animationId() {
		return configuredText("animation.id", "deal");
	}

	private @NotNull String configuredText(@NotNull String path, @NotNull String fallback) {
		String value = config.getConfig().getString(ROOT + path, fallback);
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private int boundedInt(@NotNull String path, int minimum, int maximum, int fallback) {
		int value = config.getConfig().getInt(ROOT + path, fallback);
		return Math.max(minimum, Math.min(maximum, value));
	}

	private double boundedDouble(@NotNull String path, double minimum,
	                             double maximum, double fallback) {
		double value = config.getConfig().getDouble(ROOT + path, fallback);
		return Double.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : fallback;
	}

	private void warnOnce(@NotNull String key, @NotNull String message, @NotNull Throwable error) {
		if (warned.add(key)) {
			plugin.getLogger().log(Level.WARNING, message, unwrap(error));
		}
	}

	private @NotNull String rootMessage(@NotNull Throwable error) {
		Throwable root = unwrap(error);
		return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
	}

	private @NotNull Throwable unwrap(@NotNull Throwable error) {
		Throwable current = error;
		while (current instanceof InvocationTargetException && current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	private record NpcHandle(@NotNull ClassLoader loader,
	                         @NotNull Class<?> modelEngineType,
	                         @NotNull Object entity,
	                         @NotNull String modelId,
	                         @NotNull Location location) {
	}
}
