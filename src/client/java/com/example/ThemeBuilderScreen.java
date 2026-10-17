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
import java.util.Random;

public class ThemeBuilderScreen extends FarmScreen {

	public static final String THEME_TOPIC = "afkable-client-themes-armadillo122";

	private static final String[] FIELD_LABELS = {"Panel", "Light", "Dark", "Accent", "Grad 1", "Grad 2"};

	private final Screen parent;
	private CustomTheme theme;
	private TextFieldWidget nameField;
	private TextFieldWidget bgField;
	private final TextFieldWidget[] colorFields = new TextFieldWidget[6];
	private String status = "";

	public ThemeBuilderScreen(Screen parent, CustomTheme existing) {
		super(Text.literal("Theme Builder"));
		this.parent = parent;
		this.theme = existing != null ? existing.copy() : new CustomTheme();
	}

	private static String hex(int color) {
		return String.format("%06X", color & 0xFFFFFF);
	}

	private int fieldColor(int index) {
		return switch (index) {
			case 0 -> theme.plank;
			case 1 -> theme.plankLight;
			case 2 -> theme.plankDark;
			case 3 -> theme.accent;
			case 4 -> theme.gradFrom;
			default -> theme.gradTo;
		};
	}

	private void setFieldColor(int index, int color) {
		switch (index) {
			case 0 -> theme.plank = 0xFF000000 | color;
			case 1 -> theme.plankLight = 0xFF000000 | color;
			case 2 -> theme.plankDark = 0xFF000000 | color;
			case 3 -> theme.accent = 0xFF000000 | color;
			case 4 -> theme.gradFrom = color;
			default -> theme.gradTo = color;
		}
	}

	@Override
	protected void init() {
		int panelW = 300;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - 118;

		nameField = new TextFieldWidget(this.textRenderer, px + 52, py + 28, 108, 14, Text.literal(""));
		nameField.setMaxLength(20);
		nameField.setText(theme.name);
		addSelectableChild(nameField);

		for (int i = 0; i < 6; i++) {
			int fx = px + 12 + (i % 2) * 150;
			int fy = py + 52 + (i / 2) * 20;
			TextFieldWidget field = new TextFieldWidget(this.textRenderer, fx + 46, fy, 56, 14, Text.literal(""));
			field.setMaxLength(6);
			field.setText(hex(fieldColor(i)));
			colorFields[i] = field;
			addSelectableChild(field);
		}

		bgField = new TextFieldWidget(this.textRenderer, px + 88, py + 116, 100, 14, Text.literal(""));
		bgField.setMaxLength(24);
		bgField.setText(theme.background);
		addSelectableChild(bgField);
	}

	private void pullFields() {
		if (nameField != null && !nameField.getText().isBlank()) {
			theme.name = nameField.getText().trim();
		}
		if (bgField != null) {
			theme.background = bgField.getText().trim();
		}
		for (int i = 0; i < 6; i++) {
			if (colorFields[i] == null) continue;
			String text = colorFields[i].getText().replace("#", "").trim();
			if (text.length() == 6) {
				try {
					setFieldColor(i, Integer.parseInt(text, 16));
				} catch (NumberFormatException ignored) {
				}
			}
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();
		pullFields();

		int panelW = 300;
		int panelH = 236;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - 118;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("THEME BUILDER", theme.gradFrom, theme.gradTo), px + panelW / 2, py + 7, CREAM);

		ctx.drawText(this.textRenderer, Text.literal("§7Name:"), px + 14, py + 31, 0xFFFFFFFF, true);
		if (nameField != null) nameField.render(ctx, mouseX, mouseY, delta);

		if (drawBigButton(ctx, mouseX, mouseY, px + 166, py + 27, 60, 16, "§d§lRANDOM", null)) {
			Random random = new Random();
			int base = random.nextInt(0xFFFFFF);
			theme.plank = 0xFF000000 | base;
			theme.plankLight = 0xFF000000 | lighten(base, 0.25f);
			theme.plankDark = 0xFF000000 | lighten(base, -0.35f);
			theme.accent = 0xFF000000 | random.nextInt(0xFFFFFF);
			theme.gradFrom = theme.accent & 0xFFFFFF;
			theme.gradTo = lighten(theme.accent & 0xFFFFFF, 0.4f);
			for (int i = 0; i < 6; i++) {
				colorFields[i].setText(hex(fieldColor(i)));
			}
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 230, py + 27, 58, 16, "§bPREVIEW", null)) {
			theme.applyTo();
			ForgeCalcTheme.useCustom();
			FarmScreen.applyTheme();
			ForgeOreCalculatorClient.applyPanelTheme();
			status = "§bPreviewing - hit SAVE to keep it";
		}

		for (int i = 0; i < 6; i++) {
			int fx = px + 12 + (i % 2) * 150;
			int fy = py + 52 + (i / 2) * 20;
			ctx.drawText(this.textRenderer, Text.literal("§7" + FIELD_LABELS[i]), fx, fy + 3, 0xFFFFFFFF, true);
			if (colorFields[i] != null) colorFields[i].render(ctx, mouseX, mouseY, delta);
			int swatch = 0xFF000000 | (fieldColor(i) & 0xFFFFFF);
			ctx.fill(fx + 105, fy, fx + 121, fy + 14, 0xFF000000);
			ctx.fill(fx + 106, fy + 1, fx + 120, fy + 13, swatch);
		}

		ctx.drawText(this.textRenderer, Text.literal("§7Background:"), px + 14, py + 119, 0xFFFFFFFF, true);
		if (bgField != null) bgField.render(ctx, mouseX, mouseY, delta);
		ctx.drawText(this.textRenderer, Text.literal("§8.png in config/afkable-backgrounds"), px + 192, py + 119, 0xFFFFFFFF, true);

		String[] styleNames = {"Wood", "Flat card", "Cut corner", "Double frame"};
		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + 136, 138, 16, "§fPanels: §e" + styleNames[theme.panelStyle], null)) {
			theme.panelStyle = (theme.panelStyle + 1) % 4;
		}
		String[] layoutNames = {"Classic", "Top bar", "Side tabs"};
		if (drawBigButton(ctx, mouseX, mouseY, px + 156, py + 136, 132, 16, "§fLayout: §e" + layoutNames[theme.layout], null)) {
			theme.layout = (theme.layout + 1) % 3;
		}

		// live preview strip
		int pvY = py + 158;
		ctx.fill(px + 12, pvY, px + panelW - 12, pvY + 34, 0xFF000000 | (theme.plankDark & 0xFFFFFF));
		ctx.fillGradient(px + 14, pvY + 2, px + panelW - 14, pvY + 32,
				0xFF000000 | (theme.plankLight & 0xFFFFFF), 0xFF000000 | (theme.plank & 0xFFFFFF));
		ctx.fill(px + 14, pvY + 2, px + panelW - 14, pvY + 4, 0xFF000000 | (theme.accent & 0xFFFFFF));
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("AFKABLE CLIENT", theme.gradFrom, theme.gradTo), px + panelW / 2, pvY + 9, CREAM);
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7live preview of your theme"), px + panelW / 2, pvY + 21, 0xFFFFFFFF);

		if (!status.isEmpty()) {
			ctx.drawText(this.textRenderer, Text.literal(status), px + 12, py + 196, 0xFFFFFFFF, true);
		}

		int third = (panelW - 30) / 3;
		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + panelH - 24, third, 18, "§f§lBACK", null)) {
			close();
			return;
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 15 + third, py + panelH - 24, third, 18, "§a§lSAVE", null)) {
			theme.save();
			theme.applyTo();
			ForgeCalcConfig.theme = "custom:" + theme.name;
			ForgeCalcTheme.useCustom();
			FarmScreen.applyTheme();
			ForgeOreCalculatorClient.applyPanelTheme();
			ForgeCalcConfig.save();
			status = "§aSaved + applied!";
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 18 + third * 2, py + panelH - 24, third, 18, "§d§lPUBLISH", null)) {
			publish();
		}
	}

	private static int lighten(int color, float amount) {
		int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
		int target = amount > 0 ? 255 : 0;
		float t = Math.abs(amount);
		r = (int) (r + (target - r) * t);
		g = (int) (g + (target - g) * t);
		b = (int) (b + (target - b) * t);
		return (r << 16) | (g << 8) | b;
	}

	private void publish() {
		theme.author = this.client != null && this.client.player != null
				? this.client.player.getName().getString() : "unknown";
		theme.save();
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("https://ntfy.sh/" + THEME_TOPIC))
				.timeout(Duration.ofSeconds(6))
				.POST(HttpRequest.BodyPublishers.ofString(theme.serialize()))
				.build();
		HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.discarding());
		status = "§dPublished! Others can grab it in BROWSE.";
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}
}
