package me.elian.ezauctions.immersive;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import me.elian.ezauctions.controller.AuctionController;
import me.elian.ezauctions.controller.AuctionPlayerController;
import me.elian.ezauctions.controller.ConfigController;
import me.elian.ezauctions.controller.session.AuctionSessionController;
import me.elian.ezauctions.model.Auction;
import me.elian.ezauctions.model.AuctionPlayer;
import me.elian.ezauctions.model.AuctionView;
import me.elian.ezauctions.model.BidOutcome;
import me.elian.ezauctions.model.Money;
import me.elian.ezauctions.scheduler.CancellableTask;
import me.elian.ezauctions.scheduler.TaskScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A private, seat-anchored auction menu made from native displays and left-click hit boxes. */
@Singleton
public final class HolographicBidMenuController implements Listener {
	private static final double DEFAULT_DISTANCE = 2.15D;
	private static final double MIN_DISTANCE = 1.45D;
	private static final double MAX_DISTANCE = 2.60D;
	private static final double DISTANCE_STEP = 0.15D;
	private static final long RESULT_MILLIS = 2_500L;
	private static final long DISTANCE_NOTICE_MILLIS = 1_800L;
	private static final Color TRANSPARENT = Color.fromARGB(0, 0, 0, 0);
	private final Plugin plugin;
	private final TaskScheduler scheduler;
	private final VenueConfig venueConfig;
	private final AttendanceService attendance;
	private final AuctionSessionController auctionSessions;
	private final AuctionController auctions;
	private final AuctionPlayerController players;
	private final ConfigController config;
	private final NamespacedKey markerKey;
	private final Map<UUID, MenuSession> sessions = new HashMap<>();
	private final Map<UUID, MenuButton> buttonHits = new HashMap<>();
	private final Map<UUID, Double> preferredDistances = new HashMap<>();
	private final java.util.Set<UUID> chatInputs = ConcurrentHashMap.newKeySet();
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
	                                     @NotNull ConfigController config) {
		this.plugin = plugin;
		this.scheduler = scheduler;
		this.venueConfig = venueConfig;
		this.attendance = attendance;
		this.auctionSessions = auctionSessions;
		this.auctions = auctions;
		this.players = players;
		this.config = config;
		this.markerKey = new NamespacedKey(plugin, "seated-bid-menu");
	}

	public void bindSeats(@NotNull AuctionStairSeatController seats) {
		this.seats = seats;
	}

	public void start() {
		if (started) return;
		started = true;
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		refreshTask = scheduler.runSyncRepeatingTickTask(plugin, this::refreshAll, 5L, 5L);
	}

	public void shutdown() {
		if (!started) return;
		started = false;
		if (refreshTask != null) {
			refreshTask.cancel();
			refreshTask = null;
		}
		HandlerList.unregisterAll(this);
		for (MenuSession session : List.copyOf(sessions.values())) removeNow(session);
		sessions.clear();
		buttonHits.clear();
		preferredDistances.clear();
		chatInputs.clear();
	}

	/** Called when a player mounts a venue stair. */
	public void show(@NotNull Player player) {
		if (started) {
			plugin.getServer().getScheduler().runTaskLater(plugin, () -> showNow(player), 1L);
		}
	}

	/** Called on dismount, teleport, quit and seat removal. */
	public void hide(@NotNull Player player) {
		chatInputs.remove(player.getUniqueId());
		MenuSession session = sessions.remove(player.getUniqueId());
		if (session != null) removeNow(session);
	}

	private void showNow(Player player) {
		if (!eligible(player)) return;
		hide(player);
		double distance = preferredDistances.getOrDefault(player.getUniqueId(), DEFAULT_DISTANCE);
		MenuSession session = new MenuSession(player.getUniqueId(), menuBase(player, distance), distance);
		try {
			session.header = spawnText(player, position(session, 0D, 0.63D), 0.76F);
			session.detail = spawnText(player, position(session, 0D, -0.14D), 0.78F);
			session.footer = spawnText(player, position(session, 0D, -0.95D), 0.61F);
			sessions.put(player.getUniqueId(), session);
			setStage(player, session, Stage.CHOICES);
			refreshOne(player, session);
			players.getPlayer(player).whenComplete((viewer, error) ->
					scheduler.runPlayerRegionTask(() -> {
						if (sessions.get(player.getUniqueId()) != session) return;
						session.viewer = error == null && viewer != null
								&& viewer.getUniqueId().equals(player.getUniqueId()) ? viewer : null;
						session.loadFailed = error != null || session.viewer == null;
						refreshOne(player, session);
					}, player));
		} catch (RuntimeException error) {
			sessions.remove(player.getUniqueId(), session);
			removeNow(session);
			plugin.getLogger().warning("Could not show seated bid menu for "
					+ player.getUniqueId() + ": " + error.getMessage());
		}
	}

	private void refreshAll() {
		for (MenuSession session : List.copyOf(sessions.values())) {
			Player player = plugin.getServer().getPlayer(session.playerId);
			if (player == null || !eligible(player) || !entitiesValid(session)) {
				if (player != null) {
					hide(player);
					if (eligible(player)) show(player);
				} else {
					sessions.remove(session.playerId);
					removeNow(session);
				}
				continue;
			}
			try {
				refreshOne(player, session);
			} catch (RuntimeException error) {
				hide(player);
				plugin.getLogger().warning("Could not refresh seated bid menu for "
						+ player.getUniqueId() + ": " + error.getMessage());
			}
		}
	}

	private void refreshOne(Player player, MenuSession session) {
		if (!eligible(player) || session.header == null) return;
		MenuData data = menuData(player, session);
		session.model = data.model;
		session.view = data.view;
		if ((session.stage == Stage.CONFIRM || session.stage == Stage.CUSTOM_INPUT)
				&& !sameSelection(session, data.view)) {
			session.notice = "价格或拍品已变化，请重新选择";
			session.noticeUntil = System.currentTimeMillis() + RESULT_MILLIS;
			setStage(player, session, Stage.CHOICES);
		}
		if (session.stage == Stage.RESULT && System.currentTimeMillis() >= session.resultUntil) {
			setStage(player, session, Stage.CHOICES);
		}
		if (session.stage == Stage.CHOICES) {
			if (data.model.auctionId() == null && !session.buttons.isEmpty()) {
				clearButtons(session);
			} else if (data.model.auctionId() != null && session.buttons.isEmpty()) {
				populateChoices(player, session);
			}
		}
		session.header.text(headingText(data.model.heading()));
		switch (session.stage) {
			case CHOICES -> {
				session.detail.text(Component.empty());
				setFooter(session, System.currentTimeMillis() < session.noticeUntil
						? session.notice : data.model.auctionId() == null
							? "滚轮调距  ·  Shift 起身" : "左键选价  ·  滚轮调距  ·  Shift 起身",
						System.currentTimeMillis() < session.noticeUntil ? NamedTextColor.RED : NamedTextColor.GRAY);
			}
			case CUSTOM_INPUT -> {
				session.detail.text(Component.text("在聊天输入你的出价", NamedTextColor.AQUA)
						.append(Component.newline())
						.append(Component.text("输入 cancel 取消", NamedTextColor.WHITE)));
				setFooter(session, System.currentTimeMillis() < session.noticeUntil
						? session.notice : "金额只会用于本次拍品",
						System.currentTimeMillis() < session.noticeUntil ? NamedTextColor.RED : NamedTextColor.GRAY);
			}
			case CONFIRM -> {
				BidProposal proposal = session.proposal;
				if (proposal != null) {
					long held = data.view == null ? 0L : data.view.viewerHighestBidMinor();
					long charge = Math.max(0L, proposal.amountMinor() - held);
					session.detail.text(Component.text(proposal.buyout() ? "确认一口买下" : "确认出价", NamedTextColor.WHITE)
							.append(Component.newline())
							.append(Component.text("$" + Money.format(proposal.amountMinor()), NamedTextColor.AQUA))
							.append(Component.newline())
							.append(Component.text("本次需支付 $" + Money.format(charge), NamedTextColor.GRAY)));
				}
				setFooter(session, "左键确认或返回  ·  滚轮调距  ·  Shift 起身", NamedTextColor.GRAY);
			}
			case SUBMITTING -> {
				session.detail.text(Component.text("正在提交出价…", NamedTextColor.AQUA));
				setFooter(session, "请稍候", NamedTextColor.GRAY);
			}
			case RESULT -> {
				session.detail.text(Component.text(session.resultText,
						session.resultSuccess ? NamedTextColor.AQUA : NamedTextColor.RED));
				setFooter(session, "即将返回选价", NamedTextColor.GRAY);
			}
		}
		if (session.stage != Stage.SUBMITTING && session.stage != Stage.RESULT
				&& System.currentTimeMillis() < session.distanceNoticeUntil
				&& System.currentTimeMillis() >= session.noticeUntil) {
			setFooter(session, "距离 " + String.format(Locale.ROOT, "%.2f", session.distance)
					+ " 格  ·  滚轮继续调整", NamedTextColor.AQUA);
		}
		updateButtons(player, session);
	}

	private MenuData menuData(Player player, MenuSession session) {
		if (!attendance.isInsideVenue(player)) {
			return new MenuData(BidMenuModel.waiting("座位在竞拍范围边缘"), null);
		}
		UUID playerId = player.getUniqueId();
		if (!attendance.isActive(playerId) || attendance.activeSession(playerId).isEmpty()
				|| !attendance.activeSession(playerId).equals(auctionSessions.activeSessionId())) {
			return new MenuData(BidMenuModel.waiting("进入本场拍卖模式后可出价"), null);
		}
		if (session.viewer == null || !session.viewer.getUniqueId().equals(playerId)) {
			return new MenuData(BidMenuModel.waiting(session.loadFailed
					? "玩家数据加载失败" : "正在加载玩家数据"), null);
		}
		Auction active = auctions.getActiveAuction();
		if (active == null) return new MenuData(BidMenuModel.waiting("等待下一件拍品"), null);
		AuctionView view = active.viewFor(session.viewer);
		if (view.sellerId().equals(playerId)) {
			return new MenuData(BidMenuModel.waiting("你是本件拍品的卖家"), null);
		}
		return new MenuData(BidMenuModel.fromView(view), view);
	}

	private boolean sameSelection(MenuSession session, @Nullable AuctionView view) {
		return view != null && view.running() && session.selectedAuctionId != null
				&& session.selectedAuctionId.equals(view.auctionId())
				&& session.selectedRevision == view.revision();
	}

	private void setStage(Player player, MenuSession session, Stage stage) {
		if (session.stage == Stage.CUSTOM_INPUT) chatInputs.remove(session.playerId);
		session.stage = stage;
		if (stage != Stage.CONFIRM && stage != Stage.SUBMITTING) session.proposal = null;
		clearButtons(session);
		switch (stage) {
			case CHOICES -> { }
			case CUSTOM_INPUT -> {
				chatInputs.add(session.playerId);
				addButton(player, session, Control.BACK, null, 0D, -0.56D);
			}
			case CONFIRM -> {
				addButton(player, session, Control.CONFIRM, null, -0.40D, -0.56D);
				addButton(player, session, Control.BACK, null, 0.40D, -0.56D);
			}
			case SUBMITTING, RESULT -> { }
		}
	}

	private void populateChoices(Player player, MenuSession session) {
		BidMenuAction[] choices = BidMenuAction.values();
		for (int index = 0; index < choices.length; index++) {
			int column = index % 2;
			int row = index / 2;
			addButton(player, session, Control.CHOICE, choices[index],
					column == 0 ? -0.40D : 0.40D, 0.02D - row * 0.28D);
		}
	}

	private void addButton(Player player, MenuSession session, Control control,
	                       @Nullable BidMenuAction action, double x, double y) {
		Location center = position(session, x, y);
		TextDisplay label = null;
		HologramRing ring = null;
		Interaction hit = null;
		try {
			label = spawnText(player, center.clone().add(session.right.clone().multiply(0.10D)), 0.58F);
			ring = HologramRing.spawn(plugin, player,
					center.clone().subtract(session.right.clone().multiply(0.26D)));
			hit = player.getWorld().spawn(center.clone().subtract(0D, 0.12D, 0D),
					Interaction.class, entity -> {
						configureEntity(entity, player.getUniqueId() + ":" + control + ":" + action);
						entity.setInteractionWidth(0.70F);
						entity.setInteractionHeight(0.24F);
						entity.setResponsive(true);
					});
			player.showEntity(plugin, hit);
			MenuButton button = new MenuButton(session.playerId, control, action, x, y,
					center, label, ring, hit);
			session.buttons.add(button);
			buttonHits.put(hit.getUniqueId(), button);
		} catch (RuntimeException error) {
			if (hit != null) hit.remove();
			if (ring != null) ring.remove();
			if (label != null) label.remove();
			throw error;
		}
	}

	private void updateButtons(Player player, MenuSession session) {
		MenuButton hovered = hoveredButton(player, session);
		for (MenuButton button : session.buttons) {
			boolean enabled = switch (button.control) {
				case CHOICE -> session.model != null && session.model.enabled(button.action);
				case CONFIRM, BACK -> true;
			};
			String label = switch (button.control) {
				case CHOICE -> session.model == null ? "" : session.model.buttons().get(button.action);
				case CONFIRM -> session.proposal != null && session.proposal.buyout()
						? "确认购买" : "确认出价";
				case BACK -> "返回选价";
			};
			button.label.text(buttonText(label, enabled, button == hovered));
			button.ring.style(enabled, button == hovered, button.control == Control.CONFIRM);
		}
	}

	private @Nullable MenuButton hoveredButton(Player player, MenuSession session) {
		Location eye = player.getEyeLocation();
		Vector ray = eye.getDirection();
		double denominator = ray.dot(session.forward);
		if (denominator <= 0.05D) return null;
		double distance = session.base.toVector().subtract(eye.toVector()).dot(session.forward) / denominator;
		if (distance <= 0D || distance > 3.5D) return null;
		Vector point = eye.toVector().add(ray.multiply(distance));
		MenuButton nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (MenuButton button : session.buttons) {
			Vector offset = point.clone().subtract(button.center.toVector());
			double horizontal = offset.dot(session.right);
			double vertical = offset.getY();
			if (Math.abs(horizontal) <= 0.35D && Math.abs(vertical) <= 0.12D) {
				double distanceSquared = horizontal * horizontal + vertical * vertical;
				if (distanceSquared < nearestDistance) {
					nearest = button;
					nearestDistance = distanceSquared;
				}
			}
		}
		return nearest;
	}

	private Component buttonText(String label, boolean enabled, boolean hovered) {
		if (label.isEmpty()) return Component.empty();
		String[] lines = label.split("\n", 2);
		NamedTextColor caption = !enabled ? NamedTextColor.DARK_GRAY
				: hovered ? NamedTextColor.AQUA : NamedTextColor.WHITE;
		Component result = Component.text(lines[0], caption);
		if (lines.length > 1) {
			result = result.append(Component.newline())
					.append(Component.text(lines[1], enabled ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY));
		}
		return result;
	}

	private Component headingText(String heading) {
		String[] lines = heading.split("\n", -1);
		Component result = Component.text(lines[0], NamedTextColor.GOLD);
		if (lines.length > 1) {
			result = result.append(Component.newline()).append(Component.text(lines[1], NamedTextColor.WHITE));
		}
		if (lines.length > 2) {
			result = result.append(Component.newline()).append(Component.text(lines[2], NamedTextColor.AQUA));
		}
		return result;
	}

	private void setFooter(MenuSession session, String text, NamedTextColor color) {
		if (session.footer != null) session.footer.text(Component.text(text, color));
	}

	private Location menuBase(Player player, double distance) {
		Location eye = player.getEyeLocation();
		Vector forward = eye.getDirection().setY(0D);
		if (forward.lengthSquared() < 0.001D) forward = new Vector(0D, 0D, 1D);
		return eye.add(forward.normalize().multiply(distance)).add(0D, 0.10D, 0D);
	}

	private void moveMenu(MenuSession session, double distance) {
		session.base.add(session.forward.clone().multiply(distance - session.distance));
		session.distance = distance;
		if (session.header != null) session.header.teleport(position(session, 0D, 0.63D));
		if (session.detail != null) session.detail.teleport(position(session, 0D, -0.14D));
		if (session.footer != null) session.footer.teleport(position(session, 0D, -0.95D));
		for (MenuButton button : session.buttons) {
			Location center = position(session, button.x, button.y);
			button.center = center;
			button.label.teleport(center.clone().add(session.right.clone().multiply(0.10D)));
			button.ring.move(center.clone().subtract(session.right.clone().multiply(0.26D)));
			button.hit.teleport(center.clone().subtract(0D, 0.12D, 0D));
		}
	}

	private Location position(MenuSession session, double x, double y) {
		return session.base.clone().add(session.right.clone().multiply(x)).add(0D, y, 0D);
	}

	private TextDisplay spawnText(Player player, Location location, float scale) {
		World world = player.getWorld();
		TextDisplay text = world.spawn(location, TextDisplay.class, display -> {
			configureEntity(display, player.getUniqueId().toString());
			display.setBillboard(Display.Billboard.CENTER);
			display.setAlignment(TextDisplay.TextAlignment.CENTER);
			display.setLineWidth(240);
			display.setShadowed(true);
			display.setSeeThrough(true);
			display.setDefaultBackground(false);
			display.setBackgroundColor(TRANSPARENT);
			display.setBrightness(new Display.Brightness(15, 15));
			display.setViewRange(2F);
			display.setTransformationMatrix(new Matrix4f().scale(scale));
			display.text(Component.empty());
		});
		player.showEntity(plugin, text);
		return text;
	}

	private void configureEntity(Entity entity, String marker) {
		entity.setPersistent(false);
		entity.setVisibleByDefault(false);
		entity.setInvulnerable(true);
		entity.setSilent(true);
		entity.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, marker);
	}

	private boolean eligible(Player player) {
		return started && player.isOnline() && seats != null && seats.isSeated(player)
				&& venueConfig.isReady();
	}

	private boolean entitiesValid(MenuSession session) {
		if (session.header == null || !session.header.isValid()
				|| session.detail == null || !session.detail.isValid()
				|| session.footer == null || !session.footer.isValid()) return false;
		for (MenuButton button : session.buttons) {
			if (!button.hit.isValid() || !button.label.isValid() || !button.ring.valid()) return false;
		}
		return true;
	}

	private void clearButtons(MenuSession session) {
		for (MenuButton button : session.buttons) {
			buttonHits.remove(button.hit.getUniqueId());
			button.hit.remove();
			button.label.remove();
			button.ring.remove();
		}
		session.buttons.clear();
	}

	private void removeNow(MenuSession session) {
		chatInputs.remove(session.playerId);
		clearButtons(session);
		if (session.header != null) session.header.remove();
		if (session.detail != null) session.detail.remove();
		if (session.footer != null) session.footer.remove();
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
	public void onLeftClick(PrePlayerAttackEntityEvent event) {
		MenuButton button = buttonHits.get(event.getAttacked().getUniqueId());
		if (button == null) return;
		event.setCancelled(true);
		Player player = event.getPlayer();
		MenuSession session = sessions.get(player.getUniqueId());
		if (session == null || !button.owner.equals(player.getUniqueId())
				|| !session.buttons.contains(button) || !eligible(player)
				|| player.getOpenInventory().getType() != InventoryType.CRAFTING) return;
		refreshOne(player, session);
		if (!session.buttons.contains(button)) return;
		switch (button.control) {
			case CHOICE -> choose(player, session, button.action);
			case CONFIRM -> confirm(player, session);
			case BACK -> {
				setStage(player, session, Stage.CHOICES);
				refreshOne(player, session);
			}
		}
	}

	private void choose(Player player, MenuSession session, BidMenuAction action) {
		if (session.stage != Stage.CHOICES || session.model == null
				|| !session.model.enabled(action) || session.view == null) return;
		AuctionView view = session.view;
		session.selectedAuctionId = view.auctionId();
		session.selectedRevision = view.revision();
		if (action == BidMenuAction.CUSTOM) {
			setStage(player, session, Stage.CUSTOM_INPUT);
			player.sendMessage(Component.text("在聊天输入出价金额，输入 cancel 取消。", NamedTextColor.AQUA));
		} else {
			try {
				session.proposal = BidProposal.fromAction(action, view);
				setStage(player, session, Stage.CONFIRM);
			} catch (IllegalArgumentException error) {
				session.notice = "该金额已不可用，请重新选择";
				session.noticeUntil = System.currentTimeMillis() + RESULT_MILLIS;
			}
		}
		player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.55F, 1.45F);
		refreshOne(player, session);
	}

	private void confirm(Player player, MenuSession session) {
		if (session.stage != Stage.CONFIRM || session.proposal == null
				|| session.viewer == null || !session.viewer.getUniqueId().equals(player.getUniqueId())) return;
		MenuData latest = menuData(player, session);
		if (!sameSelection(session, latest.view)) {
			session.notice = "价格或拍品已变化，请重新选择";
			session.noticeUntil = System.currentTimeMillis() + RESULT_MILLIS;
			setStage(player, session, Stage.CHOICES);
			refreshOne(player, session);
			return;
		}
		Auction active = auctions.getActiveAuction();
		if (active == null) return;
		BidProposal proposal = session.proposal;
		setStage(player, session, Stage.SUBMITTING);
		refreshOne(player, session);
		active.submitBid(player, session.viewer, proposal.auctionId(), proposal.revision(),
				proposal.amountMinor(), proposal.buyout()).whenComplete((outcome, error) ->
				scheduler.runPlayerRegionTask(() -> {
					if (sessions.get(player.getUniqueId()) != session) return;
					session.resultSuccess = error == null && outcome != null
							&& outcome.status() == BidOutcome.Status.SUCCESS;
					session.resultText = error != null || outcome == null ? "提交失败，请稍后重试"
							: bidResultText(outcome.status(), proposal.buyout());
					session.resultUntil = System.currentTimeMillis() + RESULT_MILLIS;
					setStage(player, session, Stage.RESULT);
					player.playSound(player.getLocation(), session.resultSuccess
							? Sound.ENTITY_EXPERIENCE_ORB_PICKUP : Sound.BLOCK_NOTE_BLOCK_BASS,
							0.75F, session.resultSuccess ? 1.4F : 0.75F);
					refreshOne(player, session);
				}, player));
	}

	@EventHandler(ignoreCancelled = false)
	public void onRightClick(PlayerInteractEntityEvent event) {
		if (buttonHits.containsKey(event.getRightClicked().getUniqueId())) event.setCancelled(true);
	}

	@EventHandler(ignoreCancelled = false)
	public void onDamage(EntityDamageByEntityEvent event) {
		if (buttonHits.containsKey(event.getEntity().getUniqueId())) event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onHotbarScroll(PlayerItemHeldEvent event) {
		Player player = event.getPlayer();
		MenuSession session = sessions.get(player.getUniqueId());
		if (session == null || !eligible(player)
				|| player.getOpenInventory().getType() != InventoryType.CRAFTING) return;
		int direction = wheelDirection(event.getPreviousSlot(), event.getNewSlot());
		if (direction == 0) return;
		event.setCancelled(true);
		double next = Math.max(MIN_DISTANCE,
				Math.min(MAX_DISTANCE, session.distance + direction * DISTANCE_STEP));
		if (Math.abs(next - session.distance) > 0.0001D) {
			moveMenu(session, next);
			preferredDistances.put(player.getUniqueId(), next);
		}
		session.distanceNoticeUntil = System.currentTimeMillis() + DISTANCE_NOTICE_MILLIS;
		refreshOne(player, session);
	}

	static int wheelDirection(int previousSlot, int newSlot) {
		if (previousSlot < 0 || previousSlot > 8 || newSlot < 0 || newSlot > 8) return 0;
		if (newSlot == (previousSlot + 1) % 9) return 1;
		if (newSlot == (previousSlot + 8) % 9) return -1;
		return 0;
	}

	@EventHandler(ignoreCancelled = false)
	public void onChat(AsyncPlayerChatEvent event) {
		UUID playerId = event.getPlayer().getUniqueId();
		if (!chatInputs.contains(playerId)) return;
		event.setCancelled(true);
		String input = event.getMessage();
		scheduler.runPlayerRegionTask(() -> acceptCustomInput(event.getPlayer(), input), event.getPlayer());
	}

	private void acceptCustomInput(Player player, String input) {
		MenuSession session = sessions.get(player.getUniqueId());
		if (session == null || session.stage != Stage.CUSTOM_INPUT || !eligible(player)) return;
		if (input.equalsIgnoreCase("cancel") || input.equals("取消")) {
			setStage(player, session, Stage.CHOICES);
			refreshOne(player, session);
			return;
		}
		MenuData latest = menuData(player, session);
		if (!sameSelection(session, latest.view)) {
			session.notice = "价格或拍品已变化，请重新选择";
			session.noticeUntil = System.currentTimeMillis() + RESULT_MILLIS;
			setStage(player, session, Stage.CHOICES);
			refreshOne(player, session);
			return;
		}
		try {
			long amount = Money.parseMajor(input,
					config.getConfig().getLong("gui.maximum-money-minor", Money.DEFAULT_MAX_MINOR));
			session.proposal = BidProposal.custom(amount, latest.view);
			setStage(player, session, Stage.CONFIRM);
		} catch (IllegalArgumentException error) {
			session.notice = "金额无效或低于最低有效价，请重新输入";
			session.noticeUntil = System.currentTimeMillis() + RESULT_MILLIS;
		}
		refreshOne(player, session);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		preferredDistances.remove(event.getPlayer().getUniqueId());
		hide(event.getPlayer());
	}

	private String bidResultText(BidOutcome.Status status, boolean buyout) {
		return switch (status) {
			case SUCCESS -> buyout ? "购买成功，物品已进入领奖箱" : "出价成功";
			case NO_AUCTION -> "拍卖已经结束";
			case STALE_VIEW -> "价格已变化，请重新选择";
			case BID_PROCESSING -> "另一笔出价正在提交";
			case SELF_BID -> "不能竞拍自己的物品";
			case SESSION_NOT_RUNNING -> "当前没有正在进行的拍卖场次";
			case NOT_PARTICIPANT -> "请先进入本场拍卖模式";
			case NOT_IN_VENUE, OUTSIDE_BOUNDARY -> "你不在允许竞拍的区域";
			case BLOCKED_WORLD, WRONG_WORLD -> "当前世界不能参与这场拍卖";
			case TOO_LOW -> "出价已低于最新最低有效价";
			case NO_BUYOUT -> "该拍卖没有一口价";
			case MAX_BIDS -> "密封拍卖出价次数已用完";
			case CONSECUTIVE_LIMIT -> "连续出价次数已达上限";
			case INSUFFICIENT_FUNDS -> "余额不足";
			case ECONOMY_FAILED -> "经济插件拒绝了交易";
			case EVENT_CANCELLED -> "出价被服务器规则取消";
			case PERSISTENCE_FAILED -> "出价未能保存，请核对余额并联系管理员";
			case INVALID_AMOUNT -> "金额不合法或超出上限";
		};
	}

	private enum Stage { CHOICES, CUSTOM_INPUT, CONFIRM, SUBMITTING, RESULT }
	private enum Control { CHOICE, CONFIRM, BACK }
	private record MenuData(BidMenuModel model, @Nullable AuctionView view) { }

	private static final class MenuButton {
		private final UUID owner;
		private final Control control;
		private final @Nullable BidMenuAction action;
		private final double x;
		private final double y;
		private Location center;
		private final TextDisplay label;
		private final HologramRing ring;
		private final Interaction hit;

		private MenuButton(UUID owner, Control control, @Nullable BidMenuAction action,
		                   double x, double y, Location center,
		                   TextDisplay label, HologramRing ring, Interaction hit) {
			this.owner = owner;
			this.control = control;
			this.action = action;
			this.x = x;
			this.y = y;
			this.center = center;
			this.label = label;
			this.ring = ring;
			this.hit = hit;
		}
	}

	private static final class MenuSession {
		private final UUID playerId;
		private final Location base;
		private final Vector forward;
		private final Vector right;
		private final List<MenuButton> buttons = new ArrayList<>();
		private double distance;
		private long distanceNoticeUntil;
		private @Nullable TextDisplay header;
		private @Nullable TextDisplay detail;
		private @Nullable TextDisplay footer;
		private @Nullable AuctionPlayer viewer;
		private @Nullable BidMenuModel model;
		private @Nullable AuctionView view;
		private @Nullable BidProposal proposal;
		private @Nullable UUID selectedAuctionId;
		private long selectedRevision;
		private Stage stage = Stage.CHOICES;
		private boolean loadFailed;
		private String notice = "";
		private long noticeUntil;
		private String resultText = "";
		private boolean resultSuccess;
		private long resultUntil;

		private MenuSession(UUID playerId, Location base, double distance) {
			this.playerId = playerId;
			this.base = base;
			this.distance = distance;
			Vector horizontal = base.getDirection().setY(0D);
			if (horizontal.lengthSquared() < 0.001D) horizontal = new Vector(0D, 0D, 1D);
			this.forward = horizontal.normalize();
			this.right = new Vector(-forward.getZ(), 0D, forward.getX());
		}
	}
}
