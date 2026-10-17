package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The full history view for {@link LiveActivity}.
 *
 * <p>The HUD panel only shows the last handful of un-expired events; this screen shows
 * everything still held in the ring buffer, with a per-kind filter, a text search and a
 * (confirmed) clear.
 *
 * <p>The filter chips drive {@link LiveActivity#setEnabled} - the same flag the HUD reads -
 * so a kind hidden here is hidden on the HUD too. That is deliberate: there is one "do I
 * care about this kind of event" switch, and this is the screen where you flip it.
 */
public class LiveActivityScreen extends FarmScreen {

	private static final int PANEL_W = 340;
	private static final int PANEL_H = 252;
	/** One list row. Also the scroll step, so a wheel notch moves exactly one row. */
	private static final int ROW_H = 14;
	private static final int CHIP_H = 13;
	private static final int CHIP_GAP = 3;
	/** How long the CLEAR button stays armed after the first click. */
	private static final long CONFIRM_MS = 2600;
	private static final long COPIED_MS = 1500;

	private final Screen parent;
	private TextFieldWidget searchField;
	private int scroll = 0;
	private String lastQuery = "";
	private long confirmClearAt = 0;
	private long copiedAt = 0;

	public LiveActivityScreen(Screen parent) {
		super(Text.literal("Live Activity"));
		this.parent = parent;
		LiveActivity.load();
	}

	@Override
	protected void init() {
		int px = this.width / 2 - PANEL_W / 2;
		int py = this.height / 2 - PANEL_H / 2;
		// resize() recreates widgets, so carry the typed text across
		String previousText = searchField != null ? searchField.getText() : "";
		searchField = new TextFieldWidget(this.textRenderer, px + 56, py + 25, 150, 13, Text.literal(""));
		searchField.setMaxLength(40);
		if (!previousText.isEmpty()) searchField.setText(previousText);
		addSelectableChild(searchField);
		setFocused(searchField);
	}

	// ---------- model ----------

	private String query() {
		return searchField != null ? searchField.getText().trim().toLowerCase(Locale.ROOT) : "";
	}

	/** Newest first, minus hidden kinds and anything the search does not match. */
	private List<LiveActivity.Entry> visibleRows(String query) {
		List<LiveActivity.Entry> out = new ArrayList<>();
		for (LiveActivity.Entry entry : LiveActivity.snapshot()) {
			if (entry == null || !LiveActivity.isEnabled(entry.kind())) continue;
			if (!query.isEmpty()) {
				boolean hit = entry.plain().toLowerCase(Locale.ROOT).contains(query)
						|| entry.kind().label.toLowerCase(Locale.ROOT).contains(query);
				if (!hit) continue;
			}
			out.add(entry);
		}
		return out;
	}

	private static boolean anyKindHidden() {
		for (LiveActivity.Kind kind : LiveActivity.Kind.values()) {
			if (!LiveActivity.isEnabled(kind)) return true;
		}
		return false;
	}

	private void setAllKinds(boolean value) {
		for (LiveActivity.Kind kind : LiveActivity.Kind.values()) {
			LiveActivity.setEnabled(kind, value);
		}
		LiveActivity.save();
		scroll = 0;
	}

	// ---------- render ----------

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int px = this.width / 2 - PANEL_W / 2;
		int py = this.height / 2 - PANEL_H / 2;

		drawWoodPanel(ctx, px, py, PANEL_W, PANEL_H);
		ctx.fill(px, py, px + PANEL_W, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + PANEL_W, py + 23, FRAME_DARK);
		ctx.drawItem(new ItemStack(Items.WRITABLE_BOOK), px + 5, py + 3);
		ctx.drawCenteredTextWithShadow(this.textRenderer,
				TextFx.gradient("ACTIVITY LOG", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo),
				px + PANEL_W / 2, py + 7, CREAM);

		// ---- search row ----
		ctx.drawText(this.textRenderer, Text.literal("§7Search:"), px + 12, py + 29, 0xFFFFFFFF, true);
		if (searchField != null) {
			searchField.render(ctx, mouseX, mouseY, delta);
		}

		String query = query();
		if (!query.equals(lastQuery)) {
			// the row count just changed under us - start again from the top
			lastQuery = query;
			scroll = 0;
		}

		int total = LiveActivity.size();
		List<LiveActivity.Entry> rows = visibleRows(query);

		String count = System.currentTimeMillis() - copiedAt < COPIED_MS
				? "§a✔ copied to clipboard"
				: "§7showing §f" + rows.size() + " §7of §f" + total;
		ctx.drawText(this.textRenderer, Text.literal(count),
				px + PANEL_W - 12 - this.textRenderer.getWidth(count), py + 29, 0xFFFFFFFF, true);

		// ---- filter chips (flow layout, wraps as many rows as it needs) ----
		int chipX = px + 12;
		int chipY = py + 44;
		int chipRight = px + PANEL_W - 12;
		LiveActivity.Kind hoveredChip = null;
		LiveActivity.Kind clickedChip = null;

		for (LiveActivity.Kind kind : LiveActivity.Kind.values()) {
			String label = kind.glyph + " " + kind.label;
			int w = this.textRenderer.getWidth(label) + 10;
			if (chipX + w > chipRight) {
				chipX = px + 12;
				chipY += CHIP_H + CHIP_GAP;
			}
			boolean on = LiveActivity.isEnabled(kind);
			boolean hovered = hover(mouseX, mouseY, chipX, chipY, w, CHIP_H);
			if (hovered) hoveredChip = kind;

			int body = on ? blend(FRAME_DARK, kind.color, 0.38f) : blend(FRAME_DARK, 0xFF000000, 0.35f);
			if (hovered) body = blend(body, 0xFFFFFFFF, 0.15f);
			ctx.fill(chipX - 1, chipY - 1, chipX + w + 1, chipY + CHIP_H + 1, on ? kind.color : BORDER);
			ctx.fill(chipX, chipY, chipX + w, chipY + CHIP_H, body);
			if (on) {
				ctx.fill(chipX, chipY, chipX + w, chipY + 1, kind.color);
			}
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(label), chipX + w / 2, chipY + 3,
					on ? blend(kind.color, 0xFFFFFFFF, 0.5f) : 0xFF6E6E6E);

			if (hovered && mouseClickedThisFrame) {
				clickedChip = kind;
			}
			chipX += w + CHIP_GAP;
		}

		// ALL / NONE live in the same flow so they never overlap the kind chips
		String[] bulk = {"ALL", "NONE"};
		int bulkClicked = -1;
		for (int i = 0; i < bulk.length; i++) {
			int w = this.textRenderer.getWidth(bulk[i]) + 12;
			if (chipX + w > chipRight) {
				chipX = px + 12;
				chipY += CHIP_H + CHIP_GAP;
			}
			boolean hovered = hover(mouseX, mouseY, chipX, chipY, w, CHIP_H);
			ctx.fill(chipX - 1, chipY - 1, chipX + w + 1, chipY + CHIP_H + 1, hovered ? GREEN : BORDER);
			ctx.fill(chipX, chipY, chipX + w, chipY + CHIP_H,
					hovered ? blend(PLANK_DARK, 0xFFFFFFFF, 0.14f) : PLANK_DARK);
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(bulk[i]), chipX + w / 2, chipY + 3,
					i == 0 ? 0xFF9BE3A0 : 0xFFE39B9B);
			if (hovered && mouseClickedThisFrame) {
				bulkClicked = i;
			}
			chipX += w + CHIP_GAP;
		}

		int chipsBottom = chipY + CHIP_H;

		if (clickedChip != null) {
			mouseClickedThisFrame = false;
			playClick(this.client);
			LiveActivity.toggle(clickedChip);
			LiveActivity.save();
			scroll = 0;
			return;
		}
		if (bulkClicked >= 0) {
			mouseClickedThisFrame = false;
			playClick(this.client);
			setAllKinds(bulkClicked == 0);
			return;
		}

		// ---- list ----
		int listX = px + 12;
		int listY = chipsBottom + 6;
		int listW = PANEL_W - 34;
		int listH = Math.max(20, (py + PANEL_H - 30) - listY);

		ctx.fill(listX - 1, listY - 1, listX + listW + 1, listY + listH + 1, FRAME_DARK);
		ctx.fill(listX, listY, listX + listW, listY + listH, 0x50000000);

		// pixel scrolling, re-clamped every frame so a shrinking list cannot strand us
		int contentH = rows.size() * ROW_H;
		int maxScroll = Math.max(0, contentH - listH);
		int step = shiftDown() ? ROW_H * 4 : ROW_H;
		scroll = Math.max(0, Math.min(maxScroll, scroll - pollScroll() * step));

		LiveActivity.Entry hoveredEntry = null;
		boolean inList = hover(mouseX, mouseY, listX, listY, listW, listH);

		if (rows.isEmpty()) {
			drawEmptyState(ctx, mouseX, mouseY, listX, listY, listW, listH, total, query);
		} else {
			ctx.enableScissor(listX, listY, listX + listW, listY + listH);
			int rowY = listY - scroll;
			int timeW = this.textRenderer.getWidth("00:00:00");
			for (int i = 0; i < rows.size(); i++) {
				LiveActivity.Entry entry = rows.get(i);
				if (rowY + ROW_H >= listY && rowY <= listY + listH) {
					boolean hovered = inList && hover(mouseX, mouseY, listX, rowY, listW, ROW_H - 1);
					if (hovered) hoveredEntry = entry;

					ctx.fill(listX, rowY, listX + listW, rowY + ROW_H - 1,
							hovered ? 0x38FFFFFF : (i % 2 == 0 ? 0x18FFFFFF : 0x0CFFFFFF));
					ctx.fill(listX, rowY, listX + 2, rowY + ROW_H - 1, entry.kind().color);

					ctx.drawText(this.textRenderer, Text.literal(entry.kind().glyph), listX + 6, rowY + 3,
							entry.kind().color, true);
					ctx.drawText(this.textRenderer, Text.literal(LiveActivity.clock(entry.time())),
							listX + 16, rowY + 3, 0xFF8C8C8C, true);

					int textX = listX + 20 + timeW;
					String body = LiveActivity.fit(this.textRenderer, entry.plain(),
							Math.max(20, listX + listW - 6 - textX));
					ctx.drawText(this.textRenderer, Text.literal(body), textX, rowY + 3, 0xFFF0E8DC, true);

					if (hovered && mouseClickedThisFrame) {
						mouseClickedThisFrame = false;
						playClick(this.client);
						copyToClipboard(entry);
					}
				}
				rowY += ROW_H;
			}
			ctx.disableScissor();
		}

		// ---- scrollbar ----
		int trackX = px + PANEL_W - 9;
		ctx.fill(trackX, listY, trackX + 3, listY + listH, 0x50000000);
		if (maxScroll > 0) {
			int thumbH = Math.max(14, listH * listH / Math.max(1, contentH));
			int thumbY = listY + (listH - thumbH) * scroll / maxScroll;
			ctx.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, GREEN);
		}

		// ---- footer ----
		boolean confirming = System.currentTimeMillis() - confirmClearAt < CONFIRM_MS;
		int half = (PANEL_W - 30) / 2;
		int footY = py + PANEL_H - 24;

		if (drawBigButton(ctx, mouseX, mouseY, px + 12, footY, half, 18, "§f§lBACK", null)) {
			close();
			return;
		}
		String clearLabel = total == 0 ? "§8§lCLEAR" : (confirming ? "§4§lCONFIRM?" : "§c§lCLEAR");
		if (drawBigButton(ctx, mouseX, mouseY, px + 18 + half, footY, half, 18, clearLabel, null)) {
			if (total == 0) {
				confirmClearAt = 0;
			} else if (confirming) {
				LiveActivity.clear();
				confirmClearAt = 0;
				scroll = 0;
			} else {
				confirmClearAt = System.currentTimeMillis();
			}
			return;
		}
		if (confirming && total > 0) {
			ctx.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal("§cClick again to wipe all " + total + " events"),
					px + PANEL_W / 2, py + PANEL_H + 6, 0xFFFFFFFF);
		}

		// ---- tooltips, drawn last so they sit on top of everything ----
		if (hoveredChip != null) {
			drawInfoTooltip(ctx, mouseX, mouseY, List.of(
					(LiveActivity.isEnabled(hoveredChip) ? "§a" : "§8") + hoveredChip.glyph + " " + hoveredChip.label,
					LiveActivity.isEnabled(hoveredChip) ? "§7Click to hide this kind" : "§7Click to show this kind",
					"§8Also applies to the HUD feed"));
		} else if (hoveredEntry != null) {
			List<String> lines = new ArrayList<>();
			lines.add("§f" + hoveredEntry.kind().glyph + " " + hoveredEntry.kind().label);
			for (String line : wrap(hoveredEntry.plain(), 190)) {
				lines.add("§7" + line);
			}
			lines.add("§8" + LiveActivity.clock(hoveredEntry.time())
					+ "  (" + LiveActivity.relative(System.currentTimeMillis() - hoveredEntry.time()) + ")");
			lines.add("§8Click to copy");
			drawInfoTooltip(ctx, mouseX, mouseY, lines);
		}
	}

	/** Never a blank panel: says what is missing and how to get rows back. */
	private void drawEmptyState(DrawContext ctx, int mouseX, int mouseY,
			int listX, int listY, int listW, int listH, int total, String query) {
		int cx = listX + listW / 2;
		int cy = listY + listH / 2;
		boolean filtered = total > 0;

		if (!filtered) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7Nothing has happened yet."), cx, cy - 18, 0xFFFFFFFF);
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8Ore finds, restocks, buffs, boosts and"), cx, cy - 4, 0xFFFFFFFF);
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8dominions all land here as they happen."), cx, cy + 8, 0xFFFFFFFF);
			return;
		}

		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7No events match that filter."), cx, cy - 22, 0xFFFFFFFF);
		String why = !query.isEmpty() && anyKindHidden()
				? "§8The search and the hidden kinds are both trimming it."
				: (!query.isEmpty() ? "§8Nothing here contains \"" + query + "\"." : "§8Every kind is switched off.");
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(why), cx, cy - 8, 0xFFFFFFFF);
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8" + total + " event(s) are hidden."), cx, cy + 4, 0xFFFFFFFF);

		if (drawBigButton(ctx, mouseX, mouseY, cx - 55, cy + 18, 110, 16, "§e§lRESET FILTERS", null)) {
			if (searchField != null) searchField.setText("");
			lastQuery = "";
			setAllKinds(true);
		}
	}

	private void copyToClipboard(LiveActivity.Entry entry) {
		try {
			if (this.client != null) {
				this.client.keyboard.setClipboard(LiveActivity.clock(entry.time()) + "  " + entry.plain());
				copiedAt = System.currentTimeMillis();
			}
		} catch (Throwable ignored) {
		}
	}

	/** Word-wraps for the tooltip, which sizes itself off the longest line. */
	private List<String> wrap(String text, int maxWidth) {
		List<String> out = new ArrayList<>();
		if (text == null || text.isEmpty()) return out;
		StringBuilder line = new StringBuilder();
		for (String word : text.split("\\s+")) {
			if (word.isEmpty()) continue;
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (line.length() > 0 && this.textRenderer.getWidth(candidate) > maxWidth) {
				out.add(LiveActivity.fit(this.textRenderer, line.toString(), maxWidth));
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(candidate);
			}
			if (out.size() >= 5) break;
		}
		if (line.length() > 0 && out.size() < 6) {
			out.add(LiveActivity.fit(this.textRenderer, line.toString(), maxWidth));
		}
		return out;
	}

	@Override
	public void close() {
		LiveActivity.save();
		if (this.client != null) {
			this.client.setScreen(parent);
		}
	}
}
