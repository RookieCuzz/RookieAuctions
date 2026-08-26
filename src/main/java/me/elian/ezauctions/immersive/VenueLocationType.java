package me.elian.ezauctions.immersive;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Configurable locations that make up the single immersive auction venue. */
public enum VenueLocationType {
	BUYER_SPAWN("buyer-spawn"),
	ITEM_DISPLAY("item-display"),
	INFO_DISPLAY("info-display"),
	CORNER_1("corner1"),
	CORNER_2("corner2");

	private final String configKey;

	VenueLocationType(String configKey) {
		this.configKey = configKey;
	}

	public @NotNull String configKey() {
		return configKey;
	}

	public static @NotNull Optional<VenueLocationType> fromCommandArgument(String value) {
		if (value == null) {
			return Optional.empty();
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
		return Arrays.stream(values())
				.filter(type -> type.configKey.equals(normalized)
						|| type.name().toLowerCase(Locale.ROOT).replace('_', '-').equals(normalized))
				.findFirst();
	}
}
