package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class DevBadgesScreen extends FarmScreen {

	private final Screen parent;
	private TextFieldWidget nameField;
	private String status = "";

	public DevBadgesScreen(Screen parent) {
		super(Text.literal("Badges"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int panelW = 300;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - 144;
		// resize recreates widgets, so carry the typed text across
		String previousText = nameField != null ? nameField.getText() : "";
		nameField = new TextFieldWidget(this.textRenderer, px + 12, py + 40, panelW - 24, 14, Text.literal(""));
		nameField.setMaxLength(20);
		if (!previousText.isEmpty()) nameField.setText(previousText);
		addSelectableChild(nameField);
		setInitialFocus(nameField);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = 300;
		int panelH = 288;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - panelH / 2;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("BADGES", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo), px + panelW / 2, py + 7, CREAM);

		ctx.drawText(this.textRenderer, Text.literal("§7Type a §fusername§7:"), px + 12, py + 28, 0xFFFFFFFF, true);
		if (nameField != null) {
			nameField.render(ctx, mouseX, mouseY, delta);
		}

		String name = nameField != null ? nameField.getText().trim() : "";
		int y = py + 62;

		if (!name.isEmpty()) {
			String key = name.toLowerCase();
			String current = ForgeCalcConfig.badgeMap.getOrDefault(key,
					name.equalsIgnoreCase("Armadillo122") ? "creator" : "default");
			boolean online = ForgeOreCalculatorClient.isClientUser(name);

			ctx.fill(px + 12, y, px + panelW - 12, y + 30, 0x28FFFFFF);
			ctx.drawText(this.textRenderer, Text.literal("§f" + name + (online ? " §a(online, uses client)" : " §8(not seen)")), px + 16, y + 4, 0xFFFFFFFF, true);
			ctx.drawText(this.textRenderer, Text.literal("§7Badge: " + ForgeOreCalculatorClient.badgeLabel(current)), px + 16, y + 17, 0xFFFFFFFF, true);
			y += 36;

			TabBadge[] badges = TabBadge.values();
			int half = (panelW - 30) / 2;
			for (int i = 0; i < badges.length; i++) {
				int bx = px + 12 + (i % 2) * (half + 6);
				int by = y + (i / 2) * 22;
				if (drawBigButton(ctx, mouseX, mouseY, bx, by, half, 18, badges[i].buttonLabel(), null)) {
					ForgeCalcConfig.badgeMap.put(key, badges[i].key());
					ForgeCalcConfig.save();
					ForgeOreCalculatorClient.BADGES.put(key, badges[i].key());
					ForgeOreCalculatorClient.INSTANCE.controlNet.publishBadges(ForgeCalcConfig.badgeMap);
					status = "§f" + name + " §7is now " + ForgeOreCalculatorClient.badgeLabel(badges[i].key());
				}
			}
			y += 48;
		} else {
			ctx.drawText(this.textRenderer, Text.literal("§8Profile appears here"), px + 12, y, 0xFFFFFFFF, true);
		}

		if (!status.isEmpty()) {
			ctx.drawText(this.textRenderer, Text.literal(status), px + 12, py + panelH - 40, 0xFFFFFFFF, true);
		}

		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + panelH - 24, panelW - 24, 18, "§f§lBACK", null)) {
			close();
		}
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}
}
