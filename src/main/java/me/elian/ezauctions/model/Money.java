package me.elian.ezauctions.model;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Integer money used by the auction state machine.
 *
 * <p>Vault still exposes doubles, so conversion happens only at the economy boundary. All comparisons,
 * persistence, bidding and fee calculations use minor units to avoid NaN/Infinity and floating-point drift.</p>
 */
public final class Money {
	public static final int SCALE = 2;
	public static final long MINOR_UNITS_PER_MAJOR = 100L;
	public static final long DEFAULT_MAX_MINOR = 100_000_000_000L;

	private Money() {
	}

	public static long fromMajor(double amount) {
		if (!Double.isFinite(amount)) {
			throw new IllegalArgumentException("Money must be finite");
		}

		return fromBigDecimal(BigDecimal.valueOf(amount));
	}

	public static long parseMajor(@NotNull String input, long maximumMinor) {
		String normalized = input.trim()
				.replace("$", "")
				.replace(",", "")
				.replace("_", "");
		if (normalized.isBlank()) {
			throw new IllegalArgumentException("Money is blank");
		}

		BigDecimal parsed;
		try {
			parsed = new BigDecimal(normalized);
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("Money is not numeric", exception);
		}

		long minor = fromBigDecimal(parsed);
		if (minor < 0 || minor > maximumMinor) {
			throw new IllegalArgumentException("Money is outside the configured range");
		}
		return minor;
	}

	public static long fromBigDecimal(@NotNull BigDecimal amount) {
		try {
			return amount.movePointRight(SCALE)
					.setScale(0, RoundingMode.HALF_UP)
					.longValueExact();
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException("Money is outside the supported range", exception);
		}
	}

	public static double toMajor(long minor) {
		return BigDecimal.valueOf(minor, SCALE).doubleValue();
	}

	public static long percentage(long minor, @NotNull BigDecimal percent) {
		if (minor < 0 || percent.signum() < 0) {
			throw new IllegalArgumentException("Money and percentage must not be negative");
		}

		return BigDecimal.valueOf(minor)
				.multiply(percent)
				.divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
				.longValueExact();
	}

	public static @NotNull String format(long minor) {
		NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
		format.setMinimumFractionDigits(SCALE);
		format.setMaximumFractionDigits(SCALE);
		return format.format(toMajor(minor));
	}

	public static long requireRange(long minor, long minimumMinor, long maximumMinor) {
		if (minor < minimumMinor || (maximumMinor > 0 && minor > maximumMinor)) {
			throw new IllegalArgumentException("Money is outside the configured range");
		}
		return minor;
	}
}
