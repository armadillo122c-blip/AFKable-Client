package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class ForgeCalcSettingsScreen extends FarmScreen {

	private record Entry(String name, int tab, BooleanSupplier get, Consumer<Boolean> set, List<String> tip, ItemStack icon) {
	}

	private final List<Entry> entries = List.of(
			new Entry("Restock Timer", 0, () -> ForgeCalcConfig.showRestock, v -> ForgeCalcConfig.showRestock = v,
					List.of("§6Restock Timer", "§7Countdown until the Farmer", "§7Stock refills."), new ItemStack(Items.CLOCK)),
			new Entry("Claude Tracker", 0, () -> ForgeCalcConfig.showClaude, v -> ForgeCalcConfig.showClaude = v,
					List.of("§6Claude Tracker", "§7Shows when your Claude robot", "§7storage will be full."), new ItemStack(Items.OBSERVER)),
			new Entry("Ore Calculator", 0, () -> ForgeCalcConfig.showForge, v -> ForgeCalcConfig.showForge = v,
					List.of("§6Ore Calculator", "§7Best 5-stack combination in", "§7the Forge, Ore Bag and Vaults."), new ItemStack(Items.ANVIL)),
			new Entry("Slot Highlights", 0, () -> ForgeCalcConfig.showHighlights, v -> ForgeCalcConfig.showHighlights = v,
					List.of("§6Slot Highlights", "§7Overlay on the ores in the", "§7best combination."), new ItemStack(Items.GLOWSTONE_DUST)),
			new Entry("Ore Popups", 0, () -> ForgeCalcConfig.showPopups, v -> ForgeCalcConfig.showPopups = v,
					List.of("§6Ore Popups", "§7Notification when you get an", "§7ore, with its icon."), new ItemStack(Items.BELL)),
			new Entry("Buff Timers", 0, () -> ForgeCalcConfig.showBuffs, v -> ForgeCalcConfig.showBuffs = v,
					List.of("§6Buff Timers", "§7Counts down every buff you have", "§7running. Open §f/buff §7to sync."), new ItemStack(Items.BREWING_STAND)),
			new Entry("Chat Game", 0, () -> ForgeCalcConfig.showChatGame, v -> ForgeCalcConfig.showChatGame = v,
					List.of("§6Chat Game Helper", "§7Shows the unscramble/trivia answer.", "§7Learns answers and saves them.", "§8Turn on Auto-Send to type it for you."), new ItemStack(Items.PAPER)),
			new Entry("Chat Game Auto-Send", 0, () -> ForgeCalcConfig.chatGameAutoSend, v -> ForgeCalcConfig.chatGameAutoSend = v,
					List.of("§6Chat Game Auto-Send", "§7Sends the answer in chat for you", "§7as soon as it is worked out.", "§8Off = press the key yourself."), new ItemStack(Items.FEATHER)),
			new Entry("Watermark", 0, () -> ForgeCalcConfig.showWatermark, v -> ForgeCalcConfig.showWatermark = v,
					List.of("§6Watermark", "§7Small credit text under the", "§7global boost bar."), new ItemStack(Items.NAME_TAG)),

			new Entry("Quick Buttons", 1, () -> ForgeCalcConfig.showSortButton, v -> ForgeCalcConfig.showSortButton = v,
					List.of("§6Quick Buttons", "§7The whole button column.", "§7Configure it in §fMenu > BUTTONS§7."), new ItemStack(Items.OAK_BUTTON)),
			new Entry("Auto-Sync on Join", 1, () -> ForgeCalcConfig.autoSync, v -> ForgeCalcConfig.autoSync = v,
					List.of("§6Auto-Sync on Join", "§7Opens stock, claude + incubator", "§7after joining for instant HUDs."), new ItemStack(Items.ENDER_PEARL)),
			new Entry("/fly on Join", 1, () -> ForgeCalcConfig.flyOnJoin, v -> ForgeCalcConfig.flyOnJoin = v,
					List.of("§6/fly on Join", "§7Runs /fly a few seconds", "§7after you join."), new ItemStack(Items.FEATHER)),
			new Entry("Auto Join", 1, () -> ForgeCalcConfig.autoJoin, v -> ForgeCalcConfig.autoJoin = v,
					List.of("§6Auto Join", "§7Minehut lobby §8-> §f/join afkable", "§7Afkable lobby §8-> §f/server tycoon", "§7Kicked §8-> §7auto-reconnects you."), new ItemStack(Items.COMPASS)),
			new Entry("Jumpscare", 1, () -> ForgeCalcConfig.jumpscare, v -> ForgeCalcConfig.jumpscare = v,
					List.of("§6Jumpscare", "§71 in 10,000 chance per mined", "§7block... good luck."), new ItemStack(Items.WITHER_SKELETON_SKULL)),
			new Entry("Auto Pickaxe", 1, () -> ForgeCalcConfig.autoUpgradePickaxe, v -> ForgeCalcConfig.autoUpgradePickaxe = v,
					List.of("§6Auto Upgrade Pickaxe", "§7Every minute, opens your pickaxe", "§7and buys any upgrade you can afford.", "§8Runs in the background."), new ItemStack(Items.DIAMOND_PICKAXE)),
			new Entry("Auto Dominion", 1, () -> ForgeCalcConfig.autoDominion, v -> ForgeCalcConfig.autoDominion = v,
					List.of("§6Auto Dominion", "§7Every 5 minutes, walks every rarity", "§7tab and claims any ready dominion.", "§8Only runs with no GUI open."), new ItemStack(Items.CHISELED_BOOKSHELF)),
			new Entry("Learn Ore Values", 1, () -> ForgeCalcConfig.learnOreValues, v -> ForgeCalcConfig.learnOreValues = v,
					List.of("§6Learn Ore Values", "§7Reads §fOre Value §7off the lore", "§7whenever you open an ore GUI.", "§8Keeps the calculator right after", "§8a season rebalance."), new ItemStack(Items.DIAMOND_ORE)),
			new Entry("Macro", 1, () -> false, v -> {},
					List.of("§6Macro", "§7Auto-clicks for you...", "§7or does it?"), new ItemStack(Items.REDSTONE))
	);

	private final Screen parent;
	private int tab = 0;
	private int scroll = 0;
	private boolean macroPopup = false;

	public ForgeCalcSettingsScreen(Screen parent) {
		super(Text.literal("Settings"));
		this.parent = parent;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = 288;
		int panelH = 220;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - panelH / 2;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("SETTINGS", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo), px + panelW / 2, py + 7, CREAM);

		String[] tabs = {"HUD", "FEATURES", "THEMES"};
		int tabW = (panelW - 24 - 12) / 3;
		for (int i = 0; i < 3; i++) {
			boolean active = tab == i;
			if (drawBigButton(ctx, mouseX, mouseY, px + 12 + i * (tabW + 6), py + 26, tabW, 16,
					(active ? "§f§l" : "§7") + tabs[i], null) && !macroPopup) {
				tab = i;
				scroll = 0;
			}
			if (active) {
				ctx.fill(px + 12 + i * (tabW + 6), py + 42, px + 12 + i * (tabW + 6) + tabW, py + 44, GREEN);
			}
		}

		List<String> tooltip = null;
		if (tab == 2) {
			tooltip = renderThemesTab(ctx, mouseX, mouseY, px, py, panelW, panelH);
		} else {
			tooltip = renderToggleTab(ctx, mouseX, mouseY, px, py, panelW, panelH);
		}

		int half = (panelW - 30) / 2;
		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + panelH - 24, half, 18, "§f§lBACK", null) && !macroPopup) {
			close();
			return;
		}
		int third = (panelW - 36) / 3;
		if (drawBigButton(ctx, mouseX, mouseY, px + 18 + third, py + panelH - 24, third, 18, "§b§lSUGGEST", null) && !macroPopup) {
			this.client.setScreen(new SuggestScreen(this));
			return;
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 24 + third * 2, py + panelH - 24, third, 18, "§d§lLAYOUT", null) && !macroPopup) {
			this.client.setScreen(new GuiLayoutScreen(this));
			return;
		}

		if (tooltip != null && !macroPopup) {
			drawInfoTooltip(ctx, mouseX, mouseY, tooltip);
		}

		if (macroPopup) {
			int mw = 230;
			int mh = 96;
			int mx = this.width / 2 - mw / 2;
			int my = this.height / 2 - mh / 2;
			ctx.fill(0, 0, this.width, this.height, 0x90000000);
			drawWoodPanel(ctx, mx, my, mw, mh);
			ctx.fill(mx, my, mx + mw, my + 18, 0xFF6A2020);
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§c§lDO NOT MACRO"), mx + mw / 2, my + 5, CREAM);
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7This client wasn't made for cheating."), mx + mw / 2, my + 26, 0xFFFFFFFF);
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7It was made for making the §fAfkable"), mx + mw / 2, my + 38, 0xFFFFFFFF);
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§fgameplay §7easier."), mx + mw / 2, my + 50, 0xFFFFFFFF);
			if (drawBigButton(ctx, mouseX, mouseY, mx + mw / 2 - 60, my + mh - 26, 120, 18, "§a§lACKNOWLEDGE", null)) {
				macroPopup = false;
			}
		}
	}

	private List<String> renderToggleTab(DrawContext ctx, int mouseX, int mouseY, int px, int py, int panelW, int panelH) {
		List<Entry> current = new ArrayList<>();
		for (Entry entry : entries) {
			if (entry.tab() == tab) current.add(entry);
		}

		int cols = 2;
		int cellW = (panelW - 24 - 6) / 2;
		int cellH = 34;
		int rows = (current.size() + cols - 1) / cols;
		int visibleRows = 3;
		int maxScroll = Math.max(0, rows - visibleRows);
		scroll = Math.max(0, Math.min(maxScroll, scroll - pollScroll()));

		List<String> tooltip = null;
		int gridY = py + 50;

		for (int row = scroll; row < Math.min(rows, scroll + visibleRows); row++) {
			for (int col = 0; col < cols; col++) {
				int index = row * cols + col;
				if (index >= current.size()) break;
				Entry entry = current.get(index);

				int cx = px + 12 + col * (cellW + 6);
				int cy = gridY + (row - scroll) * (cellH + 6);
				boolean value = entry.get().getAsBoolean();
				boolean hovered = !macroPopup && hover(mouseX, mouseY, cx, cy, cellW, cellH);

				ctx.fill(cx - 1, cy - 1, cx + cellW + 1, cy + cellH + 1, value ? GREEN_DARK : (hovered ? PLANK_LIGHT : BORDER));
				ctx.fillGradient(cx, cy, cx + cellW, cy + cellH, hovered ? blend(PLANK, GREEN, 0.12f) : PLANK, blend(PLANK_DARK, 0xFF000000, 0.2f));
				if (value) {
					ctx.fill(cx, cy, cx + cellW, cy + 2, GREEN);
				}

				int labelX = cx + 6;
				if (entry.icon() != null) {
					ctx.drawItem(entry.icon(), cx + 4, cy + 9);
					labelX = cx + 24;
				}
				ctx.drawText(this.textRenderer, Text.literal("§f" + entry.name()), labelX, cy + 6, 0xFFFFFFFF, true);
				String sub = entry.name().equals("Macro") ? "§8???" : (value ? "§aON" : "§cOFF");
				ctx.drawText(this.textRenderer, Text.literal(sub), labelX, cy + 19, 0xFFFFFFFF, true);

				if (hovered) {
					tooltip = entry.tip();
					if (mouseClickedThisFrame) {
						mouseClickedThisFrame = false;
						playClick(this.client);
						if (entry.name().equals("Macro")) {
							macroPopup = true;
						} else {
							entry.set().accept(!value);
						}
					}
				}
			}
		}
		return tooltip;
	}

	private List<String> renderThemesTab(DrawContext ctx, int mouseX, int mouseY, int px, int py, int panelW, int panelH) {
		List<ForgeCalcTheme> builtIn = new ArrayList<>();
		for (ForgeCalcTheme theme : ForgeCalcTheme.values()) {
			if (theme != ForgeCalcTheme.CUSTOM) builtIn.add(theme);
		}
		ForgeCalcTheme[] themes = builtIn.toArray(new ForgeCalcTheme[0]);
		List<CustomTheme> customThemes = CustomTheme.listAll();
		int cols = 4;
		int cellW = (panelW - 24 - (cols - 1) * 5) / cols;
		int cellH = 30;
		int total = themes.length + customThemes.size();
		int rows = (total + cols - 1) / cols;
		int visibleRows = 3;
		int maxScroll = Math.max(0, rows - visibleRows);
		scroll = Math.max(0, Math.min(maxScroll, scroll - pollScroll()));

		int gridY = py + 50;
		for (int row = scroll; row < Math.min(rows, scroll + visibleRows); row++) {
			for (int col = 0; col < cols; col++) {
				int index = row * cols + col;
				if (index >= total) break;

				boolean isCustom = index >= themes.length;
				CustomTheme custom = isCustom ? customThemes.get(index - themes.length) : null;
				ForgeCalcTheme theme = isCustom ? null : themes[index];

				String themeLabel = isCustom ? custom.name : theme.label;
				int swatchLight = isCustom ? custom.plankLight : theme.plankLight;
				int swatchDark = isCustom ? custom.plankDark : theme.plankDark;
				int swatchAccent = isCustom ? custom.accent : theme.accent;
				boolean isActive = isCustom
						? ForgeCalcTheme.current() == ForgeCalcTheme.CUSTOM
								&& ForgeCalcTheme.CUSTOM.label.equalsIgnoreCase(custom.name)
						: theme == ForgeCalcTheme.current();

				int cx = px + 12 + col * (cellW + 5);
				int cy = gridY + (row - scroll) * (cellH + 5);
				boolean active = isActive;
				boolean hovered = !macroPopup && hover(mouseX, mouseY, cx, cy, cellW, cellH);

				ctx.fill(cx - 1, cy - 1, cx + cellW + 1, cy + cellH + 1, active ? swatchAccent : (hovered ? 0xFFFFFFFF : 0xFF000000));
				ctx.fillGradient(cx, cy, cx + cellW, cy + cellH, swatchLight, swatchDark);
				ctx.fill(cx, cy, cx + cellW, cy + 3, swatchAccent);
				String label = themeLabel.length() > 10 ? themeLabel.substring(0, 9) + "…" : themeLabel;
				ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal((active ? "§a§l" : "§f") + label), cx + cellW / 2, cy + 10, 0xFFFFFFFF);
				if (isCustom) {
					ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8custom"), cx + cellW / 2, cy + 20, 0xFFFFFFFF);
				}

				if (hovered && mouseClickedThisFrame) {
					mouseClickedThisFrame = false;
					playClick(this.client);
					if (isCustom) {
						custom.applyTo();
						ForgeCalcConfig.theme = "custom:" + custom.name;
						ForgeCalcTheme.useCustom();
					} else {
						ForgeCalcConfig.theme = theme.label;
						ForgeCalcTheme.set(theme.label);
					}
					FarmScreen.applyTheme();
					ForgeOreCalculatorClient.applyPanelTheme();
					ForgeCalcConfig.save();
				}
			}
		}

		int barY = py + panelH - 48;
		int half = (panelW - 30) / 2;
		if (drawBigButton(ctx, mouseX, mouseY, px + 12, barY, half, 18, "§d§lCREATE THEME", null) && !macroPopup) {
			this.client.setScreen(new ThemeBuilderScreen(this, null));
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 18 + half, barY, half, 18, "§b§lBROWSE", null) && !macroPopup) {
			this.client.setScreen(new ThemeBrowserScreen(this));
		}
		return null;
	}

	@Override
	public void close() {
		ForgeCalcConfig.save();
		this.client.setScreen(parent);
	}
}
