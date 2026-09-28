package me.elian.ezauctions.immersive;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Lets one player at a time sit on each stair block inside the active auction venue. */
public final class AuctionStairSeatController implements Listener {
	private static final long SWEEP_INTERVAL_TICKS = 5L;

	private final Plugin plugin;
	private final VenueConfig venueConfig;
	private final Consumer<Player> onSit;
	private final Consumer<Player> onLeave;
	private final Map<UUID, Seat> seatsByPlayer = new HashMap<>();
	private final Map<BlockKey, Seat> seatsByBlock = new HashMap<>();
	private BukkitTask sweepTask;
	private boolean started;

	public AuctionStairSeatController(@NotNull Plugin plugin, @NotNull VenueConfig venueConfig,
	                                  @NotNull Consumer<Player> onSit,
	                                  @NotNull Consumer<Player> onLeave) {
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		this.venueConfig = Objects.requireNonNull(venueConfig, "venueConfig");
		this.onSit = Objects.requireNonNull(onSit, "onSit");
		this.onLeave = Objects.requireNonNull(onLeave, "onLeave");
	}

	/** Registers interaction and lifecycle events. Call on the server thread. */
	public void start() {
		if (started) {
			return;
		}
		started = true;
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		sweepTask = plugin.getServer().getScheduler().runTaskTimer(
				plugin, this::sweepSeats, SWEEP_INTERVAL_TICKS, SWEEP_INTERVAL_TICKS);
	}

	/** Removes every temporary seat and fires onLeave once for each seated player. */
	public void shutdown() {
		if (!started && seatsByPlayer.isEmpty()) {
			return;
		}
		started = false;
		HandlerList.unregisterAll(this);
		if (sweepTask != null) {
			sweepTask.cancel();
			sweepTask = null;
		}
		for (Seat seat : new ArrayList<>(seatsByPlayer.values())) {
			try {
				releaseSeat(seat);
			} catch (RuntimeException error) {
				plugin.getLogger().log(Level.SEVERE, "Could not clean up an auction stair seat", error);
			}
		}
	}

	/** Whether this player is still a passenger of a seat owned by this controller. */
	public boolean isSeated(@NotNull Player player) {
		Seat seat = seatsByPlayer.get(player.getUniqueId());
		return seat != null && isMounted(seat);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onInteract(PlayerInteractEvent event) {
		if (!started || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
			return;
		}
		Block block = event.getClickedBlock();
		if (block == null || !(block.getBlockData() instanceof Stairs stairs)) {
			return;
		}
		Player player = event.getPlayer();
		if (player.isSneaking()) {
			return;
		}
		VenueLayout layout = activeLayout();
		if (layout == null || !containsBlock(layout.bounds(), block)) {
			return;
		}
		if (event.getHand() == EquipmentSlot.OFF_HAND) {
			if (isSeated(player)) {
				event.setCancelled(true);
			}
			return;
		}
		if (event.getHand() != EquipmentSlot.HAND) {
			return;
		}

		// Prevent the held item from being used on a chair, including an occupied chair.
		event.setCancelled(true);
		if (player.isInsideVehicle()) {
			return;
		}
		Seat oldSeat = seatsByPlayer.get(player.getUniqueId());
		if (oldSeat != null) {
			releaseSeat(oldSeat);
		}
		BlockKey key = BlockKey.of(block);
		Seat occupant = seatsByBlock.get(key);
		if (occupant != null) {
			if (isMounted(occupant)) {
				return;
			}
			releaseSeat(occupant);
		}

		Location location = seatLocation(block, stairs, player);
		ArmorStand stand = block.getWorld().spawn(location, ArmorStand.class, entity -> {
			entity.setVisible(false);
			entity.setMarker(true);
			entity.setSmall(true);
			entity.setBasePlate(false);
			entity.setGravity(false);
			entity.setCollidable(false);
			entity.setInvulnerable(true);
			entity.setSilent(true);
			// Seats are per-player runtime state; never write them to the world file.
			entity.setPersistent(false);
		});
		if (!stand.addPassenger(player)) {
			stand.remove();
			return;
		}

		Seat seat = new Seat(key, player, stand);
		seatsByPlayer.put(player.getUniqueId(), seat);
		seatsByBlock.put(key, seat);
		try {
			onSit.accept(player);
		} catch (RuntimeException error) {
			releaseSeat(seat);
			throw error;
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDismount(EntityDismountEvent event) {
		if (event.getEntity() instanceof Player player) {
			Seat seat = seatsByPlayer.get(player.getUniqueId());
			if (seat != null && event.getDismounted().getUniqueId().equals(seat.stand.getUniqueId())) {
				releaseSeat(seat);
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onTeleport(PlayerTeleportEvent event) {
		Seat seat = seatsByPlayer.get(event.getPlayer().getUniqueId());
		if (seat != null) {
			releaseSeat(seat);
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		Seat seat = seatsByPlayer.get(event.getPlayer().getUniqueId());
		if (seat != null) {
			releaseSeat(seat);
		}
	}

	@EventHandler
	public void onDeath(PlayerDeathEvent event) {
		Seat seat = seatsByPlayer.get(event.getEntity().getUniqueId());
		if (seat != null) {
			releaseSeat(seat);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent event) {
		Seat seat = seatsByBlock.get(BlockKey.of(event.getBlock()));
		if (seat != null) {
			releaseSeat(seat);
		}
	}

	private void sweepSeats() {
		if (seatsByPlayer.isEmpty()) {
			return;
		}
		VenueLayout layout = activeLayout();
		for (Seat seat : new ArrayList<>(seatsByPlayer.values())) {
			if (layout == null || !seat.player.isOnline() || !isMounted(seat)
					|| !seat.key.worldId().equals(seat.player.getWorld().getUID())) {
				releaseSeat(seat);
				continue;
			}
			Block block = seat.player.getWorld().getBlockAt(
					seat.key.x(), seat.key.y(), seat.key.z());
			if (!containsBlock(layout.bounds(), block)
					|| !(block.getBlockData() instanceof Stairs)) {
				releaseSeat(seat);
			}
		}
	}

	private VenueLayout activeLayout() {
		return venueConfig.isEnabled() ? venueConfig.resolve().layout() : null;
	}

	private boolean isMounted(Seat seat) {
		if (!seat.stand.isValid()) {
			return false;
		}
		Entity vehicle = seat.player.getVehicle();
		return vehicle != null && vehicle.getUniqueId().equals(seat.stand.getUniqueId());
	}

	private void releaseSeat(Seat seat) {
		if (seatsByPlayer.get(seat.player.getUniqueId()) != seat) {
			return;
		}
		seatsByPlayer.remove(seat.player.getUniqueId());
		seatsByBlock.remove(seat.key, seat);
		try {
			seat.stand.remove();
		} finally {
			onLeave.accept(seat.player);
		}
	}

	/** Corner coordinates select whole blocks; the floor block may touch the lower Y face. */
	static boolean containsBlock(InclusiveCuboid bounds, Block block) {
		return bounds.worldId().equals(block.getWorld().getUID())
				&& block.getX() >= Math.floor(bounds.minX())
				&& block.getX() <= Math.floor(bounds.maxX())
				&& block.getY() + 1D >= bounds.minY()
				&& block.getY() <= bounds.maxY()
				&& block.getZ() >= Math.floor(bounds.minZ())
				&& block.getZ() <= Math.floor(bounds.maxZ());
	}

	private static Location seatLocation(Block block, Stairs stairs, Player player) {
		// Paper positions a marker armor stand's passenger about 0.68 blocks below
		// the stand. Place the passenger's feet at the stair's sitting surface.
		double surface = stairs.getHalf() == Bisected.Half.TOP ? 1D : 0.5D;
		Location location = block.getLocation().clone().add(0.5D, surface + 0.63D, 0.5D);
		location.setYaw(player.getLocation().getYaw());
		return location;
	}

	private record BlockKey(UUID worldId, int x, int y, int z) {
		private static BlockKey of(Block block) {
			return new BlockKey(block.getWorld().getUID(),
					block.getX(), block.getY(), block.getZ());
		}
	}

	private record Seat(BlockKey key, Player player, ArmorStand stand) {
	}
}
