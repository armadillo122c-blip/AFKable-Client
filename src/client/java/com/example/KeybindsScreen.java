package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class KeybindsScreen extends FarmScreen {

	private static class Row {
		int keyCode;
		TextFieldWidget field;

		Row(int keyCode) {
			this.keyCode = keyCode;
		}
	}

	private final Screen parent;
	private final List<Row> rows = new ArrayList<>();
	private int listening = -1;
	private boolean loaded = false;

	public KeybindsScreen(Screen parent) {
		super(Text.literal("Keybinds"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int panelW = 290;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - 115;

		if (!loaded) {
			loaded = true;
			for (var entry : ForgeCalcConfig.keybinds.entrySet()) {
				Row row = new Row(entry.getKey());
				rows.add(row);
			}
			for (int i = 0; i < rows.size(); i++) {
				makeField(rows.get(i), px, py, i, ForgeCalcConfig.keybinds.get(rows.get(i).keyCode));
			}
		} else {
			for (int i = 0; i < rows.size(); i++) {
				Row row = rows.get(i);
				String text = row.field != null ? row.field.getText() : "";
				makeField(row, px, py, i, text);
			}
		}
	}

	private void makeField(Row row, int px, int py, int index, String text) {
		row.field = new TextFieldWidget(this.textRenderer, px + 74, py + 42 + index * 20, 150, 15, Text.literal(""));
		row.field.setMaxLength(80);
		if (text != null) row.field.setText(text);
		addSelectableChild(row.field);
	}

	private String keyName(int code) {
		if (code <= 0) return "?";
		try {
			String glfwName = GLFW.glfwGetKeyName(code, 0);
			if (glfwName != null && !glfwName.isBlank()) {
				return glfwName.toUpperCase();
			}
		} catch (Throwable ignored) {
		}
		return switch (code) {
			case GLFW.GLFW_KEY_SPACE -> "SPACE";
			case GLFW.GLFW_KEY_TAB -> "TAB";
			case GLFW.GLFW_KEY_LEFT_SHIFT -> "LSHIFT";
			case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RSHIFT";
			case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCTRL";
			case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCTRL";
			case GLFW.GLFW_KEY_LEFT_ALT -> "LALT";
			case GLFW.GLFW_KEY_RIGHT_ALT -> "RALT";
			case GLFW.GLFW_KEY_ENTER -> "ENTER";
			case GLFW.GLFW_KEY_BACKSPACE -> "BACK";
			case GLFW.GLFW_KEY_CAPS_LOCK -> "CAPS";
			case GLFW.GLFW_KEY_UP -> "UP";
			case GLFW.GLFW_KEY_DOWN -> "DOWN";
			case GLFW.GLFW_KEY_LEFT -> "LEFT";
			case GLFW.GLFW_KEY_RIGHT -> "RIGHT";
			case GLFW.GLFW_KEY_F1, GLFW.GLFW_KEY_F2, GLFW.GLFW_KEY_F3, GLFW.GLFW_KEY_F4,
					GLFW.GLFW_KEY_F5, GLFW.GLFW_KEY_F6, GLFW.GLFW_KEY_F7, GLFW.GLFW_KEY_F8,
					GLFW.GLFW_KEY_F9, GLFW.GLFW_KEY_F10, GLFW.GLFW_KEY_F11, GLFW.GLFW_KEY_F12 ->
					"F" + (code - GLFW.GLFW_KEY_F1 + 1);
			default -> "KEY" + code;
		};
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = 290;
		int panelH = 230;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - 115;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("KEYBINDS", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo), px + panelW / 2, py + 7, CREAM);

		ctx.drawText(this.textRenderer, Text.literal("§7Key"), px + 14, py + 30, 0xFFFFFFFF, true);
		ctx.drawText(this.textRenderer, Text.literal("§7Command / message"), px + 74, py + 30, 0xFFFFFFFF, true);

		if (listening >= 0) {
			long window = this.client.getWindow().getHandle();
			for (int code = 32; code <= 348; code++) {
				if (code == GLFW.GLFW_KEY_ESCAPE) continue;
				if (GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS) {
					if (listening < rows.size()) {
						rows.get(listening).keyCode = code;
					}
					listening = -1;
					break;
				}
			}
		}

		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			int ry = py + 42 + i * 20;
			boolean isListening = listening == i;
			if (drawBigButton(ctx, mouseX, mouseY, px + 12, ry, 56, 15, isListening ? "§e§l..." : "§f§l" + keyName(row.keyCode), null)) {
				listening = isListening ? -1 : i;
			}
			if (row.field != null) {
				row.field.render(ctx, mouseX, mouseY, delta);
			}
			if (drawBigButton(ctx, mouseX, mouseY, px + 230, ry, 16, 15, "§cX", null)) {
				rows.remove(i);
				this.clearChildren();
				this.init();
				return;
			}
			if (i >= 7) break;
		}

		if (rows.size() < 8 && drawBigButton(ctx, mouseX, mouseY, px + 12, py + 44 + rows.size() * 20, panelW - 24, 16, "§a+ Add Keybind", null)) {
			rows.add(new Row(0));
			this.clearChildren();
			this.init();
			return;
		}

		ctx.drawText(this.textRenderer, Text.literal("§8Works in-world - press the key to run the command"), px + 12, py + panelH - 38, 0xFFFFFFFF, true);

		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + panelH - 24, panelW - 24, 18, "§f§lSAVE + BACK", null)) {
			close();
		}
	}

	@Override
	public void close() {
		ForgeCalcConfig.keybinds.clear();
		for (Row row : rows) {
			String cmd = row.field != null ? row.field.getText().trim() : "";
			if (row.keyCode > 0 && !cmd.isEmpty()) {
				ForgeCalcConfig.keybinds.put(row.keyCode, cmd);
			}
		}
		ForgeCalcConfig.save();
		this.client.setScreen(parent);
	}
}
