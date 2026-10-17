package com.example;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SortManager {

	public enum Mode {
		ALL("All"),
		ORES("Ores"),
		MOBS("Mobs"),
		POTIONS("Potions"),
		CRYSTALS("Crystals"),
		TIME("Time Mach");

		public final String label;

		Mode(String label) {
			this.label = label;
		}

		public Mode next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	private static final List<String> RARITIES = List.of(
			"common", "uncommon", "rare", "epic", "legendary", "mythic",
			"godly", "special", "galatic", "galactic", "astronomic"
	);

	private static final Map<String, Double> SIZES = Map.of(
			"baby", 1.0, "small", 1.25, "normal", 1.5, "large", 1.75,
			"giant", 2.25, "colossal", 3.0, "huge", 5.0, "titanic", 15.0
	);

	private static final Pattern BOOST_PATTERN = Pattern.compile("(?:Boost:\\s*)?([\\d.]+)x");
	private static final Pattern SIZE_PATTERN = Pattern.compile("(?i)Size:\\s*\\[?([a-z]+)\\]?");
	private static final Pattern NPC_DAMAGE_PATTERN = Pattern.compile("(?i)damage:\\s*([\\d.,]+)\\s*([a-z]*)");
	private static final Pattern POWER_PATTERN = Pattern.compile("Power:\\s*[\\d.]+%\\s*-\\s*([\\d.]+)%");
	private static final Pattern GENERATE_PATTERN = Pattern.compile("Generate:\\s*(?:(\\d+)h)?\\s*(?:(\\d+)m)?\\s*(?:(\\d+)s)?");

	public Mode mode = Mode.ALL;

	private final Deque<int[]> clickQueue = new ArrayDeque<>();
	private int queueSyncId = -1;
	private int clicksPerTick = 8;

	// ---------- QUEUE ----------

	public void tick(MinecraftClient client) {
		if (clickQueue.isEmpty()) {
			clicksPerTick = 8;
			return;
		}
		if (!(client.currentScreen instanceof HandledScreen<?> screen)
				|| screen.getScreenHandler().syncId != queueSyncId
				|| client.interactionManager == null) {
			clickQueue.clear();
			clicksPerTick = 8;
			return;
		}
		for (int i = 0; i < clicksPerTick && !clickQueue.isEmpty(); i++) {
			int[] click = clickQueue.poll();
			client.interactionManager.clickSlot(queueSyncId, click[0], click[1], SlotActionType.values()[click[2]], client.player);
		}
	}

	public void cancelAll() {
		clickQueue.clear();
	}

	public boolean isBusy() {
		return !clickQueue.isEmpty();
	}

	private void enqueue(int syncId, int slotId, int button, SlotActionType type) {
		queueSyncId = syncId;
		clickQueue.add(new int[]{slotId, button, type.ordinal()});
	}

	public void quickMove(int syncId, int slotId) {
		enqueue(syncId, slotId, 0, SlotActionType.QUICK_MOVE);
	}

	public void pickup(int syncId, int slotId) {
		enqueue(syncId, slotId, 0, SlotActionType.PICKUP);
	}

	// ---------- SORT ----------

	public void sort(MinecraftClient client, HandledScreen<?> screen) {
		if (isBusy() || client.player == null) return;

		ScreenHandler handler = screen.getScreenHandler();
		List<Slot> region = getSortRegion(client, screen);
		if (region.size() < 2) {
			feedback(client, "§7Nothing to sort here");
			return;
		}

		List<ItemStack> current = new ArrayList<>();
		for (Slot slot : region) {
			current.add(slot.getStack().copy());
		}

		List<ItemStack> desired = buildDesiredOrder(current);

		boolean changed = false;
		for (int i = 0; i < current.size(); i++) {
			if (!ItemStack.areEqual(current.get(i), desired.get(i))) {
				changed = true;
				break;
			}
		}
		if (!changed) {
			feedback(client, "§7Already sorted §8(§f" + mode.label + "§8)");
			return;
		}

		for (int target = 0; target < desired.size(); target++) {
			ItemStack want = desired.get(target);
			if (want.isEmpty()) continue;
			if (ItemStack.areEqual(current.get(target), want)) continue;

			int source = -1;
			for (int i = target + 1; i < current.size(); i++) {
				if (ItemStack.areEqual(current.get(i), want)) {
					source = i;
					break;
				}
			}
			if (source == -1) continue;

			int syncId = handler.syncId;
			int slotA = region.get(source).id;
			int slotB = region.get(target).id;

			enqueue(syncId, slotA, 0, SlotActionType.PICKUP);
			enqueue(syncId, slotB, 0, SlotActionType.PICKUP);
			if (!current.get(target).isEmpty()) {
				enqueue(syncId, slotA, 0, SlotActionType.PICKUP);
			}

			ItemStack tmp = current.get(target);
			current.set(target, current.get(source));
			current.set(source, tmp);
		}

		feedback(client, "§7Sorting by §f" + mode.label + "§7...");
	}

	private void feedback(MinecraftClient client, String msg) {
		client.player.sendMessage(Text.literal(msg), true);
	}

	private List<Slot> getSortRegion(MinecraftClient client, HandledScreen<?> screen) {
		List<Slot> region = new ArrayList<>();
		boolean playerInv = screen instanceof InventoryScreen;

		for (Slot slot : screen.getScreenHandler().slots) {
			boolean isPlayerSlot = slot.inventory instanceof PlayerInventory;
			if (playerInv) {
				if (isPlayerSlot && slot.getIndex() >= 9 && slot.getIndex() <= 35) {
					region.add(slot);
				}
			} else {
				if (!isPlayerSlot) {
					region.add(slot);
				}
			}
		}
		return region;
	}

	private List<ItemStack> buildDesiredOrder(List<ItemStack> current) {
		List<ItemStack> category = new ArrayList<>();
		List<ItemStack> other = new ArrayList<>();
		int empties = 0;

		for (ItemStack stack : current) {
			if (stack.isEmpty()) {
				empties++;
			} else if (matchesMode(stack)) {
				category.add(stack);
			} else {
				other.add(stack);
			}
		}

		category.sort(comparator());

		List<ItemStack> desired = new ArrayList<>();
		desired.addAll(category);
		desired.addAll(other);
		for (int i = 0; i < empties; i++) {
			desired.add(ItemStack.EMPTY);
		}
		return desired;
	}

	public boolean matches(ItemStack stack) {
		return matchesMode(stack);
	}

	private boolean matchesMode(ItemStack stack) {
		return switch (mode) {
			case ALL -> categoryIndex(stack) >= 0;
			case ORES -> OreData.find(stack.getName().getString()) != null;
			case MOBS -> mobSize(stack) > 0;
			case POTIONS -> boostValue(stack) > 0;
			case CRYSTALS -> crystalValue(stack) > 0;
			case TIME -> timeValue(stack) > 0;
		};
	}

	private Comparator<ItemStack> comparator() {
		return switch (mode) {
			case ALL -> Comparator.comparingInt(SortManager::categoryIndex)
					.thenComparing(Comparator.comparingDouble(SortManager::categoryValue).reversed());
			case ORES -> Comparator.comparingDouble(SortManager::oreValue).reversed();
			case MOBS -> Comparator.<ItemStack>comparingInt(stack -> isFactoryNpc(stack) ? 0 : 1)
					.thenComparing((a, b) -> {
						if (isFactoryNpc(a) && isFactoryNpc(b)) {
							return Double.compare(npcDamage(b), npcDamage(a));
						}
						return Comparator.comparingDouble(SortManager::mobSize)
								.thenComparingInt(SortManager::rarityIndex).reversed().compare(a, b);
					});
			case POTIONS -> Comparator.comparingDouble(SortManager::boostValue).reversed();
			case CRYSTALS -> Comparator.comparingDouble(SortManager::crystalValue).reversed();
			case TIME -> Comparator.comparingDouble(SortManager::timeValue).reversed();
		};
	}

	static int categoryIndex(ItemStack stack) {
		if (OreData.find(stack.getName().getString()) != null) return 0;
		if (isFactoryNpc(stack) || mobSize(stack) > 0) return 1;
		if (boostValue(stack) > 0) return 2;
		if (crystalValue(stack) > 0) return 3;
		if (timeValue(stack) > 0) return 4;
		return -1;
	}

	static double categoryValue(ItemStack stack) {
		return switch (categoryIndex(stack)) {
			case 0 -> oreValue(stack);
			case 1 -> isFactoryNpc(stack) ? npcDamage(stack) : mobSize(stack) * 1000 + Math.max(0, rarityIndex(stack));
			case 2 -> boostValue(stack);
			case 3 -> crystalValue(stack);
			case 4 -> timeValue(stack);
			default -> 0;
		};
	}

	// ---------- ITEM ANALYSIS ----------

	private static double oreValue(ItemStack stack) {
		String name = stack.getName().getString();
		OreData ore = OreData.find(name);
		if (ore == null) return -1;
		return ore.value() * OreData.findVariant(name).bonus;
	}

	// maps the server's small-caps display font back to ascii so "ᴛɪᴛᴀɴɪᴄ" parses as "titanic"
	private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

	public static String normalizeText(String input) {
		StringBuilder out = new StringBuilder(input.length());
		for (char c : input.toCharArray()) {
			int idx = SMALL_CAPS.indexOf(c);
			if (idx >= 0) {
				out.append((char) ('a' + idx));
			} else if (c == 'ғ') {
				out.append('f');
			} else if (c == 'ǫ' || c == 'Q') {
				out.append('q');
			} else if (c >= '０' && c <= '９') {
				out.append((char) ('0' + (c - '０')));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	private static double mobSize(ItemStack stack) {
		for (String line : loreLines(stack)) {
			Matcher m = SIZE_PATTERN.matcher(normalizeText(line));
			if (m.find()) {
				Double size = SIZES.get(m.group(1).toLowerCase());
				if (size != null) return size;
			}
		}
		if (isFactoryNpc(stack)) return 999.0;
		boolean isMob = false;
		for (String line : loreLines(stack)) {
			String lower = normalizeText(line).toLowerCase();
			if (lower.contains("farm mob") || lower.contains("produce")) {
				isMob = true;
				break;
			}
		}
		// mob with unreadable size: PROTECT it - never risk a Titanic
		return isMob ? 999.0 : -1;
	}

	static boolean isFactoryNpc(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		if (normalizeText(stack.getName().getString()).toLowerCase(Locale.ROOT).contains("[npc]")) return true;
		for (String line : loreLines(stack)) {
			String lower = normalizeText(line).toLowerCase(Locale.ROOT);
			if (lower.contains("factory miner") || lower.contains("factory npc")) return true;
		}
		return false;
	}

	static double npcDamage(ItemStack stack) {
		if (!isFactoryNpc(stack)) return -1;
		for (String line : loreLines(stack)) {
			Matcher m = NPC_DAMAGE_PATTERN.matcher(normalizeText(line));
			if (!m.find()) continue;
			try {
				double value = Double.parseDouble(m.group(1).replace(",", ""));
				String suffix = m.group(2).toLowerCase(Locale.ROOT);
				double multiplier = switch (suffix) {
					case "k" -> 1e3;
					case "m" -> 1e6;
					case "b" -> 1e9;
					case "t" -> 1e12;
					case "qa" -> 1e15;
					case "qi" -> 1e18;
					case "sx" -> 1e21;
					case "sp" -> 1e24;
					default -> suffix.isEmpty() ? 1 : -1;
				};
				return multiplier < 0 ? -1 : value * multiplier;
			} catch (NumberFormatException ignored) {
				return -1;
			}
		}
		return -1;
	}

	private static int rarityIndex(ItemStack stack) {
		int best = -1;
		for (String line : loreLines(stack)) {
			String lower = line.toLowerCase().trim();
			for (int i = 0; i < RARITIES.size(); i++) {
				if (lower.equals(RARITIES.get(i))) {
					best = Math.max(best, i);
				}
			}
		}
		return best;
	}

	private static double boostValue(ItemStack stack) {
		for (String line : loreLines(stack)) {
			if (line.contains("Boost:")) {
				Matcher m = BOOST_PATTERN.matcher(line);
				if (m.find()) {
					try {
						return Double.parseDouble(m.group(1));
					} catch (NumberFormatException ignored) {
					}
				}
			}
		}
		String name = stack.getName().getString();
		if (name.matches(".*[\\d.]+x.*")) {
			boolean boostItem = false;
			for (String line : loreLines(stack)) {
				if (line.toLowerCase().contains("boost")) {
					boostItem = true;
					break;
				}
			}
			if (boostItem) {
				Matcher m = BOOST_PATTERN.matcher(name);
				if (m.find()) {
					try {
						return Double.parseDouble(m.group(1));
					} catch (NumberFormatException ignored) {
					}
				}
			}
		}
		return -1;
	}

	static double crystalValue(ItemStack stack) {
		boolean isCrystal = stack.getName().getString().contains("Crystal Box");
		if (!isCrystal) {
			for (String line : loreLines(stack)) {
				if (line.contains("Crystal Box") || line.contains("Crystal that boosts")) {
					isCrystal = true;
					break;
				}
			}
		}
		if (!isCrystal) return -1;
		for (String line : loreLines(stack)) {
			Matcher m = POWER_PATTERN.matcher(line);
			if (m.find()) {
				try {
					return Double.parseDouble(m.group(1));
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return 1;
	}

	static double timeValue(ItemStack stack) {
		boolean isMachine = stack.getName().getString().toLowerCase().contains("time machine");
		for (String line : loreLines(stack)) {
			if (!line.contains("Generate:")) continue;
			isMachine = true;
			Matcher m = GENERATE_PATTERN.matcher(line);
			if (m.find()) {
				long h = m.group(1) != null ? Long.parseLong(m.group(1)) : 0;
				long min = m.group(2) != null ? Long.parseLong(m.group(2)) : 0;
				long sec = m.group(3) != null ? Long.parseLong(m.group(3)) : 0;
				long total = h * 3600 + min * 60 + sec;
				if (total > 0) return total;
			}
		}
		return isMachine ? 1 : -1;
	}

	// ---------- TRASH ALL ----------

	public void trashAll(MinecraftClient client, HandledScreen<?> screen) {
		if (isBusy() || client.player == null) return;

		ScreenHandler handler = screen.getScreenHandler();
		int queued = 0;
		int protectedCount = 0;

		for (Slot slot : handler.slots) {
			if (!(slot.inventory instanceof PlayerInventory)) continue;
			if (slot.getIndex() < 0 || slot.getIndex() > 35) continue;
			if (!slot.hasStack()) continue;
			if (!ForgeCalcConfig.slotTrashable(slot.getIndex())) {
				protectedCount++;
				continue;
			}

			if (isTrashable(slot.getStack())) {
				enqueue(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE);
				queued++;
			} else {
				protectedCount++;
			}
		}

		clicksPerTick = 2;
		if (queued > 0) {
			feedback(client, String.format("§7Trashing §f%d §7stacks §8(§a%d protected§8)", queued, protectedCount));
		} else {
			feedback(client, "§7Nothing safe to trash");
		}
	}

	public static boolean isTrashable(ItemStack stack) {
		String name = stack.getName().getString();
		if (isFactoryNpc(stack)) {
			if (!ForgeCalcConfig.trashMobs || !ForgeCalcConfig.trashFactoryNpcs) return false;
			double damage = npcDamage(stack);
			// Unknown NPC damage is always protected, and 0 disables NPC trashing.
			return damage >= 0 && ForgeCalcConfig.trashNpcMaxDamage > 0
					&& damage < ForgeCalcConfig.trashNpcMaxDamage;
		}

		// mobs first: a mob named "Iron Golem" must never be treated as Iron ore
		double size = mobSize(stack);
		if (size > 0) {
			if (!ForgeCalcConfig.trashMobs) return false;
			// 999 = size lore we couldn't read, and 15 = TITANIC
			if (ForgeCalcConfig.trashProtectTitanic && size >= 15.0) return false;
			return size < ForgeCalcConfig.trashMobMax;
		}

		OreData ore = OreData.find(name);
		if (ore != null) {
			if (!ForgeCalcConfig.trashOres) return false;
			double value = ore.value() * OreData.findVariant(name).bonus;
			return value < ForgeCalcConfig.trashOreMax;
		}

		return ForgeCalcConfig.trashOther;
	}

	private static List<String> loreLines(ItemStack stack) {
		LoreComponent lore = stack.get(DataComponentTypes.LORE);
		if (lore == null) return List.of();
		List<String> lines = new ArrayList<>();
		for (Text line : lore.lines()) {
			lines.add(line.getString());
		}
		return lines;
	}
}
