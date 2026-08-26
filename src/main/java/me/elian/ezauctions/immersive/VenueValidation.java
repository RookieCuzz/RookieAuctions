package me.elian.ezauctions.immersive;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Human-readable validation result used by lock checks and admin commands. */
public record VenueValidation(boolean valid, @NotNull List<String> errors) {
	public VenueValidation {
		errors = List.copyOf(errors);
		if (valid && !errors.isEmpty()) {
			throw new IllegalArgumentException("A valid venue cannot contain errors");
		}
	}

	public static @NotNull VenueValidation success() {
		return new VenueValidation(true, List.of());
	}

	public static @NotNull VenueValidation failure(@NotNull List<String> errors) {
		if (errors.isEmpty()) {
			throw new IllegalArgumentException("An invalid venue must describe at least one error");
		}
		return new VenueValidation(false, errors);
	}

	public @NotNull String summary() {
		return valid ? "场地配置有效" : String.join("；", errors);
	}
}
