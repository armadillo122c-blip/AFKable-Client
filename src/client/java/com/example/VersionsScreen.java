package com.example;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public class VersionsScreen extends FarmScreen {

	private record Version(String name, String resource, String[] notes) {
	}

	/**
	 * Older jars bundled in this build. The version you are running is not listed here -
	 * it is read from the dev logs so this screen can never claim you are on a stale
	 * version, which is exactly what the hardcoded "3.8.0" entry used to do.
	 */
	private static final List<Version> VERSIONS = List.of(
			new Version("3.7.0", "/versions/afkable-client-3.7.0.jar", new String[]{"Buttons page, styled panels", "Beans + Car themes"}),
			new Version("3.6.0", "/versions/afkable-client-3.6.0.jar", new String[]{"Theme matching, suggestions", "Slime/Poop/Dark/Light"})
	);

	private static List<Version> sorted() {
		List<Version> list = new java.util.ArrayList<>(VERSIONS);
		list.sort((a, b) -> DevLogsScreen.compareVersions(b.name(), a.name()));
		return list;
	}

	private final Screen parent;
	private String status = "";

	public VersionsScreen(Screen parent) {
		super(Text.literal("Versions"));
		this.parent = parent;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = 270;
		int panelH = 210;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - panelH / 2;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("VERSIONS", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo), px + panelW / 2, py + 7, CREAM);

		// the version you are actually running, taken from the dev logs
		String current = DevLogsScreen.latestVersion();
		int y = py + 28;
		ctx.fill(px + 12, y, px + panelW - 12, y + 20, 0x3055FF88);
		ctx.fill(px + 12, y, px + 14, y + 20, GREEN);
		ctx.drawText(this.textRenderer, Text.literal("§a§lv" + current), px + 18, y + 3, 0xFFFFFFFF, true);
		String updateStatus = AutoUpdater.status();
		while (!updateStatus.isEmpty() && this.textRenderer.getWidth("§7" + updateStatus) > panelW - 104) {
			updateStatus = updateStatus.substring(0, updateStatus.length() - 1);
		}
		ctx.drawText(this.textRenderer, Text.literal("§7" + updateStatus), px + 18, y + 12, 0xFFFFFFFF, true);
		if (drawBigButton(ctx, mouseX, mouseY, px + panelW - 66, y + 3, 50, 14, "§bCHECK", null)) {
			AutoUpdater.checkNow();
		}
		y += 26;

		ctx.drawText(this.textRenderer, Text.literal("§8older builds bundled in this jar"), px + 14, y, 0xFFFFFFFF, true);
		y += 12;

		for (Version version : sorted()) {
			int rowH = 16 + version.notes().length * 10;
			ctx.fill(px + 12, y, px + panelW - 12, y + rowH - 2, 0x22FFFFFF);
			ctx.fill(px + 12, y, px + 14, y + rowH - 2, 0x60FFFFFF);
			ctx.drawText(this.textRenderer, Text.literal("§f§lv" + version.name()), px + 18, y + 3, 0xFFFFFFFF, true);
			if (drawBigButton(ctx, mouseX, mouseY, px + panelW - 66, y + 2, 50, 13, "§fGET", null)) {
				extract(version);
			}
			int noteY = y + 14;
			for (String note : version.notes()) {
				ctx.drawText(this.textRenderer, Text.literal("§7" + note), px + 18, noteY, 0xFFFFFFFF, true);
				noteY += 10;
			}
			y += rowH + 4;
		}

		if (!status.isEmpty()) {
			ctx.drawText(this.textRenderer, Text.literal(status), px + 12, py + panelH - 48, 0xFFFFFFFF, true);
			ctx.drawText(this.textRenderer, Text.literal("§8Swap it into your mods folder + restart."), px + 12, py + panelH - 38, 0xFFFFFFFF, true);
		}

		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + panelH - 24, panelW - 24, 18, "§f§lBACK", null)) {
			close();
		}
	}

	private void extract(Version version) {
		try {
			Path folder = FabricLoader.getInstance().getGameDir().resolve("afkable-versions");
			Files.createDirectories(folder);
			Path out = folder.resolve("afkable-client-" + version.name() + ".jar");
			try (InputStream in = VersionsScreen.class.getResourceAsStream(version.resource())) {
				if (in == null) {
					status = "§cVersion file missing from this build";
					return;
				}
				Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
			}
			status = "§aSaved to §f.minecraft/afkable-versions/";
		} catch (Exception e) {
			status = "§cCouldn't extract: " + e.getMessage();
		}
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}
}
