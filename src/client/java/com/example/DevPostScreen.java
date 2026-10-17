package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class DevPostScreen extends FarmScreen {

	private final Screen parent;
	private TextFieldWidget field;
	private String status = "";

	public DevPostScreen(Screen parent) {
		super(Text.literal("Dev Post"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int w = 260;
		int x = this.width / 2 - w / 2 + 10;
		int y = this.height / 2 - 10;
		// resize recreates widgets, so carry the typed text across
		String previousText = field != null ? field.getText() : "";
		field = new TextFieldWidget(this.textRenderer, x, y, w - 20, 16, Text.literal(""));
		field.setMaxLength(180);
		if (!previousText.isEmpty()) field.setText(previousText);
		addSelectableChild(field);
		setInitialFocus(field);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = 280;
		int panelH = 130;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - panelH / 2;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("DEV ANNOUNCEMENT", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo), px + panelW / 2, py + 7, CREAM);

		ctx.drawText(this.textRenderer, Text.literal("§7Everyone with the client gets"), px + 12, py + 28, 0xFFFFFFFF, true);
		ctx.drawText(this.textRenderer, Text.literal("§7this as a §dDEV popup§7, live:"), px + 12, py + 38, 0xFFFFFFFF, true);

		if (field != null) {
			field.render(ctx, mouseX, mouseY, delta);
		}

		if (!status.isEmpty()) {
			ctx.drawText(this.textRenderer, Text.literal(status), px + 12, py + panelH - 42, 0xFFFFFFFF, true);
		}

		int half = (panelW - 30) / 2;
		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + panelH - 24, half, 18, "§f§lBACK", null)) {
			close();
			return;
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 18 + half, py + panelH - 24, half, 18, "§a§lPOST", null)) {
			post();
		}
	}

	private void post() {
		String message = field != null ? field.getText().trim() : "";
		if (message.isEmpty()) {
			status = "§cWrite something first!";
			return;
		}
		status = "§7Posting...";
		ForgeOreCalculatorClient.INSTANCE.controlNet.publish("news:" + message);
		ForgeOreCalculatorClient.INSTANCE.controlNet.forcePollSoon();
		status = "§aPosted! Everyone sees it as a popup within ~45s.";
		if (field != null) field.setText("");
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}
}
