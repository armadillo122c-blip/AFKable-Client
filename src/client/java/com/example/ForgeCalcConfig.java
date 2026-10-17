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
	public static boolean showClaude = true;
	public static boolean showForge = true;
	public static boolean showHighlights = true;
	public static boolean showWatermark = true;
	public static boolean showIncubator = true;
	public static boolean showSortButton = true;
	public static boolean showPopups = true;
	public static boolean autoSync = true;
	public static String autoSyncCommands = "seeds,stock,prestige,claude";
	public static boolean flyOnJoin = false;
	public static String theme = "Farmer";
	public static String extraButtons = "";
	public static final java.util.Set<String> hiddenButtons = new java.util.LinkedHashSet<>();
	public static String buttonOrder = "trash,stock,orebag,diary,pets,forge,garden,vault,gang,prestige,ec";
	public static boolean clientEnabled = true;
	public static final boolean starSync = true;
	public static final boolean antiNuke = true;
	public static boolean jumpscare = true;
	public static boolean autoJoin = false;
	public static boolean showBuffs = true;
	public static boolean showChatGame = true;
	public static boolean chatGameAutoSend = false;
	public static boolean autoUpgradePickaxe = false;
	public static boolean autoDominion = false;
	public static boolean learnOreValues = true;
	/** Ore values read straight off the in-game lore, so a server rebalance needs no mod update. */
	public static final Map<String, Double> oreValues = new LinkedHashMap<>();
	public static boolean trashProtectTitanic = true;
	public static boolean trashOres = true;
	public static boolean trashMobs = true;
	public static boolean trashFactoryNpcs = false;
	public static boolean trashOther = false;
	public static double trashOreMax = 500.0;
	public static double trashMobMax = 5.0;
	public static double trashNpcMaxDamage = 80000.0;
	public static final java.util.Set<Integer> trashSlots = new java.util.LinkedHashSet<>();

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
		POSITIONS.put("claude", new int[]{5, 23});
		POSITIONS.put("incubator", new int[]{5, 57});
		POSITIONS.put("popups", new int[]{9999, 60});
		POSITIONS.put("forge", new int[]{5, 99});
		POSITIONS.put("buttons", new int[]{300, 60});
		POSITIONS.put("scriptgui", new int[]{160, 5});
		POSITIONS.put("buffs", new int[]{5, 192});
		POSITIONS.put("chatgame", new int[]{200, 40});
		POSITIONS.put("activity", new int[]{5, 120});
	}

	public static int[] pos(String id) {
		return POSITIONS.getOrDefault(id, new int[]{5, 5});
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("forge-ore-calculator.properties");
	}

	public static void load() {
		try {
			Path path = file();
			if (!Files.exists(path)) return;
			Properties props = new Properties();
			try (InputStream in = Files.newInputStream(path)) {
				props.load(in);
			}
			showRestock = bool(props, "showRestock", showRestock);
			showClaude = bool(props, "showClaude", showClaude);
			showForge = bool(props, "showForge", showForge);
			showHighlights = bool(props, "showHighlights", showHighlights);
			showWatermark = bool(props, "showWatermark", showWatermark);
			showIncubator = bool(props, "showIncubator", showIncubator);
			showSortButton = bool(props, "showSortButton", showSortButton);
			showPopups = bool(props, "showPopups", showPopups);
			autoSync = bool(props, "autoSync", autoSync);
			autoSyncCommands = props.getProperty("autoSyncCommands", autoSyncCommands);
			flyOnJoin = bool(props, "flyOnJoin", flyOnJoin);
			clientEnabled = bool(props, "clientEnabled", clientEnabled);
			jumpscare = bool(props, "jumpscare", jumpscare);
			autoJoin = bool(props, "autoJoin", autoJoin);
			showBuffs = bool(props, "showBuffs", showBuffs);
			showChatGame = bool(props, "showChatGame", showChatGame);
			chatGameAutoSend = bool(props, "chatGameAutoSend", chatGameAutoSend);
			autoUpgradePickaxe = bool(props, "autoUpgradePickaxe", autoUpgradePickaxe);
			autoDominion = bool(props, "autoDominion", autoDominion);
			learnOreValues = bool(props, "learnOreValues", learnOreValues);
			oreValues.clear();
			for (String entry : props.getProperty("oreValues", "").split(";")) {
				String[] parts = entry.split("=", 2);
				if (parts.length == 2 && !parts[0].isBlank()) {
					try {
						oreValues.put(parts[0].trim(), Double.parseDouble(parts[1].trim()));
					} catch (NumberFormatException ignored) {
					}
				}
			}
			trashProtectTitanic = bool(props, "trashProtectTitanic", trashProtectTitanic);
			trashOres = bool(props, "trashOres", trashOres);
			trashMobs = bool(props, "trashMobs", trashMobs);
			trashFactoryNpcs = bool(props, "trashFactoryNpcs", trashFactoryNpcs);
			trashOther = bool(props, "trashOther", trashOther);
			try {
				trashOreMax = Double.parseDouble(props.getProperty("trashOreMax", String.valueOf(trashOreMax)));
				trashMobMax = Double.parseDouble(props.getProperty("trashMobMax", String.valueOf(trashMobMax)));
				trashNpcMaxDamage = Double.parseDouble(props.getProperty("trashNpcMaxDamage", String.valueOf(trashNpcMaxDamage)));
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
			theme = props.getProperty("theme", theme);
			extraButtons = props.getProperty("extraButtons", extraButtons);
			hiddenButtons.clear();
			for (String id : props.getProperty("hiddenButtons", "").split(",")) {
				if (!id.isBlank()) hiddenButtons.add(id.trim());
			}
			buttonOrder = props.getProperty("buttonOrder", buttonOrder);
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
		OreData.loadLearned(oreValues);
		StockStore.load();
		StockStore.purgeJunk();
	}

	public static void save() {
		try {
			Properties props = new Properties();
			props.setProperty("showRestock", String.valueOf(showRestock));
			props.setProperty("showClaude", String.valueOf(showClaude));
			props.setProperty("showForge", String.valueOf(showForge));
			props.setProperty("showHighlights", String.valueOf(showHighlights));
			props.setProperty("showWatermark", String.valueOf(showWatermark));
			props.setProperty("showIncubator", String.valueOf(showIncubator));
			props.setProperty("showSortButton", String.valueOf(showSortButton));
			props.setProperty("showPopups", String.valueOf(showPopups));
			props.setProperty("autoSync", String.valueOf(autoSync));
			props.setProperty("autoSyncCommands", autoSyncCommands);
			props.setProperty("flyOnJoin", String.valueOf(flyOnJoin));
			props.setProperty("theme", theme);
			props.setProperty("extraButtons", extraButtons);
			props.setProperty("hiddenButtons", String.join(",", hiddenButtons));
			props.setProperty("buttonOrder", buttonOrder);
			props.setProperty("clientEnabled", String.valueOf(clientEnabled));
			props.setProperty("jumpscare", String.valueOf(jumpscare));
			props.setProperty("autoJoin", String.valueOf(autoJoin));
			props.setProperty("showBuffs", String.valueOf(showBuffs));
			props.setProperty("showChatGame", String.valueOf(showChatGame));
			props.setProperty("chatGameAutoSend", String.valueOf(chatGameAutoSend));
			props.setProperty("autoUpgradePickaxe", String.valueOf(autoUpgradePickaxe));
			props.setProperty("autoDominion", String.valueOf(autoDominion));
			props.setProperty("learnOreValues", String.valueOf(learnOreValues));
			StringBuilder ov = new StringBuilder();
			for (Map.Entry<String, Double> entry : oreValues.entrySet()) {
				if (ov.length() > 0) ov.append(";");
				ov.append(entry.getKey()).append("=").append(entry.getValue());
			}
			props.setProperty("oreValues", ov.toString());
			props.setProperty("trashProtectTitanic", String.valueOf(trashProtectTitanic));
			props.setProperty("trashOres", String.valueOf(trashOres));
			props.setProperty("trashMobs", String.valueOf(trashMobs));
			props.setProperty("trashFactoryNpcs", String.valueOf(trashFactoryNpcs));
			props.setProperty("trashOther", String.valueOf(trashOther));
			props.setProperty("trashOreMax", String.valueOf(trashOreMax));
			props.setProperty("trashMobMax", String.valueOf(trashMobMax));
			props.setProperty("trashNpcMaxDamage", String.valueOf(trashNpcMaxDamage));
			StringBuilder ts = new StringBuilder();
			for (Integer id : trashSlots) {
				if (ts.length() > 0) ts.append(",");
				ts.append(id);
			}
			props.setProperty("trashSlots", ts.toString());
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
}
