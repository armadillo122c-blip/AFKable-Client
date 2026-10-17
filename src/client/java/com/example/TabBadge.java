package com.example;

import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Arrays;

/** The client-owned tab badge catalogue. Values are stable because assignments are persisted by key. */
public enum TabBadge {
	CREATOR("creator", "♛", "§6", "Creator", "Creator", '\uE000'),
	PLUS("plus", "✚", "§b", "Plus", "Plus", '\uE001'),
	DEFAULT("default", "●", "§7", "Default", "Player", '\uE002');

	private final String key;
	private final String icon;
	private final String color;
	private final String label;
	private final String role;
	private final char glyph;

	TabBadge(String key, String icon, String color, String label, String role, char glyph) {
		this.key = key;
		this.icon = icon;
		this.color = color;
		this.label = label;
		this.role = role;
		this.glyph = glyph;
	}

	public String key() {
		return key;
	}

	public String listPrefix() {
		return color + icon + " ";
	}

	public Text tabIcon() {
		return Text.literal(String.valueOf(glyph))
				.setStyle(Style.EMPTY.withFont(Identifier.of("forge-ore-calculator", "afkable_badges")));
	}

	public String label() {
		return color + icon + " " + label + " §8(" + role + ")";
	}

	public String buttonLabel() {
		return color + icon + " " + label;
	}

	public static TabBadge byKey(String key) {
		if (key == null) return DEFAULT;
		// Keep existing saved badge assignments readable after switching to the new catalogue.
		if (key.equalsIgnoreCase("hammer") || key.equalsIgnoreCase("crown")) return CREATOR;
		if (key.equalsIgnoreCase("star") || key.equalsIgnoreCase("heart") || key.equalsIgnoreCase("shield")
				|| key.equalsIgnoreCase("spark") || key.equalsIgnoreCase("pickaxe")) return DEFAULT;
		return Arrays.stream(values()).filter(badge -> badge.key.equalsIgnoreCase(key)).findFirst().orElse(DEFAULT);
	}
}
