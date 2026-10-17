package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import net.minecraft.client.gui.widget.TextFieldWidget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class StockNotifierScreen extends FarmScreen {

	private static final int COLS = 4;
	private static final int CELL_W = 72;
	private static final int CELL_H = 56;

	private final Screen parent;
	private int scroll = 0;
	private int sortMode = 0; // 0 name, 1 price, 2 gain, 3 damage
	private boolean watchedOnly = false;
	private TextFieldWidget searchField;

	private String formatDamage(double value) {
		if (value >= 1_000_000_000) return compact(value / 1_000_000_000, "B");
		if (value >= 1_000_000) return compact(value / 1_000_000, "M");
		if (value >= 1_000) return compact(value / 1_000, "K");
		return String.valueOf((long) value);
	}

	private String compact(double value, String suffix) {
		return String.format(Locale.ROOT, "%.1f", value).replaceAll("\\.0$", "") + suffix;
	}

	public StockNotifierScreen(Screen parent) {
		super(Text.literal("Stock Notifier"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int panelW = COLS * (CELL_W + 4) + 24;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - 118;
		// resize recreates widgets, so carry the typed text across
		String previousText = searchField != null ? searchField.getText() : "";
		searchField = new TextFieldWidget(this.textRenderer, px + 60, py + 25, 90, 12, Text.literal(""));
		searchField.setMaxLength(24);
		if (!previousText.isEmpty()) searchField.setText(previousText);
		addSelectableChild(searchField);
	}

	private static double parseMoney(String raw) {
		if (raw == null || raw.isBlank()) return -1;
		try {
			String cleaned = raw.replace(",", "").toLowerCase(Locale.ROOT);
			double mult = 1;
			if (cleaned.endsWith("k")) { mult = 1e3; cleaned = cleaned.substring(0, cleaned.length() - 1); }
			else if (cleaned.endsWith("m")) { mult = 1e6; cleaned = cleaned.substring(0, cleaned.length() - 1); }
			else if (cleaned.endsWith("b")) { mult = 1e9; cleaned = cleaned.substring(0, cleaned.length() - 1); }
			else if (cleaned.endsWith("t")) { mult = 1e12; cleaned = cleaned.substring(0, cleaned.length() - 1); }
			return Double.parseDouble(cleaned) * mult;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		drawThemeBackdrop(ctx);
		updateMouse();

		int panelW = COLS * (CELL_W + 4) + 24;
		int panelH = 240;
		int px = this.width / 2 - panelW / 2;
		int py = this.height / 2 - panelH / 2;

		drawWoodPanel(ctx, px, py, panelW, panelH);
		ctx.fill(px, py, px + panelW, py + 22, PLANK_DARK);
		ctx.fill(px, py + 22, px + panelW, py + 23, FRAME_DARK);
		ctx.drawItem(new ItemStack(Items.BELL), px + 5, py + 3);
		ctx.drawCenteredTextWithShadow(this.textRenderer, TextFx.gradient("STOCK NOTIFIER", ForgeCalcTheme.current().gradFrom, ForgeCalcTheme.current().gradTo), px + panelW / 2, py + 7, CREAM);

		ctx.drawText(this.textRenderer, Text.literal("§7Search:"), px + 14, py + 27, 0xFFFFFFFF, true);
		if (searchField != null) {
			searchField.render(ctx, mouseX, mouseY, delta);
		}
		String[] sortNames = {"NAME", "PRICE", "GAIN", "DAMAGE"};
		if (drawBigButton(ctx, mouseX, mouseY, px + 156, py + 24, 58, 14, "§f" + sortNames[sortMode], null)) {
			sortMode = (sortMode + 1) % sortNames.length;
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 218, py + 24, 58, 14, watchedOnly ? "§aWATCHED" : "§7ALL", null)) {
			watchedOnly = !watchedOnly;
		}

		String query = searchField != null ? searchField.getText().trim().toLowerCase(Locale.ROOT) : "";
		List<String> mobs = new ArrayList<>(ForgeCalcConfig.seenMobs);
		mobs.removeIf(StockStore::isJunk);
		mobs.removeIf(mob -> {
			if (ForgeCalcConfig.watchedMobs.contains(mob)) return false;
			net.minecraft.item.ItemStack icon = ForgeOreCalculatorClient.mobIcon(mob);
			return icon != null && icon.getItem() == Items.BARRIER;
		});
		if (!query.isEmpty()) {
			mobs.removeIf(mob -> !mob.toLowerCase(Locale.ROOT).contains(query));
		}
		if (watchedOnly) {
			mobs.removeIf(mob -> !ForgeCalcConfig.watchedMobs.contains(mob));
		}
		Comparator<String> comparator = switch (sortMode) {
			case 1 -> Comparator.comparingDouble((String mob) -> {
				String[] data = ForgeCalcConfig.mobData.get(mob);
				return data != null ? -parseMoney(data[0]) : 0;
			});
			case 2 -> Comparator.comparingDouble((String mob) -> {
				String[] data = ForgeCalcConfig.mobData.get(mob);
				return data != null ? -parseMoney(data[1]) : 0;
			});
			case 3 -> Comparator.comparingDouble((String mob) -> {
				String[] data = ForgeCalcConfig.mobData.get(mob);
				return data != null && data.length > 2 ? -parseMoney(data[2]) : 0;
			});
			default -> Comparator.naturalOrder();
		};
		mobs.sort(Comparator.<String, Boolean>comparing(mob -> !ForgeCalcConfig.watchedMobs.contains(mob))
				.thenComparing(comparator));

		if (mobs.isEmpty()) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7No mobs seen yet."), px + panelW / 2, py + 95, 0xFFFFFFFF);
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7Open the §eFarmer Stock §7once"), px + panelW / 2, py + 107, 0xFFFFFFFF);
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7so I can learn the mobs!"), px + panelW / 2, py + 119, 0xFFFFFFFF);
		} else {
			int rows = (mobs.size() + COLS - 1) / COLS;
			int visibleRows = 3;
			int maxScroll = Math.max(0, rows - visibleRows);
			scroll = Math.max(0, Math.min(maxScroll, scroll - pollScroll()));

			int gridX = px + 12;
			int gridY = py + 44;
			String hoveredMob = null;

			for (int row = scroll; row < Math.min(rows, scroll + visibleRows); row++) {
				for (int col = 0; col < COLS; col++) {
					int index = row * COLS + col;
					if (index >= mobs.size()) break;

					String mob = mobs.get(index);
					int cx = gridX + col * (CELL_W + 4);
					int cy = gridY + (row - scroll) * (CELL_H + 4);
					boolean watched = ForgeCalcConfig.watchedMobs.contains(mob);
					boolean hovered = hover(mouseX, mouseY, cx, cy, CELL_W, CELL_H);

					ctx.fill(cx - 1, cy - 1, cx + CELL_W + 1, cy + CELL_H + 1, watched ? GREEN_DARK : (hovered ? PLANK_LIGHT : BORDER));
					ctx.fill(cx, cy, cx + CELL_W, cy + CELL_H, hovered ? 0xFF2E2216 : 0xF0241A10);
					if (watched) {
						ctx.fill(cx, cy, cx + CELL_W, cy + 2, GREEN);
					}

					if (ForgeOreCalculatorClient.INSTANCE != null && ForgeOreCalculatorClient.INSTANCE.isInStock(mob)) {
						ctx.fill(cx + CELL_W - 6, cy + 2, cx + CELL_W - 2, cy + 6, 0xFF3DDC53);
					}
					ItemStack icon = ForgeOreCalculatorClient.mobIcon(mob);
					ctx.drawItem(icon != null ? icon : new ItemStack(Items.PLAYER_HEAD), cx + CELL_W / 2 - 8, cy + 3);

					String shortName = mob.length() > 11 ? mob.substring(0, 10) + "…" : mob;
					ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal((watched ? "§a" : "§f") + shortName), cx + CELL_W / 2, cy + 22, 0xFFFFFFFF);

					String[] data = ForgeCalcConfig.mobData.get(mob);
					String price = data != null && !data[0].isBlank() ? "§6$" + data[0] : "§8$?";
					String gain = data != null && data.length > 2 && !data[2].isBlank()
							? "§c" + formatDamage(parseMoney(data[2])) + " dmg"
							: (data != null && !data[1].isBlank() ? "§b$" + data[1] + "/s" : "§8?/s");
					ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(price), cx + CELL_W / 2, cy + 33, 0xFFFFFFFF);
					ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(gain), cx + CELL_W / 2, cy + 44, 0xFFFFFFFF);

					if (hovered) {
						hoveredMob = mob;
						if (mouseClickedThisFrame) {
							if (watched) {
								ForgeCalcConfig.watchedMobs.remove(mob);
							} else {
								ForgeCalcConfig.watchedMobs.add(mob);
							}
						}
					}
				}
			}

			if (hoveredMob != null) {
				boolean w = ForgeCalcConfig.watchedMobs.contains(hoveredMob);
				drawInfoTooltip(ctx, mouseX, mouseY, List.of("§f" + hoveredMob, w ? "§aWatching - click to unwatch" : "§7Click to watch"));
			}
		}

		// live activity strip: the newest stock events, straight from the shared feed
		java.util.List<LiveActivity.Entry> feed = LiveActivity.snapshot();
		int fy = py + panelH - 42;
		ctx.fill(px + 12, fy, px + panelW - 12, fy + 14, 0x30000000);
		ctx.fill(px + 12, fy, px + 14, fy + 14, ForgeCalcTheme.current().accent);
		String latest = "§8no activity yet";
		for (LiveActivity.Entry e : feed) {
			if (e != null && e.kind() == LiveActivity.Kind.STOCK) {
				latest = "§a● §f" + e.text() + " §8" + LiveActivity.relative(System.currentTimeMillis() - e.time());
				break;
			}
		}
		ctx.drawText(this.textRenderer, Text.literal(latest), px + 18, fy + 3, 0xFFFFFFFF, true);

		int half = (panelW - 30) / 2;
		if (drawBigButton(ctx, mouseX, mouseY, px + 12, py + panelH - 24, half, 18, "§f§lBACK", null)) {
			close();
			return;
		}
		if (drawBigButton(ctx, mouseX, mouseY, px + 18 + half, py + panelH - 24, half, 18, "§6§lBUY ALL", null)) {
			ForgeCalcConfig.save();
			this.client.setScreen(null);
			if (ForgeOreCalculatorClient.INSTANCE != null) {
				ForgeOreCalculatorClient.INSTANCE.startBuyAll(this.client);
			}
		}
	}

	@Override
	public void close() {
		StockStore.purgeJunk();
		ForgeCalcConfig.save();
		this.client.setScreen(parent);
	}
}
