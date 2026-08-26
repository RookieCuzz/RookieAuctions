package me.elian.ezauctions.immersive;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Serializable, world-aware location value. */
public record VenuePoint(
		@NotNull String worldName,
		@Nullable UUID worldId,
		double x,
		double y,
		double z,
		float yaw,
		float pitch
) {
	public VenuePoint {
		Objects.requireNonNull(worldName, "worldName");
		if (worldName.isBlank()) {
			throw new IllegalArgumentException("World name must not be blank");
		}
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
				|| !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
			throw new IllegalArgumentException("Venue coordinates and rotation must be finite");
		}
	}

	public static @NotNull VenuePoint from(@NotNull Location location) {
		World world = Objects.requireNonNull(location.getWorld(), "location world");
		return new VenuePoint(world.getName(), world.getUID(), location.getX(), location.getY(),
				location.getZ(), location.getYaw(), location.getPitch());
	}

	public @NotNull Optional<World> resolveWorld(@NotNull Server server) {
		World world = worldId == null ? null : server.getWorld(worldId);
		if (world == null) {
			world = server.getWorld(worldName);
		}
		return Optional.ofNullable(world);
	}

	public @NotNull Optional<Location> resolve(@NotNull Server server) {
		return resolveWorld(server).map(world -> new Location(world, x, y, z, yaw, pitch));
	}
}
