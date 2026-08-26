package me.elian.ezauctions.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import me.elian.ezauctions.session.SessionState;
import org.jetbrains.annotations.NotNull;

/**
 * Persisted wall-clock auction session.  This deliberately lives in its own
 * table so installations can upgrade without altering the legacy auction
 * record schema.
 */
@DatabaseTable(tableName = "ezAuctions_Session")
public class AuctionSessionRecord {
	@DatabaseField(id = true)
	private String id;

	@DatabaseField(canBeNull = false, index = true)
	private String state;

	@DatabaseField(index = true)
	private long scheduledStartMillis;

	@DatabaseField
	private long lockAtMillis;

	@DatabaseField
	private int capacity;

	@DatabaseField
	private int sellerLimit;

	@DatabaseField
	private long createdAtMillis;

	@DatabaseField
	private long startedAtMillis;

	@DatabaseField
	private long completedAtMillis;

	@DatabaseField
	private long revision;

	public AuctionSessionRecord() {
	}

	public AuctionSessionRecord(@NotNull String id, long scheduledStartMillis, long lockAtMillis,
	                            int capacity, int sellerLimit, long createdAtMillis) {
		if (id.isBlank()) {
			throw new IllegalArgumentException("Session id must not be blank");
		}
		if (lockAtMillis > scheduledStartMillis) {
			throw new IllegalArgumentException("Session lock time must not be after its start time");
		}
		if (capacity <= 0 || sellerLimit <= 0 || sellerLimit > capacity) {
			throw new IllegalArgumentException("Invalid session capacity or seller limit");
		}
		this.id = id;
		this.state = SessionState.OPEN.name();
		this.scheduledStartMillis = scheduledStartMillis;
		this.lockAtMillis = lockAtMillis;
		this.capacity = capacity;
		this.sellerLimit = sellerLimit;
		this.createdAtMillis = createdAtMillis;
	}

	public @NotNull String getId() {
		return id;
	}

	public @NotNull SessionState getState() {
		return SessionState.valueOf(state);
	}

	public long getScheduledStartMillis() {
		return scheduledStartMillis;
	}

	public long getLockAtMillis() {
		return lockAtMillis;
	}

	public int getCapacity() {
		return capacity;
	}

	public int getSellerLimit() {
		return sellerLimit;
	}

	public long getCreatedAtMillis() {
		return createdAtMillis;
	}

	public long getStartedAtMillis() {
		return startedAtMillis;
	}

	public long getCompletedAtMillis() {
		return completedAtMillis;
	}

	public long getRevision() {
		return revision;
	}
}
