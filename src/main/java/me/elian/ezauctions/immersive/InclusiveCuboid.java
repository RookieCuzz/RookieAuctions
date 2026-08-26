package me.elian.ezauctions.immersive;

import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/** A normalized cuboid whose six faces are part of the venue. */
public final class InclusiveCuboid {
	private final UUID worldId;
	private final String worldName;
	private final double minX;
	private final double maxX;
	private final double minY;
	private final double maxY;
	private final double minZ;
	private final double maxZ;

	public InclusiveCuboid(@NotNull Location first, @NotNull Location second) {
		World firstWorld = Objects.requireNonNull(first.getWorld(), "first world");
		World secondWorld = Objects.requireNonNull(second.getWorld(), "second world");
		if (!firstWorld.getUID().equals(secondWorld.getUID())) {
			throw new IllegalArgumentException("Venue corners must be in the same world");
		}
		this.worldId = firstWorld.getUID();
		this.worldName = firstWorld.getName();
		this.minX = Math.min(first.getX(), second.getX());
		this.maxX = Math.max(first.getX(), second.getX());
		this.minY = Math.min(first.getY(), second.getY());
		this.maxY = Math.max(first.getY(), second.getY());
		this.minZ = Math.min(first.getZ(), second.getZ());
		this.maxZ = Math.max(first.getZ(), second.getZ());
	}

	/** Pure-coordinate constructor, useful for validation and tests without loading a world. */
	public InclusiveCuboid(@NotNull UUID worldId, @NotNull String worldName,
	                        double firstX, double firstY, double firstZ,
	                        double secondX, double secondY, double secondZ) {
		this.worldId = Objects.requireNonNull(worldId, "worldId");
		this.worldName = Objects.requireNonNull(worldName, "worldName");
		if (!Double.isFinite(firstX) || !Double.isFinite(firstY) || !Double.isFinite(firstZ)
				|| !Double.isFinite(secondX) || !Double.isFinite(secondY) || !Double.isFinite(secondZ)) {
			throw new IllegalArgumentException("Cuboid bounds must be finite");
		}
		this.minX = Math.min(firstX, secondX);
		this.maxX = Math.max(firstX, secondX);
		this.minY = Math.min(firstY, secondY);
		this.maxY = Math.max(firstY, secondY);
		this.minZ = Math.min(firstZ, secondZ);
		this.maxZ = Math.max(firstZ, secondZ);
	}

	public boolean contains(@NotNull Location location) {
		World world = location.getWorld();
		return world != null && worldId.equals(world.getUID())
				&& contains(location.getX(), location.getY(), location.getZ());
	}

	public boolean contains(@NotNull UUID candidateWorldId, double x, double y, double z) {
		return worldId.equals(candidateWorldId) && contains(x, y, z);
	}

	public boolean contains(double x, double y, double z) {
		return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
				&& x >= minX && x <= maxX
				&& y >= minY && y <= maxY
				&& z >= minZ && z <= maxZ;
	}

	public @NotNull UUID worldId() {
		return worldId;
	}

	public @NotNull String worldName() {
		return worldName;
	}

	public double minX() {
		return minX;
	}

	public double maxX() {
		return maxX;
	}

	public double minY() {
		return minY;
	}

	public double maxY() {
		return maxY;
	}

	public double minZ() {
		return minZ;
	}

	public double maxZ() {
		return maxZ;
	}
}
