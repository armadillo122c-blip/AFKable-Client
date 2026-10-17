package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

public class ScriptGuiScreen extends FarmScreen {

	private final ScriptEngine.GuiDef def;

	public ScriptGuiScreen(ScriptEngine.GuiDef def) {
		super(Text.literal(def.title()));
		this.def = def;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int count = def.buttons().size();
		int panelW = 200;
		int panelH = 56 + count * 26;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - panelH / 2;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer,
				TextFx.gradient(def.title().toUpperCase(), ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo),
				px + panelW / 2, py + 7, CREAM);

		int y = py + 28;
		for (String[] button : def.buttons()) {
			if (drawBigButton(ctx, mouseX, mouseY, px + 12, y, panelW - 24, 22, "§f§l" + button[0], null)) {
				if (this.client != null && this.client.player != null) {
					ForgeOreCalculatorClient.runButtonAction(this.client, button[1]);
				}
				close();
				return;
			}
			y += 26;
		}

		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + panelH - 24, panelW - 24, 18, "§7CLOSE", null)) {
			close();
		}
	}

	@Override
	public void close() {
		this.client.setScreen(null);
	}
}
