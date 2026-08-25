package me.elian.ezauctions.gui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.elian.ezauctions.Logger;
import me.elian.ezauctions.controller.AuctionController;
import me.elian.ezauctions.controller.AuctionPlayerController;
import me.elian.ezauctions.controller.ConfigController;
import me.elian.ezauctions.controller.RewardController;
import me.elian.ezauctions.data.Database;
import me.elian.ezauctions.event.AuctionStartEvent;
import me.elian.ezauctions.helper.ItemHelper;
import me.elian.ezauctions.model.Auction;
import me.elian.ezauctions.model.AuctionData;
import me.elian.ezauctions.model.AuctionPlayer;
import me.elian.ezauctions.model.AuctionRecord;
import me.elian.ezauctions.model.AuctionRecordStatus;
import me.elian.ezauctions.model.AuctionView;
import me.elian.ezauctions.model.BidOutcome;
import me.elian.ezauctions.model.Money;
import me.elian.ezauctions.model.RewardKind;
import me.elian.ezauctions.model.RewardRecord;
import me.elian.ezauctions.model.RewardState;
import me.elian.ezauctions.scheduler.CancellableTask;
import me.elian.ezauctions.scheduler.TaskScheduler;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
	private final Map<UUID, GuiSession> sessions = new ConcurrentHashMap<>();
	private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
	private final Map<UUID, Set<UUID>> reminders = new ConcurrentHashMap<>();
	private final Set<UUID> notificationsDisabled = ConcurrentHashMap.newKeySet();
	private final Set<UUID> bossBarsDisabled = ConcurrentHashMap.newKeySet();
	private final CancellableTask refreshTask;

	@Inject
	public AuctionGuiController(Plugin plugin, AuctionController auctions, AuctionPlayerController players,
	                            RewardController rewards, Database database, Economy economy,
	                            ConfigController config, TaskScheduler scheduler, Logger logger) {
		this.plugin = plugin;
		this.auctions = auctions;
		this.players = players;
		this.rewards = rewards;
		this.database = database;
		this.economy = economy;
		this.config = config;
		this.scheduler = scheduler;
		this.logger = logger;
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		refreshTask = scheduler.runSyncRepeatingTask(plugin, this::refreshAll, 1, 1);
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

		if (event.getClickedInventory() == player.getInventory()) {
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

	@EventHandler
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

		long minimum = minimumBid(view);
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
			openCurrent(player, session);
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
			openCurrent(player, session);
			return;
		}

		active.submitBid(player, session.viewer, session.selectedAuctionId, session.selectedRevision,
				session.proposedBidMinor, session.proposedBuyout).whenComplete((outcome, error) ->
				scheduler.runPlayerRegionTask(() -> {
					session.submitting.set(false);
					if (error != null || outcome == null) {
						signal(player, false, "提交失败，请稍后重试");
						openCurrent(player, session);
						return;
					}
					if (outcome.status() == BidOutcome.Status.SUCCESS) {
						signal(player, true, session.proposedBuyout ? "购买成功，物品已进入领奖箱" : "出价成功");
						notifySuccessfulBid(player.getUniqueId());
					} else {
						signal(player, false, bidFailureText(outcome.status()));
					}
					openCurrent(player, session);
				}, player));
	}

	private void openQueue(@NotNull Player player, @NotNull GuiSession session) {
		session.page = GuiPage.QUEUE;
		Inventory inventory = standardInventory(session, GuiPage.QUEUE, "§8深岩竞技场 · 等待队列", null, 0L);
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

	private void handleQueueClick(@NotNull Player player, @NotNull GuiSession session, int slot) {
		if (slot == 36 && session.listPage > 0) {
			session.listPage--;
			openQueue(player, session);
			return;
		}
		if (slot == 44 && (session.listPage + 1) * PAGE_SIZE < auctions.getAuctionQueue().size()) {
			session.listPage++;
			openQueue(player, session);
			return;
		}
		UUID auctionId = session.visibleEntries.get(slot);
		if (auctionId != null) {
			session.selectedAuctionId = auctionId;
			openQueueDetail(player, session);
		}
	}

	private void openQueueDetail(@NotNull Player player, @NotNull GuiSession session) {
		AuctionData data = session.selectedAuctionId == null ? null
				: auctions.getQueuedAuction(session.selectedAuctionId);
		if (data == null) {
			signal(player, false, "该拍卖已离开队列");
			openQueue(player, session);
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

	private void handleQueueDetailClick(@NotNull Player player, @NotNull GuiSession session, int slot) {
		if (slot == 39) {
			openQueue(player, session);
			return;
		}
		if (slot != 41 || session.selectedAuctionId == null) {
			return;
		}
		AuctionData data = auctions.getQueuedAuction(session.selectedAuctionId);
		if (data == null) {
			openQueue(player, session);
			return;
		}
		if (data.getAuctioneer().getUniqueId().equals(player.getUniqueId())) {
			session.returnPage = GuiPage.QUEUE_DETAIL;
			openCancelConfirmation(player, session, data.getItem(), "等待队列", false);
		} else {
			Set<UUID> playerReminders = reminders.computeIfAbsent(player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet());
			if (!playerReminders.add(data.getId())) {
				playerReminders.remove(data.getId());
			}
			openQueueDetail(player, session);
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
		Inventory inventory = standardInventory(session, GuiPage.WIZARD_MODE, "§8发起拍卖 · 2/4 模式与时长",
				null, 0L);
		fillBase(inventory);
		inventory.setItem(20, GuiItems.item(session.draft.isSealed() ? Material.GRAY_DYE : Material.LIME_DYE,
				"&c普通公开竞拍", session.draft.isSealed() ? "&7点击选择" : "&a已选择"));
		inventory.setItem(24, GuiItems.item(session.draft.isSealed() ? Material.PURPLE_DYE : Material.GRAY_DYE,
				"&5密封竞拍", session.draft.isSealed() ? "&a已选择" : "&7点击选择",
				"&7隐藏其他玩家的价格和身份"));
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
		inventory.setItem(45, GuiItems.item(Material.ARROW, "&7上一步"));
		inventory.setItem(53, GuiItems.item(Material.LIME_CONCRETE, "&a下一步"));
		player.openInventory(inventory);
	}

	private void handleWizardModeClick(@NotNull Player player, @NotNull GuiSession session, int slot) {
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
			case 36 -> setDuration(player, session, 30);
			case 37 -> setDuration(player, session, 60);
			case 38 -> setDuration(player, session, 120);
			case 39 -> setDuration(player, session, 300);
			case 40 -> openAnvil(player, session, GuiSession.InputTarget.DURATION, GuiPage.WIZARD_MODE,
					Integer.toString(session.draft.getDurationSeconds()));
			case 45 -> openWizardItem(player, session);
			case 53 -> openWizardPrice(player, session);
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
		inventory.setItem(31, GuiItems.item(Material.HOPPER, "&b队列与费用",
				"&7上架费: &e$" + Money.format(listingFee()),
				"&7税率: &e" + config.getConfig().getDouble("auctions.fees.tax-percent") + "%",
				position == 0 ? "&a将立即开始" : "&7当前队列位置: &f#" + position,
				"&7预计开始: &f" + formatEta(estimatedStart)));
		inventory.setItem(45, GuiItems.item(Material.ARROW, "&7上一步"));
		inventory.setItem(53, GuiItems.item(Material.LIME_CONCRETE, "&a最终确认",
				"&7此时才会重新检查并移除物品", "&7随后持久化并加入队列"));
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
		UUID auctionId = UUID.randomUUID();
		AuctionRecord record = new AuctionRecord(auctionId, player.getUniqueId(), selected,
				session.draft.getAmount(), session.draft.isSealed(), player.getWorld().getName(),
				session.draft.getStartingPriceMinor(), session.draft.getIncrementMinor(),
				session.draft.getAutoBuyMinor(), session.draft.getDurationSeconds());
		database.createAuctionRecord(record).whenComplete((ignored, persistError) ->
				scheduler.runPlayerRegionTask(() -> {
					if (persistError != null) {
						session.submitting.set(false);
						signal(player, false, "数据库暂时不可用，未扣除物品");
						openWizardReview(player, session);
						return;
					}
					commitCreateAuction(player, session, record);
				}, player));
	}

	private void commitCreateAuction(@NotNull Player player, @NotNull GuiSession session,
	                                 @NotNull AuctionRecord record) {
		String error = validateDraft(player, session);
		if (error != null) {
			record.setStatus(AuctionRecordStatus.CANCELLED);
			database.saveAuctionRecord(record);
			session.submitting.set(false);
			signal(player, false, error);
			openWizardReview(player, session);
			return;
		}

		long fee = listingFee();
		if (safeBalance(player) < fee) {
			record.setStatus(AuctionRecordStatus.CANCELLED);
			database.saveAuctionRecord(record);
			session.submitting.set(false);
			signal(player, false, "余额不足，未扣除物品");
			openWizardReview(player, session);
			return;
		}
		if (fee > 0) {
			EconomyResponse feeResult = economy.withdrawPlayer(player, Money.toMajor(fee));
			if (feeResult == null || !feeResult.transactionSuccess()) {
				record.setStatus(AuctionRecordStatus.CANCELLED);
				database.saveAuctionRecord(record);
				session.submitting.set(false);
				signal(player, false, "上架费扣款失败，未扣除物品");
				openWizardReview(player, session);
				return;
			}
		}

		ItemStack selected = session.draft.getSelectedItem();
		if (!ItemHelper.removeItemFromPlayerInventoryExact(player, selected, session.draft.getAmount())) {
			refundListingFee(player, fee);
			record.setStatus(AuctionRecordStatus.CANCELLED);
			database.saveAuctionRecord(record);
			session.submitting.set(false);
			signal(player, false, "物品数量或位置已变化，创建失败");
			openWizardReview(player, session);
			return;
		}

		AuctionData data = new AuctionData(record.getId(), session.viewer, selected, session.draft.getAmount(),
				session.draft.getDurationSeconds(), session.draft.getStartingPriceMinor(),
				session.draft.getIncrementMinor(), session.draft.getAutoBuyMinor(),
				session.draft.isSealed(), player.getWorld().getName());
		data.gatherAdditionalData(logger);
		record.setStatus(AuctionRecordStatus.QUEUED);
		database.saveAuctionRecord(record).whenComplete((ignored, persistError) ->
				scheduler.runPlayerRegionTask(() -> {
					if (persistError != null) {
						boolean restored = ItemHelper.addItemToPlayerInventoryNoDrop(
								player, selected, session.draft.getAmount());
						if (!restored) {
							rewards.createItemReward(player.getUniqueId(), record.getId(), selected,
									session.draft.getAmount(), player.getWorld().getName());
						}
						record.cancel(restored ? "PLAYER_INVENTORY" : "SELLER_MAILBOX", "NONE");
						database.saveAuctionRecord(record);
						refundListingFee(player, fee);
						session.submitting.set(false);
						signal(player, false, restored
								? "持久化失败，物品与费用已退回"
								: "持久化失败，物品已转入领奖箱，费用已退回");
						openWizardReview(player, session);
						return;
					}
					scheduler.runSyncTask(() -> {
						boolean queued = auctions.queueAuction(data);
						session.submitting.set(false);
						scheduler.runPlayerRegionTask(() -> {
							signal(player, true, queued ? "已加入等待队列" : "拍卖已开始");
							if (queued) {
								session.listPage = 0;
								openQueue(player, session);
							} else {
								openCurrent(player, session);
							}
						}, player);
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
					renderMailbox(player, session, records);
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
			inventory.setItem(slot, rewardItem(reward));
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
						openCurrent(player, session);
						return;
					}
					AuctionView view = active.viewFor(session.viewer);
					if (!view.auctionId().equals(session.selectedAuctionId)
							|| view.revision() != session.selectedRevision) {
						signal(player, false, "竞拍状态已变化，请重新输入");
						session.inputTarget = null;
						openCurrent(player, session);
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
				if (!player.hasPermission("ezauctions.auction.start")) {
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
		inventory.setItem(NAV_QUEUE, navItem(Material.HOPPER, "等待队列", selected == GuiPage.QUEUE));
		inventory.setItem(NAV_CREATE, navItem(Material.SMITHING_TABLE, "发起拍卖", false));
		inventory.setItem(NAV_MY, navItem(Material.PLAYER_HEAD, "我的拍卖", selected == GuiPage.MY_AUCTIONS));
		inventory.setItem(NAV_MAILBOX, navItem(Material.CHEST, "领奖箱", selected == GuiPage.MAILBOX));
		inventory.setItem(NAV_SETTINGS, navItem(Material.COMPARATOR, "通知与设置", selected == GuiPage.SETTINGS));
	}

	private @NotNull ItemStack navItem(@NotNull Material material, @NotNull String name, boolean selected) {
		return GuiItems.item(material, selected ? "&a" + name : "&7" + name,
				selected ? "&a当前页面" : "&7点击打开");
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

	private @NotNull ItemStack rewardItem(@NotNull RewardRecord reward) {
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

	private void refundListingFee(@NotNull Player player, long fee) {
		if (fee <= 0) {
			return;
		}
		EconomyResponse response = economy.depositPlayer(player, Money.toMajor(fee));
		if (response == null || !response.transactionSuccess()) {
			rewards.createMoneyReward(player.getUniqueId(), null, RewardKind.REFUND, fee);
		}
	}

	private @Nullable String validateDraft(@NotNull Player player, @NotNull GuiSession session) {
		if (!auctions.isAuctionsEnabled()) {
			return "拍卖当前已停用";
		}
		if (auctions.ownsActiveOrQueuedAuction(player.getUniqueId())) {
			return "你已经有正在进行或等待中的拍卖";
		}
		if (auctions.getAuctionQueue().size() >= config.getConfig().getInt("general.auction-queue-limit")) {
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
		if (!validDuration(session.draft.getDurationSeconds())) {
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

	private static void toggle(@NotNull Set<UUID> set, @NotNull UUID value) {
		if (!set.add(value)) {
			set.remove(value);
		}
	}
}
