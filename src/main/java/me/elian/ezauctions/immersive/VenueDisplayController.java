package me.elian.ezauctions.immersive;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.elian.ezauctions.Logger;
import me.elian.ezauctions.scheduler.CancellableTask;
import me.elian.ezauctions.scheduler.TaskScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Owns the two PDC-tagged native display entities for the single auction venue. */
@Singleton
public final class VenueDisplayController implements Listener {
	private static final String ITEM_ROLE = "item";
	private static final String INFO_ROLE = "info";
	private static final String ITEM_LABEL_ROLE = "item-label";
	static final long PREVIEW_DEAL_DELAY_SECONDS = 5L;
	static final long PREVIEW_DURATION_SECONDS = 10L;
	private final Plugin plugin;
	private final TaskScheduler scheduler;
	private final VenueConfig venueConfig;
	private final AuctioneerNpcFeedback auctioneerNpcFeedback;
	private final Logger logger;
	private final NamespacedKey roleKey;
	private final AtomicReference<VenueDisplayState> latest = new AtomicReference<>();
	private final AtomicLong updateRevision = new AtomicLong();
	private final AtomicBoolean started = new AtomicBoolean();
	private final PreviewWindow previewWindow = new PreviewWindow();
	private volatile ItemDisplay itemDisplay;
	private volatile TextDisplay infoDisplay;
	private volatile TextDisplay itemLabelDisplay;
	private volatile CancellableTask rotationTask;
	private volatile CancellableTask previewCountdownTask;
	private float spinDegrees;
	private long haloTick;

	@Inject
	public VenueDisplayController(@NotNull Plugin plugin, @NotNull TaskScheduler scheduler,
	                              @NotNull VenueConfig venueConfig,
	                              @NotNull AuctioneerNpcFeedback auctioneerNpcFeedback,
	                              @NotNull Logger logger) {
		this.plugin = plugin;
		this.scheduler = scheduler;
		this.venueConfig = venueConfig;
		this.auctioneerNpcFeedback = auctioneerNpcFeedback;
		this.logger = logger;
		this.roleKey = new NamespacedKey(plugin, "immersive-venue-display");
	}

	/** Idempotent lifecycle hook. Parent integration should call this during plugin enable. */
	public void start() {
		if (!started.compareAndSet(false, true)) {
			return;
		}
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		scheduler.runSyncTask(() -> {
			cleanupOrphansNow();
			VenueDisplayState state = latest.get();
			if (state != null) {
				renderNow(state, previewWindow.active());
			}
		});
		rotationTask = scheduler.runSyncRepeatingTickTask(plugin, this::rotateNow, 1L, 1L);
	}

	/** Shows live state only while immersive mode is enabled and the venue remains valid. */
	public void update(@NotNull VenueDisplayState state) {
		if (!previewWindow.updateLive(state)) {
			queueRender(state, false);
		}
	}

	/** Admin preview remains pinned for ten seconds, even while live session updates continue. */
	public void preview(@NotNull VenueDisplayState state) {
		CancellableTask previousCountdownTask = previewCountdownTask;
		previewCountdownTask = null;
		if (previousCountdownTask != null) {
			previousCountdownTask.cancel();
		}
		long previewRevision = previewWindow.begin(state);
		queueRender(state, true);
		previewCountdownTask = scheduler.runSyncRepeatingTask(plugin,
				() -> tickPreview(previewRevision), 1L, 1L);
		scheduler.runAsyncDelayedTask(
				() -> scheduler.runSyncTask(() -> triggerPreviewDeal(previewRevision)),
				PREVIEW_DEAL_DELAY_SECONDS);
		scheduler.runAsyncDelayedTask(
				() -> scheduler.runSyncTask(() -> finishPreview(previewRevision)),
				PREVIEW_DURATION_SECONDS);
	}

	public void preview() {
		preview(VenueDisplayState.lot("场地预览", 1, 16,
				new ItemStack(Material.DIAMOND), "钻石", 120,
				"¥ 1,000", false, 2_070));
	}

	public void clear() {
		CancellableTask countdownTask = previewCountdownTask;
		previewCountdownTask = null;
		if (countdownTask != null) {
			countdownTask.cancel();
		}
		previewWindow.clear();
		latest.set(null);
		updateRevision.incrementAndGet();
		scheduler.runSyncTask(this::removeOwnedDisplaysNow);
	}

	/** Re-renders the current display state so changed styling is visible without a restart. */
	public void refresh() {
		VenueDisplayState state = latest.get();
		if (state != null) {
			queueRender(state, previewWindow.active());
		}
	}

	/** Removes duplicate/crash-left entities in loaded chunks and forgets stale references. */
	public void cleanupOrphans() {
		scheduler.runSyncTask(this::cleanupOrphansNow);
	}

	public void shutdown() {
		if (!started.compareAndSet(true, false)) {
			return;
		}
		CancellableTask task = rotationTask;
		rotationTask = null;
		if (task != null) {
			task.cancel();
		}
		CancellableTask countdownTask = previewCountdownTask;
		previewCountdownTask = null;
		if (countdownTask != null) {
			countdownTask.cancel();
		}
		previewWindow.clear();
		latest.set(null);
		HandlerList.unregisterAll(this);
		// Plugin disable normally runs on the primary thread. Remove immediately there so
		// the scheduler shutdown that follows cannot cancel the cleanup task first.
		if (Bukkit.isPrimaryThread()) {
			removeOwnedDisplaysNow();
		} else {
			scheduler.runSyncTask(this::removeOwnedDisplaysNow);
		}
	}

	/** Removes persistent crash-left displays when their chunk is loaded later. */
	@EventHandler
	public void onChunkLoad(ChunkLoadEvent event) {
		for (Entity entity : event.getChunk().getEntities()) {
			if (!entity.getPersistentDataContainer().has(roleKey, PersistentDataType.STRING)) {
				continue;
			}
			if ((itemDisplay == null || !itemDisplay.getUniqueId().equals(entity.getUniqueId()))
					&& (infoDisplay == null || !infoDisplay.getUniqueId().equals(entity.getUniqueId()))
					&& (itemLabelDisplay == null
					|| !itemLabelDisplay.getUniqueId().equals(entity.getUniqueId()))) {
				entity.remove();
			}
		}
	}

	private void queueRender(VenueDisplayState state, boolean allowDisabled) {
		Objects.requireNonNull(state, "state");
		latest.set(state);
		long revision = updateRevision.incrementAndGet();
		scheduler.runSyncTask(() -> {
			if (revision != updateRevision.get() || latest.get() != state) {
				return;
			}
			renderNow(state, allowDisabled);
		});
	}

	private void triggerPreviewDeal(long previewRevision) {
		if (previewWindow.isCurrent(previewRevision)) {
			auctioneerNpcFeedback.signalDeal();
		}
	}

	private void tickPreview(long previewRevision) {
		VenueDisplayState state = previewWindow.advance(previewRevision);
		if (state != null) {
			queueRender(state, true);
		}
	}

	private void finishPreview(long previewRevision) {
		PreviewExpiration expiration = previewWindow.expire(previewRevision);
		if (!expiration.applied()) {
			return;
		}
		CancellableTask countdownTask = previewCountdownTask;
		previewCountdownTask = null;
		if (countdownTask != null) {
			countdownTask.cancel();
		}
		VenueDisplayState restored = expiration.restoredState();
		if (restored != null) {
			queueRender(restored, false);
			return;
		}
		latest.set(null);
		updateRevision.incrementAndGet();
		removeOwnedDisplaysNow();
	}

	private void renderNow(VenueDisplayState state, boolean allowDisabled) {
		if (!allowDisabled && !venueConfig.isEnabled()) {
			removeOwnedDisplaysNow();
			return;
		}
		VenueResolution resolution = venueConfig.resolve();
		if (!resolution.validation().valid()) {
			removeOwnedDisplaysNow();
			logger.warning("Cannot render immersive auction venue: "
					+ resolution.validation().summary());
			return;
		}

		VenueLayout layout = resolution.resolvedLayout().orElseThrow();
		ensureDisplays(layout);
		ItemDisplay itemEntity = itemDisplay;
		TextDisplay infoEntity = infoDisplay;
		TextDisplay itemLabelEntity = itemLabelDisplay;
		if (itemEntity == null || infoEntity == null) {
			return;
		}

		ItemStack renderedItem = state.item();
		boolean itemVisible = isItemVisible(state, renderedItem);
		itemEntity.setItemStack(itemVisible ? renderedItem : new ItemStack(Material.AIR));
		applyItemScale(itemEntity, layout.itemScale());
		applyBrightness(itemEntity, venueConfig.itemBlockLight(), venueConfig.itemSkyLight());
		applyTextScale(infoEntity, venueConfig.infoDisplayScale());
		applyBrightness(infoEntity, venueConfig.infoBlockLight(), venueConfig.infoSkyLight());
		infoEntity.setLineWidth(venueConfig.infoLineWidth());
		infoEntity.setShadowed(venueConfig.infoShadowed());
		infoEntity.setSeeThrough(venueConfig.infoSeeThrough());
		infoEntity.setBackgroundColor(venueConfig.infoBackgroundColor());
		infoEntity.text(buildInformation(state));
		if (itemLabelEntity != null) {
			applyTextScale(itemLabelEntity, venueConfig.itemLabelScale());
			applyBrightness(itemLabelEntity, venueConfig.itemBlockLight(), venueConfig.itemSkyLight());
			itemLabelEntity.setLineWidth(venueConfig.infoLineWidth());
			itemLabelEntity.setShadowed(venueConfig.infoShadowed());
			itemLabelEntity.setSeeThrough(venueConfig.infoSeeThrough());
			itemLabelEntity.setBackgroundColor(venueConfig.infoBackgroundColor());
			itemLabelEntity.text(itemVisible
					? buildItemLabel(state) : Component.empty());
		}
	}

	private void ensureDisplays(VenueLayout layout) {
		Location itemLocation = layout.itemDisplay();
		Location infoLocation = layout.infoDisplay();
		Location itemLabelLocation = itemLocation.clone().add(0D,
				venueConfig.itemLabelHeight(), 0D);
		if (!usableAt(itemDisplay, itemLocation)) {
			remove(itemDisplay);
			itemDisplay = spawnItemDisplay(itemLocation);
		} else if (!sameCoordinates(itemDisplay.getLocation(), itemLocation)) {
			itemDisplay.teleport(itemLocation);
		}

		if (!usableAt(infoDisplay, infoLocation)) {
			remove(infoDisplay);
			infoDisplay = spawnInfoDisplay(infoLocation);
		} else if (!samePosition(infoDisplay.getLocation(), infoLocation)) {
			infoDisplay.teleport(infoLocation);
		}

		if (venueConfig.itemLabelEnabled()) {
			if (!usableAt(itemLabelDisplay, itemLabelLocation)) {
				remove(itemLabelDisplay);
				itemLabelDisplay = spawnItemLabelDisplay(itemLabelLocation);
			} else if (!samePosition(itemLabelDisplay.getLocation(), itemLabelLocation)) {
				itemLabelDisplay.teleport(itemLabelLocation);
			}
		} else {
			remove(itemLabelDisplay);
			itemLabelDisplay = null;
		}
	}

	private ItemDisplay spawnItemDisplay(Location location) {
		World world = Objects.requireNonNull(location.getWorld(), "item display world");
		ItemDisplay display = world.spawn(location, ItemDisplay.class);
		configureBase(display, ITEM_ROLE);
		display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
		display.setBillboard(Display.Billboard.FIXED);
		display.setInterpolationDuration(4);
		display.setInterpolationDelay(0);
		display.setViewRange(1.5F);
		return display;
	}

	private TextDisplay spawnInfoDisplay(Location location) {
		World world = Objects.requireNonNull(location.getWorld(), "info display world");
		TextDisplay display = world.spawn(location, TextDisplay.class);
		configureBase(display, INFO_ROLE);
		display.setBillboard(Display.Billboard.CENTER);
		display.setAlignment(TextDisplay.TextAlignment.CENTER);
		display.setDefaultBackground(false);
		display.setViewRange(2F);
		return display;
	}

	private TextDisplay spawnItemLabelDisplay(Location location) {
		World world = Objects.requireNonNull(location.getWorld(), "item label world");
		TextDisplay display = world.spawn(location, TextDisplay.class);
		configureBase(display, ITEM_LABEL_ROLE);
		display.setBillboard(Display.Billboard.CENTER);
		display.setAlignment(TextDisplay.TextAlignment.CENTER);
		display.setDefaultBackground(false);
		display.setViewRange(2F);
		return display;
	}

	private void configureBase(Entity entity, String role) {
		entity.setGravity(false);
		entity.setInvulnerable(true);
		entity.setSilent(true);
		entity.setPersistent(true);
		entity.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, role);
	}

	private void applyItemScale(ItemDisplay display, float scale) {
		Transformation transformation = display.getTransformation();
		transformation.getScale().set(scale, scale, scale);
		display.setTransformation(transformation);
	}

	private void applyTextScale(TextDisplay display, float scale) {
		Transformation transformation = display.getTransformation();
		transformation.getScale().set(scale, scale, scale);
		display.setTransformation(transformation);
	}

	private void applyBrightness(Display display, int blockLight, int skyLight) {
		display.setBrightness(new Display.Brightness(blockLight, skyLight));
	}

	private void rotateNow() {
		if (!started.get()) {
			return;
		}
		VenueDisplayState state = latest.get();
		if (state == null || (!previewWindow.active() && !venueConfig.isEnabled())) {
			return;
		}
		ItemDisplay display = itemDisplay;
		if (display == null || !display.isValid() || display.isDead()) {
			renderNow(state, previewWindow.active());
			display = itemDisplay;
		}
		if (display == null || !display.isValid()) {
			return;
		}
		if (!isItemVisible(state, display.getItemStack())) {
			haloTick = 0L;
			return;
		}

		int periodTicks = venueConfig.itemSpinPeriodTicks();
		spinDegrees = (spinDegrees + degreesPerTick(periodTicks)) % 360F;
		Location configured = venueConfig.resolve().resolvedLayout()
				.map(VenueLayout::itemDisplay).orElse(display.getLocation());
		display.setRotation(configured.getYaw() + spinDegrees, configured.getPitch());
		if (venueConfig.itemHaloEnabled()) {
			int interval = venueConfig.itemHaloIntervalTicks();
			if (++haloTick % interval == 0L) {
				spawnItemHalo(display);
			}
		} else {
			haloTick = 0L;
		}
	}

	private boolean isItemVisible(@NotNull VenueDisplayState state, @NotNull ItemStack item) {
		return state.phase() == VenueDisplayPhase.LOT
				&& state.itemAmount() > 0 && item.getType() != Material.AIR;
	}

	static float degreesPerTick(int periodTicks) {
		return 360F / Math.max(1, periodTicks);
	}

	static final class PreviewWindow {
		private VenueDisplayState liveState;
		private VenueDisplayState previewState;
		private long revision;
		private long elapsedSeconds;

		synchronized boolean updateLive(@NotNull VenueDisplayState state) {
			liveState = Objects.requireNonNull(state, "state");
			return previewState != null;
		}

		synchronized long begin(@NotNull VenueDisplayState state) {
			previewState = Objects.requireNonNull(state, "state");
			elapsedSeconds = 0L;
			return ++revision;
		}

		synchronized VenueDisplayState advance(long expectedRevision) {
			if (!isCurrent(expectedRevision)) {
				return null;
			}
			elapsedSeconds++;
			return withElapsedCountdown(previewState, elapsedSeconds);
		}

		synchronized boolean isCurrent(long expectedRevision) {
			return previewState != null && revision == expectedRevision;
		}

		synchronized PreviewExpiration expire(long expectedRevision) {
			if (!isCurrent(expectedRevision)) {
				return new PreviewExpiration(false, null);
			}
			previewState = null;
			return new PreviewExpiration(true, liveState);
		}

		synchronized boolean active() {
			return previewState != null;
		}

		synchronized @NotNull VenueDisplayState current() {
			return Objects.requireNonNull(previewState != null ? previewState : liveState,
					"No venue display state is available");
		}

		synchronized void clear() {
			liveState = null;
			previewState = null;
			elapsedSeconds = 0L;
			revision++;
		}

		private static VenueDisplayState withElapsedCountdown(VenueDisplayState state,
		                                                     long elapsedSeconds) {
			return new VenueDisplayState(state.phase(), state.sessionLabel(), state.lotNumber(),
					state.lotCount(), state.item(), state.itemName(), state.itemAmount(),
					decrement(state.lotRemainingSeconds(), elapsedSeconds), state.currentBidText(),
					state.sealed(), decrement(state.sessionRemainingSeconds(), elapsedSeconds),
					decrement(state.phaseRemainingSeconds(), elapsedSeconds), state.nextSessionText(),
					state.submittedLots(), state.capacity());
		}

		private static int decrement(int seconds, long elapsedSeconds) {
			if (seconds < 0) {
				return seconds;
			}
			return (int) Math.max(0L, (long) seconds - elapsedSeconds);
		}
	}

	record PreviewExpiration(boolean applied, VenueDisplayState restoredState) {
	}

	private void spawnItemHalo(ItemDisplay display) {
		Location center = display.getLocation();
		World world = center.getWorld();
		if (world == null) {
			return;
		}
		int count = venueConfig.itemHaloParticleCount();
		if (count <= 0) {
			return;
		}
		double radius = venueConfig.itemHaloRadius();
		double height = venueConfig.itemHaloHeight();
		Particle.DustOptions dust = new Particle.DustOptions(
				venueConfig.itemHaloColor(), venueConfig.itemHaloSize());
		for (int index = 0; index < count; index++) {
			double angle = (Math.PI * 2D * index / count) + Math.toRadians(spinDegrees);
			Location point = center.clone().add(Math.cos(angle) * radius, height,
					Math.sin(angle) * radius);
			world.spawnParticle(Particle.DUST, point, 1, 0D, 0D, 0D, 0D, dust);
		}
	}

	private boolean usableAt(Entity entity, Location expected) {
		return entity != null && entity.isValid() && !entity.isDead()
				&& entity.getWorld().getUID().equals(expected.getWorld().getUID());
	}

	private boolean samePosition(Location first, Location second) {
		return sameCoordinates(first, second)
				&& Math.abs(first.getYaw() - second.getYaw()) < 0.01F
				&& Math.abs(first.getPitch() - second.getPitch()) < 0.01F;
	}

	private boolean sameCoordinates(Location first, Location second) {
		return first.getWorld() != null && second.getWorld() != null
				&& first.getWorld().getUID().equals(second.getWorld().getUID())
				&& first.distanceSquared(second) < 0.0001D;
	}

	private void cleanupOrphansNow() {
		for (World world : plugin.getServer().getWorlds()) {
			for (Entity entity : world.getEntities()) {
				if (entity.getPersistentDataContainer().has(roleKey, PersistentDataType.STRING)) {
					entity.remove();
				}
			}
		}
		itemDisplay = null;
		infoDisplay = null;
		itemLabelDisplay = null;
		haloTick = 0L;
	}

	private void removeOwnedDisplaysNow() {
		remove(itemDisplay);
		remove(infoDisplay);
		remove(itemLabelDisplay);
		itemDisplay = null;
		infoDisplay = null;
		itemLabelDisplay = null;
		haloTick = 0L;
	}

	private void remove(Entity entity) {
		if (entity != null && entity.isValid()) {
			entity.remove();
		}
	}

	static @NotNull Component buildItemLabel(@NotNull VenueDisplayState state) {
		return Component.text(state.itemName(), NamedTextColor.WHITE)
				.append(Component.text(" × " + state.itemAmount(), NamedTextColor.GOLD));
	}

	static @NotNull Component buildInformation(@NotNull VenueDisplayState state) {
		Component heading = Component.text("沉浸式拍卖", NamedTextColor.GOLD);
		return switch (state.phase()) {
			case LOT -> heading
					.append(Component.newline())
					.append(Component.text(state.sessionLabel() + "  ·  第 " + state.lotNumber()
							+ "/" + state.lotCount() + " 件", NamedTextColor.YELLOW))
					.append(Component.newline())
					.append(Component.text("当前拍品：" + state.itemName(), NamedTextColor.WHITE))
					.append(Component.newline())
					.append(Component.text("拍卖模式：" + (state.sealed() ? "密封" : "公开"),
							NamedTextColor.AQUA))
					.append(Component.newline())
					.append(Component.text("当前报价：" + (state.sealed() ? "已密封" : state.currentBidText()),
							NamedTextColor.GREEN))
					.append(Component.newline())
					.append(Component.text("本件倒计时：" + formatDuration(state.lotRemainingSeconds()),
							NamedTextColor.RED))
					.append(Component.newline())
					.append(Component.text("整场预计剩余：" + formatDuration(state.sessionRemainingSeconds()),
							NamedTextColor.GRAY));
			case INTERMISSION -> heading
					.append(Component.newline())
					.append(Component.text(state.sessionLabel(), NamedTextColor.YELLOW))
					.append(Component.newline())
					.append(Component.text("已完成 " + state.lotNumber() + "/" + state.lotCount() + " 件",
							NamedTextColor.WHITE))
					.append(Component.newline())
					.append(Component.text("下一件：" + formatDuration(state.phaseRemainingSeconds()),
							NamedTextColor.AQUA))
					.append(Component.newline())
					.append(Component.text("整场预计剩余：" + formatDuration(state.sessionRemainingSeconds()),
							NamedTextColor.GRAY));
			case IDLE -> heading
					.append(Component.newline())
					.append(Component.text("下一场：" + state.nextSessionText(), NamedTextColor.YELLOW))
					.append(Component.newline())
					.append(Component.text("投稿：" + state.submittedLots() + "/" + state.capacity(),
							NamedTextColor.GREEN));
			case BLOCKED -> heading
					.append(Component.newline())
					.append(Component.text(state.sessionLabel(), NamedTextColor.YELLOW))
					.append(Component.newline())
					.append(Component.text("场地配置异常，等待管理员修复", NamedTextColor.RED));
		};
	}

	static @NotNull String formatDuration(int seconds) {
		if (seconds < 0) {
			return "--:--";
		}
		int safe = Math.max(0, seconds);
		int hours = safe / 3_600;
		int minutes = (safe % 3_600) / 60;
		int remainder = safe % 60;
		return hours > 0
				? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainder)
				: String.format(Locale.ROOT, "%02d:%02d", minutes, remainder);
	}
}
