package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Picker for the {@link GuiLayout} presets.
 *
 * <p>Each preset gets a card with a live thumbnail: a miniature of that preset's own arrangement,
 * drawn from its own metrics with {@code ctx.fill} at true proportions - no screenshots, nothing
 * baked. A highlight walks the tiles/rows so the thumbnail reads as alive rather than static.
 *
 * <p>The screen also lays *itself* out with {@link GuiLayout#current()}, so picking SIDEBAR
 * immediately rearranges this very panel into a nav rail plus content area. That is the fastest
 * honest preview there is.
 */
public class GuiLayoutScreen extends FarmScreen {

	/** CLASSIC-era dimensions for this screen; every preset scales them from here. */
	private static final int BASE_W = 336;
	private static final int BASE_H = 236;

	private final Screen parent;

	public GuiLayoutScreen(Screen parent) {
		super(Text.literal("GUI Setup"));
		this.parent = parent;
	}

	// =============================================================================== render

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		GuiLayout layout = GuiLayout.current();
		GuiLayout.Rect panel = layout.panelClamped(this.width, this.height, BASE_W, BASE_H);

		drawWoodPanel(ctx, panel.x(), panel.y(), panel.w(), panel.h());

		// ---- header ----
		GuiLayout.Rect head = layout.header(panel);
		ctx.fillGradient(head.x(), head.y(), head.right(), head.bottom(),
				PLANK_DARK, blend(PLANK_DARK, 0xFF000000, 0.35f));
		ctx.fill(head.x(), head.bottom(), head.right(), head.bottom() + 1, GREEN);
		ctx.drawCenteredTextWithShadow(this.textRenderer,
				TextFx.gradient("GUI SETUP", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo),
				panel.centerX(), layout.titleY(panel.y()), CREAM);
		if (layout.hasSubtitle()) {
			ctx.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal("§7how the menus are arranged"), panel.centerX(),
					layout.subtitleY(panel.y()), 0xFFCBB088);
		}

		GuiLayout.Rect body = layout.body(panel);
		GuiLayout.Rect area = layout.useSidebar() ? layout.content(panel) : body;

		GuiLayout[] presets = GuiLayout.values();
		GuiLayout described = layout;

		// ---- nav rail (SIDEBAR only; zero width otherwise) ----
		if (layout.useSidebar()) {
			GuiLayout.Rect rail = layout.navRail(panel);
			ctx.fill(rail.x(), rail.y(), rail.right(), rail.bottom(), 0x40000000);
			ctx.fill(rail.right(), rail.y(), rail.right() + 1, rail.bottom(), BORDER);
			ctx.drawText(this.textRenderer, Text.literal("§8SECTIONS"), rail.x() + 3, rail.y() + 1, 0xFFFFFFFF, true);

			int ry = rail.y() + 12;
			for (GuiLayout preset : presets) {
				boolean active = preset == layout;
				if (hover(mouseX, mouseY, rail.x(), ry, rail.w(), layout.rowHeight())) described = preset;
				if (drawRailRow(ctx, mouseX, mouseY, rail.x(), ry, rail.w(), layout.rowHeight(),
						preset.label(), active)) {
					GuiLayout.set(preset);
					return;
				}
				ry += layout.rowStride();
			}

			// with no footer the back action belongs at the foot of the rail
			int backY = rail.bottom() - layout.smallButtonHeight();
			if (drawBigButton(ctx, mouseX, mouseY, rail.x(), backY, rail.w(), layout.smallButtonHeight(),
					"§f§lBACK", null)) {
				close();
				return;
			}
		}

		// ---- three live preview cards, side by side ----
		int infoH = layout.scaleH(34);
		int cardsH = Math.max(44, area.h() - infoH - layout.gap());
		int cardW = layout.split(area.w(), presets.length);

		for (int i = 0; i < presets.length; i++) {
			GuiLayout preset = presets[i];
			int cx = area.x() + i * (cardW + layout.columnGap());
			if (hover(mouseX, mouseY, cx, area.y(), cardW, cardsH)) described = preset;
			if (drawCard(ctx, mouseX, mouseY, preset, cx, area.y(), cardW, cardsH, preset == layout)) {
				GuiLayout.set(preset);
				return;
			}
		}

		// ---- description strip under the cards ----
		int infoY = area.y() + cardsH + layout.gap();
		ctx.fill(area.x(), infoY, area.right(), infoY + infoH, 0x50000000);
		ctx.fill(area.x(), infoY, area.x() + 2, infoY + infoH, GREEN);
		ctx.drawText(this.textRenderer,
				Text.literal("§f§l" + described.label().toUpperCase() + " §8- §7" + described.blurb()),
				area.x() + 6, infoY + 3, 0xFFFFFFFF, true);
		int ty = infoY + 14;
		for (String line : wrap(described.detail(), area.w() - 12)) {
			if (ty + 9 > infoY + infoH) break;
			ctx.drawText(this.textRenderer, Text.literal("§8" + line), area.x() + 6, ty, 0xFFFFFFFF, true);
			ty += 10;
		}

		// ---- footer (CLASSIC / COMPACT; SIDEBAR keeps its actions in the rail) ----
		if (layout.footerHeight() > 0) {
			GuiLayout.Rect foot = layout.footer(panel);
			ctx.fill(panel.x(), foot.y() - 4, panel.right(), foot.y() - 3, FRAME_DARK);
			int half = layout.halfWidth(panel.w());
			if (drawBigButton(ctx, mouseX, mouseY, foot.x(), foot.y(), half, layout.buttonHeight(),
					"§f§lBACK", null)) {
				close();
				return;
			}
			int rightX = foot.x() + half + layout.columnGap();
			if (drawBigButton(ctx, mouseX, mouseY, rightX, foot.y(), half, layout.buttonHeight(),
					"§e§lRESET", "§8back to Classic")) {
				GuiLayout.set(GuiLayout.CLASSIC);
				return;
			}
		}
	}

	// =============================================================================== pieces

	/** One nav-rail entry: left accent bar, label, hover wash. Returns true on click. */
	private boolean drawRailRow(DrawContext ctx, int mouseX, int mouseY, int x, int y, int w, int h,
	                            String label, boolean active) {
		boolean hovered = hover(mouseX, mouseY, x, y, w, h);
		ctx.fill(x, y, x + w, y + h, active ? blend(PLANK, GREEN, 0.30f) : (hovered ? 0x40FFFFFF : 0x28000000));
		ctx.fill(x, y, x + 2, y + h, active ? GREEN : BORDER);
		ctx.drawText(this.textRenderer, Text.literal((active ? "§a§l" : "§f") + label),
				x + 6, y + Math.max(1, (h - 8) / 2), 0xFFFFFFFF, true);

		boolean clicked = hovered && mouseClickedThisFrame;
		if (clicked) {
			mouseClickedThisFrame = false;
			playClick(this.client);
		}
		return clicked;
	}

	/** A preset card: title, live thumbnail, dimensions. Returns true on click. */
	private boolean drawCard(DrawContext ctx, int mouseX, int mouseY, GuiLayout preset,
	                         int x, int y, int w, int h, boolean active) {
		boolean hovered = hover(mouseX, mouseY, x, y, w, h);

		ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, active ? GREEN : (hovered ? PLANK_LIGHT : BORDER));
		ctx.fillGradient(x, y, x + w, y + h,
				hovered ? blend(PLANK, GREEN, 0.12f) : PLANK,
				blend(PLANK_DARK, 0xFF000000, 0.25f));
		ctx.fill(x, y, x + w, y + 2, active ? GREEN : blend(PLANK_LIGHT, 0xFFFFFFFF, 0.2f));

		ctx.drawCenteredTextWithShadow(this.textRenderer,
				Text.literal((active ? "§a§l" : "§f§l") + preset.label().toUpperCase()),
				x + w / 2, y + 5, CREAM);

		// live thumbnail
		int miniY = y + 16;
		int miniH = Math.max(12, h - 27);
		drawMini(ctx, preset, x + 3, miniY, w - 6, miniH, hovered || active);

		String size = preset.panelWidth() + "x" + preset.panelHeight();
		ctx.drawCenteredTextWithShadow(this.textRenderer,
				Text.literal(active ? "§a" + size : "§8" + size), x + w / 2, y + h - 10, 0xFFFFFFFF);

		boolean clicked = hovered && mouseClickedThisFrame;
		if (clicked) {
			mouseClickedThisFrame = false;
			playClick(this.client);
		}
		return clicked;
	}

	/**
	 * Draws a miniature of {@code preset}'s arrangement inside the given box, scaled so the panel
	 * keeps its true aspect ratio. Everything here is derived from the preset's own accessors, so a
	 * metric change in {@link GuiLayout} shows up in the thumbnail with no edit to this method.
	 */
	private void drawMini(DrawContext ctx, GuiLayout preset, int ax, int ay, int aw, int ah, boolean live) {
		if (aw < 8 || ah < 8) return;

		float sc = Math.min(aw / (float) preset.panelWidth(), ah / (float) preset.panelHeight());
		int pw = Math.max(8, Math.round(preset.panelWidth() * sc));
		int ph = Math.max(8, Math.round(preset.panelHeight() * sc));
		int ox = ax + (aw - pw) / 2;
		int oy = ay + (ah - ph) / 2;

		ctx.fill(ox - 1, oy - 1, ox + pw + 1, oy + ph + 1, FRAME_DARK);
		ctx.fill(ox, oy, ox + pw, oy + ph, PLANK);

		// header banner + its accent rule, at the preset's real header height
		int hh = Math.max(2, Math.round(preset.headerHeight() * sc));
		ctx.fill(ox, oy, ox + pw, oy + hh, PLANK_DARK);
		ctx.fill(ox, oy + hh, ox + pw, oy + hh + 1, GREEN);
		int titleW = Math.max(6, pw / 3);
		int titleY = oy + Math.max(1, hh / 2 - 1);
		ctx.fill(ox + pw / 2 - titleW / 2, titleY, ox + pw / 2 + titleW / 2, titleY + 2, CREAM);

		int pad = Math.max(1, Math.round(preset.padding() * sc));
		int gap = Math.max(1, Math.round(preset.gap() * sc));
		int footH = preset.footerHeight() > 0 ? Math.max(3, Math.round(preset.footerHeight() * sc)) : 0;
		int bodyTop = oy + hh + Math.max(1, Math.round(preset.headerGap() * sc));
		int bodyBottom = oy + ph - (footH > 0 ? footH : pad);
		int bodyLeft = ox + pad;
		int bodyRight = ox + pw - pad;
		if (bodyBottom - bodyTop < 4 || bodyRight - bodyLeft < 4) return;

		// the "live" part: a highlight walking the cells so the thumbnail breathes
		int beat = live ? (int) ((System.currentTimeMillis() / 620) % 9) : -1;

		if (preset.useSidebar()) {
			int railRight = Math.min(bodyRight - 4, ox + Math.max(6, Math.round(preset.sidebarWidth() * sc)));
			ctx.fill(bodyLeft, bodyTop, railRight, bodyBottom, 0x50000000);

			int rh = Math.max(2, Math.round(preset.rowHeight() * sc));
			int rg = Math.max(1, Math.round(preset.rowGap() * sc));
			int ry = bodyTop + 1;
			int index = 0;
			while (ry + rh <= bodyBottom - rh - 2 && index < 12) {
				boolean hot = beat >= 0 && index == beat % 5;
				ctx.fill(bodyLeft + 1, ry, railRight - 1, ry + rh, hot ? GREEN : PLANK_LIGHT);
				ctx.fill(bodyLeft + 1, ry, bodyLeft + 2, ry + rh, hot ? CREAM : GREEN_DARK);
				ry += rh + rg;
				index++;
			}
			// the footer actions live down here on this preset
			ctx.fill(bodyLeft + 1, bodyBottom - rh, railRight - 1, bodyBottom, GREEN_DARK);

			// content pane: stacked detail lines
			int cx = railRight + gap;
			ctx.fill(cx, bodyTop, bodyRight, bodyBottom, blend(PLANK_LIGHT, 0xFF000000, 0.30f));
			ctx.fill(cx, bodyTop, bodyRight, bodyTop + 1, GREEN_DARK);
			int stride = Math.max(3, Math.round(preset.rowStride() * sc));
			int ly = bodyTop + 3;
			while (ly + 1 <= bodyBottom - 2) {
				ctx.fill(cx + 2, ly, bodyRight - 2, ly + 1, 0x50FFFFFF);
				ly += stride;
			}
		} else {
			int cols = Math.max(1, preset.gridColumns());
			int bw = bodyRight - bodyLeft;
			int tw = cols <= 1 ? bw : Math.max(2, (bw - (cols - 1) * gap) / cols);
			int th = Math.max(3, Math.round(preset.tileHeight() * sc));
			int index = 0;
			int ty = bodyTop;
			while (ty + th <= bodyBottom && index < 16) {
				for (int c = 0; c < cols; c++) {
					int tx = bodyLeft + c * (tw + gap);
					boolean hot = beat >= 0 && index == beat;
					ctx.fill(tx, ty, tx + tw, ty + th, hot ? blend(PLANK_LIGHT, GREEN, 0.45f) : PLANK_LIGHT);
					ctx.fill(tx, ty, tx + tw, ty + 1, hot ? GREEN : blend(PLANK_LIGHT, 0xFFFFFFFF, 0.25f));
					int iw = Math.max(2, tw / 3);
					int ih = Math.max(2, th / 3);
					int iy = ty + Math.max(1, th / 5);
					ctx.fill(tx + tw / 2 - iw / 2, iy, tx + tw / 2 + iw / 2, iy + ih, 0x60000000);
					index++;
				}
				ty += th + gap;
			}

			if (footH > 0) {
				int ruleY = oy + ph - footH;
				ctx.fill(ox, ruleY - 1, ox + pw, ruleY, FRAME_DARK);
				int bh = Math.max(2, Math.round(preset.buttonHeight() * sc));
				int fy = ruleY + Math.max(0, (footH - bh) / 2);
				int half = Math.max(2, (bw - gap) / 2);
				ctx.fill(bodyLeft, fy, bodyLeft + half, fy + bh, GREEN_DARK);
				ctx.fill(bodyLeft + half + gap, fy, bodyLeft + half + gap + half, fy + bh, GREEN_DARK);
			}
		}
	}

	/** Greedy word wrap against the real font width - no reliance on trim helpers. */
	private List<String> wrap(String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (this.textRenderer.getWidth(candidate) > maxWidth && line.length() > 0) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(candidate);
			}
		}
		if (line.length() > 0) lines.add(line.toString());
		return lines;
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}
}
