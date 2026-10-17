package com.example;

import java.util.Arrays;

/** The client-owned tab badge catalogue. Values are stable because assignments are persisted by key. */
public enum TabBadge {
	HAMMER("hammer", "🔨", "§6", "Hammer", "Owner"),
	HEART("heart", "❤", "§c", "Heart", "Tester"),
	PLUS("plus", "+", "§a", "Plus", "Plus features"),
	STAR("star", "★", "§e", "Star", "Player"),
	CROWN("crown", "♛", "§6", "Crown", "Creator"),
	SHIELD("shield", "◆", "§b", "Shield", "Guardian"),
	SPARK("spark", "✦", "§d", "Spark", "Contributor"),
	PICKAXE("pickaxe", "⚒", "§6", "Pickaxe", "Builder");

	private final String key;
	private final String icon;
	private final String color;
	private final String label;
	private final String role;

	TabBadge(String key, String icon, String color, String label, String role) {
		this.key = key;
		this.icon = icon;
		this.color = color;
		this.label = label;
		this.role = role;
	}

	public String key() {
		return key;
	}

	public String listPrefix() {
		return color + icon + " ";
	}

	public String label() {
		return color + icon + " " + label + " §8(" + role + ")";
	}

	public String buttonLabel() {
		return color + icon + " " + label;
	}

	public static TabBadge byKey(String key) {
		if (key == null) return STAR;
		return Arrays.stream(values()).filter(badge -> badge.key.equalsIgnoreCase(key)).findFirst().orElse(STAR);
	}
}
