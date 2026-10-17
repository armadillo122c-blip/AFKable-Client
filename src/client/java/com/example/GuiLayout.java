package com.example;

import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * GUI arrangement presets - "how the GUIs are set up".
 *
 * <p>Every screen in the mod currently hardcodes its panel size, header height, padding and
 * grid geometry. This enum owns those numbers instead, so a screen can ask
 * {@code GuiLayout.current().padding()} rather than typing {@code 12} eleven times.
 *
 * <p>Three presets ship:
 * <ul>
 *   <li>{@link #CLASSIC} - byte-for-byte the arrangement that exists today. Every metric is the
 *       literal already used by {@code ForgeCalcMenuScreen}, and the {@code scaleW}/{@code scaleH}
 *       helpers are hard short-circuited to the identity so CLASSIC can never drift by a rounding
 *       pixel.</li>
 *   <li>{@link #COMPACT} - ~240 wide, a slim 16px header with no subtitle room, tighter rows and
 *       shorter buttons. For players on GUI scale 3/4 who find the current panels enormous.</li>
 *   <li>{@link #SIDEBAR} - ~420 wide, split into a left navigation rail ({@link #sidebarWidth()})
 *       listing the sections vertically and a right content area. No footer at all: the footer
 *       actions move into the bottom of the rail, so {@link #footerHeight()} is 0.</li>
 * </ul>
 *
 * <p>The choice persists to {@code config/afkable-guilayout.properties} - its own file, not the
 * shared {@code ForgeCalcConfig} properties. {@link #current()} lazily loads on first use, so no
 * hook in the mod initialiser is required.
 */
public enum GuiLayout {

	/** The arrangement that exists today. Do not change these numbers - they are the live literals. */
	CLASSIC("Classic", "What the mod looks like today",
			"Roomy 300px panels, 4 tiles per row, footer button bar.",
			300, 302, 34, 30, 16, 1, 20, 18, 12, 8, 6, 10, 4, 62, 0, 14),

	/** Small, dense panels for players on a high GUI scale. */
	COMPACT("Compact", "Smaller, denser, less screen eaten",
			"240px panel, slim 16px header, 3 tiles per row, tight rows.",
			240, 248, 16, 22, 12, 1, 15, 13, 7, 4, 4, 5, 3, 46, 0, 10),

	/** Wide panel split into a vertical nav rail plus a content area. */
	SIDEBAR("Sidebar", "Wide panel with a left nav rail",
			"420px panel, sections listed vertically, content on the right, no footer.",
			420, 240, 22, 0, 18, 2, 18, 14, 10, 6, 6, 6, 1, 34, 104, 14);

	// ------------------------------------------------------------------ model

	/**
	 * An axis-aligned rectangle in screen pixels. Returned by the layout helpers so callers pass
	 * one value around instead of four loose ints that are easy to swap by accident.
	 */
	public record Rect(int x, int y, int w, int h) {
		public int right() {
			return x + w;
		}

		public int bottom() {
			return y + h;
		}

		public int centerX() {
			return x + w / 2;
		}

		public int centerY() {
			return y + h / 2;
		}

		public boolean contains(double mx, double my) {
			return mx >= x && mx <= x + w && my >= y && my <= y + h;
		}

		/** Shrinks the rect by {@code n} on all four sides (never past zero size). */
		public Rect inset(int n) {
			return new Rect(x + n, y + n, Math.max(0, w - n * 2), Math.max(0, h - n * 2));
		}
	}

	private final String label;
	private final String blurb;
	private final String detail;
	private final int panelWidth;
	private final int panelHeight;
	private final int headerHeight;
	private final int footerHeight;
	private final int rowHeight;
	private final int rowGap;
	private final int buttonHeight;
	private final int smallButtonHeight;
	private final int padding;
	private final int gap;
	private final int columnGap;
	private final int headerGap;
	private final int gridColumns;
	private final int tileHeight;
	private final int sidebarWidth;
	private final int scrollStep;

	GuiLayout(String label, String blurb, String detail,
	          int panelWidth, int panelHeight, int headerHeight, int footerHeight,
	          int rowHeight, int rowGap, int buttonHeight, int smallButtonHeight,
	          int padding, int gap, int columnGap, int headerGap,
	          int gridColumns, int tileHeight, int sidebarWidth, int scrollStep) {
		this.label = label;
		this.blurb = blurb;
		this.detail = detail;
		this.panelWidth = panelWidth;
		this.panelHeight = panelHeight;
		this.headerHeight = headerHeight;
		this.footerHeight = footerHeight;
		this.rowHeight = rowHeight;
		this.rowGap = rowGap;
		this.buttonHeight = buttonHeight;
		this.smallButtonHeight = smallButtonHeight;
		this.padding = padding;
		this.gap = gap;
		this.columnGap = columnGap;
		this.headerGap = headerGap;
		this.gridColumns = gridColumns;
		this.tileHeight = tileHeight;
		this.sidebarWidth = sidebarWidth;
		this.scrollStep = scrollStep;
	}

	// ------------------------------------------------------------------ metrics

	/** Display name, e.g. "Compact". */
	public String label() {
		return label;
	}

	/** One-line summary for the picker card. */
	public String blurb() {
		return blurb;
	}

	/** Longer sentence describing the geometry. */
	public String detail() {
		return detail;
	}

	/** Standard centred panel width. CLASSIC = 300, the literal in ForgeCalcMenuScreen. */
	public int panelWidth() {
		return panelWidth;
	}

	/** Standard centred panel height. CLASSIC = 302. */
	public int panelHeight() {
		return panelHeight;
	}

	/** Height of the title banner at the top of a panel. CLASSIC = 34. */
	public int headerHeight() {
		return headerHeight;
	}

	/** Height of the bottom action strip. 0 on SIDEBAR, whose actions live in the nav rail. */
	public int footerHeight() {
		return footerHeight;
	}

	/** Height of one list row (the toggle rows, button rows, log rows). CLASSIC = 16. */
	public int rowHeight() {
		return rowHeight;
	}

	/** Vertical spacing between list rows. */
	public int rowGap() {
		return rowGap;
	}

	/** Full stride of one list row, i.e. {@code rowHeight() + rowGap()}. */
	public int rowStride() {
		return rowHeight + rowGap;
	}

	/** Height of a primary drawBigButton. CLASSIC = 20. */
	public int buttonHeight() {
		return buttonHeight;
	}

	/** Height of a secondary/inline drawBigButton (tabs, back rows). CLASSIC = 18. */
	public int smallButtonHeight() {
		return smallButtonHeight;
	}

	/** Inset from the panel edge to its content. CLASSIC = 12. */
	public int padding() {
		return padding;
	}

	/** Spacing between grid cells. CLASSIC = 8. */
	public int gap() {
		return gap;
	}

	/** Spacing between side-by-side buttons in a row. CLASSIC = 6. */
	public int columnGap() {
		return columnGap;
	}

	/** Breathing room between the header rule and the first body element. CLASSIC = 10. */
	public int headerGap() {
		return headerGap;
	}

	/** Tiles per row in a tile grid. CLASSIC = 4. */
	public int gridColumns() {
		return gridColumns;
	}

	/** Height of one grid tile. CLASSIC = 62. */
	public int tileHeight() {
		return tileHeight;
	}

	/** True when the body is a left nav rail plus a right content area. */
	public boolean useSidebar() {
		return sidebarWidth > 0;
	}

	/** Width of the nav rail measured from the panel's left edge. 0 when {@link #useSidebar()} is false. */
	public int sidebarWidth() {
		return sidebarWidth;
	}

	/** Pixels to move a scissored list per mouse-wheel notch. CLASSIC = 14. */
	public int scrollStep() {
		return scrollStep;
	}

	/** True when the header is tall enough for a subtitle line under the title. */
	public boolean hasSubtitle() {
		return headerHeight >= 28;
	}

	/** Baseline for the panel title inside the header. CLASSIC = panelY + 8. */
	public int titleY(int panelY) {
		return panelY + (hasSubtitle() ? 8 : Math.max(1, (headerHeight - 8) / 2));
	}

	/** Baseline for the subtitle line. Only meaningful when {@link #hasSubtitle()}. CLASSIC = panelY + 20. */
	public int subtitleY(int panelY) {
		return panelY + Math.max(10, headerHeight - 14);
	}

	// ------------------------------------------------------------------ scaling

	/** Width of this preset relative to CLASSIC. 1.0 for CLASSIC, 0.8 COMPACT, 1.4 SIDEBAR. */
	public float widthScale() {
		return panelWidth / (float) CLASSIC.panelWidth;
	}

	/** Height of this preset relative to CLASSIC. */
	public float heightScale() {
		return panelHeight / (float) CLASSIC.panelHeight;
	}

	/**
	 * Maps a horizontal literal written for CLASSIC into this preset. Screens that are not the
	 * standard size - StatsScreen at 340, ButtonsScreen at 320 - migrate by wrapping their existing
	 * constant: {@code GuiLayout.current().scaleW(340)}. Returns the argument untouched on CLASSIC.
	 */
	public int scaleW(int classicValue) {
		if (this == CLASSIC) return classicValue;
		return Math.round(classicValue * widthScale());
	}

	/** Vertical counterpart of {@link #scaleW(int)}. Identity on CLASSIC. */
	public int scaleH(int classicValue) {
		if (this == CLASSIC) return classicValue;
		return Math.round(classicValue * heightScale());
	}

	/** Panel width for a screen whose CLASSIC width is not the standard 300. */
	public int panelWidth(int classicWidth) {
		return scaleW(classicWidth);
	}

	/** Panel height for a screen whose CLASSIC height is not the standard 302. */
	public int panelHeight(int classicHeight) {
		return scaleH(classicHeight);
	}

	// ------------------------------------------------------------------ derived geometry

	/**
	 * Splits an available width into {@code parts} equal buttons separated by {@link #columnGap()}.
	 * CLASSIC: {@code split(276, 2) == 135}, the same as the existing {@code (panelW - 30) / 2}.
	 */
	public int split(int availableWidth, int parts) {
		if (parts <= 1) return Math.max(0, availableWidth);
		return Math.max(0, (availableWidth - (parts - 1) * columnGap) / parts);
	}

	/** Width of one of two side-by-side footer buttons in a panel of {@code panelW}. CLASSIC = 135 at 300. */
	public int halfWidth(int panelW) {
		return split(panelW - padding * 2, 2);
	}

	/** Width of one grid tile inside a body of {@code bodyW}. CLASSIC: 276 -> 63. */
	public int tileWidth(int bodyW) {
		if (gridColumns <= 1) return Math.max(0, bodyW);
		return Math.max(0, (bodyW - (gridColumns - 1) * gap) / gridColumns);
	}

	/** Number of grid rows needed for {@code count} tiles. */
	public int gridRows(int count) {
		return (count + gridColumns - 1) / gridColumns;
	}

	/** The centred panel rect for the standard panel size. */
	public Rect panel(int screenW, int screenH) {
		return panel(screenW, screenH, CLASSIC.panelWidth, CLASSIC.panelHeight);
	}

	/**
	 * The centred panel rect for a screen with its own CLASSIC dimensions - pass the literals the
	 * screen uses today and CLASSIC hands them straight back.
	 */
	public Rect panel(int screenW, int screenH, int classicW, int classicH) {
		int w = scaleW(classicW);
		int h = scaleH(classicH);
		return new Rect(screenW / 2 - w / 2, screenH / 2 - h / 2, w, h);
	}

	/** Same as {@link #panel(int, int, int, int)} but never wider/taller than the window. */
	public Rect panelClamped(int screenW, int screenH, int classicW, int classicH) {
		int w = Math.min(scaleW(classicW), Math.max(120, screenW - 8));
		int h = Math.min(scaleH(classicH), Math.max(100, screenH - 8));
		return new Rect(screenW / 2 - w / 2, screenH / 2 - h / 2, w, h);
	}

	/** The header banner rect, spanning the full panel width. */
	public Rect header(Rect panel) {
		return new Rect(panel.x(), panel.y(), panel.w(), headerHeight);
	}

	/**
	 * The standard body rect: inset by {@link #padding()} horizontally, starting below the header
	 * plus {@link #headerGap()}, ending where the footer begins (or one padding above the bottom
	 * edge when the preset has no footer).
	 *
	 * <p>CLASSIC at 300x302 gives {@code x = px+12, y = py+44, w = 276, h = 228} - exactly the
	 * grid origin ForgeCalcMenuScreen computes by hand today.
	 */
	public Rect body(Rect panel) {
		int top = panel.y() + headerHeight + headerGap;
		int bottom = panel.bottom() - (footerHeight > 0 ? footerHeight : padding);
		return new Rect(panel.x() + padding, top, panel.w() - padding * 2, Math.max(0, bottom - top));
	}

	/** Convenience: the standard body rect for a centred standard-size panel. */
	public Rect body(int screenW, int screenH) {
		return body(panel(screenW, screenH));
	}

	/** The footer strip. Zero height when {@link #footerHeight()} is 0 (SIDEBAR). */
	public Rect footer(Rect panel) {
		return new Rect(panel.x() + padding, panel.bottom() - footerHeight,
				panel.w() - padding * 2, footerHeight);
	}

	/**
	 * The left navigation rail. Zero width unless {@link #useSidebar()}, so a screen can draw the
	 * rail unconditionally and simply get nothing on CLASSIC/COMPACT.
	 */
	public Rect navRail(Rect panel) {
		Rect body = body(panel);
		if (!useSidebar()) return new Rect(body.x(), body.y(), 0, body.h());
		return new Rect(body.x(), body.y(), sidebarWidth - padding, body.h());
	}

	/** The content area to the right of the nav rail. Equals {@link #body(Rect)} when there is no rail. */
	public Rect content(Rect panel) {
		Rect body = body(panel);
		if (!useSidebar()) return body;
		int x = panel.x() + sidebarWidth + gap;
		return new Rect(x, body.y(), Math.max(0, panel.right() - padding - x), body.h());
	}

	/** The rect of grid tile {@code index} laid out inside {@code body}. */
	public Rect tile(Rect body, int index) {
		int tw = tileWidth(body.w());
		int col = index % gridColumns;
		int row = index / gridColumns;
		return new Rect(body.x() + col * (tw + gap), body.y() + row * (tileHeight + gap), tw, tileHeight);
	}

	/** The rect of list row {@code index} laid out inside {@code body}, before scroll is applied. */
	public Rect row(Rect body, int index) {
		return new Rect(body.x(), body.y() + index * rowStride(), body.w(), rowHeight);
	}

	/**
	 * The clamped pixel-scroll the repo convention requires, in one call:
	 * {@code scroll = layout.scroll(scroll, pollScroll(), contentH, listH);}
	 * Never returns a negative value and never scrolls past the end of the content.
	 */
	public int scroll(int current, int wheelNotches, int contentH, int listH) {
		int maxScroll = Math.max(0, contentH - listH);
		return Math.max(0, Math.min(maxScroll, current - wheelNotches * scrollStep));
	}

	// ------------------------------------------------------------------ persistence

	private static final String FILE_NAME = "afkable-guilayout.properties";
	private static final String KEY = "layout";

	private static GuiLayout active;

	/** Where the preset is stored - its own file, never the shared ForgeCalcConfig properties. */
	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	/** The active preset. Lazily loads from disk on first call, so no initialiser hook is needed. */
	public static GuiLayout current() {
		if (active == null) load();
		return active;
	}

	/** Switches preset and writes it to disk immediately. Ignores null. */
	public static void set(GuiLayout layout) {
		if (layout == null) return;
		active = layout;
		save();
	}

	/** Cycles CLASSIC -> COMPACT -> SIDEBAR -> CLASSIC. Handy for a keybind. */
	public static GuiLayout next() {
		GuiLayout[] all = values();
		set(all[(current().ordinal() + 1) % all.length]);
		return current();
	}

	/** Resolves a stored id or display label back to a preset, falling back to CLASSIC. */
	public static GuiLayout fromId(String id) {
		if (id != null) {
			String trimmed = id.trim();
			for (GuiLayout layout : values()) {
				if (layout.name().equalsIgnoreCase(trimmed) || layout.label.equalsIgnoreCase(trimmed)) {
					return layout;
				}
			}
		}
		return CLASSIC;
	}

	/** Reads the preset file. Safe to call repeatedly; any failure falls back to CLASSIC. */
	public static void load() {
		active = CLASSIC;
		try {
			Path path = file();
			if (!Files.exists(path)) return;
			Properties props = new Properties();
			try (InputStream in = Files.newInputStream(path)) {
				props.load(in);
			}
			active = fromId(props.getProperty(KEY, CLASSIC.name()));
		} catch (Exception ignored) {
			active = CLASSIC;
		}
	}

	/** Writes the preset file. Never throws - a read-only config dir just means it does not stick. */
	public static void save() {
		try {
			Properties props = new Properties();
			props.setProperty(KEY, current().name());
			try (OutputStream out = Files.newOutputStream(file())) {
				props.store(out, "AFKABLE CLIENT - GUI layout preset (CLASSIC / COMPACT / SIDEBAR)");
			}
		} catch (Exception ignored) {
		}
	}
}
