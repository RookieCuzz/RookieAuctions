package me.elian.ezauctions.immersive;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.elian.ezauctions.controller.AuctionController;
import me.elian.ezauctions.controller.AuctionPlayerController;
import me.elian.ezauctions.controller.session.AuctionSessionController;
import me.elian.ezauctions.gui.AuctionGuiController;
import me.elian.ezauctions.model.Auction;
import me.elian.ezauctions.model.AuctionPlayer;
import me.elian.ezauctions.model.AuctionView;
import me.elian.ezauctions.scheduler.CancellableTask;
import me.elian.ezauctions.scheduler.TaskScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-player TextDisplay menu with native Interaction hit boxes, shown while sitting in the venue. */
@Singleton
public final class HolographicBidMenuController implements Listener {
	private static final double DISTANCE = 1.65D;
	private static final double HEADER_OFFSET = 0.45D;
	private static final double FIRST_BUTTON_OFFSET = 0.11D;
	private static final double BUTTON_SPACING = 0.245D;
	private static final Color HEADER_BACKGROUND = Color.fromARGB(0xC0201720);
	private static final Color BUTTON_BACKGROUND = Color.fromARGB(0xB0182226);
	private final Plugin plugin;
	private final TaskScheduler scheduler;
	private final VenueConfig venueConfig;
	private final AttendanceService attendance;
	private final AuctionSessionController auctionSessions;
	private final AuctionController auctions;
	private final AuctionPlayerController players;
	private final AuctionGuiController gui;
	private final NamespacedKey markerKey;
	private final Map<UUID, MenuSession> sessions = new HashMap<>();
	private final Map<UUID, ButtonHit> buttonHits = new HashMap<>();
	private @Nullable AuctionStairSeatController seats;
	private @Nullable CancellableTask refreshTask;
	private boolean started;

	@Inject
	public HolographicBidMenuController(@NotNull Plugin plugin, @NotNull TaskScheduler scheduler,
	                                     @NotNull VenueConfig venueConfig,
	                                     @NotNull AttendanceService attendance,
	                                     @NotNull AuctionSessionController auctionSessions,
	                                     @NotNull AuctionController auctions,
	                                     @NotNull AuctionPlayerController players,
	                                     @NotNull AuctionGuiController gui) {
		this.plugin = plugin;
		this.scheduler = scheduler;
		this.venueConfig = venueConfig;
		this.attendance = attendance;
		this.auctionSessions = auctionSessions;
		this.auctions = auctions;
		this.players = players;
		this.gui = gui;
		this.markerKey = new NamespacedKey(plugin, "seated-bid-menu");
	}

	public void bindSeats(@NotNull AuctionStairSeatController seats) {
		this.seats = seats;
	}

	public void start() {
		if (started) {
			return;
		}
		started = true;
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		refreshTask = scheduler.runSyncRepeatingTickTask(plugin, this::refreshAll, 5L, 5L);
	}

	public void shutdown() {
		if (!started) {
			return;
		}
		started = false;
		if (refreshTask != null) {
			refreshTask.cancel();
			refreshTask = null;
		}
		HandlerList.unregisterAll(this);
		for (MenuSession session : List.copyOf(sessions.values())) {
			removeNow(session);
		}
		sessions.clear();
		buttonHits.clear();
	}

	/** Called by the stair-seat controller after the player successfully mounts the seat. */
	public void show(@NotNull Player player) {
		if (!started) {
			return;
		}
		// Let the passenger position settle before measuring the player's eye location.
		plugin.getServer().getScheduler().runTaskLater(plugin, () -> showNow(player), 1L);
	}

	/** Called by the stair-seat controller on dismount or forced seat removal. */
	public void hide(@NotNull Player player) {
		MenuSession session = sessions.remove(player.getUniqueId());
		if (session != null) {
			removeNow(session);
		}
	}

	private void showNow(Player player) {
		if (!eligible(player)) {
			return;
		}
		hide(player);
		MenuSession session = new MenuSession(player.getUniqueId());
		try {
			Location base = menuBase(player);
			session.header = spawnText(player, base.clone().add(0D, HEADER_OFFSET, 0D), true);
			int index = 0;
			for (BidMenuAction action : BidMenuAction.values()) {
				Location buttonLocation = base.clone().add(0D,
						FIRST_BUTTON_OFFSET - BUTTON_SPACING * index++, 0D);
				TextDisplay text = spawnText(player, buttonLocation, false);
				Interaction hit = spawnHit(player, buttonLocation, action);
				session.buttons.put(action, text);
				session.hits.put(action, hit);
			}
			sessions.put(player.getUniqueId(), session);
			refreshOne(player, session);
			players.getPlayer(player).whenComplete((viewer, error) ->
					scheduler.runPlayerRegionTask(() -> {
						if (sessions.get(player.getUniqueId()) != session) {
							return;
						}
						session.viewer = error == null ? viewer : null;
						session.loadFailed = error != null || viewer == null;
						refreshOne(player, session);
					}, player));
		} catch (RuntimeException error) {
			removeNow(session);
			plugin.getLogger().warning("Could not show seated bid menu for "
					+ player.getUniqueId() + ": " + error.getMessage());
		}
	}

	private void refreshAll() {
		for (MenuSession session : List.copyOf(sessions.values())) {
			Player player = plugin.getServer().getPlayer(session.playerId);
			boolean eligible = player != null && eligible(player);
			if (!eligible || !entitiesValid(session)) {
				if (eligible) {
					hide(player);
					show(player);
				} else if (player != null) {
					hide(player);
				} else {
					sessions.remove(session.playerId);
					removeNow(session);
				}
				continue;
			}
			refreshOne(player, session);
		}
	}

	private void refreshOne(Player player, MenuSession session) {
		if (!eligible(player) || session.header == null) {
			return;
		}
		Location base = menuBase(player);
		if (session.lastBase == null || session.lastBase.distanceSquared(base) > 0.025D) {
			moveEntities(session, base);
			session.lastBase = base;
		}
		BidMenuModel model = modelFor(player, session.viewer, session.loadFailed);
		if (model.equals(session.model)) {
			return;
		}
		session.model = model;
		session.header.text(headingText(model.heading()));
		for (BidMenuAction action : BidMenuAction.values()) {
			TextDisplay text = session.buttons.get(action);
			NamedTextColor color = !model.enabled(action) ? NamedTextColor.GRAY
					: action == BidMenuAction.BUYOUT ? NamedTextColor.RED
					: action == BidMenuAction.CUSTOM ? NamedTextColor.AQUA : NamedTextColor.GREEN;
			text.text(Component.text(model.buttons().get(action), color));
		}
	}

	private BidMenuModel modelFor(Player player, AuctionPlayer viewer, boolean loadFailed) {
		UUID playerId = player.getUniqueId();
		if (!attendance.isInsideVenue(player)) {
			return BidMenuModel.waiting("座位在竞拍范围边缘，请管理员调整边界");
		}
		if (!attendance.isActive(playerId)
				|| attendance.activeSession(playerId).isEmpty()
				|| !attendance.activeSession(playerId).equals(auctionSessions.activeSessionId())) {
			return BidMenuModel.waiting("进入本场拍卖模式后可出价");
		}
		if (viewer == null) {
			return BidMenuModel.waiting(loadFailed ? "玩家数据加载失败" : "正在加载玩家数据");
		}
		Auction active = auctions.getActiveAuction();
		if (active == null) {
			return BidMenuModel.waiting("等待下一件拍品");
		}
		AuctionView view = active.viewFor(viewer);
		if (view.sellerId().equals(playerId)) {
			return BidMenuModel.waiting("你是本件拍品的卖家");
		}
		return BidMenuModel.fromView(view);
	}

	private boolean eligible(Player player) {
		return started && player.isOnline() && seats != null && seats.isSeated(player)
				&& venueConfig.isReady();
	}

	private boolean entitiesValid(MenuSession session) {
		if (session.header == null || !session.header.isValid()) {
			return false;
		}
		for (BidMenuAction action : BidMenuAction.values()) {
			if (!session.buttons.get(action).isValid() || !session.hits.get(action).isValid()) {
				return false;
			}
		}
		return true;
	}

	private Location menuBase(Player player) {
		Location eye = player.getEyeLocation();
		Vector forward = eye.getDirection().setY(0D);
		if (forward.lengthSquared() < 0.001D) {
			forward = new Vector(0D, 0D, 1D);
		}
		return eye.add(forward.normalize().multiply(DISTANCE));
	}

	private TextDisplay spawnText(Player player, Location location, boolean heading) {
		World world = player.getWorld();
		TextDisplay text = world.spawn(location, TextDisplay.class, display -> {
			configureEntity(display, player.getUniqueId().toString());
			display.setBillboard(Display.Billboard.CENTER);
			display.setAlignment(TextDisplay.TextAlignment.CENTER);
			display.setLineWidth(heading ? 320 : 230);
			display.setShadowed(true);
			display.setSeeThrough(false);
			display.setBackgroundColor(heading ? HEADER_BACKGROUND : BUTTON_BACKGROUND);
			display.setViewRange(2F);
			display.text(Component.empty());
		});
		player.showEntity(plugin, text);
		return text;
	}

	private Interaction spawnHit(Player player, Location buttonLocation, BidMenuAction action) {
		Location hitLocation = buttonLocation.clone().subtract(0D, 0.12D, 0D);
		Interaction hit = player.getWorld().spawn(hitLocation, Interaction.class, entity -> {
			configureEntity(entity, player.getUniqueId() + ":" + action.name());
			entity.setInteractionWidth(1.85F);
			entity.setInteractionHeight(0.24F);
			entity.setResponsive(true);
		});
		player.showEntity(plugin, hit);
		buttonHits.put(hit.getUniqueId(), new ButtonHit(player.getUniqueId(), action));
		return hit;
	}

	private void configureEntity(Entity entity, String marker) {
		entity.setPersistent(false);
		entity.setVisibleByDefault(false);
		entity.setInvulnerable(true);
		entity.setSilent(true);
		entity.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, marker);
	}

	private void moveEntities(MenuSession session, Location base) {
		session.header.teleport(base.clone().add(0D, HEADER_OFFSET, 0D));
		int index = 0;
		for (BidMenuAction action : BidMenuAction.values()) {
			Location row = base.clone().add(0D, FIRST_BUTTON_OFFSET - BUTTON_SPACING * index++, 0D);
			session.buttons.get(action).teleport(row);
			session.hits.get(action).teleport(row.clone().subtract(0D, 0.12D, 0D));
		}
	}

	private Component headingText(String heading) {
		String[] lines = heading.split("\\n", -1);
		Component result = Component.text(lines[0], NamedTextColor.GOLD);
		for (int index = 1; index < lines.length; index++) {
			result = result.append(Component.newline())
					.append(Component.text(lines[index], NamedTextColor.WHITE));
		}
		return result;
	}

	private void removeNow(MenuSession session) {
		for (Interaction hit : session.hits.values()) {
			buttonHits.remove(hit.getUniqueId());
			hit.remove();
		}
		for (TextDisplay text : session.buttons.values()) {
			text.remove();
		}
		if (session.header != null) {
			session.header.remove();
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onInteract(PlayerInteractEntityEvent event) {
		ButtonHit button = buttonHits.get(event.getRightClicked().getUniqueId());
		if (button == null) {
			return;
		}
		event.setCancelled(true);
		Player player = event.getPlayer();
		if (event.getHand() != EquipmentSlot.HAND || !button.playerId.equals(player.getUniqueId())) {
			return;
		}
		MenuSession session = sessions.get(player.getUniqueId());
		if (session == null || !eligible(player) || session.model == null
				|| player.getOpenInventory().getType() != InventoryType.CRAFTING
				|| !session.model.enabled(button.action) || session.model.auctionId() == null) {
			return;
		}
		gui.openHologramBidChoice(player, button.action, session.model.auctionId(),
				session.model.revision());
	}

	@EventHandler(ignoreCancelled = true)
	public void onDamage(EntityDamageByEntityEvent event) {
		if (buttonHits.containsKey(event.getEntity().getUniqueId())) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		hide(event.getPlayer());
	}

	private record ButtonHit(UUID playerId, BidMenuAction action) {
	}

	private static final class MenuSession {
		private final UUID playerId;
		private final EnumMap<BidMenuAction, TextDisplay> buttons = new EnumMap<>(BidMenuAction.class);
		private final EnumMap<BidMenuAction, Interaction> hits = new EnumMap<>(BidMenuAction.class);
		private @Nullable TextDisplay header;
		private @Nullable AuctionPlayer viewer;
		private @Nullable BidMenuModel model;
		private @Nullable Location lastBase;
		private boolean loadFailed;

		private MenuSession(UUID playerId) {
			this.playerId = playerId;
		}
	}
}
