package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Controls exactly what TRASH is allowed to delete: category rules, value
 * thresholds, and a click-to-toggle map of every inventory slot.
 */
public class TrashScreen extends FarmScreen {

	private final Screen parent;
	private int tab = 0;

	public TrashScreen(Screen parent) {
		super(Text.literal("Trash Control"));
		this.parent = parent;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = 300;
		int panelH = 268;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - panelH / 2;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("TRASH CONTROL", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo), px + panelW / 2, py + 7, CREAM);

		String[] tabs = {"RULES", "SLOTS"};
		int tabW = (panelW - 24 - 6) / 2;
		for (int i = 0; i < 2; i++) {
			if (drawBigButton(ctx, mouseX, mouseY, px + 12 + i * (tabW + 6), py + 26, tabW, 16,
					(tab == i ? "§f§l" : "§7") + tabs[i], null)) {
				tab = i;
			}
			if (tab == i) {
				ctx.fill(px + 12 + i * (tabW + 6), py + 42, px + 12 + i * (tabW + 6) + tabW, py + 44, GREEN);
			}
		}

		if (tab == 0) {
			renderRules(ctx, mouseX, mouseY, px, py, panelW);
		} else {
			renderSlots(ctx, mouseX, mouseY, px, py, panelW);
		}

		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + panelH - 24, panelW - 24, 18, "§f§lSAVE + BACK", null)) {
			close();
		}
	}

	private void renderRules(DrawContext ctx, int mouseX, int mouseY, int px, int py, int panelW) {
		int y = py + 50;
		int w = panelW - 24;

		y = toggleRow(ctx, mouseX, mouseY, px + 12, y, w, "Protect TITANIC mobs",
				ForgeCalcConfig.trashProtectTitanic, v -> ForgeCalcConfig.trashProtectTitanic = v,
				"§7Never delete a Titanic, whatever else you set");
		y = toggleRow(ctx, mouseX, mouseY, px + 12, y, w, "Trash low ores",
				ForgeCalcConfig.trashOres, v -> ForgeCalcConfig.trashOres = v, null);

		// ore threshold
		ctx.fill(px + 12, y, px + 12 + w, y + 18, 0x28FFFFFF);
		ctx.drawText(this.textRenderer, Text.literal("§7  under §f" + fmt(ForgeCalcConfig.trashOreMax) + "x §7multiplier"), px + 18, y + 5, 0xFFFFFFFF, true);
		if (drawBigButton(ctx, mouseX, mouseY, px + 12 + w - 44, y + 2, 20, 14, "§c-", null)) {
			ForgeCalcConfig.trashOreMax = stepDown(ForgeCalcConfig.trashOreMax);
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 12 + w - 22, y + 2, 20, 14, "§a+", null)) {
			ForgeCalcConfig.trashOreMax = stepUp(ForgeCalcConfig.trashOreMax);
		}
		y += 21;

		y = toggleRow(ctx, mouseX, mouseY, px + 12, y, w, "Trash small mobs",
				ForgeCalcConfig.trashMobs, v -> ForgeCalcConfig.trashMobs = v, null);

		// mob size threshold
		String[] sizeNames = {"Tiny", "Small", "Normal", "Big", "Giant", "Colossal", "Huge", "Titanic"};
		double[] sizeValues = {0.25, 0.5, 1.0, 1.5, 2.25, 3.0, 5.0, 15.0};
		int sizeIndex = 0;
		for (int i = 0; i < sizeValues.length; i++) {
			if (ForgeCalcConfig.trashMobMax >= sizeValues[i]) sizeIndex = i;
		}
		ctx.fill(px + 12, y, px + 12 + w, y + 18, 0x28FFFFFF);
		ctx.drawText(this.textRenderer, Text.literal("§7  smaller than §f" + sizeNames[sizeIndex]), px + 18, y + 5, 0xFFFFFFFF, true);
		if (drawBigButton(ctx, mouseX, mouseY, px + 12 + w - 44, y + 2, 20, 14, "§c-", null)) {
			ForgeCalcConfig.trashMobMax = sizeValues[Math.max(0, sizeIndex - 1)];
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 12 + w - 22, y + 2, 20, 14, "§a+", null)) {
			ForgeCalcConfig.trashMobMax = sizeValues[Math.min(sizeValues.length - 1, sizeIndex + 1)];
		}
		y += 21;

		y = toggleRow(ctx, mouseX, mouseY, px + 12, y, w, "Trash low-damage Factory NPCs",
				ForgeCalcConfig.trashFactoryNpcs, v -> ForgeCalcConfig.trashFactoryNpcs = v,
				"§7NPCs with unknown damage are always protected");

		ctx.fill(px + 12, y, px + 12 + w, y + 18, 0x28FFFFFF);
		ctx.drawText(this.textRenderer, Text.literal("§7  under §f" + formatDamage(ForgeCalcConfig.trashNpcMaxDamage) + " §7damage"), px + 18, y + 5, 0xFFFFFFFF, true);
		if (drawBigButton(ctx, mouseX, mouseY, px + 12 + w - 44, y + 2, 20, 14, "§c-", null)) {
			ForgeCalcConfig.trashNpcMaxDamage = stepNpcDamage(ForgeCalcConfig.trashNpcMaxDamage, -1);
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 12 + w - 22, y + 2, 20, 14, "§a+", null)) {
			ForgeCalcConfig.trashNpcMaxDamage = stepNpcDamage(ForgeCalcConfig.trashNpcMaxDamage, 1);
		}
		y += 21;

		y = toggleRow(ctx, mouseX, mouseY, px + 12, y, w, "Trash everything else",
				ForgeCalcConfig.trashOther, v -> ForgeCalcConfig.trashOther = v,
				"§cRisky - deletes any item it doesn't recognise");

		ctx.drawText(this.textRenderer, Text.literal("§8Unreadable mob sizes are always protected."), px + 14, y + 4, 0xFFFFFFFF, true);
	}

	private String fmt(double value) {
		if (value >= 1000000) return (long) (value / 1000000) + "M";
		if (value >= 1000) return (long) (value / 1000) + "k";
		return String.valueOf((long) value);
	}

	private String formatDamage(double value) {
		if (value >= 1_000_000_000) return trim(value / 1_000_000_000) + "B";
		if (value >= 1_000_000) return trim(value / 1_000_000) + "M";
		if (value >= 1_000) return trim(value / 1_000) + "K";
		return String.valueOf((long) value);
	}

	private String trim(double value) {
		return value == Math.rint(value) ? String.valueOf((long) value) : String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	private double stepNpcDamage(double current, int direction) {
		double[] steps = {1_000, 5_000, 10_000, 25_000, 50_000, 80_000, 100_000, 250_000, 500_000, 1_000_000, 5_000_000, 10_000_000};
		if (direction > 0) {
			for (double step : steps) if (step > current) return step;
			return current;
		}
		for (int i = steps.length - 1; i >= 0; i--) if (steps[i] < current) return steps[i];
		return steps[0];
	}

	private double stepUp(double value) {
		double[] steps = {1, 5, 10, 50, 100, 500, 1000, 5000, 10000, 30000, 60000, 125000, 250000, 500000, 1000000};
		for (double step : steps) {
			if (step > value) return step;
		}
		return value;
	}

	private double stepDown(double value) {
		double[] steps = {1, 5, 10, 50, 100, 500, 1000, 5000, 10000, 30000, 60000, 125000, 250000, 500000, 1000000};
		double best = steps[0];
		for (double step : steps) {
			if (step < value) best = step;
		}
		return best;
	}

	private int toggleRow(DrawContext ctx, int mouseX, int mouseY, int x, int y, int w,
	                      String label, boolean value, java.util.function.Consumer<Boolean> setter, String hint) {
		boolean hovered = hover(mouseX, mouseY, x, y, w, 18);
		ctx.fill(x, y, x + w, y + 18, value ? 0x2855FF88 : 0x20FF5555);
		ctx.drawText(this.textRenderer, Text.literal((value ? "§f" : "§8") + label), x + 6, y + 5, 0xFFFFFFFF, true);
		if (drawBigButton(ctx, mouseX, mouseY, x + w - 40, y + 2, 36, 14, value ? "§aON" : "§cOFF", null)) {
			setter.accept(!value);
			ForgeCalcConfig.save();
		}
		if (hovered && hint != null) {
			drawInfoTooltip(ctx, mouseX, mouseY, List.of(hint));
		}
		return y + 21;
	}

	private void renderSlots(DrawContext ctx, int mouseX, int mouseY, int px, int py, int panelW) {
		ctx.drawText(this.textRenderer, Text.literal("§7Click a slot to protect it from TRASH"), px + 14, py + 50, 0xFFFFFFFF, true);

		int cell = 20;
		int gridW = 9 * cell;
		int gridX = px + panelW / 2 - gridW / 2;
		int gridY = py + 64;

		// main inventory rows 9-35, then the hotbar 0-8 underneath
		for (int row = 0; row < 4; row++) {
			for (int col = 0; col < 9; col++) {
				int index = row < 3 ? 9 + row * 9 + col : col;
				int cx = gridX + col * cell;
				int cy = gridY + row * cell + (row == 3 ? 8 : 0);
				boolean allowed = ForgeCalcConfig.slotTrashable(index);
				boolean hovered = hover(mouseX, mouseY, cx, cy, cell - 2, cell - 2);

				ctx.fill(cx, cy, cx + cell - 2, cy + cell - 2, hovered ? 0xFF6A6A6A : 0xFF3A3A3A);
				ctx.fill(cx + 1, cy + 1, cx + cell - 3, cy + cell - 3,
						allowed ? 0x60FF5555 : 0x6055FF88);

				ItemStack stack = this.client != null && this.client.player != null
						? this.client.player.getInventory().getStack(index) : ItemStack.EMPTY;
				if (!stack.isEmpty()) {
					ctx.drawItem(stack, cx + 1, cy + 1);
				} else if (!allowed) {
					ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§a✔"), cx + cell / 2 - 1, cy + 5, 0xFFFFFFFF);
				}

				if (hovered && mouseClickedThisFrame) {
					mouseClickedThisFrame = false;
					playClick(this.client);
					if (ForgeCalcConfig.trashSlots.isEmpty()) {
						for (int i = 0; i <= 35; i++) {
							ForgeCalcConfig.trashSlots.add(i);
						}
					}
					if (ForgeCalcConfig.trashSlots.contains(index)) {
						ForgeCalcConfig.trashSlots.remove(index);
					} else {
						ForgeCalcConfig.trashSlots.add(index);
					}
					ForgeCalcConfig.save();
				}
			}
		}

		int legendY = gridY + 4 * cell + 14;
		ctx.fill(px + 14, legendY, px + 22, legendY + 8, 0x60FF5555);
		ctx.drawText(this.textRenderer, Text.literal("§7can be trashed"), px + 26, legendY, 0xFFFFFFFF, true);
		ctx.fill(px + 120, legendY, px + 128, legendY + 8, 0x6055FF88);
		ctx.drawText(this.textRenderer, Text.literal("§7protected"), px + 132, legendY, 0xFFFFFFFF, true);

		int btnY = legendY + 14;
		int half = (panelW - 30) / 2;
		if (drawBigButton(ctx, mouseX, mouseY, px + 12, btnY, half, 16, "§aALL TRASHABLE", null)) {
			ForgeCalcConfig.trashSlots.clear();
			ForgeCalcConfig.save();
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 18 + half, btnY, half, 16, "§cPROTECT ALL", null)) {
			ForgeCalcConfig.trashSlots.clear();
			ForgeCalcConfig.trashSlots.add(-1);
			ForgeCalcConfig.save();
		}
	}

	@Override
	public void close() {
		ForgeCalcConfig.save();
		this.client.setScreen(parent);
	}
}
