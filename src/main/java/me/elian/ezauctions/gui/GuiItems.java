package me.elian.ezauctions.gui;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

final class GuiItems {
	private GuiItems() {
	}

	static @NotNull ItemStack item(@NotNull Material material, @NotNull String name, String... lore) {
		return item(material, name, Arrays.asList(lore));
	}

	static @NotNull ItemStack item(@NotNull Material material, @NotNull String name,
	                               @NotNull List<String> lore) {
		ItemStack item = new ItemStack(material);
		ItemMeta meta = item.getItemMeta();
		meta.setDisplayName(color(name));
		meta.setLore(lore.stream().map(GuiItems::color).toList());
		meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
		item.setItemMeta(meta);
		return item;
	}

	static @NotNull ItemStack pane(@NotNull Material material) {
		return item(material, " ");
	}

	static @NotNull ItemStack auctionItem(@NotNull ItemStack source, int amount, String... extraLore) {
		ItemStack item = source.clone();
		item.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
		ItemMeta meta = item.getItemMeta();
		List<String> existing = meta.hasLore() ? meta.getLore() : List.of();
		java.util.ArrayList<String> lore = new java.util.ArrayList<>(existing == null ? List.of() : existing);
		if (extraLore.length > 0) {
			if (!lore.isEmpty()) {
				lore.add(" ");
			}
			for (String line : extraLore) {
				lore.add(color(line));
			}
		}
		meta.setLore(lore);
		item.setItemMeta(meta);
		return item;
	}

	static @NotNull String color(@NotNull String text) {
		return ChatColor.translateAlternateColorCodes('&', text);
	}
}
