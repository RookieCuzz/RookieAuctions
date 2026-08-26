package me.elian.ezauctions.gui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.elian.ezauctions.Logger;
import me.elian.ezauctions.controller.AuctionController;
import me.elian.ezauctions.controller.AuctionPlayerController;
import me.elian.ezauctions.controller.ConfigController;
import me.elian.ezauctions.controller.RewardController;
import me.elian.ezauctions.controller.session.AuctionSessionController;
import me.elian.ezauctions.data.Database;
import me.elian.ezauctions.event.AuctionStartEvent;
import me.elian.ezauctions.helper.ItemHelper;
import me.elian.ezauctions.immersive.AttendanceService;
import me.elian.ezauctions.model.Auction;
import me.elian.ezauctions.model.AuctionData;
import me.elian.ezauctions.model.AuctionPlayer;
import me.elian.ezauctions.model.AuctionRecord;
import me.elian.ezauctions.model.AuctionRecordStatus;
import me.elian.ezauctions.model.AuctionSubmissionTransaction;
import me.elian.ezauctions.model.AuctionView;
import me.elian.ezauctions.model.BidOutcome;
import me.elian.ezauctions.model.Money;
import me.elian.ezauctions.model.RewardKind;
import me.elian.ezauctions.model.RewardRecord;
import me.elian.ezauctions.model.RewardState;
import me.elian.ezauctions.model.SubmissionTransactionState;
import me.elian.ezauctions.scheduler.CancellableTask;
import me.elian.ezauctions.scheduler.TaskScheduler;
import me.elian.ezauctions.session.AuctionSessionView;
import me.elian.ezauctions.session.AttendanceState;
import me.elian.ezauctions.session.PlannedSession;
import me.elian.ezauctions.session.ReservationStatus;
import me.elian.ezauctions.session.SessionState;
import me.elian.ezauctions.session.SubmissionResult;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inventory-only auction UX. Every actionable holder carries the auction ID and state revision, but all mutations
 * are revalidated against the server-side state machine.
 */
@Singleton
public final class AuctionGuiController implements Listener {
	private static final int GUI_SIZE = 54;
	private static final int PAGE_SIZE = 27;
	private static final int NAV_CURRENT = 45;
	private static final int NAV_QUEUE = 46;
	private static final int NAV_CREATE = 47;
	private static final int NAV_MY = 48;
	private static final int NAV_MAILBOX = 49;
	private static final int NAV_SETTINGS = 50;
	private static final Material BACKGROUND = Material.BLACK_STAINED_GLASS_PANE;
	private static final Material MUTED = Material.GRAY_STAINED_GLASS_PANE;

	private final Plugin plugin;
	private final AuctionController auctions;
	private final AuctionPlayerController players;
	private final RewardController rewards;
	private final Database database;
	private final Economy economy;
	private final ConfigController config;
	private final TaskScheduler scheduler;
	private final Logger logger;
	private final AttendanceService attendance;
	private final AuctionSessionController auctionSessions;
	private final Map<UUID, GuiSession> sessions = new ConcurrentHashMap<>();
	private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
	private final Map<UUID, Set<UUID>> reminders = new ConcurrentHashMap<>();
	private final Set<UUID> notificationsDisabled = ConcurrentHashMap.newKeySet();
	private final Set<UUID> bossBarsDisabled = ConcurrentHashMap.newKeySet();
	private final CancellableTask refreshTask;

	@Inject
	public AuctionGuiController(Plugin plugin, AuctionController auctions, AuctionPlayerController players,
	                            RewardController rewards, Database database, Economy economy,
	                            ConfigController config, TaskScheduler scheduler, Logger logger,
	                            AttendanceService attendance,
	                            AuctionSessionController auctionSessions) {
		this.plugin = plugin;
		this.auctions = auctions;
		this.players = players;
		this.rewards = rewards;
		this.database = database;
		this.economy = economy;
		this.config = config;
		this.scheduler = scheduler;
		this.logger = logger;
		this.attendance = attendance;
		this.auctionSessions = auctionSessions;
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		refreshTask = scheduler.runSyncRepeatingTask(plugin, this::refreshAll, 1, 1);
	}

	/** Opens the compact in-venue panel used by the swap-hands key (default F). */
	public void openBidPanel(@NotNull Player player) {
		if (!attendance.isActive(player.getUniqueId())) {
			signal(player, false, "请先进入正在进行的拍卖场次");
			return;
		}
		GuiSession session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new GuiSession());
		if (session.viewer != null) {
			openBidPanel(player, session);
			return;
		}
		players.getPlayer(player).whenComplete((auctionPlayer, error) ->
				scheduler.runPlayerRegionTask(() -> {
					if (error != null || auctionPlayer == null) {
						signal(player, false, "玩家数据加载失败");
						return;
					}
					session.viewer = auctionPlayer;
					openBidPanel(player, session);
				}, player));
	}

	public void open(@NotNull Player player) {
		GuiSession session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new GuiSession());
		if (session.viewer != null) {
			openCurrent(player, session);
			return;
		}

		openLoading(player, session, "§8深岩竞技场");
		players.getPlayer(player).whenComplete((auctionPlayer, error) -> {
			if (error != null) {
				scheduler.runPlayerRegionTask(() -> signal(player, false, "玩家数据加载失败"), player);
				return;
			}
			session.viewer = auctionPlayer;
			scheduler.runPlayerRegionTask(() -> openCurrent(player, session), player);
		});
	}

	public void shutdown() {
		refreshTask.cancel();
		for (BossBar bossBar : bossBars.values()) {
			bossBar.removeAll();
		}
		bossBars.clear();
		sessions.clear();
	}

	@EventHandler
	public void onClick(InventoryClickEvent event) {
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		AuctionGuiHolder holder = holder(event.getView());
		if (holder == null) {
			return;
		}

		event.setCancelled(true);
		GuiSession session = sessions.get(player.getUniqueId());
		if (session == null || !session.token.equals(holder.getSessionToken())) {
			player.closeInventory();
			return;
		}

		if (event.getRawSlot() >= event.getView().getTopInventory().getSize()
				&& event.getClickedInventory() != null) {
			if (holder.getPage() == GuiPage.WIZARD_ITEM) {
				selectDraftItem(player, session, event.getCurrentItem());
			}
			return;
		}
		if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
			return;
		}

		if (isNavigationPage(holder.getPage()) && handleNavigation(player, session, event.getRawSlot())) {
			return;
		}

		switch (holder.getPage()) {
			case CURRENT -> handleCurrentClick(player, session, holder, event.getRawSlot());
			case BID_PANEL -> handleBidPanelClick(player, session, holder, event.getRawSlot());
			case BID_CONFIRM -> handleBidConfirmation(player, session, event.getRawSlot());
			case QUEUE -> handleQueueClick(player, session, event.getRawSlot());
			case QUEUE_DETAIL -> handleQueueDetailClick(player, session, event.getRawSlot());
			case CANCEL_CONFIRM -> handleCancelConfirmation(player, session, event.getRawSlot());
			case WIZARD_ITEM -> handleWizardItemClick(player, session, event.getRawSlot());
			case WIZARD_MODE -> handleWizardModeClick(player, session, event.getRawSlot());
			case WIZARD_PRICE -> handleWizardPriceClick(player, session, event.getRawSlot());
			case WIZARD_REVIEW -> handleWizardReviewClick(player, session, event.getRawSlot());
			case MY_AUCTIONS -> handleMyAuctionsClick(player, session, event.getRawSlot());
			case MY_DETAIL -> handleMyDetailClick(player, session, event.getRawSlot());
			case MAILBOX -> handleMailboxClick(player, session, event.getRawSlot());
			case SETTINGS -> handleSettingsClick(player, session, event.getRawSlot());
			case ANVIL_INPUT -> handleAnvilClick(player, session, event);
		}
	}

	@EventHandler
	public void onDrag(InventoryDragEvent event) {
		if (holder(event.getView()) != null) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onPrepareAnvil(PrepareAnvilEvent event) {
		if (!(event.getInventory().getHolder() instanceof AuctionGuiHolder holder)
				|| holder.getPage() != GuiPage.ANVIL_INPUT) {
			return;
		}
		AnvilInventory inventory = event.getInventory();
		ItemStack left = inventory.getItem(0);
		if (left == null) {
			return;
		}
		ItemStack output = left.clone();
		String renameText = inventory.getRenameText();
		if (renameText != null) {
			ItemMeta meta = output.getItemMeta();
			meta.setDisplayName(renameText);
			output.setItemMeta(meta);
		}
		event.setResult(output);
		inventory.setRepairCost(0);
		inventory.setRepairCostAmount(0);
		inventory.setMaximumRepairCost(Integer.MAX_VALUE);
	}

	@EventHandler
	public void onClose(InventoryCloseEvent event) {
		if (!(event.getPlayer() instanceof Player player)
				|| !(event.getInventory().getHolder() instanceof AuctionGuiHolder)) {
			return;
		}
		scheduler.runPlayerRegionTask(() -> updateBossBar(player), player);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		UUID playerId = event.getPlayer().getUniqueId();
		sessions.remove(playerId);
		reminders.remove(playerId);
		BossBar bar = bossBars.remove(playerId);
		if (bar != null) {
			bar.removeAll();
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onAuctionStart(AuctionStartEvent event) {
		UUID auctionId = event.getAuction().getAuctionData().getId();
		for (Map.Entry<UUID, Set<UUID>> entry : reminders.entrySet()) {
			if (!entry.getValue().remove(auctionId)) {
				continue;
			}
			Player player = Bukkit.getPlayer(entry.getKey());
			if (player == null) {
				continue;
			}
			scheduler.runPlayerRegionTask(() -> {
				player.sendTitle("§6你关注的拍卖开始了", "§7打开 /auction 查看", 5, 40, 10);
				player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.9F, 1.15F);
			}, player);
		}
	}

	private void openCurrent(@NotNull Player player, @NotNull GuiSession session) {
		session.page = GuiPage.CURRENT;
		session.listPage = 0;
		Auction active = auctions.getActiveAuction();
		AuctionView view = active == null ? null : active.viewFor(session.viewer);
		Inventory inventory = standardInventory(session, GuiPage.CURRENT, "§8深岩竞技场 · 当前竞拍",
				view == null ? null : view.auctionId(), view == null ? 0L : view.revision());
		populateCurrent(inventory, player, session, view);
		player.openInventory(inventory);
		hideBossBar(player);
	}

	private void populateCurrent(@NotNull Inventory inventory, @NotNull Player player,
	                             @NotNull GuiSession session, @Nullable AuctionView view) {
		fillBase(inventory);
		addNavigation(inventory, GuiPage.CURRENT);
		if (view == null || !view.running()) {
			inventory.setItem(4, GuiItems.item(Material.CLOCK, "&c暂无进行中的拍卖",
					"&7你可以查看等待队列，", "&7或发起一场新拍卖。"));
			inventory.setItem(22, GuiItems.item(Material.BARRIER, "&7拍卖席空闲"));
			for (int slot = 36; slot <= 41; slot++) {
				inventory.setItem(slot, GuiItems.item(MUTED, "&8当前不可操作"));
			}
			return;
		}

		AuctionGuiHolder holder = (AuctionGuiHolder) inventory.getHolder();
		holder.updateState(view.auctionId(), view.revision());
		long balance = safeBalance(player);
		boolean urgency = view.remainingSeconds() <= 10;
		inventory.setItem(1, GuiItems.item(view.sealed() ? Material.ENDER_EYE : Material.GOLDEN_AXE,
				view.sealed() ? "&5密封竞拍" : "&c公开竞拍",
				view.sealed() ? "&7其他玩家的价格将被隐藏" : "&7公开显示当前最高出价"));
		inventory.setItem(4, GuiItems.item(Material.CLOCK,
				(urgency ? "&c&l" : "&6&l") + formatTime(view.remainingSeconds()),
				urgency ? "&c最后十秒" : "&7剩余竞拍时间"));
		inventory.setItem(7, GuiItems.item(Material.EMERALD, "&b余额 &f$" + Money.format(balance)));
		if (urgency) {
			inventory.setItem(3, GuiItems.pane(Material.RED_STAINED_GLASS_PANE));
			inventory.setItem(5, GuiItems.pane(Material.RED_STAINED_GLASS_PANE));
		}

		inventory.setItem(9, playerHead(view.sellerId(), "&b" + view.sellerName(),
				"&7卖家", "&8" + view.sellerId()));
		inventory.setItem(18, GuiItems.item(Material.GOLD_INGOT, "&6起拍价",
				"&e$" + Money.format(view.startingPriceMinor())));
		inventory.setItem(27, GuiItems.item(Material.EMERALD, "&6最低加价",
				"&e$" + Money.format(view.incrementMinor())));
		inventory.setItem(28, GuiItems.item(Material.COMPASS, "&b所在世界", "&f" + view.world()));

		inventory.setItem(13, GuiItems.auctionItem(view.item(), view.amount(),
				"&8拍卖 ID: " + view.auctionId(), "&7总数量: &f" + view.amount()));
		inventory.setItem(31, GuiItems.item(Material.SUNFLOWER,
				view.sealed() ? "&5密封价格" : "&6当前价",
				view.sealed() ? "&7仅显示你的出价信息" : "&e$" + Money.format(view.currentPriceMinor())));

		if (view.sealed()) {
			inventory.setItem(17, GuiItems.item(Material.ENDER_PEARL, "&b你的最高出价",
					view.viewerHighestBidMinor() == 0 ? "&7尚未出价"
							: "&e$" + Money.format(view.viewerHighestBidMinor())));
			inventory.setItem(26, GuiItems.item(Material.PAPER, "&b已出价次数",
					"&f" + view.viewerBidCount()));
			inventory.setItem(35, GuiItems.item(Material.ENDER_EYE, "&b剩余出价机会",
					view.viewerRemainingBidCount() == Integer.MAX_VALUE
							? "&f不限" : "&f" + view.viewerRemainingBidCount()));
		} else {
			inventory.setItem(17, playerHead(view.highestBidderId(), "&c最高出价者",
					view.highestBidderId() == null ? "&7暂无" : "&f" + view.highestBidderName()));
			inventory.setItem(26, GuiItems.item(Material.GOLD_BLOCK, "&6一口价",
					view.autoBuyMinor() == 0 ? "&7未启用" : "&e$" + Money.format(view.autoBuyMinor())));
		}

		long minimum = minimumBid(view);
		if (immersiveEnabled() && !canBidHere(player)) {
			for (int slot = 36; slot <= 40; slot++) {
				inventory.setItem(slot, GuiItems.item(Material.GRAY_CONCRETE, "&8仅可预览",
						attendance.isActive(player.getUniqueId())
								? "&c你已离开会场区域" : "&7请先进入本场拍卖模式"));
			}
			inventory.setItem(41, GuiItems.item(Material.ENDER_PEARL,
					attendance.isActive(player.getUniqueId()) ? "&b返回会场" : "&a进入正在进行的场次",
					"&7只有拍卖模式且位于场内才可出价"));
			return;
		}
		setBidButton(inventory, 36, Material.LIME_CONCRETE, "&a最低有效出价", minimum,
				additionalRequired(view, minimum), balance);
		setBidButton(inventory, 37, Material.EMERALD, "&a+1 档",
				safeAdd(minimum, view.incrementMinor()),
				additionalRequired(view, safeAdd(minimum, view.incrementMinor())), balance);
		setBidButton(inventory, 38, Material.EMERALD, "&a+5 档",
				safeAdd(minimum, safeMultiply(view.incrementMinor(), 5L)),
				additionalRequired(view, safeAdd(minimum, safeMultiply(view.incrementMinor(), 5L))), balance);
		setBidButton(inventory, 39, Material.EMERALD, "&a+10 档",
				safeAdd(minimum, safeMultiply(view.incrementMinor(), 10L)),
				additionalRequired(view, safeAdd(minimum, safeMultiply(view.incrementMinor(), 10L))), balance);
		inventory.setItem(40, GuiItems.item(Material.NAME_TAG, "&b自定义出价", "&7通过铁砧输入金额"));
		if (view.autoBuyMinor() == 0) {
			inventory.setItem(41, GuiItems.item(MUTED, "&8未启用一口价"));
		} else {
			setBidButton(inventory, 41, balance >= additionalRequired(view, view.autoBuyMinor())
							? Material.RED_CONCRETE : Material.GRAY_CONCRETE,
					"&c立即购买", view.autoBuyMinor(),
					additionalRequired(view, view.autoBuyMinor()), balance);
		}
	}

	private void handleCurrentClick(@NotNull Player player, @NotNull GuiSession session,
	                                @NotNull AuctionGuiHolder holder, int slot) {
		if (slot < 36 || slot > 41) {
			return;
		}
		Auction active = auctions.getActiveAuction();
		if (active == null || holder.getAuctionId() == null) {
			openCurrent(player, session);
			return;
		}
		AuctionView view = active.viewFor(session.viewer);
		if (!view.auctionId().equals(holder.getAuctionId()) || view.revision() != holder.getRevision()) {
			signal(player, false, "竞拍状态已变化，请重新确认");
			openCurrent(player, session);
			return;
		}
		if (immersiveEnabled() && !canBidHere(player)) {
			if (slot != 41) {
				signal(player, false, attendance.isActive(player.getUniqueId())
						? "你当前不在拍卖场区域内" : "请先进入本场拍卖模式");
				return;
			}
			if (!canJoinSession(player)) {
				signal(player, false, "你没有进入拍卖场次的权限");
				return;
			}
			CompletableFuture<?> entry = attendance.isActive(player.getUniqueId())
					? attendance.returnToVenue(player)
					: auctionSessions.activeSessionId()
							.map(id -> attendance.enter(player, id))
							.orElseGet(() -> CompletableFuture.completedFuture(null));
			entry.whenComplete((result, error) -> scheduler.runPlayerRegionTask(() -> {
				signal(player, error == null && result != null,
						error == null && result != null ? "已进入拍卖会场，按 F 打开加价面板"
								: "暂时无法进入会场");
				openCurrent(player, session);
			}, player));
			return;
		}

		long minimum = minimumBid(view);
		session.returnPage = GuiPage.CURRENT;
		switch (slot) {
			case 36 -> openBidConfirmation(player, session, view, minimum, false);
			case 37 -> openBidConfirmation(player, session, view,
					safeAdd(minimum, view.incrementMinor()), false);
			case 38 -> openBidConfirmation(player, session, view,
					safeAdd(minimum, safeMultiply(view.incrementMinor(), 5L)), false);
			case 39 -> openBidConfirmation(player, session, view,
					safeAdd(minimum, safeMultiply(view.incrementMinor(), 10L)), false);
			case 40 -> {
				session.selectedAuctionId = view.auctionId();
				session.selectedRevision = view.revision();
				openAnvil(player, session, GuiSession.InputTarget.BID, GuiPage.CURRENT,
						Long.toString(Math.max(1L, minimum / Money.MINOR_UNITS_PER_MAJOR)));
			}
			case 41 -> {
				if (view.autoBuyMinor() > 0) {
					openBidConfirmation(player, session, view, view.autoBuyMinor(), true);
				}
			}
			default -> {
			}
		}
	}

	private void openBidPanel(@NotNull Player player, @NotNull GuiSession session) {
		session.returnPage = GuiPage.BID_PANEL;
		Auction active = auctions.getActiveAuction();
		AuctionView view = active == null ? null : active.viewFor(session.viewer);
		AuctionGuiHolder holder = new AuctionGuiHolder(session.token, GuiPage.BID_PANEL,
				view == null ? null : view.auctionId(), view == null ? 0L : view.revision());
		Inventory inventory = Bukkit.createInventory(holder, 27, "§8沉浸式拍卖 · 加价");
		holder.attach(inventory);
		session.page = GuiPage.BID_PANEL;
		populateBidPanel(inventory, holder, player, view);
		player.openInventory(inventory);
		hideBossBar(player);
	}

	private void populateBidPanel(@NotNull Inventory inventory, @NotNull AuctionGuiHolder holder,
	                              @NotNull Player player, @Nullable AuctionView view) {
		fillBase(inventory);
		if (view == null || !view.running()) {
			holder.updateState(null, 0L);
			inventory.setItem(13, GuiItems.item(Material.BARRIER, "&c当前拍品已结束",
					"&7请等待下一件拍品"));
			inventory.setItem(22, GuiItems.item(Material.ENDER_PEARL, "&b返回会场"));
			inventory.setItem(26, GuiItems.item(Material.OAK_DOOR, "&c退出拍卖模式",
					"&7返回入场前的位置"));
			return;
		}

		holder.updateState(view.auctionId(), view.revision());
		boolean inside = attendance.isInsideVenue(player);
		long balance = safeBalance(player);
		long minimum = minimumBid(view);
		inventory.setItem(0, GuiItems.item(view.sealed() ? Material.ENDER_EYE : Material.GOLDEN_AXE,
				view.sealed() ? "&5密封竞拍" : "&c公开竞拍",
				view.sealed() ? "&7公共信息不会泄露报价" : "&7当前最高价公开显示"));
		inventory.setItem(2, GuiItems.item(Material.CLOCK, "&6本件 " + formatTime(view.remainingSeconds()),
				"&7确认时会再次校验倒计时与修订号"));
		inventory.setItem(4, GuiItems.auctionItem(view.item(), view.amount(),
				"&7卖家: &f" + view.sellerName(), "&8" + view.auctionId()));
		String sessionRemaining = auctionSessions.activeSession()
				.filter(sessionView -> sessionView.estimatedRemainingSeconds().isPresent())
				.map(sessionView -> formatDuration((int) Math.min(Integer.MAX_VALUE,
						sessionView.estimatedRemainingSeconds().getAsLong())))
				.orElse("--:--");
		inventory.setItem(6, GuiItems.item(Material.RECOVERY_COMPASS, "&b整场剩余 " + sessionRemaining,
				"&7按当前拍品、防秒拍与后续间隔动态计算"));
		inventory.setItem(8, GuiItems.item(Material.EMERALD, "&b余额 &f$" + Money.format(balance)));
		inventory.setItem(18, GuiItems.item(view.sealed() ? Material.ENDER_PEARL : Material.SUNFLOWER,
				view.sealed() ? "&5你的密封报价" : "&6当前报价",
				view.sealed()
						? (view.viewerHighestBidMinor() == 0 ? "&7尚未出价"
						: "&e$" + Money.format(view.viewerHighestBidMinor()))
						: "&e$" + Money.format(view.currentPriceMinor()),
				view.sealed() ? "&7次数: &f" + view.viewerBidCount() + "  &7剩余: &f"
						+ (view.viewerRemainingBidCount() == Integer.MAX_VALUE ? "不限"
						: view.viewerRemainingBidCount()) : "&7最低加价: &e$" + Money.format(view.incrementMinor())));

		if (inside) {
			setBidButton(inventory, 10, Material.LIME_CONCRETE, "&a最低有效价", minimum,
					additionalRequired(view, minimum), balance);
			setBidButton(inventory, 11, Material.EMERALD, "&a+1 档",
					safeAdd(minimum, view.incrementMinor()),
					additionalRequired(view, safeAdd(minimum, view.incrementMinor())), balance);
			setBidButton(inventory, 12, Material.EMERALD, "&a+5 档",
					safeAdd(minimum, safeMultiply(view.incrementMinor(), 5L)),
					additionalRequired(view, safeAdd(minimum, safeMultiply(view.incrementMinor(), 5L))), balance);
			setBidButton(inventory, 13, Material.EMERALD, "&a+10 档",
					safeAdd(minimum, safeMultiply(view.incrementMinor(), 10L)),
					additionalRequired(view, safeAdd(minimum, safeMultiply(view.incrementMinor(), 10L))), balance);
			inventory.setItem(14, GuiItems.item(Material.NAME_TAG, "&b自定义价", "&7通过铁砧输入金额"));
			if (view.autoBuyMinor() > 0) {
				setBidButton(inventory, 15, Material.RED_CONCRETE, "&c一口价", view.autoBuyMinor(),
						additionalRequired(view, view.autoBuyMinor()), balance);
			} else {
				inventory.setItem(15, GuiItems.item(MUTED, "&8未启用一口价"));
			}
		} else {
			for (int slot = 10; slot <= 15; slot++) {
				inventory.setItem(slot, GuiItems.item(Material.GRAY_CONCRETE, "&c当前不在会场",
						"&7出价已禁用", "&7点击下方“返回会场”"));
			}
		}
		inventory.setItem(22, GuiItems.item(Material.ENDER_PEARL, "&b返回会场",
				inside ? "&a你当前位于会场内" : "&7点击传送回买家席"));
		inventory.setItem(26, GuiItems.item(Material.OAK_DOOR, "&c退出拍卖模式",
				"&7返回入场前的位置"));
	}

	private void handleBidPanelClick(@NotNull Player player, @NotNull GuiSession session,
	                                 @NotNull AuctionGuiHolder holder, int slot) {
		if (slot == 22) {
			attendance.returnToVenue(player).whenComplete((result, error) ->
					scheduler.runPlayerRegionTask(() -> {
						signal(player, error == null && result != null && result.successful(),
								error == null && result != null && result.successful()
										? "已返回拍卖会场" : "暂时无法返回会场");
						openBidPanel(player, session);
					}, player));
			return;
		}
		if (slot == 26) {
			player.closeInventory();
			attendance.leave(player).whenComplete((result, error) ->
					scheduler.runPlayerRegionTask(() -> signal(player,
						error == null && result != null && result.successful(),
						error == null && result != null && result.successful()
								? "已退出拍卖模式" : "退出失败，请稍后重试"), player));
			return;
		}
		if (slot < 10 || slot > 15) {
			return;
		}
		if (!attendance.isActive(player.getUniqueId()) || !attendance.isInsideVenue(player)) {
			signal(player, false, "你当前不在拍卖场区域内");
			openBidPanel(player, session);
			return;
		}
		Auction active = auctions.getActiveAuction();
		if (active == null || holder.getAuctionId() == null) {
			openBidPanel(player, session);
			return;
		}
		AuctionView view = active.viewFor(session.viewer);
		if (!view.auctionId().equals(holder.getAuctionId()) || view.revision() != holder.getRevision()) {
			signal(player, false, "竞拍状态已变化，请重新确认");
			openBidPanel(player, session);
			return;
		}
		session.returnPage = GuiPage.BID_PANEL;
		long minimum = minimumBid(view);
		switch (slot) {
			case 10 -> openBidConfirmation(player, session, view, minimum, false);
			case 11 -> openBidConfirmation(player, session, view,
					safeAdd(minimum, view.incrementMinor()), false);
			case 12 -> openBidConfirmation(player, session, view,
					safeAdd(minimum, safeMultiply(view.incrementMinor(), 5L)), false);
			case 13 -> openBidConfirmation(player, session, view,
					safeAdd(minimum, safeMultiply(view.incrementMinor(), 10L)), false);
			case 14 -> {
				session.selectedAuctionId = view.auctionId();
				session.selectedRevision = view.revision();
				openAnvil(player, session, GuiSession.InputTarget.BID, GuiPage.BID_PANEL,
						Long.toString(Math.max(1L, minimum / Money.MINOR_UNITS_PER_MAJOR)));
			}
			case 15 -> {
				if (view.autoBuyMinor() > 0) {
					openBidConfirmation(player, session, view, view.autoBuyMinor(), true);
				}
			}
			default -> {
			}
		}
	}

	private void openBidConfirmation(@NotNull Player player, @NotNull GuiSession session,
	                                 @NotNull AuctionView view, long amountMinor, boolean buyout) {
		session.page = GuiPage.BID_CONFIRM;
		session.selectedAuctionId = view.auctionId();
		session.selectedRevision = view.revision();
		session.proposedBidMinor = amountMinor;
		session.proposedBuyout = buyout;
		session.submitting.set(false);
		long balance = safeBalance(player);
		long required = additionalRequired(view, amountMinor);
		Inventory inventory = standardInventory(session, GuiPage.BID_CONFIRM, "§8确认出价",
				view.auctionId(), view.revision());
		fillBase(inventory);
		inventory.setItem(13, GuiItems.auctionItem(view.item(), view.amount()));
		inventory.setItem(22, GuiItems.item(Material.PAPER, "&f出价确认",
				"&7竞拍物品: &f" + readableName(view.item()),
				"&7你的出价: &e$" + Money.format(amountMinor),
				"&7本次追加扣款: &e$" + Money.format(required),
				"&7竞拍后余额: " + (balance >= required ? "&a" : "&c")
						+ "$" + Money.format(balance - required),
				"&7当前剩余: &c" + view.remainingSeconds() + " 秒",
				"&8确认时会重新校验拍卖 ID、价格与余额"));
		inventory.setItem(39, GuiItems.item(Material.ARROW, "&7返回"));
		inventory.setItem(41, GuiItems.item(balance >= required ? Material.LIME_CONCRETE
				: Material.GRAY_CONCRETE, balance >= required ? "&a确认出价" : "&c余额不足",
				"&7首次点击后立即锁定"));
		player.openInventory(inventory);
	}

	private void handleBidConfirmation(@NotNull Player player, @NotNull GuiSession session, int slot) {
		if (slot == 39 && !session.submitting.get()) {
			openBidReturn(player, session);
			return;
		}
		if (slot != 41 || !session.submitting.compareAndSet(false, true)) {
			return;
		}

		Inventory top = player.getOpenInventory().getTopInventory();
		top.setItem(41, GuiItems.item(MUTED, "&8正在提交", "&7请勿重复点击"));
		Auction active = auctions.getActiveAuction();
		if (active == null || session.selectedAuctionId == null) {
			session.submitting.set(false);
			signal(player, false, "拍卖已经结束");
			openBidReturn(player, session);
			return;
		}

		active.submitBid(player, session.viewer, session.selectedAuctionId, session.selectedRevision,
				session.proposedBidMinor, session.proposedBuyout).whenComplete((outcome, error) ->
				scheduler.runPlayerRegionTask(() -> {
					session.submitting.set(false);
					if (error != null || outcome == null) {
						signal(player, false, "提交失败，请稍后重试");
						openBidReturn(player, session);
						return;
					}
					if (outcome.status() == BidOutcome.Status.SUCCESS) {
						signal(player, true, session.proposedBuyout ? "购买成功，物品已进入领奖箱" : "出价成功");
						notifySuccessfulBid(player.getUniqueId());
					} else {
						signal(player, false, bidFailureText(outcome.status()));
					}
					openBidReturn(player, session);
				}, player));
	}

	private void openBidReturn(@NotNull Player player, @NotNull GuiSession session) {
		if (session.returnPage == GuiPage.BID_PANEL && attendance.isActive(player.getUniqueId())) {
			openBidPanel(player, session);
		} else {
			openCurrent(player, session);
		}
	}

	private void openQueue(@NotNull Player player, @NotNull GuiSession session) {
		if (!immersiveEnabled()) {
			openLegacyQueue(player, session);
			return;
		}
		session.page = GuiPage.QUEUE;
		long request = ++session.loadGeneration;
		Inventory inventory = standardInventory(session, GuiPage.QUEUE, "§8深岩竞技场 · 拍卖场次", null, 0L);
		fillBase(inventory);
		addNavigation(inventory, GuiPage.QUEUE);
		session.visibleEntries.clear();
		session.visibleSessions.clear();
		inventory.setItem(22, GuiItems.item(Material.CLOCK, "&b正在加载未来场次"));
		player.openInventory(inventory);
		auctionSessions.futureSessionViews().whenComplete((views, error) ->
				scheduler.runPlayerRegionTask(() -> {
					if (session.page != GuiPage.QUEUE || request != session.loadGeneration) {
						return;
					}
					if (error != null || views == null) {
						inventory.setItem(22, GuiItems.item(Material.BARRIER, "&c场次加载失败",
								"&7请稍后重新打开"));
						return;
					}
					for (int slot : List.of(20, 24, 31)) {
						inventory.setItem(slot, GuiItems.pane(BACKGROUND));
					}
					session.visibleSessions.clear();
					int[] slots = {20, 24};
					for (int index = 0; index < Math.min(slots.length, views.size()); index++) {
						AuctionSessionView view = views.get(index);
						session.visibleSessions.put(slots[index], view.sessionKey());
						inventory.setItem(slots[index], sessionItem(view, "&8点击报名、投稿或查看详情"));
					}
					auctionSessions.activeSession().ifPresent(view -> {
						session.visibleSessions.put(31, view.sessionKey());
						inventory.setItem(31, sessionItem(view, "&a正在进行，点击入场"));
					});
					if (session.visibleSessions.isEmpty()) {
						inventory.setItem(22, GuiItems.item(Material.HOPPER, "&7暂无可用场次"));
					}
				}, player));
	}

	private void handleQueueClick(@NotNull Player player, @NotNull GuiSession session, int slot) {
		if (!immersiveEnabled()) {
			handleLegacyQueueClick(player, session, slot);
			return;
		}
		String sessionId = session.visibleSessions.get(slot);
		if (sessionId != null) {
			session.selectedSessionId = sessionId;
			openQueueDetail(player, session);
		}
	}

	private void openQueueDetail(@NotNull Player player, @NotNull GuiSession session) {
		if (!immersiveEnabled()) {
			openLegacyQueueDetail(player, session);
			return;
		}
		if (session.selectedSessionId == null) {
			openQueue(player, session);
			return;
		}
		session.page = GuiPage.QUEUE_DETAIL;
		long request = ++session.loadGeneration;
		Inventory inventory = standardInventory(session, GuiPage.QUEUE_DETAIL, "§8拍卖场次详情",
				null, 0L);
		fillBase(inventory);
		inventory.setItem(22, GuiItems.item(Material.CLOCK, "&b正在加载场次"));
		inventory.setItem(39, GuiItems.item(Material.ARROW, "&7返回"));
		player.openInventory(inventory);
		CompletableFuture<Optional<AuctionSessionView>> viewFuture =
				auctionSessions.sessionView(session.selectedSessionId);
		var attendanceFuture = database.getAttendance(session.selectedSessionId, player.getUniqueId());
		viewFuture.thenCombine(attendanceFuture, (view, registered) -> Map.entry(view, registered))
				.whenComplete((loaded, error) -> scheduler.runPlayerRegionTask(() -> {
					if (session.page != GuiPage.QUEUE_DETAIL || request != session.loadGeneration) {
						return;
					}
					if (error != null || loaded == null || loaded.getKey().isEmpty()) {
						signal(player, false, "场次已不存在或加载失败");
						openQueue(player, session);
						return;
					}
					AuctionSessionView view = loaded.getKey().orElseThrow();
					session.selectedSessionRegistered = loaded.getValue().filter(record ->
							record.getState() == AttendanceState.REGISTERED
									|| record.getState() == AttendanceState.ACTIVE).isPresent();
					inventory.setItem(13, sessionItem(view));
					inventory.setItem(22, GuiItems.item(Material.PAPER, "&f场次规则",
							"&7每件竞拍: &f" + config.getConfig().getInt("immersive.lot-duration-seconds", 120) + " 秒",
							"&7换品间隔: &f" + config.getConfig().getInt("immersive.intermission-seconds", 10) + " 秒",
							"&7每名卖家上限: &f" + config.getConfig().getInt(
									"immersive.max-lots-per-player-per-session", 2) + " 件"));
					if (view.state() == SessionState.OPEN) {
						inventory.setItem(31, GuiItems.item(canSubmitSession(player)
								? Material.SMITHING_TABLE : MUTED,
								canSubmitSession(player) ? "&a向本场投稿" : "&8无投稿权限",
								"&7空位: &f" + view.remainingCapacity(), "&7点击进入投稿向导"));
						inventory.setItem(41, GuiItems.item(canJoinSession(player)
								? (session.selectedSessionRegistered ? Material.RED_DYE : Material.LIME_DYE)
								: MUTED,
								canJoinSession(player)
										? (session.selectedSessionRegistered ? "&c取消买家报名" : "&a报名成为买家")
										: "&8无入场权限",
								"&7实际开场且在线时自动传送"));
					} else if (view.state() == SessionState.RUNNING) {
						inventory.setItem(41, GuiItems.item(canJoinSession(player)
								? Material.ENDER_PEARL : MUTED,
								canJoinSession(player) ? "&a立即进入会场" : "&8无入场权限",
								"&7进行中允许临时报名并进入"));
					} else {
						inventory.setItem(41, GuiItems.item(MUTED, "&8当前不可报名"));
					}
				}, player));
	}

	private void handleQueueDetailClick(@NotNull Player player, @NotNull GuiSession session, int slot) {
		if (!immersiveEnabled()) {
			handleLegacyQueueDetailClick(player, session, slot);
			return;
		}
		if (slot == 39) {
			openQueue(player, session);
			return;
		}
		if (session.selectedSessionId == null) {
			return;
		}
		if (slot == 31) {
			if (!canSubmitSession(player)) {
				signal(player, false, "你没有向定时场次投稿的权限");
				return;
			}
			session.draft.setDurationSeconds(config.getConfig().getInt(
					"immersive.lot-duration-seconds", 120));
			openWizardItem(player, session);
			return;
		}
		if (slot != 41) {
			return;
		}
		if (!canJoinSession(player)) {
			signal(player, false, "你没有报名或进入拍卖场次的权限");
			return;
		}
		Optional<SessionState> state = auctionSessions.stateOf(session.selectedSessionId);
		if (state.orElse(null) == SessionState.RUNNING) {
			attendance.enter(player, session.selectedSessionId).whenComplete((result, error) ->
					scheduler.runPlayerRegionTask(() -> {
						signal(player, error == null && result != null && result.successful(),
								error == null && result != null && result.successful()
										? "已进入拍卖会场，按 F 打开加价面板" : "暂时无法进入会场");
						if (error == null && result != null && result.successful()) {
							player.closeInventory();
						} else {
							openQueueDetail(player, session);
						}
					}, player));
			return;
		}
		CompletableFuture<?> operation = session.selectedSessionRegistered
				? auctionSessions.unregisterBuyer(session.selectedSessionId, player.getUniqueId())
				: auctionSessions.registerBuyer(session.selectedSessionId, player.getUniqueId());
		operation.whenComplete((result, error) -> scheduler.runPlayerRegionTask(() -> {
			signal(player, error == null, error == null
					? (session.selectedSessionRegistered ? "已取消本场买家报名" : "已报名本场拍卖")
					: "报名状态更新失败");
			openQueueDetail(player, session);
		}, player));
	}

	private void openLegacyQueue(@NotNull Player player, @NotNull GuiSession session) {
		session.page = GuiPage.QUEUE;
		Inventory inventory = standardInventory(session, GuiPage.QUEUE,
				"§8深岩竞技场 · 等待队列", null, 0L);
		fillBase(inventory);
		addNavigation(inventory, GuiPage.QUEUE);
		session.visibleEntries.clear();
		List<AuctionData> queue = auctions.getAuctionQueue();
		session.visibleTotal = queue.size();
		session.listPage = clampPage(session.listPage, session.visibleTotal);
		int start = session.listPage * PAGE_SIZE;
		for (int index = start; index < Math.min(start + PAGE_SIZE, queue.size()); index++) {
			AuctionData data = queue.get(index);
			int slot = 9 + index - start;
			session.visibleEntries.put(slot, data.getId());
			inventory.setItem(slot, GuiItems.auctionItem(data.getItem(), data.getAmount(),
					"&7队列位置: &f#" + (index + 1),
					"&7卖家: &b" + safeName(data.getAuctioneer().getOfflinePlayer()),
					"&7起拍价: &e$" + Money.format(data.getStartingPriceMinor()),
					"&7模式: " + (data.isSealed() ? "&5密封" : "&c公开"),
					"&7预计开始: &f" + formatEta(auctions.estimateStartAtMillis(data.getId())),
					"&8点击查看详情"));
		}
		if (queue.isEmpty()) {
			inventory.setItem(22, GuiItems.item(Material.HOPPER, "&7等待队列为空"));
		}
		pagination(inventory, session.listPage, queue.size());
		player.openInventory(inventory);
	}

	private void handleLegacyQueueClick(@NotNull Player player, @NotNull GuiSession session, int slot) {
		if (slot == 36 && session.listPage > 0) {
			session.listPage--;
			openLegacyQueue(player, session);
			return;
		}
		if (slot == 44 && (session.listPage + 1) * PAGE_SIZE < auctions.getAuctionQueue().size()) {
			session.listPage++;
			openLegacyQueue(player, session);
			return;
		}
		UUID auctionId = session.visibleEntries.get(slot);
		if (auctionId != null) {
			session.selectedAuctionId = auctionId;
			openLegacyQueueDetail(player, session);
		}
	}

	private void openLegacyQueueDetail(@NotNull Player player, @NotNull GuiSession session) {
		AuctionData data = session.selectedAuctionId == null ? null
				: auctions.getQueuedAuction(session.selectedAuctionId);
		if (data == null) {
			signal(player, false, "该拍卖已离开队列");
			openLegacyQueue(player, session);
			return;
		}
		session.page = GuiPage.QUEUE_DETAIL;
		Inventory inventory = standardInventory(session, GuiPage.QUEUE_DETAIL, "§8队列详情",
				data.getId(), 0L);
		fillBase(inventory);
		inventory.setItem(13, GuiItems.auctionItem(data.getItem(), data.getAmount()));
		inventory.setItem(22, GuiItems.item(Material.PAPER, "&f队列详情",
				"&7位置: &f#" + auctions.getQueuePosition(data.getId()),
				"&7卖家: &b" + safeName(data.getAuctioneer().getOfflinePlayer()),
				"&7起拍价: &e$" + Money.format(data.getStartingPriceMinor()),
				"&7模式: " + (data.isSealed() ? "&5密封" : "&c公开"),
				"&7预计开始: &f" + formatEta(auctions.estimateStartAtMillis(data.getId()))));
		inventory.setItem(39, GuiItems.item(Material.ARROW, "&7返回"));
		if (data.getAuctioneer().getUniqueId().equals(player.getUniqueId())) {
			inventory.setItem(41, GuiItems.item(Material.RED_CONCRETE, "&c取消排队",
					"&c上架费不会退还", "&7需要二次确认"));
		} else {
			boolean enabled = reminders.getOrDefault(player.getUniqueId(), Set.of()).contains(data.getId());
			inventory.setItem(41, GuiItems.item(enabled ? Material.LIME_DYE : Material.BELL,
					enabled ? "&a已设置开始提醒" : "&e开始时提醒", "&7点击切换"));
		}
		player.openInventory(inventory);
	}

	private void handleLegacyQueueDetailClick(@NotNull Player player, @NotNull GuiSession session,
	                                          int slot) {
		if (slot == 39) {
			openLegacyQueue(player, session);
			return;
		}
		if (slot != 41 || session.selectedAuctionId == null) {
			return;
		}
		AuctionData data = auctions.getQueuedAuction(session.selectedAuctionId);
		if (data == null) {
			openLegacyQueue(player, session);
			return;
		}
		if (data.getAuctioneer().getUniqueId().equals(player.getUniqueId())) {
			session.returnPage = GuiPage.QUEUE_DETAIL;
			openCancelConfirmation(player, session, data.getItem(), "等待队列", false);
		} else {
			Set<UUID> playerReminders = reminders.computeIfAbsent(player.getUniqueId(),
					ignored -> ConcurrentHashMap.newKeySet());
			if (!playerReminders.add(data.getId())) {
				playerReminders.remove(data.getId());
			}
			openLegacyQueueDetail(player, session);
		}
	}

	private void openCancelConfirmation(@NotNull Player player, @NotNull GuiSession session,
	                                    @NotNull ItemStack item, @NotNull String location,
	                                    boolean listingFeeRefunded) {
		session.page = GuiPage.CANCEL_CONFIRM;
		session.submitting.set(false);
		Inventory inventory = standardInventory(session, GuiPage.CANCEL_CONFIRM, "§8确认取消",
				session.selectedAuctionId, 0L);
		fillBase(inventory);
		inventory.setItem(13, GuiItems.auctionItem(item, item.getAmount()));
		inventory.setItem(22, GuiItems.item(Material.PAPER, "&c取消拍卖",
				"&7当前位置: &f" + location,
				listingFeeRefunded ? "&a上架费将退还" : "&c上架费不会退还",
				"&7物品将进入领奖箱"));
		inventory.setItem(39, GuiItems.item(Material.ARROW, "&7返回"));
		inventory.setItem(41, GuiItems.item(Material.RED_CONCRETE, "&c确认取消"));
		player.openInventory(inventory);
	}

	private void handleCancelConfirmation(@NotNull Player player, @NotNull GuiSession session, int slot) {
		if (slot == 39) {
			if (session.returnPage == GuiPage.QUEUE_DETAIL) {
				openQueueDetail(player, session);
			} else {
				openMyDetail(player, session);
			}
			return;
		}
		if (slot != 41 || session.selectedAuctionId == null
				|| !session.submitting.compareAndSet(false, true)) {
			return;
		}
		if (immersiveEnabled()) {
			UUID auctionId = session.selectedAuctionId;
			database.getSessionLotByAuction(auctionId).thenCompose(optional -> {
				if (optional.isEmpty()) {
					return CompletableFuture.completedFuture(null);
				}
				session.selectedSessionId = optional.get().getSessionId();
				return auctionSessions.withdrawSubmission(optional.get().getSessionId(), auctionId,
						player.getUniqueId());
			}).whenComplete((result, error) -> scheduler.runPlayerRegionTask(() -> {
				session.submitting.set(false);
				boolean withdrawn = error == null && result != null && result.withdrawn();
				signal(player, withdrawn, withdrawn
						? "撤稿成功，物品已进入领奖箱；上架费不退"
						: "场次已锁单或投稿状态已变化");
				openMyAuctions(player, session);
			}, player));
			return;
		}

		boolean cancelled = auctions.cancelQueuedAuction(session.selectedAuctionId, player.getUniqueId()) != null;
		if (!cancelled) {
			cancelled = auctions.cancelActiveAuction(session.selectedAuctionId, player.getUniqueId(), false);
		}
		session.submitting.set(false);
		signal(player, cancelled, cancelled ? "拍卖已取消，物品已进入领奖箱" : "拍卖状态已变化");
		openMyAuctions(player, session);
	}

	private void openWizardItem(@NotNull Player player, @NotNull GuiSession session) {
		session.page = GuiPage.WIZARD_ITEM;
		Inventory inventory = standardInventory(session, GuiPage.WIZARD_ITEM, "§8发起拍卖 · 1/4 选择物品",
				null, 0L);
		fillBase(inventory);
		ItemStack selected = session.draft.getSelectedItem();
		if (selected == null) {
			inventory.setItem(13, GuiItems.item(Material.CHEST, "&b点击下方背包中的物品",
					"&7此步骤不会拿走物品", "&7只保存物品指纹与计划数量"));
		} else {
			inventory.setItem(13, GuiItems.auctionItem(selected, session.draft.getAmount(),
					"&7计划数量: &f" + session.draft.getAmount(),
					"&8最终确认时才会重新检查并移除"));
		}
		inventory.setItem(36, quantityButton("1 件", 1));
		inventory.setItem(37, quantityButton("16 件", 16));
		inventory.setItem(38, quantityButton("32 件", 32));
		inventory.setItem(39, quantityButton("64 件", 64));
		inventory.setItem(40, GuiItems.item(Material.IRON_SWORD, "&b主手全部"));
		inventory.setItem(41, GuiItems.item(Material.CHEST_MINECART, "&b背包中全部同类物品"));
		inventory.setItem(44, GuiItems.item(selected == null ? MUTED : Material.LIME_CONCRETE,
				selected == null ? "&8请先选择物品" : "&a下一步"));
		inventory.setItem(45, GuiItems.item(Material.BARRIER, "&c退出向导"));
		player.openInventory(inventory);
	}

	private void selectDraftItem(@NotNull Player player, @NotNull GuiSession session, @Nullable ItemStack item) {
		if (item == null || item.getType() == Material.AIR) {
			return;
		}
		session.draft.select(item);
		openWizardItem(player, session);
		player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6F, 1.2F);
	}

	private void handleWizardItemClick(@NotNull Player player, @NotNull GuiSession session, int slot) {
		ItemStack selected = session.draft.getSelectedItem();
		if (slot == 45) {
			openCurrent(player, session);
			return;
		}
		if (selected == null) {
			return;
		}
		int amount = switch (slot) {
			case 36 -> 1;
			case 37 -> 16;
			case 38 -> 32;
			case 39 -> 64;
			case 40 -> session.draft.matches(player.getInventory().getItemInMainHand())
					? player.getInventory().getItemInMainHand().getAmount() : 0;
			case 41 -> ItemHelper.getAmountOfItemInInventory(player, selected);
			default -> -1;
		};
		if (amount >= 0) {
			int available = ItemHelper.getAmountOfItemInInventory(player, selected);
			if (amount == 0 || amount > available) {
				signal(player, false, "背包中没有足够的同类物品");
			} else {
				session.draft.setAmount(amount);
				openWizardItem(player, session);
			}
			return;
		}
		if (slot == 44) {
			openWizardMode(player, session);
		}
	}

	private void openWizardMode(@NotNull Player player, @NotNull GuiSession session) {
		session.page = GuiPage.WIZARD_MODE;
		boolean immersive = immersiveEnabled();
		Inventory inventory = standardInventory(session, GuiPage.WIZARD_MODE,
				immersive ? "§8发起拍卖 · 2/4 场次与模式" : "§8发起拍卖 · 2/4 模式与时长",
				null, 0L);
		fillBase(inventory);
		if (immersive) {
			List<PlannedSession> plans = auctionSessions.futureSubmissionSessions();
			if (session.selectedSessionId == null
					|| plans.stream().noneMatch(plan -> plan.key().equals(session.selectedSessionId))) {
				session.selectedSessionId = plans.isEmpty() ? null : plans.get(0).key();
			}
			int[] slots = {10, 16};
			for (int index = 0; index < Math.min(slots.length, plans.size()); index++) {
				PlannedSession plan = plans.get(index);
				boolean selected = plan.key().equals(session.selectedSessionId);
				inventory.setItem(slots[index], GuiItems.item(selected ? Material.LIME_CONCRETE : Material.WRITABLE_BOOK,
						(selected ? "&a" : "&6") + sessionLabel(plan.key()),
						"&7开场: &f" + formatSessionInstant(plan.scheduledStart()),
						"&7锁单: &f" + formatSessionInstant(plan.submissionsLockAt()),
						selected ? "&a已选择" : "&7点击选择"));
			}
			session.draft.setDurationSeconds(config.getConfig().getInt(
					"immersive.lot-duration-seconds", 120));
		}
		inventory.setItem(20, GuiItems.item(session.draft.isSealed() ? Material.GRAY_DYE : Material.LIME_DYE,
				"&c普通公开竞拍", session.draft.isSealed() ? "&7点击选择" : "&a已选择"));
		inventory.setItem(24, GuiItems.item(session.draft.isSealed() ? Material.PURPLE_DYE : Material.GRAY_DYE,
				"&5密封竞拍", session.draft.isSealed() ? "&a已选择" : "&7点击选择",
				"&7隐藏其他玩家的价格和身份"));
		if (immersive) {
			inventory.setItem(31, GuiItems.item(Material.CLOCK, "&b固定竞拍时长",
					"&f" + formatDuration(session.draft.getDurationSeconds()),
					"&7每件时长由服务器统一设置"));
		} else {
			int[] durations = {30, 60, 120, 300};
			for (int index = 0; index < durations.length; index++) {
				int seconds = durations[index];
				inventory.setItem(36 + index, GuiItems.item(session.draft.getDurationSeconds() == seconds
								? Material.LIME_CONCRETE : Material.CLOCK,
						"&b" + formatDuration(seconds),
						session.draft.getDurationSeconds() == seconds ? "&a已选择" : "&7点击选择"));
			}
			inventory.setItem(40, GuiItems.item(Material.NAME_TAG, "&b自定义时长",
					"&7当前: &f" + formatDuration(session.draft.getDurationSeconds())));
		}
		inventory.setItem(45, GuiItems.item(Material.ARROW, "&7上一步"));
		inventory.setItem(53, GuiItems.item(Material.LIME_CONCRETE, "&a下一步"));
		player.openInventory(inventory);
	}

	private void handleWizardModeClick(@NotNull Player player, @NotNull GuiSession session, int slot) {
		if (immersiveEnabled() && (slot == 10 || slot == 16)) {
			List<PlannedSession> plans = auctionSessions.futureSubmissionSessions();
			int index = slot == 10 ? 0 : 1;
			if (index < plans.size()) {
				session.selectedSessionId = plans.get(index).key();
				openWizardMode(player, session);
			}
			return;
		}
		switch (slot) {
			case 20 -> {
				session.draft.setSealed(false);
				openWizardMode(player, session);
			}
			case 24 -> {
				if (!config.getConfig().getBoolean("sealed-auctions.enabled")
						|| !player.hasPermission("ezauctions.auction.start.sealed")) {
					signal(player, false, "你不能发起密封拍卖");
				} else {
					session.draft.setSealed(true);
					openWizardMode(player, session);
				}
			}
			case 36 -> {
				if (!immersiveEnabled()) setDuration(player, session, 30);
			}
			case 37 -> {
				if (!immersiveEnabled()) setDuration(player, session, 60);
			}
			case 38 -> {
				if (!immersiveEnabled()) setDuration(player, session, 120);
			}
			case 39 -> {
				if (!immersiveEnabled()) setDuration(player, session, 300);
			}
			case 40 -> {
				if (!immersiveEnabled()) {
					openAnvil(player, session, GuiSession.InputTarget.DURATION, GuiPage.WIZARD_MODE,
							Integer.toString(session.draft.getDurationSeconds()));
				}
			}
			case 45 -> openWizardItem(player, session);
			case 53 -> {
				if (immersiveEnabled() && session.selectedSessionId == null) {
					signal(player, false, "当前没有可投稿场次");
				} else {
					openWizardPrice(player, session);
				}
			}
			default -> {
			}
		}
	}

	private void setDuration(@NotNull Player player, @NotNull GuiSession session, int seconds) {
		session.draft.setDurationSeconds(seconds);
		openWizardMode(player, session);
	}

	private void openWizardPrice(@NotNull Player player, @NotNull GuiSession session) {
		session.page = GuiPage.WIZARD_PRICE;
		Inventory inventory = standardInventory(session, GuiPage.WIZARD_PRICE, "§8发起拍卖 · 3/4 设置价格",
				null, 0L);
		fillBase(inventory);
		inventory.setItem(20, GuiItems.item(Material.GOLD_INGOT, "&6起拍价",
				"&e$" + Money.format(session.draft.getStartingPriceMinor()), "&7点击输入"));
		inventory.setItem(22, GuiItems.item(Material.EMERALD, "&6每次最低加价",
				"&e$" + Money.format(session.draft.getIncrementMinor()), "&7点击输入"));
		inventory.setItem(24, GuiItems.item(Material.GOLD_BLOCK, "&6一口价",
				session.draft.isAutoBuyEnabled()
						? "&e$" + Money.format(session.draft.getAutoBuyMinor()) : "&7未启用",
				"&7点击输入金额"));
		inventory.setItem(25, GuiItems.item(session.draft.isAutoBuyEnabled()
						? Material.LIME_DYE : Material.GRAY_DYE,
				session.draft.isAutoBuyEnabled() ? "&a一口价已启用" : "&7一口价已关闭",
				"&7点击切换"));

		long fee = listingFee();
		long estimatedFinal = session.draft.isAutoBuyEnabled()
				? session.draft.getAutoBuyMinor() : session.draft.getStartingPriceMinor();
		long tax = Money.percentage(estimatedFinal,
				BigDecimal.valueOf(config.getConfig().getDouble("auctions.fees.tax-percent")));
		inventory.setItem(31, GuiItems.item(Material.PAPER, "&b费用预览",
				"&7上架费: &e$" + Money.format(fee),
				"&7成交税: &e$" + Money.format(tax),
				"&7预计到账: &a$" + Money.format(Math.max(0L, estimatedFinal - tax))));
		inventory.setItem(45, GuiItems.item(Material.ARROW, "&7上一步"));
		inventory.setItem(53, GuiItems.item(Material.LIME_CONCRETE, "&a下一步"));
		player.openInventory(inventory);
	}

	private void handleWizardPriceClick(@NotNull Player player, @NotNull GuiSession session, int slot) {
		switch (slot) {
			case 20 -> openAnvil(player, session, GuiSession.InputTarget.STARTING_PRICE, GuiPage.WIZARD_PRICE,
					Money.format(session.draft.getStartingPriceMinor()));
			case 22 -> openAnvil(player, session, GuiSession.InputTarget.INCREMENT, GuiPage.WIZARD_PRICE,
					Money.format(session.draft.getIncrementMinor()));
			case 24 -> openAnvil(player, session, GuiSession.InputTarget.BUYOUT, GuiPage.WIZARD_PRICE,
					session.draft.isAutoBuyEnabled() ? Money.format(session.draft.getAutoBuyMinor())
							: Money.format(safeAdd(session.draft.getStartingPriceMinor(),
							safeMultiply(session.draft.getIncrementMinor(), 10L))));
			case 25 -> {
				session.draft.setAutoBuyEnabled(!session.draft.isAutoBuyEnabled());
				if (session.draft.isAutoBuyEnabled() && session.draft.getAutoBuyMinor() == 0) {
					session.draft.setAutoBuyMinor(safeAdd(session.draft.getStartingPriceMinor(),
							safeMultiply(session.draft.getIncrementMinor(), 10L)));
				}
				openWizardPrice(player, session);
			}
			case 45 -> openWizardMode(player, session);
			case 53 -> {
				String error = validateDraftPrices(session.draft);
				if (error == null) {
					openWizardReview(player, session);
				} else {
					signal(player, false, error);
				}
			}
			default -> {
			}
		}
	}

	private void openWizardReview(@NotNull Player player, @NotNull GuiSession session) {
		ItemStack selected = session.draft.getSelectedItem();
		if (selected == null) {
			openWizardItem(player, session);
			return;
		}
		session.page = GuiPage.WIZARD_REVIEW;
		session.submitting.set(false);
		Inventory inventory = standardInventory(session, GuiPage.WIZARD_REVIEW, "§8发起拍卖 · 4/4 最终确认",
				null, 0L);
		fillBase(inventory);
		inventory.setItem(13, GuiItems.auctionItem(selected, session.draft.getAmount()));
		boolean immersive = immersiveEnabled();
		int position = auctions.hasActiveAuction() || !auctions.getAuctionQueue().isEmpty()
				? auctions.getAuctionQueue().size() + 1 : 0;
		long estimatedStart = System.currentTimeMillis();
		if (position > 0) {
			long seconds = auctions.getActiveAuction() == null ? 0L
					: auctions.getActiveAuction().getRemainingSeconds();
			for (AuctionData data : auctions.getAuctionQueue()) {
				seconds += data.getStartingAuctionTime()
						+ config.getConfig().getInt("general.time-between");
			}
			estimatedStart += seconds * 1000L;
		}
		inventory.setItem(22, GuiItems.item(Material.PAPER, "&f拍卖摘要",
				"&7数量: &f" + session.draft.getAmount(),
				"&7类型: " + (session.draft.isSealed() ? "&5密封竞拍" : "&c公开竞拍"),
				"&7起拍价: &e$" + Money.format(session.draft.getStartingPriceMinor()),
				"&7一口价: " + (session.draft.isAutoBuyEnabled()
						? "&e$" + Money.format(session.draft.getAutoBuyMinor()) : "&7关闭"),
				"&7持续时间: &f" + formatDuration(session.draft.getDurationSeconds())));
		if (immersive) {
			inventory.setItem(31, GuiItems.item(Material.WRITABLE_BOOK, "&b场次与费用",
					"&7目标场次: &f" + (session.selectedSessionId == null
							? "未选择" : sessionLabel(session.selectedSessionId)),
					"&7上架费: &e$" + Money.format(listingFee()),
					"&7税率: &e" + config.getConfig().getDouble("auctions.fees.tax-percent") + "%",
					"&c锁单前撤稿不退上架费"));
		} else {
			inventory.setItem(31, GuiItems.item(Material.HOPPER, "&b队列与费用",
					"&7上架费: &e$" + Money.format(listingFee()),
					"&7税率: &e" + config.getConfig().getDouble("auctions.fees.tax-percent") + "%",
					position == 0 ? "&a将立即开始" : "&7当前队列位置: &f#" + position,
					"&7预计开始: &f" + formatEta(estimatedStart)));
		}
		inventory.setItem(45, GuiItems.item(Material.ARROW, "&7上一步"));
		inventory.setItem(53, GuiItems.item(Material.LIME_CONCRETE, "&a最终确认",
				"&7此时才会重新检查并移除物品",
				immersive ? "&7随后进入所选定时场次的托管" : "&7随后持久化并加入队列"));
		player.openInventory(inventory);
	}

	private void handleWizardReviewClick(@NotNull Player player, @NotNull GuiSession session, int slot) {
		if (slot == 45 && !session.submitting.get()) {
			openWizardPrice(player, session);
			return;
		}
		if (slot != 53 || !session.submitting.compareAndSet(false, true)) {
			return;
		}
		player.getOpenInventory().getTopInventory().setItem(53,
				GuiItems.item(MUTED, "&8正在创建", "&7请勿重复点击"));
		beginCreateAuction(player, session);
	}

	private void beginCreateAuction(@NotNull Player player, @NotNull GuiSession session) {
		String error = validateDraft(player, session);
		if (error != null) {
			session.submitting.set(false);
			signal(player, false, error);
			openWizardReview(player, session);
			return;
		}

		ItemStack selected = session.draft.getSelectedItem();
		if (selected == null || session.viewer == null) {
			session.submitting.set(false);
			signal(player, false, "投稿快照已失效，请重新选择物品");
			openWizardItem(player, session);
			return;
		}
		int amount = session.draft.getAmount();
		boolean sealed = session.draft.isSealed();
		int duration = session.draft.getDurationSeconds();
		long startingPrice = session.draft.getStartingPriceMinor();
		long increment = session.draft.getIncrementMinor();
		long autoBuy = session.draft.getAutoBuyMinor();
		long fee = listingFee();
		String targetSessionId = immersiveEnabled() ? session.selectedSessionId : null;
		AuctionPlayer seller = session.viewer;
		UUID auctionId = UUID.randomUUID();
		AuctionRecord record = new AuctionRecord(auctionId, player.getUniqueId(), selected,
				amount, sealed, player.getWorld().getName(), startingPrice, increment,
				autoBuy, duration);
		AuctionData data = new AuctionData(record.getId(), seller, selected, amount, duration,
				startingPrice, increment, autoBuy, sealed, player.getWorld().getName());
		try {
			// Metadata extraction is intentionally completed before Vault or inventory mutations.
			data.gatherAdditionalData(logger);
		} catch (RuntimeException metadataError) {
			logger.severe("Could not prepare submitted auction " + auctionId, metadataError);
			session.submitting.set(false);
			signal(player, false, "无法读取物品数据，未扣除物品或费用");
			openWizardReview(player, session);
			return;
		}
		AuctionSubmissionTransaction transaction = new AuctionSubmissionTransaction(
				record.getId(), player.getUniqueId(), targetSessionId, fee, System.currentTimeMillis());
		SubmissionAttempt attempt = new SubmissionAttempt(record, transaction, data, selected,
				amount, fee, targetSessionId);
		database.createAuctionRecord(record).whenComplete((ignored, persistError) ->
				scheduler.runPlayerRegionTask(() -> {
					if (persistError != null) {
						session.submitting.set(false);
						if (player.isOnline()) {
							signal(player, false, "数据库暂时不可用，未扣除物品或费用");
							openWizardReview(player, session);
						}
						return;
					}
					// Persist the journal before reserving capacity so a crash can never leave an
					// untracked RESERVED lot for session bootstrap to count or lock.
					createSubmissionJournal(player, session, attempt);
				}, player));
	}

	private void createSubmissionJournal(@NotNull Player player, @NotNull GuiSession session,
	                                     @NotNull SubmissionAttempt attempt) {
		database.createSubmissionTransaction(attempt.transaction()).whenComplete((transaction, error) ->
				scheduler.runPlayerRegionTask(() -> {
					if (error != null || transaction == null) {
						abortWithoutJournal(player, session, attempt,
								"无法建立投稿事务，未扣除物品或费用");
						return;
					}
					if (attempt.sessionId() == null) {
						commitCreateAuction(player, session, attempt);
						return;
					}
					auctionSessions.reserveSubmission(attempt.sessionId(), attempt.record().getId(),
							attempt.record().getAuctioneerId())
							.whenComplete((reservation, reserveError) -> scheduler.runPlayerRegionTask(() -> {
								if (reserveError != null || reservation == null || !reservation.accepted()) {
									String reason = reserveError == null && reservation != null
											? reservationFailureText(reservation.status())
											: "场次名额预留失败，请稍后重试";
									compensateSubmission(player, session, attempt, reason);
									return;
								}
								commitCreateAuction(player, session, attempt);
							}, player));
				}, player));
	}

	private void commitCreateAuction(@NotNull Player player, @NotNull GuiSession session,
	                                 @NotNull SubmissionAttempt attempt) {
		if (!player.isOnline()) {
			compensateSubmission(player, session, attempt, "玩家已离线，投稿已安全取消");
			return;
		}
		String error = validateSubmissionAttempt(player, attempt);
		if (error != null) {
			compensateSubmission(player, session, attempt, error);
			return;
		}

		if (safeBalance(player) < attempt.feeMinor()) {
			compensateSubmission(player, session, attempt, "余额不足，未扣除物品或费用");
			return;
		}
		if (attempt.feeMinor() <= 0) {
			beginItemEscrow(player, session, attempt, SubmissionTransactionState.PREPARED);
			return;
		}

		transitionSubmission(attempt, SubmissionTransactionState.PREPARED,
				SubmissionTransactionState.FEE_WITHDRAWING, "").whenComplete((prepared, prepareError) ->
				scheduler.runPlayerRegionTask(() -> {
					if (prepareError != null || !Boolean.TRUE.equals(prepared)) {
						compensateSubmission(player, session, attempt,
								"无法持久化扣费意图，未扣除物品或费用");
						return;
					}
					withdrawListingFee(player, session, attempt);
				}, player));
	}

	private void withdrawListingFee(@NotNull Player player, @NotNull GuiSession session,
	                                @NotNull SubmissionAttempt attempt) {
		if (!player.isOnline()) {
			// Vault has not been called yet, so persist a known rejection before compensation;
			// this avoids manufacturing a refund for a fee which was never withdrawn.
			transitionSubmission(attempt, SubmissionTransactionState.FEE_WITHDRAWING,
					SubmissionTransactionState.FAILED, "player disconnected before Vault withdrawal")
					.whenComplete((ignored, error) -> compensateSubmission(player, session, attempt,
							"玩家已离线，投稿已安全取消"));
			return;
		}
		EconomyResponse result;
		try {
			result = economy.withdrawPlayer(player, Money.toMajor(attempt.feeMinor()));
		} catch (RuntimeException economyError) {
			logger.severe("Vault threw while withdrawing listing fee for "
					+ attempt.record().getId(), economyError);
			compensateSubmission(player, session, attempt,
					"扣费结果不确定，系统已生成保守退款");
			return;
		}
		if (result == null || !result.transactionSuccess()) {
			transitionSubmission(attempt, SubmissionTransactionState.FEE_WITHDRAWING,
					SubmissionTransactionState.FAILED, "Vault rejected listing fee")
					.whenComplete((ignored, transitionError) -> scheduler.runPlayerRegionTask(() ->
							compensateSubmission(player, session, attempt,
									"上架费扣款失败，未扣除物品"), player));
			return;
		}

		transitionSubmission(attempt, SubmissionTransactionState.FEE_WITHDRAWING,
				SubmissionTransactionState.FEE_WITHDRAWN, "").whenComplete((withdrawn, error) ->
				scheduler.runPlayerRegionTask(() -> {
					if (error != null || !Boolean.TRUE.equals(withdrawn)) {
						compensateSubmission(player, session, attempt,
								"扣费状态保存失败，费用将退至领奖箱");
						return;
					}
					beginItemEscrow(player, session, attempt,
							SubmissionTransactionState.FEE_WITHDRAWN);
				}, player));
	}

	private void beginItemEscrow(@NotNull Player player, @NotNull GuiSession session,
	                             @NotNull SubmissionAttempt attempt,
	                             @NotNull SubmissionTransactionState expected) {
		transitionSubmission(attempt, expected, SubmissionTransactionState.ITEM_ESCROWING, "")
				.whenComplete((ready, error) -> scheduler.runPlayerRegionTask(() -> {
					if (error != null || !Boolean.TRUE.equals(ready)) {
						compensateSubmission(player, session, attempt,
								"物品托管准备失败，费用将退至领奖箱");
						return;
					}
					removeSubmissionItem(player, session, attempt, expected);
				}, player));
	}

	private void removeSubmissionItem(@NotNull Player player, @NotNull GuiSession session,
	                                  @NotNull SubmissionAttempt attempt,
	                                  @NotNull SubmissionTransactionState rollbackState) {
		if (!player.isOnline()) {
			transitionSubmission(attempt, SubmissionTransactionState.ITEM_ESCROWING,
					rollbackState, "player disconnected before inventory escrow")
					.whenComplete((ignored, error) -> compensateSubmission(player, session, attempt,
							"玩家已离线，投稿已安全取消"));
			return;
		}
		boolean removed;
		try {
			removed = ItemHelper.removeItemFromPlayerInventoryExact(player, attempt.item(),
					attempt.amount());
		} catch (RuntimeException inventoryError) {
			logger.severe("Inventory mutation failed during submission "
					+ attempt.record().getId(), inventoryError);
			compensateSubmission(player, session, attempt,
					"物品托管结果不确定，系统已生成保守补偿");
			return;
		}
		if (!removed) {
			transitionSubmission(attempt, SubmissionTransactionState.ITEM_ESCROWING,
					rollbackState, "inventory validation rejected escrow")
					.whenComplete((ignored, error) -> scheduler.runPlayerRegionTask(() ->
							compensateSubmission(player, session, attempt,
									"物品数量或位置已变化，费用将退至领奖箱"), player));
			return;
		}

		transitionSubmission(attempt, SubmissionTransactionState.ITEM_ESCROWING,
				SubmissionTransactionState.ITEM_ESCROWED, "").whenComplete((escrowed, error) ->
				scheduler.runPlayerRegionTask(() -> {
					if (error != null || !Boolean.TRUE.equals(escrowed)) {
						compensateSubmission(player, session, attempt,
								"托管状态保存失败，物品与费用将退至领奖箱");
						return;
					}
					persistQueuedSubmission(player, session, attempt);
				}, player));
	}

	private void persistQueuedSubmission(@NotNull Player player, @NotNull GuiSession session,
	                                     @NotNull SubmissionAttempt attempt) {
		database.commitSubmissionTransaction(attempt.transaction().getId(),
				System.currentTimeMillis()).whenComplete((committed, persistError) ->
				scheduler.runPlayerRegionTask(() -> {
					if (persistError != null || !Boolean.TRUE.equals(committed)) {
						compensateSubmission(player, session, attempt,
								"持久化失败，物品与费用将退至领奖箱");
						return;
					}
					attempt.record().setStatus(AuctionRecordStatus.QUEUED);
					if (attempt.sessionId() != null) {
						finishSubmission(player, session, attempt, true);
						return;
					}
					scheduler.runSyncTask(() -> {
						try {
							boolean queued = auctions.queueAuction(attempt.data());
							scheduler.runPlayerRegionTask(() ->
									finishLegacySubmission(player, session, attempt, queued), player);
						} catch (RuntimeException queueError) {
							logger.severe("Could not publish legacy submission "
									+ attempt.record().getId(), queueError);
							// The durable QUEUED record is authoritative and will be recovered/migrated.
							scheduler.runPlayerRegionTask(() -> finishLegacySubmission(player, session,
									attempt, true), player);
						}
					});
				}, player));
	}

	private void openMyAuctions(@NotNull Player player, @NotNull GuiSession session) {
		session.page = GuiPage.MY_AUCTIONS;
		long request = ++session.loadGeneration;
		openLoading(player, session, "§8深岩竞技场 · 我的拍卖");
		database.getAuctionRecords(player.getUniqueId()).whenComplete((records, error) ->
				scheduler.runPlayerRegionTask(() -> {
					if (session.page != GuiPage.MY_AUCTIONS || request != session.loadGeneration) {
						return;
					}
					if (error != null) {
						signal(player, false, "拍卖记录加载失败");
						return;
					}
					renderMyAuctions(player, session, records);
				}, player));
	}

	private void renderMyAuctions(@NotNull Player player, @NotNull GuiSession session,
	                              @NotNull List<AuctionRecord> records) {
		Inventory inventory = standardInventory(session, GuiPage.MY_AUCTIONS, "§8深岩竞技场 · 我的拍卖",
				null, 0L);
		fillBase(inventory);
		addNavigation(inventory, GuiPage.MY_AUCTIONS);
		inventory.setItem(0, filterItem(AuctionRecordStatus.ACTIVE, session.myFilter, "正在进行"));
		inventory.setItem(1, filterItem(AuctionRecordStatus.QUEUED, session.myFilter, "等待开始"));
		inventory.setItem(2, filterItem(AuctionRecordStatus.COMPLETED, session.myFilter, "已完成"));
		inventory.setItem(3, filterItem(AuctionRecordStatus.CANCELLED, session.myFilter, "已取消"));

		List<AuctionRecord> filtered = records.stream()
				.filter(record -> record.getStatus() == session.myFilter)
				.toList();
		session.visibleEntries.clear();
		session.visibleTotal = filtered.size();
		session.listPage = clampPage(session.listPage, session.visibleTotal);
		int start = session.listPage * PAGE_SIZE;
		for (int index = start; index < Math.min(start + PAGE_SIZE, filtered.size()); index++) {
			AuctionRecord record = filtered.get(index);
			int slot = 9 + index - start;
			session.visibleEntries.put(slot, record.getId());
			try {
				inventory.setItem(slot, GuiItems.auctionItem(record.getItem(), record.getAmount(),
						"&7状态: &f" + statusName(record.getStatus()),
						"&7最终去向: &f" + record.getItemDestination(),
						"&7最高出价: &e$" + Money.format(record.getFinalPriceMinor()),
						"&7实际到账: &a$" + Money.format(record.getPayoutMinor()),
						"&7税费: &e$" + Money.format(record.getTaxMinor()),
						"&7退款状态: &f" + record.getRefundStatus(),
						"&8点击查看详情"));
			} catch (IOException exception) {
				inventory.setItem(slot, GuiItems.item(Material.BARRIER, "&c记录物品损坏",
						"&8" + record.getId()));
			}
		}
		if (filtered.isEmpty()) {
			inventory.setItem(22, GuiItems.item(Material.BOOK, "&7此筛选下暂无记录"));
		}
		pagination(inventory, session.listPage, filtered.size());
		player.openInventory(inventory);
	}

	private void handleMyAuctionsClick(@NotNull Player player, @NotNull GuiSession session, int slot) {
		if (slot == 36 && session.listPage > 0) {
			session.listPage--;
			openMyAuctions(player, session);
			return;
		}
		if (slot == 44 && (session.listPage + 1) * PAGE_SIZE < session.visibleTotal) {
			session.listPage++;
			openMyAuctions(player, session);
			return;
		}
		AuctionRecordStatus filter = switch (slot) {
			case 0 -> AuctionRecordStatus.ACTIVE;
			case 1 -> AuctionRecordStatus.QUEUED;
			case 2 -> AuctionRecordStatus.COMPLETED;
			case 3 -> AuctionRecordStatus.CANCELLED;
			default -> null;
		};
		if (filter != null) {
			session.myFilter = filter;
			session.listPage = 0;
			openMyAuctions(player, session);
			return;
		}
		UUID recordId = session.visibleEntries.get(slot);
		if (recordId != null) {
			session.selectedAuctionId = recordId;
			openMyDetail(player, session);
		}
	}

	private void openMyDetail(@NotNull Player player, @NotNull GuiSession session) {
		if (session.selectedAuctionId == null) {
			openMyAuctions(player, session);
			return;
		}
		session.page = GuiPage.MY_DETAIL;
		UUID requestedId = session.selectedAuctionId;
		long request = ++session.loadGeneration;
		openLoading(player, session, "§8我的拍卖 · 详情");
		database.getAuctionRecord(requestedId).whenComplete((optional, error) ->
				scheduler.runPlayerRegionTask(() -> {
					if (session.page != GuiPage.MY_DETAIL || request != session.loadGeneration
							|| !requestedId.equals(session.selectedAuctionId)) {
						return;
					}
					if (error != null || optional.isEmpty()) {
						signal(player, false, "记录已不存在");
						openMyAuctions(player, session);
						return;
					}
					renderMyDetail(player, session, optional.get());
				}, player));
	}

	private void renderMyDetail(@NotNull Player player, @NotNull GuiSession session,
	                            @NotNull AuctionRecord record) {
		Inventory inventory = standardInventory(session, GuiPage.MY_DETAIL, "§8我的拍卖 · 详情",
				record.getId(), 0L);
		fillBase(inventory);
		try {
			inventory.setItem(13, GuiItems.auctionItem(record.getItem(), record.getAmount()));
		} catch (IOException exception) {
			inventory.setItem(13, GuiItems.item(Material.BARRIER, "&c物品数据损坏"));
		}
		inventory.setItem(22, GuiItems.item(Material.PAPER, "&f完整记录",
				"&7状态: &f" + statusName(record.getStatus()),
				"&7拍卖类型: " + (record.isSealed() ? "&5密封" : "&c公开"),
				"&7起拍价: &e$" + Money.format(record.getStartingPriceMinor()),
				"&7最高出价: &e$" + Money.format(record.getFinalPriceMinor()),
				"&7实际到账: &a$" + Money.format(record.getPayoutMinor()),
				"&7税费: &e$" + Money.format(record.getTaxMinor()),
				"&7最终去向: &f" + record.getItemDestination(),
				"&7退款状态: &f" + record.getRefundStatus()));
		inventory.setItem(39, GuiItems.item(Material.ARROW, "&7返回"));
		if (record.getStatus() == AuctionRecordStatus.ACTIVE
				|| record.getStatus() == AuctionRecordStatus.QUEUED) {
			inventory.setItem(41, GuiItems.item(Material.RED_CONCRETE, "&c取消拍卖",
					"&c上架费不会退还", "&7需要二次确认"));
		}
		player.openInventory(inventory);
	}

	private void handleMyDetailClick(@NotNull Player player, @NotNull GuiSession session, int slot) {
		if (slot == 39) {
			openMyAuctions(player, session);
			return;
		}
		if (slot != 41 || session.selectedAuctionId == null) {
			return;
		}
		database.getAuctionRecord(session.selectedAuctionId).thenAccept(optional -> optional.ifPresent(record -> {
			if (session.page != GuiPage.MY_DETAIL) {
				return;
			}
			if (record.getStatus() != AuctionRecordStatus.ACTIVE
					&& record.getStatus() != AuctionRecordStatus.QUEUED) {
				return;
			}
			scheduler.runPlayerRegionTask(() -> {
				try {
					session.returnPage = GuiPage.MY_DETAIL;
					openCancelConfirmation(player, session, record.getItem(),
							record.getStatus() == AuctionRecordStatus.ACTIVE ? "正在进行" : "等待队列", false);
				} catch (IOException exception) {
					signal(player, false, "物品数据损坏");
				}
			}, player);
		}));
	}

	private void openMailbox(@NotNull Player player, @NotNull GuiSession session) {
		session.page = GuiPage.MAILBOX;
		long request = ++session.loadGeneration;
		openLoading(player, session, "§8深岩竞技场 · 领奖箱");
		Set<RewardKind> kinds = session.mailboxHistory
				? EnumSet.allOf(RewardKind.class) : EnumSet.of(session.mailboxFilter);
		rewards.getRewards(player.getUniqueId(), kinds, session.mailboxHistory).whenComplete((records, error) ->
				scheduler.runPlayerRegionTask(() -> {
					if (session.page != GuiPage.MAILBOX || request != session.loadGeneration) {
						return;
					}
					if (error != null) {
						signal(player, false, "领奖箱加载失败");
						return;
					}
					List<RewardRecord> visibleRecords = session.mailboxHistory
							? MailboxHistoryView.claimedOnly(records) : records;
					renderMailbox(player, session, visibleRecords);
				}, player));
	}

	private void renderMailbox(@NotNull Player player, @NotNull GuiSession session,
	                           @NotNull List<RewardRecord> records) {
		Inventory inventory = standardInventory(session, GuiPage.MAILBOX, "§8深岩竞技场 · 领奖箱",
				null, 0L);
		fillBase(inventory);
		addNavigation(inventory, GuiPage.MAILBOX);
		inventory.setItem(0, mailboxTab(RewardKind.ITEM, session, "待领取物品", Material.CHEST));
		inventory.setItem(1, mailboxTab(RewardKind.REFUND, session, "待领取退款", Material.GOLD_NUGGET));
		inventory.setItem(2, mailboxTab(RewardKind.INCOME, session, "拍卖收入", Material.EMERALD));
		inventory.setItem(3, GuiItems.item(session.mailboxHistory ? Material.LIME_DYE : Material.BOOK,
				session.mailboxHistory ? "&a领取记录" : "&7领取记录"));
		session.visibleEntries.clear();
		session.visibleTotal = records.size();
		session.listPage = clampPage(session.listPage, session.visibleTotal);
		int start = session.listPage * PAGE_SIZE;
		for (int index = start; index < Math.min(start + PAGE_SIZE, records.size()); index++) {
			RewardRecord reward = records.get(index);
			int slot = 9 + index - start;
			session.visibleEntries.put(slot, reward.getId());
			inventory.setItem(slot, rewardItem(reward, session.mailboxHistory));
		}
		if (records.isEmpty()) {
			inventory.setItem(22, GuiItems.item(Material.CHEST, "&7此分类没有奖励"));
		}
		pagination(inventory, session.listPage, records.size());
		player.openInventory(inventory);
	}

	private void handleMailboxClick(@NotNull Player player, @NotNull GuiSession session, int slot) {
		if (slot == 36 && session.listPage > 0) {
			session.listPage--;
			openMailbox(player, session);
			return;
		}
		if (slot == 44 && (session.listPage + 1) * PAGE_SIZE < session.visibleTotal) {
			session.listPage++;
			openMailbox(player, session);
			return;
		}
		if (slot >= 0 && slot <= 2) {
			session.mailboxFilter = RewardKind.values()[slot];
			session.mailboxHistory = false;
			session.listPage = 0;
			openMailbox(player, session);
			return;
		}
		if (slot == 3) {
			session.mailboxHistory = true;
			session.listPage = 0;
			openMailbox(player, session);
			return;
		}
		UUID rewardId = session.visibleEntries.get(slot);
		if (rewardId == null || session.mailboxHistory || !session.submitting.compareAndSet(false, true)) {
			return;
		}
		rewards.claim(player, rewardId).whenComplete((claimResult, error) ->
				scheduler.runPlayerRegionTask(() -> {
					session.submitting.set(false);
					if (error != null || claimResult == null) {
						signal(player, false, "领取失败，请稍后重试");
					} else {
						signal(player, claimResult == RewardController.ClaimResult.SUCCESS,
								claimResultText(claimResult));
					}
					openMailbox(player, session);
				}, player));
	}

	private void openSettings(@NotNull Player player, @NotNull GuiSession session) {
		session.page = GuiPage.SETTINGS;
		Inventory inventory = standardInventory(session, GuiPage.SETTINGS, "§8深岩竞技场 · 通知与设置",
				null, 0L);
		fillBase(inventory);
		addNavigation(inventory, GuiPage.SETTINGS);
		boolean notifications = !notificationsDisabled.contains(player.getUniqueId());
		boolean bossBar = !bossBarsDisabled.contains(player.getUniqueId());
		inventory.setItem(20, GuiItems.item(notifications ? Material.LIME_DYE : Material.GRAY_DYE,
				notifications ? "&a竞拍声音已开启" : "&7竞拍声音已关闭",
				"&7点击切换新出价与倒计时提示音"));
		inventory.setItem(24, GuiItems.item(bossBar ? Material.LIME_DYE : Material.GRAY_DYE,
				bossBar ? "&aBossBar 已开启" : "&7BossBar 已关闭",
				"&7GUI 关闭后显示当前物品、价格与时间"));
		inventory.setItem(31, GuiItems.item(Material.REDSTONE_TORCH, "&b纯 GUI 模式",
				"&7拍卖消息不会刷入聊天栏",
				"&7所有点击均由服务端重新验证"));
		player.openInventory(inventory);
		hideBossBar(player);
	}

	private void handleSettingsClick(@NotNull Player player, @NotNull GuiSession session, int slot) {
		if (slot == 20) {
			toggle(notificationsDisabled, player.getUniqueId());
			openSettings(player, session);
		} else if (slot == 24) {
			toggle(bossBarsDisabled, player.getUniqueId());
			if (bossBarsDisabled.contains(player.getUniqueId())) {
				hideBossBar(player);
			}
			openSettings(player, session);
		}
	}

	private void openAnvil(@NotNull Player player, @NotNull GuiSession session,
	                       @NotNull GuiSession.InputTarget target, @NotNull GuiPage returnPage,
	                       @NotNull String initialText) {
		session.page = GuiPage.ANVIL_INPUT;
		session.returnPage = returnPage;
		session.inputTarget = target;
		AuctionGuiHolder holder = new AuctionGuiHolder(session.token, GuiPage.ANVIL_INPUT,
				session.selectedAuctionId, session.selectedRevision);
		Inventory inventory = Bukkit.createInventory(holder, org.bukkit.event.inventory.InventoryType.ANVIL,
				"输入数值");
		holder.attach(inventory);
		ItemStack input = GuiItems.item(Material.PAPER, initialText,
				target == GuiSession.InputTarget.DURATION ? "&7输入秒数" : "&7输入货币金额");
		inventory.setItem(0, input);
		player.openInventory(inventory);
	}

	private void handleAnvilClick(@NotNull Player player, @NotNull GuiSession session,
	                              @NotNull InventoryClickEvent event) {
		if (event.getRawSlot() != 2 || event.getCurrentItem() == null
				|| session.inputTarget == null) {
			return;
		}
		if (!(event.getView().getTopInventory() instanceof AnvilInventory inventory)) {
			return;
		}
		String input = inventory.getRenameText();
		GuiSession.InputTarget inputTarget = session.inputTarget;
		try {
			if (input == null || input.isBlank()) {
				throw new IllegalArgumentException();
			}
			if (inputTarget == GuiSession.InputTarget.DURATION) {
				int seconds = Integer.parseInt(input.trim());
				if (!validDuration(seconds)) {
					throw new IllegalArgumentException();
				}
				session.draft.setDurationSeconds(seconds);
				session.inputTarget = null;
				openWizardMode(player, session);
				return;
			}

			long money = Money.parseMajor(input, configuredMaximumMoney());
			if (money <= 0) {
				throw new IllegalArgumentException();
			}
			switch (inputTarget) {
				case BID -> {
					Auction active = auctions.getActiveAuction();
					if (active == null || session.selectedAuctionId == null) {
						session.inputTarget = null;
						openBidReturn(player, session);
						return;
					}
					AuctionView view = active.viewFor(session.viewer);
					if (!view.auctionId().equals(session.selectedAuctionId)
							|| view.revision() != session.selectedRevision) {
						signal(player, false, "竞拍状态已变化，请重新输入");
						session.inputTarget = null;
						openBidReturn(player, session);
						return;
					}
					session.inputTarget = null;
					openBidConfirmation(player, session, view, money, false);
				}
				case STARTING_PRICE -> {
					session.draft.setStartingPriceMinor(money);
					session.inputTarget = null;
					openWizardPrice(player, session);
				}
				case INCREMENT -> {
					session.draft.setIncrementMinor(money);
					session.inputTarget = null;
					openWizardPrice(player, session);
				}
				case BUYOUT -> {
					session.draft.setAutoBuyMinor(money);
					session.inputTarget = null;
					openWizardPrice(player, session);
				}
				default -> throw new IllegalArgumentException();
			}
		} catch (Exception exception) {
			signal(player, false, "请输入合法且未超出上限的数值");
		}
	}

	private boolean handleNavigation(@NotNull Player player, @NotNull GuiSession session, int slot) {
		switch (slot) {
			case NAV_CURRENT -> openCurrent(player, session);
			case NAV_QUEUE -> {
				session.listPage = 0;
				openQueue(player, session);
			}
			case NAV_CREATE -> {
				if ((!immersiveEnabled() && !player.hasPermission("ezauctions.auction.start"))
						|| (immersiveEnabled() && !canSubmitSession(player))) {
					signal(player, false, "你没有发起拍卖的权限");
				} else {
					openWizardItem(player, session);
				}
			}
			case NAV_MY -> {
				session.listPage = 0;
				openMyAuctions(player, session);
			}
			case NAV_MAILBOX -> {
				session.listPage = 0;
				openMailbox(player, session);
			}
			case NAV_SETTINGS -> openSettings(player, session);
			default -> {
				return false;
			}
		}
		return true;
	}

	private boolean isNavigationPage(@NotNull GuiPage page) {
		return page == GuiPage.CURRENT || page == GuiPage.QUEUE || page == GuiPage.MY_AUCTIONS
				|| page == GuiPage.MAILBOX || page == GuiPage.SETTINGS;
	}

	private void refreshAll() {
		for (Map.Entry<UUID, GuiSession> entry : sessions.entrySet()) {
			Player player = Bukkit.getPlayer(entry.getKey());
			if (player == null || !player.isOnline()) {
				continue;
			}
			scheduler.runPlayerRegionTask(() -> refreshPlayer(player, entry.getValue()), player);
		}
	}

	private void refreshPlayer(@NotNull Player player, @NotNull GuiSession session) {
		AuctionGuiHolder holder = holder(player.getOpenInventory());
		if (holder != null && holder.getPage() == GuiPage.BID_PANEL
				&& session.page == GuiPage.BID_PANEL) {
			Auction active = auctions.getActiveAuction();
			AuctionView view = active == null ? null : active.viewFor(session.viewer);
			populateBidPanel(player.getOpenInventory().getTopInventory(), holder, player, view);
			hideBossBar(player);
			return;
		}
		if (holder != null && holder.getPage() == GuiPage.CURRENT
				&& session.page == GuiPage.CURRENT) {
			Auction active = auctions.getActiveAuction();
			AuctionView view = active == null ? null : active.viewFor(session.viewer);
			populateCurrent(player.getOpenInventory().getTopInventory(), player, session, view);
			if (view != null && view.remainingSeconds() <= 10 && view.remainingSeconds() > 0
					&& session.lastUrgencySecond != view.remainingSeconds()
					&& !notificationsDisabled.contains(player.getUniqueId())) {
				session.lastUrgencySecond = view.remainingSeconds();
				player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.75F,
						view.remainingSeconds() <= 3 ? 1.6F : 1.25F);
			}
			hideBossBar(player);
		} else {
			updateBossBar(player);
		}
	}

	private void updateBossBar(@NotNull Player player) {
		if (bossBarsDisabled.contains(player.getUniqueId())
				|| holder(player.getOpenInventory()) != null) {
			hideBossBar(player);
			return;
		}
		GuiSession session = sessions.get(player.getUniqueId());
		Auction active = auctions.getActiveAuction();
		if (session == null || session.viewer == null || active == null || !active.isRunning()) {
			hideBossBar(player);
			return;
		}
		AuctionView view = active.viewFor(session.viewer);
		BossBar bar = bossBars.computeIfAbsent(player.getUniqueId(), ignored -> {
			BossBar created = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SOLID);
			created.addPlayer(player);
			return created;
		});
		if (!bar.getPlayers().contains(player)) {
			bar.addPlayer(player);
		}
		long price = view.sealed() ? view.viewerHighestBidMinor() : view.currentPriceMinor();
		bar.setTitle("§6" + readableName(view.item()) + " §7| §e$" + Money.format(price)
				+ " §7| §c" + view.remainingSeconds() + "s");
		bar.setColor(view.remainingSeconds() <= 10 ? BarColor.RED : BarColor.YELLOW);
		int total = Math.max(1, view.startingSeconds());
		bar.setProgress(Math.max(0.0D, Math.min(1.0D,
				(double) view.remainingSeconds() / total)));
		bar.setVisible(true);
	}

	private void hideBossBar(@NotNull Player player) {
		BossBar bar = bossBars.get(player.getUniqueId());
		if (bar != null) {
			bar.setVisible(false);
		}
	}

	private void notifySuccessfulBid(@NotNull UUID bidderId) {
		for (Player online : plugin.getServer().getOnlinePlayers()) {
			if (notificationsDisabled.contains(online.getUniqueId())) {
				continue;
			}
			scheduler.runPlayerRegionTask(() -> online.playSound(online.getLocation(),
					Sound.BLOCK_NOTE_BLOCK_PLING, online.getUniqueId().equals(bidderId) ? 0.9F : 0.55F,
					online.getUniqueId().equals(bidderId) ? 1.4F : 1.05F), online);
		}
	}

	private @NotNull Inventory standardInventory(@NotNull GuiSession session, @NotNull GuiPage page,
	                                             @NotNull String title, @Nullable UUID auctionId,
	                                             long revision) {
		AuctionGuiHolder holder = new AuctionGuiHolder(session.token, page, auctionId, revision);
		Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE, title);
		holder.attach(inventory);
		session.page = page;
		return inventory;
	}

	private void openLoading(@NotNull Player player, @NotNull GuiSession session, @NotNull String title) {
		Inventory inventory = standardInventory(session, session.page, title, null, 0L);
		fillBase(inventory);
		inventory.setItem(22, GuiItems.item(Material.CLOCK, "&b正在加载"));
		player.openInventory(inventory);
	}

	private void fillBase(@NotNull Inventory inventory) {
		for (int slot = 0; slot < inventory.getSize(); slot++) {
			inventory.setItem(slot, GuiItems.pane(BACKGROUND));
		}
	}

	private void addNavigation(@NotNull Inventory inventory, @NotNull GuiPage selected) {
		inventory.setItem(NAV_CURRENT, navItem(Material.CLOCK, "当前竞拍", selected == GuiPage.CURRENT));
		inventory.setItem(NAV_QUEUE, navItem(Material.WRITABLE_BOOK, "拍卖场次", selected == GuiPage.QUEUE));
		inventory.setItem(NAV_CREATE, navItem(Material.SMITHING_TABLE, "发起拍卖", false));
		inventory.setItem(NAV_MY, navItem(Material.PLAYER_HEAD, "我的拍卖", selected == GuiPage.MY_AUCTIONS));
		inventory.setItem(NAV_MAILBOX, navItem(Material.CHEST, "领奖箱", selected == GuiPage.MAILBOX));
		inventory.setItem(NAV_SETTINGS, navItem(Material.COMPARATOR, "通知与设置", selected == GuiPage.SETTINGS));
	}

	private @NotNull ItemStack navItem(@NotNull Material material, @NotNull String name, boolean selected) {
		return GuiItems.item(material, selected ? "&a" + name : "&7" + name,
				selected ? "&a当前页面" : "&7点击打开");
	}

	private @NotNull ItemStack sessionItem(@NotNull AuctionSessionView view, String... extraLore) {
		List<String> lore = new ArrayList<>();
		lore.add("&7开始: &f" + formatSessionInstant(view.scheduledStart()));
		lore.add("&7锁单: &f" + formatSessionInstant(view.submissionsLockAt()));
		lore.add("&7状态: &f" + sessionStateText(view.state()));
		lore.add("&7拍品: &f" + view.lotCount() + "/" + view.capacity());
		if (view.estimatedRemainingSeconds().isPresent()) {
			lore.add("&7整场预计剩余: &f" + formatDuration((int) Math.min(
					Integer.MAX_VALUE, view.estimatedRemainingSeconds().getAsLong())));
		}
		lore.addAll(List.of(extraLore));
		return GuiItems.item(view.state() == SessionState.RUNNING ? Material.BELL : Material.WRITABLE_BOOK,
				"&6" + sessionLabel(view.sessionKey()), lore);
	}

	private @NotNull String formatSessionInstant(@NotNull Instant instant) {
		ZoneId zone;
		try {
			zone = ZoneId.of(config.getConfig().getString("immersive.timezone", "Asia/Shanghai"));
		} catch (Exception ignored) {
			zone = ZoneId.of("Asia/Shanghai");
		}
		return DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone).format(instant);
	}

	private static @NotNull String sessionLabel(@NotNull String sessionId) {
		if (sessionId.endsWith("/afternoon")) {
			return sessionId.substring(0, 10) + " 午场";
		}
		if (sessionId.endsWith("/evening")) {
			return sessionId.substring(0, 10) + " 晚场";
		}
		return sessionId;
	}

	private static @NotNull String sessionStateText(@NotNull SessionState state) {
		return switch (state) {
			case OPEN -> "开放投稿/报名";
			case LOCKED -> "已锁单";
			case WAITING -> "等待场地空闲";
			case BLOCKED -> "场地配置异常";
			case RUNNING -> "进行中";
			case COMPLETED -> "已完成";
			case SKIPPED -> "无拍品，已跳过";
		};
	}

	private void pagination(@NotNull Inventory inventory, int page, int total) {
		if (page > 0) {
			inventory.setItem(36, GuiItems.item(Material.ARROW, "&7上一页"));
		}
		if ((page + 1) * PAGE_SIZE < total) {
			inventory.setItem(44, GuiItems.item(Material.ARROW, "&7下一页"));
		}
	}

	private static int clampPage(int requestedPage, int totalEntries) {
		int lastPage = totalEntries <= 0 ? 0 : (totalEntries - 1) / PAGE_SIZE;
		return Math.max(0, Math.min(requestedPage, lastPage));
	}

	private @NotNull ItemStack quantityButton(@NotNull String name, int amount) {
		ItemStack item = GuiItems.item(Material.PAPER, "&b" + name);
		item.setAmount(Math.max(1, Math.min(64, amount)));
		return item;
	}

	private @NotNull ItemStack filterItem(@NotNull AuctionRecordStatus filter,
	                                      @NotNull AuctionRecordStatus selected,
	                                      @NotNull String name) {
		return GuiItems.item(filter == selected ? Material.LIME_DYE : Material.GRAY_DYE,
				filter == selected ? "&a" + name : "&7" + name);
	}

	private @NotNull ItemStack mailboxTab(@NotNull RewardKind kind, @NotNull GuiSession session,
	                                      @NotNull String name, @NotNull Material material) {
		boolean selected = !session.mailboxHistory && session.mailboxFilter == kind;
		return GuiItems.item(selected ? Material.LIME_DYE : material,
				selected ? "&a" + name : "&7" + name);
	}

	private @NotNull ItemStack rewardItem(@NotNull RewardRecord reward, boolean history) {
		if (history) {
			List<String> details = MailboxHistoryView.details(reward);
			if (reward.getKind() == RewardKind.ITEM) {
				try {
					return GuiItems.auctionItem(reward.getItem(), reward.getAmount(),
							details.toArray(String[]::new));
				} catch (IOException exception) {
					return GuiItems.item(Material.BARRIER, "&c物品数据损坏", details);
				}
			}
			return GuiItems.item(reward.getKind() == RewardKind.REFUND
						? Material.GOLD_NUGGET : Material.EMERALD,
					reward.getKind() == RewardKind.REFUND ? "&6拍卖退款" : "&a拍卖收入", details);
		}

		String state = switch (reward.getState()) {
			case PENDING -> "&a待领取";
			case CLAIMING -> "&e领取中";
			case DONE -> "&7已领取";
		};
		if (reward.getKind() == RewardKind.ITEM) {
			try {
				return GuiItems.auctionItem(reward.getItem(), reward.getAmount(),
						"&7状态: " + state,
						"&7奖励 ID: &8" + reward.getId(),
						reward.getState() == RewardState.PENDING ? "&a点击领取" : "&7不可重复领取");
			} catch (IOException exception) {
				return GuiItems.item(Material.BARRIER, "&c物品数据损坏", "&8" + reward.getId());
			}
		}
		return GuiItems.item(reward.getKind() == RewardKind.REFUND ? Material.GOLD_NUGGET : Material.EMERALD,
				reward.getKind() == RewardKind.REFUND ? "&6拍卖退款" : "&a拍卖收入",
				"&7金额: &e$" + Money.format(reward.getMoneyMinor()),
				"&7状态: " + state,
				"&7奖励 ID: &8" + reward.getId(),
				reward.getState() == RewardState.PENDING ? "&a点击领取" : "&7不可重复领取");
	}

	private @NotNull ItemStack playerHead(@Nullable UUID ownerId, @NotNull String name, String... lore) {
		if (ownerId == null) {
			return GuiItems.item(Material.SKELETON_SKULL, name, lore);
		}
		ItemStack head = GuiItems.item(Material.PLAYER_HEAD, name, lore);
		if (head.getItemMeta() instanceof SkullMeta meta) {
			meta.setOwningPlayer(Bukkit.getOfflinePlayer(ownerId));
			head.setItemMeta(meta);
		}
		return head;
	}

	private void setBidButton(@NotNull Inventory inventory, int slot, @NotNull Material material,
	                          @NotNull String name, long amountMinor, long requiredMinor,
	                          long balanceMinor) {
		boolean affordable = amountMinor > 0 && requiredMinor > 0 && balanceMinor >= requiredMinor;
		inventory.setItem(slot, GuiItems.item(affordable ? material : Material.GRAY_CONCRETE,
				affordable ? name : "&c余额不足",
				"&e$" + Money.format(amountMinor),
				"&7本次扣款: &e$" + Money.format(requiredMinor),
				affordable ? "&7点击后进入确认页" : "&7当前不可操作"));
	}

	private long minimumBid(@NotNull AuctionView view) {
		if (view.sealed()) {
			return view.viewerHighestBidMinor() == 0
					? view.startingPriceMinor()
					: safeAdd(view.viewerHighestBidMinor(), view.incrementMinor());
		}
		return view.highestBidderId() == null
				? view.startingPriceMinor()
				: safeAdd(view.currentPriceMinor(), view.incrementMinor());
	}

	private long safeBalance(@NotNull Player player) {
		try {
			return Math.max(0L, Money.fromMajor(economy.getBalance(player)));
		} catch (Exception ignored) {
			return 0L;
		}
	}

	private long listingFee() {
		try {
			return Math.max(0L, Money.fromMajor(config.getConfig().getDouble("auctions.fees.start-price")));
		} catch (Exception ignored) {
			return 0L;
		}
	}

	private @NotNull CompletableFuture<Boolean> transitionSubmission(
			@NotNull SubmissionAttempt attempt, @NotNull SubmissionTransactionState expected,
			@NotNull SubmissionTransactionState next, @NotNull String reason) {
		return database.transitionSubmissionTransaction(attempt.transaction().getId(), expected,
				next, reason, System.currentTimeMillis());
	}

	/** Used only when the journal row itself could not be created; no external resource was touched. */
	private void abortWithoutJournal(@NotNull Player player, @NotNull GuiSession session,
	                                 @NotNull SubmissionAttempt attempt, @NotNull String reason) {
		database.cancelAuction(attempt.record().getId(), AuctionRecordStatus.PREPARING,
				"PLAYER_INVENTORY", "NOT_REQUIRED", System.currentTimeMillis())
				.whenComplete((cancelled, error) -> {
					session.submitting.set(false);
					if (error != null || !Boolean.TRUE.equals(cancelled)) {
						logger.severe("Could not close unjournaled submission "
								+ attempt.record().getId(), error == null
								? new IllegalStateException("auction state changed") : asException(error));
					}
					if (player.isOnline()) {
						scheduler.runPlayerRegionTask(() -> {
							signal(player, false, reason);
							openWizardReview(player, session);
						}, player);
					}
				});
	}

	/**
	 * Compensation never writes directly to a possibly-offline inventory or calls Vault again. The
	 * database transaction closes the lot/auction and creates deterministic mailbox rewards, so a
	 * retry or startup recovery cannot duplicate either resource.
	 */
	private void compensateSubmission(@NotNull Player player, @NotNull GuiSession session,
	                                  @NotNull SubmissionAttempt attempt, @NotNull String reason) {
		database.compensateSubmissionTransaction(attempt.transaction().getId(), reason,
				System.currentTimeMillis()).whenComplete((compensated, error) -> {
			session.submitting.set(false);
			boolean success = error == null && Boolean.TRUE.equals(compensated);
			if (!success) {
				logger.severe("Could not compensate submission " + attempt.record().getId(),
						error == null ? new IllegalStateException("submission state changed")
								: asException(error));
			}
			if (player.isOnline()) {
				scheduler.runPlayerRegionTask(() -> {
					signal(player, false, success
							? reason + "；应退资源已进入领奖箱"
							: reason + "；补偿仍在持久化队列中，请联系管理员");
					openWizardReview(player, session);
				}, player);
			}
		});
	}

	private void finishSubmission(@NotNull Player player, @NotNull GuiSession session,
	                              @NotNull SubmissionAttempt attempt, boolean queued) {
		session.submitting.set(false);
		session.selectedAuctionId = attempt.record().getId();
		session.selectedSessionId = attempt.sessionId();
		if (!player.isOnline()) {
			return;
		}
		signal(player, true, queued ? "投稿成功，已进入所选场次" : "拍卖已开始");
		if (attempt.sessionId() != null) {
			openQueueDetail(player, session);
		} else if (queued) {
			session.listPage = 0;
			openQueue(player, session);
		} else {
			openCurrent(player, session);
		}
	}

	private void finishLegacySubmission(@NotNull Player player, @NotNull GuiSession session,
	                                    @NotNull SubmissionAttempt attempt, boolean queued) {
		finishSubmission(player, session, attempt, queued);
	}

	private boolean immersiveEnabled() {
		return config.getConfig().getBoolean("immersive.enabled", false);
	}

	private boolean canBidHere(@NotNull Player player) {
		if (!attendance.isActive(player.getUniqueId()) || !attendance.isInsideVenue(player)) {
			return false;
		}
		Optional<String> attendanceSession = attendance.activeSession(player.getUniqueId());
		return attendanceSession.isPresent()
				&& attendanceSession.equals(auctionSessions.activeSessionId());
	}

	private boolean canSubmitSession(@NotNull Player player) {
		return player.hasPermission("rookieauctions.session.submit")
				|| player.hasPermission("ezauctions.session.submit")
				|| player.hasPermission("ezauctions.auction.start");
	}

	private boolean canJoinSession(@NotNull Player player) {
		return player.hasPermission("rookieauctions.session.join")
				|| player.hasPermission("ezauctions.session.join")
				|| player.hasPermission("ezauctions.auction");
	}

	private static @NotNull String reservationFailureText(@NotNull ReservationStatus status) {
		return switch (status) {
			case FULL -> "本场 16 个名额已经满了";
			case SELLER_LIMIT -> "你在本场已经投稿 2 件";
			case SESSION_CLOSED -> "本场已经锁单，无法继续投稿";
			case NOT_FOUND -> "所选场次不存在，请重新选择";
			case SUCCESS -> "投稿成功";
		};
	}

	private @Nullable String validateSubmissionAttempt(@NotNull Player player,
	                                                   @NotNull SubmissionAttempt attempt) {
		if (!auctions.isAuctionsEnabled()) {
			return "拍卖当前已停用";
		}
		if (attempt.sessionId() == null
				&& auctions.ownsActiveOrQueuedAuction(player.getUniqueId())) {
			return "你已经有正在进行或等待中的拍卖";
		}
		if (attempt.sessionId() == null && auctions.getAuctionQueue().size()
				>= config.getConfig().getInt("general.auction-queue-limit")) {
			return "等待队列已满";
		}
		if (player.getGameMode() == GameMode.CREATIVE
				&& config.getConfig().getBoolean("auctions.toggles.deny-creative")) {
			return "创造模式不能发起拍卖";
		}
		if (config.getConfig().getStringList("auctions.blocked-worlds").stream()
				.anyMatch(player.getWorld().getName()::equalsIgnoreCase)) {
			return "当前世界不能发起拍卖";
		}
		ItemStack item = attempt.item();
		if (config.getConfig().getStringList("auctions.blocked-materials").stream()
				.anyMatch(item.getType().name()::equalsIgnoreCase)) {
			return "该物品不能参与拍卖";
		}
		if (item.getItemMeta() instanceof Damageable damageable && damageable.hasDamage()
				&& config.getConfig().getBoolean("auctions.toggles.restrict-damaged")) {
			return "损坏物品不能参与拍卖";
		}
		if (ItemHelper.getAmountOfItemInInventory(player, item) < attempt.amount()) {
			return "物品数量或位置已变化";
		}
		if (!attempt.data().getAuctioneer().withinBoundary(config)) {
			return "你不在允许发起拍卖的区域";
		}
		if (attempt.sessionId() != null && !canSubmitSession(player)) {
			return "你没有向定时场次投稿的权限";
		}
		if (attempt.sessionId() == null && !validDuration(attempt.record().getDurationSeconds())) {
			return "拍卖时长超出服务器限制";
		}

		long maximum = configuredMaximumMoney();
		long startingPrice = attempt.record().getStartingPriceMinor();
		long increment = attempt.record().getIncrementMinor();
		long buyout = attempt.record().getAutoBuyMinor();
		if (startingPrice <= 0 || startingPrice > maximum || increment <= 0 || increment > maximum
				|| (buyout > 0 && (buyout < startingPrice || buyout > maximum))) {
			return "投稿价格已不符合当前服务器限制";
		}
		long minStart = majorConfig("auctions.minimum.starting-price");
		long maxStart = majorConfig("auctions.maximum.starting-price");
		long minIncrement = config.getConfig().getDouble("auctions.minimum.increment") < 0
				? 0L : majorConfig("auctions.minimum.increment");
		long maxIncrement = config.getConfig().getDouble("auctions.maximum.increment") < 0
				? 0L : majorConfig("auctions.maximum.increment");
		if (startingPrice < minStart || (maxStart > 0 && startingPrice > maxStart)
				|| increment < minIncrement || (maxIncrement > 0 && increment > maxIncrement)) {
			return "投稿价格已不符合当前服务器限制";
		}
		return null;
	}

	private @Nullable String validateDraft(@NotNull Player player, @NotNull GuiSession session) {
		if (!auctions.isAuctionsEnabled()) {
			return "拍卖当前已停用";
		}
		if (!immersiveEnabled() && auctions.ownsActiveOrQueuedAuction(player.getUniqueId())) {
			return "你已经有正在进行或等待中的拍卖";
		}
		if (!immersiveEnabled() && auctions.getAuctionQueue().size() >= config.getConfig().getInt("general.auction-queue-limit")) {
			return "等待队列已满";
		}
		if (player.getGameMode() == GameMode.CREATIVE
				&& config.getConfig().getBoolean("auctions.toggles.deny-creative")) {
			return "创造模式不能发起拍卖";
		}
		if (config.getConfig().getStringList("auctions.blocked-worlds").stream()
				.anyMatch(player.getWorld().getName()::equalsIgnoreCase)) {
			return "当前世界不能发起拍卖";
		}
		ItemStack selected = session.draft.getSelectedItem();
		if (selected == null || selected.getType() == Material.AIR) {
			return "请先选择物品";
		}
		if (config.getConfig().getStringList("auctions.blocked-materials").stream()
				.anyMatch(selected.getType().name()::equalsIgnoreCase)) {
			return "该物品不能参与拍卖";
		}
		if (selected.getItemMeta() instanceof Damageable damageable && damageable.hasDamage()
				&& config.getConfig().getBoolean("auctions.toggles.restrict-damaged")) {
			return "损坏物品不能参与拍卖";
		}
		if (session.draft.getAmount() <= 0
				|| ItemHelper.getAmountOfItemInInventory(player, selected) < session.draft.getAmount()) {
			return "物品数量或位置已变化";
		}
		if (!session.viewer.withinBoundary(config)) {
			return "你不在允许发起拍卖的区域";
		}
		if (immersiveEnabled() && session.selectedSessionId == null) {
			return "请选择一个可投稿场次";
		}
		if (immersiveEnabled() && !canSubmitSession(player)) {
			return "你没有向定时场次投稿的权限";
		}
		if (!immersiveEnabled() && !validDuration(session.draft.getDurationSeconds())) {
			return "拍卖时长超出服务器限制";
		}
		return validateDraftPrices(session.draft);
	}

	private @Nullable String validateDraftPrices(@NotNull AuctionDraft draft) {
		long maximum = configuredMaximumMoney();
		if (draft.getStartingPriceMinor() <= 0 || draft.getStartingPriceMinor() > maximum) {
			return "起拍价不合法";
		}
		if (draft.getIncrementMinor() <= 0 || draft.getIncrementMinor() > maximum) {
			return "最低加价不合法";
		}
		if (draft.isAutoBuyEnabled()
				&& (draft.getAutoBuyMinor() < draft.getStartingPriceMinor()
				|| draft.getAutoBuyMinor() > maximum)) {
			return "一口价必须不低于起拍价且不超过上限";
		}

		long minStart = majorConfig("auctions.minimum.starting-price");
		long maxStart = majorConfig("auctions.maximum.starting-price");
		long minIncrement = config.getConfig().getDouble("auctions.minimum.increment") < 0
				? 0L : majorConfig("auctions.minimum.increment");
		long maxIncrement = config.getConfig().getDouble("auctions.maximum.increment") < 0
				? 0L : majorConfig("auctions.maximum.increment");
		if (draft.getStartingPriceMinor() < minStart
				|| (maxStart > 0 && draft.getStartingPriceMinor() > maxStart)) {
			return "起拍价超出服务器限制";
		}
		if (draft.getIncrementMinor() < minIncrement
				|| (maxIncrement > 0 && draft.getIncrementMinor() > maxIncrement)) {
			return "最低加价超出服务器限制";
		}
		return null;
	}

	private boolean validDuration(int seconds) {
		int minimum = config.getConfig().getInt("gui.minimum-duration-seconds", 30);
		int maximum = config.getConfig().getInt("gui.maximum-duration-seconds", 300);
		return seconds >= Math.max(1, minimum) && (maximum <= 0 || seconds <= maximum);
	}

	private long majorConfig(@NotNull String path) {
		try {
			return Money.fromMajor(Math.max(0D, config.getConfig().getDouble(path)));
		} catch (Exception ignored) {
			return 0L;
		}
	}

	private long configuredMaximumMoney() {
		return config.getConfig().getLong("gui.maximum-money-minor", Money.DEFAULT_MAX_MINOR);
	}

	private void signal(@NotNull Player player, boolean success, @NotNull String text) {
		player.sendTitle(success ? "§a操作成功" : "§c操作未完成", "§f" + text, 0, 35, 10);
		player.playSound(player.getLocation(), success ? Sound.ENTITY_EXPERIENCE_ORB_PICKUP
				: Sound.BLOCK_NOTE_BLOCK_BASS, 0.75F, success ? 1.25F : 0.75F);
	}

	private @NotNull String bidFailureText(@NotNull BidOutcome.Status status) {
		return switch (status) {
			case NO_AUCTION -> "拍卖已经结束";
			case STALE_VIEW -> "价格或状态已变化，请重新确认";
			case BID_PROCESSING -> "另一笔出价正在提交";
			case SELF_BID -> "不能竞拍自己的物品";
			case SESSION_NOT_RUNNING -> "当前没有正在进行的拍卖场次";
			case NOT_PARTICIPANT -> "请先进入本场拍卖模式";
			case NOT_IN_VENUE -> "你当前不在拍卖场区域内";
			case BLOCKED_WORLD, WRONG_WORLD -> "当前世界不能参与这场拍卖";
			case OUTSIDE_BOUNDARY -> "你不在允许竞拍的区域";
			case TOO_LOW -> "出价已低于最新最低有效价";
			case NO_BUYOUT -> "该拍卖没有一口价";
			case MAX_BIDS -> "密封拍卖出价次数已用完";
			case CONSECUTIVE_LIMIT -> "连续出价次数已达上限";
			case INSUFFICIENT_FUNDS -> "余额不足";
			case ECONOMY_FAILED -> "经济插件拒绝了交易";
			case EVENT_CANCELLED -> "出价被服务器规则取消";
			case PERSISTENCE_FAILED -> "出价未能持久化，扣款已退回";
			case INVALID_AMOUNT -> "金额不合法或超出上限";
			case SUCCESS -> "出价成功";
		};
	}

	private @NotNull String claimResultText(@NotNull RewardController.ClaimResult result) {
		return switch (result) {
			case SUCCESS -> "奖励已领取";
			case NOT_AVAILABLE -> "奖励已被领取或正在处理中";
			case NO_SPACE -> "背包空间不足，奖励仍保留在领奖箱";
			case WRONG_WORLD -> "当前世界不能领取这件物品";
			case ECONOMY_FAILED -> "经济插件拒绝入账，奖励仍被保留";
			case CORRUPT_ITEM -> "物品数据损坏，请联系管理员";
			case DATABASE_ERROR -> "数据库状态更新失败，请联系管理员";
		};
	}

	private static @Nullable AuctionGuiHolder holder(@NotNull InventoryView view) {
		return view.getTopInventory().getHolder() instanceof AuctionGuiHolder holder ? holder : null;
	}

	private static @NotNull String safeName(@NotNull OfflinePlayer player) {
		return player.getName() == null ? player.getUniqueId().toString() : player.getName();
	}

	private static @NotNull String readableName(@NotNull ItemStack item) {
		if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
			return ChatColor.stripColor(item.getItemMeta().getDisplayName());
		}
		String[] words = item.getType().name().toLowerCase().split("_");
		StringBuilder result = new StringBuilder();
		for (String word : words) {
			if (!result.isEmpty()) {
				result.append(' ');
			}
			result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return result.toString();
	}

	private static @NotNull String formatTime(int seconds) {
		return String.format("%02d:%02d", Math.max(0, seconds) / 60, Math.max(0, seconds) % 60);
	}

	private static @NotNull String formatDuration(int seconds) {
		if (seconds % 60 == 0) {
			return (seconds / 60) + " 分钟";
		}
		return seconds + " 秒";
	}

	private static @NotNull String formatEta(long epochMillis) {
		if (epochMillis <= 0L) {
			return "未知";
		}
		return new SimpleDateFormat("HH:mm:ss").format(new Date(epochMillis));
	}

	private static @NotNull String statusName(@NotNull AuctionRecordStatus status) {
		return switch (status) {
			case PREPARING -> "准备中";
			case QUEUED -> "等待开始";
			case ACTIVE -> "正在进行";
			case COMPLETED -> "已完成";
			case CANCELLED -> "已取消";
		};
	}

	private static long safeAdd(long left, long right) {
		try {
			return Math.addExact(left, right);
		} catch (ArithmeticException exception) {
			return Long.MAX_VALUE;
		}
	}

	private static long safeMultiply(long value, long multiplier) {
		try {
			return Math.multiplyExact(value, multiplier);
		} catch (ArithmeticException exception) {
			return Long.MAX_VALUE;
		}
	}

	private static long additionalRequired(@NotNull AuctionView view, long proposedMinor) {
		return Math.max(0L, proposedMinor - view.viewerHighestBidMinor());
	}

	private static @NotNull Exception asException(@NotNull Throwable error) {
		return error instanceof Exception exception ? exception : new RuntimeException(error);
	}

	private static void toggle(@NotNull Set<UUID> set, @NotNull UUID value) {
		if (!set.add(value)) {
			set.remove(value);
		}
	}

	private record SubmissionAttempt(@NotNull AuctionRecord record,
	                                 @NotNull AuctionSubmissionTransaction transaction,
	                                 @NotNull AuctionData data, @NotNull ItemStack item,
	                                 int amount, long feeMinor, @Nullable String sessionId) {
		private SubmissionAttempt {
			if (amount <= 0 || feeMinor < 0) {
				throw new IllegalArgumentException("Invalid submission resource amounts");
			}
			item = item.clone();
			item.setAmount(1);
		}

		@Override
		public @NotNull ItemStack item() {
			return item.clone();
		}
	}
}
