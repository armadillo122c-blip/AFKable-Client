package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class GangChatScreen extends FarmScreen {

	private final Screen parent;
	private TextFieldWidget messageField;
	private int scroll = 0;
	private boolean enterHeld = true;

	private void sendMessage() {
		String text = messageField != null ? messageField.getText().trim() : "";
		if (!text.isEmpty() && this.client != null && this.client.player != null) {
			this.client.player.networkHandler.sendChatCommand("gc " + text);
			messageField.setText("");
		}
	}

	public GangChatScreen(Screen parent) {
		super(Text.literal("Gang Chat"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int panelW = 280;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - 115;
		// resize recreates widgets, so carry the typed text across
		String previousText = messageField != null ? messageField.getText() : "";
		messageField = new TextFieldWidget(this.textRenderer, px + 12, py + 182, panelW - 70, 14, Text.literal(""));
		messageField.setMaxLength(100);
		if (!previousText.isEmpty()) messageField.setText(previousText);
		addSelectableChild(messageField);
		setInitialFocus(messageField);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = 280;
		int panelH = 230;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - 115;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("GANG CHAT", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo), px + panelW / 2, py + 7, CREAM);

		String gang = ForgeOreCalculatorClient.gangName;
		ctx.drawText(this.textRenderer, Text.literal(gang.isEmpty()
				? "§7Gang: §8unknown §7- press §fSYNC"
				: "§7Gang: §f" + gang), px + 12, py + 27, 0xFFFFFFFF, true);
		if (drawBigButton(ctx, mouseX, mouseY, px + panelW - 52, py + 25, 40, 13, "§bSYNC", null)) {
			if (this.client != null && this.client.player != null) {
				this.client.player.networkHandler.sendChatCommand("gang");
			}
		}

		int listX = px + 12;
		int listY = py + 42;
		int listW = panelW - 24;
		int listH = 134;
		ctx.fill(listX - 1, listY - 1, listX + listW + 1, listY + listH + 1, FRAME_DARK);
		ctx.fill(listX, listY, listX + listW, listY + listH, 0xC0140E08);

		List<String> messages;
		synchronized (ForgeOreCalculatorClient.GANG_MESSAGES) {
			messages = new ArrayList<>(ForgeOreCalculatorClient.GANG_MESSAGES);
		}

		int visible = 13;
		int maxScroll = Math.max(0, messages.size() - visible);
		scroll = Math.max(0, Math.min(maxScroll, scroll + pollScroll()));

		if (messages.isEmpty()) {
			ctx.drawText(this.textRenderer, Text.literal("§8No gang messages seen yet."), listX + 5, listY + 5, 0xFFFFFFFF, true);
			ctx.drawText(this.textRenderer, Text.literal("§8Messages from §7/gc §8appear here"), listX + 5, listY + 16, 0xFFFFFFFF, true);
		} else {
			List<String> wrapped = new ArrayList<>();
			for (String message : messages) {
				String rest = message;
				boolean first = true;
				while (rest.length() > 44) {
					int cut = rest.lastIndexOf(' ', 44);
					if (cut < 20) cut = 44;
					wrapped.add((first ? "§f" : "§f  ") + rest.substring(0, cut));
					rest = rest.substring(cut).trim();
					first = false;
				}
				if (!rest.isEmpty()) {
					wrapped.add((first ? "§f" : "§f  ") + rest);
				}
			}
			int maxWrapScroll = Math.max(0, wrapped.size() - visible);
			scroll = Math.min(scroll, maxWrapScroll);
			int start = Math.max(0, wrapped.size() - visible - scroll);
			int end = Math.min(wrapped.size(), start + visible);
			int y = listY + 3;
			for (int i = start; i < end; i++) {
				ctx.drawText(this.textRenderer, Text.literal(wrapped.get(i)), listX + 4, y, 0xFFFFFFFF, true);
				y += 10;
			}
		}

		if (messageField != null) {
			messageField.render(ctx, mouseX, mouseY, delta);
		}
		boolean enterDown = this.client != null
				&& (GLFW.glfwGetKey(this.client.getWindow().getHandle(), GLFW.GLFW_KEY_ENTER) == GLFW.GLFW_PRESS
				|| GLFW.glfwGetKey(this.client.getWindow().getHandle(), GLFW.GLFW_KEY_KP_ENTER) == GLFW.GLFW_PRESS);
		if (enterDown && !enterHeld) {
			sendMessage();
		}
		enterHeld = enterDown;

		if (drawBigButton(ctx, mouseX, mouseY, px + panelW - 52, py + 181, 40, 15, "§a§lSEND", null)) {
			sendMessage();
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
