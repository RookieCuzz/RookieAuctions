package me.elian.ezauctions.helper;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.event.DataComponentValue;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

public class ItemHelper {
	// Memoize reflection operations
	// Valid because both methods are Server API so
	// the values will not change during runtime.
	private static Optional<Boolean> hasAsHoverEvent = Optional.empty();
	private static Optional<Boolean> hasItemMetaAsString = Optional.empty();
	private static Optional<Boolean> hasItemMetaAsComponentString = Optional.empty();

	public static byte[] serialize(@NotNull ItemStack item) throws IllegalStateException {
		try (var outputStream = new ByteArrayOutputStream();
		     var dataOutput = new BukkitObjectOutputStream(outputStream)) {
			dataOutput.writeObject(item);
			dataOutput.flush();
			return Base64.getEncoder().encode(outputStream.toByteArray());
		} catch (Exception e) {
			throw new IllegalStateException("Unable to save item stacks.", e);
		}
	}

	public static @NotNull ItemStack deserialize(byte[] data) throws IOException {
		try (var inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
		     var dataInput = new BukkitObjectInputStream(inputStream)) {
			return (ItemStack) dataInput.readObject();
		} catch (ClassNotFoundException e) {
			throw new IOException("Unable to decode class type.", e);
		}
	}

	public static boolean addItemToPlayerInventory(@NotNull Player player, @NotNull ItemStack itemStack, int amount) {
		ArrayList<ItemStack> items = new ArrayList<>();
		int maxStackSize = itemStack.getMaxStackSize();
		while (amount > maxStackSize) {
			ItemStack clone = itemStack.clone();
			clone.setAmount(maxStackSize);
			items.add(clone);
			amount -= maxStackSize;
		}

		if (amount != 0) {
			ItemStack clone = itemStack.clone();
			clone.setAmount(amount);
			items.add(clone);
		}

		ItemStack[] array = new ItemStack[items.size()];
		array = items.toArray(array);

		HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(array);

		if (leftover.isEmpty())
			return false;

		Location location = player.getLocation();
		World world = player.getWorld();
		for (ItemStack item : leftover.values()) {
			world.dropItem(location, item);
		}

		return true;
	}

	/**
	 * Adds an item only when the complete amount fits. Nothing is dropped and the inventory is restored if the
	 * server unexpectedly reports leftovers.
	 */
	public static boolean addItemToPlayerInventoryNoDrop(@NotNull Player player, @NotNull ItemStack itemStack,
	                                                     int amount) {
		if (amount <= 0 || !canFitInStorage(player, itemStack, amount)) {
			return false;
		}

		PlayerInventory inventory = player.getInventory();
		ItemStack[] before = cloneContents(inventory.getStorageContents());
		HashMap<Integer, ItemStack> leftovers = inventory.addItem(splitStacks(itemStack, amount));
		if (leftovers.isEmpty()) {
			return true;
		}

		inventory.setStorageContents(before);
		return false;
	}

	public static boolean canFitInStorage(@NotNull Player player, @NotNull ItemStack itemStack, int amount) {
		if (amount <= 0) {
			return false;
		}

		int capacity = 0;
		int maxStack = itemStack.getMaxStackSize();
		for (ItemStack existing : player.getInventory().getStorageContents()) {
			if (existing == null || existing.getType() == Material.AIR) {
				capacity += maxStack;
			} else if (existing.isSimilar(itemStack)) {
				capacity += Math.max(0, maxStack - existing.getAmount());
			}
			if (capacity >= amount) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Removes the requested amount atomically from storage slots. It first computes a complete removal plan and
	 * only mutates the inventory after the full amount has been located.
	 */
	public static boolean removeItemFromPlayerInventoryExact(@NotNull Player player, @NotNull ItemStack itemStack,
	                                                          int amount) {
		if (amount <= 0) {
			return false;
		}

		PlayerInventory inventory = player.getInventory();
		ItemStack[] storage = inventory.getStorageContents();
		int remaining = amount;
		Map<Integer, Integer> plan = new HashMap<>();

		for (int slot = 0; slot < storage.length && remaining > 0; slot++) {
			ItemStack existing = storage[slot];
			if (existing == null || !existing.isSimilar(itemStack)) {
				continue;
			}
			int take = Math.min(existing.getAmount(), remaining);
			plan.put(slot, take);
			remaining -= take;
		}

		if (remaining != 0) {
			return false;
		}

		for (Map.Entry<Integer, Integer> entry : plan.entrySet()) {
			int slot = entry.getKey();
			ItemStack existing = inventory.getItem(slot);
			if (existing == null || !existing.isSimilar(itemStack) || existing.getAmount() < entry.getValue()) {
				return false;
			}
		}

		for (Map.Entry<Integer, Integer> entry : plan.entrySet()) {
			int slot = entry.getKey();
			ItemStack existing = inventory.getItem(slot);
			int remainingInStack = existing.getAmount() - entry.getValue();
			if (remainingInStack == 0) {
				inventory.setItem(slot, null);
			} else {
				existing.setAmount(remainingInStack);
				inventory.setItem(slot, existing);
			}
		}
		return true;
	}

	public static @NotNull String fingerprint(@NotNull ItemStack itemStack) {
		ItemStack normalized = itemStack.clone();
		normalized.setAmount(1);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(serialize(normalized));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	public static void removeItemFromPlayerInventory(@NotNull Player player, @NotNull ItemStack itemStack,
	                                                 int amount) {
		int remainingAmount = amount;
		PlayerInventory inventory = player.getInventory();

		ItemStack mainHand = inventory.getItemInMainHand();

		if (mainHand.isSimilar(itemStack)) {
			int itemAmount = mainHand.getAmount();
			if (itemAmount <= remainingAmount) {
				inventory.setItemInMainHand(null);
				remainingAmount -= itemAmount;
			} else {
				mainHand.setAmount(itemAmount - remainingAmount);
				inventory.setItemInMainHand(mainHand);
				return;
			}
		}

		for (int i = 0; i < inventory.getSize(); i++) {
			ItemStack is = inventory.getItem(i);

			if (is == null || !itemStack.isSimilar(is))
				continue;

			if (is.getAmount() > remainingAmount) {
				is.setAmount(is.getAmount() - remainingAmount);
				inventory.setItem(i, is);

				break;
			}

			remainingAmount -= is.getAmount();
			inventory.setItem(i, null);

			if (remainingAmount == 0)
				break;
		}
	}

	public static int getAmountOfItemInInventory(@NotNull Player player, @NotNull ItemStack itemStack) {
		int amountInInventory = 0;

		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getSize(); i++) {
			ItemStack is = inventory.getItem(i);

			if (is == null || !itemStack.isSimilar(is))
				continue;

			amountInInventory += is.getAmount();
		}

		return amountInInventory;
	}

	private static @NotNull ItemStack[] splitStacks(@NotNull ItemStack itemStack, int amount) {
		ArrayList<ItemStack> items = new ArrayList<>();
		int remaining = amount;
		while (remaining > 0) {
			ItemStack clone = itemStack.clone();
			int stackAmount = Math.min(itemStack.getMaxStackSize(), remaining);
			clone.setAmount(stackAmount);
			items.add(clone);
			remaining -= stackAmount;
		}
		return items.toArray(ItemStack[]::new);
	}

	private static @NotNull ItemStack[] cloneContents(@NotNull ItemStack[] contents) {
		return Arrays.stream(contents)
				.map(item -> item == null ? null : item.clone())
				.toArray(ItemStack[]::new);
	}

	private static boolean hasAsHoverEventMethod() {
		if (hasAsHoverEvent.isPresent()) {
			return hasAsHoverEvent.get();
		}

		Boolean methodExists = Boolean.FALSE;
		try {
			// Check if ItemStack has asHoverEvent method exists
			ItemStack.class.getMethod("asHoverEvent", UnaryOperator.class);
			methodExists = Boolean.TRUE;
		} catch (NoSuchMethodException ignored) {
		}
		hasAsHoverEvent = Optional.of(methodExists);
		return methodExists;
	}

	@Nullable
	public static HoverEvent<HoverEvent.ShowItem> getItemHover(@NotNull ItemStack itemStack,
	                                                           UnaryOperator<HoverEvent.ShowItem> transform) {
		NamespacedKey typeKey = itemStack.getType().getKey();
		Key itemKey = Key.key(typeKey.getNamespace(), typeKey.getKey());
		HoverEvent<HoverEvent.ShowItem> simpleHover = HoverEvent.showItem(itemKey, itemStack.getAmount())
				.asHoverEvent(transform);

		// Supported by PaperMC (and forks) servers
		if (hasAsHoverEventMethod()) {
			return itemStack.asHoverEvent(transform);
		}

		// Supported by Spigot 1.21+
		Map<Key, DataComponentValue> components = ComponentHelper.getComponentsFromMeta(itemStack);
		if (!components.isEmpty()) {
			try {
				return HoverEvent.showItem(itemKey, itemStack.getAmount(), components)
						.asHoverEvent(transform);
			} catch (Exception ignored) {
				return simpleHover;
			}
		}
		// Try to get NBT
		String itemNBT = null;
		try {
			itemNBT = getItemNBT(itemStack).replace("minecraft:", "");
		} catch (Exception ignored) {
		}

		if (itemNBT == null) {
			return simpleHover;
		}

		try {
			return HoverEvent.showItem(itemKey, itemStack.getAmount(),
							BinaryTagHolder.binaryTagHolder(itemNBT))
					.asHoverEvent(transform);
		} catch (Exception ignored) {
			return simpleHover;
		}
	}

	private static boolean hasItemMetaGetAsStringMethod() {
		if (hasItemMetaAsString.isPresent()) {
			return hasItemMetaAsString.get();
		}

		Boolean methodExists = Boolean.FALSE;
		try {
			// Check if ItemStack has asHoverEvent method exists
			ItemMeta.class.getMethod("getAsString");
			methodExists = Boolean.TRUE;
		} catch (NoSuchMethodException ignored) {
		}
		hasItemMetaAsString = Optional.of(methodExists);
		return methodExists;
	}

	private static boolean hasItemMetaGetAsComponentStringMethod() {
		if (hasItemMetaAsComponentString.isPresent()) {
			return hasItemMetaAsComponentString.get();
		}

		Boolean methodExists = Boolean.FALSE;
		try {
			ItemMeta.class.getMethod("getAsComponentString");
			methodExists = Boolean.TRUE;
		} catch (NoSuchMethodException ignored) {
		}
		hasItemMetaAsComponentString = Optional.of(methodExists);
		return methodExists;
	}

	public static @NotNull String getItemNBT(@NotNull ItemStack itemStack)
			throws Exception {
		// Supported by Spigot 1.18+
		if (hasItemMetaGetAsStringMethod()) {
			return itemStack.hasItemMeta() ? itemStack.getItemMeta().getAsString() : "{}";
		}

		return getItemNbtNms(itemStack);
	}

	private static @NotNull String getItemNbtNms(@NotNull ItemStack itemStack) throws NoSuchMethodException,
			InvocationTargetException, IllegalAccessException, ClassNotFoundException, InstantiationException {
		Class<? extends ItemStack> itemStackClass = itemStack.getClass();
		Class<?> nbtTagCompoundClass = Class.forName("net.minecraft.nbt.NBTTagCompound");

		// get the nms copy
		Object nmsStack = itemStackClass.getMethod("asNMSCopy", ItemStack.class).invoke(null, itemStack);

		// find the save method from the nms stack
		Method getNbtMethod = null;
		for (Method method : nmsStack.getClass().getMethods()) {
			if (method.getReturnType().equals(nbtTagCompoundClass) && method.getParameterCount() == 0) {
				getNbtMethod = method;
				break;
			}
		}

		if (getNbtMethod == null)
			throw new NoSuchMethodException("Could not save item to nbt! getNbtMethod not found!");

		// get item tag
		Object tag = getNbtMethod.invoke(nmsStack);
		// if no tag, create empty tag
		if (tag == null) {
			tag = nbtTagCompoundClass.getConstructor().newInstance();
		}
		// get string form of nbttagcompound
		return tag.toString();
	}

	public static @NotNull String getMinecraftName(ItemStack is) {
		Material material = is.getType();
		return (material.isBlock() ? "block" : "item") + ".minecraft." + material.toString().toLowerCase();
	}

	/**
	 * @return 0 if the item has never been repaired or -1 if it is no longer repairable.
	 */
	public static int getXPForRepair(ItemStack is) {
		int cost = (int) is.getItemMeta().serialize().getOrDefault("repair-cost", 0);
		boolean repairable = cost <= 40;
		return repairable ? cost : -1;
	}
}
