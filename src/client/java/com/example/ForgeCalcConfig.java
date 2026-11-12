package com.example;

import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class ForgeCalcConfig {

	public static boolean showRestock = true;
	public static boolean showAugment = true;
	public static boolean showClaude = true;
	public static boolean showWatermark = true;
	public static boolean showSortButton = true;
	public static boolean autoSync = true;
	public static String autoSyncCommands = "stock,prestige";
	public static boolean flyOnJoin = false;
	public static String theme = "Liquid Glass";
	public static String extraButtons = "";
	public static final java.util.Set<String> hiddenButtons = new java.util.LinkedHashSet<>();
	public static String buttonOrder = "trash,stock,diary,pets,forge,garden,vault,gang,prestige,ec";
	public static boolean clientEnabled = true;
	/** Shares your name + UUID on a public ntfy topic so other client users see your star. Opt-in. */
	public static boolean starSync = false;
	public static final boolean antiNuke = true;
	public static boolean autoJoin = false;
	public static boolean showBuffs = false;
	public static boolean showChatGame = true;
	/** Ding + "BITE!" flash when a fish takes your hook. */
	public static boolean biteAlert = true;
	/** HUD line from the server's 5-minute SUMMARY: earnings, per hour, rank. */
	public static boolean showIncome = true;
	public static boolean autoDominion = false;
	public static boolean trashProtectTitanic = true;
	public static boolean trashMobs = true;
	public static boolean trashOther = false;
	public static double trashMobMax = 5.0;
	public static final java.util.Set<Integer> trashSlots = new java.util.LinkedHashSet<>();

	/** Auto upgrader. Mastery stays off by default: it resets the level it masters. */
	public static boolean upgradeMastery = false;
	public static boolean upgradeCloseWhenDone = true;
	public static double upgradeReserve = 0;
	public static int upgradeInterval = 0;
	public static int upgradeRounds = 3;
	public static String upgradeCommand = "upgrades";
	public static final Set<String> upgradeTiers = new LinkedHashSet<>(
			java.util.List.of("common", "uncommon", "rare", "legendary", "mythic"));
	public static final Set<String> upgradePicks = new LinkedHashSet<>();
	public static final Set<String> upgradeKnown = new LinkedHashSet<>();

	public static boolean slotTrashable(int index) {
		return trashSlots.isEmpty() || trashSlots.contains(index);
	}
	public static final Set<String> seenMobs = new LinkedHashSet<>();
	public static final Map<String, String[]> mobData = new LinkedHashMap<>();
	public static final Set<String> watchedMobs = new LinkedHashSet<>();
	public static final Map<String, String> badgeMap = new LinkedHashMap<>();
	public static final Map<Integer, String> keybinds = new LinkedHashMap<>();

	public static final Map<String, int[]> POSITIONS = new LinkedHashMap<>();
	public static final Map<String, Float> SCALES = new LinkedHashMap<>();
	public static final Map<String, Float> ALPHAS = new LinkedHashMap<>();
	public static String apiUrl = "";

	public static float scale(String id) {
		return SCALES.getOrDefault(id, 1.0f);
	}

	public static float alpha(String id) {
		return ALPHAS.getOrDefault(id, 1.0f);
	}

	static {
		resetPositions();
	}

	public static void resetPositions() {
		POSITIONS.put("restock", new int[]{5, 5});
		POSITIONS.put("augment", new int[]{5, 57});
		POSITIONS.put("claude", new int[]{5, 23});
		POSITIONS.put("popups", new int[]{9999, 60});
		POSITIONS.put("buttons", new int[]{300, 60});
		POSITIONS.put("scriptgui", new int[]{160, 5});
		POSITIONS.put("buffs", new int[]{5, 192});
		POSITIONS.put("chatgame", new int[]{200, 40});
		POSITIONS.put("activity", new int[]{5, 120});
		POSITIONS.put("income", new int[]{5, 75});
	}

	public static int[] pos(String id) {
		return POSITIONS.getOrDefault(id, new int[]{5, 5});
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("afkable-client.properties");
	}

	public static void load() {
		try {
			Path path = file();
			if (!Files.exists(path)) {
				Path legacy = FabricLoader.getInstance().getConfigDir().resolve("forge-ore-calculator.properties");
				if (!Files.exists(legacy)) return;
				path = legacy;
			}
			Properties props = new Properties();
			try (InputStream in = Files.newInputStream(path)) {
				props.load(in);
			}
			showRestock = bool(props, "showRestock", showRestock);
			showAugment = bool(props, "showAugment", showAugment);
			showClaude = bool(props, "showClaude", showClaude);
			showWatermark = bool(props, "showWatermark", showWatermark);
			showSortButton = bool(props, "showSortButton", showSortButton);
			autoSync = bool(props, "autoSync", autoSync);
			autoSyncCommands = cleanAutoSyncCommands(props.getProperty("autoSyncCommands", autoSyncCommands));
			flyOnJoin = bool(props, "flyOnJoin", flyOnJoin);
			clientEnabled = bool(props, "clientEnabled", clientEnabled);
			autoJoin = bool(props, "autoJoin", autoJoin);
			starSync = bool(props, "starSync", starSync);
			showBuffs = bool(props, "showBuffs", showBuffs);
			showChatGame = bool(props, "showChatGame", showChatGame);
			biteAlert = bool(props, "biteAlert", biteAlert);
			showIncome = bool(props, "showIncome", showIncome);
			autoDominion = bool(props, "autoDominion", autoDominion);
			trashProtectTitanic = bool(props, "trashProtectTitanic", trashProtectTitanic);
			trashMobs = bool(props, "trashMobs", trashMobs);
			trashOther = bool(props, "trashOther", trashOther);
			try {
				trashMobMax = Double.parseDouble(props.getProperty("trashMobMax", String.valueOf(trashMobMax)));
			} catch (NumberFormatException ignored) {
			}
			trashSlots.clear();
			for (String id : props.getProperty("trashSlots", "").split(",")) {
				if (!id.isBlank()) {
					try {
						trashSlots.add(Integer.parseInt(id.trim()));
					} catch (NumberFormatException ignored) {
					}
				}
			}
			upgradeMastery = bool(props, "upgradeMastery", upgradeMastery);
			upgradeCloseWhenDone = bool(props, "upgradeCloseWhenDone", upgradeCloseWhenDone);
			upgradeCommand = props.getProperty("upgradeCommand", upgradeCommand);
			try {
				upgradeReserve = Math.max(0, Double.parseDouble(
						props.getProperty("upgradeReserve", String.valueOf(upgradeReserve))));
				upgradeInterval = Math.max(0, Math.min(180, Integer.parseInt(
						props.getProperty("upgradeInterval", String.valueOf(upgradeInterval)))));
				upgradeRounds = Math.max(1, Math.min(20, Integer.parseInt(
						props.getProperty("upgradeRounds", String.valueOf(upgradeRounds)))));
			} catch (NumberFormatException ignored) {
			}
			String tiers = props.getProperty("upgradeTiers");
			if (tiers != null) {
				upgradeTiers.clear();
				for (String tier : tiers.split(",")) {
					if (!tier.isBlank()) upgradeTiers.add(tier.trim().toLowerCase());
				}
			}
			loadSet(props, "upgradePicks", upgradePicks);
			loadSet(props, "upgradeKnown", upgradeKnown);
			theme = props.getProperty("theme", theme);
			theme = "Liquid Glass";
			extraButtons = props.getProperty("extraButtons", extraButtons);
			hiddenButtons.clear();
			for (String id : props.getProperty("hiddenButtons", "").split(",")) {
				if (!id.isBlank() && !Set.of("orebag", "claude", "seeds").contains(id.trim().toLowerCase())) {
					hiddenButtons.add(id.trim());
				}
			}
			buttonOrder = cleanButtonOrder(props.getProperty("buttonOrder", buttonOrder));
			ForgeCalcTheme.set(theme);
			loadSet(props, "seenMobs", seenMobs);
			loadSet(props, "watchedMobs", watchedMobs);
			keybinds.clear();
			for (String entry : props.getProperty("keybinds", "").split(";")) {
				String[] parts = entry.split(":", 2);
				if (parts.length == 2 && !parts[0].isBlank()) {
					try {
						keybinds.put(Integer.parseInt(parts[0].trim()), parts[1]);
					} catch (NumberFormatException ignored) {
					}
				}
			}
			badgeMap.clear();
			for (String entry : props.getProperty("badgeMap", "").split(";")) {
				String[] parts = entry.split("=");
				if (parts.length == 2 && !parts[0].isBlank()) {
					badgeMap.put(parts[0].trim().toLowerCase(), parts[1].trim().toLowerCase());
				}
			}
			mobData.clear();
			for (String entry : props.getProperty("mobData", "").split(";")) {
				String[] parts = entry.split("\\|", -1);
				if (parts.length >= 3 && !parts[0].isBlank()) {
					mobData.put(parts[0], new String[]{parts[1], parts[2], parts.length > 3 ? parts[3] : ""});
				}
			}

			apiUrl = props.getProperty("apiUrl", apiUrl);
			for (String id : POSITIONS.keySet()) {
				try {
					String sc = props.getProperty("scale." + id);
					if (sc != null) SCALES.put(id, Math.max(0.5f, Math.min(2.0f, Float.parseFloat(sc))));
					String al = props.getProperty("alpha." + id);
					if (al != null) ALPHAS.put(id, Math.max(0.2f, Math.min(1.0f, Float.parseFloat(al))));
				} catch (NumberFormatException ignored) {
				}
				String raw = props.getProperty("pos." + id);
				if (raw != null) {
					String[] parts = raw.split(",");
					if (parts.length == 2) {
						try {
							POSITIONS.put(id, new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())});
						} catch (NumberFormatException ignored) {
						}
					}
				}
			}
		} catch (Exception e) {
			System.out.println("[ForgeCalc] Failed to load config: " + e.getMessage());
		}
		StockStore.load();
		StockStore.purgeJunk();
	}

	public static void save() {
		try {
			Properties props = new Properties();
			props.setProperty("showRestock", String.valueOf(showRestock));
			props.setProperty("showAugment", String.valueOf(showAugment));
			props.setProperty("showClaude", String.valueOf(showClaude));
			props.setProperty("showWatermark", String.valueOf(showWatermark));
			props.setProperty("showSortButton", String.valueOf(showSortButton));
			props.setProperty("autoSync", String.valueOf(autoSync));
			props.setProperty("autoSyncCommands", autoSyncCommands);
			props.setProperty("flyOnJoin", String.valueOf(flyOnJoin));
			props.setProperty("theme", theme);
			props.setProperty("extraButtons", extraButtons);
			props.setProperty("hiddenButtons", String.join(",", hiddenButtons));
			props.setProperty("buttonOrder", buttonOrder);
			props.setProperty("clientEnabled", String.valueOf(clientEnabled));
			props.setProperty("autoJoin", String.valueOf(autoJoin));
			props.setProperty("starSync", String.valueOf(starSync));
			props.setProperty("showBuffs", String.valueOf(showBuffs));
			props.setProperty("showChatGame", String.valueOf(showChatGame));
			props.setProperty("biteAlert", String.valueOf(biteAlert));
			props.setProperty("showIncome", String.valueOf(showIncome));
			props.setProperty("autoDominion", String.valueOf(autoDominion));
			props.setProperty("trashProtectTitanic", String.valueOf(trashProtectTitanic));
			props.setProperty("trashMobs", String.valueOf(trashMobs));
			props.setProperty("trashOther", String.valueOf(trashOther));
			props.setProperty("trashMobMax", String.valueOf(trashMobMax));
			StringBuilder ts = new StringBuilder();
			for (Integer id : trashSlots) {
				if (ts.length() > 0) ts.append(",");
				ts.append(id);
			}
			props.setProperty("trashSlots", ts.toString());
			props.setProperty("upgradeMastery", String.valueOf(upgradeMastery));
			props.setProperty("upgradeCloseWhenDone", String.valueOf(upgradeCloseWhenDone));
			props.setProperty("upgradeCommand", upgradeCommand);
			props.setProperty("upgradeReserve", String.valueOf(upgradeReserve));
			props.setProperty("upgradeInterval", String.valueOf(upgradeInterval));
			props.setProperty("upgradeRounds", String.valueOf(upgradeRounds));
			props.setProperty("upgradeTiers", String.join(",", upgradeTiers));
			props.setProperty("upgradePicks", String.join(";", upgradePicks));
			props.setProperty("upgradeKnown", String.join(";", upgradeKnown));
			props.setProperty("seenMobs", String.join(";", seenMobs));
			props.setProperty("watchedMobs", String.join(";", watchedMobs));
			StringBuilder kb = new StringBuilder();
			for (Map.Entry<Integer, String> entry : keybinds.entrySet()) {
				if (kb.length() > 0) kb.append(";");
				kb.append(entry.getKey()).append(":").append(entry.getValue());
			}
			props.setProperty("keybinds", kb.toString());
			StringBuilder bm = new StringBuilder();
			for (Map.Entry<String, String> entry : badgeMap.entrySet()) {
				if (bm.length() > 0) bm.append(";");
				bm.append(entry.getKey()).append("=").append(entry.getValue());
			}
			props.setProperty("badgeMap", bm.toString());
			StringBuilder md = new StringBuilder();
			for (Map.Entry<String, String[]> entry : mobData.entrySet()) {
				if (md.length() > 0) md.append(";");
				String[] data = entry.getValue();
				md.append(entry.getKey()).append("|").append(data[0]).append("|").append(data[1])
						.append("|").append(data.length > 2 ? data[2] : "");
			}
			props.setProperty("mobData", md.toString());
			StockStore.save();
			props.setProperty("apiUrl", apiUrl);
			for (Map.Entry<String, int[]> entry : POSITIONS.entrySet()) {
				props.setProperty("pos." + entry.getKey(), entry.getValue()[0] + "," + entry.getValue()[1]);
				props.setProperty("scale." + entry.getKey(), String.valueOf(scale(entry.getKey())));
				props.setProperty("alpha." + entry.getKey(), String.valueOf(alpha(entry.getKey())));
			}
			try (OutputStream out = Files.newOutputStream(file())) {
				props.store(out, "Forge Ore Calculator");
			}
		} catch (Exception e) {
			System.out.println("[ForgeCalc] Failed to save config: " + e.getMessage());
		}
	}

	private static void loadSet(Properties props, String key, Set<String> target) {
		String raw = props.getProperty(key, "");
		target.clear();
		for (String entry : raw.split(";")) {
			if (!entry.isBlank()) target.add(entry.trim());
		}
	}

	private static boolean bool(Properties props, String key, boolean def) {
		return Boolean.parseBoolean(props.getProperty(key, String.valueOf(def)));
	}

	private static String cleanAutoSyncCommands(String input) {
		return java.util.Arrays.stream(input.split(","))
				.map(String::trim)
				.filter(command -> !command.isBlank())
				.filter(command -> !command.equalsIgnoreCase("seeds") && !command.equalsIgnoreCase("claude"))
				.distinct()
				.collect(java.util.stream.Collectors.joining(","));
	}

	private static String cleanButtonOrder(String input) {
		return java.util.Arrays.stream(input.split(","))
				.map(String::trim)
				.filter(id -> !id.isBlank() && !Set.of("orebag", "claude", "seeds").contains(id.toLowerCase()))
				.distinct()
				.collect(java.util.stream.Collectors.joining(","));
	}
}
