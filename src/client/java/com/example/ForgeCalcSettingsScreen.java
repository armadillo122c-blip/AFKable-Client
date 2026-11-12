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
			new Entry("Augment Timer", 0, () -> ForgeCalcConfig.showAugment, v -> ForgeCalcConfig.showAugment = v,
					List.of("§6Augment Timer", "§7Reads your active research time", "§7from the Augments menu."), new ItemStack(Items.HONEYCOMB)),
			new Entry("Claude Tracker", 0, () -> ForgeCalcConfig.showClaude, v -> ForgeCalcConfig.showClaude = v,
					List.of("§6Claude Tracker", "§7Shows when your Claude robot", "§7storage will be full."), new ItemStack(Items.OBSERVER)),
			new Entry("Buff Timers", 0, () -> ForgeCalcConfig.showBuffs, v -> ForgeCalcConfig.showBuffs = v,
					List.of("§6Buff Timers", "§7Counts down every buff you have", "§7running. Open §f/buff §7to sync."), new ItemStack(Items.BREWING_STAND)),
			new Entry("Chat Game", 0, () -> ForgeCalcConfig.showChatGame, v -> ForgeCalcConfig.showChatGame = v,
					List.of("§6Chat Game Helper", "§7Shows the riddle, unscramble and", "§7maths answers. Learns new ones.", "§8You send the answer yourself."), new ItemStack(Items.PAPER)),
			new Entry("Income", 0, () -> ForgeCalcConfig.showIncome, v -> ForgeCalcConfig.showIncome = v,
					List.of("§6Income", "§7Your last 5-minute SUMMARY, per", "§7hour, and your rank on it."), new ItemStack(Items.EMERALD)),
			new Entry("Bite Alert", 0, () -> ForgeCalcConfig.biteAlert, v -> ForgeCalcConfig.biteAlert = v,
					List.of("§6Bite Alert", "§7Dings and flashes BITE! when a", "§7fish takes your hook.", "§8You still reel in yourself."), new ItemStack(Items.FISHING_ROD)),
			new Entry("Watermark", 0, () -> ForgeCalcConfig.showWatermark, v -> ForgeCalcConfig.showWatermark = v,
					List.of("§6Watermark", "§7Small credit text under the", "§7global boost bar."), new ItemStack(Items.NAME_TAG)),

			new Entry("Quick Buttons", 1, () -> ForgeCalcConfig.showSortButton, v -> ForgeCalcConfig.showSortButton = v,
					List.of("§6Quick Buttons", "§7The whole button column.", "§7Configure it in §fMenu > BUTTONS§7."), new ItemStack(Items.OAK_BUTTON)),
			new Entry("Auto-Sync on Join", 1, () -> ForgeCalcConfig.autoSync, v -> ForgeCalcConfig.autoSync = v,
					List.of("§6Auto-Sync on Join", "§7Runs the commands in your auto-sync", "§7list after joining the server."), new ItemStack(Items.ENDER_PEARL)),
			new Entry("/fly on Join", 1, () -> ForgeCalcConfig.flyOnJoin, v -> ForgeCalcConfig.flyOnJoin = v,
					List.of("§6/fly on Join", "§7Runs /fly a few seconds", "§7after you join."), new ItemStack(Items.FEATHER)),
			new Entry("Auto Join", 1, () -> ForgeCalcConfig.autoJoin, v -> ForgeCalcConfig.autoJoin = v,
					List.of("§6Auto Join", "§7Minehut lobby §8-> §f/join afkable", "§7Afkable lobby §8-> §f/server tycoon", "§7Kicked §8-> §7auto-reconnects you."), new ItemStack(Items.COMPASS)),
			new Entry("Auto Dominion", 1, () -> ForgeCalcConfig.autoDominion, v -> ForgeCalcConfig.autoDominion = v,
					List.of("§6Auto Dominion", "§7Every 5 minutes, walks every rarity", "§7tab and claims any ready dominion.", "§8Only runs with no GUI open."), new ItemStack(Items.CHISELED_BOOKSHELF)),
			new Entry("Client Icons", 1, () -> ForgeCalcConfig.starSync, v -> ForgeCalcConfig.starSync = v,
					List.of("§6Client Icons", "§7Puts a small icon next to players", "§7in TAB who also run this client.", "§cShares your name + UUID on a", "§cpublic ntfy.sh topic. Off by default."), new ItemStack(Items.NAME_TAG))
	);

	private final Screen parent;
	private int tab = 0;
	private int scroll = 0;

	private String fitText(String value, int width) {
		int end = value.length();
		while (end > 0 && this.textRenderer.getWidth(value.substring(0, end)) > width) end--;
		if (end < value.length() && end > 0) return value.substring(0, end) + "…";
		return value.substring(0, end);
	}

	public ForgeCalcSettingsScreen(Screen parent) {
		super(Text.literal("Settings"));
		this.parent = parent;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = Math.min(360, this.width - 24);
		int panelH = Math.min(286, this.height - 24);
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - panelH / 2;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		drawHeader(ctx, px, py, panelW, "Settings", "v" + DevLogsScreen.latestVersion());

		int picked = drawSegmented(ctx, mouseX, mouseY, px + 12, py + 30, panelW - 24, 16, new String[]{"HUD", "Features"}, tab);
		if (picked >= 0) {
			tab = picked;
			scroll = 0;
		}

		List<String> tooltip = renderToggleTab(ctx, mouseX, mouseY, px, py, panelW, panelH);

		int footerY = py + panelH - 25;
		int footerW = (panelW - 36) / 3;
		if (drawBigButton(ctx, mouseX, mouseY, px + 12, footerY, footerW, 18, "§f§lBACK", null)) {
			close();
			return;
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 18 + footerW, footerY, footerW, 18, "§b§lSUGGEST", null)) {
			this.client.setScreen(new SuggestScreen(this));
			return;
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 24 + footerW * 2, footerY, footerW, 18, "§d§lLAYOUT", null)) {
			this.client.setScreen(new GuiLayoutScreen(this));
			return;
		}

		if (tooltip != null) {
			drawInfoTooltip(ctx, mouseX, mouseY, tooltip);
		}

	}

	private List<String> renderToggleTab(DrawContext ctx, int mouseX, int mouseY, int px, int py, int panelW, int panelH) {
		List<Entry> current = new ArrayList<>();
		for (Entry entry : entries) {
			if (entry.tab() != tab) continue;
			current.add(entry);
		}

		int cols = 2;
		int cellW = (panelW - 24 - 6) / 2;
		int cellH = 42;
		int rows = (current.size() + cols - 1) / cols;
		int visibleRows = Math.max(1, Math.min(4, (panelH - 84) / (cellH + 6)));
		int maxScroll = Math.max(0, rows - visibleRows);
		scroll = Math.max(0, Math.min(maxScroll, scroll - pollScroll()));

		List<String> tooltip = null;
		int gridY = py + 54;

		for (int row = scroll; row < Math.min(rows, scroll + visibleRows); row++) {
			for (int col = 0; col < cols; col++) {
				int index = row * cols + col;
				if (index >= current.size()) break;
				Entry entry = current.get(index);

				int cx = px + 12 + col * (cellW + 6);
				int cy = gridY + (row - scroll) * (cellH + 6);
				boolean value = entry.get().getAsBoolean();
				boolean hovered = hover(mouseX, mouseY, cx, cy, cellW, cellH);

				drawGlass(ctx, cx, cy, cellW, cellH, hovered ? 0x30FFFFFF : 0x1CFFFFFF, hovered ? 0xC0FFFFFF : 0x70FFFFFF);

				int labelX = cx + 8;
				if (entry.icon() != null) {
					roundedFill(ctx, cx + 6, cy + 6, cx + 26, cy + 26, 0x22FFFFFF);
					ctx.drawItem(entry.icon(), cx + 8, cy + 8);
					labelX = cx + 32;
				}
				int switchX = cx + cellW - 30;
				int labelW = Math.max(28, switchX - labelX - 10);
				ctx.drawText(this.textRenderer, Text.literal(fitText(entry.name(), labelW)), labelX, cy + 8, 0xFFF3F6FC, true);
				String description = entry.tip().size() > 1 ? entry.tip().get(1).replaceAll("§[0-9a-fk-or]", "") : "";
				ctx.drawText(this.textRenderer, Text.literal(fitText(description, labelW)), labelX, cy + 22, 0xFFA9B8CC, false);
				drawSwitch(ctx, switchX, cy + cellH / 2 - 5, value);

				if (hovered) {
					tooltip = entry.tip();
					if (mouseClickedThisFrame) {
						mouseClickedThisFrame = false;
						playClick(this.client);
						entry.set().accept(!value);
					}
				}
			}
		}
		return tooltip;
	}

	@Override
	public void close() {
		ForgeCalcConfig.save();
		this.client.setScreen(parent);
	}
}
