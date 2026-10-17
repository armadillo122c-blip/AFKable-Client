package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DevPlayersScreen extends FarmScreen {

	private final Screen parent;
	private int scroll = 0;

	public DevPlayersScreen(Screen parent) {
		super(Text.literal("Client Players"));
		this.parent = parent;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = 240;
		int panelH = 220;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - panelH / 2;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("CLIENT PLAYERS", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo), px + panelW / 2, py + 7, CREAM);

		List<Map.Entry<String, String>> users = new ArrayList<>(ForgeOreCalculatorClient.knownUsers().entrySet());

		ctx.drawText(this.textRenderer, Text.literal("§7Seen this session: §f" + users.size()), px + 12, py + 27, 0xFFFFFFFF, true);

		int maxScroll = Math.max(0, users.size() - 9);
		scroll = Math.max(0, Math.min(maxScroll, scroll - pollScroll()));

		int y = py + 40;
		for (int i = scroll; i < Math.min(users.size(), scroll + 9); i++) {
			Map.Entry<String, String> user = users.get(i);
			String name = user.getValue();
			String badge = ForgeOreCalculatorClient.badgePrefix(name, user.getKey());
			ctx.fill(px + 12, y, px + panelW - 12, y + 15, 0x28FFFFFF);
			ctx.drawText(this.textRenderer, Text.literal((badge != null ? badge : "§8? ") + "§f" + name), px + 16, y + 3, 0xFFFFFFFF, true);
			y += 17;
		}

		if (users.isEmpty()) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7Nobody synced yet - wait ~12s"), px + panelW / 2, py + 100, 0xFFFFFFFF);
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
