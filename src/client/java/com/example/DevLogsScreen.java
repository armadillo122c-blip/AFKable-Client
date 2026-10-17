package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;

public class DevLogsScreen extends FarmScreen {

	/** note prefixes: "!" fix, "+" new, "*" ui/tweak */
	public record LogEntry(String version, String date, String[] notes) {
	}

	private static final List<LogEntry> ENTRIES = List.of(
			new LogEntry("3.20.2", "Oct 17", new String[]{
					"+AFKable Client logo + clean AFKableClient.jar name",
					"+GitHub release updater, SHA-256 verified",
					"+Factory NPC damage sorting + badge choices"}),
			new LogEntry("3.20.1", "Aug 7", new String[]{
					"!Fixed crash on 1.21.11 (shift key API moved)",
					"!Audited every MC call against 1.21.11 - all clear"}),
			new LogEntry("3.20.0", "Aug 7", new String[]{
					"+SCRIPTING 2.0: the visual block editor is live",
					"+Drag-free canvas, palette, inspector + code view",
					"+GUI clicking, slot detection + GUI events in blocks",
					"+Big scripting API: events, conditions, variables, math",
					"+Live Activity log screen on the main menu",
					"!Chat + GUI event blocks now really run",
					"*Block Scripts button on the dev panel"}),
			new LogEntry("3.19.0", "Aug 7", new String[]{
					"!HUD resize no longer misaligns panels",
					"!Ore Forge Calc shows in the HUD Editor",
					"!Stock notifier: no duplicates, no missing textures",
					"!Fixed the ?/s display",
					"+HUD gradients: <gradient:#FFD66A:#FFE49D:#FFD66A>",
					"+Live Activity widget, draggable in the HUD Editor",
					"+Live Activity strip inside the Stock Notifier",
					"+Scripting 2.0: block model, compiler + runtime",
					"+Mob culling pops every 0.2s until finished",
					"+Auto Dominion reads 1,000/10,000 from the lore",
					"+Bingo + Color themes, better accents",
					"+Gear + mob stats use exact lore colours",
					"+Farm shows real mob heads, not rarity tiles",
					"+Player heads via NameMC textures",
					"+VS ALL ONLINE, compare up to 6 players",
					"+My Stats page replaces Versions",
					"+Two new GUI layouts: Compact + Sidebar",
					"*Cleaner version list + numbering",
					"*Removed the Boosts button"}),
			new LogEntry("3.18.1", "Aug 7", new String[]{
					"!Fixed crash opening the VS compare screen"}),
			new LogEntry("3.18.0", "Aug 7", new String[]{
					"!Chat game no longer learns the PRIZE as the answer",
					"!Cull mobs now paced, one every 0.2s",
					"!Forge calc can finally be moved in the HUD editor",
					"!Text boxes keep what you typed when you resize",
					"!Stock notifier: no more twin rows, more junk filtered",
					"+Trivia, Head Scratcher and Brain Blast supported",
					"+Auto dominion reads the X/Y bar and skips the rest",
					"+AFKABLE, Bingo and Color themes",
					"+Live activity feed, movable in the HUD editor",
					"+Gradient tags for labels",
					"+GUI layouts: Classic, Compact, Sidebar",
					"*MY STATS replaces VERSIONS on the menu",
					"*Removed the BUFFS button"}),
			new LogEntry("3.17.0", "Aug 6", new String[]{
					"!Cull mobs works - big numbers broke the parser",
					"!Forge now really uses the nerfed ore values",
					"!Stock timer could freeze the game, and misread",
					"!Claude HUD no longer clips its own text",
					"+AFKABLE theme, straight off the website",
					"+Stats: compare two players side by side",
					"+Stats: gear, armour view, pets, farm, garden, progress",
					"*Stats GUI rebuilt with tabs",
					"*Removed auto messages"}),
			new LogEntry("3.16.0", "Aug 6", new String[]{
					"!Mod compiles again - 3.15.0 never actually built",
					"!Auto dominion works: walks every rarity tab",
					"!Theme no longer desyncs on the GUI buttons",
					"!Dev logs sort properly, LATEST is really latest",
					"+SEASON OG 2: ore values learned from the lore",
					"+Player stats from afkable.com, in game",
					"+Auto messages on a timer, with an on/off toggle",
					"+/farm: bulk-remove your worst or best mobs",
					"+Chat game saves answers and can send them",
					"*Quick Buttons page redone - search + scrolls right",
					"*Removed the PV bar, stock info and FPS/ping HUDs",
					"*Dev panel restyled"}),
			new LogEntry("3.13.0", "Jan 24", new String[]{
					"+Discord boost alerts via webhook",
					"+Pings @boost, and @boost5x on 5x+"}),
			new LogEntry("3.15.0", "Jan 25", new String[]{
					"+Auto-dominion + auto-upgrade pickaxe",
					"+Coder + No Life themes",
					"+Stock & seed timers combined into one HUD",
					"+HUD editor shows live previews now",
					"!Fixed HUD scaling + opacity on all panels",
					"!Chat game reads the answer properly",
					"*Auto-sync runs hidden in the background",
					"*Buttons menu redone - drag to reorder",
					"*Removed the eggs HUD"}),
			new LogEntry("3.14.1", "Jan 24", new String[]{
					"!Fixed big FPS drop (ping now cached)",
					"+Chat Game helper - shows the answer, you send it",
					"*Seed timer + FPS off by default"}),
			new LogEntry("3.14.0", "Jan 24", new String[]{
					"+PETS, FORGE, GEAR, SEEDS, GARDEN buttons",
					"+Seed restock timer",
					"+FPS + ping counter (draggable, toggle)",
					"+Neji, Joshplays05 + Cursed themes",
					"*More dev access"}),
			new LogEntry("3.12.0", "Jan 24", new String[]{
					"+PV BAR: vaults 1-15 as a HUD strip",
					"*Buttons removed from the HUD editor"}),
			new LogEntry("3.11.0", "Jan 23", new String[]{
					"!Farm stock timer detects the new font",
					"+TRASH page: rules + per-slot control",
					"+Pick exactly which slots can be deleted",
					"+Angry Birds, Minecraft, Claude + Mining themes",
					"*Panels slide in, buttons animate on hover"}),
			new LogEntry("3.10.0", "Jan 23", new String[]{
					"*Earnings HUD removed"}),
			new LogEntry("3.9.0", "Jan 23", new String[]{
					"+THEME BUILDER: make your own with hex codes",
					"+Publish themes + browse what others made",
					"+BUFFS button + live buff countdowns (1h/1d)",
					"+Galaxy theme",
					"+5 new ores up to Celestial Core (1Mx)",
					"*Stock notifier hides sold-out barriers",
					"*Stock data saves as JSON now"}),
			new LogEntry("3.8.0", "Jan 23", new String[]{
					"!TRASH can never delete a Titanic again (font bug)",
					"+Real-time EARNINGS HUD ($/sec from the sidebar)",
					"+VAULT, GANG, PRESTIGE + EC buttons",
					"+Earth + Blackhole themes (watch the particles)",
					"+VERSIONS lets you grab 3.6.0 / 3.7.0",
					"+Scroll the PV button to change vault number",
					"+Cancel lazy storage by clicking it again",
					"+V in Ore Vault fills EVERY free slot",
					"*SORT starts on ALL, buttons movable again",
					"*Owner badge is now a hammer emoji",
					"*Press-I hint above server GUIs"}),
			new LogEntry("3.7.0", "Jan 23", new String[]{
					"!Egg + stock icon fixes",
					"+BUTTONS page: hide + reorder every button",
					"+Beans + Car themes, per-theme panel styles",
					"*Settings redesigned with tabs + theme grid"}),
			new LogEntry("3.6.0", "Jan 22", new String[]{
					"+Suggestions box, Slime/Poop/Dark/Light",
					"*Everything matches your theme"}),
			new LogEntry("3.5.0", "Jan 22", new String[]{
					"+Pin script GUIs to your HUD",
					"+10 new script commands"}),
			new LogEntry("3.3.0", "Jan 21", new String[]{
					"+KeyBinder hotkeys",
					"*Gang chat catches real messages"}),
			new LogEntry("3.1.0", "Jan 21", new String[]{
					"+Gang Chat GUI, new menu design"}),
			new LogEntry("3.0.0", "Jan 21", new String[]{
					"+Vault Search, smarter Stock Notifier",
					"*Everything got way faster"}),
			new LogEntry("2.0.0", "Jan 19", new String[]{
					"+Stock notifier, Lazy Storage, quick buttons"})
	);

	/**
	 * Newest first, by actual version number. The hand-written list above had 3.13.0
	 * sitting above 3.15.0, so the "(LATEST)" tag landed on the wrong release.
	 */
	public static final List<LogEntry> DEV_LOGS = ENTRIES.stream()
			.sorted((a, b) -> compareVersions(b.version(), a.version()))
			.toList();

	/** Compares dotted version strings numerically, so 3.9.0 sorts below 3.15.0. */
	static int compareVersions(String a, String b) {
		String[] left = a.split("\\.");
		String[] right = b.split("\\.");
		for (int i = 0; i < Math.max(left.length, right.length); i++) {
			int l = i < left.length ? parse(left[i]) : 0;
			int r = i < right.length ? parse(right[i]) : 0;
			if (l != r) return Integer.compare(l, r);
		}
		return 0;
	}

	private static int parse(String part) {
		try {
			return Integer.parseInt(part.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** The version shown as current, used by the menu + watermark. */
	public static String latestVersion() {
		return DEV_LOGS.isEmpty() ? "" : DEV_LOGS.get(0).version();
	}

	private final Screen parent;
	private int scroll = 0;

	public DevLogsScreen(Screen parent) {
		super(Text.literal("Dev Logs"));
		this.parent = parent;
	}

	private static int entryHeight(LogEntry entry) {
		return 14 + entry.notes().length * 10 + 4;
	}

	private String styleNote(String note) {
		if (note.startsWith("!")) return "§c▪ §7" + note.substring(1);
		if (note.startsWith("+")) return "§a▪ §7" + note.substring(1);
		if (note.startsWith("*")) return "§e▪ §7" + note.substring(1);
		return "§8▪ §7" + note;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = 290;
		int panelH = 236;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - panelH / 2;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("DEV LOGS", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo), px + panelW / 2, py + 7, CREAM);
		ctx.drawText(this.textRenderer, Text.literal("§c▪§8fix §a▪§8new §e▪§8tweak"), px + 12, py + 26, 0xFFFFFFFF, true);

		int listX = px + 12;
		int listY = py + 37;
		int listW = panelW - 34;
		int listH = panelH - 67;

		// pixel-based scrolling so the last entry can always be reached in full
		int contentH = 0;
		for (LogEntry entry : DEV_LOGS) {
			contentH += entryHeight(entry) + 3;
		}
		int maxScroll = Math.max(0, contentH - listH);
		scroll = Math.max(0, Math.min(maxScroll, scroll - pollScroll() * 14));

		ctx.enableScissor(listX, listY, listX + listW + 4, listY + listH);
		int entryY = listY - scroll;
		for (int i = 0; i < DEV_LOGS.size(); i++) {
			LogEntry entry = DEV_LOGS.get(i);
			int entryH = entryHeight(entry);
			if (entryY + entryH >= listY && entryY <= listY + listH) {
				ctx.fill(listX, entryY, listX + listW, entryY + entryH - 2, 0x28FFFFFF);
				ctx.fill(listX, entryY, listX + 2, entryY + entryH - 2, ForgeCalcTheme.current().accent);
				String tag = i == 0 ? " §a(LATEST)" : "";
				ctx.drawText(this.textRenderer,
						Text.literal("§f§lv" + entry.version() + tag + "  §8" + entry.date()),
						listX + 6, entryY + 3, 0xFFFFFFFF, true);
				int noteY = entryY + 14;
				for (String note : entry.notes()) {
					ctx.drawText(this.textRenderer, Text.literal(styleNote(note)), listX + 8, noteY, 0xFFFFFFFF, true);
					noteY += 10;
				}
			}
			entryY += entryH + 3;
		}
		ctx.disableScissor();

		int trackX = px + panelW - 9;
		ctx.fill(trackX, listY, trackX + 3, listY + listH, 0x50000000);
		if (maxScroll > 0) {
			int thumbH = Math.max(14, listH * listH / Math.max(1, contentH));
			int thumbY = listY + (listH - thumbH) * scroll / maxScroll;
			ctx.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, GREEN);
		}

		int half = (panelW - 30) / 2;
		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + panelH - 24, half, 18, "§f§lBACK", null)) {
			close();
			return;
		}
		// versions moved here off the main menu, next to the changelog it belongs with
		if (drawBigButton(ctx, mouseX, mouseY, px + 18 + half, py + panelH - 24, half, 18, "§b§lVERSIONS", null)) {
			this.client.setScreen(new VersionsScreen(this));
		}
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}
}
