package com.example;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rolling, timestamped log of things that just happened.
 *
 * <p>This file owns both the model (a bounded ring buffer of {@link Entry}) and the HUD
 * renderer. {@link LiveActivityScreen} is the full history view.
 *
 * <p>Everything here is static so any part of the mod - including background threads -
 * can call {@link #push(Kind, String)} without holding a reference to anything.
 *
 * <p>The HUD panel is drawn with this file's own copies of the panel/header helpers,
 * because {@code ForgeOreCalculatorClient.drawPanel} / {@code drawHeader} are private to
 * that class. They are deliberately pixel-identical so the feed sits next to the other
 * trackers without looking bolted on.
 */
public final class LiveActivity {

	private LiveActivity() {
	}

	// ---------- kinds ----------

	/** Every event the feed understands, each with its own colour, glyph and item icon. */
	public enum Kind {
		ORE("ORE", "◆", 0xFF5FD2FF, Items.DIAMOND),
		STOCK("STOCK", "●", 0xFF3DDC53, Items.EMERALD),
		BUFF("BUFF", "★", 0xFFE879F9, Items.BREWING_STAND),
		BOOST("BOOST", "▲", 0xFFFFB020, Items.FIREWORK_ROCKET),
		DOMINION("DOM", "♦", 0xFFB44AFF, Items.NETHER_STAR),
		CULL("CULL", "✖", 0xFFFF5E5E, Items.SHEARS),
		FORGE("FORGE", "■", 0xFFFFC845, Items.BLAST_FURNACE),
		SYSTEM("SYS", "▪", 0xFF9BB0C8, Items.PAPER);

		/** Short tag shown on the filter chips. */
		public final String label;
		/** One-character HUD icon - a 16px item will not fit an 11px row. */
		public final String glyph;
		/** 0xAARRGGBB, used for the glyph, the row accent and the chip. */
		public final int color;

		private final Item item;
		private ItemStack cached;

		Kind(String label, String glyph, int color, Item item) {
			this.label = label;
			this.glyph = glyph;
			this.color = color;
			this.item = item;
		}

		/** Built lazily: the item registry is not ready while the enum class loads. */
		public ItemStack icon() {
			if (cached == null || cached.isEmpty()) {
				try {
					cached = new ItemStack(item);
				} catch (Throwable t) {
					cached = ItemStack.EMPTY;
				}
			}
			return cached;
		}

		public static Kind byName(String name) {
			if (name == null) return null;
			for (Kind kind : values()) {
				if (kind.name().equalsIgnoreCase(name.trim())) return kind;
			}
			return null;
		}
	}

	/** One logged event. {@code time} is wall-clock ms, stamped at push time. */
	public record Entry(Kind kind, String text, long time) {

		/** The message with the server's colour codes taken off, so it can be faded. */
		public String plain() {
			return strip(text);
		}
	}

	// ---------- ring buffer ----------

	/** How many events are kept. Older ones are silently overwritten. */
	public static final int CAPACITY = 200;

	private static final Object LOCK = new Object();
	private static final Entry[] RING = new Entry[CAPACITY];
	/** Total pushes ever - a long so the modulo below can never go negative. */
	private static long pushed = 0;

	/** Identical repeats inside this window are folded into one entry. */
	private static final long DEDUPE_MS = 1200;

	/**
	 * Records an event. Safe to call from any thread, including network callbacks.
	 * Null or blank text is ignored.
	 */
	public static void push(Kind kind, String text) {
		if (kind == null || text == null) return;
		String clean = text.trim();
		if (clean.isEmpty()) return;
		long now = System.currentTimeMillis();
		synchronized (LOCK) {
			if (pushed > 0) {
				Entry last = RING[(int) ((pushed - 1) % CAPACITY)];
				if (last != null && last.kind() == kind && last.text().equals(clean)
						&& now - last.time() < DEDUPE_MS) {
					return;
				}
			}
			RING[(int) (pushed % CAPACITY)] = new Entry(kind, clean, now);
			pushed++;
		}
	}

	/** Newest first. Always a fresh list, so callers can sort or filter it freely. */
	public static List<Entry> snapshot() {
		synchronized (LOCK) {
			int size = (int) Math.min(pushed, CAPACITY);
			List<Entry> out = new ArrayList<>(size);
			for (int i = 1; i <= size; i++) {
				Entry entry = RING[(int) ((pushed - i) % CAPACITY)];
				if (entry != null) out.add(entry);
			}
			return out;
		}
	}

	/** How many events are currently held (never more than {@link #CAPACITY}). */
	public static int size() {
		synchronized (LOCK) {
			return (int) Math.min(pushed, CAPACITY);
		}
	}

	public static void clear() {
		synchronized (LOCK) {
			java.util.Arrays.fill(RING, null);
			pushed = 0;
		}
	}

	// ---------- settings (own config file - ForgeCalcConfig is off limits) ----------

	/** HUD editor id. Must match the key used in SIZES / LABELS / POSITIONS. */
	public static final String HUD_ID = "activity";

	private static final int PANEL_W = 150;
	private static final int ROW_H = 11;
	private static final int HEADER_H = 14;

	public static final int MIN_ROWS = 1;
	public static final int MAX_ROWS = 10;

	private static boolean showHud = false;
	private static int maxRows = 5;
	private static int fadeSeconds = 20;
	private static final Map<Kind, Boolean> ENABLED = new EnumMap<>(Kind.class);

	private static boolean loaded = false;
	private static boolean registered = false;

	public static boolean hudVisible() {
		return showHud;
	}

	public static void setHudVisible(boolean value) {
		showHud = value;
	}

	public static int maxRows() {
		return maxRows;
	}

	public static void setMaxRows(int value) {
		maxRows = Math.max(MIN_ROWS, Math.min(MAX_ROWS, value));
		syncEditorSize();
	}

	public static int fadeSeconds() {
		return fadeSeconds;
	}

	public static void setFadeSeconds(int value) {
		fadeSeconds = Math.max(3, Math.min(600, value));
	}

	public static boolean isEnabled(Kind kind) {
		load();
		return kind != null && ENABLED.getOrDefault(kind, Boolean.TRUE);
	}

	public static void setEnabled(Kind kind, boolean value) {
		load();
		if (kind != null) ENABLED.put(kind, value);
	}

	public static void toggle(Kind kind) {
		setEnabled(kind, !isEnabled(kind));
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("afkable-activity.properties");
	}

	/** Reads the filter/HUD settings once. Safe (and cheap) to call from anywhere. */
	public static synchronized void load() {
		if (loaded) return;
		loaded = true;
		for (Kind kind : Kind.values()) {
			ENABLED.putIfAbsent(kind, Boolean.TRUE);
		}
		try {
			Path path = file();
			if (!Files.exists(path)) return;
			Properties props = new Properties();
			try (InputStream in = Files.newInputStream(path)) {
				props.load(in);
			}
			showHud = Boolean.parseBoolean(props.getProperty("showHud", String.valueOf(showHud)));
			maxRows = clampInt(props.getProperty("maxRows"), maxRows, MIN_ROWS, MAX_ROWS);
			fadeSeconds = clampInt(props.getProperty("fadeSeconds"), fadeSeconds, 3, 600);
			for (Kind kind : Kind.values()) {
				String raw = props.getProperty("show." + kind.name());
				if (raw != null) ENABLED.put(kind, Boolean.parseBoolean(raw.trim()));
			}
		} catch (Exception e) {
			System.out.println("[ForgeCalc] Failed to load activity settings: " + e.getMessage());
		} finally {
			syncEditorSize();
		}
	}

	public static void save() {
		load();
		try {
			Properties props = new Properties();
			props.setProperty("showHud", String.valueOf(showHud));
			props.setProperty("maxRows", String.valueOf(maxRows));
			props.setProperty("fadeSeconds", String.valueOf(fadeSeconds));
			for (Kind kind : Kind.values()) {
				props.setProperty("show." + kind.name(), String.valueOf(isEnabled(kind)));
			}
			Path path = file();
			Files.createDirectories(path.getParent());
			try (OutputStream out = Files.newOutputStream(path)) {
				props.store(out, "AFKABLE CLIENT - live activity feed");
			}
		} catch (Exception e) {
			System.out.println("[ForgeCalc] Failed to save activity settings: " + e.getMessage());
		}
	}

	private static int clampInt(String raw, int fallback, int min, int max) {
		if (raw == null) return fallback;
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(raw.trim())));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	// ---------- HUD editor plumbing ----------

	/** Default HUD spot: left column, clear of the buff panel's tallest layout. */
	private static final int DEFAULT_X = 5;
	private static final int DEFAULT_Y = 280;

	/**
	 * The live size array handed to {@code HudEditScreen.SIZES}. The map stores the
	 * reference, so resizing the feed (more rows) resizes the drag box too instead of
	 * leaving the editor showing a stale footprint.
	 */
	private static final int[] EDITOR_SIZE = {PANEL_W, HEADER_H + 5 * ROW_H + 3};

	public static int[] hudEditSize() {
		load();
		syncEditorSize();
		return EDITOR_SIZE;
	}

	private static void syncEditorSize() {
		EDITOR_SIZE[0] = PANEL_W;
		EDITOR_SIZE[1] = HEADER_H + Math.max(MIN_ROWS, maxRows) * ROW_H + 3;
	}

	/** Fixed HUD panel width. */
	public static int panelWidth() {
		return PANEL_W;
	}

	/** Tallest the HUD panel ever gets - a full {@link #maxRows()} of events. */
	public static int panelHeight() {
		load();
		return HEADER_H + Math.max(MIN_ROWS, maxRows) * ROW_H + 3;
	}

	/** Height actually drawn for a given number of rows. */
	private static int heightFor(int rows) {
		return HEADER_H + Math.max(1, rows) * ROW_H + 3;
	}

	/**
	 * Makes sure the panel has a saved position even if nobody added it to
	 * {@code ForgeCalcConfig.resetPositions()}. Call this BEFORE
	 * {@code ForgeCalcConfig.load()} if you want a dragged position to survive a restart -
	 * the loader only restores keys that already exist in POSITIONS.
	 */
	public static synchronized void ensureRegistered() {
		if (registered) return;
		registered = true;
		try {
			ForgeCalcConfig.POSITIONS.putIfAbsent(HUD_ID, new int[]{DEFAULT_X, DEFAULT_Y});
		} catch (Throwable ignored) {
		}
		load();
	}

	// ---------- HUD rendering ----------

	/** Fraction of an entry's life spent at full opacity before it starts fading. */
	private static final float FADE_HOLD = 0.7f;

	private static long hudTtlMs() {
		return Math.max(3, fadeSeconds) * 1000L;
	}

	/** The rows the HUD wants right now: newest first, filtered, capped and un-expired. */
	public static List<Entry> hudRows() {
		load();
		long now = System.currentTimeMillis();
		long ttl = hudTtlMs();
		List<Entry> out = new ArrayList<>();
		for (Entry entry : snapshot()) {
			// snapshot is newest-first, so the first expired entry ends the run
			if (now - entry.time() > ttl) break;
			if (!isEnabled(entry.kind())) continue;
			out.add(entry);
			if (out.size() >= maxRows) break;
		}
		return out;
	}

	/**
	 * Draws the feed panel at its configured position, honouring the HUD editor's
	 * scale and opacity. One call, nothing else needed.
	 */
	public static void renderHud(MinecraftClient client, DrawContext ctx) {
		if (client == null || ctx == null || client.textRenderer == null) return;
		ensureRegistered();
		if (!showHud) return;

		List<Entry> rows = hudRows();
		if (rows.isEmpty()) return;

		float scale = ForgeCalcConfig.scale(HUD_ID);
		float alpha = ForgeCalcConfig.alpha(HUD_ID);
		int[] pos = ForgeCalcConfig.pos(HUD_ID);

		// keep the panel on screen whatever the saved position and GUI scale
		int w = (int) (PANEL_W * scale);
		int h = (int) (heightFor(rows.size()) * scale);
		int x = Math.max(0, Math.min(pos[0], Math.max(0, ctx.getScaledWindowWidth() - w)));
		int y = Math.max(0, Math.min(pos[1], Math.max(0, ctx.getScaledWindowHeight() - h)));

		if (scale == 1.0f) {
			drawFeed(client, ctx, x, y, rows, alpha);
			return;
		}
		boolean pushedMatrix = false;
		try {
			ctx.getMatrices().pushMatrix();
			ctx.getMatrices().translate((float) x, (float) y);
			ctx.getMatrices().scale(scale, scale);
			pushedMatrix = true;
			drawFeed(client, ctx, 0, 0, rows, alpha);
		} catch (Throwable t) {
			if (!pushedMatrix) {
				drawFeed(client, ctx, x, y, rows, alpha);
			}
		} finally {
			if (pushedMatrix) {
				try {
					ctx.getMatrices().popMatrix();
				} catch (Throwable ignored) {
				}
			}
		}
	}

	private static void drawFeed(MinecraftClient client, DrawContext ctx, int x, int y, List<Entry> rows, float panelAlpha) {
		TextRenderer tr = client.textRenderer;
		int w = PANEL_W;
		int h = heightFor(rows.size());

		drawPanel(ctx, x, y, w, h, panelAlpha);
		drawHeader(client, ctx, x + 4, y + 3, "ACTIVITY");

		String total = "§8" + size();
		ctx.drawText(tr, Text.literal(total), x + w - 4 - tr.getWidth(total), y + 3, 0xFFFFFFFF, true);

		long now = System.currentTimeMillis();
		int rowY = y + HEADER_H;
		for (Entry entry : rows) {
			float fade = fadeOf(entry, now) * panelAlpha;
			int bits = alphaBits(fade);

			String time = relative(now - entry.time());
			int timeW = tr.getWidth(time);

			ctx.drawText(tr, Text.literal(entry.kind().glyph), x + 4, rowY,
					bits | (entry.kind().color & 0xFFFFFF), true);

			int bodyW = w - 15 - timeW - 8;
			String body = fit(tr, entry.plain(), Math.max(10, bodyW));
			ctx.drawText(tr, Text.literal(body), x + 14, rowY, bits | 0xF0E8DC, true);

			ctx.drawText(tr, Text.literal(time), x + w - 4 - timeW, rowY, bits | 0x8C8C8C, true);
			rowY += ROW_H;
		}
	}

	/** 1.0 while fresh, easing to nearly nothing as the entry reaches its TTL. */
	private static float fadeOf(Entry entry, long now) {
		long ttl = hudTtlMs();
		long age = now - entry.time();
		long hold = (long) (ttl * FADE_HOLD);
		if (age <= hold) return 1.0f;
		float left = (ttl - age) / (float) Math.max(1, ttl - hold);
		return Math.max(0.05f, Math.min(1.0f, left));
	}

	private static int alphaBits(float alpha) {
		int a = (int) (255 * Math.max(0f, Math.min(1f, alpha)));
		return Math.max(12, a) << 24;
	}

	// ---------- panel style (mirrors ForgeOreCalculatorClient's private helpers) ----------

	private static void drawPanel(DrawContext ctx, int x, int y, int w, int h, float alpha) {
		ForgeCalcTheme theme = ForgeCalcTheme.current();
		ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, applyAlpha(theme.panelBorder, alpha));
		ctx.fill(x, y, x + w, y + h, applyAlpha(theme.panelBg, alpha));
	}

	private static void drawHeader(MinecraftClient client, DrawContext ctx, int x, int y, String title) {
		ForgeCalcTheme theme = ForgeCalcTheme.current();
		ctx.drawText(client.textRenderer, TextFx.gradient(title, theme.gradFrom, theme.gradTo), x, y, 0xFFFFFFFF, true);
	}

	private static int applyAlpha(int color, float mult) {
		int alpha = (int) (((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, mult)));
		return (alpha << 24) | (color & 0xFFFFFF);
	}

	// ---------- text helpers ----------

	private static final Pattern COLOR_CODE = Pattern.compile("(?i)§[0-9A-FK-OR]");

	public static String strip(String raw) {
		if (raw == null) return "";
		return COLOR_CODE.matcher(raw).replaceAll("").trim();
	}

	/** Trims a string with an ellipsis until it fits {@code maxWidth} pixels. */
	public static String fit(TextRenderer tr, String text, int maxWidth) {
		if (text == null) return "";
		if (tr == null || tr.getWidth(text) <= maxWidth) return text;
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			if (tr.getWidth(out.toString() + text.charAt(i) + "…") > maxWidth) break;
			out.append(text.charAt(i));
		}
		return out + "…";
	}

	/** "12s ago", "4m ago", "2h ago", "3d ago" - used by the HUD. */
	public static String relative(long ageMs) {
		long seconds = Math.max(0, ageMs) / 1000;
		if (seconds < 1) return "now";
		if (seconds < 60) return seconds + "s ago";
		long minutes = seconds / 60;
		if (minutes < 60) return minutes + "m ago";
		long hours = minutes / 60;
		if (hours < 24) return hours + "h ago";
		return (hours / 24) + "d ago";
	}

	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

	/** Wall-clock stamp - used by the full screen. */
	public static String clock(long epochMs) {
		try {
			return CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()));
		} catch (Exception e) {
			return "--:--:--";
		}
	}

	// ---------- optional chat hook ----------

	private static final Pattern MULTIPLIER = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*x");

	/**
	 * Picks up the two events that have no code path to hang a push() on: a global boost
	 * starting, and a dominion being claimed by anyone. Everything else is pushed
	 * explicitly from the place it happens, which is more reliable than reading chat.
	 *
	 * <p>Feed it every chat line; it ignores anything it does not recognise.
	 */
	public static void onChat(String raw) {
		if (raw == null || raw.isEmpty()) return;
		String text;
		try {
			text = SortManager.normalizeText(raw);
		} catch (Throwable t) {
			text = raw;
		}
		String lower = strip(text).toLowerCase(Locale.ROOT);
		if (lower.isEmpty()) return;

		boolean negated = lower.contains("no current") || lower.contains("no active")
				|| lower.contains("ended") || lower.contains("expired") || lower.contains("ran out");

		if (lower.contains("boost") && !negated) {
			Matcher m = MULTIPLIER.matcher(lower);
			if (m.find()) {
				try {
					if (Double.parseDouble(m.group(1)) >= 1.5) {
						push(Kind.BOOST, strip(text));
						return;
					}
				} catch (NumberFormatException ignored) {
				}
			}
		}

		if (lower.contains("dominion")
				&& (lower.contains("claim") || lower.contains("unlocked") || lower.contains("dominioned"))) {
			push(Kind.DOMINION, strip(text));
		}
	}
}
