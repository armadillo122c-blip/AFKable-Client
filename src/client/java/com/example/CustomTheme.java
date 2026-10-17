package com.example;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A player-made theme. The user picks a handful of key colours and the rest of
 * the palette (frames, borders, panel backgrounds) is derived from them so the
 * builder stays simple while every screen still gets a full theme.
 */
public class CustomTheme {

	public String name = "My Theme";
	public String author = "";
	public int plank = 0xFF9C7440;
	public int plankLight = 0xFFB08A4F;
	public int plankDark = 0xFF7A5A30;
	public int accent = 0xFF6FBF4A;
	public int gradFrom = 0xFFC941;
	public int gradTo = 0xFF7B2E;
	public int panelStyle = 0;
	public int layout = 0;
	public String background = "";

	public static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve("afkable-themes");
	}

	public static String safeName(String raw) {
		String clean = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_ -]", "").trim().replace(' ', '_');
		return clean.isEmpty() ? "theme" : clean;
	}

	private static int blend(int a, int b, float t) {
		int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		return 0xFF000000
				| ((int) (ar + (br - ar) * t) << 16)
				| ((int) (ag + (bg - ag) * t) << 8)
				| (int) (ab + (bb - ab) * t);
	}

	/** Writes this palette into the CUSTOM theme slot and makes it live. */
	public void applyTo() {
		ForgeCalcTheme custom = ForgeCalcTheme.CUSTOM;
		custom.label = name;
		custom.layout = layout;
		custom.plank = plank;
		custom.plankLight = plankLight;
		custom.plankDark = plankDark;
		custom.accent = accent;
		custom.accentDark = blend(accent, 0xFF000000, 0.45f);
		custom.frameDark = blend(plankDark, 0xFF000000, 0.6f);
		custom.border = blend(plankDark, accent, 0.25f);
		custom.panelBg = (0xD8 << 24) | (blend(plankDark, 0xFF000000, 0.55f) & 0xFFFFFF);
		custom.panelBorder = blend(plankDark, accent, 0.2f);
		custom.gradFrom = gradFrom & 0xFFFFFF;
		custom.gradTo = gradTo & 0xFFFFFF;
		ForgeCalcTheme.customPanelStyle = panelStyle;
	}

	public String serialize() {
		return String.join(";",
				"name=" + name.replace(";", ""),
				"author=" + author.replace(";", ""),
				"plank=" + Integer.toHexString(plank & 0xFFFFFF),
				"light=" + Integer.toHexString(plankLight & 0xFFFFFF),
				"dark=" + Integer.toHexString(plankDark & 0xFFFFFF),
				"accent=" + Integer.toHexString(accent & 0xFFFFFF),
				"gfrom=" + Integer.toHexString(gradFrom & 0xFFFFFF),
				"gto=" + Integer.toHexString(gradTo & 0xFFFFFF),
				"style=" + panelStyle,
				"layout=" + layout,
				"bg=" + background);
	}

	public static CustomTheme parse(String data) {
		CustomTheme theme = new CustomTheme();
		for (String part : data.split(";")) {
			String[] kv = part.split("=", 2);
			if (kv.length != 2) continue;
			String value = kv[1].trim();
			try {
				switch (kv[0].trim()) {
					case "name" -> theme.name = value;
					case "author" -> theme.author = value;
					case "plank" -> theme.plank = 0xFF000000 | Integer.parseInt(value, 16);
					case "light" -> theme.plankLight = 0xFF000000 | Integer.parseInt(value, 16);
					case "dark" -> theme.plankDark = 0xFF000000 | Integer.parseInt(value, 16);
					case "accent" -> theme.accent = 0xFF000000 | Integer.parseInt(value, 16);
					case "gfrom" -> theme.gradFrom = Integer.parseInt(value, 16);
					case "gto" -> theme.gradTo = Integer.parseInt(value, 16);
					case "style" -> theme.panelStyle = Math.max(0, Math.min(3, Integer.parseInt(value)));
					case "layout" -> theme.layout = Math.max(0, Math.min(2, Integer.parseInt(value)));
					case "bg" -> theme.background = value;
					default -> {
					}
				}
			} catch (NumberFormatException ignored) {
			}
		}
		return theme;
	}

	public void save() {
		try {
			Files.createDirectories(folder());
			Files.writeString(folder().resolve(safeName(name) + ".theme"), serialize());
		} catch (Exception ignored) {
		}
	}

	public static CustomTheme load(String fileName) {
		try {
			Path file = folder().resolve(safeName(fileName) + ".theme");
			if (!Files.exists(file)) return null;
			return parse(Files.readString(file));
		} catch (Exception e) {
			return null;
		}
	}

	public static List<CustomTheme> listAll() {
		List<CustomTheme> themes = new ArrayList<>();
		try {
			if (!Files.exists(folder())) return themes;
			try (var stream = Files.list(folder())) {
				for (Path file : stream.filter(f -> f.toString().endsWith(".theme")).toList()) {
					try {
						themes.add(parse(Files.readString(file)));
					} catch (Exception ignored) {
					}
				}
			}
		} catch (Exception ignored) {
		}
		return themes;
	}

	public static void delete(String name) {
		try {
			Files.deleteIfExists(folder().resolve(safeName(name) + ".theme"));
		} catch (Exception ignored) {
		}
	}

	public CustomTheme copy() {
		return parse(serialize());
	}
}
