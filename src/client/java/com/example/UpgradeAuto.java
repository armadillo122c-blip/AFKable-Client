package com.example;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Buys upgrades out of the server's Upgrades menu so the player does not have to click
 * through it: walks the rarity tabs, opens each upgrade's purchase page and takes the
 * biggest step it can afford.
 *
 * <p>Every decision comes from the menu's own text - "Cost: Maxed", the bulk tile's
 * "Levels: +N", each step's "Price:" - so the pass can only ever ask for a purchase the
 * server would have allowed anyway. Two tiles on the purchase page are traps: MASTERY
 * resets the level back to 0, so it stays off until the player opts in, and EFFECTS is a
 * cosmetic toggle, so it is never clicked at all.
 */
public final class UpgradeAuto {

	private static final int IDLE = 0;
	private static final int SEND = 1;
	private static final int AWAIT_MENU = 2;
	private static final int CLICK_TAB = 3;
	private static final int AWAIT_TAB = 4;
	private static final int NEXT = 5;
	private static final int AWAIT_PAGE = 6;
	private static final int BUY = 7;
	private static final int AFTER_BUY = 8;
	private static final int BACK = 9;
	private static final int FINISH = 10;

	/** The rarity tabs, in the order the menu lays them out. */
	public static final String[] TIERS = {"common", "uncommon", "rare", "legendary", "mythic"};

	private static final Pattern TIER_TAB = Pattern.compile("(common|uncommon|rare|legendary|mythic)\\s+tier");
	private static final Pattern TIER_TILE = Pattern.compile("(common|uncommon|rare|legendary|mythic)\\s+upgrade");
	private static final Pattern COST = Pattern.compile("cost:\\s*([\\d.,]+[a-z]*)");
	private static final Pattern PRICE = Pattern.compile("price:\\s*([\\d.,]+[a-z]*)");
	private static final Pattern STEP = Pattern.compile("levels:\\s*\\+\\s*([\\d,]+)");
	private static final Pattern MASTERY = Pattern.compile("mastery:\\s*(\\d+)[^/]*/\\s*(\\d+)");
	private static final Pattern HEX_TAG = Pattern.compile("<#[0-9a-fA-F]{6}>");
	private static final Pattern CODE = Pattern.compile("(?i)[§&][0-9a-fk-or]");

	private static final long BACKGROUND_GRACE_MS = 20_000L;

	private static int state = IDLE;
	private static int timer;
	private static int guard;
	private static int tabIndex;
	private static int upgradeIndex;
	private static int rounds;
	private static int boughtUpgrades;
	private static long boughtLevels;
	private static double spent;
	private static long lastRun;
	private static long joinedAt;
	private static String page = "";
	private static List<Integer> tabSlots = new ArrayList<>();
	private static List<Integer> upgradeSlots = new ArrayList<>();

	private UpgradeAuto() {}

	// ---------- PUBLIC ----------

	public static boolean isRunning() {
		return state != IDLE;
	}

	/** Short line for the quick button and the upgrader screen. */
	public static String statusText() {
		if (state == IDLE) return "Idle";
		if (state == SEND || state == AWAIT_MENU) return "Opening menu";
		if (!page.isBlank()) return "Buying " + page;
		String tier = tabIndex >= 0 && tabIndex < TIERS.length ? TIERS[tabIndex] : "";
		return tier.isBlank() ? "Scanning" : "Scanning " + tier;
	}

	public static int boughtUpgrades() {
		return boughtUpgrades;
	}

	public static long boughtLevels() {
		return boughtLevels;
	}

	/** Called when the player joins, so a background pass cannot fire mid-loading. */
	public static void onJoin() {
		joinedAt = System.currentTimeMillis();
		cancelQuietly();
	}

	public static void toggle(MinecraftClient client) {
		if (isRunning()) {
			cancel(client, "Auto upgrader stopped.");
		} else {
			start(client);
		}
	}

	/**
	 * Starts a pass. Runs on the Upgrades menu if it is already open, otherwise sends the
	 * configured command and waits for it.
	 */
	public static void start(MinecraftClient client) {
		if (isRunning() || client == null || client.player == null) return;
		tabIndex = 0;
		upgradeIndex = 0;
		boughtUpgrades = 0;
		boughtLevels = 0;
		spent = 0;
		page = "";
		tabSlots = new ArrayList<>();
		upgradeSlots = new ArrayList<>();
		// 5 tabs x ~12 upgrades x ~45 ticks, plus slack for a slow server
		guard = 9000;
		lastRun = System.currentTimeMillis();

		if (isUpgradesMenu(client)) {
			state = AWAIT_MENU;
		} else {
			state = SEND;
		}
		timer = 2;
		tell(client, "§7Auto upgrader started...");
	}

	public static void cancel(MinecraftClient client, String reason) {
		boolean was = state != IDLE;
		cancelQuietly();
		if (was && reason != null) tell(client, "§c" + reason);
	}

	public static void cancelQuietly() {
		state = IDLE;
		page = "";
		tabSlots = new ArrayList<>();
		upgradeSlots = new ArrayList<>();
	}

	// ---------- TICK ----------

	public static void tick(MinecraftClient client) {
		if (client == null || client.player == null) {
			cancelQuietly();
			return;
		}

		long now = System.currentTimeMillis();
		if (state == IDLE) {
			int minutes = ForgeCalcConfig.upgradeInterval;
			if (minutes > 0 && client.currentScreen == null
					&& now - joinedAt > BACKGROUND_GRACE_MS
					&& now - lastRun > minutes * 60_000L) {
				start(client);
			}
			return;
		}

		if (--guard <= 0) {
			closeScreen(client);
			cancel(client, "Auto upgrader timed out.");
			return;
		}
		if (timer > 0) {
			timer--;
			return;
		}

		switch (state) {
			case SEND -> {
				closeScreen(client);
				String command = ForgeCalcConfig.upgradeCommand.trim();
				if (command.startsWith("/")) command = command.substring(1);
				if (command.isBlank()) {
					cancel(client, "No upgrades command is set.");
					return;
				}
				client.player.networkHandler.sendChatCommand(command);
				state = AWAIT_MENU;
				timer = 30;
			}
			case AWAIT_MENU -> {
				if (!isUpgradesMenu(client)) {
					cancel(client, "The Upgrades menu never opened.");
					return;
				}
				tabSlots = findTabs(handled(client));
				// a build without tabs is just one page of upgrades
				if (tabSlots.isEmpty()) tabSlots = List.of(-1);
				tabIndex = 0;
				state = CLICK_TAB;
				timer = 2;
			}
			case CLICK_TAB -> {
				HandledScreen<?> screen = handled(client);
				if (screen == null) {
					cancel(client, "The Upgrades menu closed early.");
					return;
				}
				if (tabIndex >= tabSlots.size()) {
					state = FINISH;
					timer = 2;
					return;
				}
				int slot = tabSlots.get(tabIndex);
				if (slot >= 0 && !tierEnabled(screen, slot)) {
					tabIndex++;
					timer = 1;
					return;
				}
				if (slot >= 0) click(client, screen, slot);
				state = AWAIT_TAB;
				timer = 10;
			}
			case AWAIT_TAB -> {
				HandledScreen<?> screen = handled(client);
				if (screen == null) {
					cancel(client, "The Upgrades menu closed early.");
					return;
				}
				upgradeSlots = findUpgrades(screen);
				upgradeIndex = 0;
				state = NEXT;
				timer = 2;
			}
			case NEXT -> {
				HandledScreen<?> screen = handled(client);
				if (screen == null) {
					cancel(client, "The Upgrades menu closed early.");
					return;
				}
				if (upgradeIndex >= upgradeSlots.size()) {
					tabIndex++;
					state = CLICK_TAB;
					timer = 4;
					return;
				}
				int slot = upgradeSlots.get(upgradeIndex);
				if (!worthOpening(client, screen, slot)) {
					upgradeIndex++;
					timer = 1;
					return;
				}
				click(client, screen, slot);
				state = AWAIT_PAGE;
				timer = 12;
			}
			case AWAIT_PAGE -> {
				HandledScreen<?> screen = handled(client);
				if (screen == null) {
					cancel(client, "The purchase page closed early.");
					return;
				}
				String title = clean(screen.getTitle().getString());
				if (!title.startsWith("purchase")) {
					// the tile did not open a purchase page, carry on down the tab
					upgradeIndex++;
					state = NEXT;
					timer = 4;
					return;
				}
				page = title.replaceFirst("^purchase\\s*", "").trim();
				rounds = Math.max(1, ForgeCalcConfig.upgradeRounds);
				state = BUY;
				timer = 2;
			}
			case BUY -> {
				HandledScreen<?> screen = handled(client);
				if (screen == null) {
					cancel(client, "The purchase page closed early.");
					return;
				}
				if (!buyOnce(client, screen)) {
					state = BACK;
					timer = 4;
					return;
				}
				state = AFTER_BUY;
				timer = 10;
			}
			case AFTER_BUY -> {
				if (handled(client) == null) {
					cancel(client, "The purchase page closed early.");
					return;
				}
				if (--rounds > 0) {
					state = BUY;
					timer = 2;
				} else {
					state = BACK;
					timer = 2;
				}
			}
			case BACK -> {
				HandledScreen<?> screen = handled(client);
				if (screen == null) {
					cancel(client, "The purchase page closed early.");
					return;
				}
				page = "";
				int back = findBack(screen);
				if (back < 0) {
					// no way back from this page: reopen the menu from scratch
					upgradeIndex++;
					state = SEND;
					timer = 2;
					return;
				}
				click(client, screen, back);
				upgradeIndex++;
				state = NEXT;
				timer = 12;
			}
			case FINISH -> {
				if (ForgeCalcConfig.upgradeCloseWhenDone) closeScreen(client);
				state = IDLE;
				report(client);
			}
			default -> state = IDLE;
		}
	}

	// ---------- DECISIONS ----------

	/**
	 * Reads the list tile so the pass can skip an upgrade without paying the second it
	 * costs to open and close its purchase page.
	 */
	private static boolean worthOpening(MinecraftClient client, HandledScreen<?> screen, int slotId) {
		ItemStack stack = stackAt(screen, slotId);
		if (stack == null) return false;
		String lore = loreOf(stack);

		if (!tierSelected(lore)) return false;
		if (!picked(cleanName(clean(stack.getName().getString())))) return false;

		if (lore.contains("cost: maxed") || lore.contains("fully mastered")) {
			// only a mastery reset has anything left to do on a maxed upgrade
			if (!ForgeCalcConfig.upgradeMastery) return false;
			Matcher mastery = MASTERY.matcher(lore);
			if (!mastery.find()) return false;
			try {
				return Integer.parseInt(mastery.group(1)) < Integer.parseInt(mastery.group(2));
			} catch (NumberFormatException ignored) {
				return false;
			}
		}

		Matcher cost = COST.matcher(lore);
		if (cost.find()) {
			double price = ForgeOreCalculatorClient.parseMoney(cost.group(1));
			if (price > 0 && price > budget(client)) return false;
		}
		return true;
	}

	/** One purchase click on an open purchase page. False when nothing is worth clicking. */
	private static boolean buyOnce(MinecraftClient client, HandledScreen<?> screen) {
		double budget = budget(client);
		int bestSlot = -1;
		long bestLevels = 0;
		double bestPrice = 0;
		int masterySlot = -1;
		boolean sawStep = false;

		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.inventory instanceof PlayerInventory) continue;
			if (!slot.hasStack()) continue;
			ItemStack stack = slot.getStack();
			String name = clean(stack.getName().getString());
			String lore = loreOf(stack);

			// cosmetic toggle - clicking it only turns the player's own particles off
			if (lore.contains("visual effects")) continue;
			if (name.contains("go back") || lore.contains("navigation")) continue;

			if (name.contains("mastery") || lore.contains("upgrade mastery")) {
				if (!lore.contains("fully mastered")) masterySlot = slot.id;
				continue;
			}

			boolean bulk = name.contains("max upgrade") || lore.contains("buys as many levels");
			if (!bulk && !lore.contains("buy levels")) continue;

			Matcher step = STEP.matcher(lore);
			Matcher price = PRICE.matcher(lore);
			if (!step.find() || !price.find()) continue;

			long levels;
			try {
				levels = Long.parseLong(step.group(1).replace(",", ""));
			} catch (NumberFormatException ignored) {
				continue;
			}
			if (levels <= 0) continue;
			sawStep = true;

			double cost = ForgeOreCalculatorClient.parseMoney(price.group(1));
			if (cost > budget) continue;

			// the bulk tile already reports the most levels the balance can carry, so
			// whichever tile offers the most levels for a price we can pay is the click
			if (levels > bestLevels || (levels == bestLevels && cost < bestPrice)) {
				bestSlot = slot.id;
				bestLevels = levels;
				bestPrice = cost;
			}
		}

		if (bestSlot >= 0) {
			click(client, screen, bestSlot);
			boughtUpgrades++;
			boughtLevels += bestLevels;
			spent += bestPrice;
			LiveActivity.push(LiveActivity.Kind.SYSTEM, "Upgraded " + page + " +" + bestLevels);
			return true;
		}

		// nothing left to buy: a maxed page can still be mastered, if the player allows it
		if (!sawStep && masterySlot >= 0 && ForgeCalcConfig.upgradeMastery) {
			click(client, screen, masterySlot);
			LiveActivity.push(LiveActivity.Kind.SYSTEM, "Mastered " + page);
			return true;
		}
		return false;
	}

	private static double budget(MinecraftClient client) {
		double balance = ForgeOreCalculatorClient.findBalance(client);
		if (balance < 0) return Double.MAX_VALUE;
		return balance - Math.max(0, ForgeCalcConfig.upgradeReserve);
	}

	// ---------- MENU READING ----------

	private static boolean isUpgradesMenu(MinecraftClient client) {
		HandledScreen<?> screen = handled(client);
		if (screen == null) return false;
		String title = clean(screen.getTitle().getString());
		return title.equals("upgrades") || title.endsWith(" upgrades") || title.startsWith("upgrades ");
	}

	private static List<Integer> findTabs(HandledScreen<?> screen) {
		List<Integer> found = new ArrayList<>();
		if (screen == null) return found;
		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.inventory instanceof PlayerInventory) continue;
			if (!slot.hasStack()) continue;
			if (TIER_TAB.matcher(loreOf(slot.getStack())).find()) found.add(slot.id);
		}
		return found;
	}

	private static List<Integer> findUpgrades(HandledScreen<?> screen) {
		List<Integer> found = new ArrayList<>();
		if (screen == null) return found;
		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.inventory instanceof PlayerInventory) continue;
			if (!slot.hasStack()) continue;
			ItemStack stack = slot.getStack();
			String lore = loreOf(stack);
			if (!TIER_TILE.matcher(lore).find()) continue;
			if (TIER_TAB.matcher(lore).find()) continue;
			found.add(slot.id);
			learn(cleanName(clean(stack.getName().getString())));
		}
		return found;
	}

	/** The GO BACK tile: a tipped arrow on this menu, a plain arrow elsewhere. */
	private static int findBack(HandledScreen<?> screen) {
		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.inventory instanceof PlayerInventory) continue;
			if (!slot.hasStack()) continue;
			if (clean(slot.getStack().getName().getString()).contains("go back")) return slot.id;
		}
		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.inventory instanceof PlayerInventory) continue;
			if (!slot.hasStack()) continue;
			ItemStack stack = slot.getStack();
			if (stack.getItem() == net.minecraft.item.Items.ARROW
					|| stack.getItem() == net.minecraft.item.Items.TIPPED_ARROW
					|| stack.getItem() == net.minecraft.item.Items.SPECTRAL_ARROW) {
				return slot.id;
			}
		}
		return -1;
	}

	private static boolean tierEnabled(HandledScreen<?> screen, int slotId) {
		ItemStack stack = stackAt(screen, slotId);
		if (stack == null) return false;
		Matcher tier = TIER_TAB.matcher(loreOf(stack));
		if (!tier.find()) return true;
		return ForgeCalcConfig.upgradeTiers.contains(tier.group(1));
	}

	private static boolean tierSelected(String lore) {
		Matcher tier = TIER_TILE.matcher(lore);
		if (!tier.find()) return true;
		return ForgeCalcConfig.upgradeTiers.contains(tier.group(1));
	}

	private static boolean picked(String name) {
		if (ForgeCalcConfig.upgradePicks.isEmpty()) return true;
		for (String pick : ForgeCalcConfig.upgradePicks) {
			if (!pick.isBlank() && name.contains(pick)) return true;
		}
		return false;
	}

	/** Remembers the upgrade names seen in the menu so the picker is not empty. */
	private static void learn(String name) {
		if (name.isBlank() || name.length() > 32) return;
		if (ForgeCalcConfig.upgradeKnown.size() >= 120) return;
		if (ForgeCalcConfig.upgradeKnown.add(name)) ForgeCalcConfig.save();
	}

	/** Turns a decorated tile name into the bare upgrade name. */
	static String cleanName(String name) {
		return name.replaceAll("\\[[^\\]]*\\]", "")
				.replaceAll("^[^a-z]*", "")
				.replaceAll("\\s+", " ")
				.trim();
	}

	// ---------- UTIL ----------

	private static HandledScreen<?> handled(MinecraftClient client) {
		return client.currentScreen instanceof HandledScreen<?> screen ? screen : null;
	}

	private static ItemStack stackAt(HandledScreen<?> screen, int slotId) {
		if (screen == null) return null;
		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.id == slotId) return slot.hasStack() ? slot.getStack() : null;
		}
		return null;
	}

	private static void click(MinecraftClient client, HandledScreen<?> screen, int slotId) {
		if (client.interactionManager == null || client.player == null) return;
		try {
			client.interactionManager.clickSlot(screen.getScreenHandler().syncId, slotId, 0,
					SlotActionType.PICKUP, client.player);
		} catch (Throwable ignored) {
		}
	}

	private static void closeScreen(MinecraftClient client) {
		if (client.player != null && client.currentScreen instanceof HandledScreen<?>) {
			client.player.closeHandledScreen();
		}
	}

	private static void tell(MinecraftClient client, String body) {
		ForgeOreCalculatorClient instance = ForgeOreCalculatorClient.INSTANCE;
		if (instance != null && client != null) instance.notifyChat(client, body);
	}

	private static void report(MinecraftClient client) {
		if (boughtUpgrades == 0) {
			tell(client, "§7Auto upgrader: nothing affordable right now.");
			return;
		}
		tell(client, "§aBought §f" + boughtLevels + " §alevel(s) across §f" + boughtUpgrades
				+ " §aupgrade(s) §8(§f$" + ForgeOreCalculatorClient.formatMoney(spent) + "§8)");
	}

	/** Server text down to plain lowercase: small caps, colour codes and hex tags all go. */
	private static String clean(String input) {
		String text = SortManager.normalizeText(input == null ? "" : input);
		text = HEX_TAG.matcher(text).replaceAll("");
		text = CODE.matcher(text).replaceAll("");
		return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
	}

	private static String loreOf(ItemStack stack) {
		LoreComponent lore = stack.get(DataComponentTypes.LORE);
		if (lore == null) return "";
		StringBuilder sb = new StringBuilder();
		for (Text line : lore.lines()) {
			sb.append(clean(line.getString())).append(' ');
		}
		return sb.toString();
	}
}
