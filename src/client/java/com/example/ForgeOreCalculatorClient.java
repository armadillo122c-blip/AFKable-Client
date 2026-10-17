package com.example;

import com.example.mixin.client.HandledScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ForgeOreCalculatorClient implements ClientModInitializer {

	private static int PANEL_BG = 0xD8241A10;
	private static int PANEL_BORDER = 0xFF4A3319;
	private static int PANEL_ACCENT = 0xFFFFC941;

	public static void applyPanelTheme() {
		ForgeCalcTheme t = ForgeCalcTheme.current();
		PANEL_BG = t.panelBg;
		PANEL_BORDER = t.panelBorder;
		PANEL_ACCENT = 0xFF000000 | t.gradFrom;
	}
	private static final int HIGHLIGHT_BEST = 0x6055FF55;
	private static final int HIGHLIGHT_OTHER = 0x4000AAFF;
	private static final int BAR_BG = 0xFF3A2A18;
	private static final int BAR_FILL = 0xFF55FF55;
	private static final int BAR_FILL_WARN = 0xFFFFAA00;
	private static final int BAR_FILL_FULL = 0xFFFF5555;

	private static final Pattern TIMER_PATTERN = Pattern.compile("Timer:\\s*(?:(\\d+)m)?\\s*(\\d+)s");
	private static final Pattern PERIOD_PATTERN = Pattern.compile("Restocks Every\\s*(\\d+)m");
	private static final Pattern STORAGE_PATTERN = Pattern.compile("Storage:\\s*([\\d,]+)\\s*/\\s*([\\d,]+)");
	private static final Pattern RATE_PATTERN = Pattern.compile("Mine Rate:\\s*(\\d+)s");
	private static final Pattern COST_PATTERN = Pattern.compile("Cost:\\s*\\$([\\d.,]+[a-zA-Z]*)");
	private static final Pattern MONEY_PATTERN = Pattern.compile("\\$([\\d.,]+[a-zA-Z]*)");
	private static final Pattern HATCH_PATTERN = Pattern.compile("Hatches in:\\s*(?:(\\d+)h)?\\s*(?:(\\d+)m)?\\s*(\\d+)s");
	/**
	 * The server's full number ladder. The old map stopped at Q, so anything late-game
	 * ("$11.59T/sec" was fine, but "$14.020i/sec" or "1.04Sp" was not) failed to parse and
	 * silently dropped the item from every calculation. Ordered longest-suffix-first at
	 * lookup time so "Qi" is never mistaken for "Q".
	 */
	private static final Map<String, Double> SUFFIXES = buildSuffixes();

	private static Map<String, Double> buildSuffixes() {
		Map<String, Double> map = new HashMap<>();
		map.put("", 1.0);
		// short ladder, both cases, as the server mixes them
		String[] shortNames = {"k", "m", "b", "t"};
		for (int i = 0; i < shortNames.length; i++) {
			double value = Math.pow(1000, i + 1);
			map.put(shortNames[i], value);
			map.put(shortNames[i].toUpperCase(java.util.Locale.ROOT), value);
		}
		// long ladder: quadrillion upward, 1000x per step
		String[] longNames = {"Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc",
				"UDc", "DDc", "TDc", "QaDc", "QiDc", "SxDc", "SpDc", "OcDc", "NoDc", "Vg"};
		for (int i = 0; i < longNames.length; i++) {
			double value = Math.pow(1000, i + 5);
			map.put(longNames[i], value);
			map.put(longNames[i].toLowerCase(java.util.Locale.ROOT), value);
		}
		// legacy aliases the mod used before the ladder was extended
		map.put("q", 1e15);
		map.put("Q", 1e15);
		return map;
	}

	private boolean isCalcOpen = false;
	private boolean hudVisible = true;
	private List<StackEntry> topStacks = new ArrayList<>();
	private Set<OreKey> topKeys = new HashSet<>();
	private OreKey bestKey = null;
	private double totalMultiplier = 0.0;
	private double fullValue = 0.0;
	private int totalOreCount = 0;
	private int oreTypeCount = 0;
	private int emptyTicks = 0;
	private final Set<Integer> heldKeys = new HashSet<>();
	private final Set<Integer> heldMouseButtons = new HashSet<>();

	private long restockEnd = 0;
	private long restockPeriodMs = 5 * 60 * 1000;
	private boolean restockSynced = false;

	private boolean claudeSynced = false;
	private int claudeStorage = 0;
	private int claudeMax = 250;
	private int claudeRateSec = 5;
	private long claudeSyncTime = 0;

	private boolean wasInWorld = false;
	private int tickCount = 0;

	private final List<long[]> eggHatchEnds = new ArrayList<>();
	private boolean incubatorSynced = false;
	private boolean eggsNotified = false;

	private final SortManager sortManager = new SortManager();
	public final NtfyNet ntfyNet = new NtfyNet();
	public final ControlNet controlNet = new ControlNet();
	public final ScriptEngine scriptEngine = new ScriptEngine();
	private int badgeRepublishTicks = 0;

	private static final List<String> DEV_ACCOUNTS = List.of("armadillo122", "opgeruimd");

	public static boolean isDev(MinecraftClient client) {
		return client.player != null
				&& DEV_ACCOUNTS.contains(client.player.getName().getString().toLowerCase());
	}

	public static boolean isDevName(String name) {
		return name != null && DEV_ACCOUNTS.contains(name.toLowerCase());
	}
	private static int blocksBrokenThisTick = 0;
	private static final java.util.Random SCARE_RANDOM = new java.util.Random();
	private static int jumpscareTicks = 0;

	public static void onBlockBreak() {
		blocksBrokenThisTick++;
		if (ForgeCalcConfig.clientEnabled && ForgeCalcConfig.jumpscare && jumpscareTicks <= 0
				&& SCARE_RANDOM.nextInt(10000) == 0) {
			triggerJumpscare(MinecraftClient.getInstance());
		}
	}

	public static void triggerJumpscare(MinecraftClient client) {
		jumpscareTicks = 35;
		try {
			client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
					net.minecraft.sound.SoundEvents.ENTITY_ENDERMAN_SCREAM, 1.0F));
		} catch (Throwable ignored) {
		}
	}
	private final Map<OreKey, Integer> oreBaseline = new HashMap<>();
	private boolean baselineInit = false;
	private final List<RarePopup> popups = new ArrayList<>();
	private static final double RARE_POPUP_MIN = 50.0;
	private static final long POPUP_DURATION_MS = 3500;
	private static final long POPUP_SLIDE_MS = 250;

	private boolean pendingTrash = false;
	private int pendingTrashTimer = 0;
	private int pendingTrashSyncId = -1;

	private int flyTimer = -1;
	private int autoJoinTimer = -1;
	private int autoJoinStage = 0;
	private String lastAddress = "";
	private int reconnectDelay = -1;
	private int pendingPvSend = -1;
	private int pendingPvTimer = 0;
	private final Set<String> lastStock = new HashSet<>();
	private static final Map<String, ItemStack> MOB_ICONS = new HashMap<>();
	private static final Map<String, ItemStack> ORE_ICONS = new HashMap<>();

	/** Matches the "Ore Value: 50.00" / "Ore Value: 5k" line in the ores GUI lore. */
	private static final Pattern ORE_VALUE_PATTERN =
			Pattern.compile("(?i)ore\\s*value\\s*[:=]?\\s*\\$?([\\d.,]+\\s*[a-zA-Z]?)");

	/**
	 * Matches an item name to its ore, ignoring the rarity tag the server appends.
	 * In the Ores GUI an item is literally named "Uranium Ore [SPECIAL]", so an exact
	 * equals() never matched and no value was ever learned. Variants are rejected because
	 * they carry the base value with a separate multiplier applied on top.
	 */
	private static OreData baseOreFor(String rawName) {
		String name = OreData.clean(rawName).toLowerCase(java.util.Locale.ROOT);
		if (OreData.findVariant(rawName) != OreData.Variant.NORMAL) return null;

		OreData best = null;
		int bestLength = -1;
		for (OreData ore : OreData.values()) {
			String cleanName = ore.displayName.replaceAll("[^a-zA-Z0-9 ]", "")
					.toLowerCase(java.util.Locale.ROOT);
			if (!name.startsWith(cleanName)) continue;
			// whatever follows the ore name must be the rarity tag, not another word
			String rest = name.substring(cleanName.length()).trim();
			if (!rest.isEmpty() && !rest.matches("[a-z ]{0,20}")) continue;
			// longest match wins so "Diamond Cluster" beats "Diamond Ore"
			if (cleanName.length() > bestLength) {
				best = ore;
				bestLength = cleanName.length();
			}
		}
		return best;
	}

	private void learnOreIcons(HandledScreen<?> screen) {
		int nerfed = 0;
		int buffed = 0;
		String example = null;
		for (Slot slot : screen.getScreenHandler().slots) {
			if (!slot.hasStack()) continue;
			ItemStack stack = slot.getStack();
			OreData ore = baseOreFor(stack.getName().getString());
			if (ore == null) continue;

			String cleanName = ore.displayName.replaceAll("[^a-zA-Z0-9 ]", "");
			if (!ORE_ICONS.containsKey(cleanName)) {
				ORE_ICONS.put(cleanName, stack.copy());
			}
			if (ForgeCalcConfig.learnOreValues) {
				double before = ore.value();
				if (learnOreValue(ore, stack)) {
					if (ore.value() < before) nerfed++;
					else buffed++;
					if (example == null) {
						example = ore.displayName + " " + formatMoney(before) + " → " + formatMoney(ore.value());
					}
				}
			}
		}
		if (nerfed + buffed == 0) return;

		ForgeCalcConfig.oreValues.clear();
		ForgeCalcConfig.oreValues.putAll(OreData.learned());
		ForgeCalcConfig.save();

		// one summary line, never one message per ore
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) {
			StringBuilder msg = new StringBuilder("§7Ore values updated: ");
			if (nerfed > 0) msg.append("§c▼").append(nerfed).append(" ");
			if (buffed > 0) msg.append("§a▲").append(buffed).append(" ");
			msg.append("§8(").append(example).append(")");
			notifyChat(mc, msg.toString());
			LiveActivity.push(LiveActivity.Kind.FORGE, "Ore values updated ("
					+ (nerfed + buffed) + " changed)");
		}
	}

	/** Reads "Ore Value" off an ore's lore. Returns true when the stored value changed. */
	private boolean learnOreValue(OreData ore, ItemStack stack) {
		LoreComponent lore = stack.get(DataComponentTypes.LORE);
		if (lore == null) return false;
		for (Text line : lore.lines()) {
			Matcher m = ORE_VALUE_PATTERN.matcher(SortManager.normalizeText(line.getString()));
			if (!m.find()) continue;
			double parsed = parseMoney(m.group(1).replace(" ", ""));
			if (parsed <= 0) continue;
			if (Math.abs(ore.value() - parsed) < 1e-9) return false;
			OreData.putLearned(ore.name(), parsed);
			return true;
		}
		return false;
	}

	public static ItemStack mobIcon(String name) {
		return MOB_ICONS.get(name);
	}
	private int lazyVault = 0;
	private int lazyState = 0;
	private int lazyTimer = 0;
	private int lazyMoved = 0;

	private final Deque<String> autoSyncQueue = new ArrayDeque<>();
	private int autoSyncTimer = 0;
	private int autoSyncState = 0;
	private boolean autoSyncActive = false;

	public boolean isBackgroundSyncing() {
		return autoSyncActive;
	}

	private static final Set<String> CLIENT_USERS = Collections.synchronizedSet(new HashSet<>());

	public static boolean isClientUser(String name) {
		return CLIENT_USERS.contains(name.toLowerCase());
	}

	private static final Map<String, Long> USER_LAST_SEEN = Collections.synchronizedMap(new HashMap<>());

	public static void replaceClientUsers(java.util.Set<String> roster) {
		long now = System.currentTimeMillis();
		for (String token : roster) {
			USER_LAST_SEEN.put(token, now);
		}
		CLIENT_USERS.clear();
		synchronized (USER_LAST_SEEN) {
			USER_LAST_SEEN.entrySet().removeIf(entry -> now - entry.getValue() > 120_000);
			CLIENT_USERS.addAll(USER_LAST_SEEN.keySet());
		}
	}

	private static final Map<String, String> KNOWN_USERS = Collections.synchronizedMap(new LinkedHashMap<>());
	public static final Map<String, String> BADGES = Collections.synchronizedMap(new LinkedHashMap<>());

	public static void recordKnownUser(String uuid, String name) {
		KNOWN_USERS.put(uuid, name);
		if (KNOWN_USERS.size() > 200) {
			KNOWN_USERS.remove(KNOWN_USERS.keySet().iterator().next());
		}
	}

	public static Map<String, String> knownUsers() {
		return new LinkedHashMap<>(KNOWN_USERS);
	}

	public static String badgeKeyFor(String name, String uuid) {
		if (uuid != null && BADGES.containsKey(uuid)) return BADGES.get(uuid);
		if (name != null && BADGES.containsKey(name.toLowerCase())) return BADGES.get(name.toLowerCase());
		if (isDevName(name)) return "hammer";
		return "star";
	}

	public static String badgePrefix(String name, String uuid) {
		boolean isUser = (name != null && isClientUser(name)) || (uuid != null && isClientUser(uuid));
		if (!isUser && !isDevName(name)) return null;
		return TabBadge.byKey(badgeKeyFor(name, uuid)).listPrefix();
	}

	public static String badgeLabel(String key) {
		return TabBadge.byKey(key).label();
	}

	private void startBackendSync() {
		Thread thread = new Thread(() -> {
			java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
			while (true) {
				try {
					Thread.sleep(30_000);
					String url = ForgeCalcConfig.apiUrl;
					if (url == null || url.isBlank()) continue;
					MinecraftClient mc = MinecraftClient.getInstance();
					if (mc.player == null) continue;

					String uuid = mc.player.getUuidAsString().toLowerCase();
					String name = mc.player.getName().getString();

					java.net.http.HttpRequest post = java.net.http.HttpRequest.newBuilder()
							.uri(java.net.URI.create(url + "/join"))
							.header("Content-Type", "text/plain")
							.POST(java.net.http.HttpRequest.BodyPublishers.ofString(uuid + "," + name))
							.timeout(java.time.Duration.ofSeconds(5))
							.build();
					http.send(post, java.net.http.HttpResponse.BodyHandlers.discarding());

					java.net.http.HttpRequest get = java.net.http.HttpRequest.newBuilder()
							.uri(java.net.URI.create(url + "/users"))
							.timeout(java.time.Duration.ofSeconds(5))
							.GET().build();
					String body = http.send(get, java.net.http.HttpResponse.BodyHandlers.ofString()).body();

					java.util.Set<String> parsed = new HashSet<>();
					for (String entry : body.split("[,;\\n]")) {
						if (!entry.isBlank()) parsed.add(entry.trim().toLowerCase());
					}
					if (!parsed.isEmpty()) {
						mc.execute(() -> {
							CLIENT_USERS.clear();
							CLIENT_USERS.addAll(parsed);
							if (mc.player != null) {
								CLIENT_USERS.add(mc.player.getName().getString().toLowerCase());
								CLIENT_USERS.add(mc.player.getUuidAsString().toLowerCase());
							}
						});
					}
				} catch (InterruptedException e) {
					return;
				} catch (Exception ignored) {
				}
			}
		}, "afkable-backend-sync");
		thread.setDaemon(true);
		thread.start();
	}

	public static String profileUuid(Object profile) {
		if (profile == null) return null;
		try {
			Object id = profile.getClass().getMethod("getId").invoke(profile);
			return id != null ? id.toString().toLowerCase() : null;
		} catch (Exception ignored) {
		}
		try {
			Object id = profile.getClass().getMethod("id").invoke(profile);
			return id != null ? id.toString().toLowerCase() : null;
		} catch (Exception ignored) {
		}
		return null;
	}

	public static String profileName(Object profile) {
		if (profile == null) return null;
		try {
			return (String) profile.getClass().getMethod("getName").invoke(profile);
		} catch (Exception ignored) {
		}
		try {
			return (String) profile.getClass().getMethod("name").invoke(profile);
		} catch (Exception ignored) {
		}
		return null;
	}

	public static ForgeOreCalculatorClient INSTANCE;

	private static int[] buttonsOffset() {
		int[] p = ForgeCalcConfig.pos("buttons");
		return new int[]{p[0] - 300, p[1] - 60};
	}

	@Override
	public void onInitializeClient() {
		INSTANCE = this;
		System.out.println("FORGE CALCULATOR (1.21.8 COMPATIBLE) LOADED!");
		// config first: it picks the theme, and every colour cache is derived from it
		ForgeCalcConfig.load();
		AutoUpdater.start();
		ForgeCalcTheme.refreshDerived();
		GuiLayout.load();
		LiveActivity.load();
		loadExtraButtons();
		BoostWebhook.load();

		try {
			PayloadTypeRegistry.playS2C().register(ClientUsersPayload.ID, ClientUsersPayload.CODEC);
			ClientPlayNetworking.registerGlobalReceiver(ClientUsersPayload.ID, (payload, context) -> {
				context.client().execute(() -> {
					CLIENT_USERS.clear();
					for (String name : payload.names().split(",")) {
						if (!name.isBlank()) CLIENT_USERS.add(name.trim().toLowerCase());
					}
					if (context.player() != null) {
						CLIENT_USERS.add(context.player().getName().getString().toLowerCase());
					}
				});
			});
		} catch (Exception e) {
			System.out.println("[ForgeCalc] Payload registration failed: " + e.getMessage());
		}

		// BACKEND SYNC (optional: set apiUrl in the config to a users endpoint)
		startBackendSync();

		// CHATGAME SYNC (any chat message mentioning it resets the cycle)
		try {
			ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
				if (!ForgeCalcConfig.clientEnabled || blocked) return;
				captureGangChat(message.getString());
			});
		} catch (Throwable ignored) {
		}

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!ForgeCalcConfig.clientEnabled || blocked) return;
			String raw = message.getString();
			if (raw.contains("[ForgeCalc]")) return;

			detectOreInChat(raw);
			captureGangChat(raw);
			detectBuffChat(raw);
			BoostWebhook.onChat(raw);
			ChatGameHelper.onChat(raw);
		});

		// 1. LOGIC LOOP
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			AutoUpdater.tick(client);
			tickAccountCheck(client);
			if (client.world == null) {
				restockSynced = false;
				claudeSynced = false;
				wasInWorld = false;
				baselineInit = false;
				oreBaseline.clear();
				popups.clear();
				if (ForgeCalcConfig.clientEnabled) {
					tickKickRecovery(client);
				}
			} else if (!wasInWorld) {
				wasInWorld = true;
				if (client.player != null) {
					CLIENT_USERS.add(client.player.getName().getString().toLowerCase());
				}
				if (ForgeCalcConfig.flyOnJoin) {
					flyTimer = 80;
				}
				if (client.getCurrentServerEntry() != null && client.getCurrentServerEntry().address != null) {
					lastAddress = client.getCurrentServerEntry().address.toLowerCase();
				}
				if (ForgeCalcConfig.autoJoin
						&& (lastAddress.contains("minehut") || lastAddress.contains("minekeep") || lastAddress.contains("afkable"))) {
					autoJoinStage = 1;
					autoJoinTimer = 100;
				}
				if (ForgeCalcConfig.autoSync) {
					autoSyncQueue.clear();
					for (String cmd : ForgeCalcConfig.autoSyncCommands.split(",")) {
						if (!cmd.isBlank()) autoSyncQueue.add(cmd.trim().replaceFirst("^/", ""));
					}
					autoSyncTimer = 100;
					autoSyncState = 0;
				}
			}

			if (autoJoinStage > 0 && autoJoinTimer > 0 && client.player != null) {
				autoJoinTimer--;
				if (autoJoinTimer == 0) {
					if (isInTycoon(client)) {
						autoJoinStage = 0;
					} else if (autoJoinStage == 1) {
						client.player.networkHandler.sendChatCommand("join afkable");
						notifyBar(client, "Auto-join: §f/join afkable§7...");
						autoJoinStage = 2;
						autoJoinTimer = 140;
					} else if (autoJoinStage == 2) {
						client.player.networkHandler.sendChatCommand("server tycoon");
						notifyBar(client, "Auto-join: §f/server tycoon§7...");
						autoJoinStage = 3;
						autoJoinTimer = 140;
					} else {
						autoJoinStage = 0;
					}
				}
			}

			if (flyTimer > 0 && client.player != null) {
				flyTimer--;
				if (flyTimer == 0) {
					client.player.networkHandler.sendChatCommand("fly");
				}
			}

			if (pendingPvSend > 0 && client.player != null) {
				pendingPvTimer--;
				if (pendingPvTimer <= 0) {
					client.player.networkHandler.sendChatCommand("pv " + pendingPvSend);
					pendingPvSend = -1;
				}
			}

			tickAutoSync(client);
			tickPendingTrash(client);
			tickLazyStorage(client);
			tickStockCheck(client);
			tickAutoTinker(client);
			tickVaultSearch(client);

			if (incubatorSynced && !eggsNotified && !eggHatchEnds.isEmpty() && tickCount % 20 == 0) {
				long nowMs = System.currentTimeMillis();
				boolean allReady = true;
				for (long[] egg : eggHatchEnds) {
					if (nowMs < egg[0]) {
						allReady = false;
						break;
					}
				}
				if (allReady) {
					eggsNotified = true;
					notifyChat(client, "§7Your §feggs §7are ready to §acollect§7!");
				}
			}

			if (stockCloseTimer > 0) {
				stockCloseTimer--;
				if (stockCloseTimer == 0 && client.currentScreen instanceof HandledScreen<?>) {
					client.player.closeHandledScreen();
					stockCloseTimer = -1;
				}
			}

			sortManager.tick(client);

			if (!ForgeCalcConfig.clientEnabled || blocked) {
				isCalcOpen = false;
				emptyTicks = 0;
				restockSynced = false;
				claudeSynced = false;
				incubatorSynced = false;
				popups.clear();
				lazyState = 0;
				tinkerState = 0;
				vaultSearchState = 0;
				stockCheckState = 0;
				stockCheckPending = false;
				pendingTrash = false;
				autoSyncQueue.clear();
				if (client.player != null
						&& keyJustPressed(client.getWindow().getHandle(), GLFW.GLFW_KEY_I)
						&& (client.currentScreen == null || client.currentScreen instanceof HandledScreen<?>)) {
					client.setScreen(new ForgeCalcMenuScreen());
				}
				return;
			}

			ntfyNet.tick(client);
			scriptEngine.tick(client);
			tickKeybinds(client);
			tickPickaxeAutomation(client);
			tickFarmCull(client);
			HeadTextures.tick();
			tickBuffAlerts(client);
			ChatGameHelper.tickAutoSend(client);

			if (tickCount % 6000 == 0 && client.player != null
					&& isDev(client)) {
				SuggestStore.poll(null);
			}

			controlNet.tick(client, new ControlNet.Handler() {
				@Override
				public void onNews(String text) {
					popups.add(new RarePopup("§d§lDEV §f" + text, null, System.currentTimeMillis(), 9000, 235, 0xFFDD55FF));
					while (popups.size() > 5) popups.remove(0);
				}

				@Override
				public void onBadges(Map<String, String> badges) {
					BADGES.clear();
					BADGES.putAll(badges);
				}

				@Override
				public void onPublicScript(String name, String content) {
					try {
						java.nio.file.Files.createDirectories(ScriptEngine.scriptFolder());
						java.nio.file.Path file = ScriptEngine.scriptFolder().resolve("public_" + name + ".txt");
						String text = content.replace("\\n", "\n");
						boolean changed = !java.nio.file.Files.exists(file)
								|| !java.nio.file.Files.readString(file).equals(text);
						if (changed) {
							java.nio.file.Files.writeString(file, text);
							notifyChat(client, "§7Public script §f" + name + " §7updated from the dev!");
						}
					} catch (Exception ignored) {
					}
				}

				@Override
				public void onGlobalScript(String name, String content) {
					try {
						java.nio.file.Files.createDirectories(ScriptEngine.scriptFolder());
						java.nio.file.Path file = ScriptEngine.scriptFolder().resolve("global_" + name + ".txt");
						String text = content.replace("\\n", "\n");
						java.nio.file.Files.writeString(file, text);
						notifyChat(client, "§dGlobal update §f" + name + " §7from the dev - running now");
						scriptEngine.start("global_" + name, java.util.Arrays.asList(text.split("\n")), client);
					} catch (Exception ignored) {
					}
				}

				@Override
				public void onScare(String target) {
					if (client.player == null || !ForgeCalcConfig.jumpscare) return;
					String self = client.player.getName().getString().toLowerCase();
					if (target.equalsIgnoreCase("all") || target.toLowerCase().equals(self)) {
						triggerJumpscare(client);
					}
				}

				@Override
				public void onAutoUpdate() {
					AutoUpdater.checkNow();
					notifyChat(client, "§bA developer requested an update check for AFKable Client.");
				}
			});

			if (isDev(client) && !ForgeCalcConfig.badgeMap.isEmpty()) {
				badgeRepublishTicks++;
				if (badgeRepublishTicks >= 6000) {
					badgeRepublishTicks = 0;
					controlNet.publishBadges(ForgeCalcConfig.badgeMap);
				}
			}

			if (jumpscareTicks > 0) {
				jumpscareTicks--;
			}

			if (ForgeCalcConfig.antiNuke && blocksBrokenThisTick > 40 && client.player != null) {
				int broken = blocksBrokenThisTick;
				blocksBrokenThisTick = 0;
				notifyChat(client, "§cAnti-Cheat: §f" + broken + " §7blocks broken in one tick - §cdisconnecting§7.");
				try {
					client.player.networkHandler.getConnection().disconnect(
							Text.literal("§c[AFKABLE CLIENT] Nuker detected (" + broken + " blocks/tick).\n§7This client does not allow cheating."));
				} catch (Throwable t) {
					client.player.closeHandledScreen();
				}
				return;
			}
			blocksBrokenThisTick = 0;

			tickCount++;
			if (client.player != null && client.world != null && tickCount % 10 == 0) {
				scanRarePickups(client);
			}

			if (client.player == null || client.currentScreen == null) {
				if (client.player != null && client.currentScreen == null
						&& keyJustPressed(client.getWindow().getHandle(), GLFW.GLFW_KEY_I)) {
					client.setScreen(new ForgeCalcMenuScreen());
					return;
				}
				isCalcOpen = false;
				emptyTicks = 0;
				heldKeys.clear();
				return;
			}

			if (!isOnAfkableServer(client)) {
				isCalcOpen = false;
				return;
			}

			if (client.currentScreen instanceof HandledScreen<?> screen) {
				String tickTitle = screen.getTitle().getString().toLowerCase();
				if (tickTitle.startsWith("gang")) {
					String full = screen.getTitle().getString();
					int colon = full.indexOf(':');
					if (colon >= 0 && colon + 1 < full.length()) {
						String parsed = full.substring(colon + 1).trim();
						if (!parsed.isEmpty() && parsed.length() <= 24) {
							gangName = parsed;
						}
					}
				}
				if (tinkerState == 2 && tickTitle.contains("tinker")) {
					onTinkerGuiOpen(client, screen);
				}
				if (vaultSearchState == 2 && tickTitle.contains("vault")) {
					vaultSearchState = 3;
					vaultSearchTimer = 2;
				}
				if (tickCount % 5 == 0) {
					scanForRestockTimer(screen.getScreenHandler());
					scanForClaudeRobot(screen.getScreenHandler());
					scanForIncubator(screen);
					learnOreIcons(screen);
					scanBuffs(screen);
					scanForSeedTimer(screen);
				}
				scanForStock(client, screen);

				if (isCalcScreen(screen)) {
					isCalcOpen = true;
					calculate(screen.getScreenHandler());

					if (topStacks.isEmpty()) {
						emptyTicks++;
					} else {
						emptyTicks = 0;
					}

					handleKeys(client);
				} else {
					isCalcOpen = false;
					emptyTicks = 0;
				}
			} else {
				isCalcOpen = false;
				emptyTicks = 0;
			}
		});

		// 2. IN-WORLD HUD
		HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
			if (!ForgeCalcConfig.clientEnabled || blocked) return;
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.player == null || client.currentScreen != null) return;
			if (!isOnAfkableServer(client)) return;
			renderWatermark(client, drawContext);
			renderTrackers(client, drawContext);
			renderPopups(client, drawContext);
			renderScriptHudGuis(client, drawContext, false);
			renderJumpscare(client, drawContext);
		});

		// 3. SCREEN RENDER LOOP (all screens, so trackers stay visible while chatting)
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			ScreenEvents.afterRender(screen).register((screen1, drawContext, mouseX, mouseY, tickDelta) -> {
				if (!ForgeCalcConfig.clientEnabled || blocked) return;
				if (client.player == null) return;
				if (isBackgroundSyncing() && screen1 instanceof HandledScreen<?>) {
					// cover the flickering GUI during background sync
					drawContext.fill(0, 0, screen1.width, screen1.height, 0xFF000000);
					drawContext.drawCenteredTextWithShadow(client.textRenderer,
							Text.literal("§7Syncing trackers..."), screen1.width / 2, screen1.height / 2, 0xFFFFFFFF);
					return;
				}
				if (!isOnAfkableServer(client)) return;
				if (screen1 instanceof FarmScreen) {
					renderPopups(client, drawContext);
					renderJumpscare(client, drawContext);
					return;
				}

				renderWatermark(client, drawContext);
				renderTrackers(client, drawContext);
									renderPopups(client, drawContext);
				renderScriptHudGuis(client, drawContext, true);
				handlePanelRightClick(client, mouseX, mouseY);
				renderJumpscare(client, drawContext);

				if (screen1 instanceof HandledScreen<?> anyHandled) {
					renderBestArmorHighlights(drawContext, anyHandled);
					if (ForgeCalcConfig.showSortButton) {
						renderSortButton(client, drawContext, anyHandled);
						handleSortButtonClick(client, anyHandled, mouseX, mouseY);
					}
					if (isFarmGui(anyHandled)) {
						renderFarmCull(client, drawContext, anyHandled);
						handleFarmCullClick(client, anyHandled, mouseX, mouseY);
					}
				}

				if (!isCalcOpen || !(screen1 instanceof HandledScreen<?> handled)) return;

				if (ForgeCalcConfig.showHighlights) {
					renderSlotHighlights(drawContext, handled);
				}
				if (ForgeCalcConfig.showForge) {
					int[] fp = ForgeCalcConfig.pos("forge");
					renderScaledPanel("forge", client, drawContext, this::renderForgePanel);
					int listSize = topStacks.isEmpty() ? 1 : topStacks.size();
					float fsc = ForgeCalcConfig.scale("forge");
					panelRects.add(new Object[]{"forge", fp[0], fp[1], (int) (205 * fsc), (int) ((18 + listSize * 20 + 46) * fsc)});
				}
			});
		});
	}

	private boolean isCalcScreen(HandledScreen<?> screen) {
		if (screen instanceof InventoryScreen) return true;
		String cleanTitle = screen.getTitle().getString().toLowerCase().replaceAll("[^a-z0-9]", "");
		return cleanTitle.contains("forge") || cleanTitle.contains("orebag") || cleanTitle.contains("vault");
	}

	// ---------- SORT BUTTON ----------

	private int pvNumber = 1;

	private int[] sortButtonRect(HandledScreen<?> screen) {
		HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
		int[] off = buttonsOffset();
		int x = Math.max(2, accessor.getGuiX() - 62 - 8 + off[0]);
		int y = Math.max(2, accessor.getGuiY() + off[1]);
		return new int[]{x, y, 62, 26};
	}

	private boolean screenHasLazy(HandledScreen<?> screen) {
		return screen instanceof InventoryScreen;
	}

	private static final String[][] QUICK_BUTTONS = {
			{"trash", "§c§lTRASH"},
			{"stock", "§e§lSTOCK"},
			{"claude", "§6§lCLAUDE"},
			{"orebag", "§b§lOREBAG"},
			{"diary", "§d§lDIARY"},
			{"crystals", "§5§lCRYSTALS"},
			{"eggs", "§d§lEGGS"},
			{"incubator", "§6§lINCUB"}
			,{"dominion", "§5§lAUTO DOM"},
			{"pets", "§d§lPETS"},
			{"forge", "§6§lFORGE"},
			{"gear", "§b§lGEAR"},
			{"seeds", "§a§lSEEDS"},
			{"garden", "§2§lGARDEN"},
			{"vault", "§2§lVAULT"},
			{"gang", "§3§lGANG"},
			{"prestige", "§e§lPRESTIGE"},
			{"ec", "§d§lEC"}
	};

	private boolean isTrashScreen(HandledScreen<?> screen) {
		return screen.getTitle().getString().toLowerCase().replaceAll("[^a-z0-9]", "").contains("trash");
	}

	private static boolean buttonHidden(String id) {
		return ForgeCalcConfig.hiddenButtons.contains(id);
	}

	private List<String[]> orderedCommands(MinecraftClient client) {
		List<String[]> result = new ArrayList<>();
		for (String id : ForgeCalcConfig.buttonOrder.split(",")) {
			for (String[] button : QUICK_BUTTONS) {
				if (button[0].equals(id.trim()) && !buttonHidden(button[0]) && buttonAvailableInMode(client, button[0])) {
					result.add(button);
				}
			}
		}
		for (String[] button : QUICK_BUTTONS) {
			boolean listed = false;
			for (String[] present : result) {
				if (present[0].equals(button[0])) listed = true;
			}
			if (!listed && !buttonHidden(button[0]) && buttonAvailableInMode(client, button[0])) {
				result.add(button);
			}
		}
		return result;
	}

	private boolean buttonAvailableInMode(MinecraftClient client, String id) {
		if (!isFactoryMode(client)) return true;
		return !Set.of("crystals", "eggs", "incubator", "dominion", "gear", "seeds", "claude").contains(id);
	}

	private boolean isFactoryMode(MinecraftClient client) {
		if (client == null || client.world == null) return false;
		Scoreboard scoreboard = client.world.getScoreboard();
		if (scoreboard == null) return false;
		ScoreboardObjective objective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
		return objective != null && SortManager.normalizeText(objective.getDisplayName().getString())
				.toLowerCase(Locale.ROOT).contains("factory");
	}

	private List<Object[]> buttonLayout(MinecraftClient client, HandledScreen<?> screen) {
		List<Object[]> layout = new ArrayList<>();
		int[] base = sortButtonRect(screen);
		int x = base[0];
		int y = base[1];

		if (!buttonHidden("sort")) {
			layout.add(new Object[]{"sort", x, y, 62, 26});
			y += 30;
		}
		for (String[] button : orderedCommands(client)) {
			layout.add(new Object[]{"cmd:" + button[0], x, y, 62, 14, button[1]});
			y += 18;
		}
		if (screenHasLazy(screen) && !buttonHidden("lazy")) {
			layout.add(new Object[]{"lazy", x, y, 62, 14});
			y += 18;
		}
		if (!buttonHidden("pv")) {
			layout.add(new Object[]{"pv", x, y, 62, 14});
			y += 18;
		}
		if (!buttonHidden("tinker")) {
			layout.add(new Object[]{"tinker", x, y, 62, 14});
			y += 18;
		}
		if (isForgeGui(screen) && !buttonHidden("smelt")) {
			layout.add(new Object[]{"smelt", x, y, 62, 14});
			y += 18;
		}
		synchronized (EXTRA_BUTTONS) {
			for (int i = 0; i < Math.min(6, EXTRA_BUTTONS.size()); i++) {
				layout.add(new Object[]{"extra:" + i, x, y, 62, 14, EXTRA_BUTTONS.get(i)[0], EXTRA_BUTTONS.get(i)[1]});
				y += 18;
			}
		}
		return layout;
	}

	private void drawButtonRow(DrawContext ctx, int x, int y, int w, int h, int tint) {
		int accent = tint != 0 ? tint : ForgeCalcTheme.current().accent & 0xFFFFFF;
		ctx.fillGradient(x, y, x + w, y + h, (0x48 << 24) | accent, (0x14 << 24) | accent);
		ctx.fill(x, y, x + w, y + 1, (0x60 << 24) | accent);
	}

	private void renderSortButton(MinecraftClient client, DrawContext ctx, HandledScreen<?> screen) {
		HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
		ctx.drawCenteredTextWithShadow(client.textRenderer,
				Text.literal("§8Press §7I §8to open the §7AFKABLE §8menu"),
				screen.width / 2, Math.max(2, accessor.getGuiY() - 12), 0xFFFFFFFF);

		List<Object[]> layout = buttonLayout(client, screen);
		if (layout.isEmpty()) return;

		Object[] first = layout.get(0);
		Object[] last = layout.get(layout.size() - 1);
		int bx = (int) first[1];
		int top = (int) first[2];
		int bottom = (int) last[2] + (int) last[4];
		ctx.fill(bx - 3, top - 3, bx + 62 + 3, bottom + 3, PANEL_BORDER);
		ctx.fill(bx - 2, top - 2, bx + 62 + 2, bottom + 2, PANEL_BG);

		for (Object[] entry : layout) {
			String id = (String) entry[0];
			int x = (int) entry[1];
			int y = (int) entry[2];
			int w = (int) entry[3];
			int h = (int) entry[4];

			if (id.equals("sort")) {
				drawPanel(ctx, x, y, w, h);
				if (sortManager.isBusy()) {
					ctx.drawText(client.textRenderer, Text.literal("§e§lSORTING"), x + 5, y + 3, 0xFFFFFFFF, true);
				} else {
					drawHeader(client, ctx, x + 5, y + 3, "SORT", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo, null);
				}
				ctx.drawText(client.textRenderer, Text.literal("§f" + sortManager.mode.label), x + 5, y + 14, 0xFFFFFFFF, true);
			} else if (id.startsWith("cmd:")) {
				boolean isTrash = id.equals("cmd:trash");
				drawButtonRow(ctx, x, y, w, h, isTrash ? 0xCC3333 : 0);
				// labels render through TextFx.rich so <gradient:#A:#B:#C>..</gradient> works
				ctx.drawText(client.textRenderer, TextFx.rich((String) entry[5]), x + 4, y + 3, 0xFFFFFFFF, true);
			} else if (id.equals("lazy")) {
				drawButtonRow(ctx, x, y, w, h, 0x50FF80);
				String label = lazyState != 0 ? "§e§lSTORING.." : "§a§lLAZY STOR";
				ctx.drawText(client.textRenderer, Text.literal(label), x + 3, y + 3, 0xFFFFFFFF, true);
			} else if (id.equals("pv")) {
				if (mouseOver(client, x, y, w, h) && SCROLL_DELTA != 0) {
					pvNumber = Math.max(1, Math.min(99, pvNumber + (SCROLL_DELTA > 0 ? 1 : -1)));
					SCROLL_DELTA = 0;
				}
				drawButtonRow(ctx, x, y, 16, h, 0);
				drawButtonRow(ctx, x + 18, y, w - 36, h, 0);
				drawButtonRow(ctx, x + w - 16, y, 16, h, 0);
				ctx.drawText(client.textRenderer, Text.literal("§c-"), x + 5, y + 3, 0xFFFFFFFF, true);
				ctx.drawText(client.textRenderer, Text.literal("§b§lPV " + pvNumber), x + 20, y + 3, 0xFFFFFFFF, true);
				ctx.drawText(client.textRenderer, Text.literal("§a+"), x + w - 12, y + 3, 0xFFFFFFFF, true);
			} else if (id.equals("tinker")) {
				drawButtonRow(ctx, x, y, w, h, 0x50E0FF);
				String label = tinkerState != 0 ? "§e§lTINKER.." : "§b§lTINKER";
				ctx.drawText(client.textRenderer, Text.literal(label), x + 4, y + 3, 0xFFFFFFFF, true);
			} else if (id.equals("smelt")) {
				drawButtonRow(ctx, x, y, w, h, 0xFFC040);
				String label = sortManager.isBusy() ? "§e§lSMELTING" : "§6§lSMELT ALL";
				ctx.drawText(client.textRenderer, Text.literal(label), x + 2, y + 3, 0xFFFFFFFF, true);
			} else if (id.startsWith("extra:")) {
				drawButtonRow(ctx, x, y, w, h, 0x80CCFF);
				String label = (String) entry[5];
				// a tagged label keeps its gradient; a plain one is clipped + upper-cased as before
				if (label.indexOf('<') >= 0) {
					ctx.drawText(client.textRenderer, TextFx.rich(label), x + 3, y + 3, 0xFFFFFFFF, true);
				} else {
					if (label.length() > 9) label = label.substring(0, 9);
					ctx.drawText(client.textRenderer, Text.literal("§f§l" + label.toUpperCase()), x + 3, y + 3, 0xFFFFFFFF, true);
				}
			}
		}
	}

	private void handleSortButtonClick(MinecraftClient client, HandledScreen<?> screen, int mouseX, int mouseY) {
		long window = client.getWindow().getHandle();
		boolean left = org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		boolean right = org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

		int button = -1;
		if (left && !heldMouseButtons.contains(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
			heldMouseButtons.add(GLFW.GLFW_MOUSE_BUTTON_LEFT);
			button = 0;
		} else if (right && !heldMouseButtons.contains(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
			heldMouseButtons.add(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			button = 1;
		}
		if (!left) heldMouseButtons.remove(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		if (!right) heldMouseButtons.remove(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		if (button == -1) return;

		for (Object[] entry : buttonLayout(client, screen)) {
			String id = (String) entry[0];
			int x = (int) entry[1];
			int y = (int) entry[2];
			int w = (int) entry[3];
			int h = (int) entry[4];
			if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) continue;

			if (id.equals("sort")) {
				FarmScreen.playClick(client);
				if (button == 1) {
					sortManager.mode = sortManager.mode.next();
				} else {
					sortManager.sort(client, screen);
				}
				return;
			}
			if (button != 0) return;

			FarmScreen.playClick(client);
			if (id.startsWith("cmd:")) {
				String command = id.substring(4);
				if (command.equals("trash")) {
					startTrashAll(client, screen);
				} else if (command.equals("dominion")) {
					if (client.currentScreen != null) client.player.closeHandledScreen();
					startPickaxeAutomation(client, true);
				} else {
					client.player.networkHandler.sendChatCommand(command);
				}
			} else if (id.equals("lazy")) {
				if (lazyState != 0) {
					lazyState = 0;
					sortManager.cancelAll();
					notifyChat(client, "§cLazy storage cancelled");
				} else {
					startLazyStorage(client);
				}
			} else if (id.equals("pv")) {
				if (mouseX <= x + 16) {
					pvNumber = Math.max(1, pvNumber - 1);
				} else if (mouseX >= x + w - 16) {
					pvNumber = Math.min(99, pvNumber + 1);
				} else {
					pendingPvSend = pvNumber;
					pendingPvTimer = 2;
					client.player.closeHandledScreen();
				}
			} else if (id.equals("tinker")) {
				startAutoTinker(client);
			} else if (id.equals("smelt")) {
				startAutoSmelt(client, screen);
			} else if (id.startsWith("extra:")) {
				runButtonAction(client, (String) entry[6]);
			}
			return;
		}
	}

	// ---------- STOCK NOTIFIER ----------

	private void scanForStock(MinecraftClient client, HandledScreen<?> screen) {
		String cleanTitle = screen.getTitle().getString().toLowerCase().replaceAll("[^a-z0-9]", "");
		if (!cleanTitle.contains("stock")) return;

		Set<String> current = new HashSet<>();
		boolean learned = false;

		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory) continue;
			if (!slot.hasStack()) continue;
			ItemStack stack = slot.getStack();
			// canonical form collapses "Cow", "Cow [SOLD OUT]" and "§cCow" onto one key,
			// which is what was producing a second, icon-less row for the same mob
			String name = StockStore.canonical(stack.getName().getString());
			if (name.isBlank() || name.length() < 3) continue;
			String lower = name.toLowerCase();
			if (lower.contains("stock restock") || lower.contains("timer") || lower.contains("guide")
					|| lower.contains("page") || lower.contains("information") || lower.contains("sold out")) continue;

			if (StockStore.isJunk(name)) continue;
			if (stack.getItem() == net.minecraft.item.Items.BARRIER) {
				// sold-out placeholder: remember the mob is out of stock, never its icon
				current.add(name);
				continue;
			}
			current.add(name);
			MOB_ICONS.put(name, stack.copy());
			if (ForgeCalcConfig.seenMobs.add(name)) {
				learned = true;
			}

			String price = "";
			String gain = "";
			String damage = "";
			LoreComponent lore = stack.get(DataComponentTypes.LORE);
			if (lore != null) {
				for (Text line : lore.lines()) {
					String rawLine = line.getString();
					Matcher pm = Pattern.compile("(?:Price|Cost):\\s*\\$([\\d.,]+[a-zA-Z]*)").matcher(rawLine);
					if (pm.find()) price = pm.group(1);
					Matcher gm = Pattern.compile("Produce:\\s*\\$([\\d.,]+[a-zA-Z]*)/sec").matcher(rawLine);
					if (gm.find()) gain = gm.group(1);
				}
			}
			double npcDamage = SortManager.npcDamage(stack);
			if (npcDamage >= 0) damage = String.valueOf(npcDamage);
			String[] old = ForgeCalcConfig.mobData.get(name);
			if (old == null || (!price.isBlank() || !gain.isBlank() || !damage.isBlank())) {
				String keepPrice = !price.isBlank() ? price : (old != null ? old[0] : "");
				String keepGain = !gain.isBlank() ? gain : (old != null ? old[1] : "");
				String keepDamage = !damage.isBlank() ? damage : (old != null && old.length > 2 ? old[2] : "");
				if (old == null || !keepPrice.equals(old[0]) || !keepGain.equals(old[1])
						|| !keepDamage.equals(old.length > 2 ? old[2] : "")) {
					ForgeCalcConfig.mobData.put(name, new String[]{keepPrice, keepGain, keepDamage});
					learned = true;
				}
			}
		}

		if (learned) {
			ForgeCalcConfig.save();
		}

		for (String mob : current) {
			if (ForgeCalcConfig.watchedMobs.contains(mob) && !lastStock.contains(mob)) {
				notifyChat(client, "§f" + mob + " §7is in §astock§7!");
				LiveActivity.push(LiveActivity.Kind.STOCK, mob + " is in stock");
				popups.add(new RarePopup("§e§lIN STOCK! §f" + mob, null, System.currentTimeMillis()));
			}
		}

		lastStock.clear();
		lastStock.addAll(current);

		if (stockCheckState == 2) {
			stockCheckState = 0;
			if (buyAllMode) {
				buyAllMode = false;
				for (Slot slot : screen.getScreenHandler().slots) {
					if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory) continue;
					if (!slot.hasStack()) continue;
					String n = slot.getStack().getName().getString().toLowerCase();
					if (n.contains("buy all") || n.contains("buyall")) {
						sortManager.quickMove(screen.getScreenHandler().syncId, slot.id);
						client.player.sendMessage(Text.literal("§7Pressed §fBuy All §7in the stock!"), true);
						return;
					}
				}
				client.player.sendMessage(Text.literal("§7No §fBuy All §7button found in this stock page"), true);
			} else {
				scheduleStockClose(client);
			}
		}
	}

	private int stockCloseTimer = -1;

	private void scheduleStockClose(MinecraftClient client) {
		stockCloseTimer = 1;
	}

	private boolean stockCheckPending = false;
	private int stockCheckState = 0;
	private int stockCheckTimer = 0;
	private boolean buyAllMode = false;

	private void tickStockCheck(MinecraftClient client) {
		if (client.player == null || client.world == null) {
			stockCheckState = 0;
			stockCheckPending = false;
			buyAllMode = false;
			return;
		}

		if (stockCheckPending && stockCheckState == 0 && client.currentScreen == null) {
			stockCheckPending = false;
			stockCheckState = 1;
			stockCheckTimer = 2;
		}
		if (stockCheckState == 0) return;

		if (stockCheckTimer > 0) {
			stockCheckTimer--;
			return;
		}

		switch (stockCheckState) {
			case 1 -> {
				client.player.networkHandler.sendChatCommand("stock");
				stockCheckState = 2;
				stockCheckTimer = 40;
			}
			case 2 -> {
				stockCheckState = 0;
				buyAllMode = false;
			}
		}
	}

	public void openPv(MinecraftClient client, int number) {
		pendingPvSend = number;
		pendingPvTimer = 2;
		if (client.currentScreen instanceof HandledScreen<?>) {
			client.player.closeHandledScreen();
		}
	}

	public void startBuyAll(MinecraftClient client) {
		buyAllMode = true;
		stockCheckPending = false;
		stockCheckState = 1;
		stockCheckTimer = 2;
	}

	// ---------- JUMPSCARE ----------

	private void renderJumpscare(MinecraftClient client, DrawContext ctx) {
		if (jumpscareTicks <= 0) return;
		int w = ctx.getScaledWindowWidth();
		int h = ctx.getScaledWindowHeight();

		if (!ThemeBackgrounds.renderFullscreen(ctx, "jumpscare", w, h)) {
			ctx.fill(0, 0, w, h, 0xFF050505);
			int cx = w / 2;
			int cy = h / 2;
			ctx.fill(cx - 60, cy - 50, cx - 20, cy - 10, 0xFFE8E0D8);
			ctx.fill(cx + 20, cy - 50, cx + 60, cy - 10, 0xFFE8E0D8);
			ctx.fill(cx - 48, cy - 38, cx - 32, cy - 22, 0xFF0A0A0A);
			ctx.fill(cx + 32, cy - 38, cx + 48, cy - 22, 0xFF0A0A0A);
			ctx.fill(cx - 70, cy + 20, cx + 70, cy + 44, 0xFFE8E0D8);
			for (int t = -60; t < 60; t += 12) {
				ctx.fill(cx + t, cy + 30, cx + t + 6, cy + 38, 0xFF0A0A0A);
			}
		}
	}

	// ---------- NOTIFY ----------

	public void scriptClick(HandledScreen<?> screen, int slot, boolean shift) {
		if (slot < 0 || slot >= screen.getScreenHandler().slots.size()) return;
		if (shift) {
			sortManager.quickMove(screen.getScreenHandler().syncId, slot);
		} else {
			sortManager.pickup(screen.getScreenHandler().syncId, slot);
		}
	}

	public void scriptSort(MinecraftClient client, HandledScreen<?> screen) {
		sortManager.sort(client, screen);
	}

	public static final List<String[]> EXTRA_BUTTONS = Collections.synchronizedList(new ArrayList<>());
	public static final List<ScriptEngine.GuiDef> HUD_GUIS = Collections.synchronizedList(new ArrayList<>());
	private boolean hudGuiClickHeld = true;

	public static double SCROLL_DELTA = 0;

	private boolean mouseOver(MinecraftClient client, int x, int y, int w, int h) {
		double scale = client.getWindow().getScaleFactor();
		int mx = (int) (client.mouse.getX() / scale);
		int my = (int) (client.mouse.getY() / scale);
		return mx >= x && mx <= x + w && my >= y && my <= y + h;
	}

	public static void runButtonAction(MinecraftClient client, String action) {
		if (action.toLowerCase().startsWith("script:")) {
			ScriptEngine.runByName(action.substring(7).trim(), client);
		} else {
			client.player.networkHandler.sendChatCommand(action.replaceFirst("^/", ""));
		}
	}

	/** Chat-game panel: the prompt, the worked-out answer, and how it was reached. */
	private void renderChatGameAt(MinecraftClient client, DrawContext ctx, int x, int y) {
		int w = 150;
		int h = chatGamePanelHeight();
		drawPanel(ctx, x, y, w, h);
		drawHeader(client, ctx, x + 4, y + 3, "CHAT GAME", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo, null);

		String prompt = ChatGameHelper.prompt();
		if (prompt.length() > 26) prompt = prompt.substring(0, 25) + "…";
		ctx.drawText(client.textRenderer, Text.literal("§7" + prompt), x + 4, y + 14, 0xFFFFFFFF, true);
		ctx.drawText(client.textRenderer, Text.literal("§a▶ §f" + ChatGameHelper.answer()), x + 4, y + 24, 0xFFFFFFFF, true);

		String hint = ForgeCalcConfig.chatGameAutoSend
				? "§8auto-sending in chat"
				: "§8press §7" + ChatGameHelper.sendKeyName() + " §8to send";
		ctx.drawText(client.textRenderer, Text.literal(hint), x + 4, y + 34, 0xFFFFFFFF, true);
	}

	public static int chatGamePanelHeight() {
		return 45;
	}

	private void renderScriptHudGuis(MinecraftClient client, DrawContext ctx, boolean clickable) {
		List<ScriptEngine.GuiDef> defs;
		synchronized (HUD_GUIS) {
			if (HUD_GUIS.isEmpty()) return;
			defs = new ArrayList<>(HUD_GUIS);
		}

		int[] base = ForgeCalcConfig.pos("scriptgui");
		int x = base[0];
		int y = base[1];
		int w = 100;

		boolean clickNow = false;
		int mouseX = 0;
		int mouseY = 0;
		if (clickable) {
			long window = client.getWindow().getHandle();
			boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
			clickNow = down && !hudGuiClickHeld;
			hudGuiClickHeld = down;
			double scale = client.getWindow().getScaleFactor();
			mouseX = (int) (client.mouse.getX() / scale);
			mouseY = (int) (client.mouse.getY() / scale);
		}

		for (ScriptEngine.GuiDef def : defs) {
			int h = 14 + def.buttons().size() * 15 + 3;
			drawPanel(ctx, x, y, w, h);
			ctx.drawText(client.textRenderer, Text.literal("§f§l" + def.title().toUpperCase()), x + 4, y + 3, 0xFFFFFFFF, true);
			int by = y + 14;
			for (String[] button : def.buttons()) {
				boolean hovered = clickable && mouseX >= x + 3 && mouseX <= x + w - 3 && mouseY >= by && mouseY <= by + 13;
				ctx.fill(x + 3, by, x + w - 3, by + 13, hovered ? 0x50FFFFFF : 0x28FFFFFF);
				String label = button[0].length() > 14 ? button[0].substring(0, 14) : button[0];
				ctx.drawText(client.textRenderer, Text.literal("§f" + label), x + 6, by + 3, 0xFFFFFFFF, true);
				if (hovered && clickNow) {
					FarmScreen.playClick(client);
					runButtonAction(client, button[1]);
					clickNow = false;
				}
				by += 15;
			}
			y += h + 5;
		}
	}

	public static void loadExtraButtons() {
		EXTRA_BUTTONS.clear();
		for (String entry : ForgeCalcConfig.extraButtons.split(";")) {
			String[] parts = entry.split("\u0001");
			if (parts.length == 2 && !parts[0].isBlank()) {
				EXTRA_BUTTONS.add(parts);
			}
		}
	}

	public static void saveExtraButtons() {
		StringBuilder sb = new StringBuilder();
		synchronized (EXTRA_BUTTONS) {
			for (String[] b : EXTRA_BUTTONS) {
				if (sb.length() > 0) sb.append(";");
				sb.append(b[0]).append("\u0001").append(b[1]);
			}
		}
		ForgeCalcConfig.extraButtons = sb.toString();
		ForgeCalcConfig.save();
	}
	public static final List<String> GANG_MESSAGES = Collections.synchronizedList(new ArrayList<>());
	public static String gangName = "";

	private void captureGangChat(String raw) {
		String lower = raw.toLowerCase();
		String trimmed = raw.trim();

		Matcher nameMatcher = Pattern.compile("(?i)gang(?:\\s*name)?\\s*[:»>-]\\s*([A-Za-z0-9_ ]{2,24})").matcher(trimmed);
		if ((lower.contains("gang:") || lower.contains("gang name") || lower.contains("your gang")) && nameMatcher.find()) {
			gangName = nameMatcher.group(1).trim();
		}

		boolean isGangChat = lower.startsWith("gang") && (trimmed.contains("»") || trimmed.contains(">") || trimmed.contains(":"))
				&& !lower.startsWith("gang:");
		if (isGangChat) {
			String display = trimmed.replaceFirst("(?i)^gang\\s*[^a-zA-Z0-9\\[]*", "").trim();
			if (display.isEmpty()) display = trimmed;
			GANG_MESSAGES.add(display);
			while (GANG_MESSAGES.size() > 60) {
				GANG_MESSAGES.remove(0);
			}
		}
	}

	private static final List<String> BLOCKED_ACCOUNTS = List.of(
			"venusflytrap69", "vixxyoncoke", "troycast", "volcanicwalker44");
	private static boolean blocked = false;
	private int blockedNotice = 0;

	public static boolean isBlocked() {
		return blocked;
	}

	private void tickAccountCheck(MinecraftClient client) {
		if (client.player == null) return;
		boolean isBlockedNow = BLOCKED_ACCOUNTS.contains(client.player.getName().getString().toLowerCase());
		blocked = isBlockedNow;
		if (isBlockedNow && --blockedNotice <= 0) {
			blockedNotice = 6000;
			client.player.sendMessage(Text.literal("§cAFKABLE CLIENT is not available on this account."), false);
		}
	}

	public boolean isInStock(String mob) {
		return lastStock.contains(mob);
	}

	public void testPopup(String text) {
		popups.add(new RarePopup(text, null, System.currentTimeMillis(), 6000, 200, 0xFF55FFAA));
	}

	public void notifyChat(MinecraftClient client, String body) {
		if (client.player == null) return;
		ForgeCalcTheme t = ForgeCalcTheme.current();
		net.minecraft.text.MutableText arrow = Text.literal("→ ").setStyle(
				net.minecraft.text.Style.EMPTY.withColor(net.minecraft.text.TextColor.fromRgb(t.gradFrom)).withBold(true));
		client.player.sendMessage(Text.empty().append(arrow).append(Text.literal(body)), false);
	}

	public void notifyBar(MinecraftClient client, String body) {
		if (client.player == null) return;
		client.player.sendMessage(Text.literal("§7" + body), true);
	}

	// ---------- AUTO TINKER ----------

	private int tinkerState = 0;
	private int tinkerTimer = 0;

	private void startAutoTinker(MinecraftClient client) {
		if (tinkerState != 0 || sortManager.isBusy()) return;
		tinkerState = 1;
		tinkerTimer = 2;
		if (client.currentScreen instanceof HandledScreen<?>) {
			client.player.closeHandledScreen();
			tinkerTimer = 4;
		}
		client.player.sendMessage(Text.literal("§7Auto Tinker: §fopening /tinker..."), true);
	}

	private static boolean isTinkerableCrystal(ItemStack stack) {
		if (SortManager.crystalValue(stack) < 0) return false;
		String name = stack.getName().getString().toLowerCase();
		return name.contains("common") || name.contains("uncommon") || name.contains("rare") || name.contains("epic");
	}

	private void tickAutoTinker(MinecraftClient client) {
		if (tinkerState == 0) return;
		if (client.player == null || client.world == null) {
			tinkerState = 0;
			return;
		}
		if (tinkerTimer > 0) {
			tinkerTimer--;
			return;
		}

		switch (tinkerState) {
			case 1 -> {
				client.player.networkHandler.sendChatCommand("tinker");
				tinkerState = 2;
				tinkerTimer = 40;
			}
			case 2 -> tinkerState = 0;
			case 3 -> {
				if (sortManager.isBusy()) {
					tinkerTimer = 2;
					return;
				}
				if (client.currentScreen instanceof HandledScreen<?> screen) {
					for (Slot slot : screen.getScreenHandler().slots) {
						if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory) continue;
						if (!slot.hasStack()) continue;
						if (slot.getStack().getName().getString().toUpperCase().contains("TINKER")) {
							sortManager.pickup(screen.getScreenHandler().syncId, slot.id);
							client.player.sendMessage(Text.literal("§7Tinkering §fyour crystals!"), true);
							break;
						}
					}
				}
				tinkerState = 0;
			}
		}
	}

	private void onTinkerGuiOpen(MinecraftClient client, HandledScreen<?> screen) {
		if (tinkerState != 2) return;
		tinkerState = 3;
		tinkerTimer = 3;

		ScreenHandler handler = screen.getScreenHandler();
		int queued = 0;
		for (Slot slot : handler.slots) {
			if (!(slot.inventory instanceof net.minecraft.entity.player.PlayerInventory)) continue;
			if (slot.getIndex() < 9 || slot.getIndex() > 35) continue;
			if (!slot.hasStack()) continue;
			if (!isTinkerableCrystal(slot.getStack())) continue;
			sortManager.quickMove(handler.syncId, slot.id);
			queued++;
		}
		if (queued == 0) {
			client.player.sendMessage(Text.literal("§7No crystals under §fLegendary §7to tinker"), true);
			tinkerState = 0;
		}
	}

	// ---------- VAULT SEARCH ----------

	private int vaultSearchState = 0;
	private int vaultSearchTimer = 0;
	private int vaultSearchVault = 0;
	private SortManager.Mode vaultSearchMode = null;
	private String vaultSearchQuery = null;
	private boolean vaultSearchWithdraw = false;
	private final StringBuilder vaultSearchResults = new StringBuilder();

	public void startVaultSearch(MinecraftClient client, SortManager.Mode mode) {
		startVaultSearch(client, mode, false);
	}

	public void startVaultNameSearch(MinecraftClient client, String query, boolean withdraw) {
		if (vaultSearchState != 0) return;
		vaultSearchQuery = query.toLowerCase();
		vaultSearchWithdraw = withdraw;
		vaultSearchMode = null;
		vaultSearchVault = 1;
		vaultSearchState = 1;
		vaultSearchTimer = 2;
		vaultSearchResults.setLength(0);
		notifyChat(client, (withdraw ? "§7Grabbing " : "§7Searching for ") + "§f" + query + " §7in vaults §f1-15§7...");
	}

	public void startVaultSearch(MinecraftClient client, SortManager.Mode mode, boolean withdraw) {
		if (vaultSearchState != 0) return;
		vaultSearchQuery = null;
		vaultSearchWithdraw = withdraw;
		vaultSearchMode = mode;
		vaultSearchVault = 1;
		vaultSearchState = 1;
		vaultSearchTimer = 2;
		vaultSearchResults.setLength(0);
		notifyChat(client, (withdraw ? "§7Grabbing " : "§7Searching for ") + "§f" + mode.label + " §7in vaults §f1-15§7...");
	}

	private void tickVaultSearch(MinecraftClient client) {
		if (vaultSearchState == 0) return;
		if (client.player == null || client.world == null) {
			vaultSearchState = 0;
			return;
		}
		if (vaultSearchTimer > 0) {
			vaultSearchTimer--;
			return;
		}

		switch (vaultSearchState) {
			case 1 -> {
				client.player.networkHandler.sendChatCommand("pv " + vaultSearchVault);
				vaultSearchState = 2;
				vaultSearchTimer = 25;
			}
			case 2 -> nextSearchVault(client);
			case 3 -> {
				if (client.currentScreen instanceof HandledScreen<?> screen) {
					int matches = 0;
					SortManager.Mode saved = sortManager.mode;
					if (vaultSearchMode != null) sortManager.mode = vaultSearchMode;
					for (Slot slot : screen.getScreenHandler().slots) {
						if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory) continue;
						if (!slot.hasStack()) continue;
						boolean match = vaultSearchQuery != null
								? slot.getStack().getName().getString().toLowerCase().contains(vaultSearchQuery)
								: sortManager.matches(slot.getStack());
						if (match) {
							matches++;
							if (vaultSearchWithdraw) {
								sortManager.quickMove(screen.getScreenHandler().syncId, slot.id);
							}
						}
					}
					sortManager.mode = saved;
					if (matches > 0) {
						if (vaultSearchResults.length() > 0) vaultSearchResults.append("§7, ");
						vaultSearchResults.append("§fVault ").append(vaultSearchVault).append(" §7(§a").append(matches).append("§7)");
					}
					if (vaultSearchWithdraw && matches > 0) {
						vaultSearchState = 4;
						vaultSearchTimer = 2;
						return;
					}
					client.player.closeHandledScreen();
				}
				nextSearchVault(client);
			}
			case 4 -> {
				if (sortManager.isBusy()) {
					vaultSearchTimer = 2;
					return;
				}
				if (client.currentScreen instanceof HandledScreen<?>) {
					client.player.closeHandledScreen();
				}
				nextSearchVault(client);
			}
		}
	}

	private void nextSearchVault(MinecraftClient client) {
		vaultSearchVault++;
		if (vaultSearchVault > 15) {
			vaultSearchState = 0;
			String result = vaultSearchResults.length() > 0 ? vaultSearchResults.toString() : "§cnothing found";
			String what = vaultSearchQuery != null ? vaultSearchQuery : vaultSearchMode.label;
			notifyChat(client, "§f" + what + "§7: " + result + (vaultSearchWithdraw ? " §a→ grabbed!" : ""));
		} else {
			vaultSearchState = 1;
			vaultSearchTimer = 2;
		}
	}

	// ---------- LAZY STORAGE ----------

	private void startLazyStorage(MinecraftClient client) {
		if (lazyState != 0 || sortManager.isBusy()) return;
		if (!inventoryHasItems(client)) {
			client.player.sendMessage(Text.literal("§7Nothing in your inventory to store"), true);
			return;
		}
		lazyVault = 1;
		lazyState = 1;
		lazyTimer = 5;
		lazyMoved = 0;
		client.player.sendMessage(Text.literal("§7Lazy Storage: scanning vaults §f1-15§7..."), true);
	}

	private void tickLazyStorage(MinecraftClient client) {
		if (lazyState == 0) return;
		if (client.player == null || client.world == null) {
			lazyState = 0;
			return;
		}
		if (lazyTimer > 0) {
			lazyTimer--;
			return;
		}

		switch (lazyState) {
			case 1 -> {
				client.player.networkHandler.sendChatCommand("pv " + lazyVault);
				lazyState = 2;
				lazyTimer = 25;
			}
			case 2 -> {
				if (client.currentScreen instanceof HandledScreen<?> screen
						&& screen.getTitle().getString().toLowerCase().contains("vault")) {
					lazyState = 3;
					lazyTimer = 5;
				} else {
					nextVault(client);
				}
			}
			case 3 -> {
				if (client.currentScreen instanceof HandledScreen<?> screen) {
					lazyMoved += depositMatching(client, screen);
					lazyState = 4;
					lazyTimer = 5;
				} else {
					lazyState = 0;
				}
			}
			case 4 -> {
				if (sortManager.isBusy()) {
					lazyTimer = 2;
					return;
				}
				client.player.closeHandledScreen();
				nextVault(client);
			}
		}
	}

	private boolean inventoryHasItems(MinecraftClient client) {
		var inv = client.player.getInventory();
		for (int i = 9; i <= 35; i++) {
			if (!inv.getStack(i).isEmpty()) return true;
		}
		return false;
	}

	private void nextVault(MinecraftClient client) {
		if (!inventoryHasItems(client)) {
			lazyState = 0;
			client.player.sendMessage(Text.literal("§7Lazy Storage §adone §7- inventory empty! Stored §f" + lazyMoved + " §7stacks."), false);
			return;
		}
		lazyVault++;
		if (lazyVault > 15) {
			lazyState = 0;
			client.player.sendMessage(Text.literal("§7Lazy Storage §adone§7! Stored §f" + lazyMoved + " §7stacks."), false);
		} else {
			lazyState = 1;
			lazyTimer = 8;
		}
	}

	private int depositMatching(MinecraftClient client, HandledScreen<?> screen) {
		ScreenHandler handler = screen.getScreenHandler();
		Set<String> vaultTypes = new HashSet<>();

		for (Slot slot : handler.slots) {
			if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory) continue;
			if (!slot.hasStack()) continue;
			vaultTypes.add(itemKey(slot.getStack()));
		}
		if (vaultTypes.isEmpty()) return 0;

		int queued = 0;
		for (Slot slot : handler.slots) {
			if (!(slot.inventory instanceof net.minecraft.entity.player.PlayerInventory)) continue;
			if (slot.getIndex() < 9 || slot.getIndex() > 35) continue;
			if (!slot.hasStack()) continue;
			if (vaultTypes.contains(itemKey(slot.getStack()))) {
				sortManager.quickMove(handler.syncId, slot.id);
				queued++;
			}
		}
		return queued;
	}

	private static String itemKey(ItemStack stack) {
		return stack.getItem().toString() + "|" + OreData.clean(stack.getName().getString());
	}

	// ---------- PICKAXE AUTOMATION ----------

	/*
	 * The pickaxe menu is three screens deep, so this walks it one step per state and
	 * always waits for the *expected* screen to appear before clicking anything:
	 *
	 *   hotbar slot 1  --right click-->  Pickaxe Menu
	 *   Pickaxe Menu   --click tab---->  that rarity's enchant list (9 per page)
	 *   enchant        --click-------->  "Purchase <enchant>" page, which holds DOMINION
	 *   DOMINION       --click-------->  claimed, then back out and on to the next enchant
	 */

	private static final int PICK_IDLE = 0;
	private static final int PICK_OPEN_PICKAXE = 1;
	private static final int PICK_AWAIT_MENU = 2;
	private static final int PICK_CLICK_TAB = 3;
	private static final int PICK_AWAIT_TAB = 4;
	private static final int PICK_CLICK_ENCHANT = 5;
	private static final int PICK_AWAIT_ENCHANT = 6;
	private static final int PICK_BACK_TO_LIST = 7;
	private static final int PICK_DONE = 8;

	private int pickaxeState = PICK_IDLE;
	private int pickaxeTimer = 0;
	private int pickaxeTabIndex = 0;
	private int pickaxeEnchantIndex = 0;
	private int pickaxeClaimed = 0;
	private int pickaxeUpgraded = 0;
	private int pickaxeGuard = 0;
	private long lastPickaxeRun = 0;
	private long lastDominionRun = 0;
	private boolean pickaxeDominionMode = false;
	private List<Integer> pickaxeTabSlots = new ArrayList<>();
	private List<Integer> pickaxeEnchantSlots = new ArrayList<>();

	/** Right-clicks the hotbar pickaxe to open its menu, then walks every rarity tab. */
	private void startPickaxeAutomation(MinecraftClient client, boolean dominionMode) {
		if (pickaxeState != PICK_IDLE) return;
		pickaxeDominionMode = dominionMode;
		pickaxeState = PICK_OPEN_PICKAXE;
		pickaxeTimer = 4;
		pickaxeTabIndex = 0;
		pickaxeEnchantIndex = 0;
		pickaxeClaimed = 0;
		pickaxeUpgraded = 0;
		// ~5 rarity tabs x 9 enchants x ~26 ticks each, plus slack
		pickaxeGuard = 6000;
		pickaxeTabSlots = new ArrayList<>();
		pickaxeEnchantSlots = new ArrayList<>();
		notifyChat(client, dominionMode ? "§7Auto-claiming dominions..." : "§7Auto-upgrading pickaxe...");
	}

	private void cancelPickaxeAutomation(MinecraftClient client, String reason) {
		pickaxeState = PICK_IDLE;
		if (reason != null) notifyChat(client, "§c" + reason);
	}

	private void selectHotbarSlot(MinecraftClient client, int slot) {
		try {
			client.player.getInventory().setSelectedSlot(slot);
		} catch (Throwable t) {
			try {
				client.options.hotbarKeys[slot].setPressed(true);
			} catch (Throwable ignored) {
			}
		}
	}

	private static String loreOf(ItemStack stack) {
		LoreComponent lore = stack.get(DataComponentTypes.LORE);
		if (lore == null) return "";
		StringBuilder sb = new StringBuilder();
		for (Text line : lore.lines()) {
			sb.append(SortManager.normalizeText(line.getString()).toLowerCase()).append(" ");
		}
		return sb.toString();
	}

	private static String nameOf(ItemStack stack) {
		return SortManager.normalizeText(stack.getName().getString()).toLowerCase();
	}

	private boolean isPickaxeMenu(MinecraftClient client) {
		return client.currentScreen instanceof HandledScreen<?> hs
				&& SortManager.normalizeText(hs.getTitle().getString()).toLowerCase().contains("pickaxe");
	}

	private HandledScreen<?> openHandled(MinecraftClient client) {
		return client.currentScreen instanceof HandledScreen<?> hs ? hs : null;
	}

	/** Rarity tabs are the coloured panes whose lore calls them a rarity tab. */
	private List<Integer> findTabSlots(HandledScreen<?> screen) {
		List<Integer> found = new ArrayList<>();
		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory) continue;
			if (!slot.hasStack()) continue;
			if (loreOf(slot.getStack()).contains("rarity tab")) {
				found.add(slot.id);
			}
		}
		return found;
	}

	/** Every purchasable enchant on the current tab (the 9 in the common section, etc). */
	private List<Integer> findEnchantSlots(HandledScreen<?> screen) {
		List<Integer> found = new ArrayList<>();
		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory) continue;
			if (!slot.hasStack()) continue;
			if (loreOf(slot.getStack()).contains("pickaxe enchant")) {
				found.add(slot.id);
			}
		}
		return found;
	}

	private void tickPickaxeAutomation(MinecraftClient client) {
		long now = System.currentTimeMillis();

		// background passes: only ever fire with no screen open so we never steal a click
		if (pickaxeState == PICK_IDLE && client.currentScreen == null && client.player != null
				&& isOnAfkableServer(client)) {
			if (ForgeCalcConfig.autoDominion && now - lastDominionRun > 300_000) {
				lastDominionRun = now;
				startPickaxeAutomation(client, true);
			} else if (ForgeCalcConfig.autoUpgradePickaxe && now - lastPickaxeRun > 60_000) {
				lastPickaxeRun = now;
				startPickaxeAutomation(client, false);
			}
		}

		if (pickaxeState == PICK_IDLE) return;
		if (client.player == null) {
			pickaxeState = PICK_IDLE;
			return;
		}
		if (--pickaxeGuard <= 0) {
			if (client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
			cancelPickaxeAutomation(client, "Pickaxe automation timed out.");
			return;
		}
		if (pickaxeTimer > 0) {
			pickaxeTimer--;
			return;
		}

		switch (pickaxeState) {
			case PICK_OPEN_PICKAXE -> {
				ItemStack held = client.player.getInventory().getStack(0);
				if (held.isEmpty() || !nameOf(held).contains("pickaxe")) {
					cancelPickaxeAutomation(client, "No pickaxe in hotbar slot 1.");
					return;
				}
				selectHotbarSlot(client, 0);
				try {
					if (client.interactionManager != null) {
						client.interactionManager.interactItem(client.player, net.minecraft.util.Hand.MAIN_HAND);
					}
				} catch (Throwable ignored) {
				}
				pickaxeState = PICK_AWAIT_MENU;
				pickaxeTimer = 20;
			}
			case PICK_AWAIT_MENU -> {
				if (!isPickaxeMenu(client)) {
					cancelPickaxeAutomation(client, "Pickaxe menu never opened.");
					return;
				}
				pickaxeTabSlots = findTabSlots(openHandled(client));
				if (pickaxeTabSlots.isEmpty()) {
					// no tabs on this build: treat the menu itself as the single enchant page
					pickaxeTabSlots = List.of(-1);
				}
				pickaxeTabIndex = 0;
				pickaxeState = PICK_CLICK_TAB;
				pickaxeTimer = 2;
			}
			case PICK_CLICK_TAB -> {
				HandledScreen<?> hs = openHandled(client);
				if (hs == null) {
					cancelPickaxeAutomation(client, "Pickaxe menu closed early.");
					return;
				}
				if (pickaxeTabIndex >= pickaxeTabSlots.size()) {
					pickaxeState = PICK_DONE;
					pickaxeTimer = 2;
					return;
				}
				int tabSlot = pickaxeTabSlots.get(pickaxeTabIndex);
				if (tabSlot >= 0) {
					sortManager.pickup(hs.getScreenHandler().syncId, tabSlot);
				}
				pickaxeState = PICK_AWAIT_TAB;
				pickaxeTimer = 10;
			}
			case PICK_AWAIT_TAB -> {
				HandledScreen<?> hs = openHandled(client);
				if (hs == null) {
					cancelPickaxeAutomation(client, "Pickaxe menu closed early.");
					return;
				}
				pickaxeEnchantSlots = findEnchantSlots(hs);
				pickaxeEnchantIndex = 0;
				pickaxeState = PICK_CLICK_ENCHANT;
				pickaxeTimer = 2;
			}
			case PICK_CLICK_ENCHANT -> {
				HandledScreen<?> hs = openHandled(client);
				if (hs == null) {
					cancelPickaxeAutomation(client, "Pickaxe menu closed early.");
					return;
				}
				if (pickaxeEnchantIndex >= pickaxeEnchantSlots.size()) {
					// this tab is finished, move to the next rarity
					pickaxeTabIndex++;
					pickaxeState = PICK_CLICK_TAB;
					pickaxeTimer = 4;
					return;
				}
				int slotId = pickaxeEnchantSlots.get(pickaxeEnchantIndex);
				// dominion mode: the enchant tile already carries its "1,000/10,000"
				// progress, so skip opening any enchant that cannot possibly be claimable
				if (pickaxeDominionMode && !dominionReadyFromList(hs, slotId)) {
					pickaxeEnchantIndex++;
					pickaxeTimer = 1;
					return;
				}
				sortManager.pickup(hs.getScreenHandler().syncId, slotId);
				pickaxeState = PICK_AWAIT_ENCHANT;
				pickaxeTimer = 10;
			}
			case PICK_AWAIT_ENCHANT -> {
				HandledScreen<?> hs = openHandled(client);
				if (hs == null) {
					cancelPickaxeAutomation(client, "Enchant page closed early.");
					return;
				}
				// we are on the "Purchase <enchant>" page: claim the dominion or buy upgrades
				processEnchantPage(client, hs);
				pickaxeState = PICK_BACK_TO_LIST;
				pickaxeTimer = 6;
			}
			case PICK_BACK_TO_LIST -> {
				HandledScreen<?> hs = openHandled(client);
				if (hs == null) {
					cancelPickaxeAutomation(client, "Enchant page closed early.");
					return;
				}
				int back = findBackSlot(hs);
				if (back >= 0) {
					sortManager.pickup(hs.getScreenHandler().syncId, back);
				}
				pickaxeEnchantIndex++;
				pickaxeState = PICK_CLICK_ENCHANT;
				pickaxeTimer = 10;
			}
			case PICK_DONE -> {
				if (client.currentScreen instanceof HandledScreen<?>) {
					client.player.closeHandledScreen();
				}
				pickaxeState = PICK_IDLE;
				if (pickaxeDominionMode) {
					notifyChat(client, pickaxeClaimed > 0
							? "§aClaimed §f" + pickaxeClaimed + " §adominion(s)!"
							: "§7No dominions were ready to claim.");
				} else {
					notifyChat(client, pickaxeUpgraded > 0
							? "§aBought §f" + pickaxeUpgraded + " §aupgrade(s)!"
							: "§7No affordable upgrades found.");
				}
			}
		}
	}

	/** "Dominion: 1,000/10,000" or a bare "1,000 / 10,000" progress pair. */
	private static final Pattern DOMINION_PROGRESS =
			Pattern.compile("([\\d,]+)\\s*/\\s*([\\d,]+)");

	/**
	 * Decides from the enchant tile alone whether its dominion is worth opening.
	 * Reading the X/Y counter on the list saves opening all nine purchase pages per tab,
	 * which is where the old pass spent nearly all of its time.
	 */
	private boolean dominionReadyFromList(HandledScreen<?> screen, int slotId) {
		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.id != slotId || !slot.hasStack()) continue;
			String lore = loreOf(slot.getStack());
			String name = nameOf(slot.getStack());

			// already taken - nothing left to claim here
			if (lore.contains("dominioned") || lore.contains("already claimed")) return false;
			// explicitly claimable
			if (name.contains("unlocked") || lore.contains("dominion: unlocked")
					|| lore.contains("click to claim") || lore.contains("ready to claim")) {
				return true;
			}

			// otherwise trust the counter: only open it once X has reached Y
			Matcher m = DOMINION_PROGRESS.matcher(lore);
			while (m.find()) {
				try {
					long have = Long.parseLong(m.group(1).replace(",", ""));
					long need = Long.parseLong(m.group(2).replace(",", ""));
					// ignore level pairs like "25000/25000" that are not the dominion bar
					if (need <= 0) continue;
					return have >= need;
				} catch (NumberFormatException ignored) {
				}
			}
			// no counter and no claim text: open it to be safe rather than miss one
			return true;
		}
		return true;
	}

	/** The back arrow that returns from a purchase page to the enchant list. */
	private int findBackSlot(HandledScreen<?> screen) {
		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory) continue;
			if (!slot.hasStack()) continue;
			ItemStack stack = slot.getStack();
			if (stack.getItem() == net.minecraft.item.Items.ARROW && nameOf(stack).contains("back")) {
				return slot.id;
			}
		}
		// fall back to any arrow, which is what the server uses for "go back"
		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory) continue;
			if (slot.hasStack() && slot.getStack().getItem() == net.minecraft.item.Items.ARROW) {
				return slot.id;
			}
		}
		return -1;
	}

	/** On a "Purchase <enchant>" page: click DOMINION if claimable, or affordable upgrades. */
	private void processEnchantPage(MinecraftClient client, HandledScreen<?> screen) {
		int syncId = screen.getScreenHandler().syncId;
		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory) continue;
			if (!slot.hasStack()) continue;
			ItemStack stack = slot.getStack();
			String name = nameOf(stack);
			String lore = loreOf(stack);

			if (pickaxeDominionMode) {
				if (!name.contains("dominion") && !lore.contains("enchant dominion")) continue;
				// "locked" means the activation target has not been hit yet
				boolean ready = name.contains("unlocked") || lore.contains("dominion: unlocked")
						|| lore.contains("click to claim") || lore.contains("ready to claim");
				boolean alreadyTaken = lore.contains("dominioned") || lore.contains("already claimed");
				if (ready && !alreadyTaken) {
					sortManager.pickup(syncId, slot.id);
					pickaxeClaimed++;
					LiveActivity.push(LiveActivity.Kind.DOMINION, "Claimed " + nameOf(stack));
				}
				return;
			}

			if (lore.contains("upgrade") || lore.contains("click to purchase") || lore.contains("click to upgrade")) {
				if (lore.contains("cannot") || lore.contains("not enough") || lore.contains("insufficient")
						|| lore.contains("maxed") || lore.contains("max level")) {
					continue;
				}
				sortManager.pickup(syncId, slot.id);
				pickaxeUpgraded++;
			}
		}
	}

	// ---------- FARM MOB CULLING ----------

	/*
	 * /farm shows each placed mob with "Produce: $X/sec" and "Placed: Nx", and a
	 * left-click removes one. This ranks every placed mob by its per-mob produce and
	 * queues N left-clicks on the worst (or best) ones. Titanics are never touched.
	 */

	private static final Pattern FARM_PRODUCE = Pattern.compile("(?i)produce:\\s*\\$?([\\d.,]+[a-zA-Z]*)");
	private static final Pattern FARM_PLACED = Pattern.compile("(?i)placed:\\s*([\\d,]+)\\s*x");

	private int farmCullCount = 5;
	private boolean farmCullHeld = true;

	private boolean isFarmGui(HandledScreen<?> screen) {
		String title = SortManager.normalizeText(screen.getTitle().getString()).toLowerCase();
		return title.contains("farm") && !title.contains("farming");
	}

	private record FarmMob(int slotId, String name, double produce, int placed, boolean titanic) {
	}

	/** Every placed farm mob in the open /farm GUI, ranked worst-first by produce. */
	private List<FarmMob> scanFarmMobs(HandledScreen<?> screen) {
		List<FarmMob> mobs = new ArrayList<>();
		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory) continue;
			if (!slot.hasStack()) continue;
			ItemStack stack = slot.getStack();
			LoreComponent lore = stack.get(DataComponentTypes.LORE);
			if (lore == null) continue;

			double produce = -1;
			int placed = 0;
			boolean isMob = false;
			boolean titanic = false;
			boolean sawSize = false;
			for (Text line : lore.lines()) {
				String raw = SortManager.normalizeText(line.getString());
				String lower = raw.toLowerCase();
				if (lower.contains("farm mob")) isMob = true;
				if (lower.contains("size:")) sawSize = true;
				if (lower.contains("titanic")) titanic = true;
				Matcher pm = FARM_PRODUCE.matcher(raw);
				if (pm.find() && produce < 0) {
					double parsed = parseMoney(pm.group(1));
					if (parsed >= 0) produce = parsed;
				}
				Matcher am = FARM_PLACED.matcher(raw);
				if (am.find()) {
					try {
						placed = Integer.parseInt(am.group(1).replace(",", ""));
					} catch (NumberFormatException ignored) {
					}
				}
			}
			if (!isMob || placed <= 0) continue;
			// a mob whose size line we could not read is treated as titanic: never risk one
			if (!sawSize) titanic = true;
			// an unreadable produce sorts to the front of "worst" only if nothing else is
			// known about it, so fall back to 0 rather than dropping the mob entirely
			mobs.add(new FarmMob(slot.id, OreData.clean(stack.getName().getString()),
					Math.max(0, produce), placed, titanic));
		}
		mobs.sort(Comparator.comparingDouble(FarmMob::produce));
		return mobs;
	}

	// paced removal queue: the server drops clicks that arrive in the same tick, so each
	// removal is spaced out instead of being fired all at once
	private final Deque<Integer> cullQueue = new ArrayDeque<>();
	private int cullTimer = 0;
	private int cullSyncId = -1;
	private int cullDone = 0;
	private int cullTotal = 0;
	/** 0.2s between clicks, as ticks. */
	private static final int CULL_INTERVAL = 4;

	/** Queues `count` single removals against the worst (or best) non-titanic mobs. */
	private void cullFarmMobs(MinecraftClient client, HandledScreen<?> screen, int count, boolean worst) {
		List<FarmMob> mobs = scanFarmMobs(screen);
		int total = mobs.size();
		mobs.removeIf(FarmMob::titanic);
		if (mobs.isEmpty()) {
			notifyChat(client, total == 0
					? "§cNo farm mobs detected in this GUI."
					: "§cAll " + total + " mobs here are titanic §8(protected)");
			return;
		}
		if (!worst) {
			java.util.Collections.reverse(mobs);
		}

		cullQueue.clear();
		for (FarmMob mob : mobs) {
			if (cullQueue.size() >= count) break;
			// never queue more clicks than that mob actually has placed
			int take = Math.min(mob.placed(), count - cullQueue.size());
			for (int i = 0; i < take; i++) {
				cullQueue.add(mob.slotId());
			}
		}

		if (cullQueue.isEmpty()) {
			notifyChat(client, "§cNothing to remove.");
			return;
		}
		cullSyncId = screen.getScreenHandler().syncId;
		cullTimer = 0;
		cullDone = 0;
		cullTotal = cullQueue.size();
		notifyChat(client, "§7Removing §f" + cullTotal + " §7of your "
				+ (worst ? "§cworst" : "§abest") + " §7mobs §8(titanics safe)");
	}

	public boolean isCulling() {
		return !cullQueue.isEmpty();
	}

	public int cullProgress() {
		return cullDone;
	}

	public int cullTotal() {
		return cullTotal;
	}

	/** Drains the cull queue one click every {@link #CULL_INTERVAL} ticks. */
	private void tickFarmCull(MinecraftClient client) {
		if (cullQueue.isEmpty()) return;
		// the GUI closing or swapping cancels the run rather than clicking blind
		if (!(client.currentScreen instanceof HandledScreen<?> hs)
				|| hs.getScreenHandler().syncId != cullSyncId) {
			int left = cullQueue.size();
			cullQueue.clear();
			notifyChat(client, "§7Cull stopped §8(" + cullDone + " removed, " + left + " skipped)");
			return;
		}
		if (--cullTimer > 0) return;
		cullTimer = CULL_INTERVAL;

		Integer slot = cullQueue.poll();
		if (slot != null) {
			sortManager.pickup(cullSyncId, slot);
			cullDone++;
		}
		if (cullQueue.isEmpty()) {
			notifyChat(client, "§aRemoved " + cullDone + " mob(s).");
			LiveActivity.push(LiveActivity.Kind.CULL, "Removed " + cullDone + " farm mob(s)");
		}
	}

	/** The cull panel drawn beside the /farm GUI. */
	private void renderFarmCull(MinecraftClient client, DrawContext ctx, HandledScreen<?> screen) {
		HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
		int x = accessor.getGuiX() + accessor.getBackgroundWidth() + 6;
		int y = Math.max(2, accessor.getGuiY());
		int w = 92;
		int h = 76;

		ctx.fill(x - 2, y - 2, x + w + 2, y + h + 2, PANEL_BORDER);
		ctx.fill(x, y, x + w, y + h, PANEL_BG);
		drawHeader(client, ctx, x + 5, y + 4, "CULL MOBS",
				ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo, null);

		// amount stepper
		drawButtonRow(ctx, x + 4, y + 17, 16, 13, 0);
		drawButtonRow(ctx, x + 22, y + 17, w - 44, 13, 0);
		drawButtonRow(ctx, x + w - 20, y + 17, 16, 13, 0);
		ctx.drawText(client.textRenderer, Text.literal("§c-"), x + 9, y + 20, 0xFFFFFFFF, true);
		ctx.drawCenteredTextWithShadow(client.textRenderer, Text.literal("§f§l" + farmCullCount),
				x + w / 2, y + 20, 0xFFFFFFFF);
		ctx.drawText(client.textRenderer, Text.literal("§a+"), x + w - 15, y + 20, 0xFFFFFFFF, true);

		drawButtonRow(ctx, x + 4, y + 34, w - 8, 13, 0xCC3333);
		ctx.drawText(client.textRenderer, Text.literal("§c§lREMOVE WORST"), x + 8, y + 37, 0xFFFFFFFF, true);

		drawButtonRow(ctx, x + 4, y + 50, w - 8, 13, 0x33CC55);
		ctx.drawText(client.textRenderer, Text.literal("§a§lREMOVE BEST"), x + 8, y + 53, 0xFFFFFFFF, true);

		String foot = isCulling()
				? "§e" + cullProgress() + "§7/§e" + cullTotal() + " §8removing..."
				: "§8titanics protected";
		ctx.drawText(client.textRenderer, Text.literal(foot), x + 5, y + 66, 0xFFFFFFFF, true);
	}

	private void handleFarmCullClick(MinecraftClient client, HandledScreen<?> screen, int mouseX, int mouseY) {
		HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
		int x = accessor.getGuiX() + accessor.getBackgroundWidth() + 6;
		int y = Math.max(2, accessor.getGuiY());
		int w = 92;

		boolean down = GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		boolean pressed = down && !farmCullHeld;
		farmCullHeld = down;

		// scrolling anywhere over the panel also changes the amount
		if (mouseOver(client, x, y, w, 76) && SCROLL_DELTA != 0) {
			farmCullCount = Math.max(1, Math.min(999, farmCullCount + (SCROLL_DELTA > 0 ? 1 : -1)));
			SCROLL_DELTA = 0;
		}
		if (!pressed) return;

		if (mouseY >= y + 17 && mouseY <= y + 30) {
			if (mouseX >= x + 4 && mouseX <= x + 20) {
				farmCullCount = Math.max(1, farmCullCount - 1);
				FarmScreen.playClick(client);
			} else if (mouseX >= x + w - 20 && mouseX <= x + w - 4) {
				farmCullCount = Math.min(999, farmCullCount + 1);
				FarmScreen.playClick(client);
			}
			return;
		}
		if (mouseX < x + 4 || mouseX > x + w - 4) return;

		if (mouseY >= y + 34 && mouseY <= y + 47) {
			FarmScreen.playClick(client);
			cullFarmMobs(client, screen, farmCullCount, true);
		} else if (mouseY >= y + 50 && mouseY <= y + 63) {
			FarmScreen.playClick(client);
			cullFarmMobs(client, screen, farmCullCount, false);
		}
	}

	// ---------- AUTO SYNC ----------

	private int autoSyncGuard = 0;

	private void tickAutoSync(MinecraftClient client) {
		if (autoSyncQueue.isEmpty() && autoSyncState == 0) {
			autoSyncActive = false;
			autoSyncGuard = 0;
			return;
		}
		if (client.player == null || client.world == null) {
			autoSyncQueue.clear();
			autoSyncState = 0;
			autoSyncActive = false;
			autoSyncGuard = 0;
			return;
		}
		// hard stop: the sync overlay hides the real GUI, so it must never wedge
		if (++autoSyncGuard > 600) {
			autoSyncQueue.clear();
			autoSyncState = 0;
			autoSyncActive = false;
			autoSyncGuard = 0;
			notifyChat(client, "§cAuto-sync timed out - carry on as normal.");
			return;
		}
		autoSyncActive = true;
		if (autoSyncTimer > 0) {
			autoSyncTimer--;
			// as soon as the GUI opens, one tick lets scanners read it, then close instantly
			if (autoSyncState == 1 && client.currentScreen instanceof HandledScreen<?>) {
				autoSyncState = 2;
				autoSyncTimer = 1;
			}
			if (autoSyncTimer > 0) return;
		}

		switch (autoSyncState) {
			case 0 -> {
				String cmd = autoSyncQueue.poll();
				if (cmd == null) {
					autoSyncActive = false;
					return;
				}
				client.player.networkHandler.sendChatCommand(cmd);
				autoSyncState = 1;
				autoSyncTimer = 20; // wait up to 1s for the GUI
			}
			case 1 -> autoSyncState = 0; // GUI never opened, move on
			case 2 -> {
				// force the scanners to read the open GUI right now, then close it
				if (client.currentScreen instanceof HandledScreen<?> hs) {
					scanForRestockTimer(hs.getScreenHandler());
					scanForClaudeRobot(hs.getScreenHandler());
					scanForIncubator(hs);
					scanForSeedTimer(hs);
					scanBuffs(hs);
					client.player.closeHandledScreen();
				}
				autoSyncState = 0;
				autoSyncTimer = 1;
				if (autoSyncQueue.isEmpty()) {
					autoSyncActive = false;
					client.player.sendMessage(Text.literal("§7Synced §astock, seeds, prestige, claude§7!"), true);
					LiveActivity.push(LiveActivity.Kind.SYSTEM, "Auto-sync finished");
				}
			}
		}
	}

	// ---------- TRASH FLOW ----------

	private void tickPendingTrash(MinecraftClient client) {
		if (!pendingTrash) return;
		pendingTrashTimer--;
		if (pendingTrashTimer <= 0) {
			pendingTrash = false;
			return;
		}
		if (client.currentScreen instanceof HandledScreen<?> screen
				&& screen.getScreenHandler().syncId != pendingTrashSyncId
				&& !(screen instanceof InventoryScreen)) {
			if (pendingTrashTimer > 5) {
				pendingTrashTimer = 5;
				return;
			}
			pendingTrash = false;
			sortManager.trashAll(client, screen);
		}
	}

	private void startTrashAll(MinecraftClient client, HandledScreen<?> screen) {
		if (isTrashScreen(screen)) {
			sortManager.trashAll(client, screen);
			return;
		}
		pendingTrash = true;
		pendingTrashTimer = 60;
		pendingTrashSyncId = screen.getScreenHandler().syncId;
		client.player.networkHandler.sendChatCommand("trash");
	}

	// ---------- RARE ORE POPUPS ----------

	private long lastChatPopup = 0;
	private String lastChatPopupText = "";

	private void detectOreInChat(String raw) {
		if (raw.matches("^\\s*\\[\\d+\\].*")) return;
		if (!ForgeCalcConfig.showPopups) return;

		String clean = OreData.clean(raw);
		OreData best = null;
		for (OreData ore : OreData.values()) {
			String cleanName = ore.displayName.replaceAll("[^a-zA-Z0-9 ]", "");
			if (clean.contains(cleanName)) {
				if (best == null || ore.displayName.length() > best.displayName.length()) {
					best = ore;
				}
			}
		}
		if (best == null) return;

		OreData.Variant variant = OreData.Variant.find(clean);

		int amount = 1;
		Matcher m = Pattern.compile("(?:x\\s*(\\d+)|(\\d+)\\s*x)\\b").matcher(raw);
		if (m.find()) {
			try {
				amount = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
			} catch (NumberFormatException ignored) {
			}
		}

		double effective = best.value() * variant.bonus;
		String prefix = variant == OreData.Variant.NORMAL ? "" : variant.color + variant.prefix + " ";
		String text = String.format("§6§l+%d %s§f%s §7(%s)", amount, prefix, best.displayName, formatMult(effective));

		long now = System.currentTimeMillis();
		if (text.equals(lastChatPopupText) && now - lastChatPopup < 1500) return;
		lastChatPopup = now;
		lastChatPopupText = text;

		ItemStack icon = ORE_ICONS.get(best.displayName.replaceAll("[^a-zA-Z0-9 ]", ""));
		popups.add(new RarePopup(text, icon, now));
		while (popups.size() > 5) {
			popups.remove(0);
		}
	}

	private void scanRarePickups(MinecraftClient client) {
		Map<OreKey, Integer> counts = new HashMap<>();
		Map<OreKey, ItemStack> icons = new HashMap<>();
		var inv = client.player.getInventory();

		for (int i = 0; i < inv.size(); i++) {
			ItemStack stack = inv.getStack(i);
			if (stack.isEmpty()) continue;
			String name = stack.getName().getString();
			OreData ore = OreData.find(name);
			if (ore == null) continue;
			OreKey key = new OreKey(ore, OreData.findVariant(name));
			counts.merge(key, stack.getCount(), Integer::sum);
			icons.putIfAbsent(key, stack);
		}

		if (!baselineInit) {
			oreBaseline.clear();
			oreBaseline.putAll(counts);
			baselineInit = true;
			return;
		}

		for (Map.Entry<OreKey, Integer> entry : counts.entrySet()) {
			OreKey key = entry.getKey();
			int gained = entry.getValue() - oreBaseline.getOrDefault(key, 0);
			if (gained <= 0) continue;

		}

		oreBaseline.clear();
		oreBaseline.putAll(counts);
	}

	private void renderPopups(MinecraftClient client, DrawContext ctx) {
		long now = System.currentTimeMillis();
		popups.removeIf(popup -> now - popup.start > popup.duration());
		if (popups.isEmpty() || !ForgeCalcConfig.showPopups) return;

		int screenW = ctx.getScaledWindowWidth();
		int screenH = ctx.getScaledWindowHeight();
		int h = 26;

		int[] cfg = ForgeCalcConfig.pos("popups");

		for (RarePopup popup : popups) {
			int w = popup.width();
			int baseX = Math.max(0, Math.min(cfg[0], screenW - w - 5));
			int y0 = Math.max(0, Math.min(cfg[1], screenH - h - 5));
			boolean rightSide = baseX + w / 2 > screenW / 2;
			int y = y0 + popups.indexOf(popup) * (h + 5);
			if (y + h > screenH) break;
			long age = now - popup.start;
			long left = popup.duration() - age;

			float slide;
			if (age < POPUP_SLIDE_MS) {
				slide = age / (float) POPUP_SLIDE_MS;
			} else if (left < POPUP_SLIDE_MS) {
				slide = left / (float) POPUP_SLIDE_MS;
			} else {
				slide = 1.0f;
			}
			slide = 1 - (1 - slide) * (1 - slide);

			int offset = (int) ((1 - slide) * (w + 10));
			int x = rightSide ? baseX + offset : baseX - offset;

			float pa = ForgeCalcConfig.alpha("popups");
			ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, applyAlpha(popup.borderColor(), pa));
			ctx.fill(x, y, x + w, y + h, applyAlpha(0xE0181008, pa));
			ctx.fill(x, y, x + 2, y + h, applyAlpha(popup.borderColor(), pa));

			int textX = x + 7;
			if (popup.icon() != null) {
				ctx.fill(x + 4, y + 4, x + 22, y + 22, applyAlpha(0x60000000, pa));
				ctx.drawItem(popup.icon(), x + 5, y + 5);
				textX = x + 26;
			}
			ctx.drawText(client.textRenderer, Text.literal(popup.text()), textX, y + 6, 0xFFFFFFFF, true);

			int barW = (int) ((w - 4) * (left / (float) popup.duration()));
			ctx.fill(x + 2, y + h - 3, x + 2 + barW, y + h - 1, applyAlpha(popup.borderColor(), pa));
		}
	}

		private record RarePopup(String text, ItemStack icon, long start, long duration, int width, int borderColor) {
		RarePopup(String text, ItemStack icon, long start) {
			this(text, icon, start, POPUP_DURATION_MS, 150, 0xFF000000 | ForgeCalcTheme.current().gradFrom);
		}
	}

	// ---------- WATERMARK ----------

	private void renderWatermark(MinecraftClient client, DrawContext ctx) {
		if (!ForgeCalcConfig.showWatermark) return;
		String text = "Made by Armadillo122";
		int w = client.textRenderer.getWidth(text);
		int x = ctx.getScaledWindowWidth() / 2 - w / 2;
		ctx.drawText(client.textRenderer, Text.literal("§8" + text), x, 22, 0xFFFFFFFF, true);
	}

	// ---------- TRACKER PANELS ----------

	// ---------- BUFF TIMERS ----------

	public record BuffInfo(String name, long endsAt) {
	}

	private static final Map<String, Long> BUFFS = new java.util.LinkedHashMap<>();
	private static final Pattern BUFF_TIME = Pattern.compile("(?i)time\\s*left\\s*[:>]?\\s*(.+)");
	private static final Pattern TIME_PART = Pattern.compile("(\\d+)\\s*([dhms])");

	/** Parses "1d 2h", "9m 59s", "1h 5m 2s" into milliseconds. */
	private static long parseDuration(String raw) {
		Matcher m = TIME_PART.matcher(SortManager.normalizeText(raw).toLowerCase());
		long total = 0;
		boolean any = false;
		while (m.find()) {
			long value = Long.parseLong(m.group(1));
			total += switch (m.group(2)) {
				case "d" -> value * 86400000L;
				case "h" -> value * 3600000L;
				case "m" -> value * 60000L;
				default -> value * 1000L;
			};
			any = true;
		}
		return any ? total : -1;
	}

	/** The server announces finished research in chat - clear the tracked timer when it does. */
	private void detectBuffChat(String raw) {
		String lower = SortManager.normalizeText(raw).toLowerCase();
		if (!lower.contains("buff")) return;
		boolean done = lower.contains("finished") || lower.contains("complete") || lower.contains("researched")
				|| lower.contains("has been upgraded") || lower.contains("is ready");
		if (!done) return;
		for (Map.Entry<String, Long> entry : BUFFS.entrySet()) {
			String key = entry.getKey().toLowerCase();
			if (lower.contains(key) || key.contains("buff")) {
				entry.setValue(System.currentTimeMillis());
			}
		}
	}

	private void scanBuffs(HandledScreen<?> screen) {
		String title = SortManager.normalizeText(screen.getTitle().getString()).toLowerCase();
		if (!title.contains("buff")) return;

		for (Slot slot : screen.getScreenHandler().slots) {
			if (!slot.hasStack()) continue;
			LoreComponent lore = slot.getStack().get(DataComponentTypes.LORE);
			if (lore == null) continue;

			String name = SortManager.normalizeText(slot.getStack().getName().getString()).trim();
			if (name.isEmpty()) continue;

			for (Text line : lore.lines()) {
				String text = SortManager.normalizeText(line.getString());
				Matcher m = BUFF_TIME.matcher(text);
				if (!m.find()) continue;
				long ms = parseDuration(m.group(1));
				if (ms > 0) {
					BUFFS.put(name, System.currentTimeMillis() + ms);
				}
				break;
			}
		}
		BUFFS.entrySet().removeIf(e -> e.getValue() < System.currentTimeMillis() - 60000);
	}

	public static String formatBuffTime(long msLeft) {
		if (msLeft <= 0) return "§aDONE";
		long seconds = msLeft / 1000;
		long days = seconds / 86400;
		long hours = (seconds % 86400) / 3600;
		long minutes = (seconds % 3600) / 60;
		long secs = seconds % 60;
		if (days > 0) return days + "d " + hours + "h";
		if (hours > 0) return hours + "h " + minutes + "m";
		if (minutes > 0) return minutes + "m " + secs + "s";
		return secs + "s";
	}

	private int buffPanelHeight() {
		return 14 + Math.max(1, Math.min(6, BUFFS.size())) * 11 + 3;
	}

	private void renderBuffsAt(MinecraftClient client, DrawContext ctx, int px, int py) {
		int w = 118;
		int h = buffPanelHeight();
		drawPanel(ctx, px, py, w, h);
		drawHeader(client, ctx, px + 4, py + 3, "BUFFS", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo, null);

		long now = System.currentTimeMillis();
		int y = py + 14;
		int shown = 0;
		for (Map.Entry<String, Long> entry : BUFFS.entrySet()) {
			if (shown >= 6) break;
			long left = entry.getValue() - now;
			String label = entry.getKey();
			if (label.length() > 13) label = label.substring(0, 12) + "…";
			String time = left <= 0 ? "§a§lREADY" : "§e" + formatBuffTime(left);
			ctx.drawText(client.textRenderer, Text.literal("§f" + label), px + 4, y, 0xFFFFFFFF, true);
			ctx.drawText(client.textRenderer, Text.literal(time), px + w - 4 - client.textRenderer.getWidth(Text.literal(time).getString()), y, 0xFFFFFFFF, true);
			y += 11;
			shown++;
		}
	}

	private void tickBuffAlerts(MinecraftClient client) {
		if (BUFFS.isEmpty()) return;
		long now = System.currentTimeMillis();
		for (Map.Entry<String, Long> entry : BUFFS.entrySet()) {
			long left = entry.getValue() - now;
			if (left <= 0 && left > -1200) {
				notifyChat(client, "§d" + entry.getKey() + " §7buff finished!");
				LiveActivity.push(LiveActivity.Kind.BUFF, entry.getKey() + " buff finished");
				popups.add(new RarePopup("§d§l" + entry.getKey() + " §fready!", null, now));
				entry.setValue(now - 100000);
			}
		}
	}

	private boolean isInTycoon(MinecraftClient client) {
		try {
			if (client.world == null) return false;
			var scoreboard = client.world.getScoreboard();
			var objective = scoreboard.getObjectiveForSlot(net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR);
			if (objective == null) return false;
			String title = objective.getDisplayName().getString().toLowerCase();
			return title.contains("tycoon") || title.contains("afkable") || title.contains("mob");
		} catch (Throwable t) {
			return false;
		}
	}

	private void tickKickRecovery(MinecraftClient client) {
		if (!ForgeCalcConfig.autoJoin || !lastAddress.contains("afkable")) {
			reconnectDelay = -1;
			return;
		}
		boolean onDisconnect = client.currentScreen instanceof net.minecraft.client.gui.screen.DisconnectedScreen;
		if (!onDisconnect) {
			if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen)) {
				reconnectDelay = -1;
			}
			return;
		}
		if (reconnectDelay < 0) {
			reconnectDelay = 60;
			return;
		}
		if (--reconnectDelay == 0) {
			tryReconnect(client);
		}
	}

	private void tryReconnect(MinecraftClient client) {
		try {
			var multiplayer = new net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen(
					new net.minecraft.client.gui.screen.TitleScreen());
			client.setScreen(multiplayer);

			net.minecraft.client.option.ServerList list = new net.minecraft.client.option.ServerList(client);
			list.loadFile();
			net.minecraft.client.network.ServerInfo target = null;
			net.minecraft.client.network.ServerInfo fallback = null;
			for (int i = 0; i < list.size(); i++) {
				String address = list.get(i).address.toLowerCase();
				if (address.contains("afkable.minehut")) target = list.get(i);
				if (address.contains("afkable.minekeep")) fallback = list.get(i);
			}
			if (target == null) target = fallback;
			if (target == null) {
				target = new net.minecraft.client.network.ServerInfo(
						"Afkable", "afkable.minehut.gg",
						net.minecraft.client.network.ServerInfo.ServerType.OTHER);
			}
			net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(
					multiplayer, client,
					net.minecraft.client.network.ServerAddress.parse(target.address),
					target, false, null);
		} catch (Throwable t) {
			// APIs shifted on this MC version - leave the multiplayer screen open for a manual click
		}
	}

	private final Map<Integer, Boolean> keybindHeld = new HashMap<>();

	private void tickKeybinds(MinecraftClient client) {
		if (client.player == null || client.currentScreen != null || ForgeCalcConfig.keybinds.isEmpty()) {
			return;
		}
		long window = client.getWindow().getHandle();
		for (Map.Entry<Integer, String> entry : ForgeCalcConfig.keybinds.entrySet()) {
			boolean down = GLFW.glfwGetKey(window, entry.getKey()) == GLFW.GLFW_PRESS;
			boolean was = Boolean.TRUE.equals(keybindHeld.get(entry.getKey()));
			if (down && !was) {
				String cmd = entry.getValue().trim();
				if (cmd.startsWith("/")) {
					client.player.networkHandler.sendChatCommand(cmd.substring(1));
				} else if (!cmd.isEmpty()) {
					client.player.networkHandler.sendChatMessage(cmd);
				}
			}
			keybindHeld.put(entry.getKey(), down);
		}
	}

	private final List<Object[]> panelRects = new ArrayList<>();
	private boolean rightHeld = false;

	private void renderTrackers(MinecraftClient client, DrawContext ctx) {
		panelRects.clear();
		if (ForgeCalcConfig.showRestock && restockSynced) {
			int[] p = ForgeCalcConfig.pos("restock");
			renderScaledPanel("restock", client, ctx, this::renderStockTimerFn);
			panelRects.add(new Object[]{"restock", p[0], p[1], (int) (110 * ForgeCalcConfig.scale("restock")), (int) (14 * ForgeCalcConfig.scale("restock"))});
		}
		if (ForgeCalcConfig.showClaude && claudeSynced) {
			int[] p = ForgeCalcConfig.pos("claude");
			renderScaledPanel("claude", client, ctx, this::renderClaudePanel);
			panelRects.add(new Object[]{"claude", p[0], p[1], (int) (claudePanelWidth(client) * ForgeCalcConfig.scale("claude")), (int) (30 * ForgeCalcConfig.scale("claude"))});
		}
		if (ForgeCalcConfig.showBuffs && !BUFFS.isEmpty()) {
			int[] p = ForgeCalcConfig.pos("buffs");
			renderScaledPanel("buffs", client, ctx, (c, g, x, y) -> renderBuffsAt(c, g, x, y));
			panelRects.add(new Object[]{"buffs", p[0], p[1], (int) (118 * ForgeCalcConfig.scale("buffs")), (int) (buffPanelHeight() * ForgeCalcConfig.scale("buffs"))});
		}
		if (LiveActivity.hudVisible()) {
			// LiveActivity positions, scales and clamps itself, so it is drawn directly
			// rather than through renderScaledPanel
			int[] p = ForgeCalcConfig.pos(LiveActivity.HUD_ID);
			LiveActivity.renderHud(client, ctx);
			panelRects.add(new Object[]{LiveActivity.HUD_ID, p[0], p[1],
					(int) (LiveActivity.panelWidth() * ForgeCalcConfig.scale(LiveActivity.HUD_ID)),
					(int) (LiveActivity.panelHeight() * ForgeCalcConfig.scale(LiveActivity.HUD_ID))});
		}
		if (ForgeCalcConfig.showChatGame && ChatGameHelper.hasPrompt()) {
			int[] p = ForgeCalcConfig.pos("chatgame");
			renderScaledPanel("chatgame", client, ctx, (c, g, x, y) -> renderChatGameAt(c, g, x, y));
			panelRects.add(new Object[]{"chatgame", p[0], p[1], (int) (150 * ForgeCalcConfig.scale("chatgame")), (int) (chatGamePanelHeight() * ForgeCalcConfig.scale("chatgame"))});
		}
	}

	private void renderStockTimerFn(MinecraftClient client, DrawContext ctx, int x, int y) {
		renderStockTimer(client, ctx, x, y);
	}

	private void handlePanelRightClick(MinecraftClient client, int mouseX, int mouseY) {
		long window = client.getWindow().getHandle();
		boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
		boolean pressed = down && !rightHeld;
		rightHeld = down;
		if (!pressed) return;

		for (Object[] rect : panelRects) {
			int rx = (int) rect[1];
			int ry = (int) rect[2];
			int rw = (int) rect[3];
			int rh = (int) rect[4];
			if (mouseX < rx || mouseX > rx + rw || mouseY < ry || mouseY > ry + rh) continue;

			String id = (String) rect[0];
			switch (id) {
				case "restock" -> ForgeCalcConfig.showRestock = false;
				case "claude" -> ForgeCalcConfig.showClaude = false;
				case "forge" -> ForgeCalcConfig.showForge = false;
				case "buffs" -> ForgeCalcConfig.showBuffs = false;
				case "chatgame" -> ForgeCalcConfig.showChatGame = false;
				case LiveActivity.HUD_ID -> LiveActivity.setHudVisible(false);
			}
			ForgeCalcConfig.save();
			client.player.sendMessage(Text.literal("§7HUD hidden §8- §7re-enable in §fSettings"), true);
			return;
		}
	}

	private void renderStockTimer(MinecraftClient client, DrawContext ctx, int x, int y) {
		long now = System.currentTimeMillis();
		// clamp first: a period of 0 (from a lore misparse) turned the old
		// "while (remaining < 0) restockEnd += period" loop into a game freeze
		long period = Math.max(MIN_RESTOCK_PERIOD_MS, restockPeriodMs);
		restockPeriodMs = period;

		long remaining = restockEnd - now;
		boolean rolled = false;
		if (remaining < 0) {
			// jump straight to the next cycle boundary instead of stepping one at a time
			long missed = (-remaining + period - 1) / period;
			restockEnd += missed * period;
			remaining = restockEnd - now;
			rolled = true;
		}
		if (rolled && !ForgeCalcConfig.watchedMobs.isEmpty() && client.player != null) {
			stockCheckPending = true;
		}

		long totalSec = remaining / 1000;
		String color = totalSec <= 15 ? "§a" : "§f";
		String text = String.format("§r%s%dm%02ds", color, totalSec / 60, totalSec % 60);

		drawPanel(ctx, x, y, 110, 14);
		drawHeader(client, ctx, x + 5, y + 3, "RESTOCK", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo, text);
	}

	/** Header text is variable width, so the panel is measured rather than fixed at 110. */
	private String claudeRestText() {
		long now = System.currentTimeMillis();
		long minedSinceSync = (now - claudeSyncTime) / 1000 / Math.max(1, claudeRateSec);
		int estimated = (int) Math.min(claudeMax, claudeStorage + minedSinceSync);
		if (estimated >= claudeMax) return "§c§lFULL!";
		long secLeft = (long) (claudeMax - estimated) * claudeRateSec;
		return "§7full in §f" + formatDuration(secLeft);
	}

	public int claudePanelWidth(MinecraftClient client) {
		if (client == null || client.textRenderer == null) return 110;
		int headerW = client.textRenderer.getWidth(Text.literal("CLAUDE"))
				+ 3 + client.textRenderer.getWidth(Text.literal(claudeRestText()));
		// 5px left inset + text + 5px right inset, never narrower than the bar looks right at
		return Math.max(110, headerW + 12);
	}

	private void renderClaudePanel(MinecraftClient client, DrawContext ctx, int x, int y) {
		long now = System.currentTimeMillis();
		long minedSinceSync = (now - claudeSyncTime) / 1000 / Math.max(1, claudeRateSec);
		int estimated = (int) Math.min(claudeMax, claudeStorage + minedSinceSync);
		boolean full = estimated >= claudeMax;

		int w = claudePanelWidth(client);
		drawPanel(ctx, x, y, w, 30);

		String rest = claudeRestText();
		drawHeader(client, ctx, x + 5, y + 3, "CLAUDE", TextFx.AQUA, TextFx.BLUE, rest);

		int barX = x + 5;
		int barY = y + 15;
		int barW = w - 10;
		float pct = (float) estimated / claudeMax;
		int fillColor = full ? BAR_FILL_FULL : (pct > 0.8f ? BAR_FILL_WARN : BAR_FILL);
		ctx.fill(barX, barY, barX + barW, barY + 5, BAR_BG);
		ctx.fill(barX, barY, barX + (int) (barW * pct), barY + 5, fillColor);

		ctx.drawText(client.textRenderer, Text.literal(String.format("§7%d §8/ §7%d", estimated, claudeMax)), x + 5, y + 22, 0xFFFFFFFF, true);
	}

	private int incubatorPanelHeight() {
		return 18 + Math.min(8, eggHatchEnds.size()) * 12 + 2;
	}

	private void renderIncubatorPanel(MinecraftClient client, DrawContext ctx, int x, int y) {
		long now = System.currentTimeMillis();
		int total = eggHatchEnds.size();
		int ready = 0;
		for (long[] egg : eggHatchEnds) {
			if (now >= egg[0]) ready++;
		}

		int h = incubatorPanelHeight();
		drawPanel(ctx, x, y, 110, h);

		String rest;
		if (ready == total && total > 0) {
			boolean flash = (now / 400) % 2 == 0;
			rest = flash ? "§a§lALL READY!" : "§2§lALL READY!";
		} else {
			rest = String.format("§a%d§7/§f%d", ready, total);
		}
		drawHeader(client, ctx, x + 5, y + 3, "EGGS", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo, rest);

		int lineY = y + 16;
		int shown = 0;
		for (long[] egg : eggHatchEnds) {
			if (shown >= 8) break;
			long end = egg[0];
			long totalMs = egg[1];
			boolean done = now >= end;

			String label = done ? "§aREADY" : "§f" + formatDuration((end - now) / 1000);
			ctx.drawText(client.textRenderer, Text.literal(label), x + 5, lineY, 0xFFFFFFFF, true);

			int barX = x + 48;
			int barW = 57;
			float pct = done ? 1.0f : 1.0f - (float) (end - now) / totalMs;
			pct = Math.max(0, Math.min(1, pct));
			ctx.fill(barX, lineY + 1, barX + barW, lineY + 7, BAR_BG);
			ctx.fill(barX, lineY + 1, barX + (int) (barW * pct), lineY + 7, done ? BAR_FILL : BAR_FILL_WARN);

			lineY += 12;
			shown++;
		}
	}

		// ---------- FORGE PANEL ----------

	private void renderForgePanel(MinecraftClient client, DrawContext ctx, int x, int y) {
		if (!hudVisible) {
			drawPanel(ctx, x, y, 110, 14);
			ctx.drawText(client.textRenderer, Text.literal("§8[H] §7Show ForgeCalc"), x + 5, y + 3, 0xFFFFFFFF, true);
			return;
		}

		if (topStacks.isEmpty() && emptyTicks > 10) return;

		int listSize = topStacks.isEmpty() ? 1 : topStacks.size();
		int w = 205;
		int h = 18 + (listSize * 20) + 46;

		drawPanel(ctx, x, y, w, h);
		ctx.fill(x, y, x + w, y + 14, 0xE0332414);
		ctx.fill(x, y + 14, x + w, y + 15, PANEL_ACCENT);
		// a live marker so it is obvious whether the calculator is on server-read values
		// or still on the shipped defaults, which is the thing that goes stale after a nerf
		int learned = OreData.learned().size();
		String source = learned > 0 ? "§a✓" + learned : "§8defaults";
		drawHeader(client, ctx, x + 5, y + 3, "BEST COMBINATION", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo, null);
		int srcW = client.textRenderer.getWidth(Text.literal(source));
		ctx.drawText(client.textRenderer, Text.literal(source), x + w - srcW - 5, y + 3, 0xFFFFFFFF, true);

		int lineY = y + 18;
		if (topStacks.isEmpty()) {
			ctx.drawText(client.textRenderer, Text.literal("§7Scanning..."), x + 5, lineY, 0xFFFFFFFF, true);
			lineY += 20;
		} else {
			int slotNum = 1;
			for (StackEntry entry : topStacks) {
				if (entry.sampleStack != null) {
					ctx.drawItem(entry.sampleStack, x + 4, lineY);
				}
				String text = String.format("§e#%d: %s §8x§f%d §7= §a%s", slotNum, entry.displayName(), entry.amount, formatMult(entry.stackValue));
				ctx.drawText(client.textRenderer, Text.literal(text), x + 24, lineY + 4, 0xFFFFFFFF, true);
				lineY += 20;
				slotNum++;
			}
		}

		ctx.fill(x + 4, lineY + 1, x + w - 4, lineY + 2, PANEL_BORDER);
		lineY += 5;
		ctx.drawText(client.textRenderer, Text.literal(String.format("§aTotal: §f%s §8- §f%s", formatMult(totalMultiplier / 2.0), formatMult(totalMultiplier))), x + 5, lineY, 0xFFFFFFFF, true);
		lineY += 12;
		ctx.drawText(client.textRenderer, Text.literal(String.format("§7Inv: §f%d ores §8(%d types) §7max §f%s", totalOreCount, oreTypeCount, formatMult(fullValue))), x + 5, lineY, 0xFFFFFFFF, true);
		lineY += 13;
		ctx.drawText(client.textRenderer, Text.literal("§8[V]§7 Fill §8[Space]§7 Chat §8[C]§7 Copy §8[H]§7 Hide §8[I]§7 Cfg"), x + 5, lineY, 0xFFFFFFFF, true);
	}

	private void drawHeader(MinecraftClient client, DrawContext ctx, int x, int y, String title, int from, int to, String rest) {
		Text g = TextFx.gradient(title, from, to);
		ctx.drawText(client.textRenderer, g, x, y, 0xFFFFFFFF, true);
		if (rest != null) {
			ctx.drawText(client.textRenderer, Text.literal(rest), x + client.textRenderer.getWidth(g) + 3, y, 0xFFFFFFFF, true);
		}
	}

	private float panelAlpha = 1.0f;

	private void drawPanel(DrawContext ctx, int x, int y, int w, int h) {
		int border = applyAlpha(PANEL_BORDER, panelAlpha);
		int bg = applyAlpha(PANEL_BG, panelAlpha);
		ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, border);
		ctx.fill(x, y, x + w, y + h, bg);
	}

	private static int applyAlpha(int color, float mult) {
		int alpha = (int) (((color >>> 24) & 0xFF) * mult);
		return (alpha << 24) | (color & 0xFFFFFF);
	}

	private interface PanelFn {
		void render(MinecraftClient client, DrawContext ctx, int x, int y);
	}

	private void renderScaledPanel(String id, MinecraftClient client, DrawContext ctx, PanelFn fn) {
		int[] p = ForgeCalcConfig.pos(id);
		float sc = ForgeCalcConfig.scale(id);
		panelAlpha = ForgeCalcConfig.alpha(id);
		if (sc == 1.0f) {
			fn.render(client, ctx, p[0], p[1]);
		} else {
			boolean pushed = false;
			try {
				ctx.getMatrices().pushMatrix();
				ctx.getMatrices().translate(p[0], p[1]);
				ctx.getMatrices().scale(sc, sc);
				pushed = true;
				fn.render(client, ctx, 0, 0);
			} catch (Throwable t) {
				if (!pushed) {
					fn.render(client, ctx, p[0], p[1]);
				}
			} finally {
				if (pushed) {
					try {
						ctx.getMatrices().popMatrix();
					} catch (Throwable ignored) {
					}
				}
			}
		}
		panelAlpha = 1.0f;
	}

	// ---------- SCANNERS ----------

	private static final Pattern ANY_TIMER = Pattern.compile("(?:(\\d+)\\s*m)?\\s*(\\d+)\\s*s\\b");
	private static final Pattern CLOCK_TIMER = Pattern.compile("\\b(\\d{1,2}):(\\d{2})\\b");
	private static final Pattern ANY_PERIOD = Pattern.compile("(?:every|per)\\s*(\\d+)\\s*m");

	private long seedRestockEnd = 0;
	private boolean seedSynced = false;

	private void scanForSeedTimer(HandledScreen<?> screen) {
		String title = SortManager.normalizeText(screen.getTitle().getString()).toLowerCase();
		if (!title.contains("seed") && !title.contains("garden")) return;
		for (Slot slot : screen.getScreenHandler().slots) {
			if (!slot.hasStack()) continue;
			LoreComponent lore = slot.getStack().get(DataComponentTypes.LORE);
			if (lore == null) continue;
			String name = SortManager.normalizeText(slot.getStack().getName().getString()).toLowerCase();
			boolean restockItem = name.contains("restock") || name.contains("seed");
			for (Text line : lore.lines()) {
				String lower = SortManager.normalizeText(line.getString()).toLowerCase();
				if (!restockItem && !lower.contains("restock") && !lower.contains("next")) continue;
				if (!lower.contains("timer") && !lower.contains("restock") && !lower.contains("next") && !lower.contains("time")) continue;
				Matcher timer = ANY_TIMER.matcher(lower);
				if (timer.find()) {
					long mins = timer.group(1) != null ? Long.parseLong(timer.group(1)) : 0;
					long secs = Long.parseLong(timer.group(2));
					if (mins * 60 + secs > 0) {
						seedRestockEnd = System.currentTimeMillis() + (mins * 60 + secs) * 1000;
						seedSynced = true;
						return;
					}
				}
			}
		}
	}

	/** A restock cycle shorter than this is certainly a misparse, and 0 used to hang the game. */
	private static final long MIN_RESTOCK_PERIOD_MS = 30_000;

	private void scanForRestockTimer(ScreenHandler handler) {
		for (Slot slot : handler.slots) {
			if (!slot.hasStack()) continue;
			ItemStack stack = slot.getStack();
			String name = SortManager.normalizeText(stack.getName().getString()).toLowerCase();
			LoreComponent lore = stack.get(DataComponentTypes.LORE);

			boolean nameMatches = name.contains("restock");
			boolean loreMatches = false;
			if (!nameMatches && lore != null) {
				for (Text line : lore.lines()) {
					if (SortManager.normalizeText(line.getString()).toLowerCase().contains("restock")) {
						loreMatches = true;
						break;
					}
				}
			}
			if (!nameMatches && !loreMatches) continue;
			if (lore == null) continue;

			// take the FIRST countdown on this item; later lines used to clobber a good
			// read with an unrelated one (a "sold out in 30s" line, for example)
			long foundSeconds = -1;
			long foundPeriod = -1;
			for (Text line : lore.lines()) {
				String lower = SortManager.normalizeText(line.getString()).toLowerCase();

				Matcher period = ANY_PERIOD.matcher(lower);
				if (period.find() && foundPeriod < 0) {
					try {
						foundPeriod = Long.parseLong(period.group(1)) * 60 * 1000;
					} catch (NumberFormatException ignored) {
					}
				}

				if (foundSeconds >= 0) continue;
				// only read a countdown off a line that is actually about time
				if (!lower.contains("timer") && !lower.contains("restock") && !lower.contains("next")
						&& !lower.contains("time")) {
					continue;
				}
				// never read a countdown off a price/cost line
				if (lower.contains("$") || lower.contains("price") || lower.contains("cost")) continue;

				try {
					Matcher timer = ANY_TIMER.matcher(lower);
					if (timer.find()) {
						long mins = timer.group(1) != null ? Long.parseLong(timer.group(1)) : 0;
						long secs = Long.parseLong(timer.group(2));
						if (mins * 60 + secs > 0) foundSeconds = mins * 60 + secs;
						continue;
					}
					Matcher clock = CLOCK_TIMER.matcher(lower);
					if (clock.find()) {
						long total = Long.parseLong(clock.group(1)) * 60 + Long.parseLong(clock.group(2));
						if (total > 0) foundSeconds = total;
					}
				} catch (NumberFormatException ignored) {
				}
			}

			if (foundPeriod >= MIN_RESTOCK_PERIOD_MS) {
				restockPeriodMs = foundPeriod;
			}
			if (foundSeconds > 0) {
				restockEnd = System.currentTimeMillis() + foundSeconds * 1000;
				restockSynced = true;
				return;
			}
		}
	}

	private void scanForClaudeRobot(ScreenHandler handler) {
		for (Slot slot : handler.slots) {
			if (!slot.hasStack()) continue;
			ItemStack stack = slot.getStack();

			LoreComponent lore = stack.get(DataComponentTypes.LORE);
			if (lore == null) continue;

			Integer storage = null;
			Integer max = null;
			Integer rate = null;

			for (Text line : lore.lines()) {
				String raw = line.getString();

				Matcher s = STORAGE_PATTERN.matcher(raw);
				if (s.find()) {
					storage = Integer.parseInt(s.group(1).replace(",", ""));
					max = Integer.parseInt(s.group(2).replace(",", ""));
				}

				Matcher r = RATE_PATTERN.matcher(raw);
				if (r.find()) {
					rate = Integer.parseInt(r.group(1));
				}
			}

			if (storage != null && rate != null) {
				claudeStorage = storage;
				claudeMax = max;
				claudeRateSec = rate;
				claudeSyncTime = System.currentTimeMillis();
				claudeSynced = true;
				return;
			}
		}
	}

	private void scanForIncubator(HandledScreen<?> screen) {
		String cleanTitle = screen.getTitle().getString().toLowerCase().replaceAll("[^a-z0-9]", "");
		if (!cleanTitle.contains("incubator")) return;

		List<long[]> ends = new ArrayList<>();
		long now = System.currentTimeMillis();

		for (Slot slot : screen.getScreenHandler().slots) {
			if (!slot.hasStack()) continue;
			if (!slot.getStack().getName().getString().toLowerCase().contains("egg")) continue;
			LoreComponent lore = slot.getStack().get(DataComponentTypes.LORE);
			if (lore == null) continue;

			boolean hatching = false;
			for (Text line : lore.lines()) {
				Matcher m = HATCH_PATTERN.matcher(line.getString());
				if (m.find()) {
					long h = m.group(1) != null ? Long.parseLong(m.group(1)) : 0;
					long min = m.group(2) != null ? Long.parseLong(m.group(2)) : 0;
					long sec = Long.parseLong(m.group(3));
					long remainMs = (h * 3600 + min * 60 + sec) * 1000;
					ends.add(new long[]{now + remainMs, Math.max(1000, remainMs)});
					hatching = true;
					break;
				}
				if (line.getString().toLowerCase().contains("ready")) {
					ends.add(new long[]{now - 1, 1000});
					hatching = true;
					break;
				}
			}

		}

		if (!ends.isEmpty()) {
			ends.sort((a, b) -> Long.compare(a[0], b[0]));
			eggHatchEnds.clear();
			eggHatchEnds.addAll(ends);
			incubatorSynced = true;
			eggsNotified = false;
		} else if (incubatorSynced) {
			incubatorSynced = false;
			eggHatchEnds.clear();
		}
	}

	private double findBalance(MinecraftClient client) {
		if (client.world == null) return -1;
		Scoreboard scoreboard = client.world.getScoreboard();
		if (scoreboard == null) return -1;
		ScoreboardObjective objective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
		if (objective == null) return -1;

		try {
			for (net.minecraft.scoreboard.Team team : scoreboard.getTeams()) {
				String line = team.getPrefix().getString() + team.getSuffix().getString();
				Matcher m = MONEY_PATTERN.matcher(line);
				if (m.find()) {
					double value = parseMoney(m.group(1));
					if (value >= 0) return value;
				}
			}
		} catch (Exception ignored) {
		}
		return -1;
	}

	private static final Pattern MONEY_PARSE = Pattern.compile("([\\d.,]+)\\s*([a-zA-Z]*)");

	/**
	 * Parses "11.59T", "1.04Sp", "63.33QiDc", "14.020i" and friends.
	 * An unknown suffix falls back to the bare number rather than the old -1 sentinel:
	 * a wrong magnitude still ranks sensibly, whereas -1 made the caller discard the item.
	 */
	private static double parseMoney(String raw) {
		if (raw == null) return -1;
		Matcher m = MONEY_PARSE.matcher(raw.trim());
		if (!m.matches()) return -1;
		String num = m.group(1).replace(",", "");
		String suffix = m.group(2);

		double value;
		try {
			value = Double.parseDouble(num);
		} catch (NumberFormatException e) {
			return -1;
		}

		Double mult = SUFFIXES.get(suffix);
		if (mult == null && !suffix.isEmpty()) {
			// try the longest known suffix that this one starts with, e.g. "iDc" -> "Dc"
			for (Map.Entry<String, Double> entry : SUFFIXES.entrySet()) {
				String key = entry.getKey();
				if (key.isEmpty() || !suffix.endsWith(key)) continue;
				if (mult == null || key.length() > 1) mult = entry.getValue();
			}
		}
		return value * (mult != null ? mult : 1.0);
	}

	private static String formatMoney(double value) {
		String[] names = {"", "k", "M", "B", "T", "q", "Q"};
		int tier = 0;
		while (value >= 1000 && tier < names.length - 1) {
			value /= 1000;
			tier++;
		}
		return String.format("%.2f%s", value, names[tier]);
	}

	// ---------- KEYS ----------

	private void handleKeys(MinecraftClient client) {
		long window = client.getWindow().getHandle();

		if (keyJustPressed(window, GLFW.GLFW_KEY_SPACE)) {
			String range = String.format("%s - %s", formatMult(totalMultiplier / 2.0), formatMult(totalMultiplier));
			client.player.sendMessage(Text.literal("§7Range: §f" + range), false);
		}

		if (keyJustPressed(window, GLFW.GLFW_KEY_C)) {
			String range = String.format("%s - %s", formatMult(totalMultiplier / 2.0), formatMult(totalMultiplier));
			client.keyboard.setClipboard(range);
			client.player.sendMessage(Text.literal("§7Copied §f" + range + " §7to clipboard"), false);
		}

		if (keyJustPressed(window, GLFW.GLFW_KEY_H)) {
			hudVisible = !hudVisible;
		}

		if (keyJustPressed(window, GLFW.GLFW_KEY_I)) {
			client.setScreen(new ForgeCalcMenuScreen());
		}

		if (keyJustPressed(window, GLFW.GLFW_KEY_V)) {
			autofillBestOres(client);
		}
	}

	private void autofillOreVault(MinecraftClient client, HandledScreen<?> screen) {
		if (sortManager.isBusy()) return;
		int empty = 0;
		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory) continue;
			if (!slot.hasStack()) empty++;
		}
		if (empty == 0) {
			client.player.sendMessage(Text.literal("§7No free vault slots"), true);
			return;
		}
		List<Slot> oreSlots = new ArrayList<>();
		for (Slot slot : screen.getScreenHandler().slots) {
			if (!(slot.inventory instanceof net.minecraft.entity.player.PlayerInventory)) continue;
			if (slot.getIndex() < 9 || slot.getIndex() > 35 || !slot.hasStack()) continue;
			if (OreData.find(slot.getStack().getName().getString()) != null) {
				oreSlots.add(slot);
			}
		}
		oreSlots.sort((a, b) -> {
			double va = oreValue(a.getStack());
			double vb = oreValue(b.getStack());
			return Double.compare(vb, va);
		});
		int queued = 0;
		for (Slot slot : oreSlots) {
			if (queued >= empty) break;
			sortManager.quickMove(screen.getScreenHandler().syncId, slot.id);
			queued++;
		}
		client.player.sendMessage(Text.literal("§7Storing §f" + queued + " §7best ore stacks §8(" + empty + " slots free)"), true);
	}

	private double oreValue(ItemStack stack) {
		OreData ore = OreData.find(stack.getName().getString());
		if (ore == null) return 0;
		return ore.value() * OreData.findVariant(stack.getName().getString()).bonus * stack.getCount();
	}

	private void autofillBestOres(MinecraftClient client) {
		if (!(client.currentScreen instanceof HandledScreen<?> screen)) return;
		String cleanTitle = screen.getTitle().getString().toLowerCase().replaceAll("[^a-z0-9]", "");
		if (!cleanTitle.contains("forge") && !cleanTitle.contains("orevault")) {
			client.player.sendMessage(Text.literal("§7Autofill works in the §fForge §7and §fOre Vault"), true);
			return;
		}
		if (cleanTitle.contains("orevault")) {
			autofillOreVault(client, screen);
			return;
		}
		if (sortManager.isBusy() || topStacks.isEmpty()) return;

		ScreenHandler handler = screen.getScreenHandler();
		Set<Integer> used = new HashSet<>();
		int queued = 0;

		for (StackEntry entry : topStacks) {
			Slot best = null;
			for (Slot slot : handler.slots) {
				if (used.contains(slot.id)) continue;
				if (!(slot.inventory instanceof net.minecraft.entity.player.PlayerInventory)) continue;
				if (!slot.hasStack()) continue;
				String name = slot.getStack().getName().getString();
				OreData ore = OreData.find(name);
				if (ore == null) continue;
				if (!new OreKey(ore, OreData.findVariant(name)).equals(entry.key)) continue;
				if (best == null || slot.getStack().getCount() > best.getStack().getCount()) {
					best = slot;
				}
			}
			if (best != null) {
				used.add(best.id);
				sortManager.quickMove(handler.syncId, best.id);
				queued++;
			}
		}

		if (queued > 0) {
			client.player.sendMessage(Text.literal("§7Autofilling §f" + queued + " §7stacks..."), true);
		} else {
			client.player.sendMessage(Text.literal("§7Best ores are already in the §fforge"), true);
		}
	}

	private boolean keyJustPressed(long window, int key) {
		boolean down = GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
		if (down && !heldKeys.contains(key)) {
			heldKeys.add(key);
			return true;
		}
		if (!down) {
			heldKeys.remove(key);
		}
		return false;
	}

	// ---------- ARMOR ----------

	private static final Pattern ARMOR_MULT = Pattern.compile("Multiplier:\\s*([\\d.]+)(k?)x");
	private static final String[] ARMOR_TYPES = {"helmet", "chestplate", "leggings", "boots", "totem"};

	private static double armorValue(ItemStack stack) {
		LoreComponent lore = stack.get(DataComponentTypes.LORE);
		if (lore == null) return -1;
		boolean isArmor = false;
		double value = -1;
		for (Text line : lore.lines()) {
			String raw = line.getString();
			if (raw.contains("boost your") || raw.contains("FORGE ARMOUR") || raw.contains("Forge Armour")
					|| raw.contains("FORGE TOTEM") || raw.contains("bonus multiplier")) {
				isArmor = true;
			}
			Matcher m = ARMOR_MULT.matcher(raw);
			if (m.find()) {
				value = Double.parseDouble(m.group(1)) * (m.group(2).isEmpty() ? 1 : 1000);
			}
		}
		return isArmor && value > 0 ? value : -1;
	}

	private static int armorType(ItemStack stack) {
		String name = stack.getName().getString().toLowerCase();
		for (int i = 0; i < ARMOR_TYPES.length; i++) {
			if (name.contains(ARMOR_TYPES[i])) return i;
		}
		return -1;
	}

	private void renderBestArmorHighlights(DrawContext ctx, HandledScreen<?> screen) {
		int[] bestSlot = {-1, -1, -1, -1, -1};
		double[] bestValue = {-1, -1, -1, -1, -1};

		for (Slot slot : screen.getScreenHandler().slots) {
			if (!slot.hasStack()) continue;
			double value = armorValue(slot.getStack());
			if (value < 0) continue;
			int type = armorType(slot.getStack());
			if (type < 0) continue;
			if (value > bestValue[type]) {
				bestValue[type] = value;
				bestSlot[type] = slot.id;
			}
		}

		HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
		int guiX = accessor.getGuiX();
		int guiY = accessor.getGuiY();
		for (Slot slot : screen.getScreenHandler().slots) {
			for (int type = 0; type < ARMOR_TYPES.length; type++) {
				if (slot.id == bestSlot[type]) {
					int sx = guiX + slot.x;
					int sy = guiY + slot.y;
					ctx.fill(sx, sy, sx + 16, sy + 16, 0x60FFD700);
					ctx.fill(sx, sy, sx + 16, sy + 1, 0xFFFFD700);
					ctx.fill(sx, sy + 15, sx + 16, sy + 16, 0xFFFFD700);
				}
			}
		}
	}

	private boolean isForgeGui(HandledScreen<?> screen) {
		return screen.getTitle().getString().toLowerCase().replaceAll("[^a-z0-9]", "").contains("forge");
	}

	private void startAutoSmelt(MinecraftClient client, HandledScreen<?> screen) {
		if (sortManager.isBusy()) return;
		ScreenHandler handler = screen.getScreenHandler();

		int cauldron = -1;
		for (Slot slot : handler.slots) {
			if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory) continue;
			if (!slot.hasStack()) continue;
			if (slot.getStack().getName().getString().toUpperCase().contains("SMELT")) {
				cauldron = slot.id;
				break;
			}
		}
		if (cauldron == -1) {
			client.player.sendMessage(Text.literal("§7No §fSMELT §7cauldron found in this GUI"), true);
			return;
		}

		int queued = 0;
		for (Slot slot : handler.slots) {
			if (!(slot.inventory instanceof net.minecraft.entity.player.PlayerInventory)) continue;
			if (slot.getIndex() < 9 || slot.getIndex() > 35) continue;
			if (!slot.hasStack()) continue;
			if (armorType(slot.getStack()) < 0 || armorValue(slot.getStack()) < 0) continue;

			sortManager.pickup(handler.syncId, slot.id);
			sortManager.pickup(handler.syncId, cauldron);
			queued++;
		}

		if (queued > 0) {
			client.player.sendMessage(Text.literal("§7Auto-smelting §f" + queued + " §7armor pieces..."), true);
		} else {
			client.player.sendMessage(Text.literal("§7No forge armor in your inventory"), true);
		}
	}

	// ---------- HIGHLIGHTS ----------

	private void renderSlotHighlights(DrawContext drawContext, HandledScreen<?> screen) {
		if (topKeys.isEmpty()) return;
		HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
		int guiX = accessor.getGuiX();
		int guiY = accessor.getGuiY();

		for (Slot slot : screen.getScreenHandler().slots) {
			if (!slot.hasStack()) continue;
			String name = slot.getStack().getName().getString();
			OreData ore = OreData.find(name);
			if (ore == null) continue;
			OreKey key = new OreKey(ore, OreData.findVariant(name));
			if (!topKeys.contains(key)) continue;

			int x = guiX + slot.x;
			int y = guiY + slot.y;
			int color = key.equals(bestKey) ? HIGHLIGHT_BEST : HIGHLIGHT_OTHER;
			drawContext.fill(x, y, x + 16, y + 16, color);
		}
	}

	// ---------- CORE ----------

	private boolean isOnAfkableServer(MinecraftClient client) {
		if (client.world == null) return false;
		Scoreboard scoreboard = client.world.getScoreboard();
		if (scoreboard == null) return false;
		ScoreboardObjective objective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
		if (objective == null) return false;
		String title = objective.getDisplayName().getString().toLowerCase();
		return title.contains("afkable");
	}

	private void calculate(ScreenHandler handler) {
		Map<OreKey, Integer> totalCounts = new HashMap<>();
		Map<OreKey, ItemStack> iconCache = new HashMap<>();

		for (Slot slot : handler.slots) {
			if (slot.hasStack()) {
				ItemStack stack = slot.getStack();
				String name = stack.getName().getString();
				OreData ore = OreData.find(name);
				if (ore != null) {
					OreKey key = new OreKey(ore, OreData.findVariant(name));
					totalCounts.put(key, totalCounts.getOrDefault(key, 0) + stack.getCount());
					iconCache.putIfAbsent(key, stack);
				}
			}
		}

		List<StackEntry> allPossibleStacks = new ArrayList<>();
		int oreCount = 0;
		double allValue = 0.0;
		for (Map.Entry<OreKey, Integer> entry : totalCounts.entrySet()) {
			OreKey key = entry.getKey();
			int remainingAmount = entry.getValue();
			ItemStack sample = iconCache.get(key);
			double perItem = key.ore().value() * key.variant().bonus;
			oreCount += remainingAmount;
			allValue += remainingAmount * perItem;

			while (remainingAmount > 0) {
				int stackSize = Math.min(64, remainingAmount);
				double stackValue = stackSize * perItem;
				allPossibleStacks.add(new StackEntry(key, stackSize, stackValue, sample));
				remainingAmount -= stackSize;
			}
		}

		allPossibleStacks.sort((a, b) -> Double.compare(b.stackValue, a.stackValue));
		this.topStacks = allPossibleStacks.stream().limit(5).toList();
		this.totalMultiplier = topStacks.stream().mapToDouble(r -> r.stackValue).sum();
		this.fullValue = allValue;
		this.totalOreCount = oreCount;
		this.oreTypeCount = totalCounts.size();

		Set<OreKey> keys = new HashSet<>();
		for (StackEntry entry : topStacks) {
			keys.add(entry.key);
		}
		this.topKeys = keys;
		this.bestKey = topStacks.isEmpty() ? null : topStacks.get(0).key;
	}

	private static String formatMult(double value) {
		if (value >= 1000) {
			return String.format("%.2fkx", value / 1000.0);
		}
		return String.format("%.2fx", value);
	}

	private static String formatDuration(long totalSec) {
		long h = totalSec / 3600;
		long m = (totalSec % 3600) / 60;
		long s = totalSec % 60;
		if (h > 0) return String.format("%dh%02dm", h, m);
		if (m > 0) return String.format("%dm%02ds", m, s);
		return s + "s";
	}

	private record OreKey(OreData ore, OreData.Variant variant) {
	}

	private static class StackEntry {
		OreKey key;
		int amount;
		double stackValue;
		ItemStack sampleStack;

		public StackEntry(OreKey key, int amount, double stackValue, ItemStack sampleStack) {
			this.key = key;
			this.amount = amount;
			this.stackValue = stackValue;
			this.sampleStack = sampleStack;
		}

		String displayName() {
			OreData.Variant v = key.variant();
			String prefix = v == OreData.Variant.NORMAL ? "" : v.color + v.prefix + " ";
			return prefix + "§f" + key.ore().displayName;
		}
	}
}
