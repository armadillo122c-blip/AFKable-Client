package com.example;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ThemeBackgrounds {

	private static final int GRID_W = 144;
	private static final int GRID_H = 81;

	// segments per theme: {gridX, gridY, gridWidth, argb}
	private static final Map<String, List<int[]>> CACHE = new HashMap<>();
	private static final Map<String, Boolean> MISSING = new HashMap<>();

	public static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve("afkable-backgrounds");
	}

	public static void invalidate() {
		CACHE.clear();
		MISSING.clear();
		ICON_CACHE.clear();
		ICON_MISSING.clear();
	}

	private static final int ICON_GRID = 24;
	private static final Map<String, List<int[]>> ICON_CACHE = new HashMap<>();
	private static final Map<String, Boolean> ICON_MISSING = new HashMap<>();

	public static boolean renderIcon(DrawContext ctx, String key, int x, int y, int w, int h) {
		if (Boolean.TRUE.equals(ICON_MISSING.get(key))) return false;

		List<int[]> segments = ICON_CACHE.get(key);
		if (segments == null) {
			segments = loadGrid(key, ICON_GRID, ICON_GRID, true);
			if (segments == null) {
				ICON_MISSING.put(key, true);
				return false;
			}
			ICON_CACHE.put(key, segments);
		}

		for (int[] seg : segments) {
			int x1 = x + seg[0] * w / ICON_GRID;
			int y1 = y + seg[1] * h / ICON_GRID;
			int x2 = x + (seg[0] + seg[2]) * w / ICON_GRID;
			int y2 = y + (seg[1] + 1) * h / ICON_GRID;
			ctx.fill(x1, y1, x2, y2, seg[3]);
		}
		return true;
	}

	public static boolean renderFullscreen(DrawContext ctx, String key, int width, int height) {
		if (Boolean.TRUE.equals(ICON_MISSING.get(key + "$fs"))) return false;
		List<int[]> segments = ICON_CACHE.get(key + "$fs");
		if (segments == null) {
			segments = loadGrid(key, GRID_W, GRID_H, false);
			if (segments == null) {
				ICON_MISSING.put(key + "$fs", true);
				return false;
			}
			ICON_CACHE.put(key + "$fs", segments);
		}
		for (int[] seg : segments) {
			int x1 = seg[0] * width / GRID_W;
			int y1 = seg[1] * height / GRID_H;
			int x2 = (seg[0] + seg[2]) * width / GRID_W;
			int y2 = (seg[1] + 1) * height / GRID_H;
			ctx.fill(x1, y1, x2, y2, seg[3]);
		}
		return true;
	}

	public static String themeKey() {
		if (ForgeCalcTheme.current() == ForgeCalcTheme.CUSTOM) {
			CustomTheme custom = CustomTheme.load(ForgeCalcTheme.CUSTOM.label);
			if (custom != null && !custom.background.isBlank()) {
				return custom.background.toLowerCase(Locale.ROOT).replace(".png", "");
			}
		}
		return ForgeCalcTheme.current().name().toLowerCase(Locale.ROOT);
	}

	public static boolean hasBackground() {
		return CACHE.containsKey(themeKey());
	}

	public static boolean render(DrawContext ctx, int width, int height) {
		String key = themeKey();
		if (Boolean.TRUE.equals(MISSING.get(key))) return false;

		List<int[]> segments = CACHE.get(key);
		if (segments == null) {
			segments = load(key);
			if (segments == null) {
				MISSING.put(key, true);
				return false;
			}
			CACHE.put(key, segments);
		}

		for (int[] seg : segments) {
			int x1 = seg[0] * width / GRID_W;
			int y1 = seg[1] * height / GRID_H;
			int x2 = (seg[0] + seg[2]) * width / GRID_W;
			int y2 = (seg[1] + 1) * height / GRID_H;
			ctx.fill(x1, y1, x2, y2, seg[3]);
		}
		ctx.fill(0, 0, width, height, 0x38000000);
		return true;
	}

	private static List<int[]> load(String themeKey) {
		return loadGrid(themeKey, GRID_W, GRID_H, false);
	}

	private static List<int[]> loadGrid(String key, int gridW, int gridH, boolean keepTransparency) {
		try {
			Files.createDirectories(folder());
			Path file = null;
			String[] names = {key, key.replace("_", ""), key.equals("kitagawa") ? "kitawaga" : key};
			String[] exts = {".png", ".jpg", ".jpeg"};
			outer:
			for (String name : names) {
				for (String ext : exts) {
					Path candidate = folder().resolve(name + ext);
					if (Files.exists(candidate)) {
						file = candidate;
						break outer;
					}
				}
			}
			if (file == null) return null;

			BufferedImage image = ImageIO.read(file.toFile());
			if (image == null) return null;

			List<int[]> segments = new ArrayList<>();
			for (int gy = 0; gy < gridH; gy++) {
				int sampleY = Math.min(image.getHeight() - 1, gy * image.getHeight() / gridH + image.getHeight() / (gridH * 2));
				int runStart = 0;
				int runColor = sample(image, 0, sampleY, gridW, keepTransparency);

				for (int gx = 1; gx <= gridW; gx++) {
					int color = gx < gridW ? sample(image, gx, sampleY, gridW, keepTransparency) : ~runColor;
					if (color != runColor) {
						if ((runColor >>> 24) > 0x20) {
							segments.add(new int[]{runStart, gy, gx - runStart, runColor});
						}
						runStart = gx;
						runColor = color;
					}
				}
			}
			System.out.println("[ForgeCalc] Loaded image " + key + ".png (" + segments.size() + " segments)");
			return segments;
		} catch (Exception e) {
			System.out.println("[ForgeCalc] Failed to load image " + key + ": " + e.getMessage());
			return null;
		}
	}

	private static int sample(BufferedImage image, int gx, int sampleY, int gridW, boolean keepTransparency) {
		int x = Math.min(image.getWidth() - 1, gx * image.getWidth() / gridW + image.getWidth() / (gridW * 2));
		int argb = image.getRGB(x, sampleY);
		if (keepTransparency) {
			int alpha = (argb >>> 24) < 0x40 ? 0 : 0xFF;
			return (alpha << 24) | (argb & 0xF0F0F0);
		}
		return 0xFF000000 | (argb & 0xF0F0F0);
	}

	private static int quantize(int argb) {
		return argb & 0xF0F0F0;
	}
}
