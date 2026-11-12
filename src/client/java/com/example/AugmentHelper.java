package com.example;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the active research countdown from the server's Augments menu. */
public final class AugmentHelper {
	private static final Pattern RESEARCHING = Pattern.compile(
			"(?i)researching\\s*:\\s*(augment\\s+[\\w-]+).*?((?:\\d+h\\s*)?(?:\\d+m\\s*)?\\d+s)");
	private static final Pattern TIME_LEFT = Pattern.compile(
			"(?i)time\\s*left\\s*:\\s*((?:\\d+h\\s*)?(?:\\d+m\\s*)?\\d+s)");
	private static final Pattern DURATION = Pattern.compile("(?i)(?:(\\d+)h\\s*)?(?:(\\d+)m\\s*)?(\\d+)s");

	private static boolean wasAugmentMenu;
	private static boolean scanPending;
	private static boolean completionAnnounced;
	private static long nextScanAt;
	private static long lastReminderAt;
	private static long researchEndsAt;
	private static String researchName = "";
	private static final long REMINDER_INTERVAL_MS = 60_000L;
	private static final long GUI_SCAN_INTERVAL_MS = 500L;

	private AugmentHelper() {}

	/** Called from the client tick; syncs once per menu open so the local countdown cannot freeze. */
	public static void tick(MinecraftClient client) {
		long now = System.currentTimeMillis();
		HandledScreen<?> screen = client.currentScreen instanceof HandledScreen<?> handled ? handled : null;
		boolean inAugmentMenu = screen != null
				&& screen.getTitle().getString().toLowerCase(Locale.ROOT).contains("augment");
		if (!inAugmentMenu) {
			wasAugmentMenu = false;
			scanPending = false;
		} else {
			if (!wasAugmentMenu) {
			wasAugmentMenu = true;
				scanPending = true;
				nextScanAt = 0;
			}
			if (scanPending && now >= nextScanAt) {
				nextScanAt = now + GUI_SCAN_INTERVAL_MS;
				scanResearch(screen, now);
			}
		}

		if (!completionAnnounced && researchEndsAt > 0 && now >= researchEndsAt) {
			completionAnnounced = true;
			scanPending = inAugmentMenu;
			announce(client, now);
		}
		if (completionAnnounced && now - lastReminderAt >= REMINDER_INTERVAL_MS) {
			announce(client, now);
		}
	}

	private static void scanResearch(HandledScreen<?> screen, long now) {
		for (Slot slot : screen.getScreenHandler().slots) {
			ItemStack stack = slot.getStack();
			if (stack.isEmpty()) continue;
			String name = SortManager.normalizeText(stack.getName().getString());
			StringBuilder text = new StringBuilder(name);
			LoreComponent lore = stack.get(DataComponentTypes.LORE);
			if (lore != null) {
				for (var line : lore.lines()) text.append('\n').append(SortManager.normalizeText(line.getString()));
			}

			Matcher summary = RESEARCHING.matcher(text);
			if (summary.find()) {
				if (startResearch(summary.group(1), summary.group(2), now)) return;
			}

			if (name.toLowerCase(Locale.ROOT).contains("[researching]")) {
				Matcher remaining = TIME_LEFT.matcher(text);
				if (remaining.find()) {
					String level = name.replaceAll("(?i)\\[.*?\\]", "").trim();
					if (startResearch(level, remaining.group(1), now)) return;
				}
			}
		}
	}

	private static boolean startResearch(String name, String duration, long now) {
		long seconds = parseSeconds(duration);
		if (seconds <= 0) return false;
		String nextName = name.trim();
		if (completionAnnounced && nextName.equalsIgnoreCase(researchName)) return false;
		researchName = nextName;
		researchEndsAt = now + seconds * 1000L;
		completionAnnounced = false;
		lastReminderAt = 0;
		scanPending = false;
		return true;
	}

	private static void announce(MinecraftClient client, long now) {
		if (client.player == null) return;
		String level = researchName.replaceFirst("(?i)^augment\\s+", "");
		client.player.sendMessage(Text.literal("§a[AFKable] §fAugment " + level
				+ " research is complete. Start the next augment in the Augments menu."), false);
		lastReminderAt = now;
	}

	private static long parseSeconds(String value) {
		Matcher matcher = DURATION.matcher(value);
		if (!matcher.find()) return -1;
		try {
			long hours = matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1));
			long minutes = matcher.group(2) == null ? 0 : Long.parseLong(matcher.group(2));
			long seconds = Long.parseLong(matcher.group(3));
			return hours * 3600 + minutes * 60 + seconds;
		} catch (NumberFormatException ignored) {
			return -1;
		}
	}

	public static boolean isActive() {
		return !researchName.isBlank() && remainingSeconds() > 0;
	}

	public static String researchName() {
		return researchName;
	}

	public static long remainingSeconds() {
		return Math.max(0, (researchEndsAt - System.currentTimeMillis() + 999) / 1000);
	}

	public static String remainingText() {
		long seconds = remainingSeconds();
		long hours = seconds / 3600;
		long minutes = (seconds % 3600) / 60;
		long remainder = seconds % 60;
		if (hours > 0) return String.format(Locale.ROOT, "%dh %02dm", hours, minutes);
		return String.format(Locale.ROOT, "%dm %02ds", minutes, remainder);
	}

	public static void clear() {
		researchName = "";
		researchEndsAt = 0;
		wasAugmentMenu = false;
		scanPending = false;
		completionAnnounced = false;
		lastReminderAt = 0;
	}
}
