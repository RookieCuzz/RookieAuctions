package me.elian.ezauctions.immersive;

import me.elian.ezauctions.Logger;
import me.elian.ezauctions.controller.ConfigController;
import me.elian.ezauctions.data.Database;
import me.elian.ezauctions.model.AuctionAttendanceRecord;
import me.elian.ezauctions.scheduler.CancellableTask;
import me.elian.ezauctions.scheduler.TaskScheduler;
import me.elian.ezauctions.session.AttendanceState;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttendanceServiceRaceTest {
	private static final String SESSION_ID = "2026-08-26/evening";
	private static final Clock FIXED_CLOCK = Clock.fixed(
			Instant.parse("2026-08-26T12:00:00Z"), ZoneOffset.UTC);

	private static ServerMock server;
	private static Plugin plugin;
	private static World world;
	private static VenueConfig venueConfig;
	private static Path pluginDataDirectory;

	@BeforeAll
	static void setUp() {
		server = MockBukkit.mock();
		plugin = MockBukkit.createMockPlugin("AttendanceRaceTest");
		pluginDataDirectory = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
		world = server.addSimpleWorld("world");
		SilentLogger logger = new SilentLogger();
		ConfigController configController = new ConfigController(plugin, logger);
		configureVenue(configController.getConfig(), world);
		venueConfig = new VenueConfig(configController, plugin);
		assertTrue(venueConfig.resolve().validation().valid());
	}

	@AfterAll
	static void tearDown() throws IOException {
		MockBukkit.unmock();
		deleteTree(pluginDataDirectory);
	}

	@Test
	void restoreRechecksSessionAfterTeleportAndReturnsBuyerWhenItEndedInFlight() throws Exception {
		UUID playerId = UUID.randomUUID();
		Location origin = new Location(world, 30.5, 70, -12.5, 45F, 5F);
		AttendanceDatabase database = new AttendanceDatabase(playerId, origin,
				AttendanceState.ACTIVE, false);
		CompletableFuture<Boolean> venueTeleport = new CompletableFuture<>();
		TeleportingPlayer player = new TeleportingPlayer(playerId, origin,
				List.of(venueTeleport, CompletableFuture.completedFuture(true)));
		AtomicBoolean running = new AtomicBoolean(true);
		AttendanceService service = service(database.proxy(), running);

		CompletableFuture<AttendanceResult> recovery = service.recover(player.proxy());
		assertFalse(recovery.isDone());
		running.set(false);
		venueTeleport.complete(true);

		AttendanceResult result = recovery.get(2, TimeUnit.SECONDS);
		assertEquals(AttendanceResult.Status.LEFT, result.status());
		assertEquals(AttendanceState.LEFT, result.state());
		assertEquals(AttendanceState.LEFT, database.state());
		assertEquals(List.of("ACTIVE->PENDING_RETURN", "PENDING_RETURN->LEFT"),
				database.transitions());
		assertEquals(2, player.destinations().size());
		assertEquals(origin, player.destinations().get(1));
		assertFalse(service.isActive(playerId));
	}

	@Test
	void startExposesAFutureForEveryInitiallyOnlinePlayerRecovery() throws Exception {
		PlayerMock online = server.addPlayer();
		CompletableFuture<List<AuctionAttendanceRecord>> attendanceLoad = new CompletableFuture<>();
		Database database = (Database) Proxy.newProxyInstance(Database.class.getClassLoader(),
				new Class<?>[]{Database.class}, (proxy, method, args) -> switch (method.getName()) {
					case "getAttendances" -> attendanceLoad;
					case "toString" -> "DelayedAttendanceDatabaseProxy";
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					default -> throw new AssertionError("Unexpected database call: " + method.getName());
				});
		AttendanceService service = service(database, new AtomicBoolean(true));

		service.start();
		try {
			CompletableFuture<Void> initialRecovery = service.awaitInitialRecovery();
			assertFalse(initialRecovery.isDone());
			attendanceLoad.complete(List.of());
			initialRecovery.get(2, TimeUnit.SECONDS);
			assertTrue(initialRecovery.isDone());
			assertFalse(service.isActive(online.getUniqueId()));
		} finally {
			service.shutdown();
		}
	}

	@Test
	void failedCompensationTeleportKeepsPendingReturnInsteadOfRegistration() throws Exception {
		assertFailedCompensationKeepsPending(CompletableFuture.completedFuture(false));
	}

	@Test
	void exceptionalCompensationTeleportKeepsPendingReturnInsteadOfRegistration() throws Exception {
		assertFailedCompensationKeepsPending(CompletableFuture.failedFuture(
				new IllegalStateException("teleport failed")));
	}

	@Test
	void endSessionFutureRemainsPendingUntilTheOnlineBuyerReturnFinishes() throws Exception {
		UUID playerId = UUID.randomUUID();
		Location origin = new Location(world, 42.5, 72, 9.5, 180F, 0F);
		AttendanceDatabase database = new AttendanceDatabase(playerId, origin,
				AttendanceState.ACTIVE, false);
		CompletableFuture<Boolean> returnTeleport = new CompletableFuture<>();
		DelayedPlayer player = new DelayedPlayer(server, "ReturningBuyer", playerId, returnTeleport);
		server.addPlayer(player);
		player.teleport(new Location(world, 0, 65, 0));
		AttendanceService service = service(database.proxy(), new AtomicBoolean(false));

		CompletableFuture<List<AttendanceResult>> ending = service.endSession(SESSION_ID);
		assertFalse(ending.isDone());
		assertEquals(AttendanceState.PENDING_RETURN, database.state());

		returnTeleport.complete(true);
		List<AttendanceResult> results = ending.get(2, TimeUnit.SECONDS);
		assertEquals(1, results.size());
		assertEquals(AttendanceResult.Status.LEFT, results.getFirst().status());
		assertEquals(AttendanceState.LEFT, database.state());
		assertEquals(List.of("ACTIVE->PENDING_RETURN", "PENDING_RETURN->LEFT"),
				database.transitions());
	}

	private void assertFailedCompensationKeepsPending(
			CompletableFuture<Boolean> returnTeleport) throws Exception {
		UUID playerId = UUID.randomUUID();
		Location origin = new Location(world, -25.5, 65, 18.5, -90F, 0F);
		AttendanceDatabase database = new AttendanceDatabase(playerId, origin,
				AttendanceState.REGISTERED, true);
		TeleportingPlayer player = new TeleportingPlayer(playerId, origin,
				List.of(CompletableFuture.completedFuture(true), returnTeleport));
		AtomicBoolean running = new AtomicBoolean(true);
		AttendanceService service = service(database.proxy(), running);

		AttendanceResult result = service.enter(player.proxy(), SESSION_ID)
				.get(2, TimeUnit.SECONDS);

		assertEquals(AttendanceResult.Status.RETURN_DEFERRED, result.status());
		assertEquals(AttendanceState.PENDING_RETURN, result.state());
		assertEquals(AttendanceState.PENDING_RETURN, database.state());
		assertEquals(List.of("ENTERING->ACTIVE", "ENTERING->PENDING_RETURN"),
				database.transitions());
		assertFalse(database.transitions().stream().anyMatch(value -> value.endsWith("->REGISTERED")));
		assertFalse(service.isActive(playerId));
	}

	private static AttendanceService service(Database database, AtomicBoolean running) {
		AttendanceService service = new AttendanceService(plugin, new ImmediateScheduler(), database,
				venueConfig, new SilentLogger(), FIXED_CLOCK);
		service.setSessionPolicy(new AttendanceSessionPolicy() {
			@Override
			public boolean canRegister(String sessionId) {
				return true;
			}

			@Override
			public boolean isRunning(String sessionId) {
				return running.get();
			}
		});
		return service;
	}

	private static void configureVenue(FileConfiguration config, World configuredWorld) {
		config.set("immersive.enabled", true);
		setPoint(config, VenueLocationType.BUYER_SPAWN, configuredWorld, 0, 65, 0);
		setPoint(config, VenueLocationType.ITEM_DISPLAY, configuredWorld, 2, 66, 0);
		setPoint(config, VenueLocationType.INFO_DISPLAY, configuredWorld, 0, 67, 2);
		setPoint(config, VenueLocationType.CORNER_1, configuredWorld, -10, 50, -10);
		setPoint(config, VenueLocationType.CORNER_2, configuredWorld, 10, 100, 10);
	}

	private static void setPoint(FileConfiguration config, VenueLocationType type, World pointWorld,
	                             double x, double y, double z) {
		String path = "immersive.venue." + type.configKey();
		config.set(path + ".world", pointWorld.getName());
		config.set(path + ".world-uuid", pointWorld.getUID().toString());
		config.set(path + ".x", x);
		config.set(path + ".y", y);
		config.set(path + ".z", z);
		config.set(path + ".yaw", 0D);
		config.set(path + ".pitch", 0D);
	}

	private static void setState(AuctionAttendanceRecord record,
	                             AttendanceState state) throws ReflectiveOperationException {
		Field stateField = AuctionAttendanceRecord.class.getDeclaredField("state");
		stateField.setAccessible(true);
		stateField.set(record, state.name());
	}

	private static void deleteTree(Path root) throws IOException {
		if (root == null || !Files.exists(root)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static final class AttendanceDatabase {
		private final AuctionAttendanceRecord record;
		private final AtomicReference<AttendanceState> state;
		private final boolean failActivation;
		private final List<String> transitions = new ArrayList<>();

		private AttendanceDatabase(UUID playerId, Location origin, AttendanceState initialState,
		                           boolean failActivation) throws ReflectiveOperationException {
			record = new AuctionAttendanceRecord(SESSION_ID, playerId, FIXED_CLOCK.millis());
			record.setReturnLocation(origin.getWorld().getName(), origin.getX(), origin.getY(),
					origin.getZ(), origin.getYaw(), origin.getPitch(), FIXED_CLOCK.millis());
			setState(record, initialState);
			state = new AtomicReference<>(initialState);
			this.failActivation = failActivation;
		}

		private Database proxy() {
			return (Database) Proxy.newProxyInstance(Database.class.getClassLoader(),
					new Class<?>[]{Database.class}, (proxy, method, args) -> switch (method.getName()) {
						case "getAttendances" -> completed(attendancesIn((Collection<?>) args[1]));
						case "getAttendance" -> args[1] instanceof Collection<?> requested
								? completed(attendancesIn(requested))
								: completed(Optional.of(record));
						case "registerAttendance" -> completed(record);
						case "beginAttendanceEntry" -> completed(beginEntry(args));
						case "transitionAttendance" -> completed(transition(
								(AttendanceState) args[2], (AttendanceState) args[3]));
						case "toString" -> "AttendanceDatabaseProxy";
						case "hashCode" -> System.identityHashCode(proxy);
						case "equals" -> proxy == args[0];
						default -> throw new AssertionError("Unexpected database call: " + method.getName());
					});
		}

		private List<AuctionAttendanceRecord> attendancesIn(Collection<?> requestedStates) {
			return requestedStates.contains(state.get()) ? List.of(record) : List.of();
		}

		private boolean beginEntry(Object[] args) throws ReflectiveOperationException {
			if (state.get() != AttendanceState.REGISTERED) {
				return false;
			}
			record.setReturnLocation((String) args[2], (double) args[3], (double) args[4],
					(double) args[5], (float) args[6], (float) args[7], (long) args[8]);
			changeState(AttendanceState.ENTERING);
			return true;
		}

		private boolean transition(AttendanceState expected,
		                           AttendanceState next) throws ReflectiveOperationException {
			transitions.add(expected + "->" + next);
			if (state.get() != expected || failActivation && next == AttendanceState.ACTIVE) {
				return false;
			}
			changeState(next);
			return true;
		}

		private void changeState(AttendanceState next) throws ReflectiveOperationException {
			state.set(next);
			setState(record, next);
		}

		private AttendanceState state() {
			return state.get();
		}

		private List<String> transitions() {
			return List.copyOf(transitions);
		}

		private static <T> CompletableFuture<T> completed(T value) {
			return CompletableFuture.completedFuture(value);
		}
	}

	private static final class DelayedPlayer extends PlayerMock {
		private final CompletableFuture<Boolean> teleportResult;

		private DelayedPlayer(ServerMock server, String name, UUID uniqueId,
		                      CompletableFuture<Boolean> teleportResult) {
			super(server, name, uniqueId);
			this.teleportResult = teleportResult;
		}

		@Override
		public CompletableFuture<Boolean> teleportAsync(Location location,
		                                                   PlayerTeleportEvent.TeleportCause cause,
		                                                   TeleportFlag... teleportFlags) {
			return teleportResult;
		}
	}

	private static final class TeleportingPlayer {
		private final UUID playerId;
		private final Location origin;
		private final Deque<CompletableFuture<Boolean>> teleports;
		private final List<Location> destinations = new ArrayList<>();

		private TeleportingPlayer(UUID playerId, Location origin,
		                          List<CompletableFuture<Boolean>> teleports) {
			this.playerId = playerId;
			this.origin = origin.clone();
			this.teleports = new ArrayDeque<>(teleports);
		}

		private Player proxy() {
			return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
					new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
						case "getUniqueId" -> playerId;
						case "getName" -> "Buyer";
						case "isOnline" -> true;
						case "getLocation" -> origin.clone();
						case "teleportAsync" -> nextTeleport((Location) args[0]);
						case "toString" -> "TeleportingPlayer[" + playerId + "]";
						case "hashCode" -> playerId.hashCode();
						case "equals" -> proxy == args[0];
						default -> defaultValue(method.getReturnType(), method.getName());
					});
		}

		private CompletableFuture<Boolean> nextTeleport(Location destination) {
			destinations.add(destination.clone());
			if (teleports.isEmpty()) {
				throw new AssertionError("Unexpected extra teleport to " + destination);
			}
			return teleports.removeFirst();
		}

		private List<Location> destinations() {
			return List.copyOf(destinations);
		}

		private Object defaultValue(Class<?> type, String methodName) {
			if (!type.isPrimitive()) {
				throw new AssertionError("Unexpected player call: " + methodName);
			}
			if (type == boolean.class) {
				return false;
			}
			if (type == char.class) {
				return '\0';
			}
			return 0;
		}
	}

	private static final class ImmediateScheduler implements TaskScheduler {
		@Override
		public void shutdown() {
		}

		@Override
		public void runPlayerRegionTask(Runnable runnable, Player player) {
			runnable.run();
		}

		@Override
		public void runAsyncPlayerCommandTask(Player player, Runnable runnable) {
			runnable.run();
		}

		@Override
		public void runSyncTask(Runnable runnable) {
			runnable.run();
		}

		@Override
		public void runAsyncTask(Runnable runnable) {
			runnable.run();
		}

		@Override
		public void runAsyncDelayedTask(Runnable runnable, long delaySeconds) {
			runnable.run();
		}

		@Override
		public CancellableTask runAsyncRepeatingTask(Plugin plugin, Runnable runnable,
		                                                      long initialDelaySeconds,
		                                                      long intervalSeconds) {
			return () -> { };
		}

		@Override
		public CancellableTask runSyncRepeatingTask(Plugin plugin, Runnable runnable,
		                                                     long initialDelaySeconds,
		                                                     long intervalSeconds) {
			return () -> { };
		}

		@Override
		public CancellableTask runSyncRepeatingTickTask(Plugin plugin, Runnable runnable,
		                                                         long initialDelayTicks,
		                                                         long intervalTicks) {
			return () -> { };
		}
	}

	private static final class SilentLogger implements Logger {
		@Override
		public void info(String message) {
		}

		@Override
		public void warning(String message) {
		}

		@Override
		public void warning(String message, Exception exception) {
		}

		@Override
		public void severe(String message) {
		}

		@Override
		public void severe(String message, Exception exception) {
		}
	}
}
