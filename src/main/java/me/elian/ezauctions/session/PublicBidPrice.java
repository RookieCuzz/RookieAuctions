package me.elian.ezauctions.session;

import java.util.OptionalLong;

/**
 * Public-safe price disclosure. The sealed variant has no field capable of
 * carrying the authoritative highest bid.
 */
public sealed interface PublicBidPrice permits PublicBidPrice.Visible, PublicBidPrice.Sealed {
	OptionalLong amountMinor();

	static PublicBidPrice visible(long amountMinor) {
		return new Visible(amountMinor);
	}

	static PublicBidPrice sealed() {
		return Sealed.INSTANCE;
	}

	record Visible(long valueMinor) implements PublicBidPrice {
		public Visible {
			if (valueMinor < 0) {
				throw new IllegalArgumentException("Public price must not be negative");
			}
		}

		@Override
		public OptionalLong amountMinor() {
			return OptionalLong.of(valueMinor);
		}
	}

	enum Sealed implements PublicBidPrice {
		INSTANCE;

		@Override
		public OptionalLong amountMinor() {
			return OptionalLong.empty();
		}
	}
}
