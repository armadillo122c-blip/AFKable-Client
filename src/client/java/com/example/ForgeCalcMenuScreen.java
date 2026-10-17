package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.net.URI;
import java.util.List;

public class ForgeCalcMenuScreen extends FarmScreen {

	private record Tile(String label, String color, ItemStack icon) {
	}

	private static final Tile[] TILES = {
			new Tile("HUD", "§e", new ItemStack(Items.ITEM_FRAME)),
			new Tile("CONFIG", "§a", new ItemStack(Items.COMPARATOR)),
			new Tile("STOCK", "§6", new ItemStack(Items.BELL)),
			new Tile("VAULTS", "§d", new ItemStack(Items.SPYGLASS)),
			new Tile("GANG", "§b", new ItemStack(Items.WHITE_BANNER)),
			new Tile("KEYS", "§c", new ItemStack(Items.TRIPWIRE_HOOK)),
			new Tile("BUTTONS", "§6", new ItemStack(Items.OAK_BUTTON)),
			new Tile("TRASH", "§c", new ItemStack(Items.LAVA_BUCKET)),
			new Tile("STATS", "§b", new ItemStack(Items.PLAYER_HEAD)),
			new Tile("ACTIVITY", "§b", new ItemStack(Items.WRITABLE_BOOK)),
			new Tile("INFO", "§f", new ItemStack(Items.BOOK))
	};

	public ForgeCalcMenuScreen() {
		super(Text.literal("AFKABLE CLIENT"));
		ThemeBackgrounds.invalidate();
	}

	/** Long usernames would overflow the footer button, so clip them with an ellipsis. */
	private static String fitName(String name) {
		return name.length() > 12 ? name.substring(0, 11) + "…" : name;
	}

	@Override
	protected void init() {
		if (ForgeOreCalculatorClient.isBlocked() && this.client != null) {
			this.client.setScreen(null);
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = 300;
		int panelH = 302;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - panelH / 2;

		boolean customBg = ThemeBackgrounds.hasBackground();
		if (customBg) {
			ctx.fill(px - 2, py - 2, px + panelW + 2, py + panelH + 2, 0x90000000);
			ctx.fill(px, py, px + panelW, py + panelH, 0x50100A06);
		} else {
			drawWoodPanel(ctx, px, py, panelW, panelH);
		}

		// header banner
		ctx.fillGradient(px, py, px + panelW, py + 34, PLANK_DARK, blend(PLANK_DARK, 0xFF000000, 0.35f));
		ctx.fill(px, py + 34, px + panelW, py + 35, GREEN);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("AFKABLE CLIENT", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo), px + panelW / 2, py + 8, CREAM);
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7by §fAFKartel §8- §7v" + DevLogsScreen.latestVersion()), px + panelW / 2, py + 20, 0xFFCBB088);

		// discord icon, header left
		int dcSize = 24;
		int dcX = px + 5;
		int dcY = py + 5;
		boolean dcHover = hover(mouseX, mouseY, dcX, dcY, dcSize, dcSize);
		boolean customIcon = ThemeBackgrounds.renderIcon(ctx, "discord", dcX, dcY, dcSize, dcSize);
		if (!customIcon) {
			int blurple = dcHover ? 0xFF7A85F5 : 0xFF5865F2;
			ctx.fill(dcX + 3, dcY, dcX + dcSize - 3, dcY + dcSize, blurple);
			ctx.fill(dcX + 1, dcY + 2, dcX + dcSize - 1, dcY + dcSize - 2, blurple);
			ctx.fill(dcX, dcY + 4, dcX + dcSize, dcY + dcSize - 4, blurple);
			ctx.fill(dcX + 6, dcY + 8, dcX + 18, dcY + 10, 0xFFFFFFFF);
			ctx.fill(dcX + 7, dcY + 10, dcX + 10, dcY + 14, 0xFFFFFFFF);
			ctx.fill(dcX + 14, dcY + 10, dcX + 17, dcY + 14, 0xFFFFFFFF);
			ctx.fill(dcX + 6, dcY + 14, dcX + 18, dcY + 16, 0xFFFFFFFF);
			ctx.fill(dcX + 4, dcY + 16, dcX + 8, dcY + 18, 0xFFFFFFFF);
			ctx.fill(dcX + 16, dcY + 16, dcX + 20, dcY + 18, 0xFFFFFFFF);
			ctx.fill(dcX + 8, dcY + 11, dcX + 9, dcY + 13, blurple);
			ctx.fill(dcX + 15, dcY + 11, dcX + 16, dcY + 13, blurple);
		} else if (dcHover) {
			ctx.fill(dcX, dcY, dcX + dcSize, dcY + dcSize, 0x30FFFFFF);
		}
		if (dcHover) {
			drawInfoTooltip(ctx, dcX, dcY + dcSize + 4, List.of("§fJoin our Discord!", "§7discord.gg/AmqYWweCUw"));
			if (mouseClickedThisFrame) {
				mouseClickedThisFrame = false;
				playClick(this.client);
				try {
					Util.getOperatingSystem().open(new URI("https://discord.gg/AmqYWweCUw"));
				} catch (Exception ignored) {
				}
			}
		}

		// power switch, header right
		boolean on = ForgeCalcConfig.clientEnabled;
		if (drawBigButton(ctx, mouseX, mouseY, px + panelW - 62, py + 6, 56, 22, on ? "§a§lON" : "§c§lOFF", null)) {
			ForgeCalcConfig.clientEnabled = !on;
			ForgeCalcConfig.save();
		}

		// dev button floats in the SCREEN top-right corner, outside the menu panel
		boolean isDev = this.client != null && ForgeOreCalculatorClient.isDev(this.client);
		if (isDev && drawBigButton(ctx, mouseX, mouseY, this.width - 66, 6, 58, 22, "§d§lDEV PANEL", null)) {
			this.client.setScreen(new DevPanelScreen(this));
			return;
		}

		// 3x2 tile grid
		int cols = 4;
		int tileW = (panelW - 24 - (cols - 1) * 8) / cols;
		int tileH = 62;
		int gridY = py + 44;

		for (int i = 0; i < TILES.length; i++) {
			Tile tile = TILES[i];
			int tx = px + 12 + (i % cols) * (tileW + 8);
			int ty = gridY + (i / cols) * (tileH + 8);
			boolean hovered = hover(mouseX, mouseY, tx, ty, tileW, tileH);

			ctx.fill(tx - 1, ty - 1, tx + tileW + 1, ty + tileH + 1, hovered ? GREEN : BORDER);
			ctx.fillGradient(tx, ty, tx + tileW, ty + tileH,
					hovered ? blend(PLANK_LIGHT, GREEN, 0.25f) : PLANK_LIGHT,
					hovered ? PLANK : PLANK_DARK);
			ctx.fill(tx, ty, tx + tileW, ty + 2, hovered ? GREEN : blend(PLANK_LIGHT, 0xFFFFFFFF, 0.2f));

			ctx.fill(tx + tileW / 2 - 12, ty + 8, tx + tileW / 2 + 12, ty + 32, 0x38000000);
			ctx.drawItem(tile.icon(), tx + tileW / 2 - 8, ty + 12);
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(tile.color() + "§l" + tile.label()), tx + tileW / 2, ty + 40, CREAM);

			if (hovered && mouseClickedThisFrame) {
				mouseClickedThisFrame = false;
				playClick(this.client);
				switch (i) {
					case 0 -> this.client.setScreen(new HudEditScreen(this));
					case 1 -> this.client.setScreen(new ForgeCalcSettingsScreen(this));
					case 2 -> this.client.setScreen(new StockNotifierScreen(this));
					case 3 -> this.client.setScreen(new VaultSearchScreen(this));
					case 4 -> this.client.setScreen(new GangChatScreen(this));
					case 5 -> this.client.setScreen(new KeybindsScreen(this));
					case 6 -> this.client.setScreen(new ButtonsScreen(this));
					case 7 -> this.client.setScreen(new TrashScreen(this));
					case 8 -> this.client.setScreen(new StatsScreen(this));
					case 9 -> this.client.setScreen(new LiveActivityScreen(this));
					case 10 -> this.client.setScreen(new ForgeCalcInfoScreen(this));
				}
				return;
			}
		}

		// footer
		int footY = py + panelH - 30;
		ctx.fill(px, footY - 4, px + panelW, footY - 3, FRAME_DARK);
		int half = (panelW - 30) / 2;
		if (drawBigButton(ctx, mouseX, mouseY, px + 12, footY, half, 20, "§e§lDEV LOGS", null)) {
			this.client.setScreen(new DevLogsScreen(this));
			return;
		}
		// MY STATS looks the player up before the screen opens, so it lands on data
		// rather than on an empty form
		String self = this.client != null && this.client.player != null
				? this.client.player.getName().getString() : "";
		String label = self.isEmpty() ? "§b§lMY STATS" : "§b§l" + fitName(self);
		if (drawBigButton(ctx, mouseX, mouseY, px + 18 + half, footY, half, 20, label,
				self.isEmpty() ? null : "§8my stats")) {
			if (!self.isEmpty()) {
				PlayerStatsNet.fetch(self, PlayerStatsNet.SLOT_A);
			}
			this.client.setScreen(new StatsScreen(this));
			return;
		}

		if (!on) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§c§oClient disabled - everything is off"), px + panelW / 2, py + panelH + 6, 0xFFFFFFFF);
		}
		ctx.drawText(this.textRenderer, Text.literal("§8Made by §fArmadillo122"), px + 2, py + panelH + 6, 0xFF888888, true);
	}

	@Override
	public void close() {
		ForgeCalcConfig.save();
		this.client.setScreen(null);
	}
}
