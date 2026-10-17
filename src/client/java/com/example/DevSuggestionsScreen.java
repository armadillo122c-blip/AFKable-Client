package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DevSuggestionsScreen extends FarmScreen {

	private final Screen parent;
	private final List<String[]> suggestions = new ArrayList<>();
	private int scroll = 0;
	private String status = "§7Loading...";

	public DevSuggestionsScreen(Screen parent) {
		super(Text.literal("Suggestions"));
		this.parent = parent;
		refresh(true);
	}

	private void refresh(boolean pollFirst) {
		if (pollFirst) {
			SuggestStore.poll(() -> {
				if (this.client != null) {
					this.client.execute(() -> refresh(false));
				}
			});
			return;
		}
		suggestions.clear();
		Map<String, String[]> entries = SuggestStore.load();
		List<Map.Entry<String, String[]>> sorted = new ArrayList<>(entries.entrySet());
		sorted.sort((a, b) -> Long.compare(Long.parseLong(b.getValue()[0]), Long.parseLong(a.getValue()[0])));
		for (Map.Entry<String, String[]> entry : sorted) {
			suggestions.add(new String[]{entry.getKey(), entry.getValue()[0], entry.getValue()[1]});
		}
		status = suggestions.isEmpty() ? "§7No suggestions saved yet" : "";
	}

	private String age(long time) {
		long minutes = (System.currentTimeMillis() - time) / 60000;
		if (minutes < 60) return minutes + "m";
		if (minutes < 1440) return (minutes / 60) + "h";
		return (minutes / 1440) + "d";
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = 300;
		int panelH = 226;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - panelH / 2;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("SUGGESTIONS", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo), px + panelW / 2, py + 7, CREAM);
		ctx.drawText(this.textRenderer, Text.literal("§8Kept for 14 days §7- §f" + suggestions.size() + " §7saved"), px + 12, py + 27, 0xFFFFFFFF, true);

		int visible = 12;
		int maxScroll = Math.max(0, suggestions.size() - visible);
		scroll = Math.max(0, Math.min(maxScroll, scroll - pollScroll()));

		int y = py + 40;
		for (int i = scroll; i < Math.min(suggestions.size(), scroll + visible); i++) {
			String[] entry = suggestions.get(i);
			String line = entry[2];
			if (line.length() > 40) line = line.substring(0, 39) + "…";
			ctx.fill(px + 12, y, px + panelW - 12, y + 12, 0x28FFFFFF);
			ctx.drawText(this.textRenderer, Text.literal("§8" + age(Long.parseLong(entry[1])) + " §f" + line), px + 15, y + 2, 0xFFFFFFFF, true);
			if (drawBigButton(ctx, mouseX, mouseY, px + panelW - 28, y, 14, 12, "§cX", null)) {
				SuggestStore.delete(entry[0]);
				refresh(false);
				return;
			}
			y += 14;
		}
		if (!status.isEmpty()) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status), px + panelW / 2, py + 100, 0xFFFFFFFF);
		}

		int half = (panelW - 30) / 2;
		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + panelH - 24, half, 18, "§f§lBACK", null)) {
			close();
			return;
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 18 + half, py + panelH - 24, half, 18, "§bREFRESH", null)) {
			status = "§7Loading...";
			refresh(true);
		}
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}
}
