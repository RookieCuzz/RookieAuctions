package me.elian.ezauctions.gui;

import me.elian.ezauctions.model.AuctionPlayer;
import me.elian.ezauctions.model.AuctionRecordStatus;
import me.elian.ezauctions.model.RewardKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class GuiSession {
	final UUID token = UUID.randomUUID();
	final AuctionDraft draft = new AuctionDraft();
	AuctionDraft lastSubmittedDraft;
	final AtomicBoolean submitting = new AtomicBoolean();
	GuiPage page = GuiPage.CURRENT;
	GuiPage returnPage = GuiPage.CURRENT;
	AuctionPlayer viewer;
	UUID selectedAuctionId;
	String selectedSessionId;
	boolean selectedSessionRegistered;
	long selectedRevision;
	long proposedBidMinor;
	boolean proposedBuyout;
	int listPage;
	AuctionRecordStatus myFilter = AuctionRecordStatus.ACTIVE;
	RewardKind mailboxFilter = RewardKind.ITEM;
	boolean mailboxHistory;
	InputTarget inputTarget;
	int lastUrgencySecond = Integer.MIN_VALUE;
	int visibleTotal;
	long loadGeneration;
	final Map<Integer, UUID> visibleEntries = new HashMap<>();
	final Map<Integer, String> visibleSessions = new HashMap<>();

	boolean isReady() {
		return viewer != null;
	}

	enum InputTarget {
		BID,
		STARTING_PRICE,
		INCREMENT,
		BUYOUT,
		DURATION
	}
}
