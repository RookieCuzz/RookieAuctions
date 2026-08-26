package me.elian.ezauctions;

import com.google.inject.Inject;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.elian.ezauctions.controller.AuctionController;
import me.elian.ezauctions.controller.ConfigController;
import me.elian.ezauctions.controller.session.AuctionSessionController;
import me.elian.ezauctions.model.Auction;
import me.elian.ezauctions.model.AuctionData;
import me.elian.ezauctions.model.Bid;
import me.elian.ezauctions.model.BidList;
import me.elian.ezauctions.model.Money;
import me.elian.ezauctions.session.AuctionPublicLotView;
import me.elian.ezauctions.session.AuctionSessionView;
import me.elian.ezauctions.session.PlannedSession;
import me.elian.ezauctions.session.ScheduledSessionReference;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public class EzAuctionsPlaceholderExpansion extends PlaceholderExpansion {
	private final Plugin plugin;
	private final AuctionController auctionController;
	private final ConfigController configController;
	private final Economy economy;
	private final @Nullable AuctionSessionController sessionController;

	/**
	 * Retained for integrations that constructed the legacy expansion directly.
	 * Session placeholders are blank when no session controller was supplied.
	 */
	@Deprecated(forRemoval = false)
	public EzAuctionsPlaceholderExpansion(Plugin plugin, AuctionController auctionController,
	                                      ConfigController configController, Economy economy) {
		this(plugin, auctionController, configController, economy, null);
	}

	@Inject
	public EzAuctionsPlaceholderExpansion(Plugin plugin, AuctionController auctionController,
	                                      ConfigController configController, Economy economy,
	                                      @Nullable AuctionSessionController sessionController) {
		this.plugin = plugin;
		this.auctionController = auctionController;
		this.configController = configController;
		this.economy = economy;
		this.sessionController = sessionController;
	}

	@Override
	public @NotNull String getIdentifier() {
		return "ezauctions";
	}

	@Override
	public @NotNull String getAuthor() {
		return String.join(" -> ", plugin.getDescription().getAuthors());
	}

	@Override
	public @NotNull String getVersion() {
		return plugin.getDescription().getVersion();
	}

	@Override
	public boolean persist() {
		return true;
	}

	@Override
	public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
		String placeholder = params.toLowerCase(Locale.ROOT);
		String sessionValue = getSessionPlaceholderValue(placeholder);
		if (sessionValue != null) {
			return sessionValue;
		}
		Auction auction = auctionController.getActiveAuction();
		// if there is no active auction -> return a blank string
		if (auction == null) {
			return "";
		}

		AuctionData data = auction.getAuctionData();

		String auctioneerName = data.getAuctioneer().getOfflinePlayer().getName();
		if (auctioneerName == null) {
			auctioneerName = "";
		}

		double highestBidAmount = data.getStartingPrice();
		String highestBidderName = null;
		String highestBidderUniqueId = "";
		BidList bidList = auction.getBidList();
		if (bidList != null) {
			Bid highestBid = bidList.getHighestBid();
			if (highestBid != null && !data.isSealed()) {
				highestBidAmount = highestBid.amount();
				highestBidderName = highestBid.auctionPlayer().getOfflinePlayer().getName();
				highestBidderUniqueId = highestBid.auctionPlayer().getUniqueId().toString();
			}
		}

		if (highestBidderName == null) {
			highestBidderName = "";
		}

		return getAuctionPlaceholderValue(placeholder, data, auctioneerName, highestBidderName, highestBidAmount,
				highestBidderUniqueId, auction.getRemainingSeconds());
	}

	private @Nullable String getSessionPlaceholderValue(@NotNull String placeholder) {
		if (sessionController == null) {
			return isSessionPlaceholder(placeholder)
					? renderSessionPlaceholder(placeholder, null, null, null, null,
							configController.getConfig().getInt(
									"immersive.capacity-per-session", 16), configuredZone())
					: null;
		}
		AuctionSessionView session = sessionController.activeSession().orElse(null);
		AuctionPublicLotView lot = sessionController.currentLot().orElse(null);
		ScheduledSessionReference scheduled = sessionController.nextScheduledSession().orElse(null);
		List<PlannedSession> submissionSessions = sessionController.futureSubmissionSessions();
		java.time.Instant nextStart;
		if (session != null && scheduled != null
				&& session.sessionKey().equals(scheduled.sessionKey())) {
			// nextScheduledSession deliberately prioritizes RUNNING for synchronous dashboards.
			// The dedicated next-session placeholder must advance past that current session.
			nextStart = submissionSessions.stream().findFirst()
					.map(PlannedSession::scheduledStart).orElse(null);
		} else if (scheduled != null) {
			nextStart = scheduled.scheduledStart();
		} else {
			nextStart = submissionSessions.stream().findFirst()
					.map(PlannedSession::scheduledStart).orElse(null);
		}
		return renderSessionPlaceholder(placeholder, session, lot, scheduled, nextStart,
				configController.getConfig().getInt("immersive.capacity-per-session", 16),
				configuredZone());
	}

	private static boolean isSessionPlaceholder(@NotNull String placeholder) {
		return switch (placeholder) {
			case "session_state", "session_start", "session_start_time", "session_remaining",
					"session_estimated_remaining", "session_current_lot", "session_lot_index",
					"session_total_lots", "session_lot_count", "session_lot_progress",
					"session_capacity", "next_session", "next_session_time", "current_mode",
					"session_current_mode", "current_bid", "session_current_bid" -> true;
			default -> false;
		};
	}

	static @Nullable String renderSessionPlaceholder(
			@NotNull String placeholder,
			@Nullable AuctionSessionView session,
			@Nullable AuctionPublicLotView lot,
			@Nullable ScheduledSessionReference scheduled,
			@Nullable java.time.Instant nextStart,
			int configuredCapacity,
			@NotNull ZoneId zone) {
		return switch (placeholder) {
			case "session_state" -> session != null ? session.state().name()
					: scheduled == null ? "" : scheduled.state().name();
			case "session_start", "session_start_time" -> session != null
					? formatSessionTime(session.scheduledStart(), zone)
					: scheduled == null ? "" : formatSessionTime(scheduled.scheduledStart(), zone);
			case "session_remaining", "session_estimated_remaining" ->
					session == null || session.estimatedRemainingSeconds().isEmpty()
					? "" : Long.toString(session.estimatedRemainingSeconds().getAsLong());
			case "session_current_lot", "session_lot_index" ->
					session == null || session.progress().isEmpty()
					? "0" : Integer.toString(session.progress().get().currentOrUpcomingLotNumber());
			case "session_total_lots", "session_lot_count" ->
					session == null ? "0" : Integer.toString(session.lotCount());
			case "session_lot_progress" -> session == null || session.progress().isEmpty()
					? "0/0" : session.progress().get().currentOrUpcomingLotNumber()
					+ "/" + session.lotCount();
			case "session_capacity" -> session == null
					? Integer.toString(configuredCapacity)
					: Integer.toString(session.capacity());
			case "next_session", "next_session_time" -> nextStart == null ? ""
					: formatSessionTime(nextStart, zone);
			case "current_mode", "session_current_mode" -> lot == null ? ""
					: lot.sealed() ? "SEALED" : "OPEN";
			case "current_bid", "session_current_bid" -> lot == null ? ""
					: lot.currentPriceMinor().isPresent()
							? Money.format(lot.currentPriceMinor().getAsLong()) : "已密封";
			default -> null;
		};
	}

	private @NotNull ZoneId configuredZone() {
		try {
			return ZoneId.of(configController.getConfig().getString(
					"immersive.timezone", "Asia/Shanghai"));
		} catch (Exception ignored) {
			return ZoneId.of("Asia/Shanghai");
		}
	}

	private static @NotNull String formatSessionTime(@NotNull java.time.Instant instant,
	                                                @NotNull ZoneId zone) {
		return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(instant);
	}

	private String getAuctionPlaceholderValue(String placeholder, AuctionData data, String auctioneerName,
	                                          String highestBidderName, double highestBidAmount,
	                                          String highestBidderUniqueId, int remainingSeconds) {
		ItemStack item = data.getItem();
		return switch (placeholder) {
			case "auctioneer" -> auctioneerName;
			case "auctioneeruuid" -> data.getAuctioneer().getUniqueId().toString();
			case "itemamount" -> Integer.toString(data.getAmount());
			case "minecraftname" -> data.getMinecraftName();
			case "customname" -> data.getCustomName();
			case "materialtype" -> item.getType().toString().toLowerCase();
			case "startingprice" -> Double.toString(data.getStartingPrice());
			case "highestbidamount" -> Double.toString(highestBidAmount);
			case "highestbidder" -> highestBidderName;
			case "highestbidderuuid" -> highestBidderUniqueId;
			case "increment" -> Double.toString(data.getIncrementPrice());
			case "starttime" -> Integer.toString(data.getStartingAuctionTime());
			case "remainingtime" -> Integer.toString(remainingSeconds);
			case "autobuy" -> Double.toString(data.getAutoBuyPrice());
			case "world" -> data.getWorld();
			case "skullowner" -> data.getSkullOwner();
			case "repairprice" -> Integer.toString(data.getRepairPrice());
			case "antisnipetime" -> Integer.toString(configController.getConfig().getInt("antisnipe.time"));
			case "currencynameplural" -> economy.currencyNamePlural();
			case "currencynamesingular" -> economy.currencyNameSingular();
			default -> null;
		};
	}
}
