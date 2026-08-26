package me.elian.ezauctions.immersive;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

/** Read-only hot-path projection used by bid authorization and input listeners. */
public interface AuctionModeAccess {
	boolean isActive(@NotNull UUID playerId);

	@NotNull Optional<String> activeSession(@NotNull UUID playerId);
}
