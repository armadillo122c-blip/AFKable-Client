package com.example;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stock notifier data lives in its own JSON file instead of the properties
 * config - mob names contain colours, spaces and separators that kept
 * corrupting the flat key=value format.
 */
public class StockStore {

	private static final Pattern ENTRY = Pattern.compile(
			"\\{\\s*\"name\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"price\"\\s*:\\s*\"(.*?)\"\\s*,"
					+ "\\s*\"gain\"\\s*:\\s*\"(.*?)\"(?:\\s*,\\s*\"damage\"\\s*:\\s*\"(.*?)\")?"
					+ "\\s*,\\s*\"watched\"\\s*:\\s*(true|false)\\s*}");

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("afkable-stock.json");
	}

	private static String escape(String raw) {
		return raw.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static String unescape(String raw) {
		return raw.replace("\\\"", "\"").replace("\\\\", "\\");
	}

	public static void save() {
		try {
			StringBuilder json = new StringBuilder("{\n  \"mobs\": [\n");
			boolean first = true;
			for (String mob : ForgeCalcConfig.seenMobs) {
				String[] data = ForgeCalcConfig.mobData.get(mob);
				String price = data != null ? data[0] : "";
				String gain = data != null ? data[1] : "";
				String damage = data != null && data.length > 2 ? data[2] : "";
				if (!first) json.append(",\n");
				first = false;
				json.append("    {\"name\": \"").append(escape(mob))
						.append("\", \"price\": \"").append(escape(price))
						.append("\", \"gain\": \"").append(escape(gain))
						.append("\", \"damage\": \"").append(escape(damage))
						.append("\", \"watched\": ").append(ForgeCalcConfig.watchedMobs.contains(mob))
						.append("}");
			}
			json.append("\n  ]\n}\n");
			Files.writeString(file(), json.toString());
		} catch (Exception ignored) {
		}
	}

	public static void load() {
		try {
			if (!Files.exists(file())) return;
			String json = Files.readString(file());
			Set<String> seen = new LinkedHashSet<>();
			Set<String> watched = new LinkedHashSet<>();
			Map<String, String[]> data = new java.util.LinkedHashMap<>();

			Matcher m = ENTRY.matcher(json);
			while (m.find()) {
				String name = unescape(m.group(1));
				if (isJunk(name)) continue;
				seen.add(name);
				data.put(name, new String[]{unescape(m.group(2)), unescape(m.group(3)), m.group(4) == null ? "" : unescape(m.group(4))});
				if (Boolean.parseBoolean(m.group(5))) watched.add(name);
			}
			if (!seen.isEmpty()) {
				ForgeCalcConfig.seenMobs.clear();
				ForgeCalcConfig.seenMobs.addAll(seen);
				ForgeCalcConfig.watchedMobs.clear();
				ForgeCalcConfig.watchedMobs.addAll(watched);
				ForgeCalcConfig.mobData.clear();
				ForgeCalcConfig.mobData.putAll(data);
			}
		} catch (Exception ignored) {
		}
	}

	/** Shop control items (barrier "sold out", BUY ALL, page arrows) are never real mobs. */
	public static boolean isJunk(String name) {
		String lower = name.toLowerCase().trim();
		if (lower.isBlank() || lower.length() < 2) return true;
		// a "name" that is only digits or punctuation is GUI furniture
		if (lower.matches("[^a-z]+")) return true;
		String[] junk = {
				"buy all", "buyall", "instant restock", "restock", "barrier", "sold out",
				"locked", "coming soon", "next page", "previous page", "prev page",
				"page ", "go back", "back to", "close", "information", "info", "guide",
				"how to", "click here", "your farm", "filler", "placeholder", "timer",
				"balance", "wallet", "scroll", "sort by", "search",
		};
		for (String token : junk) {
			if (lower.contains(token)) return true;
		}
		return false;
	}

	/**
	 * Collapses the display variations the server uses for the same mob so it cannot end
	 * up stored twice - once with an icon and once without. Strips a trailing rarity or
	 * status tag, squashes whitespace and compares case-insensitively.
	 */
	public static String canonical(String name) {
		if (name == null) return "";
		String out = SortManager.normalizeText(name);
		out = out.replaceAll("(?i)\\[(.*?)\\]", " ");          // [UNIQUE], [SOLD OUT]
		out = out.replaceAll("(?i)\\((.*?)\\)", " ");          // (sold out)
		out = out.replaceAll("[^A-Za-z0-9 ]", " ");
		out = out.replaceAll("\\s+", " ").trim();
		return out;
	}

	private static String canonKey(String name) {
		return canonical(name).toLowerCase();
	}

	/**
	 * Drops junk and merges entries whose canonical names match, keeping whichever copy
	 * actually carries price/gain data. Safe to call repeatedly.
	 */
	public static void purgeJunk() {
		ForgeCalcConfig.seenMobs.removeIf(StockStore::isJunk);
		ForgeCalcConfig.watchedMobs.removeIf(StockStore::isJunk);
		ForgeCalcConfig.mobData.keySet().removeIf(StockStore::isJunk);
		dedupe();
	}

	/** Merges duplicate spellings of the same mob down to one entry. */
	public static void dedupe() {
		Map<String, String> chosen = new LinkedHashMap<>();
		for (String name : new LinkedHashSet<>(ForgeCalcConfig.seenMobs)) {
			String key = canonKey(name);
			if (key.isEmpty()) continue;
			String existing = chosen.get(key);
			if (existing == null) {
				chosen.put(key, name);
				continue;
			}
			// prefer the spelling that has real data behind it, else the tidier one
			chosen.put(key, betterOf(existing, name));
		}
		if (chosen.size() == ForgeCalcConfig.seenMobs.size()) return;

		Set<String> keep = new LinkedHashSet<>(chosen.values());
		Set<String> watched = new LinkedHashSet<>();
		for (String name : ForgeCalcConfig.watchedMobs) {
			String winner = chosen.get(canonKey(name));
			if (winner != null) watched.add(winner);
		}
		Map<String, String[]> data = new LinkedHashMap<>();
		for (String name : keep) {
			String[] best = ForgeCalcConfig.mobData.get(name);
			// pull data across from a duplicate that had it when the winner does not
			if (!hasData(best)) {
				for (Map.Entry<String, String[]> e : ForgeCalcConfig.mobData.entrySet()) {
					if (!canonKey(e.getKey()).equals(canonKey(name))) continue;
					String[] candidate = e.getValue();
					if (hasData(candidate)) {
						best = candidate;
						break;
					}
				}
			}
			if (best != null) data.put(name, best);
		}

		ForgeCalcConfig.seenMobs.clear();
		ForgeCalcConfig.seenMobs.addAll(keep);
		ForgeCalcConfig.watchedMobs.clear();
		ForgeCalcConfig.watchedMobs.addAll(watched);
		ForgeCalcConfig.mobData.clear();
		ForgeCalcConfig.mobData.putAll(data);
	}

	private static String betterOf(String a, String b) {
		String[] da = ForgeCalcConfig.mobData.get(a);
		String[] db = ForgeCalcConfig.mobData.get(b);
		boolean aHas = hasData(da);
		boolean bHas = hasData(db);
		if (aHas != bHas) return aHas ? a : b;
		// otherwise the shorter, cleaner spelling wins
		return a.length() <= b.length() ? a : b;
	}

	private static boolean hasData(String[] data) {
		if (data == null) return false;
		for (String value : data) if (value != null && !value.isBlank()) return true;
		return false;
	}
}
