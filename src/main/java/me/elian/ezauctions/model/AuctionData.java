package me.elian.ezauctions.model;

import me.elian.ezauctions.Logger;
import me.elian.ezauctions.controller.AuctionPlayerController;
import me.elian.ezauctions.controller.ConfigController;
import me.elian.ezauctions.controller.MessageController;
import me.elian.ezauctions.helper.ItemHelper;
import me.elian.ezauctions.scheduler.TaskScheduler;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class AuctionData {
	private final UUID id;
	private final AuctionPlayer auctioneer;
	private final ItemStack item;
	private final String amountString;
	private final boolean isSealed;
	private final String world;
	private long startingPriceMinor;
	private int startingAuctionTime;
	private long incrementPriceMinor;
	private long autoBuyPriceMinor;
	private int amount;
	private String skullOwner;
	private int repairPrice;
	private String minecraftName;
	private String customName;
	private Key itemKey;

	public AuctionData(AuctionPlayer auctioneer, ItemStack item, String amountString, int startingAuctionTime,
	                   double startingPrice, double incrementPrice, double autoBuyPrice, boolean isSealed,
	                   String world) {
		this(UUID.randomUUID(), auctioneer, item, amountString, startingAuctionTime,
				Money.fromMajor(startingPrice), Money.fromMajor(incrementPrice), Money.fromMajor(autoBuyPrice),
				isSealed, world);
	}

	public AuctionData(@NotNull UUID id, @NotNull AuctionPlayer auctioneer, @NotNull ItemStack item, int amount,
	                   int startingAuctionTime, long startingPriceMinor, long incrementPriceMinor,
	                   long autoBuyPriceMinor, boolean isSealed, @NotNull String world) {
		this(id, auctioneer, item, Integer.toString(amount), startingAuctionTime, startingPriceMinor,
				incrementPriceMinor, autoBuyPriceMinor, isSealed, world);
		this.amount = amount;
	}

	private AuctionData(@NotNull UUID id, @NotNull AuctionPlayer auctioneer, @NotNull ItemStack item,
	                    String amountString, int startingAuctionTime, long startingPriceMinor,
	                    long incrementPriceMinor, long autoBuyPriceMinor, boolean isSealed,
	                    @NotNull String world) {
		this.id = id;
		this.auctioneer = auctioneer;
		this.item = item;
		this.amountString = amountString == null ? "" : amountString.toLowerCase();
		this.startingAuctionTime = startingAuctionTime;
		this.startingPriceMinor = startingPriceMinor;
		this.incrementPriceMinor = incrementPriceMinor;
		this.autoBuyPriceMinor = autoBuyPriceMinor;
		this.isSealed = isSealed;
		this.world = world;
	}

	public UUID getId() {
		return id;
	}

	public AuctionPlayer getAuctioneer() {
		return auctioneer;
	}

	public ItemStack getItem() {
		return item;
	}

	public int getAmount() {
		return amount;
	}

	public int getStartingAuctionTime() {
		return startingAuctionTime;
	}

	public double getStartingPrice() {
		return Money.toMajor(startingPriceMinor);
	}

	public long getStartingPriceMinor() {
		return startingPriceMinor;
	}

	public double getIncrementPrice() {
		return Money.toMajor(incrementPriceMinor);
	}

	public long getIncrementPriceMinor() {
		return incrementPriceMinor;
	}

	public double getAutoBuyPrice() {
		return Money.toMajor(autoBuyPriceMinor);
	}

	public long getAutoBuyPriceMinor() {
		return autoBuyPriceMinor;
	}

	public boolean isSealed() {
		return isSealed;
	}

	public String getWorld() {
		return world;
	}

	public String getSkullOwner() {
		return skullOwner;
	}

	public int getRepairPrice() {
		return repairPrice;
	}

	public String getMinecraftName() {
		return minecraftName;
	}

	public String getCustomName() {
		return customName;
	}

	public Key getItemKey() {
		return itemKey;
	}

	public void gatherAdditionalData(Logger logger) {
		if (item == null || item.getType() == Material.AIR)
			return;

		ItemMeta meta = item.getItemMeta();
		if (meta instanceof SkullMeta skullMeta) {
			skullOwner = skullMeta.getOwner();
		}

		if (skullOwner == null) {
			skullOwner = "";
		}

		repairPrice = ItemHelper.getXPForRepair(item);

		NamespacedKey typeKey = item.getType().getKey();
		itemKey = Key.key(typeKey.getNamespace(), typeKey.getKey());

		minecraftName = ItemHelper.getMinecraftName(item);
		customName = minecraftName;
		if (meta != null && !meta.getDisplayName().isBlank()) {
			Component legacySection = LegacyComponentSerializer.legacySection().deserialize(meta.getDisplayName());
			customName = MiniMessage.miniMessage().serialize(legacySection);
		}
	}

	public void fillDefaults(@NotNull ConfigController configController) {
		FileConfiguration config = configController.getConfig();

		if (startingAuctionTime == 0) {
			startingAuctionTime = config.getInt("auctions.default.auction-time");
		}

		if (incrementPriceMinor == 0) {
			incrementPriceMinor = Money.fromMajor(config.getDouble("auctions.default.increment"));
		}
	}

	public boolean validate(@NotNull ConfigController configController, @NotNull MessageController messages,
	                        Player player) {
		FileConfiguration config = configController.getConfig();

		truncateDecimals(config);
		return validateGameMode(config, messages, player)
				&& validateWorld(config, messages, player)
				&& validateType(config, messages, player)
				&& findAmount(messages, player)
				&& validateDamage(config, messages, player)
				&& validateStartingPrice(config, messages, player)
				&& validateIncrement(config, messages, player)
				&& validateAutoBuy(config, messages, player)
				&& validateTime(config, messages, player);
	}

	private void truncateDecimals(FileConfiguration config) {
		startingPriceMinor = truncateToDecimalPlace(startingPriceMinor,
				config.getInt("auctions.decimal.starting-price"));
		incrementPriceMinor = truncateToDecimalPlace(incrementPriceMinor,
				config.getInt("auctions.decimal.increment"));
		autoBuyPriceMinor = truncateToDecimalPlace(autoBuyPriceMinor,
				config.getInt("auctions.decimal.autobuy"));
	}

	private long truncateToDecimalPlace(long minor, int decimalPlaces) {
		int safePlaces = Math.max(0, Math.min(Money.SCALE, decimalPlaces));
		long factor = (long) Math.pow(10, Money.SCALE - safePlaces);
		return Math.round((double) minor / factor) * factor;
	}

	private boolean validateStartingPrice(FileConfiguration config, MessageController messages, Player player) {
		long min = Money.fromMajor(config.getDouble("auctions.minimum.starting-price"));
		long max = Money.fromMajor(config.getDouble("auctions.maximum.starting-price"));
		if (startingPriceMinor <= 0 || startingPriceMinor < min) {
			messages.sendMessage(player, "command.auction.start.invalid_start_price.min",
					Formatter.number("min", Money.toMajor(min)),
					Formatter.number("max", Money.toMajor(max)),
					Formatter.number("entered", getStartingPrice()));
			return false;
		}

		if (startingPriceMinor > max && max != 0) {
			messages.sendMessage(player, "command.auction.start.invalid_start_price.max",
					Formatter.number("min", Money.toMajor(min)),
					Formatter.number("max", Money.toMajor(max)),
					Formatter.number("entered", getStartingPrice()));
			return false;
		}

		return true;
	}

	private boolean validateIncrement(FileConfiguration config, MessageController messages, Player player) {
		double minConfigured = config.getDouble("auctions.minimum.increment");
		double maxConfigured = config.getDouble("auctions.maximum.increment");

		if (minConfigured == -1 && maxConfigured == -1) {
			incrementPriceMinor = Money.fromMajor(config.getDouble("auctions.default.increment"));
			return true;
		}

		long min = Money.fromMajor(Math.max(0, minConfigured));
		long max = Money.fromMajor(Math.max(0, maxConfigured));
		if (incrementPriceMinor <= 0 || incrementPriceMinor < min
				|| (incrementPriceMinor > max && max != 0)) {
			messages.sendMessage(player, "command.auction.start.invalid-inc",
					Formatter.number("min", Money.toMajor(min)),
					Formatter.number("max", Money.toMajor(max)),
					Formatter.number("entered", getIncrementPrice()));
			return false;
		}

		return true;
	}

	private boolean validateAutoBuy(FileConfiguration config, MessageController messages, Player player) {
		double minConfigured = config.getDouble("auctions.minimum.autobuy");
		double maxConfigured = config.getDouble("auctions.maximum.autobuy");

		if (minConfigured == -1 && maxConfigured == -1) {
			autoBuyPriceMinor = Money.fromMajor(config.getDouble("auctions.default.autobuy"));
			return true;
		}

		if (autoBuyPriceMinor == 0) {
			return true;
		}

		long min = Math.max(startingPriceMinor, Money.fromMajor(Math.max(0, minConfigured)));
		long max = Money.fromMajor(Math.max(0, maxConfigured));
		if (autoBuyPriceMinor < min || (autoBuyPriceMinor > max && max != 0)) {
			messages.sendMessage(player, "command.auction.start.invalid-buyout",
					Formatter.number("min", Money.toMajor(min)),
					Formatter.number("max", Money.toMajor(max)),
					Formatter.number("entered", getAutoBuyPrice()));
			return false;
		}

		return true;
	}

	private boolean validateTime(FileConfiguration config, MessageController messages, Player player) {
		double min = config.getDouble("auctions.minimum.auction-time");
		double max = config.getDouble("auctions.maximum.auction-time");

		if (min == -1 && max == -1) {
			startingAuctionTime = config.getInt("auctions.default.auction-time");
			return true;
		}

		if (startingAuctionTime <= 0 || startingAuctionTime < min || (startingAuctionTime > max && max != 0)) {
			messages.sendMessage(player, "command.auction.start.invalid-time",
					Formatter.number("min", min),
					Formatter.number("max", max),
					Formatter.number("entered", startingAuctionTime));
			return false;
		}

		return true;
	}

	private boolean validateDamage(FileConfiguration config, MessageController messages, Player player) {
		ItemMeta meta = item.getItemMeta();
		if (meta instanceof Damageable damageable && damageable.hasDamage()
				&& config.getBoolean("auctions.toggles.restrict-damaged")) {
			messages.sendMessage(player, "command.auction.start.damaged_item");
			return false;
		}

		return true;
	}

	private boolean validateGameMode(FileConfiguration config, MessageController messages, Player player) {
		if (player.getGameMode() == GameMode.CREATIVE && config.getBoolean("auctions.toggles.deny-creative")) {
			messages.sendMessage(player, "command.auction.start.deny-creative");
			return false;
		}

		return true;
	}

	private boolean validateWorld(FileConfiguration config, MessageController messages, Player player) {
		if (config.getStringList("auctions.blocked-worlds").stream().anyMatch(blocked -> blocked.equalsIgnoreCase(player.getWorld().getName()))) {
			messages.sendMessage(player, "command.auction.start.blocked-worlds");
			return false;
		}

		return true;
	}

	private boolean validateType(FileConfiguration config, MessageController messages, Player player) {
		if (item.getType() == Material.AIR) {
			messages.sendMessage(player, "command.auction.start.cannot_auction_air");
			return false;
		}

		String typeString = item.getType().toString();
		if (config.getStringList("auctions.blocked-materials").stream().anyMatch(blocked -> blocked.equalsIgnoreCase(typeString))) {
			messages.sendMessage(player, "command.auction.start.blocked-materials");
			return false;
		}

		return true;
	}

	private boolean findAmount(MessageController messages, Player player) {
		if (amountString.equals("h") || amountString.equals("hand")) {
			amount = item.getAmount();
		} else if (amountString.equals("a") || amountString.equals("all")) {
			amount = ItemHelper.getAmountOfItemInInventory(player, item);
		} else {
			try {
				amount = Integer.parseInt(amountString);
				double amountInInventory = ItemHelper.getAmountOfItemInInventory(player, item);

				if (amount <= 0 || amount > amountInInventory)
					throw new IllegalArgumentException();
			} catch (Exception e) {
				messages.sendMessage(player, "command.auction.start.invalid-amt");
				return false;
			}
		}

		return true;
	}

	public boolean giveItemToPlayer(AuctionPlayer auctionPlayer, AuctionPlayerController playerController,
	                                TaskScheduler scheduler, ConfigController config, MessageController messages) {
		Player player = auctionPlayer.getOnlinePlayer();
		if (player == null) {
			addSavedItemToPlayer(auctionPlayer, playerController, scheduler);
			return false;
		}

		if (config.getConfig().getBoolean("auctions.per-world-auctions")
				&& !player.getWorld().getName().equals(world)) {
			messages.sendMessage(player, "reward.wrong_world", Placeholder.unparsed("itemworld", world));
			addSavedItemToPlayer(auctionPlayer, playerController, scheduler);
			return false;
		}

		if (config.getConfig().getStringList("auctions.blocked-worlds").contains(player.getWorld().getName())) {
			messages.sendMessage(player, "reward.blocked_world");
			addSavedItemToPlayer(auctionPlayer, playerController, scheduler);
			return false;
		}

		scheduler.runPlayerRegionTask(() -> {
			boolean overflow = ItemHelper.addItemToPlayerInventory(player, item, amount);
			if (overflow) {
				messages.sendMessage(player, "reward.full_inventory");
			}
		}, player);

		return true;
	}

	public void addSavedItemToPlayer(AuctionPlayer auctionPlayer, AuctionPlayerController playerController,
	                                  TaskScheduler scheduler) {
		scheduler.runAsyncTask(() -> playerController.getPlayerFromDatabase(auctionPlayer.getUniqueId())
				.thenAccept((newAuctionPlayer) -> {
					SavedItem savedItem = new SavedItem(newAuctionPlayer, item, amount, world);
					newAuctionPlayer.getSavedItems().add(savedItem);
				}));
	}
}
