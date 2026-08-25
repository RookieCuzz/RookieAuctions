package me.elian.ezauctions.gui;

import me.elian.ezauctions.model.Money;
import me.elian.ezauctions.model.RewardKind;
import me.elian.ezauctions.model.RewardRecord;
import me.elian.ezauctions.model.RewardState;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

final class MailboxHistoryView {
	private MailboxHistoryView() {
	}

	static @NotNull List<RewardRecord> claimedOnly(@NotNull List<RewardRecord> records) {
		return records.stream().filter(record -> record.getState() == RewardState.DONE).toList();
	}

	static @NotNull List<String> details(@NotNull RewardRecord reward) {
		List<String> lore = new ArrayList<>();
		lore.add("&7类型: &f" + kindName(reward.getKind()));
		if (reward.getKind() == RewardKind.ITEM) {
			lore.add("&7数量: &e" + reward.getAmount());
		} else {
			lore.add("&7金额: &e$" + Money.format(reward.getMoneyMinor()));
		}
		lore.add("&7状态: &7已领取");
		lore.add("&7创建时间: &f" + formatTimestamp(reward.getCreatedAtMillis()));
		lore.add("&7领取时间: &f" + formatTimestamp(reward.getClaimedAtMillis()));
		lore.add("&7奖励 ID: &8" + reward.getId());
		return lore;
	}

	static @NotNull String formatTimestamp(long epochMillis) {
		if (epochMillis <= 0L) {
			return "未记录";
		}
		return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(epochMillis));
	}

	private static @NotNull String kindName(@NotNull RewardKind kind) {
		return switch (kind) {
			case ITEM -> "物品";
			case REFUND -> "拍卖退款";
			case INCOME -> "拍卖收入";
		};
	}
}
