package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Configure the in-GUI quick-button column: toggle each on/off and drag the
 * command buttons to reorder them.
 *
 * The list is scissor-clipped and scrolls by pixel, so no row can end up drawn
 * outside the panel or become unreachable at the bottom.
 */
public class ButtonsScreen extends FarmScreen {

	private record Row(String id, String label, boolean reorderable) {
	}

	private static final List<String[]> SPECIALS = List.of(
			new String[]{"sort", "§a§lSORT"},
			new String[]{"lazy", "§a§lLAZY STORAGE"},
			new String[]{"pv", "§b§lPV -/+"},
			new String[]{"tinker", "§b§lTINKER"},
			new String[]{"smelt", "§6§lSMELT ALL"},
			new String[]{"dominion", "§5§lAUTO DOMINION"});
	private static final Set<String> FACTORY_UNAVAILABLE = Set.of(
			"claude", "crystals", "eggs", "incubator", "dominion", "gear", "seeds");

	private static final List<String[]> COMMANDS = List.of(
			new String[]{"trash", "§c§lTRASH"},
			new String[]{"stock", "§6§lSTOCK"},
			new String[]{"claude", "§b§lCLAUDE"},
			new String[]{"orebag", "§3§lOREBAG"},
			new String[]{"diary", "§d§lDIARY"},
			new String[]{"crystals", "§5§lCRYSTALS"},
			new String[]{"eggs", "§d§lEGGS"},
			new String[]{"incubator", "§6§lINCUB"},
			new String[]{"pets", "§d§lPETS"},
			new String[]{"forge", "§6§lFORGE"},
			new String[]{"gear", "§b§lGEAR"},
			new String[]{"seeds", "§a§lSEEDS"},
			new String[]{"garden", "§2§lGARDEN"},
			new String[]{"vault", "§2§lVAULT"},
			new String[]{"gang", "§3§lGANG"},
			new String[]{"prestige", "§e§lPRESTIGE"},
			new String[]{"ec", "§d§lEC"});

	private static final int ROW_H = 16;
	private static final int ROW_GAP = 1;

	private final Screen parent;
	private int scroll = 0;
	private String dragging = null;
	private boolean mouseWasDown = false;
	private String search = "";

	public ButtonsScreen(Screen parent) {
		super(Text.literal("Buttons"));
		this.parent = parent;
	}

	private List<String> allOrder() {
		List<String> order = new ArrayList<>();
		for (String id : ForgeCalcConfig.buttonOrder.split(",")) {
			if (!id.isBlank()) order.add(id.trim());
		}
		for (String[] c : COMMANDS) {
			if (!order.contains(c[0])) order.add(c[0]);
		}
		// drop ids that no longer exist so a stale config cannot desync the list
		order.removeIf(id -> COMMANDS.stream().noneMatch(c -> c[0].equals(id)));
		return order;
	}

	private List<String> order() {
		return allOrder().stream().filter(this::isVisibleInCurrentMode).toList();
	}

	private boolean isVisibleInCurrentMode(String id) {
		return !ForgeOreCalculatorClient.isFactoryMode(this.client) || !FACTORY_UNAVAILABLE.contains(id);
	}

	private List<String[]> specialsForCurrentMode() {
		if (!ForgeOreCalculatorClient.isFactoryMode(this.client)) return SPECIALS;
		return SPECIALS.stream().filter(row -> !FACTORY_UNAVAILABLE.contains(row[0])).toList();
	}

	private String labelFor(String id) {
		for (String[] c : COMMANDS) {
			if (c[0].equals(id)) return c[1];
		}
		for (String[] s : SPECIALS) {
			if (s[0].equals(id)) return s[1];
		}
		return "§f" + id;
	}

	private static String plain(String label) {
		return label.replaceAll("§[0-9a-fk-or]", "").toLowerCase();
	}

	private List<Row> rows() {
		List<Row> rows = new ArrayList<>();
		for (String[] sp : specialsForCurrentMode()) {
			rows.add(new Row(sp[0], sp[1], false));
		}
		for (String id : order()) {
			rows.add(new Row(id, labelFor(id), true));
		}
		if (search.isEmpty()) return rows;
		rows.removeIf(r -> !plain(r.label()).contains(search.toLowerCase()) && !r.id().contains(search.toLowerCase()));
		return rows;
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		if (chr >= ' ' && search.length() < 16) {
			search += chr;
			scroll = 0;
			return true;
		}
		return super.charTyped(chr, modifiers);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
			search = search.substring(0, search.length() - 1);
			scroll = 0;
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = 320;
		int panelH = 250;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - panelH / 2;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 24, PLANK_DARK);
		ctx.fill(px, py + 24, px + panelW, py + 25, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer,
				TextFx.gradient("QUICK BUTTONS", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo),
				px + panelW / 2, py + 8, CREAM);

		List<Row> rows = rows();
		int specialCount = specialsForCurrentMode().size();
		int shown = 0;
		for (Row r : rows) {
			if (!ForgeCalcConfig.hiddenButtons.contains(r.id())) shown++;
		}

		// search box doubles as the hint line
		int searchY = py + 28;
		ctx.fill(px + 12, searchY, px + panelW - 12, searchY + 13, 0x40000000);
		String searchLabel = search.isEmpty()
				? "§8type to search §8• §8drag §7☰ §8to reorder"
				: "§f" + search + "§7_";
		ctx.drawText(this.textRenderer, Text.literal(searchLabel), px + 16, searchY + 3, 0xFFFFFFFF, true);
		ctx.drawText(this.textRenderer, Text.literal("§8" + shown + "§7/§8" + rows.size() + " on"),
				px + panelW - 62, searchY + 3, 0xFFFFFFFF, true);

		int listX = px + 12;
		int listY = py + 46;
		int listW = panelW - 34;
		int listH = py + panelH - 30 - listY;

		int contentH = rows.size() * (ROW_H + ROW_GAP);
		int maxScroll = Math.max(0, contentH - listH);
		scroll = Math.max(0, Math.min(maxScroll, scroll - pollScroll() * 12));

		boolean mouseDown = this.client != null
				&& GLFW.glfwGetMouseButton(this.client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

		String toggleClicked = null;
		ctx.enableScissor(listX, listY, listX + listW + 4, listY + listH);
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			int ry = listY + i * (ROW_H + ROW_GAP) - scroll;
			if (ry + ROW_H < listY || ry > listY + listH) continue;

			boolean hidden = ForgeCalcConfig.hiddenButtons.contains(row.id());
			boolean isDragged = row.id().equals(dragging);

			ctx.fill(listX, ry, listX + listW, ry + ROW_H,
					isDragged ? 0x60FFFFFF : (hidden ? 0x22FF5555 : 0x2255FF88));
			ctx.fill(listX, ry, listX + 2, ry + ROW_H,
					hidden ? 0xFFAA3333 : (0xFF000000 | (ForgeCalcTheme.current().accent & 0xFFFFFF)));

			if (row.reorderable()) {
				ctx.drawText(this.textRenderer, Text.literal("§8☰"), listX + 6, ry + 4, 0xFFFFFFFF, true);
			} else {
				ctx.drawText(this.textRenderer, Text.literal("§8●"), listX + 6, ry + 4, 0xFFFFFFFF, true);
			}
			ctx.drawText(this.textRenderer, Text.literal((hidden ? "§8" : "") + row.label()), listX + 18, ry + 4, 0xFFFFFFFF, true);

			// the toggle is only live when the row is fully inside the viewport
			boolean rowVisible = ry >= listY && ry + ROW_H <= listY + listH;
			int bx = listX + listW - 42;
			boolean hovered = rowVisible && hover(mouseX, mouseY, bx, ry + 1, 38, ROW_H - 2);
			ctx.fill(bx, ry + 1, bx + 38, ry + ROW_H - 1, hovered ? 0x60FFFFFF : 0x30000000);
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(hidden ? "§cOFF" : "§aON"),
					bx + 19, ry + 5, 0xFFFFFFFF);
			if (hovered && mouseClickedThisFrame) {
				toggleClicked = row.id();
			}

			if (row.reorderable() && rowVisible && mouseDown && !mouseWasDown && dragging == null
					&& mouseX >= listX && mouseX <= listX + 60 && mouseY >= ry && mouseY <= ry + ROW_H) {
				dragging = row.id();
			}
		}
		ctx.disableScissor();

		if (toggleClicked != null) {
			mouseClickedThisFrame = false;
			playClick(this.client);
			if (!ForgeCalcConfig.hiddenButtons.remove(toggleClicked)) {
				ForgeCalcConfig.hiddenButtons.add(toggleClicked);
			}
			ForgeCalcConfig.save();
		}

		// active drag: reorder against the full (unfiltered) command order
		if (dragging != null) {
			if (!mouseDown) {
				dragging = null;
			} else {
				List<String> newOrder = allOrder();
				List<String> visibleOrder = order();
				int hoveredIndex = (mouseY - listY + scroll) / (ROW_H + ROW_GAP) - specialCount;
				int from = visibleOrder.indexOf(dragging);
				int target = Math.max(0, Math.min(visibleOrder.size() - 1, hoveredIndex));
				if (from >= 0 && target != from && search.isEmpty()) {
					visibleOrder = new ArrayList<>(visibleOrder);
					visibleOrder.remove(from);
					visibleOrder.add(target, dragging);
					int visibleIndex = 0;
					for (int i = 0; i < newOrder.size(); i++) {
						if (isVisibleInCurrentMode(newOrder.get(i))) {
							newOrder.set(i, visibleOrder.get(visibleIndex++));
						}
					}
					ForgeCalcConfig.buttonOrder = String.join(",", newOrder);
					ForgeCalcConfig.save();
				}
				ctx.drawText(this.textRenderer, Text.literal("§e" + labelFor(dragging)), mouseX + 6, mouseY - 4, 0xFFFFFFFF, true);
			}
		}
		mouseWasDown = mouseDown;

		int trackX = px + panelW - 18;
		ctx.fill(trackX, listY, trackX + 3, listY + listH, 0x50000000);
		if (maxScroll > 0) {
			int thumbH = Math.max(14, listH * listH / Math.max(1, contentH));
			int thumbY = listY + (listH - thumbH) * scroll / maxScroll;
			ctx.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, GREEN);
		}

		int third = (panelW - 30) / 3;
		int by = py + panelH - 24;
		if (drawBigButton(ctx, mouseX, mouseY, px + 12, by, third, 18, "§f§lBACK", null)) {
			close();
			return;
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 15 + third, by, third, 18, "§a§lALL ON", null)) {
			ForgeCalcConfig.hiddenButtons.clear();
			ForgeCalcConfig.save();
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 18 + third * 2, by, third, 18, "§e§lRESET", null)) {
			ForgeCalcConfig.buttonOrder = "trash,stock,orebag,diary,pets,forge,garden,vault,gang,prestige,ec";
			ForgeCalcConfig.hiddenButtons.clear();
			ForgeCalcConfig.save();
			search = "";
			scroll = 0;
		}
	}

	@Override
	public void close() {
		ForgeCalcConfig.save();
		this.client.setScreen(parent);
	}
}
