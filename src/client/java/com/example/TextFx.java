package com.example;

import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Coloured label helpers.
 *
 * <p>{@link #gradient(String, int, int)} is the original two-stop helper and is unchanged.
 *
 * <p>{@link #rich(String)} parses a small markup language on top of Minecraft's legacy
 * section codes:
 * <pre>
 *   &lt;b&gt;&lt;gradient:#FFD66A:#FFE49D:#FFD66A&gt;HUD&lt;/gradient&gt;&lt;/b&gt;
 * </pre>
 * Supported tags:
 * <ul>
 *   <li>{@code <gradient:#RRGGBB:#RRGGBB[:#RRGGBB...]>} … {@code </gradient>} - two or
 *       more stops, spread evenly across the enclosed characters. With three stops the
 *       middle stop lands exactly on the halfway character.</li>
 *   <li>{@code <b>} bold, {@code <i>} italic, {@code <u>} underline,
 *       {@code <s>} strikethrough, {@code <o>} obfuscated (aliases: bold/italic/em/
 *       underline/strike/strikethrough/obf/obfuscated/k).</li>
 *   <li>{@code <#RRGGBB>} … {@code </#>} a solid colour run. {@code #RGB} shorthand works.</li>
 * </ul>
 * Tags nest, and legacy {@code §}-codes keep working inside and around them. Anything the
 * parser does not recognise - a bad hex value, an unknown tag name, a {@code <} with no
 * {@code >} - is emitted as literal text. {@code rich} never throws.
 *
 * <p>{@link #stripRich(String)} returns exactly the characters {@code rich} would render,
 * with every recognised tag and legacy code removed, for width measurement and logging.
 */
public class TextFx {

	private static final java.util.Map<String, Text> CACHE = new java.util.HashMap<>();
	private static final java.util.Map<String, Text> RICH_CACHE = new java.util.HashMap<>();

	public static final int GOLD = 0xFFC941;
	public static final int ORANGE = 0xFF7B2E;
	public static final int AQUA = 0x4FE3E0;
	public static final int BLUE = 0x3E7BFF;
	public static final int PINK = 0xFF6FD8;
	public static final int PURPLE = 0x9B4DFF;
	public static final int LIME = 0x8CFF5E;
	public static final int GREEN = 0x2ECC5B;
	public static final int RED = 0xFF5E5E;
	public static final int CREAM = 0xF5E6C8;

	private static final char SECTION = '§';

	/** Vanilla legacy colour codes 0-9 a-f. */
	private static final int[] LEGACY_COLOURS = {
			0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
			0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
	};

	// ---------------------------------------------------------------- existing API

	public static Text gradient(String text, int from, int to) {
		return gradient(text, from, to, true);
	}

	public static Text gradient(String text, int from, int to, boolean bold) {
		String key = text + "|" + from + "|" + to + "|" + bold;
		Text cached = CACHE.get(key);
		if (cached != null) return cached;

		MutableText result = Text.empty();
		int len = Math.max(1, text.length() - 1);
		for (int i = 0; i < text.length(); i++) {
			float t = (float) i / len;
			int r = lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
			int g = lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
			int b = lerp(from & 0xFF, to & 0xFF, t);
			Style style = Style.EMPTY.withColor(TextColor.fromRgb((r << 16) | (g << 8) | b)).withBold(bold);
			result.append(Text.literal(String.valueOf(text.charAt(i))).setStyle(style));
		}
		if (CACHE.size() < 256) {
			CACHE.put(key, result);
		}
		return result;
	}

	private static int lerp(int a, int b, float t) {
		return (int) (a + (b - a) * t);
	}

	// ---------------------------------------------------------------- rich markup

	/** Parses the tag markup described on this class into styled text. Never throws. */
	public static Text rich(String input) {
		if (input == null || input.isEmpty()) return Text.empty();
		Text cached = RICH_CACHE.get(input);
		if (cached != null) return cached;
		Text result;
		try {
			result = parse(input);
		} catch (Throwable t) {
			result = Text.literal(input);
		}
		if (RICH_CACHE.size() < 256) {
			RICH_CACHE.put(input, result);
		}
		return result;
	}

	/** The characters {@link #rich} would render: tags and legacy codes removed. */
	public static String stripRich(String input) {
		if (input == null || input.isEmpty()) return "";
		StringBuilder out = new StringBuilder(input.length());
		int i = 0;
		int n = input.length();
		while (i < n) {
			char c = input.charAt(i);
			if (c == SECTION && i + 1 < n && isLegacyCode(input.charAt(i + 1))) {
				i += 2;
				continue;
			}
			if (c == '<') {
				int gt = input.indexOf('>', i + 1);
				if (gt > i && canonical(input.substring(i + 1, gt)) != null) {
					i = gt + 1;
					continue;
				}
			}
			out.append(c);
			i++;
		}
		return out.toString();
	}

	// ---------------------------------------------------------------- parser

	/** Style state at one point in the string. */
	private static final class Ctx {
		boolean bold, italic, underline, strike, obfuscated;
		Integer color;
		int[] stops;
		int total = 1;
		int[] counter;

		Ctx copy() {
			Ctx c = new Ctx();
			c.bold = bold;
			c.italic = italic;
			c.underline = underline;
			c.strike = strike;
			c.obfuscated = obfuscated;
			c.color = color;
			c.stops = stops;
			c.total = total;
			c.counter = counter;
			return c;
		}
	}

	/** One open tag: the state to restore, plus its gradient counter when it has one. */
	private static final class Frame {
		final String name;
		final Ctx saved;
		final int[] counter;

		Frame(String name, Ctx saved, int[] counter) {
			this.name = name;
			this.saved = saved;
			this.counter = counter;
		}
	}

	/** The rendered characters of some markup plus the style of each one. */
	private static final class Layout {
		final String plain;
		final List<Style> styles;

		Layout(String plain, List<Style> styles) {
			this.plain = plain;
			this.styles = styles;
		}
	}

	private static Text parse(String s) {
		Layout layout = layout(s);
		MutableText out = Text.empty();
		StringBuilder buf = new StringBuilder();
		Style bufStyle = null;
		for (int i = 0; i < layout.plain.length(); i++) {
			Style style = layout.styles.get(i);
			if (bufStyle == null) {
				bufStyle = style;
			} else if (!bufStyle.equals(style)) {
				out.append(Text.literal(buf.toString()).setStyle(bufStyle));
				buf.setLength(0);
				bufStyle = style;
			}
			buf.append(layout.plain.charAt(i));
		}
		if (buf.length() > 0) {
			out.append(Text.literal(buf.toString()).setStyle(bufStyle == null ? Style.EMPTY : bufStyle));
		}
		return out;
	}

	private static Layout layout(String s) {
		StringBuilder plain = new StringBuilder(s.length());
		List<Style> styles = new ArrayList<>(s.length());
		Ctx cur = new Ctx();
		Deque<Frame> stack = new ArrayDeque<>();
		List<int[]> counters = new ArrayList<>();

		int i = 0;
		int n = s.length();
		while (i < n) {
			char c = s.charAt(i);

			// legacy section codes keep working, inside tags as well as outside
			if (c == SECTION && i + 1 < n && isLegacyCode(s.charAt(i + 1))) {
				applyLegacy(cur, Character.toLowerCase(s.charAt(i + 1)));
				i += 2;
				continue;
			}

			if (c == '<') {
				int gt = s.indexOf('>', i + 1);
				String body = gt > i ? s.substring(i + 1, gt) : null;
				String canon = body == null ? null : canonical(body);
				if (canon != null) {
					if (body.trim().startsWith("/")) {
						// unmatched closers are simply ignored, matching stripRich
						if (!stack.isEmpty() && stack.peek().name.equals(canon)) {
							Frame frame = stack.pop();
							cur = frame.saved;
							if (frame.counter != null) counters.remove(frame.counter);
						}
						i = gt + 1;
						continue;
					}
					Ctx next = cur.copy();
					int[] counter = null;
					if (canon.equals("gradient")) {
						int[] stops = parseStops(body);
						int close = findGradientClose(s, gt + 1);
						String inner = close >= 0 ? s.substring(gt + 1, close) : s.substring(gt + 1);
						counter = new int[]{0};
						next.stops = stops;
						next.total = Math.max(1, stripRich(inner).length());
						next.counter = counter;
						next.color = null;
						counters.add(counter);
					} else if (canon.equals("#")) {
						next.color = parseHex(body);
						next.stops = null;
						next.counter = null;
					} else {
						switch (canon) {
							case "b" -> next.bold = true;
							case "i" -> next.italic = true;
							case "u" -> next.underline = true;
							case "s" -> next.strike = true;
							default -> next.obfuscated = true;
						}
					}
					stack.push(new Frame(canon, cur, counter));
					cur = next;
					i = gt + 1;
					continue;
				}
				// unknown or malformed tag: fall through and emit '<' literally
			}

			int index = cur.counter != null ? cur.counter[0] : 0;
			plain.append(c);
			styles.add(styleOf(cur, index));
			// every open gradient advances, so an outer one resumes correctly after a nested tag
			for (int k = 0; k < counters.size(); k++) {
				counters.get(k)[0]++;
			}
			i++;
		}
		return new Layout(plain.toString(), styles);
	}

	private static Style styleOf(Ctx c, int index) {
		Style style = Style.EMPTY;
		Integer colour = c.color;
		if (colour == null && c.stops != null) colour = gradientAt(c.stops, index, c.total);
		if (colour != null) style = style.withColor(TextColor.fromRgb(colour));
		if (c.bold) style = style.withBold(true);
		if (c.italic) style = style.withItalic(true);
		if (c.underline) style = style.withUnderline(true);
		if (c.strike) style = style.withStrikethrough(true);
		if (c.obfuscated) style = style.withObfuscated(true);
		return style;
	}

	/** Colour of character {@code index} of {@code total}, spread evenly over the stops. */
	private static int gradientAt(int[] stops, int index, int total) {
		if (stops.length == 1 || total <= 1) return stops[0];
		float t = (float) index / (total - 1);
		if (t < 0f) t = 0f;
		if (t > 1f) t = 1f;
		float scaled = t * (stops.length - 1);
		int seg = (int) Math.floor(scaled);
		if (seg > stops.length - 2) seg = stops.length - 2;
		if (seg < 0) seg = 0;
		float local = scaled - seg;
		if (local < 0f) local = 0f;
		if (local > 1f) local = 1f;
		int from = stops[seg];
		int to = stops[seg + 1];
		int r = lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, local);
		int g = lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, local);
		int b = lerp(from & 0xFF, to & 0xFF, local);
		return (r << 16) | (g << 8) | b;
	}

	// ---------------------------------------------------------------- tag helpers

	/**
	 * Canonical name for a tag body, or null when the tag is unknown or malformed.
	 * Opening and closing forms map to the same name so they can be matched up:
	 * "b"/"bold" -> "b", "#FFD66A" and "/#" -> "#", "gradient:..." -> "gradient".
	 */
	private static String canonical(String body) {
		if (body == null || body.isEmpty()) return null;
		String lower = body.toLowerCase(Locale.ROOT).trim();
		boolean closing = lower.startsWith("/");
		if (closing) {
			lower = lower.substring(1).trim();
			if (lower.isEmpty()) return null;
			if (lower.equals("#")) return "#";
			if (lower.equals("gradient")) return "gradient";
		} else {
			if (lower.startsWith("gradient")) return parseStops(lower) != null ? "gradient" : null;
		}
		if (lower.charAt(0) == '#') return parseHex(lower) != null ? "#" : null;
		return switch (lower) {
			case "b", "bold" -> "b";
			case "i", "italic", "em" -> "i";
			case "u", "underline" -> "u";
			case "s", "strike", "strikethrough" -> "s";
			case "o", "obf", "obfuscated", "k" -> "o";
			default -> null;
		};
	}

	/** "gradient:#FFD66A:#FFE49D" -> the stop colours, or null when it is not valid. */
	private static int[] parseStops(String body) {
		String lower = body.toLowerCase(Locale.ROOT).trim();
		if (!lower.startsWith("gradient")) return null;
		String rest = lower.substring("gradient".length());
		if (!rest.startsWith(":")) return null;
		String[] parts = rest.substring(1).split(":");
		if (parts.length < 2) return null;
		int[] stops = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			Integer colour = parseHex(parts[i]);
			if (colour == null) return null;
			stops[i] = colour;
		}
		return stops;
	}

	/** "#FFD66A" or "#FA6" -> 0xFFD66A / 0xFFAA66. Null when it is not a hex colour. */
	private static Integer parseHex(String raw) {
		if (raw == null) return null;
		String t = raw.trim();
		if (t.startsWith("#")) t = t.substring(1);
		if (t.length() == 3) {
			StringBuilder sb = new StringBuilder(6);
			for (int i = 0; i < 3; i++) {
				sb.append(t.charAt(i)).append(t.charAt(i));
			}
			t = sb.toString();
		}
		if (t.length() != 6) return null;
		int value = 0;
		for (int i = 0; i < 6; i++) {
			int digit = Character.digit(t.charAt(i), 16);
			if (digit < 0) return null;
			value = (value << 4) | digit;
		}
		return value;
	}

	/** Index of the '<' of the {@code </gradient>} matching an already-open gradient. */
	private static int findGradientClose(String s, int from) {
		int depth = 0;
		int i = from;
		while (i < s.length()) {
			int lt = s.indexOf('<', i);
			if (lt < 0) return -1;
			int gt = s.indexOf('>', lt + 1);
			if (gt < 0) return -1;
			String tag = s.substring(lt + 1, gt).toLowerCase(Locale.ROOT).trim();
			if (tag.equals("/gradient")) {
				if (depth == 0) return lt;
				depth--;
			} else if (tag.startsWith("gradient:")) {
				depth++;
			}
			i = gt + 1;
		}
		return -1;
	}

	private static boolean isLegacyCode(char c) {
		char l = Character.toLowerCase(c);
		return (l >= '0' && l <= '9') || (l >= 'a' && l <= 'f')
				|| l == 'k' || l == 'l' || l == 'm' || l == 'n' || l == 'o' || l == 'r';
	}

	private static void applyLegacy(Ctx c, char code) {
		int colour = Character.digit(code, 16);
		if (colour >= 0 && colour <= 15) {
			c.color = LEGACY_COLOURS[colour];
			c.stops = null;
			c.counter = null;
			return;
		}
		switch (code) {
			case 'k' -> c.obfuscated = true;
			case 'l' -> c.bold = true;
			case 'm' -> c.strike = true;
			case 'n' -> c.underline = true;
			case 'o' -> c.italic = true;
			case 'r' -> {
				c.bold = false;
				c.italic = false;
				c.underline = false;
				c.strike = false;
				c.obfuscated = false;
				c.color = null;
				c.stops = null;
				c.counter = null;
			}
			default -> {
			}
		}
	}

	// ---------------------------------------------------------------- self test

	/**
	 * Hand-runnable check of the markup parser. Returns one line per case, prefixed
	 * OK or FAIL. Deliberately main-free: call it from a dev command or a breakpoint.
	 */
	public static String selfTest() {
		StringBuilder sb = new StringBuilder();
		String hud = "<b><gradient:#FFD66A:#FFE49D:#FFD66A>HUD</gradient></b>";
		check(sb, "strip hud", "HUD", stripRich(hud));
		check(sb, "strip trash", "TRASH",
				stripRich("<b><gradient:#FF6A6A:#FF9D9D:#FF6A6A>TRASH</gradient></b>"));
		check(sb, "strip legacy", "hi", stripRich("§ah§ri"));
		check(sb, "strip unknown", "<foo>x", stripRich("<foo>x"));
		check(sb, "strip bad hex", "<#GG0000>x", stripRich("<#GG0000>x"));
		check(sb, "strip solid", "x", stripRich("<#FF0000>x</#>"));
		check(sb, "hud text", "HUD", rich(hud).getString());
		check(sb, "hud left stop", "FFD66A", hex(colourAt(hud, 0)));
		check(sb, "hud mid stop", "FFE49D", hex(colourAt(hud, 1)));
		check(sb, "hud right stop", "FFD66A", hex(colourAt(hud, 2)));
		check(sb, "solid run", "FF0000", hex(colourAt("<#FF0000>ab</#>", 1)));
		check(sb, "legacy red", "FF5555", hex(colourAt("§cab", 1)));
		check(sb, "unknown literal", "<foo>x", rich("<foo>x").getString());
		check(sb, "unclosed lt", "a<b", rich("a<b").getString());
		return sb.toString();
	}

	/** Colour of the i-th rendered character of some markup, or null. */
	private static Integer colourAt(String markup, int index) {
		String plain = stripRich(markup);
		if (index < 0 || index >= plain.length()) return null;
		int[] seen = {0};
		Integer[] found = {null};
		rich(markup).visit((style, part) -> {
			for (int i = 0; i < part.length(); i++) {
				if (seen[0]++ == index && style.getColor() != null) {
					found[0] = style.getColor().getRgb() & 0xFFFFFF;
				}
			}
			return java.util.Optional.empty();
		}, Style.EMPTY);
		return found[0];
	}

	private static String hex(Integer value) {
		return value == null ? "null" : String.format(Locale.ROOT, "%06X", value);
	}

	private static void check(StringBuilder sb, String name, String expected, String actual) {
		boolean ok = expected == null ? actual == null : expected.equals(actual);
		sb.append(ok ? "OK   " : "FAIL ").append(name).append(" -> ").append(actual)
				.append(ok ? "" : " (expected " + expected + ")").append('\n');
	}
}
