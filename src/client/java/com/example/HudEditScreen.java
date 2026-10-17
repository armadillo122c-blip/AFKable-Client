package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashMap;
import java.util.Map;

public class HudEditScreen extends FarmScreen {

	private static final Map<String, int[]> SIZES = new LinkedHashMap<>();
	private static final Map<String, String> LABELS = new LinkedHashMap<>();

	static {
		SIZES.put("restock", new int[]{110, 14});
		SIZES.put("claude", new int[]{110, 30});
		SIZES.put("popups", new int[]{155, 26});
		SIZES.put("chatgame", new int[]{150, 45});
		// the forge calculator had a saved position but no editor entry, so it could
		// never be dragged - it is the biggest panel in the mod, hence the size
		LABELS.put("restock", "§6§lRESTOCK");
		LABELS.put("claude", "§6§lCLAUDE");
		LABELS.put("popups", "§6§lORE POPUPS");
		LABELS.put("chatgame", "§e§lCHAT GAME");
		SIZES.put(LiveActivity.HUD_ID, new int[]{LiveActivity.panelWidth(), LiveActivity.panelHeight()});
	}

	private final Screen parent;
	private String dragging = null;
	private int gridMode = 0;
	private int invScaleMode = 0;
	private static final int[] GRID_STEPS = {0, 8, 16, 24, 32};

	private void drawInventoryPreview(DrawContext ctx) {
		double current = this.client != null ? this.client.getWindow().getScaleFactor() : 2;
		double target = invScaleMode == 0 ? current : invScaleMode;
		int w = (int) Math.round(176 * target / current);
		int h = (int) Math.round(166 * target / current);
		int x = this.width / 2 - w / 2;
		int y = this.height / 2 - h / 2;
		ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x60FFFFFF);
		ctx.fill(x, y, x + w, y + h, 0x30C6C6C6);
		ctx.drawCenteredTextWithShadow(this.textRenderer,
				net.minecraft.text.Text.literal("§7INVENTORY §8(scale " + (invScaleMode == 0 ? "auto" : String.valueOf(invScaleMode)) + ")"),
				x + w / 2, y + h / 2 - 4, 0xFFFFFFFF);
	}

	private void drawMockHud(DrawContext ctx) {
		int cx = this.width / 2;
		int hotbarY = this.height - 24;
		for (int i = 0; i < 9; i++) {
			int sx = cx - 91 + i * 20;
			ctx.fill(sx, hotbarY, sx + 20, hotbarY + 20, 0x60000000);
			ctx.fill(sx + 1, hotbarY + 1, sx + 19, hotbarY + 19, 0x40404040);
		}
		ctx.fill(cx - 91, hotbarY - 6, cx + 91, hotbarY - 2, 0x603DDC53);
		ctx.fill(cx - 4, this.height / 2, cx + 5, this.height / 2 + 1, 0x80FFFFFF);
		ctx.fill(cx, this.height / 2 - 4, cx + 1, this.height / 2 + 5, 0x80FFFFFF);
	}
	private int dragOffsetX = 0;
	private int dragOffsetY = 0;
	private boolean mouseWasDown = false;

	public HudEditScreen(Screen parent) {
		super(Text.literal("Edit HUD Layout"));
		this.parent = parent;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		updateMouse();

		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§6§lEDIT HUD LAYOUT"), this.width / 2, 8, 0xFFFFFFFF);
		drawMockHud(ctx);
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7Drag to move §8| §7Scroll to §fresize §8| §7[-][+] for §fopacity §8| §fR §7reset §8| §fESC §7save"), this.width / 2, 20, 0xFFFFFFFF);

		String[] gridNames = {"OFF", "8px", "16px", "24px", "32px"};
		if (drawBigButton(ctx, mouseX, mouseY, this.width / 2 - 92, 34, 88, 14, "§fGRID: §e" + gridNames[gridMode], null)) {
			gridMode = (gridMode + 1) % 5;
		}
		String[] invNames = {"AUTO", "1", "2", "3", "4"};
		if (drawBigButton(ctx, mouseX, mouseY, this.width / 2 + 4, 34, 88, 14, "§fINV: §e" + invNames[invScaleMode], null)) {
			invScaleMode = (invScaleMode + 1) % 5;
		}
		drawInventoryPreview(ctx);
		if (gridMode > 0) {
			int step = GRID_STEPS[gridMode];
			for (int gx = 0; gx < this.width; gx += step) {
				ctx.fill(gx, 0, gx + 1, this.height, 0x14FFFFFF);
			}
			for (int gy = 0; gy < this.height; gy += step) {
				ctx.fill(0, gy, this.width, gy + 1, 0x14FFFFFF);
			}
		}

		boolean mouseDown = this.client != null
				&& GLFW.glfwGetMouseButton(this.client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

		if (mouseDown && !mouseWasDown) {
			for (String id : SIZES.keySet()) {
				int[] pos = ForgeCalcConfig.pos(id);
				int[] base = SIZES.get(id);
				float sc0 = ForgeCalcConfig.scale(id);
				int[] size = {(int) (base[0] * sc0), (int) (base[1] * sc0)};
				if (mouseX >= pos[0] && mouseX <= pos[0] + size[0] && mouseY >= pos[1] && mouseY <= pos[1] + size[1]) {
					dragging = id;
					dragOffsetX = mouseX - pos[0];
					dragOffsetY = mouseY - pos[1];
				}
			}
		}
		if (!mouseDown) {
			dragging = null;
		}
		mouseWasDown = mouseDown;

		if (dragging != null) {
			int[] base = SIZES.get(dragging);
			float scd = ForgeCalcConfig.scale(dragging);
			int[] size = {(int) (base[0] * scd), (int) (base[1] * scd)};
			int nx = Math.max(0, Math.min(this.width - size[0], mouseX - dragOffsetX));
			int ny = Math.max(0, Math.min(this.height - size[1], mouseY - dragOffsetY));
			if (gridMode > 0) {
				int step = GRID_STEPS[gridMode];
				nx = Math.round(nx / (float) step) * step;
				ny = Math.round(ny / (float) step) * step;
			}
			ForgeCalcConfig.POSITIONS.put(dragging, new int[]{nx, ny});
		}

		if (this.client != null && GLFW.glfwGetKey(this.client.getWindow().getHandle(), GLFW.GLFW_KEY_R) == GLFW.GLFW_PRESS) {
			ForgeCalcConfig.resetPositions();
			ForgeCalcConfig.SCALES.clear();
			ForgeCalcConfig.ALPHAS.clear();
		}

		int wheel = pollScroll();

		for (String id : SIZES.keySet()) {
			int[] pos = ForgeCalcConfig.pos(id);
			int[] base = SIZES.get(id);
			float sc = ForgeCalcConfig.scale(id);
			float al = ForgeCalcConfig.alpha(id);
			int w = (int) (base[0] * sc);
			int h = (int) (base[1] * sc);
			boolean hover = id.equals(dragging)
					|| (mouseX >= pos[0] && mouseX <= pos[0] + w && mouseY >= pos[1] && mouseY <= pos[1] + h);

			if (hover && wheel != 0) {
				float next = Math.max(0.5f, Math.min(2.0f, sc + wheel * 0.1f));
				ForgeCalcConfig.SCALES.put(id, next);
				sc = next;
				w = (int) (base[0] * sc);
				h = (int) (base[1] * sc);
				wheel = 0;
			}

			int bgAlpha = (int) (0xE0 * al) << 24;
			ctx.fill(pos[0] - 1, pos[1] - 1, pos[0] + w + 1, pos[1] + h + 1, hover ? GREEN : BORDER);
			ctx.fill(pos[0], pos[1], pos[0] + w, pos[1] + h, bgAlpha | (ForgeCalcTheme.current().panelBg & 0xFFFFFF));
			ctx.drawText(this.textRenderer, Text.literal(LABELS.get(id)), pos[0] + 5, pos[1] + 3, 0xFFFFFFFF, true);
			drawSampleContent(ctx, id, pos[0], pos[1], w, h);

			if (hover) {
				String stats = String.format("§7x%.1f §8| §7%d%%", sc, (int) (al * 100));
				ctx.drawText(this.textRenderer, Text.literal(stats), pos[0] + 5, pos[1] + h - 11, 0xFFFFFFFF, true);

				int bx = pos[0] + w - 30;
				int by = pos[1] + h - 14;
				if (drawBigButton(ctx, mouseX, mouseY, bx, by, 13, 11, "§c-", null)) {
					ForgeCalcConfig.ALPHAS.put(id, Math.max(0.2f, al - 0.1f));
				}
				if (drawBigButton(ctx, mouseX, mouseY, bx + 15, by, 13, 11, "§a+", null)) {
					ForgeCalcConfig.ALPHAS.put(id, Math.min(1.0f, al + 0.1f));
				}
			}
		}
	}

	private void drawSampleContent(DrawContext ctx, String id, int x, int y, int w, int h) {
		switch (id) {
			case "restock" -> ctx.drawText(this.textRenderer, Text.literal("§f2m 14s"), x + 5, y + h - 11, 0xFFFFFFFF, true);
			case "claude" -> {
				ctx.drawText(this.textRenderer, Text.literal("§f78% full"), x + 5, y + 13, 0xFFFFFFFF, true);
				ctx.fill(x + 5, y + h - 8, x + w - 5, y + h - 4, 0xFF3DDC53);
			}
			case "chatgame" -> {
				ctx.drawText(this.textRenderer, Text.literal("§7which command?"), x + 4, y + 14, 0xFFFFFFFF, true);
				ctx.drawText(this.textRenderer, Text.literal("§a\u25b6 §fstock"), x + 4, y + 24, 0xFFFFFFFF, true);
			}
			default -> {
			}
		}
	}

	@Override
	public void close() {
		ForgeCalcConfig.save();
		this.client.setScreen(parent);
	}
}
