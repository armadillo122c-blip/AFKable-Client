package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The block picker behind {@link BlockScriptScreen}'s ADD button: the whole
 * {@link BlockScript#palette()} grouped under its {@link BlockScript.Category}
 * headers, filtered by a search box.
 *
 * <p>The caller passes in what to do with the pick, so this screen never has to
 * know anything about the open document.</p>
 */
public class BlockPaletteScreen extends FarmScreen {

	private static final int ROW_H = 15;
	private static final int HEAD_H = 13;

	private final Screen parent;
	private final Consumer<BlockScript.Block> onPick;

	private TextFieldWidget searchField;
	private int scroll;

	public BlockPaletteScreen(Screen parent, Consumer<BlockScript.Block> onPick) {
		super(Text.literal("Add a block"));
		this.parent = parent;
		this.onPick = onPick;
	}

	private int panelW() {
		return Math.min(this.width - 20, 400);
	}

	private int panelH() {
		return Math.min(this.height - 20, 284);
	}

	private int panelX() {
		return this.width / 2 - panelW() / 2;
	}

	private int panelY() {
		return Math.max(6, this.height / 2 - panelH() / 2);
	}

	@Override
	protected void init() {
		int px = panelX();
		int py = panelY();
		// resize() rebuilds the widget, so the typed query has to survive it
		String previous = searchField != null ? searchField.getText() : "";
		searchField = new TextFieldWidget(this.textRenderer, px + 8, py + 26, panelW() - 16, 14, Text.literal(""));
		searchField.setMaxLength(32);
		searchField.setPlaceholder(Text.literal("§8search blocks..."));
		if (!previous.isEmpty()) searchField.setText(previous);
		addSelectableChild(searchField);
		setInitialFocus(searchField);
	}

	/** A palette line: either a category header ({@code def == null}) or a block. */
	private record Entry(BlockScript.BlockDef def, BlockScript.Category category) {
		int height() {
			return def == null ? HEAD_H : ROW_H;
		}
	}

	private List<Entry> entries() {
		String query = searchField != null
				? searchField.getText().trim().toLowerCase(Locale.ROOT)
				: "";
		List<Entry> out = new ArrayList<>();
		for (BlockScript.Category category : BlockScript.Category.values()) {
			List<BlockScript.BlockDef> matches = new ArrayList<>();
			for (BlockScript.BlockDef def : BlockScript.palette()) {
				if (def.category != category) continue;
				if (!query.isEmpty() && !matches(def, query)) continue;
				matches.add(def);
			}
			if (matches.isEmpty()) continue;
			out.add(new Entry(null, category));
			for (BlockScript.BlockDef def : matches) out.add(new Entry(def, category));
		}
		return out;
	}

	private static boolean matches(BlockScript.BlockDef def, String query) {
		String label = BlockScriptScreen.stripCodes(BlockScript.paletteLabelOf(def)).toLowerCase(Locale.ROOT);
		return label.contains(query)
				|| def.type.toLowerCase(Locale.ROOT).contains(query)
				|| def.category.label.toLowerCase(Locale.ROOT).contains(query);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = panelW();
		int panelH = panelH();
		int px = panelX();
		int py = panelY();

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer,
				TextFx.gradient("ADD A BLOCK", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo),
				px + panelW / 2, py + 7, CREAM);

		if (searchField != null) {
			searchField.setX(px + 8);
			searchField.setY(py + 26);
			searchField.setWidth(panelW - 16);
			searchField.render(ctx, mouseX, mouseY, delta);
		}

		int listX = px + 8;
		int listY = py + 44;
		int listW = panelW - 16;
		int listH = (py + panelH - 30) - listY;

		ctx.fill(listX - 1, listY - 1, listX + listW + 1, listY + listH + 1, FRAME_DARK);
		ctx.fill(listX, listY, listX + listW, listY + listH, 0xC0140E08);

		List<Entry> entries = entries();
		int contentH = 0;
		for (Entry entry : entries) contentH += entry.height() + 1;

		int maxScroll = Math.max(0, contentH - listH);
		scroll = Math.max(0, Math.min(maxScroll, scroll - pollScroll() * 14));

		if (entries.isEmpty()) {
			ctx.drawText(this.textRenderer, Text.literal("§8Nothing matches that search."),
					listX + 6, listY + 6, 0xFFFFFFFF, true);
		}

		BlockScript.BlockDef picked = null;
		boolean mouseInList = hover(mouseX, mouseY, listX, listY, listW, listH);

		ctx.enableScissor(listX, listY, listX + listW, listY + listH);
		int y = listY - scroll;
		for (Entry entry : entries) {
			int h = entry.height();
			if (y + h < listY || y > listY + listH) {
				y += h + 1;
				continue;
			}
			int colour = entry.category().color;

			if (entry.def() == null) {
				ctx.fill(listX + 2, y, listX + listW - 2, y + h, tint(colour, 0x50));
				ctx.fill(listX + 2, y, listX + 5, y + h, colour);
				ctx.drawText(this.textRenderer, Text.literal("§f" + entry.category().label),
						listX + 9, y + 3, 0xFFFFFFFF, true);
			} else {
				boolean fullyVisible = y >= listY && y + h <= listY + listH;
				boolean hovered = mouseInList && fullyVisible
						&& hover(mouseX, mouseY, listX + 8, y, listW - 12, h);
				ctx.fill(listX + 8, y, listX + listW - 4, y + h, tint(colour, hovered ? 0x88 : 0x33));
				ctx.fill(listX + 8, y, listX + 11, y + h, colour);

				boolean unsupported = !entry.def().engineSupported;
				ctx.drawText(this.textRenderer,
						Text.literal(fit(BlockScript.paletteLabelOf(entry.def()), listW - 30 - (unsupported ? 8 : 0))),
						listX + 15, y + 4, 0xFFFFFFFF, true);
				if (unsupported) {
					ctx.drawText(this.textRenderer, Text.literal("§6!"), listX + listW - 13, y + 4, 0xFFFFFFFF, true);
				}
				if (hovered && mouseClickedThisFrame) {
					mouseClickedThisFrame = false;
					playClick(this.client);
					picked = entry.def();
				}
			}
			y += h + 1;
		}
		ctx.disableScissor();

		if (maxScroll > 0) {
			int barH = Math.max(12, listH * listH / Math.max(1, contentH));
			int barY = listY + (listH - barH) * scroll / maxScroll;
			ctx.fill(listX + listW - 3, listY, listX + listW - 1, listY + listH, 0x30FFFFFF);
			ctx.fill(listX + listW - 3, barY, listX + listW - 1, barY + barH, 0x90FFFFFF);
		}

		if (drawBigButton(ctx, mouseX, mouseY, px + 8, py + panelH - 24, 70, 18, "§fBACK", null)) {
			this.client.setScreen(parent);
			return;
		}
		ctx.drawText(this.textRenderer,
				Text.literal("§8§oclick a block to add it §8· §6! §8= engine support pending"),
				px + 84, py + panelH - 19, 0xFFFFFFFF, true);

		if (picked != null) {
			if (onPick != null) onPick.accept(BlockScript.create(picked.type));
			this.client.setScreen(parent);
		}
	}

	private static int tint(int colour, int alpha) {
		return (alpha << 24) | (colour & 0xFFFFFF);
	}

	private String fit(String text, int maxWidth) {
		if (text == null || text.isEmpty()) return "";
		if (this.textRenderer.getWidth(text) <= maxWidth) return text;
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			if (this.textRenderer.getWidth(out.toString() + text.charAt(i) + "…") > maxWidth) break;
			out.append(text.charAt(i));
		}
		if (out.length() > 0 && out.charAt(out.length() - 1) == '§') out.setLength(out.length() - 1);
		return out + "…";
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}
}
