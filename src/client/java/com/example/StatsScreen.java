package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Player stats pulled live from afkable.com. Type a name, hit LOOK UP, then flip between the
 * seasons the API returns (SEASON OG 2 is the live one) and the six category tabs.
 *
 * <p>The screen never blocks: {@link PlayerStatsNet} owns the daemon threads and this class only
 * polls slot A each frame. Sections the server did not send - factory has no gear, farm or garden -
 * grey their tab out instead of drawing an empty body.
 */
public class StatsScreen extends FarmScreen {

	private static final int PANEL_W = 340;
	private static final int PANEL_H = 250;
	private static final int BODY_W = 320;
	private static final int BODY_H = 148;

	private static final int TAB_STATS = 0;
	private static final int TAB_GEAR = 1;
	private static final int TAB_PETS = 2;
	private static final int TAB_FARM = 3;
	private static final int TAB_GARDEN = 4;
	private static final int TAB_PROG = 5;
	private static final String[] TAB_NAMES = {"STATS", "GEAR", "PETS", "FARM", "GARDEN", "PROG"};

	private static final String[] PET_SORTS = {"MULTI", "RARITY", "NAME"};

	private final Screen parent;
	private TextFieldWidget nameField;

	private int serverTab = 0;
	private int catTab = TAB_STATS;
	private final int[] scroll = new int[TAB_NAMES.length];
	private int petSort = 0;

	/** Real Text, not strings: item names, lore, pet names and mob names keep the server's colours. */
	private List<Text> pendingTooltip;
	private int wheel;

	private List<StatsModels.PetInfo> petCache;
	private int petCacheSort = -1;
	private StatsModels.ServerProfile petCacheOwner;

	private static ItemStack headStack;

	public StatsScreen(Screen parent) {
		super(Text.literal("Stats"));
		this.parent = parent;
	}

	private static ItemStack head() {
		if (headStack == null) headStack = new ItemStack(Items.PLAYER_HEAD);
		return headStack;
	}

	@Override
	protected void init() {
		int px = this.width / 2 - PANEL_W / 2;
		int py = this.height / 2 - PANEL_H / 2;
		String previous = nameField != null ? nameField.getText() : "";
		nameField = new TextFieldWidget(this.textRenderer, px + 10, py + 25, 140, 14, Text.literal(""));
		nameField.setMaxLength(16);
		if (!previous.isEmpty()) {
			nameField.setText(previous);
		} else {
			String slotQuery = PlayerStatsNet.slot(PlayerStatsNet.SLOT_A).query();
			if (slotQuery != null && !slotQuery.isEmpty()) {
				nameField.setText(slotQuery);
			} else if (this.client != null && this.client.player != null) {
				nameField.setText(this.client.player.getName().getString());
			}
		}
		addSelectableChild(nameField);
		setInitialFocus(nameField);
	}

	private void lookup() {
		String name = nameField != null ? nameField.getText().trim() : "";
		if (name.isEmpty()) return;
		serverTab = 0;
		java.util.Arrays.fill(scroll, 0);
		PlayerStatsNet.fetch(name, PlayerStatsNet.SLOT_A);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER && nameField != null && nameField.isFocused()) {
			lookup();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	// =============================================================================== render

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();
		pendingTooltip = null;
		wheel = pollScroll();

		int px = this.width / 2 - PANEL_W / 2;
		int py = this.height / 2 - PANEL_H / 2;
		int bx = px + 10;
		int by = py + 74;

		drawWoodPanel(ctx, px, py, PANEL_W, PANEL_H);

		PlayerStatsNet.Slot slot = PlayerStatsNet.slot(PlayerStatsNet.SLOT_A);
		// only read the profile once the slot has actually settled, so a refresh in flight
		// cannot leave the header advertising the previous player's timestamp
		StatsModels.ServerProfile server = slot.ready() ? currentServer(slot) : null;

		// ---- header
		ctx.fill(px, py, px + PANEL_W, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + PANEL_W, py + 23, FRAME_DARK);
		ctx.drawCenteredTextWithShadow(this.textRenderer,
				TextFx.gradient("PLAYER STATS", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo),
				px + PANEL_W / 2, py + 7, CREAM);
		if (server != null) {
			String stamp = StatsModels.ago(server.updatedAt());
			if (!stamp.isEmpty()) {
				StatsModels.textRight(ctx, this.textRenderer, "§8updated " + stamp, px + PANEL_W - 6, py + 7);
			}
		}

		// ---- query row
		if (nameField != null) {
			nameField.setX(px + 10);
			nameField.setY(py + 25);
			nameField.setWidth(140);
			nameField.render(ctx, mouseX, mouseY, delta);
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 156, py + 25, 58, 14, "§a§lLOOK UP", null)) {
			lookup();
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 218, py + 25, 44, 14, "§e§lVS", null)) {
			String seed = nameField != null ? nameField.getText().trim() : "";
			if (this.client != null) this.client.setScreen(new StatsCompareScreen(this, seed));
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 266, py + 25, 18, 14, "§f⟳", null)) {
			PlayerStatsNet.refresh(PlayerStatsNet.SLOT_A);
		}

		// ---- body
		switch (slot.state()) {
			case PlayerStatsNet.IDLE -> {
				centered(ctx, px, by + 46, "§7Type a username and press §aLOOK UP");
				centered(ctx, px, by + 60, "§8data from afkable.com");
			}
			case PlayerStatsNet.LOADING -> {
				String dots = ".".repeat((int) ((System.currentTimeMillis() / 300) % 4));
				centered(ctx, px, by + 52, "§7Loading §f" + slot.query() + "§7" + dots);
			}
			case PlayerStatsNet.FAILED -> {
				centered(ctx, px, by + 46, "§cCould not load §f" + slot.query());
				centered(ctx, px, by + 60, "§8" + slot.error());
			}
			default -> {
				if (server == null) {
					centered(ctx, px, by + 52, "§8No visible data for this player");
				} else {
					drawServerTabs(ctx, mouseX, mouseY, px, py, slot);
					drawCategoryTabs(ctx, mouseX, mouseY, px, py, server);
					drawBody(ctx, mouseX, mouseY, bx, by, slot, server);
				}
			}
		}

		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + 226, 316, 18, "§f§lBACK", null)) {
			close();
		}

		if (pendingTooltip != null && !pendingTooltip.isEmpty()) {
			StatsModels.richTooltip(ctx, this.textRenderer, mouseX, mouseY,
					this.width, this.height, pendingTooltip);
		}
	}

	private void centered(DrawContext ctx, int px, int y, String text) {
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(text), px + PANEL_W / 2, y, 0xFFFFFFFF);
	}

	private StatsModels.ServerProfile currentServer(PlayerStatsNet.Slot slot) {
		List<StatsModels.ServerProfile> servers = slot.servers();
		if (servers.isEmpty()) return null;
		if (serverTab < 0 || serverTab >= servers.size()) serverTab = 0;
		return servers.get(serverTab);
	}

	private void drawServerTabs(DrawContext ctx, int mouseX, int mouseY, int px, int py, PlayerStatsNet.Slot slot) {
		List<StatsModels.ServerProfile> servers = slot.servers();
		int n = Math.max(1, servers.size());
		int tabW = BODY_W / n;
		for (int i = 0; i < servers.size(); i++) {
			StatsModels.ServerProfile s = servers.get(i);
			int tx = px + 10 + i * tabW;
			boolean active = i == serverTab;
			boolean hovered = hover(mouseX, mouseY, tx, py + 42, tabW - 2, 14);
			ctx.fill(tx, py + 42, tx + tabW - 2, py + 56, active ? GREEN_DARK : (hovered ? 0x40FFFFFF : 0x30000000));
			if (active) ctx.fill(tx, py + 54, tx + tabW - 2, py + 56, GREEN);
			String label = s.label();
			if (this.textRenderer.getWidth(label) > tabW - 8) label = s.shortLabel();
			ctx.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal((active ? "§f§l" : "§7") + label), tx + (tabW - 2) / 2, py + 45, 0xFFFFFFFF);
			if (hovered && mouseClickedThisFrame) {
				mouseClickedThisFrame = false;
				playClick(this.client);
				serverTab = i;
				java.util.Arrays.fill(scroll, 0);
			}
		}
	}

	private boolean available(StatsModels.ServerProfile s, int tab) {
		return switch (tab) {
			case TAB_GEAR -> s.hasGear();
			case TAB_PETS -> s.has("pets") && !s.pets().isEmpty();
			case TAB_FARM -> s.has("farmPlot") && s.farm() != null;
			case TAB_GARDEN -> s.has("garden") && s.garden() != null;
			case TAB_PROG -> !s.progRows().isEmpty();
			default -> true;
		};
	}

	private void drawCategoryTabs(DrawContext ctx, int mouseX, int mouseY, int px, int py, StatsModels.ServerProfile s) {
		if (!available(s, catTab)) {
			for (int i = 0; i < TAB_NAMES.length; i++) {
				if (available(s, i)) {
					catTab = i;
					break;
				}
			}
		}
		int tabW = 53;
		for (int i = 0; i < TAB_NAMES.length; i++) {
			int tx = px + 10 + i * tabW;
			boolean on = available(s, i);
			boolean active = i == catTab;
			boolean hovered = on && hover(mouseX, mouseY, tx, py + 58, tabW - 1, 13);
			ctx.fill(tx, py + 58, tx + tabW - 1, py + 71, active ? GREEN_DARK : (hovered ? 0x38FFFFFF : 0x28000000));
			if (active) ctx.fill(tx, py + 69, tx + tabW - 1, py + 71, GREEN);
			String colour = !on ? "§8" : (active ? "§f§l" : "§7");
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(colour + TAB_NAMES[i]),
					tx + (tabW - 1) / 2, py + 61, 0xFFFFFFFF);
			if (hovered && mouseClickedThisFrame) {
				mouseClickedThisFrame = false;
				playClick(this.client);
				catTab = i;
			}
		}
	}

	private void drawBody(DrawContext ctx, int mouseX, int mouseY, int bx, int by,
	                      PlayerStatsNet.Slot slot, StatsModels.ServerProfile s) {
		switch (catTab) {
			case TAB_GEAR -> drawGear(ctx, mouseX, mouseY, bx, by, s);
			case TAB_PETS -> drawPets(ctx, mouseX, mouseY, bx, by, s);
			case TAB_FARM -> drawFarm(ctx, mouseX, mouseY, bx, by, s);
			case TAB_GARDEN -> drawGarden(ctx, mouseX, mouseY, bx, by, s);
			case TAB_PROG -> drawProg(ctx, mouseX, mouseY, bx, by, s);
			default -> drawOverview(ctx, mouseX, mouseY, bx, by, slot, s);
		}
	}

	private void emptyBody(DrawContext ctx, int bx, int by, String message) {
		ctx.fill(bx, by, bx + BODY_W, by + BODY_H, 0x20000000);
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(message),
				bx + BODY_W / 2, by + BODY_H / 2 - 4, 0xFFFFFFFF);
	}

	// =============================================================================== STATS

	private static final String[][] CARD_KEYS = {
			{"Balance", "economy", "balance"},
			{"Gems", "economy", "gems"},
			{"Scales", "economy", "scales"},
			{"Tokens", "economy", "tokens"},
			{"@multi", "", ""},
			{"Prestige", "progression", "prestigeLabel"},
			{"Playtime", "time", "playtime"},
	};

	private void drawOverview(DrawContext ctx, int mouseX, int mouseY, int bx, int by,
	                          PlayerStatsNet.Slot slot, StatsModels.ServerProfile s) {
		// identity strip
		ctx.fill(bx, by, bx + BODY_W, by + 22, 0x30000000);
		ctx.fill(bx, by, bx + 2, by + 22, ForgeCalcTheme.current().accent);
		// the looked-up player's real head, falling back to the generic skull
		if (!HeadTextures.drawPlayerHead(ctx, slot.playerName(), bx + 3, by + 3, 16)) {
			ctx.drawItem(head(), bx + 3, by + 3);
		}
		StatsModels.text(ctx, this.textRenderer, "§f§l" + slot.playerName(), bx + 24, by + 3);
		String rank = s.rank() == null || s.rank().isBlank() ? "" : "§e" + s.rank().toUpperCase(Locale.ROOT) + " §8• ";
		String online = s.visible("online")
				? (s.online() ? "§a● ONLINE" : "§8● offline")
				: "§8● hidden";
		StatsModels.text(ctx, this.textRenderer, rank + online, bx + 24, by + 13);

		// stat cards
		List<String[]> cards = new ArrayList<>();
		for (String[] candidate : CARD_KEYS) {
			if (cards.size() >= 6) break;
			if (candidate[0].equals("@multi")) {
				String sell = s.stat("multipliers", "sell");
				if (s.has("multipliers") && !sell.isEmpty()) {
					cards.add(new String[]{"Sell Multi", StatsModels.clean(sell)});
				} else {
					String money = s.stat("multipliers", "money");
					if (s.has("multipliers") && !money.isEmpty()) {
						cards.add(new String[]{"Money Multi", StatsModels.clean(money)});
					}
				}
				continue;
			}
			if (!s.has(candidate[1])) continue;
			String value = s.stat(candidate[1], candidate[2]);
			if (value.isEmpty() && candidate[0].equals("Prestige")) value = s.stat("progression", "prestige");
			if (value.isEmpty()) continue;
			cards.add(new String[]{candidate[0], StatsModels.clean(value)});
		}
		int[] accents = {ForgeCalcTheme.current().accent, GREEN, 0xFFFFC941, 0xFF4FE3E0, 0xFFFF6FD8, 0xFF9B4DFF};
		for (int i = 0; i < cards.size() && i < 6; i++) {
			int cx = bx + (i % 3) * 108;
			int cy = by + 26 + (i / 3) * 34;
			StatsModels.statCard(ctx, this.textRenderer, cx, cy, 104, 30,
					cards.get(i)[0], cards.get(i)[1], accents[i % accents.length]);
		}
		if (cards.isEmpty()) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8no economy data shared"),
					bx + BODY_W / 2, by + 42, 0xFFFFFFFF);
		}

		// cookie bar
		StatsModels.Cookie cookie = s.cookie();
		if (cookie != null && s.has("cookie")) {
			int col = StatsModels.parseHtmlColor(cookie.color(), 0xFFAAAAAA);
			ctx.fill(bx, by + 96, bx + BODY_W, by + 112, (col & 0xFFFFFF) | 0x50000000);
			ctx.fill(bx, by + 96, bx + BODY_W, by + 97, col);
			ctx.fill(bx, by + 111, bx + BODY_W, by + 112, col);
			ctx.fill(bx, by + 96, bx + 1, by + 112, col);
			ctx.fill(bx + BODY_W - 1, by + 96, bx + BODY_W, by + 112, col);
			String line = "§lCOOKIE " + cookie.label() + " §7• x" + cookie.multiplierLabel()
					+ " • " + StatsModels.compact(cookie.chance()) + "% • "
					+ StatsModels.compact(cookie.rolls()) + " rolls";
			StatsModels.text(ctx, this.textRenderer, StatsModels.fit(this.textRenderer, line, BODY_W - 10), bx + 5, by + 100);
		} else {
			ctx.fill(bx, by + 96, bx + BODY_W, by + 112, 0x25000000);
			StatsModels.text(ctx, this.textRenderer, "§8no cookie data on this server", bx + 5, by + 100);
		}

		// two progress bars, auto-picked from whatever this server tracks
		List<Object[]> bars = new ArrayList<>();
		if (s.has("progression")) {
			double mineLevel = StatsModels.parseValue(s.stat("progression", "mineLevel"));
			if (!Double.isNaN(mineLevel)) {
				double xp = StatsModels.parseValue(s.stat("progression", "mineExp"));
				double next = StatsModels.parseValue(s.stat("progression", "mineXpForNext"));
				bars.add(new Object[]{"Mine Lv " + (long) mineLevel, StatsModels.pct(xp, next),
						StatsModels.compact(xp) + " / " + StatsModels.compact(next)});
			}
			double farming = StatsModels.parseValue(s.stat("progression", "farminglv"));
			if (bars.size() < 2 && !Double.isNaN(farming)) {
				double xp = StatsModels.parseValue(s.stat("progression", "farmingxp"));
				double next = StatsModels.parseValue(s.stat("progression", "farmingxpfornext"));
				bars.add(new Object[]{"Farming " + (long) farming, StatsModels.pct(xp, next),
						StatsModels.compact(xp) + " / " + StatsModels.compact(next)});
			}
			double progress = StatsModels.parseValue(s.stat("progression", "prestigeProgress"));
			if (!Double.isNaN(progress)) {
				String label = StatsModels.clean(s.stat("progression", "prestigeLabel"));
				if (label.isEmpty()) label = StatsModels.clean(s.stat("progression", "prestige"));
				bars.add(new Object[]{"Prestige " + label, StatsModels.pct(progress, 100), (long) progress + "%"});
			}
			double fishing = StatsModels.parseValue(s.stat("progression", "fishinglv"));
			if (bars.size() < 2 && !Double.isNaN(fishing)) {
				double xp = StatsModels.parseValue(s.stat("progression", "fishingxp"));
				double next = StatsModels.parseValue(s.stat("progression", "fishingxpfornext"));
				bars.add(new Object[]{"Fishing " + (long) fishing, StatsModels.pct(xp, next),
						StatsModels.compact(xp) + " / " + StatsModels.compact(next)});
			}
		}
		for (int i = 0; i < 2; i++) {
			int barY = by + 116 + i * 16;
			if (i < bars.size()) {
				Object[] bar = bars.get(i);
				StatsModels.progressBar(ctx, this.textRenderer, bx, barY, BODY_W, 12,
						(Double) bar[1], "§f" + bar[0] + " §7" + bar[2], GREEN);
			} else {
				ctx.fill(bx, barY, bx + BODY_W, barY + 12, 0x30000000);
			}
		}
	}

	// =============================================================================== GEAR

	private void drawGear(DrawContext ctx, int mouseX, int mouseY, int bx, int by, StatsModels.ServerProfile s) {
		if (!s.hasGear()) {
			emptyBody(ctx, bx, by, "§8This server does not share gear");
			return;
		}
		StatsModels.ItemInfo[] armor = s.armor();
		int dx = bx + 40;
		int dy = by + 14;

		StatsModels.text(ctx, this.textRenderer, "§e§lARMOUR", bx, by);
		int hoveredPiece = s.has("armor")
				? StatsModels.paperDoll(ctx, dx, dy, armor, mouseX, mouseY)
				: StatsModels.paperDoll(ctx, dx, dy, new StatsModels.ItemInfo[4], mouseX, mouseY);

		// slot column + connector back to the tinted body segment
		int[] slotY = {by + 12, by + 32, by + 52, by + 72};
		int[] connectorX = {dx + 9, dx + 11, dx + 10, dx + 10};
		for (int i = 0; i < 4; i++) {
			StatsModels.ItemInfo piece = s.has("armor") && i < armor.length ? armor[i] : null;
			int sx = bx + 66;
			int sy = slotY[i];
			int col = piece != null ? piece.color() : 0xFF6A6A6A;
			ctx.fill(sx + 1, sy + 8, connectorX[i], sy + 9, (col & 0xFFFFFF) | 0x80000000);
			ctx.fill(sx - 1, sy - 1, sx + 19, sy + 19, FRAME_DARK);
			ctx.fill(sx, sy, sx + 18, sy + 18, piece != null ? (col & 0xFFFFFF) | 0x55000000 : 0x30000000);
			if (piece != null) {
				ctx.drawItem(StatsModels.stackFor(piece.type()), sx + 1, sy + 1);
			}
			boolean hovered = hover(mouseX, mouseY, sx, sy, 18, 18) || hoveredPiece == i;
			if (hovered && piece != null) {
				ctx.fill(sx, sy, sx + 18, sy + 18, 0x33FFFFFF);
				pendingTooltip = StatsModels.itemTooltipRich(piece);
			}
		}

		// offhand
		StatsModels.ItemInfo off = s.has("offhand") ? s.offhand() : null;
		int ox = bx + 66;
		int oy = by + 94;
		StatsModels.text(ctx, this.textRenderer, "§7OFF", bx + 46, oy + 5);
		ctx.fill(ox - 1, oy - 1, ox + 19, oy + 19, FRAME_DARK);
		ctx.fill(ox, oy, ox + 18, oy + 18, off != null ? (off.color() & 0xFFFFFF) | 0x55000000 : 0x30000000);
		if (off != null) {
			ctx.drawItem(StatsModels.stackFor(off.type()), ox + 1, oy + 1);
			if (hover(mouseX, mouseY, ox, oy, 18, 18)) {
				ctx.fill(ox, oy, ox + 18, oy + 18, 0x33FFFFFF);
				pendingTooltip = StatsModels.itemTooltipRich(off);
			}
		}

		String armorMulti = StatsModels.clean(s.stat("progression", "armorMulti"));
		StatsModels.text(ctx, this.textRenderer,
				"§7Armor Multi §f" + (armorMulti.isEmpty() ? "-" : armorMulti), bx, by + 118);
		double total = s.armorLoreTotal();
		StatsModels.text(ctx, this.textRenderer,
				"§7Total §a" + (total > 0 ? StatsModels.compact(total) : "-"), bx, by + 130);

		// ---- hotbar
		StatsModels.text(ctx, this.textRenderer, "§e§lHOTBAR", bx + 124, by);
		StatsModels.ItemInfo[] hotbar = s.has("hotbar") ? s.hotbar() : new StatsModels.ItemInfo[9];
		boolean hotbarEmpty = true;
		for (int i = 0; i < 9; i++) {
			StatsModels.ItemInfo info = i < hotbar.length ? hotbar[i] : null;
			if (info != null) hotbarEmpty = false;
			int sx = bx + 141 + i * 18;
			if (StatsModels.itemSlot(ctx, this.textRenderer, sx, by + 12, info, mouseX, mouseY, false) && info != null) {
				pendingTooltip = StatsModels.itemTooltipRich(info);
			}
		}
		if (hotbarEmpty) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8empty"),
					bx + 141 + 81, by + 17, 0xFFFFFFFF);
		}

		// ---- inventory
		StatsModels.ItemInfo[] inv = s.has("inventory") ? s.inventory() : new StatsModels.ItemInfo[27];
		int filled = 0;
		for (StatsModels.ItemInfo info : inv) if (info != null) filled++;
		StatsModels.text(ctx, this.textRenderer, "§e§lINVENTORY §7" + filled + "/27", bx + 124, by + 36);
		for (int i = 0; i < 27; i++) {
			StatsModels.ItemInfo info = i < inv.length ? inv[i] : null;
			int sx = bx + 141 + (i % 9) * 18;
			int sy = by + 48 + (i / 9) * 18;
			if (StatsModels.itemSlot(ctx, this.textRenderer, sx, sy, info, mouseX, mouseY, filled == 0) && info != null) {
				pendingTooltip = StatsModels.itemTooltipRich(info);
			}
		}
		if (filled == 0) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8empty"),
					bx + 141 + 81, by + 74, 0xFFFFFFFF);
		}
	}

	// =============================================================================== PETS

	/**
	 * Sorting 250+ pets on every frame would be wasteful, so the result is memoised until the
	 * sort mode or the server behind it changes.
	 */
	private List<StatsModels.PetInfo> sortedPets(StatsModels.ServerProfile s) {
		if (petCache != null && petCacheSort == petSort && petCacheOwner == s) return petCache;
		List<StatsModels.PetInfo> pets = new ArrayList<>(s.pets());
		Comparator<StatsModels.PetInfo> order = switch (petSort) {
			case 1 -> Comparator.comparingInt((StatsModels.PetInfo p) -> -StatsModels.rarityRank(p.rarity()))
					.thenComparingInt(p -> -p.tier());
			case 2 -> Comparator.comparing((StatsModels.PetInfo p) -> p.display().toLowerCase(Locale.ROOT));
			default -> Comparator.comparingDouble(
					(StatsModels.PetInfo p) -> -(Double.isNaN(p.multiplier()) ? 0 : p.multiplier()));
		};
		// equipped pets always pin to the top, whatever the sort is
		pets.sort(Comparator.comparingInt((StatsModels.PetInfo p) -> p.equipped() ? 0 : 1).thenComparing(order));
		petCache = pets;
		petCacheSort = petSort;
		petCacheOwner = s;
		return pets;
	}

	private void drawPets(DrawContext ctx, int mouseX, int mouseY, int bx, int by, StatsModels.ServerProfile s) {
		if (!s.has("pets") || s.pets().isEmpty()) {
			emptyBody(ctx, bx, by, "§8No pets shared on this server");
			return;
		}
		List<StatsModels.PetInfo> pets = sortedPets(s);
		StatsModels.text(ctx, this.textRenderer,
				"§f§l" + pets.size() + " PETS §7• " + s.petsEquipped() + "/" + s.petLimit() + " equipped", bx, by + 2);
		if (drawBigButton(ctx, mouseX, mouseY, bx + 232, by, 88, 12,
				"§f" + PET_SORTS[petSort] + (petSort == 0 ? " ▾" : ""), null)) {
			petSort = (petSort + 1) % PET_SORTS.length;
			scroll[TAB_PETS] = 0;
		}

		int listY = by + 16;
		int listH = BODY_H - 16;
		int rowH = 16;
		int contentH = pets.size() * rowH;
		int maxScroll = Math.max(0, contentH - listH);
		scroll[TAB_PETS] = Math.max(0, Math.min(maxScroll, scroll[TAB_PETS] - wheel * 14));
		int offset = scroll[TAB_PETS];

		ctx.enableScissor(bx, listY, bx + BODY_W, listY + listH);
		for (int i = 0; i < pets.size(); i++) {
			int y = listY + i * rowH - offset;
			if (y + rowH < listY || y > listY + listH) continue;
			StatsModels.PetInfo pet = pets.get(i);
			boolean hovered = mouseX >= bx && mouseX < bx + BODY_W && mouseY >= Math.max(listY, y)
					&& mouseY < Math.min(listY + listH, y + rowH);
			if (pet.equipped()) ctx.fill(bx, y, bx + BODY_W, y + rowH - 1, 0x3055FF55);
			else if (i % 2 == 0) ctx.fill(bx, y, bx + BODY_W, y + rowH - 1, 0x18000000);
			if (hovered) ctx.fill(bx, y, bx + BODY_W, y + rowH - 1, 0x22FFFFFF);

			ctx.fill(bx + 2, y + 1, bx + 16, y + 15, pet.color());
			ctx.drawItem(head(), bx + 1, y);

			// the pet's own colours, not a flat grey - the star keeps the equipped marker readable
			int nameX = bx + 20;
			int nameW = 150;
			if (pet.equipped()) {
				StatsModels.text(ctx, this.textRenderer, "§a★", nameX, y + 4);
				nameX += 8;
				nameW -= 8;
			}
			ctx.drawText(this.textRenderer,
					StatsModels.richFit(this.textRenderer, pet.displayRaw(), nameW),
					nameX, y + 4, 0xFFFFFFFF, true);

			int tierX = bx + 176;
			ctx.fill(tierX, y + 3, tierX + 20, y + 13, (pet.color() & 0xFFFFFF) | 0x50000000);
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§fT" + pet.tier()),
					tierX + 10, y + 4, 0xFFFFFFFF);
			StatsModels.text(ctx, this.textRenderer, "§7x" + pet.count(), bx + 202, y + 4);

			String multi = Double.isNaN(pet.multiplier())
					? StatsModels.clean(pet.multiplierRaw())
					: StatsModels.compact(pet.multiplier());
			StatsModels.textRight(ctx, this.textRenderer, "§e" + multi, bx + BODY_W - 4, y + 4);

			if (hovered) {
				pendingTooltip = List.of(
						pet.nameText(),
						StatsModels.colored("Rarity: ", 0xAAAAAA)
								.append(StatsModels.colored(pet.rarity(), pet.color(), true)),
						StatsModels.colored("Tier: ", 0xAAAAAA)
								.append(StatsModels.colored(String.valueOf(pet.tier()), 0xFFFFFF)),
						StatsModels.colored("Owned: ", 0xAAAAAA)
								.append(StatsModels.colored(String.valueOf(pet.count()), 0xFFFFFF)),
						StatsModels.colored("Multiplier: ", 0xAAAAAA)
								.append(StatsModels.colored(multi, 0x55FF55)),
						pet.equipped()
								? StatsModels.colored("Equipped", 0x55FF55)
								: StatsModels.colored("Not equipped", 0x555555));
			}
		}
		ctx.disableScissor();
		StatsModels.scrollbar(ctx, bx + BODY_W, listY, listH, contentH, scroll[TAB_PETS], maxScroll);
	}

	// =============================================================================== FARM

	/** Left half of the FARM tab, in pixels. The grid gets the rest of the body height. */
	private static final int FARM_GRID_W = 150;

	private void drawFarm(DrawContext ctx, int mouseX, int mouseY, int bx, int by, StatsModels.ServerProfile s) {
		StatsModels.FarmPlot plot = s.farm();
		if (plot == null || !s.has("farmPlot")) {
			emptyBody(ctx, bx, by, "§8No farm plot on this server");
			return;
		}

		// Each mob is a real ItemStack (usually a player head) rather than a rarity swatch. The
		// def also carries a textures.minecraft.net URL for the actual skin, but downloading and
		// binding that from the render thread is not something we can do cheaply or without
		// blocking, so the plain head item stands in and the rarity survives as a 1px border.
		//
		// pitch includes the shared 1px border, so neighbouring cells draw onto the same border
		// pixel and the item never overlaps its own frame.
		int pitch = Math.max(5, Math.min(17,
				Math.min((FARM_GRID_W - 1) / plot.width(), (BODY_H - 1) / plot.height())));
		int itemPx = Math.max(4, StatsModels.itemPixels(pitch - 1));
		pitch = itemPx + 1;

		int gx = bx + 2;
		int gy = by + 1;
		int gridW = plot.width() * pitch + 1;
		int gridH = plot.height() * pitch + 1;
		int hoveredIndex = -1;

		ctx.fill(gx - 1, gy - 1, gx + gridW, gy + gridH, 0x40000000);

		for (int i = 0; i < plot.cells().length; i++) {
			int x = gx + (i % plot.width()) * pitch;
			int y = gy + (i / plot.width()) * pitch;
			StatsModels.FarmDef def = plot.cells()[i];
			boolean hovered = mouseX >= x && mouseX < x + pitch && mouseY >= y && mouseY < y + pitch;
			if (hovered) hoveredIndex = i;

			if (def == null) {
				ctx.fill(x, y, x + itemPx, y + itemPx, 0x26000000);
				continue;
			}
			int c = def.color();
			// real mob head from its texture URL; the item icon is the fallback while it
			// downloads, or forever if the fetch fails
			if (!HeadTextures.drawHead(ctx, def.key(), def.texture(), x, y, itemPx)) {
				StatsModels.drawItemScaled(ctx, def.stack(), x, y, itemPx);
			}
			// thin rarity frame sitting in the shared gutter, so it never covers the head
			outline(ctx, x - 1, y - 1, itemPx + 2, itemPx + 2, (c & 0xFFFFFF) | 0xC0000000);
		}

		// hover ring last so it wins over the neighbours' rarity borders
		if (hoveredIndex >= 0) {
			int x = gx + (hoveredIndex % plot.width()) * pitch;
			int y = gy + (hoveredIndex / plot.width()) * pitch;
			outline(ctx, x - 1, y - 1, itemPx + 2, itemPx + 2, CREAM);
			StatsModels.FarmDef def = plot.cells()[hoveredIndex];
			if (def != null) pendingTooltip = StatsModels.farmTooltipRich(def);
		}

		drawFarmSidebar(ctx, mouseX, mouseY, bx + FARM_GRID_W + 4, by, plot);
	}

	private void outline(DrawContext ctx, int x, int y, int w, int h, int color) {
		ctx.fill(x, y, x + w, y + 1, color);
		ctx.fill(x, y + h - 1, x + w, y + h, color);
		ctx.fill(x, y, x + 1, y + h, color);
		ctx.fill(x + w - 1, y, x + w, y + h, color);
	}

	/** Summary, rarity breakdown and top producers - one scissored, pixel-scrolled list. */
	private void drawFarmSidebar(DrawContext ctx, int mouseX, int mouseY, int sx, int by,
	                             StatsModels.FarmPlot plot) {
		int listW = sidebarWidth(sx) - 4; // 4px reserved for the scrollbar
		int rowH = 11;

		// flatten everything into rows so one scroll offset covers the lot
		List<Object[]> rows = new ArrayList<>();
		rows.add(new Object[]{"stat", "§f§l" + plot.placed() + "/" + plot.size() + " filled"});
		rows.add(new Object[]{"stat", "§7Total §a$" + StatsModels.compact(plot.totalProduce()) + "§7/sec"});
		if (plot.placed() == 0) {
			rows.add(new Object[]{"stat", "§8plot is empty"});
		}
		if (!plot.rarityCounts().isEmpty()) {
			rows.add(new Object[]{"head", "RARITY"});
			for (Map.Entry<String, Integer> entry : plot.rarityCounts().entrySet()) {
				rows.add(new Object[]{"rarity", entry.getKey(), entry.getValue()});
			}
		}
		if (!plot.top().isEmpty()) {
			rows.add(new Object[]{"head", "TOP PRODUCE"});
			for (StatsModels.FarmDef def : plot.top()) rows.add(new Object[]{"mob", def});
		}

		int contentH = rows.size() * rowH;
		int maxScroll = Math.max(0, contentH - BODY_H);
		scroll[TAB_FARM] = Math.max(0, Math.min(maxScroll, scroll[TAB_FARM] - wheel * 14));
		int offset = scroll[TAB_FARM];

		ctx.enableScissor(sx, by, sx + listW, by + BODY_H);
		for (int i = 0; i < rows.size(); i++) {
			int y = by + i * rowH - offset;
			if (y + rowH < by || y > by + BODY_H) continue;
			Object[] row = rows.get(i);
			switch ((String) row[0]) {
				case "head" -> StatsModels.text(ctx, this.textRenderer, "§e§l" + row[1], sx, y + 1);
				case "rarity" -> {
					String rarity = (String) row[1];
					ctx.fill(sx, y + 2, sx + 6, y + 8, StatsModels.rarityColor(rarity));
					StatsModels.text(ctx, this.textRenderer, StatsModels.fit(this.textRenderer,
							"§7" + rarity + " §fx" + row[2], listW - 12), sx + 10, y + 1);
				}
				case "mob" -> {
					StatsModels.FarmDef def = (StatsModels.FarmDef) row[1];
					if (!HeadTextures.drawHead(ctx, def.key(), def.texture(), sx, y, 8)) {
						StatsModels.drawItemScaled(ctx, def.stack(), sx, y, 8);
					}
					int nameW = listW - 12 - this.textRenderer.getWidth("$" + StatsModels.compact(def.produce())) - 4;
					ctx.drawText(this.textRenderer,
							StatsModels.richFit(this.textRenderer, def.name(), Math.max(20, nameW)),
							sx + 10, y + 1, 0xFFFFFFFF, true);
					StatsModels.textRight(ctx, this.textRenderer,
							"§7$" + StatsModels.compact(def.produce()), sx + listW, y + 1);
					if (mouseX >= sx && mouseX < sx + listW && mouseY >= Math.max(by, y)
							&& mouseY < Math.min(by + BODY_H, y + rowH)) {
						pendingTooltip = StatsModels.farmTooltipRich(def);
					}
				}
				default -> StatsModels.text(ctx, this.textRenderer,
						StatsModels.fit(this.textRenderer, (String) row[1], listW), sx, y + 1);
			}
		}
		ctx.disableScissor();
		StatsModels.scrollbar(ctx, sx + listW, by, BODY_H, contentH, scroll[TAB_FARM], maxScroll);
	}

	/** How much body width is left from {@code sx} across to the body's right edge. */
	private int sidebarWidth(int sx) {
		int bx = this.width / 2 - PANEL_W / 2 + 10;
		return Math.max(40, bx + BODY_W - sx);
	}

	// =============================================================================== GARDEN

	private void drawGarden(DrawContext ctx, int mouseX, int mouseY, int bx, int by, StatsModels.ServerProfile s) {
		StatsModels.Garden g = s.garden();
		if (g == null || !s.has("garden")) {
			emptyBody(ctx, bx, by, "§8No garden on this server");
			return;
		}
		StatsModels.text(ctx, this.textRenderer,
				"§f§lGARDEN Lv " + g.level() + "§7/" + g.maxLevel(), bx, by);
		int pctValue = (int) Math.round(g.xpProgress() * 100);
		StatsModels.progressBar(ctx, this.textRenderer, bx, by + 14, BODY_W, 12, g.xpProgress(),
				"§f" + StatsModels.compact(g.xp()) + " XP §7• " + pctValue + "%", GREEN);

		int perRow = 10;
		int cell = 16;
		int gap = 2;
		int maxPlots = Math.max(g.plots(), g.maxPlots());
		for (int i = 0; i < maxPlots && i < 60; i++) {
			int cx = bx + (i % perRow) * (cell + gap);
			int cy = by + 32 + (i / perRow) * (cell + gap);
			if (cy + cell > by + 88) break; // never spill into the counters row at BY+92
			boolean unlocked = i < g.plots();
			ctx.fill(cx, cy, cx + cell, cy + cell, unlocked ? GREEN_DARK : 0x40000000);
			ctx.fill(cx, cy, cx + cell, cy + 1, unlocked ? GREEN : 0x30FFFFFF);
			if (!unlocked) {
				ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8x"),
						cx + cell / 2, cy + 4, 0xFFFFFFFF);
			}
		}

		StatsModels.text(ctx, this.textRenderer, "§aREADY " + g.ready(), bx, by + 92);
		StatsModels.text(ctx, this.textRenderer, "§ePLANTED " + g.planted(), bx + 80, by + 92);
		StatsModels.text(ctx, this.textRenderer,
				"§7PLOTS " + g.plots() + "/" + g.maxPlots(), bx + 176, by + 92);

		int listY = by + 108;
		int listH = BODY_H - 108;
		List<String> crops = g.crops();
		if (crops.isEmpty()) {
			ctx.fill(bx, listY, bx + BODY_W, listY + listH, 0x20000000);
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8No crops planted"),
					bx + BODY_W / 2, listY + listH / 2 - 4, 0xFFFFFFFF);
			return;
		}
		int rowH = 11;
		int rows = (crops.size() + 1) / 2;
		int contentH = rows * rowH;
		int maxScroll = Math.max(0, contentH - listH);
		scroll[TAB_GARDEN] = Math.max(0, Math.min(maxScroll, scroll[TAB_GARDEN] - wheel * 14));

		ctx.enableScissor(bx, listY, bx + BODY_W, listY + listH);
		for (int i = 0; i < crops.size(); i++) {
			int cx = bx + (i % 2) * 160;
			int cy = listY + (i / 2) * rowH - scroll[TAB_GARDEN];
			if (cy + rowH < listY || cy > listY + listH) continue;
			StatsModels.text(ctx, this.textRenderer,
					StatsModels.fit(this.textRenderer, "§7• §f" + crops.get(i), 154), cx, cy);
		}
		ctx.disableScissor();
		StatsModels.scrollbar(ctx, bx + BODY_W, listY, listH, contentH, scroll[TAB_GARDEN], maxScroll);
	}

	// =============================================================================== PROG

	private void drawProg(DrawContext ctx, int mouseX, int mouseY, int bx, int by, StatsModels.ServerProfile s) {
		List<StatsModels.ProgRow> rows = s.progRows();
		if (rows.isEmpty()) {
			emptyBody(ctx, bx, by, "§8No progression data shared");
			return;
		}
		int contentH = 0;
		for (StatsModels.ProgRow row : rows) contentH += row.height();
		int maxScroll = Math.max(0, contentH - BODY_H);
		scroll[TAB_PROG] = Math.max(0, Math.min(maxScroll, scroll[TAB_PROG] - wheel * 14));
		int offset = scroll[TAB_PROG];

		ctx.enableScissor(bx, by, bx + BODY_W, by + BODY_H);
		int y = by - offset;
		for (StatsModels.ProgRow row : rows) {
			int h = row.height();
			if (y + h >= by && y <= by + BODY_H) {
				switch (row.kind()) {
					case StatsModels.ROW_HEADER ->
							StatsModels.sectionHeader(ctx, this.textRenderer, bx, y, BODY_W - 4, row.label());
					case StatsModels.ROW_BAR -> {
						StatsModels.text(ctx, this.textRenderer, "§f" + row.label(), bx + 8, y);
						StatsModels.textRight(ctx, this.textRenderer, "§7" + row.value(), bx + BODY_W - 8, y);
						StatsModels.progressBar(ctx, this.textRenderer, bx + 8, y + 10, BODY_W - 20, 4,
								row.fraction(), "", GREEN);
					}
					default -> {
						StatsModels.text(ctx, this.textRenderer,
								StatsModels.fit(this.textRenderer, "§7" + row.label(), 170), bx + 10, y);
						StatsModels.textRight(ctx, this.textRenderer,
								StatsModels.fit(this.textRenderer, "§f" + row.value(), 130), bx + BODY_W - 8, y);
					}
				}
			}
			y += h;
		}
		ctx.disableScissor();
		StatsModels.scrollbar(ctx, bx + BODY_W, by, BODY_H, contentH, scroll[TAB_PROG], maxScroll);
	}

	@Override
	public void close() {
		if (this.client != null) this.client.setScreen(parent);
	}
}
