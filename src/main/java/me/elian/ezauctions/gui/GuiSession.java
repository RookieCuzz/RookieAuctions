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
	final AtomicBoolean submitting = new AtomicBoolean();
	GuiPage page = GuiPage.CURRENT;
	GuiPage returnPage = GuiPage.CURRENT;
	AuctionPlayer viewer;
	UUID selectedAuctionId;
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
