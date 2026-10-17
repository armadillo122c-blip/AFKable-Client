package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ThemeBrowserScreen extends FarmScreen {

	private static final Pattern MSG = Pattern.compile("\"message\"\\s*:\\s*\"(.*?)\"");

	private final Screen parent;
	private final List<CustomTheme> themes = new ArrayList<>();
	private int scroll = 0;
	private String status = "§7Loading themes...";

	public ThemeBrowserScreen(Screen parent) {
		super(Text.literal("Browse Themes"));
		this.parent = parent;
		fetch();
	}

	private void fetch() {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("https://ntfy.sh/" + ThemeBuilderScreen.THEME_TOPIC + "/json?poll=1&since=12h"))
				.timeout(Duration.ofSeconds(8))
				.GET().build();
		HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenAccept(response -> {
					Map<String, CustomTheme> found = new LinkedHashMap<>();
					for (CustomTheme local : CustomTheme.listAll()) {
						found.put(local.name.toLowerCase(), local);
					}
					for (String line : response.body().split("\n")) {
						Matcher m = MSG.matcher(line);
						if (m.find() && m.group(1).contains("plank=")) {
							try {
								CustomTheme theme = CustomTheme.parse(m.group(1).replace("\\\"", "\""));
								found.put(theme.name.toLowerCase(), theme);
							} catch (Exception ignored) {
							}
						}
					}
					if (this.client != null) {
						this.client.execute(() -> {
							themes.clear();
							themes.addAll(found.values());
							status = themes.isEmpty() ? "§7No themes published yet - make one!" : "";
						});
					}
				});
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = 300;
		int panelH = 230;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - panelH / 2;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("COMMUNITY THEMES", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo), px + panelW / 2, py + 7, CREAM);
		ctx.drawText(this.textRenderer, Text.literal("§8Your saved themes + everything published"), px + 12, py + 27, 0xFFFFFFFF, true);

		int visible = 5;
		int maxScroll = Math.max(0, themes.size() - visible);
		scroll = Math.max(0, Math.min(maxScroll, scroll - pollScroll()));

		int y = py + 40;
		for (int i = scroll; i < Math.min(themes.size(), scroll + visible); i++) {
			CustomTheme theme = themes.get(i);
			int rowH = 28;

			ctx.fill(px + 12, y, px + panelW - 12, y + rowH, 0xFF000000 | (theme.plankDark & 0xFFFFFF));
			ctx.fillGradient(px + 13, y + 1, px + panelW - 13, y + rowH - 1,
					0xFF000000 | (theme.plankLight & 0xFFFFFF), 0xFF000000 | (theme.plank & 0xFFFFFF));
			ctx.fill(px + 13, y + 1, px + panelW - 13, y + 3, 0xFF000000 | (theme.accent & 0xFFFFFF));

			ctx.drawText(this.textRenderer, TextFx.gradient(theme.name, theme.gradFrom, theme.gradTo), px + 18, y + 6, CREAM, true);
			ctx.drawText(this.textRenderer, Text.literal("§7by §f" + (theme.author.isEmpty() ? "unknown" : theme.author)), px + 18, y + 17, 0xFFFFFFFF, true);

			if (drawBigButton(ctx, mouseX, mouseY, px + panelW - 106, y + 6, 44, 16, "§a§lAPPLY", null)) {
				theme.save();
				theme.applyTo();
				ForgeCalcConfig.theme = "custom:" + theme.name;
				ForgeCalcTheme.useCustom();
				FarmScreen.applyTheme();
				ForgeOreCalculatorClient.applyPanelTheme();
				ForgeCalcConfig.save();
				status = "§aApplied §f" + theme.name;
			}
			if (drawBigButton(ctx, mouseX, mouseY, px + panelW - 58, y + 6, 44, 16, "§bEDIT", null)) {
				this.client.setScreen(new ThemeBuilderScreen(this, theme));
				return;
			}
			y += rowH + 4;
		}

		if (!status.isEmpty()) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status), px + panelW / 2, py + 110, 0xFFFFFFFF);
		}

		int third = (panelW - 30) / 3;
		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + panelH - 24, third, 18, "§f§lBACK", null)) {
			close();
			return;
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 15 + third, py + panelH - 24, third, 18, "§d§lCREATE", null)) {
			this.client.setScreen(new ThemeBuilderScreen(this, null));
			return;
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 18 + third * 2, py + panelH - 24, third, 18, "§bREFRESH", null)) {
			status = "§7Loading themes...";
			fetch();
		}
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}
}
