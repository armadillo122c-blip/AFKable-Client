package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class VaultSearchScreen extends FarmScreen {

	private final Screen parent;
	private TextFieldWidget searchField;

	public VaultSearchScreen(Screen parent) {
		super(Text.literal("Vault Search"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int panelW = 220;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - 122;
		// resize recreates widgets, so carry the typed text across
		String previousText = searchField != null ? searchField.getText() : "";
		searchField = new TextFieldWidget(this.textRenderer, px + 12, py + 40, panelW - 24, 13, Text.literal(""));
		searchField.setMaxLength(30);
		if (!previousText.isEmpty()) searchField.setText(previousText);
		addSelectableChild(searchField);
	}

	private void startSearch(SortManager.Mode mode, boolean withdraw) {
		if (ForgeOreCalculatorClient.INSTANCE != null && this.client != null && this.client.player != null) {
			ForgeOreCalculatorClient.INSTANCE.startVaultSearch(this.client, mode, withdraw);
			this.client.setScreen(null);
		}
	}

	private void startNameSearch(String query, boolean withdraw) {
		if (ForgeOreCalculatorClient.INSTANCE != null && this.client != null && this.client.player != null) {
			ForgeOreCalculatorClient.INSTANCE.startVaultNameSearch(this.client, query, withdraw);
			this.client.setScreen(null);
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = 220;
		int panelH = 262;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - 122;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawItem(new ItemStack(Items.SPYGLASS), px + 5, py + 3);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("VAULT SEARCH", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo), px + panelW / 2, py + 7, CREAM);

		ctx.drawText(this.textRenderer, Text.literal("§7Search by §fname§7:"), px + 12, py + 28, 0xFFFFFFFF, true);
		if (searchField != null) {
			searchField.render(ctx, mouseX, mouseY, delta);
		}
		String query = searchField != null ? searchField.getText().trim() : "";
		int qy = py + 57;
		if (drawBigButton(ctx, mouseX, mouseY, px + 12, qy, (panelW - 30) / 2, 16, "§fFIND", null)) {
			if (!query.isEmpty()) startNameSearch(query, false);
			return;
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 18 + (panelW - 30) / 2, qy, (panelW - 30) / 2, 16, "§aGRAB", null)) {
			if (!query.isEmpty()) startNameSearch(query, true);
			return;
		}

		ctx.drawText(this.textRenderer, Text.literal("§7Or by §fcategory§7:"), px + 12, py + 78, 0xFFFFFFFF, true);
		int y = py + 89;
		int w = panelW - 24;
		SortManager.Mode[] modes = {SortManager.Mode.ORES, SortManager.Mode.MOBS, SortManager.Mode.POTIONS,
				SortManager.Mode.CRYSTALS, SortManager.Mode.TIME};
		String[] labels = {"§bOres", "§aMobs", "§dPotions", "§5Crystals", "§eTime Mach"};

		for (int i = 0; i < modes.length; i++) {
			ctx.drawText(this.textRenderer, Text.literal("§f" + labels[i].substring(2)), px + 14, y + 5, 0xFFFFFFFF, true);
			if (drawBigButton(ctx, mouseX, mouseY, px + w - 76, y, 42, 18, "§fFIND", null)) {
				startSearch(modes[i], false);
				return;
			}
			if (drawBigButton(ctx, mouseX, mouseY, px + w - 30, y, 42, 18, "§aGRAB", null)) {
				startSearch(modes[i], true);
				return;
			}
			y += 21;
		}

		y += 3;
		ctx.drawText(this.textRenderer, Text.literal("§7Quick open §fvault§7:"), px + 12, y, 0xFFFFFFFF, true);
		y += 11;
		int pvW = 12;
		for (int n = 1; n <= 15; n++) {
			int col = (n - 1) % 15;
			int bx = px + 12 + col * (pvW + 1);
			if (drawBigButton(ctx, mouseX, mouseY, bx, y, pvW, 12, "§f" + (n < 10 ? String.valueOf(n) : String.valueOf(n)), null)) {
				if (ForgeOreCalculatorClient.INSTANCE != null && this.client != null && this.client.player != null) {
					ForgeOreCalculatorClient.INSTANCE.openPv(this.client, n);
					this.client.setScreen(null);
					return;
				}
			}
		}

		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + panelH - 24, w, 18, "§f§lBACK", null)) {
			close();
		}
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}
}
