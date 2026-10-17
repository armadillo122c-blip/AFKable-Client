package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Leaderboard comparison of up to six afkable.com profiles.
 *
 * <p>Names go into one comma-separated field. {@link StatsModels.MultiFetch} owns the daemon
 * threads and the result columns; this screen only polls them, so it never blocks and a batch that
 * lands after the user retyped the list is dropped by the generation stamp rather than drawn.
 *
 * <p>Every metric is one row and every player one column. The outright leader's cell is green, a
 * shared top is yellow and scores nobody, and the last row tallies the wins. Past three players the
 * columns switch to {@link StatsModels#compact} numbers and the strip scrolls sideways - shift plus
 * the wheel, the arrow keys, the two arrow buttons, or a click on the bar under the table.
 *
 * <p>A player who fails to load keeps their column: it greys out and carries the error, so one bad
 * name never costs you the rest of the comparison.
 */
public class StatsCompareScreen extends FarmScreen {

	private static final int PANEL_W = 340;
	private static final int PANEL_H = 250;
	private static final int BODY_W = 320;
	private static final int BODY_H = 148;

	/** Body height actually given to the table; the remainder carries the horizontal scrollbar. */
	private static final int LIST_H = BODY_H - 6;

	/** Fixed left gutter holding the metric names. Player columns start after it. */
	private static final int LABEL_W = 92;

	private final Screen parent;
	private TextFieldWidget namesField;
	private final String seed;

	private int serverTab = 0;
	private int scroll = 0;
	private int hscroll = 0;
	private int wheel;

	public StatsCompareScreen(Screen parent, String seedName) {
		super(Text.literal("Compare Stats"));
		this.parent = parent;
		this.seed = seedName == null ? "" : seedName.trim();
	}

	@Override
	protected void init() {
		int px = this.width / 2 - PANEL_W / 2;
		int py = this.height / 2 - PANEL_H / 2;

		String keep = namesField != null ? namesField.getText() : "";
		namesField = new TextFieldWidget(this.textRenderer, px + 10, py + 25, 160, 14, Text.literal(""));
		namesField.setMaxLength(120);
		namesField.setText(pick(keep, currentNames(), seed,
				PlayerStatsNet.slot(PlayerStatsNet.SLOT_A).query()));

		addSelectableChild(namesField);
		setInitialFocus(namesField);
	}

	/** Whatever batch is already loaded, so reopening the screen does not lose the comparison. */
	private static String currentNames() {
		List<StatsModels.MultiFetch.Column> columns = StatsModels.MultiFetch.columns();
		if (columns.isEmpty()) return "";
		StringBuilder out = new StringBuilder();
		for (StatsModels.MultiFetch.Column c : columns) {
			if (out.length() > 0) out.append(", ");
			out.append(c.name());
		}
		return out.toString();
	}

	private static String pick(String... options) {
		for (String s : options) if (s != null && !s.isBlank()) return s;
		return "";
	}

	/**
	 * Fills the field from the server tab list and compares everyone currently online.
	 * The tab list is the only online roster the client actually has; it is capped at
	 * MultiFetch.MAX and skips the entries the server uses as header/footer padding.
	 */
	private void vsAllOnline() {
		if (this.client == null || this.client.getNetworkHandler() == null) return;
		java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
		String self = this.client.player != null ? this.client.player.getName().getString() : null;
		if (self != null) names.add(self);
		try {
			for (net.minecraft.client.network.PlayerListEntry e
				: this.client.getNetworkHandler().getPlayerList()) {
				if (names.size() >= StatsModels.MultiFetch.MAX) break;
				if (e == null || e.getProfile() == null) continue;
				String name = e.getProfile().getName();
				// tab-list padding entries are blank or not valid usernames
				if (name == null || !name.matches("[A-Za-z0-9_]{3,16}")) continue;
				names.add(name);
			}
		} catch (Throwable ignored) {
		}
		if (names.isEmpty()) return;
		if (namesField != null) namesField.setText(String.join(", ", names));
		scroll = 0;
		StatsModels.MultiFetch.fetch(new java.util.ArrayList<>(names));
	}

	private void lookup() {
		if (namesField == null) return;
		List<String> names = StatsModels.MultiFetch.names(namesField.getText());
		if (names.isEmpty()) return;
		scroll = 0;
		hscroll = 0;
		serverTab = 0;
		StatsModels.MultiFetch.fetch(names);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER && namesField != null && namesField.isFocused()) {
			lookup();
			return true;
		}
		// arrow keys pan the columns whenever the field does not want them
		if (namesField == null || !namesField.isFocused()) {
			if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT) {
				hscroll -= 24;
				return true;
			}
			if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT) {
				hscroll += 24;
				return true;
			}
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	// =============================================================================== render

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();
		wheel = pollScroll();

		int px = this.width / 2 - PANEL_W / 2;
		int py = this.height / 2 - PANEL_H / 2;
		int bx = px + 10;
		int by = py + 74;

		drawWoodPanel(ctx, px, py, PANEL_W, PANEL_H);

		ctx.fill(px, py, px + PANEL_W, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + PANEL_W, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer,
				TextFx.gradient("COMPARE PLAYERS", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo),
				px + PANEL_W / 2, py + 7, CREAM);

		// ---- query row
		if (namesField != null) {
			namesField.setX(px + 10);
			namesField.setY(py + 25);
			namesField.setWidth(160);
			namesField.render(ctx, mouseX, mouseY, delta);
			if (namesField.getText().isEmpty() && !namesField.isFocused()) {
				StatsModels.text(ctx, this.textRenderer, "§8name, name, name ...", px + 14, py + 28);
			}
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 228, py + 25, 58, 14, "§a§lLOOK UP", null)) {
			lookup();
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 174, py + 25, 50, 14, "§b§lVS ALL", null)) {
			vsAllOnline();
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 290, py + 25, 16, 14, "§f⟳", null)) {
			StatsModels.MultiFetch.refresh();
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 310, py + 25, 16, 14, "§c✕", null)) {
			close();
			return;
		}

		List<StatsModels.MultiFetch.Column> columns = StatsModels.MultiFetch.columns();

		// ---- shared server tab strip, built from every player who loaded
		List<String> ids = new ArrayList<>(sharedServerIds(columns));
		if (!ids.isEmpty()) {
			if (serverTab >= ids.size()) serverTab = 0;
			int tabW = BODY_W / ids.size();
			for (int i = 0; i < ids.size(); i++) {
				int tx = bx + i * tabW;
				boolean active = i == serverTab;
				boolean hovered = hover(mouseX, mouseY, tx, py + 42, tabW - 2, 14);
				ctx.fill(tx, py + 42, tx + tabW - 2, py + 56,
						active ? GREEN_DARK : (hovered ? 0x40FFFFFF : 0x30000000));
				if (active) ctx.fill(tx, py + 54, tx + tabW - 2, py + 56, GREEN);
				String label = StatsModels.serverLabel(ids.get(i));
				if (this.textRenderer.getWidth(label) > tabW - 8) label = StatsModels.serverShort(ids.get(i));
				ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal((active ? "§f§l" : "§7") + label),
						tx + (tabW - 2) / 2, py + 45, 0xFFFFFFFF);
				if (hovered && mouseClickedThisFrame) {
					mouseClickedThisFrame = false;
					playClick(this.client);
					serverTab = i;
					scroll = 0;
				}
			}
		}

		// ---- body
		String status = statusMessage(columns);
		if (status != null) {
			ctx.fill(bx, py + 58, bx + BODY_W, py + 72, 0x30000000);
			ctx.fill(bx, by, bx + BODY_W, by + BODY_H, 0x20000000);
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status),
					bx + BODY_W / 2, by + BODY_H / 2 - 10, 0xFFFFFFFF);
			ctx.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal("§8up to " + StatsModels.MultiFetch.MAX + " names, comma separated"),
					bx + BODY_W / 2, by + BODY_H / 2 + 4, 0xFFFFFFFF);
		} else {
			drawLeaderboard(ctx, mouseX, mouseY, bx, py, by, columns,
					ids.isEmpty() ? "" : ids.get(serverTab));
		}

		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + 226, 316, 18, "§f§lBACK", null)) {
			close();
		}
	}

	private LinkedHashSet<String> sharedServerIds(List<StatsModels.MultiFetch.Column> columns) {
		LinkedHashSet<String> ids = new LinkedHashSet<>();
		for (StatsModels.MultiFetch.Column column : columns) {
			for (StatsModels.ServerProfile s : column.servers()) ids.add(s.id());
		}
		return ids;
	}

	/** Null when at least one column has data and the table can be drawn. */
	private String statusMessage(List<StatsModels.MultiFetch.Column> columns) {
		if (columns.isEmpty()) return "§7Enter names to compare";
		if (StatsModels.MultiFetch.busy()) {
			String dots = ".".repeat((int) ((System.currentTimeMillis() / 300) % 4));
			return "§7Loading " + columns.size() + " player" + (columns.size() == 1 ? "" : "s") + dots;
		}
		if (!StatsModels.MultiFetch.anyReady()) {
			StatsModels.MultiFetch.Column first = columns.get(0);
			return "§cNo player loaded §8- " + first.name() + ": " + first.error();
		}
		return null;
	}

	// =============================================================================== leaderboard

	private void drawLeaderboard(DrawContext ctx, int mouseX, int mouseY, int bx, int py, int by,
	                             List<StatsModels.MultiFetch.Column> columns, String serverId) {
		int n = columns.size();
		int visW = BODY_W - LABEL_W;
		boolean compactText = n > 3;
		int colW = compactText ? 52 : Math.min(100, Math.max(64, visW / Math.max(1, n)));
		int contentW = n * colW;
		int maxHScroll = Math.max(0, contentW - visW);

		// shift + wheel pans sideways, a plain wheel scrolls the rows.
		// shiftDown() reads GLFW directly: Screen.hasShiftDown() exists in 1.21.8 but was
		// removed by 1.21.11, and this jar is run on both.
		if (shiftDown() && maxHScroll > 0) {
			hscroll -= wheel * 14;
			wheel = 0;
		}
		hscroll = Math.max(0, Math.min(maxHScroll, hscroll));

		List<StatsModels.ServerProfile> profiles = new ArrayList<>(n);
		for (StatsModels.MultiFetch.Column column : columns) profiles.add(column.server(serverId));
		StatsModels.MultiResult result = StatsModels.compareAll(profiles, compactText);
		int leader = result.leader();

		drawColumnHeaders(ctx, mouseX, mouseY, bx, py, columns, result, colW, visW, maxHScroll, leader);

		// ---- flatten into rows
		List<Row> rows = new ArrayList<>();
		String category = null;
		for (StatsModels.MultiRow row : result.rows()) {
			if (!row.metric().category().equals(category)) {
				category = row.metric().category();
				rows.add(new Row(Row.HEADER, category, null));
			}
			rows.add(new Row(Row.METRIC, row.metric().label(), row));
		}
		rows.add(new Row(Row.WINS, "WINS", null));

		int contentH = 0;
		for (Row row : rows) contentH += row.height();
		int maxScroll = Math.max(0, contentH - LIST_H);
		scroll = Math.max(0, Math.min(maxScroll, scroll - wheel * 14));

		// pass 1: row backgrounds and the pinned metric labels
		ctx.enableScissor(bx, by, bx + BODY_W, by + LIST_H);
		int y = by - scroll;
		for (Row row : rows) {
			int h = row.height();
			if (y + h >= by && y <= by + LIST_H) drawRowLabel(ctx, bx, y, row, result);
			y += h;
		}
		ctx.disableScissor();

		// pass 2: the player cells, clipped to the scrolling column strip
		ctx.enableScissor(bx + LABEL_W, by, bx + BODY_W, by + LIST_H);
		for (int i = 0; i < n; i++) {
			int cx = bx + LABEL_W + i * colW - hscroll;
			StatsModels.MultiFetch.Column column = columns.get(i);
			if (i % 2 == 1) ctx.fill(cx, by, cx + colW - 1, by + LIST_H, 0x14FFFFFF);
			if (column.failed() || column.profile() == null) {
				// greyed out but still present, so the table keeps its shape
				ctx.fill(cx, by, cx + colW - 1, by + LIST_H, 0x40000000);
				ctx.drawCenteredTextWithShadow(this.textRenderer,
						Text.literal(StatsModels.fit(this.textRenderer, "§c" + column.error(), colW - 6)),
						cx + colW / 2, by + 4, 0xFFFFFFFF);
			} else if (profiles.get(i) == null) {
				ctx.fill(cx, by, cx + colW - 1, by + LIST_H, 0x30000000);
				ctx.drawCenteredTextWithShadow(this.textRenderer,
						Text.literal(StatsModels.fit(this.textRenderer, "§8not on this", colW - 6)),
						cx + colW / 2, by + 4, 0xFFFFFFFF);
				ctx.drawCenteredTextWithShadow(this.textRenderer,
						Text.literal(StatsModels.fit(this.textRenderer, "§8season", colW - 6)),
						cx + colW / 2, by + 14, 0xFFFFFFFF);
			}
		}
		y = by - scroll;
		for (Row row : rows) {
			int h = row.height();
			if (y + h >= by && y <= by + LIST_H) {
				drawRowCells(ctx, bx, y, row, result, columns, profiles, colW, leader);
			}
			y += h;
		}
		// column rules on top so the cells read as a grid
		for (int i = 1; i < n; i++) {
			int cx = bx + LABEL_W + i * colW - hscroll;
			ctx.fill(cx - 1, by, cx, by + LIST_H, 0x30000000);
		}
		ctx.disableScissor();

		StatsModels.scrollbar(ctx, bx + BODY_W, by, LIST_H, contentH, scroll, maxScroll);

		// ---- horizontal bar, clickable
		int barY = by + LIST_H + 2;
		StatsModels.hscrollbar(ctx, bx + LABEL_W, barY, visW, contentW, hscroll, maxHScroll);
		if (maxHScroll > 0 && mouseClickedThisFrame
				&& hover(mouseX, mouseY, bx + LABEL_W, barY - 2, visW, 7)) {
			mouseClickedThisFrame = false;
			hscroll = StatsModels.hscrollFromClick(mouseX, bx + LABEL_W, visW, contentW, maxHScroll);
		}
	}

	private void drawColumnHeaders(DrawContext ctx, int mouseX, int mouseY, int bx, int py,
	                               List<StatsModels.MultiFetch.Column> columns,
	                               StatsModels.MultiResult result, int colW, int visW,
	                               int maxHScroll, int leader) {
		int hy = py + 58;
		ctx.fill(bx, hy, bx + BODY_W, hy + 14, 0x30000000);
		StatsModels.text(ctx, this.textRenderer, "§7PLAYERS", bx + 3, hy + 3);

		if (maxHScroll > 0) {
			if (arrow(ctx, mouseX, mouseY, bx + LABEL_W - 22, hy + 2, "◀")) hscroll -= 40;
			if (arrow(ctx, mouseX, mouseY, bx + LABEL_W - 11, hy + 2, "▶")) hscroll += 40;
		}

		ctx.enableScissor(bx + LABEL_W, hy, bx + BODY_W, hy + 14);
		for (int i = 0; i < columns.size(); i++) {
			StatsModels.MultiFetch.Column column = columns.get(i);
			int cx = bx + LABEL_W + i * colW - hscroll;
			boolean won = i == leader;
			if (won) ctx.fill(cx, hy, cx + colW - 1, hy + 14, 0x4055FF55);
			String colour = column.failed() ? "§8" : (won ? "§a§l" : "§f");
			ctx.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal(StatsModels.fit(this.textRenderer, colour + column.displayName(), colW - 6)),
					cx + colW / 2, hy + 3, 0xFFFFFFFF);
			if (i > 0) ctx.fill(cx - 1, hy, cx, hy + 14, 0x30000000);
		}
		ctx.disableScissor();
	}

	/** Small square nudge button; returns true on the frame it is clicked. */
	private boolean arrow(DrawContext ctx, int mouseX, int mouseY, int x, int y, String glyph) {
		boolean hovered = hover(mouseX, mouseY, x, y, 10, 10);
		ctx.fill(x, y, x + 10, y + 10, hovered ? GREEN_DARK : 0x40000000);
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§f" + glyph), x + 5, y + 1, 0xFFFFFFFF);
		boolean clicked = hovered && mouseClickedThisFrame;
		if (clicked) {
			mouseClickedThisFrame = false;
			playClick(this.client);
		}
		return clicked;
	}

	private void drawRowLabel(DrawContext ctx, int bx, int y, Row row, StatsModels.MultiResult result) {
		switch (row.kind) {
			case Row.HEADER -> StatsModels.sectionHeader(ctx, this.textRenderer, bx, y, BODY_W - 4, row.label);
			case Row.WINS -> {
				ctx.fill(bx, y + 2, bx + BODY_W - 4, y + 16, 0x50000000);
				ctx.fill(bx, y + 2, bx + 2, y + 16, ForgeCalcTheme.current().accent);
				StatsModels.text(ctx, this.textRenderer, "§6§lWINS", bx + 6, y + 6);
			}
			default -> {
				ctx.fill(bx, y, bx + BODY_W - 4, y + 10, 0x10FFFFFF);
				StatsModels.text(ctx, this.textRenderer,
						StatsModels.fit(this.textRenderer, "§7" + row.label, LABEL_W - 8), bx + 4, y + 1);
			}
		}
	}

	private void drawRowCells(DrawContext ctx, int bx, int y, Row row, StatsModels.MultiResult result,
	                          List<StatsModels.MultiFetch.Column> columns,
	                          List<StatsModels.ServerProfile> profiles, int colW, int leader) {
		if (row.kind == Row.HEADER) return;

		for (int i = 0; i < columns.size(); i++) {
			int cx = bx + LABEL_W + i * colW - hscroll;
			int centre = cx + colW / 2;

			if (row.kind == Row.WINS) {
				int wins = i < result.wins().length ? result.wins()[i] : 0;
				boolean won = i == leader;
				if (won) ctx.fill(cx, y + 2, cx + colW - 1, y + 16, 0x4055FF55);
				String text = columns.get(i).failed() ? "-" : String.valueOf(wins);
				ctx.drawCenteredTextWithShadow(this.textRenderer,
						Text.literal((columns.get(i).failed() ? "§8" : won ? "§a§l" : "§f") + text),
						centre, y + 6, 0xFFFFFFFF);
				continue;
			}

			StatsModels.MultiRow metric = row.row;
			if (metric == null) continue;
			boolean dead = columns.get(i).failed() || profiles.get(i) == null;
			boolean best = !dead && metric.best()[i];
			String value = dead ? "-" : metric.texts()[i];
			String colour;
			if (dead || "-".equals(value)) {
				colour = "§8";
			} else if (best && !metric.tied()) {
				colour = "§a";
				ctx.fill(cx, y, cx + colW - 1, y + 10, 0x3355FF55);
			} else if (best) {
				colour = "§e"; // shared top - shown, but nobody scores it
			} else {
				colour = "§f";
			}
			ctx.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal(StatsModels.fit(this.textRenderer, colour + value, colW - 6)),
					centre, y + 1, 0xFFFFFFFF);
		}
	}

	/** One drawable entry in the leaderboard. */
	private static final class Row {
		static final int HEADER = 0;
		static final int METRIC = 1;
		static final int WINS = 2;

		final int kind;
		final String label;
		final StatsModels.MultiRow row;

		Row(int kind, String label, StatsModels.MultiRow row) {
			this.kind = kind;
			this.label = label;
			this.row = row;
		}

		int height() {
			return switch (kind) {
				case HEADER -> 13;
				case WINS -> 20;
				default -> 11;
			};
		}
	}

	@Override
	public void close() {
		if (this.client != null) this.client.setScreen(parent);
	}
}
