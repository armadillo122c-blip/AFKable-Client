package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class SuggestScreen extends FarmScreen {

	public static final String SUGGEST_TOPIC = "afkable-client-suggest-armadillo122";

	private final Screen parent;
	private TextFieldWidget field;
	private String status = "";

	public SuggestScreen(Screen parent) {
		super(Text.literal("Suggest"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int panelW = 260;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - 60;
		// resize recreates widgets, so carry the typed text across
		String previousText = field != null ? field.getText() : "";
		field = new TextFieldWidget(this.textRenderer, px + 12, py + 52, panelW - 24, 15, Text.literal(""));
		field.setMaxLength(160);
		if (!previousText.isEmpty()) field.setText(previousText);
		addSelectableChild(field);
		setInitialFocus(field);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = 260;
		int panelH = 120;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - 60;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("SUGGESTIONS", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo), px + panelW / 2, py + 7, CREAM);

		ctx.drawText(this.textRenderer, Text.literal("§7Suggest a feature - it goes straight"), px + 12, py + 28, 0xFFFFFFFF, true);
		ctx.drawText(this.textRenderer, Text.literal("§7to §fArmadillo122§7:"), px + 12, py + 38, 0xFFFFFFFF, true);

		if (field != null) {
			field.render(ctx, mouseX, mouseY, delta);
		}
		if (!status.isEmpty()) {
			ctx.drawText(this.textRenderer, Text.literal(status), px + 12, py + 72, 0xFFFFFFFF, true);
		}

		int half = (panelW - 30) / 2;
		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + panelH - 24, half, 18, "§f§lBACK", null)) {
			close();
			return;
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 18 + half, py + panelH - 24, half, 18, "§a§lSEND", null)) {
			String text = field != null ? field.getText().trim() : "";
			if (text.isEmpty()) {
				status = "§cWrite your idea first!";
				return;
			}
			String name = this.client != null && this.client.player != null
					? this.client.player.getName().getString() : "unknown";
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("https://ntfy.sh/" + SUGGEST_TOPIC))
					.timeout(Duration.ofSeconds(6))
					.POST(HttpRequest.BodyPublishers.ofString(name + ": " + text))
					.build();
			HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.discarding());
			status = "§aSent! Thanks for the idea.";
			field.setText("");
		}
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}
}
