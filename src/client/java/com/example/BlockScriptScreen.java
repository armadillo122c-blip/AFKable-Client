package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The visual editor for {@link BlockScript}. Two modes live in one screen:
 *
 * <ul>
 *   <li><b>doc list</b> - no script open: the saved scripts with NEW / OPEN /
 *       RENAME / DELETE.</li>
 *   <li><b>canvas</b> - a script open: the ordered block list on the left with
 *       C-shaped blocks indenting their children, the typed parameter inspector
 *       on the right, and a read-only compiled preview behind VIEW CODE.</li>
 * </ul>
 *
 * <p>Nothing here owns the model. Every edit mutates the {@link BlockScript.Doc}
 * in place and is written back through {@link BlockScript#saveAll(List)} on SAVE
 * and on close, so the JSON on disk is always the last thing the user saw.</p>
 */
public class BlockScriptScreen extends FarmScreen {

	private static final int ROW_H = 15;
	private static final int ELSE_H = 11;
	private static final int END_H = 7;
	private static final int INDENT = 10;
	/** Plenty for the catalogue (widest block has two), sized so it never has to grow. */
	private static final int MAX_PARAM_FIELDS = 6;
	private static final int OFFSCREEN = -500;

	private final Screen parent;

	private final List<BlockScript.Doc> docs = new ArrayList<>();
	/** null => doc list mode. */
	private BlockScript.Doc doc;
	private BlockScript.Block selected;

	private int selectedDoc = -1;
	private int pendingDelete = -1;
	private int listScroll;
	private int docScroll;
	private int codeScroll;
	private boolean showCode;

	private String status = "";
	private long statusUntil;

	private TextFieldWidget nameField;
	private final TextFieldWidget[] paramFields = new TextFieldWidget[MAX_PARAM_FIELDS];
	/** The block the parameter fields currently mirror; a mismatch forces a rebind. */
	private BlockScript.Block boundBlock;

	public BlockScriptScreen(Screen parent) {
		super(Text.literal("Block Scripts"));
		this.parent = parent;
		this.docs.addAll(BlockScript.loadAll());
		if (!BlockScript.lastLoadError.isEmpty()) setStatus(BlockScript.lastLoadError);
	}

	// ------------------------------------------------------------------
	// geometry
	// ------------------------------------------------------------------

	private int panelW() {
		return Math.min(this.width - 20, 470);
	}

	private int panelH() {
		return Math.min(this.height - 20, 296);
	}

	private int panelX() {
		return this.width / 2 - panelW() / 2;
	}

	private int panelY() {
		return Math.max(6, this.height / 2 - panelH() / 2);
	}

	private int inspectorW() {
		return Math.max(112, Math.min(160, panelW() / 3));
	}

	// ------------------------------------------------------------------
	// widgets
	// ------------------------------------------------------------------

	@Override
	protected void init() {
		int px = panelX();
		int py = panelY();
		int panelW = panelW();

		// resize() rebuilds every widget, so the typed text has to be carried across
		String previousName = nameField != null ? nameField.getText() : "";
		nameField = new TextFieldWidget(this.textRenderer, px + 8, py + 26, panelW - 16, 14, Text.literal(""));
		nameField.setMaxLength(28);
		nameField.setPlaceholder(Text.literal("§8name for NEW / RENAME"));
		if (!previousName.isEmpty()) nameField.setText(previousName);
		addSelectableChild(nameField);

		for (int i = 0; i < MAX_PARAM_FIELDS; i++) {
			String previous = paramFields[i] != null ? paramFields[i].getText() : "";
			TextFieldWidget field = new TextFieldWidget(this.textRenderer, OFFSCREEN, OFFSCREEN, 80, 14, Text.literal(""));
			field.setMaxLength(180);
			if (!previous.isEmpty()) field.setText(previous);
			paramFields[i] = field;
			addSelectableChild(field);
		}
		// the fresh fields hold stale text until the inspector re-binds them
		boundBlock = null;
	}

	private void setStatus(String text) {
		status = text;
		statusUntil = System.currentTimeMillis() + 7000;
	}

	private void hideParamFields() {
		for (TextFieldWidget field : paramFields) {
			if (field != null) field.setY(OFFSCREEN);
		}
	}

	private void blurAll() {
		if (nameField != null) nameField.setFocused(false);
		for (TextFieldWidget field : paramFields) {
			if (field != null) field.setFocused(false);
		}
		this.setFocused(null);
	}

	private boolean blurFocusedField() {
		boolean any = false;
		if (nameField != null && nameField.isFocused()) {
			nameField.setFocused(false);
			any = true;
		}
		for (TextFieldWidget field : paramFields) {
			if (field != null && field.isFocused()) {
				field.setFocused(false);
				any = true;
			}
		}
		if (any) this.setFocused(null);
		return any;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			// first ESC leaves the text box / the code pane instead of closing everything
			if (blurFocusedField()) return true;
			if (showCode) {
				showCode = false;
				return true;
			}
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	// ------------------------------------------------------------------
	// render
	// ------------------------------------------------------------------

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
				TextFx.gradient(doc == null ? "BLOCK SCRIPTS" : "BLOCK EDITOR",
						ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo),
				px + panelW / 2, py + 7, CREAM);

		boolean changed = doc == null
				? renderDocList(ctx, mouseX, mouseY, delta, px, py, panelW, panelH)
				: renderCanvas(ctx, mouseX, mouseY, delta, px, py, panelW, panelH);
		if (changed) return;

		if (!status.isEmpty() && System.currentTimeMillis() < statusUntil) {
			ctx.drawText(this.textRenderer, Text.literal(fit(status, panelW - 16)),
					px + 8, py + panelH - 38, 0xFFFFFFFF, true);
		}
	}

	// ---------------------------------------------------------------- doc list

	private boolean renderDocList(DrawContext ctx, int mouseX, int mouseY, float delta,
								  int px, int py, int panelW, int panelH) {
		hideParamFields();

		if (nameField != null) {
			nameField.setX(px + 8);
			nameField.setY(py + 26);
			nameField.setWidth(panelW - 16);
			nameField.render(ctx, mouseX, mouseY, delta);
		}

		int listX = px + 8;
		int listY = py + 44;
		int listW = panelW - 16;
		int listH = (py + panelH - 42) - listY;

		ctx.fill(listX - 1, listY - 1, listX + listW + 1, listY + listH + 1, FRAME_DARK);
		ctx.fill(listX, listY, listX + listW, listY + listH, 0xC0140E08);

		int rowH = 17;
		int contentH = docs.size() * rowH;
		int maxScroll = Math.max(0, contentH - listH);
		docScroll = Math.max(0, Math.min(maxScroll, docScroll - pollScroll() * 14));

		if (docs.isEmpty()) {
			ctx.drawText(this.textRenderer, Text.literal("§8No block scripts yet - press §aNEW"),
					listX + 6, listY + 6, 0xFFFFFFFF, true);
		}

		ctx.enableScissor(listX, listY, listX + listW, listY + listH);
		for (int i = 0; i < docs.size(); i++) {
			int ry = listY + i * rowH - docScroll;
			if (ry + rowH < listY || ry > listY + listH) continue;

			BlockScript.Doc entry = docs.get(i);
			boolean isSel = i == selectedDoc;
			boolean fullyVisible = ry >= listY && ry + rowH <= listY + listH;
			boolean hovered = fullyVisible && hover(mouseX, mouseY, listX, ry, listW, rowH - 1);

			ctx.fill(listX, ry, listX + listW, ry + rowH - 1,
					isSel ? 0x506FBF4A : (hovered ? 0x40FFFFFF : 0x22FFFFFF));
			ctx.fill(listX, ry, listX + 2, ry + rowH - 1,
					isSel ? 0xFF6FBF4A : 0xFF5A4A34);

			String meta = "§8" + countBlocks(entry.blocks) + " blocks · " + ago(entry.updated);
			int metaW = this.textRenderer.getWidth(stripCodes(meta)) + 8;
			ctx.drawText(this.textRenderer,
					Text.literal(fit((isSel ? "§a" : "§f") + entry.name, listW - metaW - 12)),
					listX + 7, ry + 4, 0xFFFFFFFF, true);
			ctx.drawText(this.textRenderer, Text.literal(meta), listX + listW - metaW + 4, ry + 4, 0xFFFFFFFF, true);

			if (hovered && mouseClickedThisFrame) {
				mouseClickedThisFrame = false;
				playClick(this.client);
				selectedDoc = i;
				pendingDelete = -1;
				if (nameField != null) nameField.setText(entry.name);
			}
		}
		ctx.disableScissor();

		// ---- buttons ----
		int bw = (panelW - 16 - 16) / 5;
		int by = py + panelH - 24;
		int bx = px + 8;

		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§aNEW", null)) {
			String name = nameField != null ? nameField.getText().trim() : "";
			if (name.isEmpty()) name = "script " + (docs.size() + 1);
			BlockScript.Doc created = new BlockScript.Doc(name);
			docs.add(created);
			selectedDoc = docs.size() - 1;
			BlockScript.saveAll(docs);
			openDoc(created);
			setStatus("§aCreated §f" + name);
			return false;
		}
		bx += bw + 4;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§bOPEN", null)) {
			if (selectedDoc >= 0 && selectedDoc < docs.size()) {
				openDoc(docs.get(selectedDoc));
			} else {
				setStatus("§cPick a script first");
			}
			return false;
		}
		bx += bw + 4;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§eRENAME", null)) {
			String name = nameField != null ? nameField.getText().trim() : "";
			if (selectedDoc < 0 || selectedDoc >= docs.size()) {
				setStatus("§cPick a script first");
			} else if (name.isEmpty()) {
				setStatus("§cType the new name in the box first");
			} else {
				docs.get(selectedDoc).name = name;
				docs.get(selectedDoc).updated = System.currentTimeMillis();
				BlockScript.saveAll(docs);
				setStatus("§aRenamed to §f" + name);
			}
			return false;
		}
		bx += bw + 4;
		boolean confirming = pendingDelete >= 0 && pendingDelete == selectedDoc;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, confirming ? "§4SURE?" : "§cDELETE", null)) {
			if (selectedDoc < 0 || selectedDoc >= docs.size()) {
				setStatus("§cPick a script first");
			} else if (!confirming) {
				pendingDelete = selectedDoc;
				setStatus("§cPress DELETE again to remove §f" + docs.get(selectedDoc).name);
			} else {
				String gone = docs.remove(selectedDoc).name;
				pendingDelete = -1;
				selectedDoc = Math.min(selectedDoc, docs.size() - 1);
				docScroll = 0;
				BlockScript.saveAll(docs);
				setStatus("§7Deleted §f" + gone);
			}
			return false;
		}
		bx += bw + 4;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§fBACK", null)) {
			close();
			return true;
		}
		return false;
	}

	private void openDoc(BlockScript.Doc target) {
		doc = target;
		selected = target.blocks.isEmpty() ? null : target.blocks.get(0);
		boundBlock = null;
		listScroll = 0;
		codeScroll = 0;
		showCode = false;
		pendingDelete = -1;
		blurAll();
	}

	// ---------------------------------------------------------------- canvas

	private boolean renderCanvas(DrawContext ctx, int mouseX, int mouseY, float delta,
								 int px, int py, int panelW, int panelH) {
		if (nameField != null) nameField.setY(OFFSCREEN);

		List<String> warnings = BlockScript.warnings(doc.blocks);
		String head = "§7" + fit(doc.name, panelW - 150) + " §8· " + countBlocks(doc.blocks) + " blocks";
		ctx.drawText(this.textRenderer, Text.literal(head), px + 8, py + 26, 0xFFFFFFFF, true);
		if (!warnings.isEmpty()) {
			String badge = "§6! " + warnings.size() + (warnings.size() == 1 ? " warning" : " warnings");
			ctx.drawText(this.textRenderer, Text.literal(badge),
					px + panelW - 8 - this.textRenderer.getWidth(stripCodes(badge)), py + 26, 0xFFFFFFFF, true);
		}

		int insW = inspectorW();
		int listX = px + 8;
		int listY = py + 38;
		int listW = panelW - 16 - insW - 8;
		int listH = (py + panelH - 42) - listY;
		int insX = listX + listW + 8;

		if (showCode) {
			hideParamFields();
			renderCode(ctx, listX, listY, listW + 8 + insW, listH, warnings);
		} else {
			renderBlockList(ctx, mouseX, mouseY, listX, listY, listW, listH);
			renderInspector(ctx, mouseX, mouseY, delta, insX, listY, insW, listH);
		}

		// ---- buttons ----
		int bw = (panelW - 16 - 8 * 4) / 9;
		int by = py + panelH - 24;
		int bx = px + 8;
		int step = bw + 4;

		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§aADD", null)) {
			this.client.setScreen(new BlockPaletteScreen(this, this::addBlock));
			return true;
		}
		bx += step;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§fUP", null)) {
			moveSelected(true);
		}
		bx += step;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§fDN", null)) {
			moveSelected(false);
		}
		bx += step;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§cDEL", null)) {
			deleteSelected();
		}
		bx += step;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§eDUP", null)) {
			duplicateSelected();
		}
		bx += step;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, showCode ? "§bBLOCKS" : "§bCODE", null)) {
			showCode = !showCode;
			codeScroll = 0;
			blurAll();
		}
		bx += step;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§aRUN", null)) {
			doc.updated = System.currentTimeMillis();
			BlockScript.saveAll(docs);
			if (BlockScript.run(this.client, doc)) {
				this.client.setScreen(null);
				return true;
			}
			setStatus("§cCould not start - the script is empty or you are not in a world");
		}
		bx += step;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§6SAVE", null)) {
			doc.updated = System.currentTimeMillis();
			BlockScript.saveAll(docs);
			setStatus("§aSaved §f" + doc.name);
		}
		bx += step;
		if (drawBigButton(ctx, mouseX, mouseY, bx, by, bw, 18, "§fBACK", null)) {
			doc.updated = System.currentTimeMillis();
			BlockScript.saveAll(docs);
			doc = null;
			selected = null;
			boundBlock = null;
			showCode = false;
			blurAll();
			hideParamFields();
			setStatus("§7Saved");
		}
		return false;
	}

	// ---------------------------------------------------------------- block list

	/** One printed line of the canvas. {@code kind}: 0 block, 1 else divider, 2 end cap. */
	private static final class Row {
		final BlockScript.Block block;
		final int depth;
		final int kind;

		Row(BlockScript.Block block, int depth, int kind) {
			this.block = block;
			this.depth = depth;
			this.kind = kind;
		}

		int height() {
			return kind == 0 ? ROW_H : (kind == 1 ? ELSE_H : END_H);
		}
	}

	private List<Row> rows() {
		List<Row> out = new ArrayList<>();
		flatten(doc.blocks, 0, out);
		return out;
	}

	private void flatten(List<BlockScript.Block> list, int depth, List<Row> out) {
		for (BlockScript.Block block : list) {
			out.add(new Row(block, depth, 0));
			BlockScript.BlockDef def = BlockScript.def(block.type);
			if (def != null && def.container) {
				flatten(block.children, depth + 1, out);
				if (def.hasElse) {
					out.add(new Row(block, depth, 1));
					flatten(block.elseChildren, depth + 1, out);
				}
				out.add(new Row(block, depth, 2));
			}
		}
	}

	private void renderBlockList(DrawContext ctx, int mouseX, int mouseY, int listX, int listY, int listW, int listH) {
		ctx.fill(listX - 1, listY - 1, listX + listW + 1, listY + listH + 1, FRAME_DARK);
		ctx.fill(listX, listY, listX + listW, listY + listH, 0xC0140E08);

		List<Row> rows = rows();
		int contentH = 0;
		for (Row row : rows) contentH += row.height() + 1;

		int maxScroll = Math.max(0, contentH - listH);
		listScroll = Math.max(0, Math.min(maxScroll, listScroll - pollScroll() * 14));

		if (rows.isEmpty()) {
			ctx.drawText(this.textRenderer, Text.literal("§8Empty script."), listX + 6, listY + 6, 0xFFFFFFFF, true);
			ctx.drawText(this.textRenderer, Text.literal("§8Press §aADD §8to place your first block."),
					listX + 6, listY + 18, 0xFFFFFFFF, true);
			return;
		}

		boolean mouseInList = hover(mouseX, mouseY, listX, listY, listW, listH);
		ctx.enableScissor(listX, listY, listX + listW, listY + listH);
		int y = listY - listScroll;
		for (Row row : rows) {
			int h = row.height();
			if (y + h < listY || y > listY + listH) {
				y += h + 1;
				continue;
			}

			BlockScript.BlockDef def = BlockScript.def(row.block.type);
			int colour = def != null ? def.category.color : BlockScript.Category.RAW.color;
			int rx = listX + 3 + row.depth * INDENT;
			int rw = Math.max(24, listX + listW - 4 - rx);
			boolean isSel = row.block == selected;

			if (row.kind == 0) {
				ctx.fill(rx, y, rx + rw, y + h, tint(colour, isSel ? 0x88 : 0x38));
				ctx.fill(rx, y, rx + 3, y + h, colour);
				if (isSel) {
					ctx.fill(rx, y, rx + rw, y + 1, CREAM);
					ctx.fill(rx, y + h - 1, rx + rw, y + h, CREAM);
					ctx.fill(rx + rw - 1, y, rx + rw, y + h, CREAM);
				}
				boolean unsupported = def != null && !def.engineSupported;
				int textW = rw - 12 - (unsupported ? 8 : 0);
				ctx.drawText(this.textRenderer, Text.literal(fit(BlockScript.labelOf(row.block), textW)),
						rx + 7, y + 4, 0xFFFFFFFF, true);
				if (unsupported) {
					ctx.drawText(this.textRenderer, Text.literal("§6!"), rx + rw - 8, y + 4, 0xFFFFFFFF, true);
				}
			} else if (row.kind == 1) {
				ctx.fill(rx, y, rx + rw, y + h, tint(colour, 0x30));
				ctx.fill(rx, y, rx + 3, y + h, colour);
				ctx.drawText(this.textRenderer, Text.literal("§7else"), rx + 7, y + 2, 0xFFFFFFFF, true);
			} else {
				ctx.fill(rx, y, rx + Math.min(rw, 34), y + h, tint(colour, 0x60));
				ctx.fill(rx, y, rx + 3, y + h, colour);
			}

			boolean fullyVisible = y >= listY && y + h <= listY + listH;
			if (mouseInList && fullyVisible && hover(mouseX, mouseY, rx, y, rw, h) && mouseClickedThisFrame) {
				mouseClickedThisFrame = false;
				playClick(this.client);
				selected = row.block;
			}
			y += h + 1;
		}
		ctx.disableScissor();

		if (maxScroll > 0) {
			// slim scrollbar so a long script reads as scrollable
			int barH = Math.max(12, listH * listH / Math.max(1, contentH));
			int barY = listY + (listH - barH) * listScroll / maxScroll;
			ctx.fill(listX + listW - 3, listY, listX + listW - 1, listY + listH, 0x30FFFFFF);
			ctx.fill(listX + listW - 3, barY, listX + listW - 1, barY + barH, 0x90FFFFFF);
		}
	}

	private static int tint(int colour, int alpha) {
		return (alpha << 24) | (colour & 0xFFFFFF);
	}

	// ---------------------------------------------------------------- inspector

	private void renderInspector(DrawContext ctx, int mouseX, int mouseY, float delta,
								 int x, int y, int w, int h) {
		ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, FRAME_DARK);
		ctx.fill(x, y, x + w, y + h, 0xC0140E08);
		ctx.fill(x, y, x + w, y + 12, 0x40FFFFFF);
		ctx.drawText(this.textRenderer, Text.literal("§7INSPECTOR"), x + 4, y + 2, 0xFFFFFFFF, true);

		if (selected != boundBlock) {
			// selection changed: pull every field back out of the model before anything reads them
			for (int i = 0; i < MAX_PARAM_FIELDS; i++) {
				TextFieldWidget field = paramFields[i];
				if (field == null) continue;
				field.setFocused(false);
				field.setText(selected != null ? selected.param(i) : "");
				field.setY(OFFSCREEN);
			}
			this.setFocused(null);
			boundBlock = selected;
		}

		if (selected == null) {
			hideParamFields();
			ctx.drawText(this.textRenderer, Text.literal("§8Click a block"), x + 5, y + 20, 0xFFFFFFFF, true);
			ctx.drawText(this.textRenderer, Text.literal("§8on the left."), x + 5, y + 30, 0xFFFFFFFF, true);
			return;
		}

		BlockScript.BlockDef def = BlockScript.def(selected.type);
		int colour = def != null ? def.category.color : BlockScript.Category.RAW.color;
		int cursor = y + 16;

		ctx.fill(x + 4, cursor, x + w - 4, cursor + 12, tint(colour, 0x60));
		ctx.fill(x + 4, cursor, x + 7, cursor + 12, colour);
		ctx.drawText(this.textRenderer,
				Text.literal(fit("§f" + (def != null ? def.category.label : "RAW"), w - 16)),
				x + 10, cursor + 2, 0xFFFFFFFF, true);
		cursor += 15;

		if (def == null) {
			hideParamFields();
			ctx.drawText(this.textRenderer, Text.literal("§cUnknown block"), x + 5, cursor, 0xFFFFFFFF, true);
			return;
		}

		for (String piece : wrap(BlockScript.paletteLabelOf(def), w - 10, 2)) {
			ctx.drawText(this.textRenderer, Text.literal(piece), x + 5, cursor, 0xFFFFFFFF, true);
			cursor += 10;
		}
		cursor += 2;

		if (!def.engineSupported) {
			ctx.drawText(this.textRenderer, Text.literal(fit("§6needs: §f" + def.needsCommand, w - 10)),
					x + 5, cursor, 0xFFFFFFFF, true);
			cursor += 11;
		}

		// RAW blocks carry a whole engine line: show it, do not offer to edit it here
		if ("raw".equals(selected.type)) {
			hideParamFields();
			ctx.drawText(this.textRenderer, Text.literal("§8literal line (read-only)"), x + 5, cursor, 0xFFFFFFFF, true);
			cursor += 11;
			int boxH = Math.max(12, Math.min(h - (cursor - y) - 6, 60));
			ctx.fill(x + 4, cursor, x + w - 4, cursor + boxH, 0xC0000000);
			String line = selected.param(0);
			if (line.isBlank()) line = "§8(blank line)";
			List<String> wrapped = wrap("§f" + line, w - 14, Math.max(1, boxH / 10));
			int ty = cursor + 3;
			for (String piece : wrapped) {
				ctx.drawText(this.textRenderer, Text.literal(piece), x + 7, ty, 0xFFFFFFFF, true);
				ty += 10;
			}
			cursor += boxH + 4;
			ctx.drawText(this.textRenderer, Text.literal(fit("§8edit raw lines in SCRIPTS", w - 10)),
					x + 5, cursor, 0xFFFFFFFF, true);
			return;
		}

		if (def.params.isEmpty()) {
			hideParamFields();
			ctx.drawText(this.textRenderer, Text.literal("§8no settings"), x + 5, cursor, 0xFFFFFFFF, true);
			cursor += 12;
			if (def.container) {
				ctx.drawText(this.textRenderer, Text.literal(fit("§8ADD puts blocks inside", w - 10)),
						x + 5, cursor, 0xFFFFFFFF, true);
			}
			return;
		}

		int bottom = y + h - 4;
		for (int i = 0; i < def.params.size(); i++) {
			BlockScript.ParamDef param = def.params.get(i);
			int rowH = param.type == BlockScript.ParamType.BOOLEAN ? 20 : 28;
			TextFieldWidget field = i < MAX_PARAM_FIELDS ? paramFields[i] : null;
			if (cursor + rowH > bottom || field == null) {
				if (field != null) field.setY(OFFSCREEN);
				continue;
			}

			if (param.type != BlockScript.ParamType.BOOLEAN) {
				String name = "§7" + param.name
						+ (param.type == BlockScript.ParamType.SLOT ? " §8(0-53)" : "");
				ctx.drawText(this.textRenderer, Text.literal(fit(name, w - 10)), x + 5, cursor, 0xFFFFFFFF, true);
			}
			int rowY = cursor + (param.type == BlockScript.ParamType.BOOLEAN ? 0 : 10);

			switch (param.type) {
				case INT, SLOT -> {
					int fieldW = w - 8 - 30;
					if (drawBigButton(ctx, mouseX, mouseY, x + 4, rowY, 12, 14, "§c-", null)) {
						step(i, -1, param.type);
					}
					if (drawBigButton(ctx, mouseX, mouseY, x + w - 16, rowY, 12, 14, "§a+", null)) {
						step(i, 1, param.type);
					}
					placeField(ctx, mouseX, mouseY, delta, field, i, x + 19, rowY, fieldW);
				}
				case STRING, ITEM -> placeField(ctx, mouseX, mouseY, delta, field, i, x + 4, rowY, w - 8);
				case BOOLEAN -> {
					field.setY(OFFSCREEN);
					boolean value = "true".equalsIgnoreCase(selected.param(i).trim());
					if (drawToggleRow(ctx, mouseX, mouseY, x + 4, rowY, w - 8, param.name, value)) {
						selected.setParam(i, value ? "false" : "true");
					}
				}
				case DIRECTION -> {
					field.setY(OFFSCREEN);
					String current = currentDirection(i);
					if (drawBigButton(ctx, mouseX, mouseY, x + 4, rowY, w - 8, 14, "§b" + current + " §8▸", null)) {
						int index = 0;
						for (int k = 0; k < BlockScript.DIRECTIONS.length; k++) {
							if (BlockScript.DIRECTIONS[k].equals(current)) index = k;
						}
						selected.setParam(i, BlockScript.DIRECTIONS[(index + 1) % BlockScript.DIRECTIONS.length]);
					}
				}
			}
			cursor += rowH;
		}

		for (int i = def.params.size(); i < MAX_PARAM_FIELDS; i++) {
			if (paramFields[i] != null) paramFields[i].setY(OFFSCREEN);
		}

		if (cursor + 10 <= bottom) {
			ctx.drawText(this.textRenderer, Text.literal("§8shift = ±10"), x + 5, bottom - 9, 0xFFFFFFFF, true);
		}
	}

	private String currentDirection(int index) {
		String value = selected.param(index).trim().toLowerCase(java.util.Locale.ROOT);
		for (String option : BlockScript.DIRECTIONS) {
			if (option.equals(value)) return option;
		}
		return BlockScript.DIRECTIONS[0];
	}

	/**
	 * Positions a shared field over a parameter row and keeps it in sync both
	 * ways: typing writes into the block, everything else (steppers, a new
	 * selection) writes back into the field.
	 */
	private void placeField(DrawContext ctx, int mouseX, int mouseY, float delta,
							TextFieldWidget field, int index, int x, int y, int w) {
		field.setX(x);
		field.setY(y);
		field.setWidth(Math.max(20, w));
		if (field.isFocused()) {
			selected.setParam(index, field.getText());
		} else if (!field.getText().equals(selected.param(index))) {
			field.setText(selected.param(index));
		}
		field.render(ctx, mouseX, mouseY, delta);
	}

	private void step(int index, int direction, BlockScript.ParamType type) {
		if (selected == null) return;
		int amount = shiftDown() ? 10 : 1;
		int value = parseInt(selected.param(index)) + direction * amount;
		if (type == BlockScript.ParamType.SLOT) {
			value = Math.max(0, Math.min(53, value));
		} else {
			value = Math.max(-9999, Math.min(9999, value));
		}
		String text = String.valueOf(value);
		selected.setParam(index, text);
		if (index < MAX_PARAM_FIELDS && paramFields[index] != null) paramFields[index].setText(text);
	}

	private static int parseInt(String value) {
		try {
			return Integer.parseInt(value.trim());
		} catch (Exception ignored) {
			return 0;
		}
	}

	// ---------------------------------------------------------------- code preview

	private void renderCode(DrawContext ctx, int x, int y, int w, int h, List<String> warnings) {
		ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, FRAME_DARK);
		ctx.fill(x, y, x + w, y + h, 0xF00E0906);
		ctx.fill(x, y, x + w, y + 12, 0x40FFFFFF);
		ctx.drawText(this.textRenderer, Text.literal("§7COMPILED SCRIPT §8(read-only)"), x + 5, y + 2, 0xFFFFFFFF, true);

		List<String> display = new ArrayList<>();
		for (String warning : warnings) display.add(warning);
		if (!warnings.isEmpty()) display.add("");

		List<String> lines = BlockScript.compile(doc.blocks);
		if (lines.isEmpty()) {
			display.add("§8(nothing to compile - the script is empty)");
		} else {
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				String colour = line.trim().startsWith("#") ? "§8" : "§f";
				display.add("§8" + pad(i + 1) + " " + colour + line);
			}
		}

		int top = y + 14;
		int viewH = h - 16;
		int contentH = display.size() * 10;
		int maxScroll = Math.max(0, contentH - viewH);
		codeScroll = Math.max(0, Math.min(maxScroll, codeScroll - pollScroll() * 14));

		ctx.enableScissor(x, top, x + w, top + viewH);
		int ly = top - codeScroll;
		for (String line : display) {
			if (ly + 10 >= top && ly <= top + viewH) {
				ctx.drawText(this.textRenderer, Text.literal(fit(line, w - 10)), x + 5, ly, 0xFFFFFFFF, true);
			}
			ly += 10;
		}
		ctx.disableScissor();

		if (maxScroll > 0) {
			int barH = Math.max(12, viewH * viewH / Math.max(1, contentH));
			int barY = top + (viewH - barH) * codeScroll / maxScroll;
			ctx.fill(x + w - 3, top, x + w - 1, top + viewH, 0x30FFFFFF);
			ctx.fill(x + w - 3, barY, x + w - 1, barY + barH, 0x90FFFFFF);
		}
	}

	private static String pad(int number) {
		String text = String.valueOf(number);
		while (text.length() < 3) text = " " + text;
		return text;
	}

	// ------------------------------------------------------------------
	// tree edits
	// ------------------------------------------------------------------

	/** Where a block sits: its owner list, its index, and where that owner hangs. */
	private static final class Loc {
		List<BlockScript.Block> list;
		int index;
		BlockScript.Block parent;
		/** true when {@link #list} is the parent's else-branch. */
		boolean inElse;
		List<BlockScript.Block> parentList;
		int parentIndex;
	}

	private Loc locate(BlockScript.Block target) {
		if (doc == null || target == null) return null;
		return find(doc.blocks, target, null, false, null, -1);
	}

	private Loc find(List<BlockScript.Block> list, BlockScript.Block target, BlockScript.Block parent,
					 boolean inElse, List<BlockScript.Block> parentList, int parentIndex) {
		for (int i = 0; i < list.size(); i++) {
			BlockScript.Block block = list.get(i);
			if (block == target) {
				Loc loc = new Loc();
				loc.list = list;
				loc.index = i;
				loc.parent = parent;
				loc.inElse = inElse;
				loc.parentList = parentList;
				loc.parentIndex = parentIndex;
				return loc;
			}
			Loc inner = find(block.children, target, block, false, list, i);
			if (inner != null) return inner;
			inner = find(block.elseChildren, target, block, true, list, i);
			if (inner != null) return inner;
		}
		return null;
	}

	private static boolean isContainer(BlockScript.Block block) {
		BlockScript.BlockDef def = BlockScript.def(block.type);
		return def != null && def.container;
	}

	private static boolean hasElse(BlockScript.Block block) {
		BlockScript.BlockDef def = BlockScript.def(block.type);
		return def != null && def.hasElse;
	}

	/**
	 * Moves the selection one printed row up or down, crossing nesting
	 * boundaries the way the canvas reads: past a C-block goes into it, off the
	 * end of a body climbs back out. The block is removed and re-inserted once,
	 * so nothing is ever duplicated or dropped, and the ends are hard stops.
	 */
	private void moveSelected(boolean up) {
		if (doc == null || selected == null) {
			setStatus("§cPick a block first");
			return;
		}
		Loc loc = locate(selected);
		if (loc == null) {
			selected = null;
			return;
		}
		BlockScript.Block block = selected;

		if (up) {
			if (loc.index > 0) {
				BlockScript.Block previous = loc.list.get(loc.index - 1);
				loc.list.remove(loc.index);
				if (isContainer(previous)) {
					// slide into the bottom of the block above
					(hasElse(previous) ? previous.elseChildren : previous.children).add(block);
				} else {
					loc.list.add(loc.index - 1, block);
				}
			} else if (loc.parent == null) {
				setStatus("§7Already at the top");
				return;
			} else if (loc.inElse) {
				loc.list.remove(0);
				loc.parent.children.add(block); // cross the else divider
			} else {
				loc.list.remove(0);
				loc.parentList.add(loc.parentIndex, block); // climb out above the container
			}
		} else {
			if (loc.index < loc.list.size() - 1) {
				BlockScript.Block next = loc.list.get(loc.index + 1);
				loc.list.remove(loc.index);
				if (isContainer(next)) {
					next.children.add(0, block);
				} else {
					loc.list.add(loc.index + 1, block);
				}
			} else if (loc.parent == null) {
				setStatus("§7Already at the bottom");
				return;
			} else if (!loc.inElse && hasElse(loc.parent)) {
				loc.list.remove(loc.index);
				loc.parent.elseChildren.add(0, block);
			} else {
				loc.list.remove(loc.index);
				loc.parentList.add(loc.parentIndex + 1, block); // climb out below the container
			}
		}
		selected = block;
		boundBlock = null;
	}

	private void deleteSelected() {
		if (doc == null || selected == null) {
			setStatus("§cPick a block first");
			return;
		}
		Loc loc = locate(selected);
		if (loc == null) {
			selected = null;
			return;
		}
		String label = stripCodes(BlockScript.labelOf(selected));
		loc.list.remove(loc.index);

		BlockScript.Block next = null;
		if (!loc.list.isEmpty()) {
			next = loc.list.get(Math.min(loc.index, loc.list.size() - 1));
		} else if (loc.parent != null) {
			next = loc.parent;
		} else if (!doc.blocks.isEmpty()) {
			next = doc.blocks.get(0);
		}
		selected = next;
		boundBlock = null;
		setStatus("§7Deleted §f" + fit(label, 240));
	}

	private void duplicateSelected() {
		if (doc == null || selected == null) {
			setStatus("§cPick a block first");
			return;
		}
		Loc loc = locate(selected);
		if (loc == null) {
			selected = null;
			return;
		}
		BlockScript.Block copy = copyOf(selected);
		loc.list.add(loc.index + 1, copy);
		selected = copy;
		boundBlock = null;
		setStatus("§aDuplicated");
	}

	private static BlockScript.Block copyOf(BlockScript.Block source) {
		BlockScript.Block copy = new BlockScript.Block(source.type);
		copy.params.addAll(source.params);
		for (BlockScript.Block child : source.children) copy.children.add(copyOf(child));
		for (BlockScript.Block child : source.elseChildren) copy.elseChildren.add(copyOf(child));
		return copy;
	}

	/** Callback handed to {@link BlockPaletteScreen}. */
	private void addBlock(BlockScript.Block block) {
		if (doc == null || block == null) return;
		String where;
		if (selected != null && isContainer(selected)) {
			// a C-block is selected: the natural place is inside it
			selected.children.add(block);
			where = " §8(inside)";
		} else if (selected != null) {
			Loc loc = locate(selected);
			if (loc != null) {
				loc.list.add(loc.index + 1, block);
			} else {
				doc.blocks.add(block);
			}
			where = "";
		} else {
			doc.blocks.add(block);
			where = "";
		}
		selected = block;
		boundBlock = null;
		showCode = false;
		setStatus("§aAdded §f" + stripCodes(BlockScript.labelOf(block)) + where);
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private static int countBlocks(List<BlockScript.Block> list) {
		int total = 0;
		for (BlockScript.Block block : list) {
			total += 1 + countBlocks(block.children) + countBlocks(block.elseChildren);
		}
		return total;
	}

	private static String ago(long time) {
		long diff = System.currentTimeMillis() - time;
		if (diff < 60_000L) return "just now";
		if (diff < 3_600_000L) return (diff / 60_000L) + "m ago";
		if (diff < 86_400_000L) return (diff / 3_600_000L) + "h ago";
		return (diff / 86_400_000L) + "d ago";
	}

	static String stripCodes(String text) {
		return text == null ? "" : text.replaceAll("§[0-9a-fk-or]", "");
	}

	/** Trims to a pixel width, never leaving a dangling section sign behind. */
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

	private List<String> wrap(String text, int maxWidth, int maxLines) {
		List<String> out = new ArrayList<>();
		String rest = text == null ? "" : text;
		while (!rest.isEmpty() && out.size() < maxLines) {
			int cut = rest.length();
			while (cut > 1 && this.textRenderer.getWidth(rest.substring(0, cut)) > maxWidth) cut--;
			if (cut > 1 && rest.charAt(cut - 1) == '§') cut--;
			out.add(rest.substring(0, cut));
			rest = rest.substring(cut);
		}
		if (!rest.isEmpty() && !out.isEmpty()) {
			int last = out.size() - 1;
			out.set(last, fit(out.get(last), maxWidth - 6) + "…");
		}
		return out;
	}

	@Override
	public void close() {
		if (doc != null) doc.updated = System.currentTimeMillis();
		BlockScript.saveAll(docs);
		this.client.setScreen(parent);
	}
}
