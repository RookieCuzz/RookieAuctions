package me.elian.ezauctions;

import me.elian.ezauctions.session.AuctionMode;
import me.elian.ezauctions.session.AuctionPublicLotView;
import me.elian.ezauctions.session.AuctionSessionView;
import me.elian.ezauctions.session.LotState;
import me.elian.ezauctions.session.PublicBidPrice;
import me.elian.ezauctions.session.ScheduledSessionReference;
import me.elian.ezauctions.session.SessionProgress;
import me.elian.ezauctions.session.SessionState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PlaceholderSessionContractTest {
	private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
	private static final Instant AFTERNOON = Instant.parse("2026-08-26T06:00:00Z");

	@Test
	void legacyPublicConstructorsRemainBinaryCompatible() throws Exception {
		assertNotNull(EzAuctionsPlaceholderExpansion.class.getConstructor(
				org.bukkit.plugin.Plugin.class,
				me.elian.ezauctions.controller.AuctionController.class,
				me.elian.ezauctions.controller.ConfigController.class,
				net.milkbowl.vault.economy.Economy.class));
		assertNotNull(RookieAuctionsPlaceholderExpansion.class.getConstructor(
				org.bukkit.plugin.Plugin.class,
				me.elian.ezauctions.controller.AuctionController.class,
				me.elian.ezauctions.controller.ConfigController.class,
				net.milkbowl.vault.economy.Economy.class));
	}

	@Test
	void idlePlaceholdersExposeTheLockedUpcomingSession() {
		ScheduledSessionReference locked = new ScheduledSessionReference(
				"2026-08-26/afternoon", AFTERNOON, SessionState.LOCKED);

		assertEquals("LOCKED", render("session_state", null, null, locked, AFTERNOON));
		assertEquals("2026-08-26 14:00",
				render("session_start_time", null, null, locked, AFTERNOON));
		assertEquals("2026-08-26 14:00",
				render("next_session_time", null, null, locked, AFTERNOON));
		assertEquals("16", render("session_capacity", null, null, locked, AFTERNOON));
	}

	@Test
	void runningPlaceholdersExposeProgressEtaAndOpenPrice() {
		AuctionSessionView session = runningSession();
		AuctionPublicLotView lot = lot(AuctionMode.OPEN, PublicBidPrice.visible(12_345L));

		assertEquals("RUNNING", render("session_state", session, lot, null, null));
		assertEquals("87", render("session_remaining", session, lot, null, null));
		assertEquals("2", render("session_current_lot", session, lot, null, null));
		assertEquals("2/3", render("session_lot_progress", session, lot, null, null));
		assertEquals("3", render("session_total_lots", session, lot, null, null));
		assertEquals("OPEN", render("session_current_mode", session, lot, null, null));
		assertEquals("123.45", render("session_current_bid", session, lot, null, null));
	}

	@Test
	void sealedBidPlaceholderNeverDisclosesAnAmount() {
		AuctionPublicLotView sealed = lot(AuctionMode.SEALED, PublicBidPrice.sealed());

		assertEquals("已密封", render("session_current_bid", runningSession(), sealed, null, null));
		assertEquals("SEALED", render("session_current_mode", runningSession(), sealed, null, null));
	}

	private static String render(String placeholder, AuctionSessionView session,
	                             AuctionPublicLotView lot, ScheduledSessionReference scheduled,
	                             Instant nextStart) {
		return EzAuctionsPlaceholderExpansion.renderSessionPlaceholder(
				placeholder, session, lot, scheduled, nextStart, 16, SHANGHAI);
	}

	private static AuctionSessionView runningSession() {
		return new AuctionSessionView(
				"2026-08-26/afternoon", "afternoon", AFTERNOON,
				AFTERNOON.minusSeconds(600), SessionState.RUNNING, 3, 16,
				Optional.of(SessionProgress.running(2, 3, 37)), OptionalLong.of(87));
	}

	private static AuctionPublicLotView lot(AuctionMode mode, PublicBidPrice price) {
		return new AuctionPublicLotView(
				UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
				"2026-08-26/afternoon", 2, LotState.ACTIVE, mode,
				"钻石", 1, "Seller", 10_000L, 100L, 50_000L,
				price, 37, 4L);
	}
}
