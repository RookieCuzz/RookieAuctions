package me.elian.ezauctions.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Last durable runtime position for a running session. */
@DatabaseTable(tableName = "ezAuctions_RuntimeCheckpoint")
public class AuctionRuntimeCheckpoint {
	@DatabaseField(id = true)
	private String sessionId;

	@DatabaseField(index = true)
	private String currentLotId;

	@DatabaseField
	private int currentLotSequence;

	@DatabaseField
	private int remainingSeconds;

	@DatabaseField
	private long revision;

	@DatabaseField
	private int antiSnipeExtensions;

	@DatabaseField
	private boolean intermission;

	@DatabaseField
	private long updatedAtMillis;

	public AuctionRuntimeCheckpoint() {
	}

	public AuctionRuntimeCheckpoint(@NotNull String sessionId, @Nullable UUID currentLotId,
	                                int currentLotSequence, int remainingSeconds, long revision,
	                                int antiSnipeExtensions, boolean intermission,
	                                long updatedAtMillis) {
		if (sessionId.isBlank()) {
			throw new IllegalArgumentException("Session id must not be blank");
		}
		if (currentLotSequence < 0 || remainingSeconds < 0 || revision < 0
				|| antiSnipeExtensions < 0) {
			throw new IllegalArgumentException("Checkpoint counters must not be negative");
		}
		this.sessionId = sessionId;
		this.currentLotId = currentLotId == null ? null : currentLotId.toString();
		this.currentLotSequence = currentLotSequence;
		this.remainingSeconds = remainingSeconds;
		this.revision = revision;
		this.antiSnipeExtensions = antiSnipeExtensions;
		this.intermission = intermission;
		this.updatedAtMillis = updatedAtMillis;
	}

	public @NotNull String getSessionId() {
		return sessionId;
	}

	public @Nullable UUID getCurrentLotId() {
		return currentLotId == null || currentLotId.isBlank() ? null : UUID.fromString(currentLotId);
	}

	public int getCurrentLotSequence() {
		return currentLotSequence;
	}

	public int getRemainingSeconds() {
		return remainingSeconds;
	}

	public long getRevision() {
		return revision;
	}

	public int getAntiSnipeExtensions() {
		return antiSnipeExtensions;
	}

	public boolean isIntermission() {
		return intermission;
	}

	public long getUpdatedAtMillis() {
		return updatedAtMillis;
	}
}
