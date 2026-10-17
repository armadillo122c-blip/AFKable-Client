package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.List;

public class ForgeCalcInfoScreen extends FarmScreen {

	private static final List<String> LINES = List.of(
			"§f§lAFKABLE CLIENT §7by §fAFKartel",
			"§7Made by §fArmadillo122 §8- §7Vibcoded",
			"",
			"§6§lTRACKERS",
			"§7- §fRestock §7countdown with auto re-check",
			"§7- §fClaude §7robot storage timer + bar",
			"§7- §fEggs §7list, every egg with its own bar",
			"§7- §fOre Calculator §7best 5-stack combo",
			"",
			"§6§lQUICK BUTTONS",
			"§7- §fSORT §7(right-click cycles category)",
			"§7- §fTRASH §7safe-dumps junk, protects good loot",
			"§7- §fSTOCK CLAUDE OREBAG DIARY CRYSTALS",
			"§7- §fEGGS INCUB PV 1-15 TINKER SMELT ALL",
			"",
			"§6§lTOOLS",
			"§7- §fStock Notifier §7with watchlist + Buy All",
			"§7- §fVault Search §7find or grab from /pv 1-15",
			"§7- §fLazy Storage §7auto-deposits your loot",
			"§7- §fAuto Tinker §7salvages low crystals",
			"",
			"§6§lSTYLE",
			"§7- §f10 themes §7with animated backgrounds",
			"§7- §7Custom PNG backgrounds from config folder",
			"§7- §fHUD editor§7: drag, resize, opacity, grids",
			"",
			"§6§lSOCIAL",
			"§7- §7Tab badges: §6\u2692 §c\u2764 §a+ §e\u2605 §6♛ §b◆ §d✦",
			"§7- §7Auto peer discovery via ntfy.sh",
			"",
			"§6§lSAFETY",
			"§7- §fAnti-Cheat §7nuker auto-disconnect",
			"§7- §7This client is NOT for cheating.",
			"",
			"§8Press §7I §8to open the menu anytime.");

	private final Screen parent;
	private int scroll = 0;

	public ForgeCalcInfoScreen(Screen parent) {
		super(Text.literal("Info"));
		this.parent = parent;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = 250;
		int panelH = 230;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - panelH / 2;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawItem(new ItemStack(Items.BOOK), px + 5, py + 3);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("INFO", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo), px + panelW / 2, py + 7, CREAM);

		int visible = 17;
		int maxScroll = Math.max(0, LINES.size() - visible);
		scroll = Math.max(0, Math.min(maxScroll, scroll - pollScroll()));

		int y = py + 28;
		for (int i = scroll; i < Math.min(LINES.size(), scroll + visible); i++) {
			ctx.drawText(this.textRenderer, Text.literal(LINES.get(i)), px + 12, y, 0xFFFFFFFF, true);
			y += 10;
		}

		int trackX = px + panelW - 8;
		int trackY = py + 28;
		int trackH = visible * 10;
		ctx.fill(trackX, trackY, trackX + 3, trackY + trackH, 0x50000000);
		if (maxScroll > 0) {
			int thumbH = Math.max(12, trackH * visible / LINES.size());
			int thumbY = trackY + (trackH - thumbH) * scroll / maxScroll;
			ctx.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, GREEN);
		}

		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + panelH - 24, panelW - 24, 18, "\u00a7f\u00a7lBACK", null)) {
			close();
		}
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}
}
