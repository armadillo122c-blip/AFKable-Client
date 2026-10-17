package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Dev-only control panel. Laid out as labelled cards rather than a flat stack of
 * buttons, with the destructive jumpscare tools boxed off in their own red zone.
 */
public class DevPanelScreen extends FarmScreen {

	private static final int PANEL_W = 300;
	private static final int PANEL_H = 320;

	private final Screen parent;
	private TextFieldWidget scareField;
	private String status = "";
	private long statusUntil = 0;

	public DevPanelScreen(Screen parent) {
		super(Text.literal("Dev Panel"));
		this.parent = parent;
	}

	private int panelX() {
		return this.width / 2 - PANEL_W / 2;
	}

	private int panelY() {
		return this.height / 2 - PANEL_H / 2;
	}

	@Override
	protected void init() {
		scareField = new TextFieldWidget(this.textRenderer, panelX() + 16, panelY() + 262, PANEL_W - 32, 14, Text.literal(""));
		scareField.setMaxLength(20);
		scareField.setPlaceholder(Text.literal("§8username, or leave empty"));
		addSelectableChild(scareField);
	}

	private void setStatus(String text) {
		status = text;
		statusUntil = System.currentTimeMillis() + 6000;
	}

	/** Section header: a coloured rule with a title sitting on it. */
	private int drawSection(DrawContext ctx, int x, int y, int w, String title, int accent) {
		ctx.fill(x, y + 4, x + w, y + 5, (0x50 << 24) | (accent & 0xFFFFFF));
		int textW = this.textRenderer.getWidth(title.replaceAll("§[0-9a-fk-or]", "")) + 8;
		ctx.fill(x, y, x + textW, y + 10, 0xB0000000);
		ctx.fill(x, y, x + 2, y + 10, 0xFF000000 | (accent & 0xFFFFFF));
		ctx.drawText(this.textRenderer, Text.literal(title), x + 5, y + 1, 0xFFFFFFFF, true);
		return y + 14;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int px = panelX();
		int py = panelY();
		int w = PANEL_W - 32;
		int half = (w - 8) / 2;
		int third = (w - 16) / 3;

		drawWoodPanel(ctx, px, py, PANEL_W, PANEL_H);

		// header
		ctx.fillGradient(px, py, px + PANEL_W, py + 30, PLANK_DARK, blend(PLANK_DARK, 0xFF000000, 0.4f));
		ctx.fill(px, py + 30, px + PANEL_W, py + 31, 0xFFD060FF);
		ctx.drawCenteredTextWithShadow(this.textRenderer,
				TextFx.gradient("DEV PANEL", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo),
				px + PANEL_W / 2, py + 7, CREAM);
		String who = this.client.player != null ? this.client.player.getName().getString() : "?";
		ctx.drawCenteredTextWithShadow(this.textRenderer,
				Text.literal("§8signed in as §d" + who), px + PANEL_W / 2, py + 19, 0xFFCBB088);

		int x = px + 16;
		int y = py + 38;

		// ---- broadcast ----
		y = drawSection(ctx, x, y, w, "§d▸ BROADCAST", 0xD060FF);
		if (drawBigButton(ctx, mouseX, mouseY, x, y, half, 26, "§d§lANNOUNCE", null, new ItemStack(Items.WRITABLE_BOOK))) {
			this.client.setScreen(new DevPostScreen(this));
			return;
		}
		if (drawBigButton(ctx, mouseX, mouseY, x + half + 8, y, half, 26, "§b§lPLAYERS", null, new ItemStack(Items.PLAYER_HEAD))) {
			this.client.setScreen(new DevPlayersScreen(this));
			return;
		}
		y += 32;

		// ---- manage ----
		y = drawSection(ctx, x, y, w, "§6▸ MANAGE", 0xFFC941);
		if (drawBigButton(ctx, mouseX, mouseY, x, y, third, 24, "§6§lBADGES", null)) {
			this.client.setScreen(new DevBadgesScreen(this));
			return;
		}
		if (drawBigButton(ctx, mouseX, mouseY, x + third + 8, y, third, 24, "§a§lSCRIPTS", null)) {
			this.client.setScreen(new ScriptScreen(this));
			return;
		}
		if (drawBigButton(ctx, mouseX, mouseY, x + (third + 8) * 2, y, third, 24, "§e§lIDEAS", null)) {
			this.client.setScreen(new DevSuggestionsScreen(this));
			return;
		}
		y += 28;
		if (drawBigButton(ctx, mouseX, mouseY, x, y, w, 22, "§d§lBLOCK SCRIPTS §8- visual editor", null)) {
			this.client.setScreen(new BlockScriptScreen(this));
			return;
		}
		y += 30;

		// ---- tools ----
		y = drawSection(ctx, x, y, w, "§b▸ TOOLS", 0x55DDFF);
		if (drawBigButton(ctx, mouseX, mouseY, x, y, third, 20, "§eTEST POPUP", null)) {
			ForgeOreCalculatorClient.INSTANCE.testPopup("§e§lTEST §fThis is a test popup!");
			setStatus("§7Test popup showing now →");
		}
		if (drawBigButton(ctx, mouseX, mouseY, x + third + 8, y, third, 20, "§bSYNC NOW", null)) {
			ForgeOreCalculatorClient.INSTANCE.ntfyNet.syncNow();
			ForgeOreCalculatorClient.INSTANCE.controlNet.forcePollSoon();
			setStatus("§7Forcing a sync cycle...");
		}
		if (drawBigButton(ctx, mouseX, mouseY, x + (third + 8) * 2, y, third, 20, "§cRESET BADGES", null)) {
			ForgeCalcConfig.badgeMap.clear();
			ForgeCalcConfig.save();
			ForgeOreCalculatorClient.BADGES.clear();
			ForgeOreCalculatorClient.INSTANCE.controlNet.publishBadges(ForgeCalcConfig.badgeMap);
			setStatus("§7Badges reset to defaults");
		}
		y += 24;
		if (drawBigButton(ctx, mouseX, mouseY, x, y, w, 20, "§6RELEARN ORE VALUES §8(clears the cache)", null)) {
			OreData.clearLearned();
			ForgeCalcConfig.oreValues.clear();
			ForgeCalcConfig.save();
			setStatus("§7Cleared - open §f/ores §7to relearn them");
		}
		y += 28;

		// ---- danger zone ----
		int dangerTop = y - 2;
		int dangerBottom = py + PANEL_H - 30;
		ctx.fill(x - 4, dangerTop, x + w + 4, dangerBottom, 0x28FF3333);
		ctx.fill(x - 4, dangerTop, x - 3, dangerBottom, 0xFFCC3333);
		ctx.fill(x + w + 3, dangerTop, x + w + 4, dangerBottom, 0xFFCC3333);

		y = drawSection(ctx, x, y + 2, w, "§4▸ JUMPSCARE", 0xFF3333);
		ctx.drawText(this.textRenderer, Text.literal("§8players can disable this in their settings"), x, y, 0xFFFFFFFF, true);

		// these follow the flowing y rather than fixed offsets, so adding a row above
		// cannot push the danger zone out from under its own controls
		int fieldY = y + 12;
		if (scareField != null) {
			scareField.setX(x);
			scareField.setY(fieldY);
			scareField.render(ctx, mouseX, mouseY, delta);
		}

		int sy = fieldY + 18;
		if (drawBigButton(ctx, mouseX, mouseY, x, sy, half, 18, "§c§lSCARE PLAYER", null)) {
			String target = scareField != null ? scareField.getText().trim() : "";
			if (target.isEmpty()) {
				setStatus("§cType a username first");
			} else {
				ForgeOreCalculatorClient.INSTANCE.controlNet.publish("scare:" + target);
				ForgeOreCalculatorClient.INSTANCE.controlNet.forcePollSoon();
				setStatus("§aSent to §f" + target + " §7- lands within §f~45s");
			}
		}
		if (drawBigButton(ctx, mouseX, mouseY, x + half + 8, sy, half, 18, "§4§lSCARE ALL", null)) {
			ForgeOreCalculatorClient.INSTANCE.controlNet.publish("scare:all");
			ForgeOreCalculatorClient.INSTANCE.controlNet.forcePollSoon();
			setStatus("§aSent to §feveryone §7- lands within §f~45s");
		}

		if (!status.isEmpty() && System.currentTimeMillis() < statusUntil) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status),
					px + PANEL_W / 2, py + PANEL_H + 6, 0xFFFFFFFF);
		}

		if (drawBigButton(ctx, mouseX, mouseY, px + 16, py + PANEL_H - 24, w, 18, "§f§lBACK", null)) {
			close();
		}
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}
}
