package me.elian.ezauctions.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import me.elian.ezauctions.session.AttendanceState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Registration, venue mode, and recoverable return location for one buyer. */
@DatabaseTable(tableName = "ezAuctions_Attendance")
public class AuctionAttendanceRecord {
	@DatabaseField(id = true)
	private String id;

	@DatabaseField(canBeNull = false, index = true)
	private String sessionId;

	@DatabaseField(canBeNull = false, index = true)
	private String playerId;

	@DatabaseField(canBeNull = false, index = true)
	private String state;

	@DatabaseField
	private String returnWorld;

	@DatabaseField
	private double returnX;

	@DatabaseField
	private double returnY;

	@DatabaseField
	private double returnZ;

	@DatabaseField
	private float returnYaw;

	@DatabaseField
	private float returnPitch;

	@DatabaseField
	private long createdAtMillis;

	@DatabaseField
	private long updatedAtMillis;

	public AuctionAttendanceRecord() {
	}

	public AuctionAttendanceRecord(@NotNull String sessionId, @NotNull UUID playerId,
	                               long createdAtMillis) {
		if (sessionId.isBlank()) {
			throw new IllegalArgumentException("Session id must not be blank");
		}
		this.id = idFor(sessionId, playerId).toString();
		this.sessionId = sessionId;
		this.playerId = playerId.toString();
		this.state = AttendanceState.REGISTERED.name();
		this.createdAtMillis = createdAtMillis;
		this.updatedAtMillis = createdAtMillis;
	}

	public static @NotNull UUID idFor(@NotNull String sessionId, @NotNull UUID playerId) {
		return UUID.nameUUIDFromBytes(("ezAuctions:attendance:" + sessionId + ":" + playerId)
				.getBytes(StandardCharsets.UTF_8));
	}

	public @NotNull UUID getId() {
		return UUID.fromString(id);
	}

	public @NotNull String getSessionId() {
		return sessionId;
	}

	public @NotNull UUID getPlayerId() {
		return UUID.fromString(playerId);
	}

	public @NotNull AttendanceState getState() {
		return AttendanceState.valueOf(state);
	}

	public boolean hasReturnLocation() {
		return returnWorld != null && !returnWorld.isBlank();
	}

	public @Nullable String getReturnWorld() {
		return returnWorld;
	}

	public double getReturnX() {
		return returnX;
	}

	public double getReturnY() {
		return returnY;
	}

	public double getReturnZ() {
		return returnZ;
	}

	public float getReturnYaw() {
		return returnYaw;
	}

	public float getReturnPitch() {
		return returnPitch;
	}

	public long getCreatedAtMillis() {
		return createdAtMillis;
	}

	public long getUpdatedAtMillis() {
		return updatedAtMillis;
	}

	public void setReturnLocation(@NotNull String world, double x, double y, double z,
	                              float yaw, float pitch, long updatedAtMillis) {
		if (world.isBlank() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
				|| !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
			throw new IllegalArgumentException("Invalid return location");
		}
		this.returnWorld = world;
		this.returnX = x;
		this.returnY = y;
		this.returnZ = z;
		this.returnYaw = yaw;
		this.returnPitch = pitch;
		this.updatedAtMillis = updatedAtMillis;
	}
}
