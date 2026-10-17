package com.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything the player stats viewer needs that is not a Screen: the parsed shape of the
 * afkable.com payload, the idle-game number formatter, the compare metric table, and the
 * small reusable draw helpers the two stats screens share.
 *
 * <p>Nothing in here touches the network and nothing here blocks; {@link PlayerStatsNet}
 * does the fetching on a daemon thread and hands the raw JSON to {@link #parse(JsonObject)}.
 */
public final class StatsModels {

	private StatsModels() {
	}

	// =================================================================================
	// text cleaning
	// =================================================================================

	/** "paragraph-x" followed by six "paragraph-hexdigit" pairs - the server's hex colour form. */
	private static final Pattern HEX_CODE = Pattern.compile("§x(§[0-9a-fA-F]){6}");
	private static final Pattern LEGACY_CODE = Pattern.compile("[§&][0-9a-fk-orA-FK-OR]");
	private static final Pattern TAG_HEX = Pattern.compile("<#[0-9a-fA-F]{6}>");
	private static final Pattern LEADING_NUMBER = Pattern.compile("^[-]?\\d*\\.?\\d+");
	private static final Pattern ANY_NUMBER = Pattern.compile("[-]?\\d*\\.?\\d+");
	private static final Pattern PLAIN_NUMBER = Pattern.compile("^[-]?\\d*\\.?\\d+$");

	/**
	 * SortManager.normalizeText is the repo's small-caps converter, but it also folds ASCII
	 * 'Q' to 'q' (it is tuned for ore names). That would eat the quadrillion suffix and mangle
	 * display names, so shield the ASCII Q across the round trip.
	 */
	private static final char Q_SHIELD = 0x01;

	private static String smallCaps(String in) {
		try {
			return SortManager.normalizeText(in.replace('Q', Q_SHIELD)).replace(Q_SHIELD, 'Q');
		} catch (Throwable t) {
			return in;
		}
	}

	/** Strips hex colour codes, legacy colour codes and &lt;#RRGGBB&gt; tags, then un-small-caps. */
	public static String clean(String in) {
		if (in == null) return "";
		String s = HEX_CODE.matcher(in).replaceAll("");
		s = TAG_HEX.matcher(s).replaceAll("");
		s = LEGACY_CODE.matcher(s).replaceAll("");
		return smallCaps(s).trim();
	}

	/** Same as {@link #clean} but keeps leading/trailing spacing so lore blocks keep their shape. */
	public static String cleanLore(String in) {
		if (in == null) return "";
		String s = HEX_CODE.matcher(in).replaceAll("");
		s = TAG_HEX.matcher(s).replaceAll("");
		s = LEGACY_CODE.matcher(s).replaceAll("");
		return smallCaps(s);
	}

	/** First embedded colour in a string as ARGB, or 0 when the string carries no colour. */
	public static int hexOf(String in) {
		if (in == null) return 0;
		Matcher hex = HEX_CODE.matcher(in);
		if (hex.find()) {
			String g = hex.group();
			StringBuilder digits = new StringBuilder(6);
			for (int i = 3; i < g.length(); i += 2) digits.append(g.charAt(i));
			try {
				return 0xFF000000 | Integer.parseInt(digits.toString(), 16);
			} catch (NumberFormatException ignored) {
			}
		}
		Matcher tag = TAG_HEX.matcher(in);
		if (tag.find()) {
			try {
				return 0xFF000000 | Integer.parseInt(tag.group().substring(2, 8), 16);
			} catch (NumberFormatException ignored) {
			}
		}
		return 0;
	}

	/** "#DDFF55" -> 0xFFDDFF55. Returns {@code fallback} when the string is not a hex colour. */
	public static int parseHtmlColor(String in, int fallback) {
		if (in == null) return fallback;
		String s = in.trim();
		if (s.startsWith("#")) s = s.substring(1);
		if (s.length() != 6) return fallback;
		try {
			return 0xFF000000 | Integer.parseInt(s, 16);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	// =================================================================================
	// colour-preserving text
	// =================================================================================
	//
	// clean()/stripCodes() above throw the colours away - they exist for measuring, sorting and
	// number parsing and every one of their callers still wants that. Everything below is the
	// other half: it keeps the colours and hands back a real Text so item names, lore, pet names
	// and farm mob names draw exactly as the server wrote them.
	//
	// Three encodings turn up in the payload, sometimes inside the same string:
	//   legacy   "paragraph" or "&" + one of 0-9 a-f k-o r
	//   hex      "paragraph-x" followed by six sigil+hex-digit pairs (#FFB8A1 -> x f f b 8 a 1)
	//   tag hex  <#FFB8A1>
	// The small-caps unicode the server uses for display fonts is still folded back to ASCII on
	// the way out, so a coloured name reads the same as its clean() form, only in colour.

	/** Vanilla's sixteen legacy colours, indexed by the digit after the sigil. */
	private static final int[] LEGACY_RGB = {
			0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
			0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
	};
	private static final String LEGACY_DIGITS = "0123456789abcdef";

	/** {@link ColorRun#rgb()} value meaning "no colour of its own - use the caller's". */
	public static final int INHERIT = -1;

	/** One stretch of identically styled characters carved out of a colour-coded server string. */
	public record ColorRun(String text, int rgb, boolean bold, boolean italic,
	                       boolean underlined, boolean strikethrough, boolean obfuscated) {

		/** Builds the Style for this run, falling back to {@code fallbackRgb} when it has no colour. */
		public Style style(int fallbackRgb) {
			Style s = Style.EMPTY;
			int colour = rgb != INHERIT ? rgb : fallbackRgb;
			if (colour != INHERIT) s = s.withColor(TextColor.fromRgb(colour & 0xFFFFFF));
			if (bold) s = s.withBold(true);
			if (italic) s = s.withItalic(true);
			if (underlined) s = s.withUnderline(true);
			if (strikethrough) s = s.withStrikethrough(true);
			if (obfuscated) s = s.withObfuscated(true);
			return s;
		}
	}

	/** True when {@code at} starts six "sigil + hex digit" pairs, i.e. the tail of a hex colour. */
	private static boolean hexPairsAt(String s, int at) {
		if (at + 12 > s.length()) return false;
		for (int i = 0; i < 6; i++) {
			char sigil = s.charAt(at + i * 2);
			if (sigil != '§' && sigil != '&') return false;
			if (Character.digit(s.charAt(at + i * 2 + 1), 16) < 0) return false;
		}
		return true;
	}

	private static boolean hex6At(String s, int at) {
		if (at + 6 > s.length()) return false;
		for (int i = 0; i < 6; i++) if (Character.digit(s.charAt(at + i), 16) < 0) return false;
		return true;
	}

	private static void flushRun(List<ColorRun> out, StringBuilder buf, int rgb, boolean bold,
	                             boolean italic, boolean underlined, boolean strikethrough,
	                             boolean obfuscated) {
		if (buf.length() == 0) return;
		out.add(new ColorRun(smallCaps(buf.toString()), rgb, bold, italic, underlined,
				strikethrough, obfuscated));
		buf.setLength(0);
	}

	/**
	 * Splits a server string into styled runs. Colour codes reset the format flags exactly like
	 * vanilla does; a {@code <#RRGGBB>} tag only changes the colour. A stray sigil that is not
	 * followed by a real code is kept as an ordinary character rather than silently eaten.
	 */
	public static List<ColorRun> runs(String in) {
		List<ColorRun> out = new ArrayList<>();
		if (in == null || in.isEmpty()) return out;
		StringBuilder buf = new StringBuilder(in.length());
		int rgb = INHERIT;
		boolean bold = false;
		boolean italic = false;
		boolean underlined = false;
		boolean strikethrough = false;
		boolean obfuscated = false;

		int i = 0;
		while (i < in.length()) {
			char c = in.charAt(i);
			if ((c == '§' || c == '&') && i + 1 < in.length()) {
				char code = Character.toLowerCase(in.charAt(i + 1));
				if (code == 'x' && hexPairsAt(in, i + 2)) {
					StringBuilder digits = new StringBuilder(6);
					for (int p = 0; p < 6; p++) digits.append(in.charAt(i + 3 + p * 2));
					flushRun(out, buf, rgb, bold, italic, underlined, strikethrough, obfuscated);
					rgb = Integer.parseInt(digits.toString(), 16);
					bold = italic = underlined = strikethrough = obfuscated = false;
					i += 14;
					continue;
				}
				int digit = LEGACY_DIGITS.indexOf(code);
				if (digit >= 0) {
					flushRun(out, buf, rgb, bold, italic, underlined, strikethrough, obfuscated);
					rgb = LEGACY_RGB[digit];
					bold = italic = underlined = strikethrough = obfuscated = false;
					i += 2;
					continue;
				}
				if (code == 'k' || code == 'l' || code == 'm' || code == 'n' || code == 'o' || code == 'r') {
					flushRun(out, buf, rgb, bold, italic, underlined, strikethrough, obfuscated);
					switch (code) {
						case 'k' -> obfuscated = true;
						case 'l' -> bold = true;
						case 'm' -> strikethrough = true;
						case 'n' -> underlined = true;
						case 'o' -> italic = true;
						default -> {
							rgb = INHERIT;
							bold = italic = underlined = strikethrough = obfuscated = false;
						}
					}
					i += 2;
					continue;
				}
				// not a code - "&" is a normal character in prose, so keep it
			} else if (c == '<' && i + 8 < in.length() && in.charAt(i + 1) == '#'
					&& in.charAt(i + 8) == '>' && hex6At(in, i + 2)) {
				flushRun(out, buf, rgb, bold, italic, underlined, strikethrough, obfuscated);
				rgb = Integer.parseInt(in.substring(i + 2, i + 8), 16);
				i += 9;
				continue;
			}
			buf.append(c);
			i++;
		}
		flushRun(out, buf, rgb, bold, italic, underlined, strikethrough, obfuscated);
		return out;
	}

	// Parsing happens per frame for every visible name and lore line, so both results are memoised.
	// Only the render thread ever touches these, and both are hard-capped so a pathological payload
	// cannot grow them without bound.
	private static final Map<String, Text> RICH_CACHE = new HashMap<>();
	private static final Map<String, Text> RICH_FIT_CACHE = new HashMap<>();
	private static final int RICH_CACHE_MAX = 2048;

	/** The server's string as a real coloured Text. Uncoloured runs inherit the draw colour. */
	public static Text rich(String in) {
		return rich(in, INHERIT);
	}

	/** As {@link #rich(String)} but uncoloured runs take {@code fallbackRgb} (0xRRGGBB). */
	public static Text rich(String in, int fallbackRgb) {
		if (in == null || in.isEmpty()) return Text.empty();
		String key = fallbackRgb + " " + in;
		Text cached = RICH_CACHE.get(key);
		if (cached != null) return cached;
		MutableText built = Text.empty();
		for (ColorRun run : runs(in)) {
			built.append(Text.literal(run.text()).setStyle(run.style(fallbackRgb)));
		}
		if (RICH_CACHE.size() < RICH_CACHE_MAX) RICH_CACHE.put(key, built);
		return built;
	}

	/** A plain string in one explicit colour - the counterpart to rich() for text we author. */
	public static MutableText colored(String text, int argb) {
		return colored(text, argb, false);
	}

	public static MutableText colored(String text, int argb, boolean bold) {
		Style style = Style.EMPTY.withColor(TextColor.fromRgb(argb & 0xFFFFFF));
		if (bold) style = style.withBold(true);
		return Text.literal(text == null ? "" : text).setStyle(style);
	}

	/** Rendered width of a coloured string, bold runs included. */
	public static int richWidth(TextRenderer tr, String in) {
		return tr.getWidth(rich(in));
	}

	/** Trims a coloured string to {@code maxWidth} pixels, keeping every run's colour. */
	public static Text richFit(TextRenderer tr, String in, int maxWidth) {
		if (in == null || in.isEmpty() || maxWidth <= 0) return Text.empty();
		String key = maxWidth + " " + in;
		Text cached = RICH_FIT_CACHE.get(key);
		if (cached != null) return cached;

		Text full = rich(in);
		Text result = full;
		if (tr.getWidth(full) > maxWidth) {
			int budget = Math.max(0, maxWidth - tr.getWidth(".."));
			MutableText built = Text.empty();
			int used = 0;
			boolean full_ = false;
			for (ColorRun run : runs(in)) {
				if (full_) break;
				Style style = run.style(INHERIT);
				StringBuilder kept = new StringBuilder();
				for (int i = 0; i < run.text().length(); i++) {
					char ch = run.text().charAt(i);
					int w = tr.getWidth(String.valueOf(ch)) + (run.bold() ? 1 : 0);
					if (used + w > budget) {
						full_ = true;
						break;
					}
					kept.append(ch);
					used += w;
				}
				if (kept.length() > 0) built.append(Text.literal(kept.toString()).setStyle(style));
			}
			built.append(Text.literal(".."));
			result = built;
		}
		if (RICH_FIT_CACHE.size() < RICH_CACHE_MAX) RICH_FIT_CACHE.put(key, result);
		return result;
	}

	public static void drawRich(DrawContext ctx, TextRenderer tr, String in, int x, int y) {
		ctx.drawText(tr, rich(in), x, y, 0xFFFFFFFF, true);
	}

	public static void drawRich(DrawContext ctx, TextRenderer tr, String in, int x, int y, int fallbackArgb) {
		ctx.drawText(tr, rich(in), x, y, fallbackArgb, true);
	}

	public static void drawRichFit(DrawContext ctx, TextRenderer tr, String in, int x, int y, int maxWidth) {
		ctx.drawText(tr, richFit(tr, in, maxWidth), x, y, 0xFFFFFFFF, true);
	}

	public static void drawRichRight(DrawContext ctx, TextRenderer tr, String in, int right, int y) {
		Text text = rich(in);
		ctx.drawText(tr, text, right - tr.getWidth(text), y, 0xFFFFFFFF, true);
	}

	public static void drawRichCenter(DrawContext ctx, TextRenderer tr, String in, int cx, int y) {
		ctx.drawCenteredTextWithShadow(tr, rich(in), cx, y, 0xFFFFFFFF);
	}

	public static void drawText(DrawContext ctx, TextRenderer tr, Text text, int x, int y) {
		ctx.drawText(tr, text, x, y, 0xFFFFFFFF, true);
	}

	public static void drawTextCenter(DrawContext ctx, TextRenderer tr, Text text, int cx, int y) {
		ctx.drawCenteredTextWithShadow(tr, text, cx, y, 0xFFFFFFFF);
	}

	public static void drawTextRight(DrawContext ctx, TextRenderer tr, Text text, int right, int y) {
		ctx.drawText(tr, text, right - tr.getWidth(text), y, 0xFFFFFFFF, true);
	}

	// =================================================================================
	// the idle-game suffix ladder
	// =================================================================================

	private static final LinkedHashMap<String, Integer> LADDER = new LinkedHashMap<>();
	private static final Map<Integer, String> LADDER_REVERSE = new HashMap<>();
	private static final Map<String, Integer> LADDER_LOWER = new HashMap<>();

	static {
		// Standard short-scale ladder. The "*Dc" entries are SINGLE tokens (undecillion
		// through novemdecillion), not a product - reading "QiDc" as Qi x Dc overshoots by
		// nine orders of magnitude, and leaving UDc/DDc/TDc out entirely made them fall
		// through to the unknown-token break so "5.2UDc" parsed as 5.2 and lost every
		// comparison. Longest tokens first: the matcher tries 4, then 3, then 2, then 1.
		LADDER.put("NoDc", 60);
		LADDER.put("OcDc", 57);
		LADDER.put("SpDc", 54);
		LADDER.put("SxDc", 51);
		LADDER.put("QiDc", 48);
		LADDER.put("QaDc", 45);
		LADDER.put("TDc", 42);
		LADDER.put("DDc", 39);
		LADDER.put("UDc", 36);
		LADDER.put("Vg", 63);
		LADDER.put("Dc", 33);
		LADDER.put("No", 30);
		LADDER.put("Oc", 27);
		LADDER.put("Sp", 24);
		LADDER.put("Sx", 21);
		LADDER.put("Qi", 18);
		LADDER.put("Qa", 15);
		LADDER.put("k", 3);
		LADDER.put("M", 6);
		LADDER.put("B", 9);
		LADDER.put("T", 12);
		LADDER.put("Q", 15);
		// reverse map is for formatting: prefer the canonical token per magnitude
		LADDER_REVERSE.put(3, "k");
		LADDER_REVERSE.put(6, "M");
		LADDER_REVERSE.put(9, "B");
		LADDER_REVERSE.put(12, "T");
		LADDER_REVERSE.put(15, "Qa");
		LADDER_REVERSE.put(18, "Qi");
		LADDER_REVERSE.put(21, "Sx");
		LADDER_REVERSE.put(24, "Sp");
		LADDER_REVERSE.put(27, "Oc");
		LADDER_REVERSE.put(30, "No");
		LADDER_REVERSE.put(33, "Dc");
		LADDER_REVERSE.put(36, "UDc");
		LADDER_REVERSE.put(39, "DDc");
		LADDER_REVERSE.put(42, "TDc");
		LADDER_REVERSE.put(45, "QaDc");
		LADDER_REVERSE.put(48, "QiDc");
		LADDER_REVERSE.put(51, "SxDc");
		LADDER_REVERSE.put(54, "SpDc");
		LADDER_REVERSE.put(57, "OcDc");
		LADDER_REVERSE.put(60, "NoDc");
		LADDER_REVERSE.put(63, "Vg");
		// forgiving single-letter fallbacks for casing the API sometimes flips
		LADDER_LOWER.put("k", 3);
		LADDER_LOWER.put("m", 6);
		LADDER_LOWER.put("b", 9);
		LADDER_LOWER.put("t", 12);
	}

	/**
	 * Parses "1.04Sp", "$63.33QiDc", "200,000,000x", "551.18Qix" or a plain number.
	 *
	 * <p>An unrecognised suffix does <b>not</b> poison the result: whatever ladder tokens were
	 * consumed still apply and the leading number is always honoured, so ordering stays sane.
	 * {@link Double#NaN} comes back only when the string holds no number at all.
	 */
	public static double parseValue(String in) {
		if (in == null) return Double.NaN;
		String s = clean(in);
		if (s.isEmpty()) return Double.NaN;
		if (s.equalsIgnoreCase("true")) return 1;
		if (s.equalsIgnoreCase("false")) return 0;
		StringBuilder stripped = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == ',' || c == '$' || c == '%' || c == '+' || c == '\'' || Character.isWhitespace(c)) continue;
			stripped.append(c);
		}
		s = stripped.toString();
		if (s.isEmpty()) return Double.NaN;

		Matcher m = LEADING_NUMBER.matcher(s);
		if (!m.find()) {
			// leading junk such as a stray "x" prefix - fall back to the first number in the string
			m = ANY_NUMBER.matcher(s);
			if (!m.find()) return Double.NaN;
		}
		double mantissa;
		try {
			mantissa = Double.parseDouble(m.group());
		} catch (NumberFormatException e) {
			return Double.NaN;
		}

		String suffix = s.substring(m.end());
		int exp = 0;
		int i = 0;
		while (i < suffix.length()) {
			char c = suffix.charAt(i);
			if (c == 'x' || c == 'X' || c == '/' || c == '*') {
				i++;
				continue;
			}
			// longest token first: "QiDc" must never be read as "Qi" then "Dc"
			boolean matched = false;
			for (int len = 4; len >= 2; len--) {
				if (i + len > suffix.length()) continue;
				Integer token = LADDER.get(suffix.substring(i, i + len));
				if (token != null) {
					exp += token;
					i += len;
					matched = true;
					break;
				}
			}
			if (matched) continue;
			String one = suffix.substring(i, i + 1);
			Integer single = LADDER.get(one);
			if (single == null) single = LADDER_LOWER.get(one.toLowerCase(Locale.ROOT));
			if (single != null) {
				exp += single;
				i++;
				continue;
			}
			break; // unknown token - keep what we understood rather than throwing the row away
		}
		return mantissa * Math.pow(10, exp);
	}

	/** Formats with the same ladder: 11424.4694 -> "11.42k", 1.04e57 -> "1.04QiDc". */
	public static String compact(double v) {
		if (Double.isNaN(v)) return "-";
		if (Double.isInfinite(v)) return v > 0 ? "inf" : "-inf";
		double abs = Math.abs(v);
		if (abs < 1000) {
			if (abs == Math.rint(abs) && abs < 1e15) return String.valueOf((long) v);
			return trimZeros(String.format(Locale.ROOT, "%.2f", v));
		}
		int e = (int) Math.floor(Math.log10(abs));
		int step = (e / 3) * 3;
		if (step > 72) step = 72;
		double mantissa = v / Math.pow(10, step);
		return trimZeros(String.format(Locale.ROOT, "%.2f", mantissa)) + suffixFor(step);
	}

	private static String suffixFor(int step) {
		if (step <= 0) return "";
		String single = LADDER_REVERSE.get(step);
		if (single != null) return single;
		// past Vg there is no agreed token, so show plain scientific notation
		return "e" + step;
	}

	private static String trimZeros(String s) {
		if (s.indexOf('.') < 0) return s;
		s = s.replaceAll("0+$", "");
		if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
		return s;
	}

	/** "22d3h37m", "17h 6m", "9d 15h 21m" -> total minutes. NaN when nothing parses. */
	public static double parseDuration(String in) {
		if (in == null) return Double.NaN;
		String s = clean(in).toLowerCase(Locale.ROOT);
		double minutes = 0;
		boolean any = false;
		Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([a-z]+)").matcher(s);
		while (m.find()) {
			double n;
			try {
				n = Double.parseDouble(m.group(1));
			} catch (NumberFormatException e) {
				continue;
			}
			switch (m.group(2)) {
				case "y" -> {
					minutes += n * 525600;
					any = true;
				}
				case "mo" -> {
					minutes += n * 43200;
					any = true;
				}
				case "w" -> {
					minutes += n * 10080;
					any = true;
				}
				case "d" -> {
					minutes += n * 1440;
					any = true;
				}
				case "h" -> {
					minutes += n * 60;
					any = true;
				}
				case "m", "min" -> {
					minutes += n;
					any = true;
				}
				case "s" -> {
					minutes += n / 60.0;
					any = true;
				}
				default -> {
				}
			}
		}
		return any ? minutes : Double.NaN;
	}

	/** Renders a minute count back as "22d 3h" for the compare rows. */
	public static String formatDuration(double minutes) {
		if (Double.isNaN(minutes)) return "-";
		long total = (long) minutes;
		long d = total / 1440;
		long h = (total % 1440) / 60;
		long m = total % 60;
		if (d > 0) return d + "d " + h + "h";
		if (h > 0) return h + "h " + m + "m";
		return m + "m";
	}

	public static double pct(double a, double b) {
		if (b == 0 || Double.isNaN(a) || Double.isNaN(b)) return 0;
		return Math.max(0, Math.min(1, a / b));
	}

	/** "2026-08-06T11:36:37.268+00:00" -> "3m ago". Empty string when it will not parse. */
	public static String ago(String iso) {
		if (iso == null || iso.isEmpty()) return "";
		try {
			Instant then = OffsetDateTime.parse(iso).toInstant();
			long secs = Duration.between(then, Instant.now()).getSeconds();
			if (secs < 5) return "just now";
			if (secs < 60) return secs + "s ago";
			long mins = secs / 60;
			if (mins < 60) return mins + "m ago";
			long hours = mins / 60;
			if (hours < 48) return hours + "h ago";
			return (hours / 24) + "d ago";
		} catch (Exception e) {
			return "";
		}
	}

	// =================================================================================
	// rarity + item stacks
	// =================================================================================

	private static final Map<String, Integer> RARITY = new HashMap<>();
	private static final List<String> RARITY_ORDER = List.of(
			"COMMON", "UNCOMMON", "RARE", "UNIQUE", "SPECIAL", "EPIC",
			"LEGENDARY", "MYTHIC", "COSMIC", "DIVINE", "SECRET");

	static {
		RARITY.put("COMMON", 0xFFAAAAAA);
		RARITY.put("UNCOMMON", 0xFF55FF55);
		RARITY.put("UNIQUE", 0xFFFFB8A1);
		RARITY.put("SPECIAL", 0xFF55FFFF);
		RARITY.put("COSMIC", 0xFFD1A1FF);
		RARITY.put("RARE", 0xFF5599FF);
		RARITY.put("EPIC", 0xFFAA55FF);
		RARITY.put("LEGENDARY", 0xFFFFAA00);
		RARITY.put("MYTHIC", 0xFFFF55FF);
		RARITY.put("SECRET", 0xFFFF5555);
		RARITY.put("DIVINE", 0xFFFFFF55);
	}

	public static int rarityColor(String rarity) {
		if (rarity == null) return 0xFFAAAAAA;
		return RARITY.getOrDefault(rarity.trim().toUpperCase(Locale.ROOT), 0xFFAAAAAA);
	}

	/** Sort weight so the pets list can order by rarity. Unknown rarities sink to the bottom. */
	public static int rarityRank(String rarity) {
		if (rarity == null) return -1;
		return RARITY_ORDER.indexOf(rarity.trim().toUpperCase(Locale.ROOT));
	}

	private static final Map<String, ItemStack> STACK_CACHE = new HashMap<>();

	/**
	 * Builds (and caches) a real ItemStack from an item id string. Unknown ids, blank ids and
	 * ids that resolve to air all come back as a barrier so the slot still draws something.
	 */
	public static ItemStack stackFor(String type) {
		String key = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
		ItemStack cached = STACK_CACHE.get(key);
		if (cached != null) return cached;
		ItemStack built = new ItemStack(Items.BARRIER);
		if (!key.isEmpty()) {
			try {
				String path = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
				String namespace = key.contains(":") ? key.substring(0, key.indexOf(':')) : "minecraft";
				Optional<Item> item = Registries.ITEM.getOptionalValue(Identifier.of(namespace, path));
				if (item.isPresent()) {
					ItemStack stack = new ItemStack(item.get());
					if (!stack.isEmpty()) built = stack;
				}
			} catch (Throwable ignored) {
				// invalid identifier characters - fall through to the barrier
			}
		}
		STACK_CACHE.put(key, built);
		return built;
	}

	// =================================================================================
	// records
	// =================================================================================

	/** One "Balance: 1.04Sp" line inside a section. */
	public record Stat(String key, String label, String value) {
	}

	public record ItemInfo(String name, String type, int amount, List<String> lore, boolean enchanted) {
		public String display() {
			return clean(name);
		}

		/** Scrapes " Multiplier: 268.67x (Money)" out of the lore block. NaN when absent. */
		public double loreMultiplier() {
			for (String raw : lore) {
				String line = clean(raw);
				int at = line.toLowerCase(Locale.ROOT).indexOf("multiplier:");
				if (at < 0) continue;
				double v = parseValue(line.substring(at + "multiplier:".length()));
				if (!Double.isNaN(v)) return v;
			}
			return Double.NaN;
		}

		public String rarity() {
			String n = clean(name);
			int open = n.lastIndexOf('[');
			int close = n.lastIndexOf(']');
			if (open >= 0 && close > open) return n.substring(open + 1, close).toUpperCase(Locale.ROOT);
			for (String raw : lore) {
				String line = clean(raw);
				int at = line.toLowerCase(Locale.ROOT).indexOf("rarity:");
				if (at >= 0) return line.substring(at + "rarity:".length()).trim().toUpperCase(Locale.ROOT);
			}
			return "COMMON";
		}

		public int color() {
			int hex = hexOf(name);
			if (hex != 0) return hex;
			return rarityColor(rarity());
		}
	}

	/**
	 * {@code display} is the ASCII form used for sorting and width maths; {@code displayRaw} is the
	 * untouched string the server sent, kept so the row can be drawn in its real colours.
	 */
	public record PetInfo(String key, String display, String displayRaw, int tier, String rarity,
	                      double multiplier, String multiplierRaw, int count, boolean equipped) {
		public int color() {
			return rarityColor(rarity);
		}

		/** The pet's name in its own colours, falling back to the rarity colour when it has none. */
		public Text nameText() {
			return rich(displayRaw == null || displayRaw.isBlank() ? display : displayRaw, color());
		}
	}

	/** {@code texture} is the mob head skin URL; we never fetch it - see {@link #stack()}. */
	public record FarmDef(String key, String name, String size, int tier, String rarity,
	                      double produce, String item, String texture, List<String> lore) {
		public int color() {
			int hex = hexOf(name);
			return hex != 0 ? hex : rarityColor(rarity);
		}

		/**
		 * The item id to draw for this mob. Downloading and binding {@link #texture()} from the
		 * render thread is not something we can do cheaply or without blocking, so the plot draws
		 * the def's own item instead - a real player head for every mob the API has shipped so far.
		 */
		public String itemId() {
			return item == null || item.isBlank() ? "player_head" : item;
		}

		public ItemStack stack() {
			return stackFor(itemId());
		}

		public Text nameText() {
			return rich(name == null || name.isBlank() ? key : name, color());
		}
	}

	/** cells[i] is null for an empty plot square; i = y * width + x. */
	public record FarmPlot(int width, int height, FarmDef[] cells, Map<String, FarmDef> defs,
	                       int placed, double totalProduce, Map<String, Integer> rarityCounts,
	                       List<FarmDef> top) {
		public int size() {
			return width * height;
		}
	}

	public record Garden(int level, int maxLevel, int plots, int maxPlots, int ready, int planted,
	                     double xp, double xpProgress, List<String> crops) {
	}

	public record Cookie(int tier, String label, String color, double rolls, double chance,
	                     double multiplier, String multiplierLabel) {
	}

	/** Row kinds for the PROG tab. */
	public static final int ROW_HEADER = 0;
	public static final int ROW_VALUE = 1;
	public static final int ROW_BAR = 2;

	public record ProgRow(int kind, String label, String value, double fraction) {
		public int height() {
			return switch (kind) {
				case ROW_HEADER -> 13;
				case ROW_BAR -> 16;
				default -> 11;
			};
		}
	}

	public record ServerProfile(String id, String label, String shortLabel, String rank, boolean online,
	                            String updatedAt, Map<String, Boolean> visibility, JsonObject raw,
	                            ItemInfo[] armor, ItemInfo offhand, ItemInfo[] hotbar, ItemInfo[] inventory,
	                            List<PetInfo> pets, int petLimit, int petsEquipped,
	                            FarmPlot farm, Garden garden, Cookie cookie, List<String> gamepasses,
	                            LinkedHashMap<String, List<Stat>> sections, List<ProgRow> progRows) {

		/** False only when the owner explicitly hid the section (an absent flag means visible). */
		public boolean visible(String key) {
			Boolean b = visibility.get(key);
			return b == null || b;
		}

		/** True when the server actually sent the section and the owner has not hidden it. */
		public boolean has(String key) {
			if (!visible(key)) return false;
			JsonElement e = raw.get(key);
			return e != null && !e.isJsonNull();
		}

		public boolean hasGear() {
			return has("armor") || has("hotbar") || has("inventory") || has("offhand");
		}

		public int armorPieces() {
			int n = 0;
			for (ItemInfo piece : armor) if (piece != null) n++;
			return n;
		}

		public double armorLoreTotal() {
			double sum = 0;
			for (ItemInfo piece : armor) {
				if (piece == null) continue;
				double v = piece.loreMultiplier();
				if (!Double.isNaN(v)) sum += v;
			}
			if (offhand != null) {
				double v = offhand.loreMultiplier();
				if (!Double.isNaN(v)) sum += v;
			}
			return sum;
		}

		public int filledInventory() {
			int n = 0;
			for (ItemInfo slot : inventory) if (slot != null) n++;
			return n;
		}

		/** Raw string for a "section.key" pair, or "" when either half is missing. */
		public String stat(String section, String key) {
			JsonElement e = raw.get(section);
			if (e == null || !e.isJsonObject()) return "";
			JsonElement v = e.getAsJsonObject().get(key);
			if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) return "";
			return v.getAsString();
		}

		public double bestPetMultiplier() {
			double best = 0;
			for (PetInfo p : pets) if (!Double.isNaN(p.multiplier())) best = Math.max(best, p.multiplier());
			return best;
		}

		public double summedPetMultiplier() {
			double sum = 0;
			for (PetInfo p : pets) if (!Double.isNaN(p.multiplier())) sum += p.multiplier();
			return sum;
		}
	}

	public record PlayerProfile(String name, String uuid, List<ServerProfile> servers) {
		public ServerProfile server(String id) {
			for (ServerProfile s : servers) if (s.id().equalsIgnoreCase(id)) return s;
			return null;
		}
	}

	// =================================================================================
	// parsing
	// =================================================================================

	public static String serverLabel(String id) {
		if (id == null) return "?";
		return switch (id.toLowerCase(Locale.ROOT)) {
			case "tycoon" -> "SEASON OG 2";
			case "tycoon-s1" -> "SEASON OG 1";
			case "factory" -> "FACTORY";
			default -> id.toUpperCase(Locale.ROOT);
		};
	}

	public static String serverShort(String id) {
		if (id == null) return "?";
		return switch (id.toLowerCase(Locale.ROOT)) {
			case "tycoon" -> "OG2";
			case "tycoon-s1" -> "OG1";
			case "factory" -> "FACTORY";
			default -> id.toUpperCase(Locale.ROOT);
		};
	}

	/**
	 * Turns the afkable.com payload into a {@link PlayerProfile}.
	 *
	 * @return null when the payload is missing, malformed or reports status != "ok".
	 */
	public static PlayerProfile parse(JsonObject root) {
		if (root == null) return null;
		if (!"ok".equalsIgnoreCase(str(root, "status"))) return null;

		List<ServerProfile> servers = new ArrayList<>();
		JsonElement serversEl = root.get("servers");
		if (serversEl != null && serversEl.isJsonArray()) {
			for (JsonElement element : serversEl.getAsJsonArray()) {
				if (element == null || !element.isJsonObject()) continue;
				ServerProfile parsed = parseServer(element.getAsJsonObject());
				if (parsed != null) servers.add(parsed);
			}
		}
		if (servers.isEmpty()) return null;
		return new PlayerProfile(str(root, "playerName"), str(root, "playerUuid"), servers);
	}

	private static ServerProfile parseServer(JsonObject server) {
		JsonElement statsEl = server.get("stats");
		JsonObject stats = statsEl != null && statsEl.isJsonObject() ? statsEl.getAsJsonObject() : new JsonObject();
		String id = str(server, "server");

		Map<String, Boolean> visibility = new HashMap<>();
		JsonElement visEl = stats.get("visibility");
		if (visEl != null && visEl.isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : visEl.getAsJsonObject().entrySet()) {
				JsonElement v = e.getValue();
				if (v != null && v.isJsonPrimitive()) {
					try {
						visibility.put(e.getKey(), v.getAsBoolean());
					} catch (Exception ignored) {
					}
				}
			}
		}

		ItemInfo[] armor = itemArray(stats.get("armor"), 4);
		ItemInfo offhand = itemInfo(stats.get("offhand"));
		ItemInfo[] hotbar = itemArray(stats.get("hotbar"), 9);
		ItemInfo[] inventory = itemArray(stats.get("inventory"), 27);

		List<PetInfo> pets = new ArrayList<>();
		int petLimit = 0;
		int petsEquipped = 0;
		JsonElement petsEl = stats.get("pets");
		if (petsEl != null && petsEl.isJsonObject()) {
			JsonObject petObj = petsEl.getAsJsonObject();
			petLimit = (int) num(petObj, "limit", 0);
			java.util.Set<String> equippedIds = new java.util.HashSet<>();
			List<PetInfo> equippedPets = parsePetList(str(petObj, "equipped"), true);
			for (PetInfo p : equippedPets) equippedIds.add(petIdentity(p));
			petsEquipped = equippedPets.size();

			List<PetInfo> owned = parsePetList(str(petObj, "list"), false);
			java.util.Set<String> seen = new java.util.HashSet<>();
			for (PetInfo p : owned) {
				boolean eq = equippedIds.contains(petIdentity(p));
				seen.add(petIdentity(p));
				pets.add(eq
						? new PetInfo(p.key(), p.display(), p.displayRaw(), p.tier(), p.rarity(),
						p.multiplier(), p.multiplierRaw(), p.count(), true)
						: p);
			}
			// an equipped pet the owned list somehow did not mention still deserves a row
			for (PetInfo p : equippedPets) {
				if (!seen.contains(petIdentity(p))) pets.add(p);
			}
		}

		FarmPlot farm = parseFarmPlot(stats.get("farmPlot"));
		Garden garden = parseGarden(stats.get("garden"));
		Cookie cookie = parseCookie(stats.get("cookie"));
		List<String> gamepasses = parseGamepasses(stats.get("gamepasses"));

		LinkedHashMap<String, List<Stat>> sections = new LinkedHashMap<>();
		addSection(sections, "ECONOMY", stats.get("economy"));
		addSection(sections, "MULTIPLIERS", stats.get("multipliers"));
		addSection(sections, "PROGRESSION", stats.get("progression"));
		addSection(sections, "TIME", stats.get("time"));
		addSection(sections, "HQ", stats.get("hq"));
		addSection(sections, "PUNISHMENTS", stats.get("punishments"));
		if (!gamepasses.isEmpty()) {
			List<Stat> passes = new ArrayList<>();
			for (int i = 0; i < gamepasses.size(); i++) {
				passes.add(new Stat("gamepass" + i, "#" + (i + 1), gamepasses.get(i)));
			}
			sections.put("GAMEPASSES", passes);
		}

		return new ServerProfile(id, serverLabel(id), serverShort(id), str(server, "rank"),
				bool(server, "online"), str(server, "updatedAt"), visibility, stats,
				armor, offhand, hotbar, inventory, pets, petLimit, petsEquipped,
				farm, garden, cookie, gamepasses, sections, buildProgRows(stats, sections));
	}

	private static String petIdentity(PetInfo p) {
		return p.key().toLowerCase(Locale.ROOT) + "#" + p.tier() + "#" + p.display().toLowerCase(Locale.ROOT);
	}

	/** "KEY|Display|tier|RARITY|multiplier|count|?|?" joined by "~~". */
	private static List<PetInfo> parsePetList(String raw, boolean equipped) {
		List<PetInfo> out = new ArrayList<>();
		if (raw == null || raw.isBlank()) return out;
		for (String entry : raw.split("~~")) {
			if (entry == null || entry.isBlank()) continue;
			String[] parts = entry.split("\\|", -1);
			String key = parts.length > 0 ? parts[0].trim() : "";
			String displayRaw = parts.length > 1 && !parts[1].isBlank() ? parts[1] : key;
			String display = clean(displayRaw);
			int tier = parts.length > 2 ? (int) safeInt(parts[2], 1) : 1;
			String rarity = parts.length > 3 ? parts[3].trim().toUpperCase(Locale.ROOT) : "COMMON";
			String multiRaw = parts.length > 4 ? parts[4].trim() : "";
			double multi = parseValue(multiRaw);
			int count = parts.length > 5 ? (int) safeInt(parts[5], 1) : 1;
			out.add(new PetInfo(key, display, displayRaw, tier, rarity, multi, multiRaw,
					Math.max(1, count), equipped));
		}
		return out;
	}

	/** The pet "items"/"textures" maps are keyed by the base name, so drop any "#N" variant tag. */
	public static String petBaseKey(String key) {
		if (key == null) return "";
		int hash = key.indexOf('#');
		return (hash >= 0 ? key.substring(0, hash) : key).toUpperCase(Locale.ROOT);
	}

	private static FarmPlot parseFarmPlot(JsonElement el) {
		if (el == null || !el.isJsonObject()) return null;
		JsonObject obj = el.getAsJsonObject();
		int width = (int) num(obj, "width", 15);
		int height = (int) num(obj, "height", 15);
		if (width <= 0 || width > 64) width = 15;
		if (height <= 0 || height > 64) height = 15;

		Map<String, FarmDef> defs = new LinkedHashMap<>();
		JsonElement defsEl = obj.get("defs");
		if (defsEl != null && defsEl.isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : defsEl.getAsJsonObject().entrySet()) {
				if (e.getValue() == null || !e.getValue().isJsonObject()) continue;
				JsonObject d = e.getValue().getAsJsonObject();
				defs.put(e.getKey(), new FarmDef(e.getKey(), str(d, "name"), str(d, "size"),
						(int) num(d, "tier", 1), str(d, "rarity").toUpperCase(Locale.ROOT),
						num(d, "produce", 0), str(d, "item"), str(d, "texture"),
						strList(d.get("lore"))));
			}
		}

		FarmDef[] cells = new FarmDef[width * height];
		int placed = 0;
		double total = 0;
		Map<String, Integer> rarityCounts = new LinkedHashMap<>();
		List<FarmDef> all = new ArrayList<>();
		JsonElement mobsEl = obj.get("mobs");
		if (mobsEl != null && mobsEl.isJsonArray()) {
			for (JsonElement m : mobsEl.getAsJsonArray()) {
				if (m == null || !m.isJsonObject()) continue;
				JsonObject mob = m.getAsJsonObject();
				int index = (int) num(mob, "i", -1);
				String key = str(mob, "k");
				FarmDef def = defs.get(key);
				if (def == null) {
					// the defs block is trimmed or a new mob shipped ahead of it - the square is
					// still occupied, so give it a placeholder rather than dropping it from the plot
					if (key.isEmpty()) continue;
					def = defs.computeIfAbsent(key, k -> new FarmDef(k, "§7" + k, "", 1, "COMMON",
							0, "player_head", "", Collections.emptyList()));
				}
				placed++;
				total += def.produce();
				all.add(def);
				rarityCounts.merge(def.rarity().isEmpty() ? "COMMON" : def.rarity(), 1, Integer::sum);
				if (index >= 0 && index < cells.length) cells[index] = def;
			}
		}
		all.sort((a, b) -> Double.compare(b.produce(), a.produce()));
		List<FarmDef> top = new ArrayList<>();
		java.util.Set<String> topSeen = new java.util.HashSet<>();
		for (FarmDef d : all) {
			if (topSeen.add(d.key())) top.add(d);
			if (top.size() >= 3) break;
		}
		return new FarmPlot(width, height, cells, defs, placed, total, rarityCounts, top);
	}

	private static Garden parseGarden(JsonElement el) {
		if (el == null || !el.isJsonObject()) return null;
		JsonObject g = el.getAsJsonObject();
		List<String> crops = new ArrayList<>();
		JsonElement cropsEl = g.get("crops");
		if (cropsEl != null && cropsEl.isJsonArray()) {
			for (JsonElement c : cropsEl.getAsJsonArray()) {
				if (c == null || c.isJsonNull()) continue;
				if (c.isJsonPrimitive()) {
					crops.add(clean(c.getAsString()));
				} else if (c.isJsonObject()) {
					JsonObject co = c.getAsJsonObject();
					String name = clean(firstNonEmpty(str(co, "name"), str(co, "crop"), str(co, "type")));
					String amount = firstNonEmpty(str(co, "amount"), str(co, "count"), str(co, "stage"));
					crops.add(amount.isEmpty() ? name : name + " §7x" + amount);
				}
			}
		}
		return new Garden((int) num(g, "level", 0), (int) num(g, "maxLevel", 0),
				(int) num(g, "plots", 0), (int) num(g, "maxPlots", 0),
				(int) num(g, "ready", 0), (int) num(g, "planted", 0),
				num(g, "xp", 0), num(g, "xpProgress", 0), crops);
	}

	private static Cookie parseCookie(JsonElement el) {
		if (el == null || !el.isJsonObject()) return null;
		JsonObject c = el.getAsJsonObject();
		return new Cookie((int) num(c, "tier", 0), str(c, "label"), str(c, "color"),
				num(c, "rolls", 0), num(c, "chance", 0), num(c, "multiplier", 0),
				firstNonEmpty(str(c, "multiplierLabel"), compact(num(c, "multiplier", 0))));
	}

	/** An array on tycoon, a double-pipe delimited string on factory. */
	private static List<String> parseGamepasses(JsonElement el) {
		List<String> out = new ArrayList<>();
		if (el == null || el.isJsonNull()) return out;
		if (el.isJsonArray()) {
			for (JsonElement p : el.getAsJsonArray()) {
				if (p != null && p.isJsonPrimitive()) {
					String s = clean(p.getAsString());
					if (!s.isBlank()) out.add(s);
				}
			}
		} else if (el.isJsonPrimitive()) {
			for (String p : el.getAsString().split("\\|\\|")) {
				String s = clean(p);
				if (!s.isBlank()) out.add(s);
			}
		}
		return out;
	}

	private static ItemInfo[] itemArray(JsonElement el, int size) {
		ItemInfo[] out = new ItemInfo[size];
		if (el == null || !el.isJsonArray()) return out;
		JsonArray arr = el.getAsJsonArray();
		for (int i = 0; i < Math.min(size, arr.size()); i++) {
			out[i] = itemInfo(arr.get(i));
		}
		return out;
	}

	private static ItemInfo itemInfo(JsonElement el) {
		if (el == null || !el.isJsonObject()) return null;
		JsonObject o = el.getAsJsonObject();
		String type = str(o, "type");
		if (type.isEmpty() && str(o, "name").isEmpty()) return null;
		return new ItemInfo(str(o, "name"), type, Math.max(1, (int) num(o, "amount", 1)),
				strList(o.get("lore")), bool(o, "enchanted"));
	}

	private static void addSection(LinkedHashMap<String, List<Stat>> target, String name, JsonElement el) {
		if (el == null || !el.isJsonObject()) return;
		List<Stat> rows = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : el.getAsJsonObject().entrySet()) {
			JsonElement value = entry.getValue();
			if (value == null || !value.isJsonPrimitive()) continue;
			String key = entry.getKey();
			if (key.equals("color") || key.endsWith("Progress")) continue;
			rows.add(new Stat(key, pretty(key), displayValue(value)));
		}
		if (!rows.isEmpty()) target.put(name, rows);
	}

	/** Numbers go through the ladder formatter, strings just get their colour codes stripped. */
	private static String displayValue(JsonElement el) {
		try {
			if (el.getAsJsonPrimitive().isBoolean()) return el.getAsBoolean() ? "Yes" : "No";
			if (el.getAsJsonPrimitive().isNumber()) return compact(el.getAsDouble());
		} catch (Exception ignored) {
		}
		return clean(el.getAsString());
	}

	// -------- PROG tab rows --------

	private static final String[][] BAR_PAIRS = {
			{"farm", "farmMax", "Farm Mobs"},
			{"hoelevel", "hoemax", "Hoe Level"},
			{"rodlevel", "rodmax", "Rod Level"},
			{"miners", "minersmax", "Miners"},
			{"blocktier", "blocktiermax", "Block Tier"},
			{"armortier", "armortiermax", "Armor Tier"},
			{"playtimetier", "playtimetiermax", "Playtime Tier"},
	};

	private static final String[][] XP_PAIRS = {
			{"mineLevel", "mineExp", "mineXpForNext", "Mine Level"},
			{"farminglv", "farmingxp", "farmingxpfornext", "Farming"},
			{"fishinglv", "fishingxp", "fishingxpfornext", "Fishing"},
	};

	private static List<ProgRow> buildProgRows(JsonObject stats, LinkedHashMap<String, List<Stat>> sections) {
		List<ProgRow> rows = new ArrayList<>();
		JsonElement progEl = stats.get("progression");
		JsonObject prog = progEl != null && progEl.isJsonObject() ? progEl.getAsJsonObject() : null;

		if (prog != null) {
			rows.add(new ProgRow(ROW_HEADER, "PROGRESSION", "", 0));
			java.util.Set<String> used = new java.util.HashSet<>();

			for (String[] pair : XP_PAIRS) {
				if (!prog.has(pair[0])) continue;
				double level = num(prog, pair[0], 0);
				double xp = num(prog, pair[1], 0);
				double next = num(prog, pair[2], 0);
				used.add(pair[0]);
				used.add(pair[1]);
				used.add(pair[2]);
				rows.add(new ProgRow(ROW_BAR, pair[3] + " " + (long) level,
						compact(xp) + " / " + compact(next), pct(xp, next)));
			}
			for (String[] pair : BAR_PAIRS) {
				if (!prog.has(pair[0]) || !prog.has(pair[1])) continue;
				double cur = num(prog, pair[0], 0);
				double max = num(prog, pair[1], 0);
				used.add(pair[0]);
				used.add(pair[1]);
				rows.add(new ProgRow(ROW_BAR, pair[2], compact(cur) + " / " + compact(max), pct(cur, max)));
			}
			if (prog.has("prestigeProgress")) {
				double p = num(prog, "prestigeProgress", 0);
				String label = str(prog, "prestigeLabel");
				used.add("prestigeProgress");
				used.add("prestigeLabel");
				rows.add(new ProgRow(ROW_BAR, "Prestige " + (label.isEmpty() ? "" : clean(label)),
						((long) p) + "%", pct(p, 100)));
			}
			List<Stat> progStats = sections.get("PROGRESSION");
			if (progStats != null) {
				for (Stat s : progStats) {
					if (used.contains(s.key())) continue;
					rows.add(new ProgRow(ROW_VALUE, s.label(), s.value(), 0));
				}
			}
		}

		for (Map.Entry<String, List<Stat>> section : sections.entrySet()) {
			if (section.getKey().equals("PROGRESSION")) continue;
			rows.add(new ProgRow(ROW_HEADER, section.getKey(), "", 0));
			for (Stat s : section.getValue()) {
				rows.add(new ProgRow(ROW_VALUE, s.label(), s.value(), 0));
			}
		}
		return rows;
	}

	private static final Map<String, String> PRETTY = new HashMap<>();

	static {
		PRETTY.put("hoelevel", "Hoe Level");
		PRETTY.put("hoemax", "Hoe Max");
		PRETTY.put("rodlevel", "Rod Level");
		PRETTY.put("rodmax", "Rod Max");
		PRETTY.put("farminglv", "Farming Lv");
		PRETTY.put("farmingxp", "Farming XP");
		PRETTY.put("farmingxpfornext", "Farming XP Next");
		PRETTY.put("fishinglv", "Fishing Lv");
		PRETTY.put("fishingxp", "Fishing XP");
		PRETTY.put("fishingxpfornext", "Fishing XP Next");
		PRETTY.put("minersmax", "Miners Max");
		PRETTY.put("blocklevel", "Block Level");
		PRETTY.put("blocktier", "Block Tier");
		PRETTY.put("blocktiermax", "Block Tier Max");
		PRETTY.put("armortier", "Armor Tier");
		PRETTY.put("armortiermax", "Armor Tier Max");
		PRETTY.put("playtimetier", "Playtime Tier");
		PRETTY.put("playtimetiermax", "Playtime Tier Max");
		PRETTY.put("cookietier", "Cookie Tier");
		PRETTY.put("miners", "Miners");
		PRETTY.put("prestige", "Prestige");
		PRETTY.put("confetti", "Confetti");
		PRETTY.put("scales", "Scales");
	}

	/** "prestigeCost" -> "Prestige Cost", plus a dictionary for the all-lowercase factory keys. */
	public static String pretty(String key) {
		if (key == null) return "";
		String known = PRETTY.get(key);
		if (known != null) return known;
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			if (i == 0) out.append(Character.toUpperCase(c));
			else if (Character.isUpperCase(c)) out.append(' ').append(c);
			else out.append(c);
		}
		return out.toString();
	}

	// -------- tiny json helpers --------

	private static String str(JsonObject o, String key) {
		if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
		JsonElement e = o.get(key);
		return e.isJsonPrimitive() ? e.getAsString() : "";
	}

	private static double num(JsonObject o, String key, double fallback) {
		if (o == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
		JsonElement e = o.get(key);
		if (!e.isJsonPrimitive()) return fallback;
		try {
			return e.getAsDouble();
		} catch (Exception ex) {
			double parsed = parseValue(e.getAsString());
			return Double.isNaN(parsed) ? fallback : parsed;
		}
	}

	private static boolean bool(JsonObject o, String key) {
		if (o == null || !o.has(key) || o.get(key).isJsonNull()) return false;
		try {
			return o.get(key).getAsBoolean();
		} catch (Exception e) {
			return false;
		}
	}

	private static List<String> strList(JsonElement el) {
		if (el == null || !el.isJsonArray()) return Collections.emptyList();
		List<String> out = new ArrayList<>();
		for (JsonElement e : el.getAsJsonArray()) {
			if (e != null && e.isJsonPrimitive()) out.add(e.getAsString());
		}
		return out;
	}

	private static long safeInt(String s, long fallback) {
		try {
			return Long.parseLong(s.trim());
		} catch (Exception e) {
			double v = parseValue(s);
			return Double.isNaN(v) ? fallback : (long) v;
		}
	}

	private static String firstNonEmpty(String... options) {
		for (String s : options) if (s != null && !s.isBlank()) return s;
		return "";
	}

	// =================================================================================
	// compare
	// =================================================================================

	/**
	 * One comparable number. {@code path} is either "section.key" into the raw stats object or
	 * an "@synthetic" key computed from the parsed profile.
	 */
	public record Metric(String category, String label, String path, boolean lowerWins) {
	}

	/** -1 / 0 / 1 in {@code winner}: A wins, tie, B wins. */
	public record DiffRow(Metric metric, double a, double b, String textA, String textB, int winner) {
	}

	public record CategoryResult(String category, int aWins, int bWins, int ties) {
		public String winnerLabel(String nameA, String nameB) {
			if (aWins > bWins) return nameA;
			if (bWins > aWins) return nameB;
			return "TIE";
		}
	}

	public record CompareResult(List<DiffRow> rows, List<CategoryResult> categories,
	                            int aCategories, int bCategories, int tiedCategories) {
	}

	public static final List<Metric> METRICS = List.of(
			new Metric("ECONOMY", "Balance", "economy.balance", false),
			new Metric("ECONOMY", "Gems", "economy.gems", false),
			new Metric("ECONOMY", "Tokens", "economy.tokens", false),
			new Metric("ECONOMY", "Credits", "economy.credits", false),
			new Metric("ECONOMY", "Scales", "economy.scales", false),
			new Metric("ECONOMY", "Confetti", "economy.confetti", false),

			new Metric("MULTIPLIERS", "Money", "multipliers.money", false),
			new Metric("MULTIPLIERS", "Gem", "multipliers.gem", false),
			new Metric("MULTIPLIERS", "Token", "multipliers.token", false),
			new Metric("MULTIPLIERS", "Sell", "multipliers.sell", false),
			new Metric("MULTIPLIERS", "Scale", "multipliers.scale", false),
			new Metric("MULTIPLIERS", "Playtime", "multipliers.playtime", false),
			new Metric("MULTIPLIERS", "Prestige", "multipliers.prestige", false),

			new Metric("PROGRESSION", "Prestige", "progression.prestige", false),
			new Metric("PROGRESSION", "Ascension", "progression.ascension", false),
			new Metric("PROGRESSION", "Mine Level", "progression.mineLevel", false),
			new Metric("PROGRESSION", "Mined", "progression.mined", false),
			new Metric("PROGRESSION", "Farm", "progression.farm", false),
			new Metric("PROGRESSION", "Armor Multi", "progression.armorMulti", false),
			new Metric("PROGRESSION", "Block Level", "progression.blocklevel", false),
			new Metric("PROGRESSION", "Block Tier", "progression.blocktier", false),
			new Metric("PROGRESSION", "Hoe Level", "progression.hoelevel", false),
			new Metric("PROGRESSION", "Rod Level", "progression.rodlevel", false),
			new Metric("PROGRESSION", "Miners", "progression.miners", false),
			new Metric("PROGRESSION", "Farming Lv", "progression.farminglv", false),
			new Metric("PROGRESSION", "Fishing Lv", "progression.fishinglv", false),

			new Metric("GARDEN", "Level", "garden.level", false),
			new Metric("GARDEN", "Plots", "garden.plots", false),
			new Metric("GARDEN", "XP", "garden.xp", false),

			new Metric("COOKIE", "Tier", "cookie.tier", true),
			new Metric("COOKIE", "Multiplier", "cookie.multiplier", false),

			new Metric("PETS", "Owned", "@pets.owned", false),
			new Metric("PETS", "Equipped", "@pets.equipped", false),
			new Metric("PETS", "Slots", "@pets.limit", false),
			new Metric("PETS", "Best Multi", "@pets.best", false),
			new Metric("PETS", "Total Multi", "@pets.sum", false),

			new Metric("GEAR", "Pieces", "@gear.pieces", false),
			new Metric("GEAR", "Armor Multi", "@gear.multi", false),

			new Metric("FARM", "Mobs Placed", "@farm.mobs", false),
			new Metric("FARM", "Produce/sec", "@farm.produce", false),

			new Metric("TIME", "Playtime", "@time.playtime", false)
	);

	private static double metricValue(ServerProfile s, Metric m) {
		if (s == null) return Double.NaN;
		String path = m.path();
		if (path.startsWith("@")) {
			return switch (path) {
				case "@pets.owned" -> s.has("pets") ? s.pets().size() : Double.NaN;
				case "@pets.equipped" -> s.has("pets") ? s.petsEquipped() : Double.NaN;
				case "@pets.limit" -> s.has("pets") ? s.petLimit() : Double.NaN;
				case "@pets.best" -> s.has("pets") && !s.pets().isEmpty() ? s.bestPetMultiplier() : Double.NaN;
				case "@pets.sum" -> s.has("pets") && !s.pets().isEmpty() ? s.summedPetMultiplier() : Double.NaN;
				case "@gear.pieces" -> s.has("armor") ? s.armorPieces() : Double.NaN;
				case "@gear.multi" -> s.has("armor") ? s.armorLoreTotal() : Double.NaN;
				case "@farm.mobs" -> s.farm() != null && s.has("farmPlot") ? s.farm().placed() : Double.NaN;
				case "@farm.produce" -> s.farm() != null && s.has("farmPlot") ? s.farm().totalProduce() : Double.NaN;
				case "@time.playtime" -> parseDuration(s.stat("time", "playtime"));
				default -> Double.NaN;
			};
		}
		int dot = path.indexOf('.');
		if (dot < 0) return Double.NaN;
		String section = path.substring(0, dot);
		if (!s.has(section)) return Double.NaN;
		String raw = s.stat(section, path.substring(dot + 1));
		return raw.isEmpty() ? Double.NaN : parseValue(raw);
	}

	private static String metricText(ServerProfile s, Metric m, double value) {
		if (Double.isNaN(value)) return "-";
		if (m.path().equals("@time.playtime")) {
			String raw = s == null ? "" : clean(s.stat("time", "playtime"));
			return raw.isEmpty() ? formatDuration(value) : raw;
		}
		if (m.path().startsWith("@")) return compact(value);
		int dot = m.path().indexOf('.');
		String raw = s == null ? "" : clean(s.stat(m.path().substring(0, dot), m.path().substring(dot + 1)));
		// keep suffixed / currency strings as the server wrote them, but shorten raw long
		// integers like "922491" so both columns read the same way as the rest of the UI
		if (raw.isEmpty() || PLAIN_NUMBER.matcher(raw).matches()) return compact(value);
		return raw;
	}

	/** Builds the full diff list plus per-category and overall tallies. Never throws. */
	public static CompareResult compare(ServerProfile a, ServerProfile b) {
		List<DiffRow> rows = new ArrayList<>();
		LinkedHashMap<String, int[]> tally = new LinkedHashMap<>();

		for (Metric m : METRICS) {
			double va = metricValue(a, m);
			double vb = metricValue(b, m);
			if (Double.isNaN(va) && Double.isNaN(vb)) continue;

			// A stat only one side even has is shown for context but never scored - winning
			// "gear" against a season that does not publish gear would be meaningless.
			if (Double.isNaN(va) || Double.isNaN(vb)) {
				rows.add(new DiffRow(m, va, vb, metricText(a, m, va), metricText(b, m, vb), 0));
				continue;
			}

			int winner;
			double scale = Math.max(Math.abs(va), Math.abs(vb));
			if (Math.abs(va - vb) <= scale * 1e-9) {
				winner = 0;
			} else if (m.lowerWins()) {
				winner = va < vb ? -1 : 1;
			} else {
				winner = va > vb ? -1 : 1;
			}

			int[] counts = tally.computeIfAbsent(m.category(), k -> new int[3]);
			if (winner < 0) counts[0]++;
			else if (winner > 0) counts[1]++;
			else counts[2]++;

			rows.add(new DiffRow(m, va, vb, metricText(a, m, va), metricText(b, m, vb), winner));
		}

		List<CategoryResult> categories = new ArrayList<>();
		int aCats = 0;
		int bCats = 0;
		int tiedCats = 0;
		for (Map.Entry<String, int[]> e : tally.entrySet()) {
			int[] c = e.getValue();
			categories.add(new CategoryResult(e.getKey(), c[0], c[1], c[2]));
			if (c[0] > c[1]) aCats++;
			else if (c[1] > c[0]) bCats++;
			else tiedCats++;
		}
		return new CompareResult(rows, categories, aCats, bCats, tiedCats);
	}

	// =================================================================================
	// multi-player compare
	// =================================================================================

	/**
	 * One metric across every player in a leaderboard.
	 *
	 * <p>{@code best[i]} marks every column sitting on the winning value; when more than one does,
	 * {@code tied} is set and nobody scores. {@code scored} is false for a metric only one player
	 * even publishes - it is shown for context but never counted, same rule as the 1v1 compare.
	 */
	public record MultiRow(Metric metric, double[] values, String[] texts, boolean[] best,
	                       boolean tied, boolean scored) {
	}

	/** {@code wins[i]} is how many scored metrics player {@code i} leads outright. */
	public record MultiResult(List<MultiRow> rows, int[] wins, int scoredRows) {

		/** Index of the outright leader, or -1 when the top of the table is shared. */
		public int leader() {
			int bestIndex = -1;
			int bestScore = -1;
			boolean shared = false;
			for (int i = 0; i < wins.length; i++) {
				if (wins[i] > bestScore) {
					bestScore = wins[i];
					bestIndex = i;
					shared = false;
				} else if (wins[i] == bestScore) {
					shared = true;
				}
			}
			return shared || bestScore <= 0 ? -1 : bestIndex;
		}
	}

	/**
	 * Scores every metric across N profiles. A null entry means that player has no data on the
	 * selected server; its cells read "-" and it can never win a row.
	 *
	 * @param compactText true to force {@link #compact} formatting, for when the columns are too
	 *                    narrow to carry the server's own "$1.04Sp" style strings
	 */
	public static MultiResult compareAll(List<ServerProfile> profiles, boolean compactText) {
		int n = profiles == null ? 0 : profiles.size();
		List<MultiRow> rows = new ArrayList<>();
		int[] wins = new int[Math.max(0, n)];
		int scoredRows = 0;
		if (n == 0) return new MultiResult(rows, wins, 0);

		for (Metric m : METRICS) {
			double[] values = new double[n];
			String[] texts = new String[n];
			int present = 0;
			for (int i = 0; i < n; i++) {
				ServerProfile s = profiles.get(i);
				double v = metricValue(s, m);
				values[i] = v;
				if (Double.isNaN(v)) {
					texts[i] = "-";
					continue;
				}
				present++;
				if (compactText) {
					texts[i] = m.path().equals("@time.playtime") ? formatDuration(v) : compact(v);
				} else {
					texts[i] = metricText(s, m, v);
				}
			}
			if (present == 0) continue;

			boolean[] best = new boolean[n];
			boolean tied = false;
			boolean scored = present >= 2;
			if (scored) {
				double top = Double.NaN;
				for (int i = 0; i < n; i++) {
					if (Double.isNaN(values[i])) continue;
					if (Double.isNaN(top) || (m.lowerWins() ? values[i] < top : values[i] > top)) top = values[i];
				}
				double slack = Math.max(Math.abs(top), 1e-9) * 1e-9;
				int leaders = 0;
				for (int i = 0; i < n; i++) {
					if (Double.isNaN(values[i])) continue;
					if (Math.abs(values[i] - top) <= slack) {
						best[i] = true;
						leaders++;
					}
				}
				tied = leaders > 1;
				if (!tied) {
					for (int i = 0; i < n; i++) if (best[i]) wins[i]++;
					scoredRows++;
				}
			}
			rows.add(new MultiRow(m, values, texts, best, tied, scored));
		}
		return new MultiResult(rows, wins, scoredRows);
	}

	// =================================================================================
	// multi-player fetching
	// =================================================================================

	/**
	 * A self-contained lookup pool for the compare screen.
	 *
	 * <p>{@link PlayerStatsNet} only owns two slots and is not ours to widen, so this mirrors its
	 * shape at a different arity: every request runs on its own daemon thread, the render thread
	 * only ever reads volatile fields, and a single generation stamp is bumped on each new batch so
	 * a slow reply landing after the user changed the name list is dropped instead of writing into
	 * a column nobody is looking at any more.
	 */
	public static final class MultiFetch {

		public static final int MAX = 6;

		public static final int IDLE = 0;
		public static final int LOADING = 1;
		public static final int DONE = 2;
		public static final int FAILED = 3;

		private static final String ENDPOINT = "https://afkable.com/api/player/";

		private MultiFetch() {
		}

		/** One player's column. Everything a screen reads is volatile and non-blocking. */
		public static final class Column {
			private final String name;
			private volatile int state = IDLE;
			private volatile String error = "";
			private volatile PlayerProfile profile;

			private Column(String name) {
				this.name = name;
			}

			/** The name as typed - available before the lookup finishes. */
			public String name() {
				return name;
			}

			public int state() {
				return state;
			}

			public boolean loading() {
				return state == LOADING;
			}

			public boolean ready() {
				return state == DONE && profile != null;
			}

			public boolean failed() {
				return state == FAILED;
			}

			public String error() {
				return error;
			}

			public PlayerProfile profile() {
				return profile;
			}

			/** The API's spelling of the name once it is known, otherwise what the user typed. */
			public String displayName() {
				PlayerProfile p = profile;
				return p == null || p.name() == null || p.name().isBlank() ? name : p.name();
			}

			/** This player's profile on one server, or null when they never played it. */
			public ServerProfile server(String id) {
				PlayerProfile p = profile;
				return p == null ? null : p.server(id);
			}

			public List<ServerProfile> servers() {
				PlayerProfile p = profile;
				return p == null ? List.of() : p.servers();
			}
		}

		private static final AtomicLong GENERATION = new AtomicLong();
		private static volatile List<Column> COLUMNS = List.of();
		private static volatile HttpClient http;

		/** Immutable snapshot - safe to iterate from render while threads are still finishing. */
		public static List<Column> columns() {
			return COLUMNS;
		}

		public static boolean empty() {
			return COLUMNS.isEmpty();
		}

		public static boolean busy() {
			for (Column c : COLUMNS) if (c.loading()) return true;
			return false;
		}

		/** True once every column has settled one way or the other and at least one has data. */
		public static boolean anyReady() {
			for (Column c : COLUMNS) if (c.ready()) return true;
			return false;
		}

		public static void clear() {
			GENERATION.incrementAndGet();
			COLUMNS = List.of();
		}

		/**
		 * Splits the compare screen's one text field into names: comma or space separated,
		 * trimmed, case-insensitively de-duplicated and capped at {@link #MAX}.
		 */
		public static List<String> names(String csv) {
			List<String> out = new ArrayList<>();
			if (csv == null) return out;
			java.util.Set<String> seen = new java.util.HashSet<>();
			for (String part : csv.split("[,;\\s]+")) {
				String name = part.trim();
				if (name.isEmpty()) continue;
				if (!seen.add(name.toLowerCase(Locale.ROOT))) continue;
				out.add(name);
				if (out.size() >= MAX) break;
			}
			return out;
		}

		/** Starts a fresh batch. Any batch already in flight is abandoned, not cancelled. */
		public static void fetch(List<String> requested) {
			List<Column> columns = new ArrayList<>();
			if (requested != null) {
				for (String name : requested) {
					if (columns.size() >= MAX) break;
					if (name == null || name.isBlank()) continue;
					columns.add(new Column(name.trim()));
				}
			}
			long generation = GENERATION.incrementAndGet();
			COLUMNS = List.copyOf(columns);
			for (Column column : columns) {
				column.state = LOADING;
				Thread thread = new Thread(() -> load(column, generation),
						"afkable-multi-" + column.name());
				thread.setDaemon(true);
				thread.start();
			}
		}

		/** Re-runs the current name list. No-op when nothing has been looked up yet. */
		public static void refresh() {
			List<String> last = new ArrayList<>();
			for (Column c : COLUMNS) last.add(c.name());
			if (!last.isEmpty()) fetch(last);
		}

		private static HttpClient client() {
			HttpClient local = http;
			if (local == null) {
				synchronized (MultiFetch.class) {
					local = http;
					if (local == null) {
						local = HttpClient.newBuilder()
								.connectTimeout(Duration.ofSeconds(8))
								.followRedirects(HttpClient.Redirect.NORMAL)
								.build();
						http = local;
					}
				}
			}
			return local;
		}

		private static void load(Column column, long generation) {
			try {
				HttpRequest request = HttpRequest.newBuilder()
						.uri(URI.create(ENDPOINT + encodeName(column.name())))
						.header("User-Agent", "AFKABLE-CLIENT")
						.header("Accept", "application/json")
						.timeout(Duration.ofSeconds(12))
						.GET().build();
				HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
				if (GENERATION.get() != generation) return; // batch superseded

				int code = response.statusCode();
				if (code == 404) {
					fail(column, generation, "not found");
					return;
				}
				if (code < 200 || code >= 300) {
					fail(column, generation, "HTTP " + code);
					return;
				}

				JsonObject root;
				try {
					root = JsonParser.parseString(response.body()).getAsJsonObject();
				} catch (Exception e) {
					fail(column, generation, "bad response");
					return;
				}

				PlayerProfile parsed;
				try {
					parsed = parse(root);
				} catch (Throwable t) {
					fail(column, generation, "unreadable stats");
					return;
				}
				if (parsed == null || parsed.servers().isEmpty()) {
					fail(column, generation, "no server data");
					return;
				}

				if (GENERATION.get() != generation) return;
				column.profile = parsed;
				column.error = "";
				column.state = DONE;
			} catch (java.net.http.HttpTimeoutException e) {
				fail(column, generation, "timed out");
			} catch (java.net.ConnectException | java.net.UnknownHostException e) {
				fail(column, generation, "offline");
			} catch (Throwable t) {
				String label = t.getClass().getSimpleName().replace("Exception", "");
				fail(column, generation, label.isEmpty() ? "failed" : label.toLowerCase(Locale.ROOT));
			}
		}

		private static void fail(Column column, long generation, String message) {
			if (GENERATION.get() != generation) return;
			column.profile = null;
			column.error = message;
			column.state = FAILED;
		}

		/** Same whitelist the single-slot fetcher uses - nothing user-typed reaches the URL raw. */
		private static String encodeName(String name) {
			StringBuilder out = new StringBuilder(name.length());
			for (int i = 0; i < name.length(); i++) {
				char c = name.charAt(i);
				if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
						|| c == '_' || c == '-' || c == '.') {
					out.append(c);
				}
			}
			return out.toString();
		}
	}

	// =================================================================================
	// shared draw helpers
	// =================================================================================

	private static int accent() {
		return ForgeCalcTheme.current().accent;
	}

	private static int accentDark() {
		return ForgeCalcTheme.current().accentDark;
	}

	public static void text(DrawContext ctx, TextRenderer tr, String s, int x, int y) {
		ctx.drawText(tr, Text.literal(s), x, y, 0xFFFFFFFF, true);
	}

	public static void textRight(DrawContext ctx, TextRenderer tr, String s, int right, int y) {
		ctx.drawText(tr, Text.literal(s), right - tr.getWidth(stripCodes(s)), y, 0xFFFFFFFF, true);
	}

	public static void textCenter(DrawContext ctx, TextRenderer tr, String s, int cx, int y) {
		ctx.drawCenteredTextWithShadow(tr, Text.literal(s), cx, y, 0xFFFFFFFF);
	}

	/** Width helper - the text renderer counts colour codes, so strip them before measuring. */
	public static String stripCodes(String s) {
		if (s == null) return "";
		return LEGACY_CODE.matcher(HEX_CODE.matcher(s).replaceAll("")).replaceAll("");
	}

	public static int widthOf(TextRenderer tr, String s) {
		return tr.getWidth(stripCodes(s));
	}

	/** Trims a coloured string so its visible part fits inside {@code maxWidth}. */
	public static String fit(TextRenderer tr, String s, int maxWidth) {
		if (s == null) return "";
		if (widthOf(tr, s) <= maxWidth) return s;
		int ellipsis = tr.getWidth("..");
		StringBuilder out = new StringBuilder();
		int width = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if ((c == '§' || c == '&') && i + 1 < s.length()) {
				out.append(c).append(s.charAt(i + 1)); // colour codes cost no width
				i++;
				continue;
			}
			int cw = tr.getWidth(String.valueOf(c));
			if (width + cw + ellipsis > maxWidth) break;
			out.append(c);
			width += cw;
		}
		return out + "..";
	}

	public static void statCard(DrawContext ctx, TextRenderer tr, int x, int y, int w, int h,
	                            String label, String value, int accent) {
		ctx.fill(x, y, x + w, y + h, 0x30000000);
		ctx.fill(x, y, x + 2, y + h, accent);
		text(ctx, tr, "§7" + label, x + 5, y + 4);
		text(ctx, tr, "§f§l" + fit(tr, value, w - 9), x + 5, y + 15);
	}

	public static void progressBar(DrawContext ctx, TextRenderer tr, int x, int y, int w, int h,
	                               double fraction, String label, int fill) {
		double f = Double.isNaN(fraction) ? 0 : Math.max(0, Math.min(1, fraction));
		ctx.fill(x, y, x + w, y + h, 0x50000000);
		int end = x + (int) (w * f);
		if (end > x) ctx.fill(x, y, end, y + h, fill);
		ctx.fill(x, y + h - 1, x + w, y + h, 0x40000000);
		if (label != null && !label.isEmpty()) {
			textCenter(ctx, tr, label, x + w / 2, y + (h - 8) / 2 + 1);
		}
	}

	/** 18x18 item slot. Returns true when the mouse is inside it. */
	public static boolean itemSlot(DrawContext ctx, TextRenderer tr, int x, int y,
	                               ItemInfo info, int mouseX, int mouseY, boolean dim) {
		boolean hovered = mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18;
		if (info == null) {
			ctx.fill(x, y, x + 18, y + 18, dim ? 0x18000000 : 0x25000000);
			ctx.fill(x, y, x + 18, y + 1, 0x40FFFFFF);
			ctx.fill(x, y + 17, x + 18, y + 18, 0x40FFFFFF);
			ctx.fill(x, y, x + 1, y + 18, 0x40FFFFFF);
			ctx.fill(x + 17, y, x + 18, y + 18, 0x40FFFFFF);
			return hovered;
		}
		int tint = (info.color() & 0xFFFFFF) | 0x60000000;
		ctx.fill(x, y, x + 18, y + 18, tint);
		ctx.fill(x, y, x + 18, y + 1, (info.color() & 0xFFFFFF) | 0xA0000000);
		ItemStack stack = stackFor(info.type());
		ctx.drawItem(stack, x + 1, y + 1);
		if (info.amount() > 1) {
			ItemStack counted = stack.copy();
			counted.setCount(Math.min(99, info.amount()));
			ctx.drawStackOverlay(tr, counted, x + 1, y + 1);
		}
		if (hovered) {
			ctx.fill(x, y, x + 18, y + 18, 0x33FFFFFF);
		}
		return hovered;
	}

	/** Tooltip lines for an item: cleaned name then every lore line, capped at 14. */
	public static List<String> itemTooltip(ItemInfo info) {
		List<String> lines = new ArrayList<>();
		if (info == null) return lines;
		lines.add("§f" + info.display());
		int shown = 0;
		for (String raw : info.lore()) {
			if (shown >= 13) {
				lines.add("§8..." + (info.lore().size() - shown) + " more");
				break;
			}
			String line = cleanLore(raw);
			if (line.isBlank()) continue;
			lines.add("§7" + line);
			shown++;
		}
		return lines;
	}

	/**
	 * The same tooltip in the item's real colours. Blank and struck-through spacer lines are kept
	 * because they are what gives a lore block its shape - the whole point of drawing it properly.
	 */
	public static List<Text> itemTooltipRich(ItemInfo info) {
		List<Text> lines = new ArrayList<>();
		if (info == null) return lines;
		lines.add(rich(info.name(), 0xFFFFFF));
		List<String> lore = info.lore();
		for (int i = 0; i < lore.size(); i++) {
			if (i >= 15) {
				lines.add(colored("..." + (lore.size() - i) + " more", 0x555555));
				break;
			}
			lines.add(rich(lore.get(i), 0xAAAAAA));
		}
		return lines;
	}

	/** Hover card for a farm mob: real name colours, then size / tier / produce / rarity. */
	public static List<Text> farmTooltipRich(FarmDef def) {
		List<Text> lines = new ArrayList<>();
		if (def == null) return lines;
		lines.add(def.nameText());
		String size = def.size() == null || def.size().isBlank() ? "" : def.size();
		MutableText sizeLine = colored("Size: ", 0xAAAAAA);
		sizeLine.append(size.isEmpty() ? colored("-", 0x555555) : rich(size, 0xFFFFFF));
		lines.add(sizeLine);
		lines.add(colored("Tier: ", 0xAAAAAA).append(colored(String.valueOf(def.tier()), 0xFFFFFF)));
		lines.add(colored("Produce: ", 0xAAAAAA)
				.append(colored("$" + compact(def.produce()), 0x55FF55))
				.append(colored("/sec", 0xAAAAAA)));
		lines.add(colored("Rarity: ", 0xAAAAAA)
				.append(colored(def.rarity().isEmpty() ? "COMMON" : def.rarity(),
						rarityColor(def.rarity()), true)));
		return lines;
	}

	/** Tooltip frame in the repo's style, but taking real Text so colours survive. */
	public static void richTooltip(DrawContext ctx, TextRenderer tr, int mouseX, int mouseY,
	                               int screenW, int screenH, List<Text> lines) {
		if (lines == null || lines.isEmpty()) return;
		int w = 0;
		for (Text line : lines) w = Math.max(w, tr.getWidth(line));
		w += 10;
		int h = lines.size() * 10 + 6;
		int x = Math.max(4, Math.min(mouseX + 10, screenW - w - 4));
		int y = Math.max(4, Math.min(mouseY - h - 4 < 4 ? mouseY + 12 : mouseY - h - 4, screenH - h - 4));

		ForgeCalcTheme theme = ForgeCalcTheme.current();
		ctx.fill(x - 2, y - 2, x + w + 2, y + h + 2, theme.frameDark);
		ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, theme.border);
		ctx.fill(x, y, x + w, y + h, 0xF0241A10);
		int ty = y + 4;
		for (Text line : lines) {
			ctx.drawText(tr, line, x + 5, ty, 0xFFFFFFFF, true);
			ty += 10;
		}
	}

	// -------- scaled item rendering --------

	/** Largest quarter-step scale whose 16x16 item still fits inside {@code size} pixels. */
	public static float itemScaleFor(int size) {
		if (size >= 16) return 1f;
		return Math.max(0.25f, (float) Math.floor(size / 16f * 4f) / 4f);
	}

	/** How many pixels {@link #drawItemScaled} will actually paint for a {@code size} box. */
	public static int itemPixels(int size) {
		return Math.round(16f * itemScaleFor(size));
	}

	/**
	 * Draws a stack inside a {@code size}-pixel box, centred. Anything 16px or wider is a plain
	 * {@link DrawContext#drawItem} call; smaller boxes push a scale onto the GUI matrix stack so
	 * the item still renders as a real item instead of a coloured square. No texture is downloaded
	 * and nothing blocks - the render thread never waits on anything here.
	 */
	public static void drawItemScaled(DrawContext ctx, ItemStack stack, int x, int y, int size) {
		if (stack == null || stack.isEmpty() || size <= 0) return;
		if (size >= 16) {
			ctx.drawItem(stack, x + (size - 16) / 2, y + (size - 16) / 2);
			return;
		}
		float scale = itemScaleFor(size);
		int painted = Math.round(16f * scale);
		ctx.getMatrices().pushMatrix();
		ctx.getMatrices().translate(x + (size - painted) / 2f, y + (size - painted) / 2f);
		ctx.getMatrices().scale(scale, scale);
		ctx.drawItem(stack, 0, 0);
		ctx.getMatrices().popMatrix();
	}

	/** Section header row used by the scrolling lists. */
	public static void sectionHeader(DrawContext ctx, TextRenderer tr, int x, int y, int w, String label) {
		ctx.fill(x, y, x + w, y + 11, 0x35FFFFFF);
		ctx.fill(x, y, x + 2, y + 11, accent());
		text(ctx, tr, "§e§l" + label, x + 6, y + 2);
	}

	/** Vertical scrollbar in the repo's style. Pass the already-clamped scroll offset. */
	public static void scrollbar(DrawContext ctx, int x, int y, int h, int contentH, int scroll, int maxScroll) {
		ctx.fill(x, y, x + 3, y + h, 0x50000000);
		if (maxScroll <= 0) return;
		int thumbH = Math.max(14, h * h / Math.max(1, contentH));
		int thumbY = y + (h - thumbH) * scroll / maxScroll;
		ctx.fill(x, thumbY, x + 3, thumbY + thumbH, accent());
	}

	/** The same thing lying down, for column strips that scroll sideways. */
	public static void hscrollbar(DrawContext ctx, int x, int y, int w, int contentW, int scroll, int maxScroll) {
		ctx.fill(x, y, x + w, y + 3, 0x50000000);
		if (maxScroll <= 0) return;
		int thumbW = Math.max(14, w * w / Math.max(1, contentW));
		int thumbX = x + (w - thumbW) * scroll / maxScroll;
		ctx.fill(thumbX, y, thumbX + thumbW, y + 3, accent());
	}

	/** Maps a click at {@code mouseX} on an hscrollbar track back to a clamped scroll offset. */
	public static int hscrollFromClick(int mouseX, int x, int w, int contentW, int maxScroll) {
		if (maxScroll <= 0 || w <= 0) return 0;
		int thumbW = Math.max(14, w * w / Math.max(1, contentW));
		int travel = Math.max(1, w - thumbW);
		int wanted = (mouseX - x - thumbW / 2) * maxScroll / travel;
		return Math.max(0, Math.min(maxScroll, wanted));
	}

	/** Chunky armour silhouette. Returns the segment index under the mouse, or -1. */
	public static int paperDoll(DrawContext ctx, int dx, int dy, ItemInfo[] armor, int mouseX, int mouseY) {
		int[][] segments = {
				{dx - 9, dy, 18, 16},          // 0 head
				{dx - 4, dy + 16, 8, 3},       // - neck (decor only)
				{dx - 11, dy + 19, 22, 26},    // 2 torso
				{dx - 17, dy + 19, 6, 24},     // 3 left arm
				{dx + 11, dy + 19, 6, 24},     // 4 right arm
				{dx - 10, dy + 45, 9, 26},     // 5 left leg
				{dx + 1, dy + 45, 9, 26},      // 6 right leg
				{dx - 10, dy + 71, 9, 5},      // 7 left foot
				{dx + 1, dy + 71, 9, 5},       // 8 right foot
		};
		// which armour piece tints which segment: 0 helmet, 1 chestplate, 2 leggings, 3 boots
		int[] owner = {0, -1, 1, 1, 1, 2, 2, 3, 3};

		int hoveredPiece = -1;
		for (int i = 0; i < segments.length; i++) {
			int[] s = segments[i];
			ctx.fill(s[0], s[1], s[0] + s[2], s[1] + s[3], 0xFF3A2A1C);
			ctx.fill(s[0], s[1], s[0] + s[2], s[1] + 1, 0x60FFFFFF);
			int piece = owner[i];
			ItemInfo worn = piece >= 0 && piece < armor.length ? armor[piece] : null;
			if (worn != null) {
				ctx.fill(s[0], s[1], s[0] + s[2], s[1] + s[3], (worn.color() & 0xFFFFFF) | 0x70000000);
			} else if (piece >= 0) {
				for (int hy = s[1] + 2; hy < s[1] + s[3]; hy += 4) {
					ctx.fill(s[0], hy, s[0] + s[2], hy + 1, 0x30000000);
				}
			}
			if (piece >= 0 && mouseX >= s[0] && mouseX < s[0] + s[2] && mouseY >= s[1] && mouseY < s[1] + s[3]) {
				hoveredPiece = piece;
			}
		}
		return hoveredPiece;
	}
}
