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
			new Tile("VAULTS", "§d", new ItemStack(Items.SPYGLASS)),
			new Tile("GANG", "§b", new ItemStack(Items.WHITE_BANNER)),
			new Tile("KEYS", "§c", new ItemStack(Items.TRIPWIRE_HOOK)),
			new Tile("BUTTONS", "§6", new ItemStack(Items.OAK_BUTTON)),
			new Tile("TRASH", "§c", new ItemStack(Items.LAVA_BUCKET)),
			new Tile("STATS", "§b", new ItemStack(Items.PLAYER_HEAD)),
			new Tile("UPGRADER", "§3", new ItemStack(Items.NETHER_STAR))
	};

	public ForgeCalcMenuScreen() {
		super(Text.literal("AFKABLE CLIENT"));
		ThemeBackgrounds.invalidate();
	}

	/** Long usernames would overflow the footer button, so clip them with an ellipsis. */
	private static String fitName(String name) {
		return name.length() > 12 ? name.substring(0, 11) + "…" : name;
	}

	private String fitText(String value, int width) {
		if (this.textRenderer.getWidth(value) <= width) return value;
		int end = value.length();
		while (end > 0 && this.textRenderer.getWidth(value.substring(0, end)) > width) end--;
		return end > 0 ? value.substring(0, end) + "…" : "";
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

	int panelW = Math.min(340, this.width - 24);
		int panelH = Math.min(300, this.height - 28);
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - panelH / 2;

		boolean customBg = ThemeBackgrounds.hasBackground();
		if (customBg) {
			ctx.fill(px - 2, py - 2, px + panelW + 2, py + panelH + 2, 0x90000000);
			ctx.fill(px, py, px + panelW, py + panelH, 0x50100A06);
		} else {
			drawWoodPanel(ctx, px, py, panelW, panelH);
		}

		// header: discord chip, title block on the left, power switch on the right
		ctx.drawText(this.textRenderer, Text.literal("§f§lAFKable Client"), px + 36, py + 8, 0xFFFFFFFF, true);
		ctx.drawText(this.textRenderer, Text.literal("Mob Tycoon  ·  v" + DevLogsScreen.latestVersion()), px + 36, py + 20, 0xFF9FB2C8, false);
		ctx.fill(px + 10, py + 34, px + panelW - 10, py + 35, 0x26FFFFFF);

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
		int switchX = px + panelW - 34;
		ctx.drawText(this.textRenderer, Text.literal(on ? "§fOn" : "§7Off"), switchX - 20, py + 13, 0xFFFFFFFF, false);
		drawSwitch(ctx, switchX, py + 12, on);
		if (hover(mouseX, mouseY, switchX - 22, py + 6, 50, 22) && mouseClickedThisFrame) {
			mouseClickedThisFrame = false;
			playClick(this.client);
			ForgeCalcConfig.clientEnabled = !on;
			ForgeCalcConfig.save();
		}

		// dev button floats in the SCREEN top-right corner, outside the menu panel
		boolean isDev = this.client != null && ForgeOreCalculatorClient.isDev(this.client);
		if (isDev && drawBigButton(ctx, mouseX, mouseY, this.width - 66, 6, 58, 22, "§d§lDEV PANEL", null)) {
			this.client.setScreen(new DevPanelScreen(this));
			return;
		}

		// Spacious two-column launcher cards make every section easy to hit and read.
		int cols = 2;
		int tileW = (panelW - 24 - (cols - 1) * 8) / cols;
		// tiles shrink to whatever room is left above the footer, so short windows never overlap
		int gridY = py + 43;
		int tileRows = (TILES.length + cols - 1) / cols;
		int room = (py + panelH - 38) - gridY;
		int tileH = Math.max(20, Math.min(45, (room - (tileRows - 1) * 8) / tileRows));
		boolean showSubtitle = tileH >= 32;

		for (int i = 0; i < TILES.length; i++) {
			Tile tile = TILES[i];
			int tx = px + 12 + (i % cols) * (tileW + 8);
			int ty = gridY + (i / cols) * (tileH + 8);
			boolean hovered = hover(mouseX, mouseY, tx, ty, tileW, tileH);

			drawGlass(ctx, tx, ty, tileW, tileH, hovered ? 0x30FFFFFF : 0x1CFFFFFF, hovered ? 0xC0FFFFFF : 0x70FFFFFF);
			int chip = Math.min(20, tileH - 8);
			int chipY = ty + (tileH - chip) / 2;
			roundedFill(ctx, tx + 8, chipY, tx + 8 + chip, chipY + chip, 0x22FFFFFF);
			ctx.drawItem(tile.icon(), tx + 8 + (chip - 16) / 2, chipY + (chip - 16) / 2);
			String subtitle = switch (tile.label()) {
				case "HUD" -> "Move and scale overlays";
				case "CONFIG" -> "Tune client features";
				case "VAULTS" -> "Search your storage";
				case "GANG" -> "Gang tools and chat";
				case "KEYS" -> "Set your shortcuts";
				case "BUTTONS" -> "Choose quick actions";
				case "TRASH" -> "Safe cleanup rules";
				case "UPGRADER" -> "Buy upgrades for you";
			default -> "Profile and badges";
			};
			int textX = tx + 16 + chip;
			if (showSubtitle) {
				ctx.drawText(this.textRenderer, Text.literal("§f§l" + fitText(tile.label(), tileW - 46)), textX, ty + tileH / 2 - 10, 0xFFFFFFFF, true);
				ctx.drawText(this.textRenderer, Text.literal(fitText(subtitle, tileW - 46)), textX, ty + tileH / 2 + 3, 0xFFA9B8CC, false);
			} else {
				ctx.drawText(this.textRenderer, Text.literal("§f§l" + fitText(tile.label(), tileW - 46)), textX, ty + (tileH - 8) / 2, 0xFFFFFFFF, true);
			}

			if (hovered && mouseClickedThisFrame) {
				mouseClickedThisFrame = false;
				playClick(this.client);
				switch (i) {
					case 0 -> this.client.setScreen(new HudEditScreen(this));
					case 1 -> this.client.setScreen(new ForgeCalcSettingsScreen(this));
					case 2 -> this.client.setScreen(new VaultSearchScreen(this));
					case 3 -> this.client.setScreen(new GangChatScreen(this));
					case 4 -> this.client.setScreen(new KeybindsScreen(this));
					case 5 -> this.client.setScreen(new ButtonsScreen(this));
					case 6 -> this.client.setScreen(new TrashScreen(this));
					case 7 -> this.client.setScreen(new StatsScreen(this));
					case 8 -> this.client.setScreen(new UpgradeAutoScreen(this));
				}
				return;
			}
		}

		// footer
		int footY = py + panelH - 30;
		ctx.fill(px + 10, footY - 5, px + panelW - 10, footY - 4, 0x26FFFFFF);
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
