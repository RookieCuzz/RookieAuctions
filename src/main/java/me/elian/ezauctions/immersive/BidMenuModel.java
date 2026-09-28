package me.elian.ezauctions.immersive;

import me.elian.ezauctions.model.AuctionView;
import me.elian.ezauctions.model.Money;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Immutable text and revision displayed by one buyer's in-world menu. */
record BidMenuModel(@NotNull String heading, @NotNull Map<BidMenuAction, String> buttons,
                    @Nullable UUID auctionId, long revision, boolean canBid,
                    boolean buyoutEnabled) {
	static BidMenuModel waiting(@NotNull String reason) {
		EnumMap<BidMenuAction, String> buttons = new EnumMap<>(BidMenuAction.class);
		for (BidMenuAction action : BidMenuAction.values()) {
			buttons.put(action, "");
		}
		return new BidMenuModel("拍卖加价\n" + reason, Map.copyOf(buttons),
				null, 0L, false, false);
	}

	static BidMenuModel fromView(@NotNull AuctionView view) {
		if (!view.running()) {
			return waiting("等待下一件拍品");
		}
		long minimum = minimumBid(view);
		EnumMap<BidMenuAction, String> buttons = new EnumMap<>(BidMenuAction.class);
		buttons.put(BidMenuAction.MINIMUM, "最低有效价\n$" + Money.format(cappedByBuyout(view, minimum)));
		buttons.put(BidMenuAction.ONE_STEP,
				"+1 档\n$" + Money.format(cappedByBuyout(view,
						saturatedAdd(minimum, view.incrementMinor()))));
		buttons.put(BidMenuAction.FIVE_STEPS,
				"+5 档\n$" + Money.format(cappedByBuyout(view, saturatedAdd(minimum,
						saturatedMultiply(view.incrementMinor(), 5L)))));
		buttons.put(BidMenuAction.TEN_STEPS,
				"+10 档\n$" + Money.format(cappedByBuyout(view, saturatedAdd(minimum,
						saturatedMultiply(view.incrementMinor(), 10L)))));
		buttons.put(BidMenuAction.CUSTOM, "自定义\n输入金额");
		buttons.put(BidMenuAction.BUYOUT, view.autoBuyMinor() > 0
				? "一口买下\n$" + Money.format(view.autoBuyMinor()) : "一口价\n未启用");

		String price = view.sealed()
				? "密封报价 · 你的最高价 " + (view.viewerHighestBidMinor() > 0
						? "$" + Money.format(view.viewerHighestBidMinor()) : "暂无")
				: "当前价 $" + Money.format(view.currentPriceMinor());
		String heading = "拍卖加价 · " + (view.sealed() ? "密封" : "公开")
				+ "\n" + readableName(view.item()) + " × " + view.amount()
				+ " · " + formatTime(view.remainingSeconds())
				+ "\n" + price;
		boolean canBid = !view.bidProcessing()
				&& (!view.sealed() || view.viewerRemainingBidCount() > 0);
		return new BidMenuModel(heading, Map.copyOf(buttons), view.auctionId(),
				view.revision(), canBid, view.autoBuyMinor() > 0);
	}

	boolean enabled(@NotNull BidMenuAction action) {
		return canBid && (action != BidMenuAction.BUYOUT || buyoutEnabled);
	}

	static long minimumBid(@NotNull AuctionView view) {
		if (view.sealed()) {
			return view.viewerHighestBidMinor() == 0
					? view.startingPriceMinor()
					: saturatedAdd(view.viewerHighestBidMinor(), view.incrementMinor());
		}
		return view.highestBidderId() == null
				? view.startingPriceMinor()
				: saturatedAdd(view.currentPriceMinor(), view.incrementMinor());
	}

	private static long cappedByBuyout(AuctionView view, long amount) {
		return view.autoBuyMinor() > 0 ? Math.min(amount, view.autoBuyMinor()) : amount;
	}

	private static long saturatedAdd(long first, long second) {
		if (first < 0 || second < 0 || Long.MAX_VALUE - first < second) {
			return Long.MAX_VALUE;
		}
		return first + second;
	}

	private static long saturatedMultiply(long first, long second) {
		if (first < 0 || second < 0 || first > Long.MAX_VALUE / second) {
			return Long.MAX_VALUE;
		}
		return first * second;
	}

	private static String readableName(ItemStack item) {
		String name;
		if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
			name = ChatColor.stripColor(item.getItemMeta().getDisplayName());
		} else {
			name = item.getType().name().replace('_', ' ');
		}
		if (name == null) {
			name = item.getType().name();
		}
		return name.length() > 28 ? name.substring(0, 27) + "…" : name;
	}

	private static String formatTime(int seconds) {
		int safe = Math.max(0, seconds);
		return String.format("%02d:%02d", safe / 60, safe % 60);
	}
}
