package me.elian.ezauctions.immersive;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/** Atomic resolution of config values into either a usable layout or validation errors. */
public record VenueResolution(@NotNull VenueValidation validation, @Nullable VenueLayout layout) {
	public VenueResolution {
		if (validation.valid() != (layout != null)) {
			throw new IllegalArgumentException("Layout presence must match venue validity");
		}
	}

	public @NotNull Optional<VenueLayout> resolvedLayout() {
		return Optional.ofNullable(layout);
	}
}
