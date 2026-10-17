package com.example;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ScriptScreen extends FarmScreen {

	private final Screen parent;
	private List<Path> scripts;
	private int selectedFile = -1;
	private final List<String> lines = new ArrayList<>();
	private int selectedLine = -1;
	private int lineScroll = 0;
	private TextFieldWidget lineField;
	private int lastSyncedLine = -1;
	private String status = "";

	public ScriptScreen(Screen parent) {
		super(Text.literal("Scripts"));
		this.parent = parent;
		ScriptEngine.ensureExampleScript();
		this.scripts = ScriptEngine.listScripts();
	}

	@Override
	protected void init() {
		int panelW = Math.min(440, this.width - 20);
		int px = this.width / 2 - panelW / 2;
		int py = Math.max(10, this.height / 2 - 135);
		// resize recreates widgets, so carry the typed text across
		String previousText = lineField != null ? lineField.getText() : "";
		lineField = new TextFieldWidget(this.textRenderer, px + 150, py + 196, panelW - 162, 14, Text.literal(""));
		lineField.setMaxLength(200);
		if (!previousText.isEmpty()) lineField.setText(previousText);
		addSelectableChild(lineField);
	}

	private void loadFile(int index) {
		selectedFile = index;
		lines.clear();
		selectedLine = -1;
		lastSyncedLine = -1;
		lineScroll = 0;
		if (lineField != null) lineField.setText("");
		try {
			lines.addAll(Files.readAllLines(scripts.get(index)));
			status = "§7Loaded §f" + scripts.get(index).getFileName();
		} catch (Exception e) {
			status = "§cCould not read file";
		}
	}

	private void syncFieldToLine() {
		if (lineField == null) return;
		if (selectedLine != lastSyncedLine) {
			lineField.setText(selectedLine >= 0 && selectedLine < lines.size() ? lines.get(selectedLine) : "");
			lastSyncedLine = selectedLine;
		} else if (selectedLine >= 0 && selectedLine < lines.size()) {
			lines.set(selectedLine, lineField.getText());
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();
		syncFieldToLine();

		int panelW = Math.min(440, this.width - 20);
		int panelH = 270;
		int px = this.width / 2 - panelW / 2;
		int py = Math.max(10, this.height / 2 - 135);

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("SCRIPTS", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo), px + panelW / 2, py + 7, CREAM);
		ctx.drawText(this.textRenderer, Text.literal("§c§oExperimental"), px + panelW - 68, py + 8, 0xFFFFFFFF, true);

		ScriptEngine engine = ForgeOreCalculatorClient.INSTANCE != null ? ForgeOreCalculatorClient.INSTANCE.scriptEngine : null;
		MinecraftClient mc = this.client;

		// file list, left
		ctx.drawText(this.textRenderer, Text.literal("§7Files:"), px + 10, py + 28, 0xFFFFFFFF, true);
		int fy = py + 40;
		for (int i = 0; i < Math.min(scripts.size(), 8); i++) {
			String name = scripts.get(i).getFileName().toString();
			if (name.length() > 18) name = name.substring(0, 17) + "…";
			boolean isSel = i == selectedFile;
			boolean hov = hover(mouseX, mouseY, px + 10, fy, 132, 14);
			ctx.fill(px + 10, fy, px + 142, fy + 14, isSel ? 0x506FBF4A : (hov ? 0x40FFFFFF : 0x28FFFFFF));
			ctx.drawText(this.textRenderer, Text.literal((isSel ? "§a" : "§f") + name), px + 13, fy + 3, 0xFFFFFFFF, true);
			if (hov && mouseClickedThisFrame) {
				mouseClickedThisFrame = false;
				playClick(mc);
				loadFile(i);
			}
			fy += 16;
		}

		// line editor, right
		int ex = px + 150;
		int ew = panelW - 162;
		ctx.fill(ex - 1, py + 27, ex + ew + 1, py + 191, FRAME_DARK);
		ctx.fill(ex, py + 28, ex + ew, py + 190, 0xC0140E08);

		if (selectedFile < 0) {
			ctx.drawText(this.textRenderer, Text.literal("§8Select a file on the left"), ex + 6, py + 34, 0xFFFFFFFF, true);
		} else {
			int visible = 13;
			int maxScroll = Math.max(0, lines.size() - visible);
			lineScroll = Math.max(0, Math.min(maxScroll, lineScroll - pollScroll()));

			int ly = py + 31;
			for (int i = lineScroll; i < Math.min(lines.size(), lineScroll + visible); i++) {
				boolean isSel = i == selectedLine;
				boolean hov = hover(mouseX, mouseY, ex + 2, ly, ew - 4, 12);
				if (isSel || hov) {
					ctx.fill(ex + 2, ly, ex + ew - 2, ly + 12, isSel ? 0x50DD55FF : 0x30FFFFFF);
				}
				String text = lines.get(i);
				if (text.length() > 38) text = text.substring(0, 37) + "…";
				String color = text.trim().startsWith("#") ? "§8" : "§f";
				ctx.drawText(this.textRenderer, Text.literal("§8" + (i + 1) + " " + color + text), ex + 5, ly + 2, 0xFFFFFFFF, true);
				if (hov && mouseClickedThisFrame) {
					mouseClickedThisFrame = false;
					playClick(mc);
					selectedLine = i;
				}
				ly += 12;
			}
			if (lines.isEmpty()) {
				ctx.drawText(this.textRenderer, Text.literal("§8Empty - press + LINE"), ex + 6, py + 34, 0xFFFFFFFF, true);
			}
		}

		if (lineField != null && selectedFile >= 0) {
			ctx.drawText(this.textRenderer, Text.literal(selectedLine >= 0 ? "§7Editing line §f" + (selectedLine + 1) + "§7:" : "§7Click a line to edit it:"), ex, py + 186 - 42, 0, false);
			lineField.render(ctx, mouseX, mouseY, delta);
		}

		// line ops
		boolean isDev = mc != null && mc.player != null
				&& ForgeOreCalculatorClient.isDev(mc);
		if (isDev && selectedFile >= 0) {
			if (drawBigButton(ctx, mouseX, mouseY, px + panelW - 66, py + 172, 56, 14, "§d§lPUBLISH", null)) {
				if (ForgeOreCalculatorClient.INSTANCE != null) {
					String name = scripts.get(selectedFile).getFileName().toString()
							.replace(".txt", "").replace("public_", "").replace("global_", "");
					ForgeOreCalculatorClient.INSTANCE.controlNet.publishScript(name, String.join("\n", lines));
					status = "§aPublished §f" + name + " §7- users can run it (within ~45s)";
				}
			}
			if (drawBigButton(ctx, mouseX, mouseY, px + panelW - 130, py + 172, 60, 14, "§4§lGLOBAL RUN", null)) {
				if (ForgeOreCalculatorClient.INSTANCE != null) {
					String name = scripts.get(selectedFile).getFileName().toString()
							.replace(".txt", "").replace("public_", "").replace("global_", "");
					ForgeOreCalculatorClient.INSTANCE.controlNet.publishGlobalScript(name, String.join("\n", lines));
					status = "§cGLOBAL: §f" + name + " §7will AUTO-RUN on everyone (~45s)";
				}
			}
		}
		if (selectedFile >= 0) {
			if (drawBigButton(ctx, mouseX, mouseY, px + 10, py + 172, 42, 14, "§a+LINE", null)) {
				int at = selectedLine >= 0 ? selectedLine + 1 : lines.size();
				lines.add(at, "");
				selectedLine = at;
				lastSyncedLine = -1;
			}
			if (drawBigButton(ctx, mouseX, mouseY, px + 56, py + 172, 42, 14, "§c-LINE", null)) {
				if (selectedLine >= 0 && selectedLine < lines.size()) {
					lines.remove(selectedLine);
					selectedLine = Math.min(selectedLine, lines.size() - 1);
					lastSyncedLine = -1;
				}
			}
			if (drawBigButton(ctx, mouseX, mouseY, px + 102, py + 172, 40, 14, "§eHELP", null)) {
				status = "§7wait N seconds §8| §7run command \"/x\" §8| §7send \"msg\" §8| §7say \"msg\" §8| §7loop N times / end loop";
			}
		}

		if (engine != null && engine.isRunning()) {
			ctx.drawText(this.textRenderer, Text.literal("§aRunning: §f" + engine.runningName()), px + 10, py + panelH - 56, 0xFFFFFFFF, true);
		}
		if (!status.isEmpty()) {
			String shown = status.length() > 74 ? status.substring(0, 73) + "…" : status;
			ctx.drawText(this.textRenderer, Text.literal(shown), px + 10, py + panelH - 44, 0xFFFFFFFF, true);
		}

		int bw = (panelW - 20 - 5 * 6) / 6;
		int by = py + panelH - 26;
		int bx = px + 10;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§fBACK", null)) {
			close();
			return;
		}
		bx += bw + 6;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§aRUN", null)) {
			if (engine != null && mc != null && mc.player != null && selectedFile >= 0) {
				if (engine.start(scripts.get(selectedFile).getFileName().toString(), new ArrayList<>(lines), mc)) {
					this.client.setScreen(null);
					return;
				}
			} else {
				status = "§cSelect a script first";
			}
		}
		bx += bw + 6;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§cSTOP", null)) {
			if (engine != null) {
				engine.stop();
				status = "§7Stopped";
			}
		}
		bx += bw + 6;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§eSAVE", null)) {
			if (selectedFile >= 0) {
				try {
					Files.write(scripts.get(selectedFile), lines);
					status = "§aSaved §f" + scripts.get(selectedFile).getFileName();
				} catch (Exception e) {
					status = "§cSave failed";
				}
			}
		}
		bx += bw + 6;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§bRELOAD", null)) {
			scripts = ScriptEngine.listScripts();
			selectedFile = -1;
			lines.clear();
			selectedLine = -1;
			status = "§7Reloaded file list";
		}
		bx += bw + 6;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§dFOLDER", null)) {
			try {
				Util.getOperatingSystem().open(ScriptEngine.scriptFolder().toUri());
			} catch (Exception ignored) {
			}
		}
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}
}
