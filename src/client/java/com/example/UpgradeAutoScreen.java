package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings for the auto upgrader, plus the run/stop button.
 *
 * <p>The picker lists the upgrade names the pass has seen in the menu; with nothing
 * picked it buys every upgrade, which is what most players want, so an empty picker is
 * shown as ALL rather than as an error.
 */
public class UpgradeAutoScreen extends FarmScreen {

	private static final double[] RESERVES = {
			0, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18};
	private static final int[] INTERVALS = {0, 5, 10, 15, 30, 60, 120};

	private final Screen parent;
	private TextFieldWidget commandField;
	private int scroll = 0;

	public UpgradeAutoScreen(Screen parent) {
		super(Text.literal("Auto Upgrader"));
		this.parent = parent;
	}

	private int panelW() {
		return Math.min(340, this.width - 24);
	}

	private int panelH() {
		return Math.min(272, this.height - 24);
	}

	private int panelX() {
		return this.width / 2 - panelW() / 2;
	}

	private int panelY() {
		return Math.max(6, this.height / 2 - panelH() / 2);
	}

	@Override
	protected void init() {
		// resize() rebuilds the widget, so whatever is typed has to survive it
		String previous = commandField != null ? commandField.getText() : ForgeCalcConfig.upgradeCommand;
		commandField = new TextFieldWidget(this.textRenderer, panelX() + 92, panelY() + 46,
				panelW() - 104, 14, Text.literal(""));
		commandField.setMaxLength(32);
		commandField.setPlaceholder(Text.literal("§8upgrades"));
		commandField.setText(previous);
		commandField.setChangedListener(value -> ForgeCalcConfig.upgradeCommand = value.trim());
		addSelectableChild(commandField);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = panelW();
		int panelH = panelH();
		int px = panelX();
		int py = panelY();
		int w = panelW - 24;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer,
				TextFx.gradient("AUTO UPGRADER", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo),
				px + panelW / 2, py + 7, CREAM);

		boolean running = UpgradeAuto.isRunning();
		int y = py + 27;

		// status + run/stop
		ctx.fill(px + 12, y, px + 12 + w, y + 16, running ? 0x3055FF88 : 0x25FFFFFF);
		ctx.drawText(this.textRenderer, Text.literal((running ? "§a" : "§7") + UpgradeAuto.statusText()),
				px + 18, y + 4, 0xFFFFFFFF, true);
		if (running && UpgradeAuto.boughtUpgrades() > 0) {
			String tally = "§f" + UpgradeAuto.boughtLevels() + "§7 lv";
			ctx.drawText(this.textRenderer, Text.literal(tally),
					px + 12 + w - 4 - this.textRenderer.getWidth(tally.replaceAll("§.", "")) - 46, y + 4, 0xFFFFFFFF, true);
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 12 + w - 46, y + 1, 44, 14,
				running ? "§c§lSTOP" : "§a§lRUN", null)) {
			UpgradeAuto.toggle(this.client);
		}
		y += 19;

		// the command that opens the menu
		ctx.drawText(this.textRenderer, Text.literal("§7Menu command"), px + 18, y + 4, 0xFFFFFFFF, true);
		if (commandField != null) {
			commandField.setX(px + 92);
			commandField.setY(y);
			commandField.setWidth(w - 80);
			commandField.render(ctx, mouseX, mouseY, delta);
		}
		y += 20;

		y = toggleRow(ctx, mouseX, mouseY, px + 12, y, w, "Close the menu when done",
				ForgeCalcConfig.upgradeCloseWhenDone, v -> ForgeCalcConfig.upgradeCloseWhenDone = v,
				"§7Leave this off to watch the last page");
		y = toggleRow(ctx, mouseX, mouseY, px + 12, y, w, "Allow Mastery",
				ForgeCalcConfig.upgradeMastery, v -> ForgeCalcConfig.upgradeMastery = v,
				"§cMastery resets that upgrade's level to 0");

		// money to keep back
		int reserveIndex = 0;
		for (int i = 0; i < RESERVES.length; i++) {
			if (ForgeCalcConfig.upgradeReserve >= RESERVES[i]) reserveIndex = i;
		}
		y = stepperRow(ctx, mouseX, mouseY, px + 12, y, w, "Keep back",
				reserveIndex == 0 ? "§7nothing" : "§a$" + ForgeOreCalculatorClient.formatMoney(RESERVES[reserveIndex]),
				"§7Never spend below this balance",
				() -> ForgeCalcConfig.upgradeReserve = RESERVES[Math.max(0, indexOf(RESERVES, ForgeCalcConfig.upgradeReserve) - 1)],
				() -> ForgeCalcConfig.upgradeReserve = RESERVES[Math.min(RESERVES.length - 1, indexOf(RESERVES, ForgeCalcConfig.upgradeReserve) + 1)]);

		// repeat timer
		int intervalIndex = 0;
		for (int i = 0; i < INTERVALS.length; i++) {
			if (ForgeCalcConfig.upgradeInterval >= INTERVALS[i]) intervalIndex = i;
		}
		y = stepperRow(ctx, mouseX, mouseY, px + 12, y, w, "Repeat every",
				intervalIndex == 0 ? "§7manual only" : "§f" + INTERVALS[intervalIndex] + " min",
				"§7Runs on its own with no GUI open",
				() -> ForgeCalcConfig.upgradeInterval = INTERVALS[Math.max(0, indexOf(INTERVALS, ForgeCalcConfig.upgradeInterval) - 1)],
				() -> ForgeCalcConfig.upgradeInterval = INTERVALS[Math.min(INTERVALS.length - 1, indexOf(INTERVALS, ForgeCalcConfig.upgradeInterval) + 1)]);

		// buys per page
		y = stepperRow(ctx, mouseX, mouseY, px + 12, y, w, "Buys per upgrade",
				"§f" + ForgeCalcConfig.upgradeRounds + "x",
				"§7How many times it re-buys the same upgrade",
				() -> ForgeCalcConfig.upgradeRounds = Math.max(1, ForgeCalcConfig.upgradeRounds - 1),
				() -> ForgeCalcConfig.upgradeRounds = Math.min(20, ForgeCalcConfig.upgradeRounds + 1));

		// tier chips
		int chipW = (w - 4 * 3) / 5;
		for (int i = 0; i < UpgradeAuto.TIERS.length; i++) {
			String tier = UpgradeAuto.TIERS[i];
			boolean on = ForgeCalcConfig.upgradeTiers.contains(tier);
			int cx = px + 12 + i * (chipW + 3);
			if (drawBigButton(ctx, mouseX, mouseY, cx, y, chipW, 15,
					(on ? "§f" : "§8") + tier.substring(0, 1).toUpperCase(Locale.ROOT) + tier.substring(1, Math.min(4, tier.length())), null)) {
				if (!ForgeCalcConfig.upgradeTiers.remove(tier)) ForgeCalcConfig.upgradeTiers.add(tier);
				ForgeCalcConfig.save();
			}
			if (on) ctx.fill(cx, y + 15, cx + chipW, y + 16, ForgeCalcTheme.current().accent);
		}
		y += 20;

		// which upgrades
		List<String> known = new ArrayList<>(ForgeCalcConfig.upgradeKnown);
		known.sort(String::compareTo);
		boolean all = ForgeCalcConfig.upgradePicks.isEmpty();
		ctx.drawText(this.textRenderer, Text.literal(all
				? "§7Upgrades: §aALL"
				: "§7Upgrades: §f" + ForgeCalcConfig.upgradePicks.size() + " picked"), px + 18, y, 0xFFFFFFFF, true);
		if (!all && drawBigButton(ctx, mouseX, mouseY, px + 12 + w - 46, y - 3, 44, 14, "§e§lCLEAR", null)) {
			ForgeCalcConfig.upgradePicks.clear();
			ForgeCalcConfig.save();
		}
		y += 13;

		int listBottom = py + panelH - 28;
		int rowH = 15;
		int visible = Math.max(1, (listBottom - y) / rowH);
		if (known.isEmpty()) {
			ctx.drawText(this.textRenderer, Text.literal("§8Run it once to list your upgrades"),
					px + 18, y + 4, 0xFFFFFFFF, false);
		} else {
			int maxScroll = Math.max(0, known.size() - visible);
			scroll = Math.max(0, Math.min(maxScroll, scroll - pollScroll()));
			for (int i = scroll; i < Math.min(known.size(), scroll + visible); i++) {
				String name = known.get(i);
				boolean picked = ForgeCalcConfig.upgradePicks.contains(name);
				int ry = y + (i - scroll) * rowH;
				boolean hovered = hover(mouseX, mouseY, px + 12, ry, w, rowH - 1);
				ctx.fill(px + 12, ry, px + 12 + w, ry + rowH - 1,
						picked ? 0x3055FF88 : hovered ? 0x25FFFFFF : 0x18FFFFFF);
				ctx.drawText(this.textRenderer, Text.literal((picked ? "§f" : "§7") + title(name)),
						px + 18, ry + 3, 0xFFFFFFFF, true);
				ctx.drawText(this.textRenderer, Text.literal(picked ? "§a✔" : "§8-"),
						px + 12 + w - 14, ry + 3, 0xFFFFFFFF, true);
				if (hovered && mouseClickedThisFrame) {
					mouseClickedThisFrame = false;
					playClick(this.client);
					if (!ForgeCalcConfig.upgradePicks.remove(name)) ForgeCalcConfig.upgradePicks.add(name);
					ForgeCalcConfig.save();
				}
			}
		}

		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + panelH - 25, w, 18, "§f§lBACK", null)) {
			close();
		}
	}

	private static int indexOf(double[] ladder, double value) {
		int index = 0;
		for (int i = 0; i < ladder.length; i++) {
			if (value >= ladder[i]) index = i;
		}
		return index;
	}

	private static int indexOf(int[] ladder, int value) {
		int index = 0;
		for (int i = 0; i < ladder.length; i++) {
			if (value >= ladder[i]) index = i;
		}
		return index;
	}

	private static String title(String name) {
		if (name.isBlank()) return name;
		return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
	}

	private int toggleRow(DrawContext ctx, int mouseX, int mouseY, int x, int y, int w,
	                      String label, boolean value, java.util.function.Consumer<Boolean> setter, String hint) {
		boolean hovered = hover(mouseX, mouseY, x, y, w, 16);
		roundedFill(ctx, x, y, x + w, y + 16, hovered ? 0x30FFFFFF : 0x18FFFFFF);
		ctx.drawText(this.textRenderer, Text.literal((value ? "§f" : "§7") + label), x + 6, y + 4, 0xFFFFFFFF, true);
		drawSwitch(ctx, x + w - 28, y + 3, value);
		if (hovered && mouseClickedThisFrame) {
			mouseClickedThisFrame = false;
			playClick(this.client);
			setter.accept(!value);
			ForgeCalcConfig.save();
		}
		if (hovered && hint != null) drawInfoTooltip(ctx, mouseX, mouseY, List.of(hint));
		return y + 19;
	}

	private int stepperRow(DrawContext ctx, int mouseX, int mouseY, int x, int y, int w,
	                       String label, String value, String hint, Runnable down, Runnable up) {
		boolean hovered = hover(mouseX, mouseY, x, y, w, 16);
		ctx.fill(x, y, x + w, y + 16, 0x25FFFFFF);
		ctx.drawText(this.textRenderer, Text.literal("§7" + label), x + 6, y + 4, 0xFFFFFFFF, true);
		ctx.drawText(this.textRenderer, Text.literal(value), x + 6 + 92, y + 4, 0xFFFFFFFF, true);
		if (drawBigButton(ctx, mouseX, mouseY, x + w - 44, y + 1, 20, 14, "§c-", null)) {
			down.run();
			ForgeCalcConfig.save();
		}
		if (drawBigButton(ctx, mouseX, mouseY, x + w - 22, y + 1, 20, 14, "§a+", null)) {
			up.run();
			ForgeCalcConfig.save();
		}
		if (hovered && hint != null) drawInfoTooltip(ctx, mouseX, mouseY, List.of(hint));
		return y + 19;
	}

	@Override
	public void close() {
		ForgeCalcConfig.save();
		this.client.setScreen(parent);
	}
}
