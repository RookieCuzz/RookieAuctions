package me.elian.ezauctions.immersive;

import me.elian.ezauctions.model.AuctionView;
import me.elian.ezauctions.model.Money;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/** A buyer's proposed price, tied to the auction revision that produced the menu. */
record BidProposal(@NotNull UUID auctionId, long revision, long amountMinor,
                   boolean buyout, @NotNull String label) {
	BidProposal {
		Objects.requireNonNull(auctionId, "auctionId");
		Objects.requireNonNull(label, "label");
		if (amountMinor <= 0 || label.isBlank()) {
			throw new IllegalArgumentException("A bid proposal needs a positive amount and label");
		}
	}

	static @NotNull BidProposal fromAction(@NotNull BidMenuAction action,
	                                      @NotNull AuctionView view) {
		Objects.requireNonNull(action, "action");
		requireRunning(view);
		if (action == BidMenuAction.CUSTOM) {
			throw new IllegalArgumentException("Custom bids need an entered amount");
		}
		if (action == BidMenuAction.BUYOUT) {
			if (view.autoBuyMinor() <= 0) {
				throw new IllegalArgumentException("This auction has no buyout price");
			}
			return proposal(view, view.autoBuyMinor(), true, "一口价");
		}

		long minimum = positiveMinimum(view);
		long increment = view.incrementMinor();
		if (increment <= 0) {
			throw new IllegalArgumentException("The bid increment must be positive");
		}
		return switch (action) {
			case MINIMUM -> proposal(view, minimum, false, "最低有效价");
			case ONE_STEP -> proposal(view, addSteps(minimum, increment, 1L, view.autoBuyMinor()), false, "+1 档");
			case FIVE_STEPS -> proposal(view, addSteps(minimum, increment, 5L, view.autoBuyMinor()), false, "+5 档");
			case TEN_STEPS -> proposal(view, addSteps(minimum, increment, 10L, view.autoBuyMinor()), false, "+10 档");
			case CUSTOM, BUYOUT -> throw new IllegalStateException("Handled above");
		};
	}

	static @NotNull BidProposal custom(long amountMinor, @NotNull AuctionView view) {
		requireRunning(view);
		if (amountMinor <= 0 || amountMinor < positiveMinimum(view)) {
			throw new IllegalArgumentException("Custom bid is below the minimum effective price");
		}
		return proposal(view, amountMinor, false, "自定义出价");
	}

	private static void requireRunning(AuctionView view) {
		Objects.requireNonNull(view, "view");
		if (!view.running()) {
			throw new IllegalArgumentException("The auction is no longer running");
		}
	}

	private static long positiveMinimum(AuctionView view) {
		long minimum = BidMenuModel.minimumBid(view);
		if (minimum <= 0) {
			throw new IllegalArgumentException("The minimum effective price must be positive");
		}
		return minimum;
	}

	private static long addSteps(long minimum, long increment, long steps, long buyoutMinor) {
		try {
			return Math.addExact(minimum, Math.multiplyExact(increment, steps));
		} catch (ArithmeticException overflow) {
			if (buyoutMinor > 0) {
				return buyoutMinor;
			}
			throw new IllegalArgumentException("Proposed bid is too large", overflow);
		}
	}

	private static BidProposal proposal(AuctionView view, long amountMinor,
	                                    boolean buyout, String label) {
		if (view.autoBuyMinor() > 0 && amountMinor >= view.autoBuyMinor()) {
			amountMinor = view.autoBuyMinor();
			buyout = true;
			label = "一口买下";
		}
		return new BidProposal(view.auctionId(), view.revision(), amountMinor, buyout,
				label + " $" + Money.format(amountMinor));
	}
}
