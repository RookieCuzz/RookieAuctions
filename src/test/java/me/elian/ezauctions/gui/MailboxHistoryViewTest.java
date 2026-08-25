package me.elian.ezauctions.gui;

import be.seeseemelk.mockbukkit.MockBukkit;
import me.elian.ezauctions.model.RewardKind;
import me.elian.ezauctions.model.RewardRecord;
import me.elian.ezauctions.model.RewardState;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailboxHistoryViewTest {
	@BeforeAll
	static void setUpBukkit() {
		MockBukkit.mock();
	}

	@AfterAll
	static void tearDownBukkit() {
		MockBukkit.unmock();
	}

	@Test
	void historyContainsOnlyCompletedRewardsAcrossAllKinds() {
		UUID owner = UUID.randomUUID();
		List<RewardRecord> allStatesAndKinds = new ArrayList<>();
		for (RewardKind kind : RewardKind.values()) {
			for (RewardState state : RewardState.values()) {
				RewardRecord reward = kind == RewardKind.ITEM
						? RewardRecord.item(owner, UUID.randomUUID(), new ItemStack(Material.DIAMOND), 2, "world")
						: RewardRecord.money(owner, UUID.randomUUID(), kind, 500L);
				reward.setState(state);
				allStatesAndKinds.add(reward);
			}
		}

		List<RewardRecord> claimed = MailboxHistoryView.claimedOnly(allStatesAndKinds);
		assertEquals(3, claimed.size());
		assertTrue(claimed.stream().allMatch(record -> record.getState() == RewardState.DONE));
		assertEquals(Set.of(RewardKind.ITEM, RewardKind.REFUND, RewardKind.INCOME),
				claimed.stream().map(RewardRecord::getKind).collect(java.util.stream.Collectors.toSet()));
		assertTrue(MailboxHistoryView.claimedOnly(allStatesAndKinds.stream()
				.filter(record -> record.getState() != RewardState.DONE).toList()).isEmpty());
	}

	@Test
	void historyDetailsAreReadOnlyAndIncludeAuditFields() {
		RewardRecord reward = RewardRecord.money(UUID.randomUUID(), UUID.randomUUID(), RewardKind.REFUND, 1_250L);
		reward.setState(RewardState.DONE);

		List<String> details = MailboxHistoryView.details(reward);
		assertTrue(details.stream().anyMatch(line -> line.contains("类型") && line.contains("拍卖退款")));
		assertTrue(details.stream().anyMatch(line -> line.contains("$12.5")));
		assertTrue(details.stream().anyMatch(line -> line.contains("创建时间")));
		assertTrue(details.stream().anyMatch(line -> line.contains("领取时间")));
		assertTrue(details.stream().anyMatch(line -> line.contains(reward.getId().toString())));
		assertFalse(details.stream().anyMatch(line -> line.contains("点击") || line.contains("重复领取")));
	}

	@Test
	void missingClaimedTimestampIsExplicit() {
		assertEquals("未记录", MailboxHistoryView.formatTimestamp(0L));
	}

	@Test
	void itemHistoryKeepsOriginalLoreBeforeAuditDetails() {
		ItemStack source = new ItemStack(Material.DIAMOND);
		ItemMeta meta = source.getItemMeta();
		meta.setLore(List.of("Original lore"));
		source.setItemMeta(meta);
		RewardRecord reward = RewardRecord.money(UUID.randomUUID(), UUID.randomUUID(), RewardKind.REFUND, 500L);
		reward.setState(RewardState.DONE);

		ItemStack rendered = GuiItems.auctionItem(source, 2,
				MailboxHistoryView.details(reward).toArray(String[]::new));
		List<String> lore = rendered.getItemMeta().getLore();
		assertEquals("Original lore", lore.get(0));
		assertTrue(lore.stream().anyMatch(line -> line.contains("奖励 ID")));
		assertFalse(lore.stream().anyMatch(line -> line.contains("点击领取") || line.contains("不可重复领取")));
	}
}
