package com.example;

public enum ForgeCalcTheme {

	DEFAULT("Default", 0,
			0xFF0C1018, 0xFF2A3B55, 0xFF3A5075, 0xFF1E2C42, 0xFF16202F,
			0xFF4FA3FF, 0xFF2A5C99, 0xD8101A28, 0xFF223550, 0x4FA3FF, 0x9BD1FF),
	FARMER("Farmer", 0,
			0xFF1C1410, 0xFF9C7440, 0xFFB08A4F, 0xFF7A5A30, 0xFF4A3319,
			0xFF6FBF4A, 0xFF3F7A2A, 0xD8241A10, 0xFF4A3319, 0xFFC941, 0xFF7B2E),
	HACKER("Hacker", 1,
			0xFF000500, 0xFF0A140A, 0xFF10240F, 0xFF061006, 0xFF063806,
			0xFF00FF66, 0xFF00802F, 0xE0020A02, 0xFF0A3A12, 0x00FF66, 0x66FFB2),
	CHERRY("Cherry Blossom", 2,
			0xFF230F1A, 0xFF4A2436, 0xFF6E3550, 0xFF381A29, 0xFF5C2C42,
			0xFFFF9BC7, 0xFFB05580, 0xD82E1220, 0xFF5C2C42, 0xFF9BC7, 0xFFD4E6),
	NETHER("Nether", 1,
			0xFF160604, 0xFF3A1410, 0xFF57201A, 0xFF2A0E0A, 0xFF571E12,
			0xFFFF5533, 0xFF992A16, 0xD8200A06, 0xFF571E12, 0xFF5533, 0xFFB347),
	SPACE("Space", 2,
			0xFF060414, 0xFF161230, 0xFF241D4E, 0xFF0E0B22, 0xFF2E2560,
			0xFF8A6CFF, 0xFF4A3A99, 0xD80E0A22, 0xFF2E2560, 0x8A6CFF, 0xE0B0FF),
	BLOOD("Blood", 1,
			0xFF120202, 0xFF3A0808, 0xFF5C0F0F, 0xFF260404, 0xFF6E1212,
			0xFFFF2222, 0xFF8A0F0F, 0xE01A0303, 0xFF4A0A0A, 0xFF2222, 0xFF8888),
	MOON("Moon", 0,
			0xFF05070F, 0xFF141B2E, 0xFF222D4A, 0xFF0C111F, 0xFF2E3A5C,
			0xFFC9D6F0, 0xFF5C6E99, 0xD80A0E1C, 0xFF2E3A5C, 0xC9D6F0, 0x8FA8E0),
	ALPHA_WOLF("Alpha Wolf", 2,
			0xFF0A0A0C, 0xFF26262B, 0xFF3C3C44, 0xFF18181C, 0xFF4A4A55,
			0xFFE8E8F0, 0xFF6E6E7A, 0xE0121216, 0xFF3C3C44, 0xE8E8F0, 0x8A8A99),
	KITAGAWA("Kitagawa", 0,
			0xFF2A141E, 0xFF8A4A66, 0xFFB06A8A, 0xFF6E3850, 0xFF4A2438,
			0xFFFFB7D9, 0xFF99506E, 0xD8301826, 0xFF6E3850, 0xFFB7D9, 0xFFE3F0),
	SLIME("Slime", 0,
			0xFF0E1A0C, 0xFF2E5C22, 0xFF3F7A2E, 0xFF224418, 0xFF163310,
			0xFF7CFC5A, 0xFF3E8F2A, 0xD8122A0C, 0xFF224418, 0x7CFC5A, 0xC8FF9E),
	POOP("Poop", 1,
			0xFF140C06, 0xFF5C3A1E, 0xFF7A4E28, 0xFF3E2712, 0xFF2A1A0C,
			0xFFB07840, 0xFF5C3A1E, 0xD81E1206, 0xFF3E2712, 0xB07840, 0xE0B080),
	DARK("Dark", 0,
			0xFF000000, 0xFF141414, 0xFF232323, 0xFF0C0C0C, 0xFF2E2E2E,
			0xFFB8B8B8, 0xFF565656, 0xE8060606, 0xFF2E2E2E, 0xB8B8B8, 0xFFFFFF),
	LIGHT("Light", 0,
			0xFF9AA4B4, 0xFFD8DEE8, 0xFFEDF1F7, 0xFFC2CAD6, 0xFF8A94A6,
			0xFF4FA3FF, 0xFF7A90B0, 0xE0DCE2EC, 0xFF8A94A6, 0x2E6ECC, 0x6FA8FF),
	BEANS("Beans", 0,
			0xFF1A0F08, 0xFF6B4226, 0xFF8A5A36, 0xFF4E2E18, 0xFF3A2010,
			0xFFE8C49A, 0xFF8A5A36, 0xD8241408, 0xFF4E2E18, 0xE8C49A, 0xC08850),
	CAR("Car", 1,
			0xFF0A0A0A, 0xFF1E1E22, 0xFF2E2E34, 0xFF141416, 0xFFCC2222,
			0xFFFF3333, 0xFF881414, 0xE0101012, 0xFFCC2222, 0xFF3333, 0xFFAA00),
	EARTH("Earth", 0,
			0xFF06121E, 0xFF14425E, 0xFF1C5A7E, 0xFF0E3048, 0xFF0A2234,
			0xFF48C878, 0xFF1C5A7E, 0xD80A1E30, 0xFF0E3048, 0x48C878, 0x4FA3FF),
	BLACKHOLE("Blackhole", 0,
			0xFF000000, 0xFF120A1C, 0xFF1E1030, 0xFF0A0612, 0xFF241438,
			0xFFFF8C28, 0xFF6A2ECC, 0xEE060310, 0xFF241438, 0xFF8C28, 0xB44AFF),
	GALAXY("Galaxy", 2,
			0xFF060312, 0xFF1B1040, 0xFF2E1C66, 0xFF120A2C, 0xFF3A2280,
			0xFFB98CFF, 0xFF5B36B0, 0xDC0A0620, 0xFF3A2280, 0xFF6FC7, 0x6FD6FF),
	ANGRY_BIRDS("Angry Birds", 1,
			0xFF2A1206, 0xFFC03A18, 0xFFE05A22, 0xFF8E2810, 0xFFFFC414,
			0xFFFFD427, 0xFFB07A08, 0xE0341608, 0xFF8E2810, 0xFF4422, 0xFFD427),
	MINECRAFT("Minecraft", 0,
			0xFF1D1712, 0xFF7C6247, 0xFF9C7B58, 0xFF5A4634, 0xFF3E7C22,
			0xFF5FD22F, 0xFF2F7A16, 0xD8241C15, 0xFF5A4634, 0x5FD22F, 0xB8E986),
	CLAUDE("Claude", 2,
			0xFF1A140F, 0xFF2E2622, 0xFF3E342E, 0xFF221C18, 0xFFD97757,
			0xFFD97757, 0xFF8C4A34, 0xE01E1814, 0xFF3E342E, 0xD97757, 0xF0C0A8),
	MINING("Mining", 3,
			0xFF14100C, 0xFF3A3630, 0xFF524C44, 0xFF262220, 0xFF6A5A3A,
			0xFFFFC845, 0xFF9A7420, 0xDC1A1614, 0xFF4A443C, 0xFFC845, 0xC9D6F0),
	NEJI("Neji", 3,
			0xFF0A0410, 0xFF2A0E2E, 0xFF3E1442, 0xFF1A0820, 0xFF8E1030,
			0xFFD41840, 0xFF6A0A50, 0xE60E0414, 0xFF3E1442, 0xFF1840, 0x8A2ECC),
	JOSH_SAFARI("Joshplays05", 0,
			0xFF1E1408, 0xFFB07C34, 0xFFCE9A48, 0xFF8A5E22, 0xFFE0A020,
			0xFFF0B830, 0xFF9A6C1A, 0xD8281A0A, 0xFF8A5E22, 0xF0B830, 0xC89848),
	CURSED("Cursed", 1,
			0xFF000000, 0xFF1A0000, 0xFF2E0000, 0xFF0E0000, 0xFF8A0000,
			0xFFFF0000, 0xFF400000, 0xEE0A0000, 0xFF2E0000, 0xFF0000, 0x00FF00),
	CODER("Coder", 1,
			0xFF0A0E12, 0xFF161C24, 0xFF1E2630, 0xFF10151C, 0xFF2A9D4E,
			0xFF3DDC84, 0xFF1E7A44, 0xF00A0E12, 0xFF1E2630, 0x3DDC84, 0x56B6C2),
	NOLIFE("No Life", 3,
			0xFF050505, 0xFF101018, 0xFF18182A, 0xFF090910, 0xFF5B21B6,
			0xFF8B5CF6, 0xFF4C1D95, 0xF0060608, 0xFF18182A, 0x8B5CF6, 0xEC4899),
	AFKABLE("AFKable", 1,
			0xFF08080C, 0xFF15151C, 0xFF1C1C26, 0xFF0D0D12, 0xFF2A2A36,
			0xFF4ADE80, 0xFF22C55E, 0xEE0D0D12, 0xFF2A2A36, 0x4ADE80, 0x4CD7E0),
	BINGO("Bingo", 0,
			0xFF06170E, 0xFF14472E, 0xFF1C6340, 0xFF0C3020, 0xFFD4A02E,
			0xFFE23B3B, 0xFF8E1F22, 0xDC0A2416, 0xFFD4A02E, 0xF6D25A, 0xFFF1D6),
	COLOR("Color", 1,
			0xFF120018, 0xFF2B1046, 0xFF3F1A66, 0xFF1A0A2C, 0xFFFF4FD8,
			0xFF00E5FF, 0xFF0090B8, 0xE8150726, 0xFFFF4FD8, 0xFF2E88, 0x2EE8FF),
	CUSTOM("Custom", 0,
			0xFF1C1410, 0xFF9C7440, 0xFFB08A4F, 0xFF7A5A30, 0xFF4A3319,
			0xFF6FBF4A, 0xFF3F7A2A, 0xD8241A10, 0xFF4A3319, 0xFFC941, 0xFF7B2E);

	public String label;
	public int layout;
	public int frameDark;
	public int plank;
	public int plankLight;
	public int plankDark;
	public int border;
	public int accent;
	public int accentDark;
	public int panelBg;
	public int panelBorder;
	public int gradFrom;
	public int gradTo;

	public static int customPanelStyle = 0;

	public int panelStyle() {
		if (this == CUSTOM) return customPanelStyle;
		return switch (this) {
			case HACKER, DARK, CAR, ANGRY_BIRDS, CURSED, CODER, AFKABLE, COLOR -> 1;
			case CHERRY, KITAGAWA, SLIME, CLAUDE -> 2;
			case SPACE, MOON, ALPHA_WOLF, BLOOD, BLACKHOLE, GALAXY, MINING, NEJI, NOLIFE, BINGO -> 3;
			case EARTH -> 2;
			default -> 0;
		};
	}

	ForgeCalcTheme(String label, int layout, int frameDark, int plank, int plankLight, int plankDark,
	               int border, int accent, int accentDark, int panelBg, int panelBorder, int gradFrom, int gradTo) {
		this.label = label;
		this.layout = layout;
		this.frameDark = frameDark;
		this.plank = plank;
		this.plankLight = plankLight;
		this.plankDark = plankDark;
		this.border = border;
		this.accent = accent;
		this.accentDark = accentDark;
		this.panelBg = panelBg;
		this.panelBorder = panelBorder;
		this.gradFrom = gradFrom;
		this.gradTo = gradTo;
	}

	/**
	 * Wall-clock driven rainbow, for effects that want to cycle the hue wheel instead of
	 * sitting on one static colour. The enum's own fields are read all over the mod and
	 * must stay stable, so animated colours are produced here on demand rather than by
	 * mutating a theme.
	 *
	 * @param speed  full hue cycles per second (0.3f is a calm sweep)
	 * @param offset extra hue in turns; pass a per-particle value to fan them across the wheel
	 * @return an opaque 0xFFRRGGBB colour at full saturation and value
	 */
	public static int rainbow(float speed, float offset) {
		double turns = System.currentTimeMillis() * 0.001d * speed + offset;
		float hue = (float) (turns - Math.floor(turns));
		float h = hue * 6f;
		int sector = (int) h;
		int up = (int) ((h - sector) * 255f);
		int down = 255 - up;
		return 0xFF000000 | switch (sector % 6) {
			case 0 -> 0xFF0000 | (up << 8);
			case 1 -> (down << 16) | 0x00FF00;
			case 2 -> 0x00FF00 | up;
			case 3 -> (down << 8) | 0x0000FF;
			case 4 -> (up << 16) | 0x0000FF;
			default -> 0xFF0000 | down;
		};
	}

	private static ForgeCalcTheme current = FARMER;

	public static ForgeCalcTheme current() {
		return current;
	}

	/**
	 * The one place a theme actually becomes active. Every colour cache in the mod is
	 * refreshed here, so screens, HUD panels and the in-GUI quick buttons can never end
	 * up drawing with palettes from different themes.
	 */
	private static void activate(ForgeCalcTheme theme) {
		current = theme;
		ThemeBackgrounds.invalidate();
		refreshDerived();
	}

	/** Re-pushes the live palette into every cached copy. Safe to call at any time. */
	public static void refreshDerived() {
		try {
			FarmScreen.applyTheme();
		} catch (Throwable ignored) {
		}
		try {
			ForgeOreCalculatorClient.applyPanelTheme();
		} catch (Throwable ignored) {
		}
	}

	/** Switches to the CUSTOM slot using whatever palette is already loaded into it. */
	public static void useCustom() {
		activate(CUSTOM);
	}

	public static void set(String name) {
		if (name != null && name.startsWith("custom:")) {
			CustomTheme custom = CustomTheme.load(name.substring(7));
			if (custom != null) {
				custom.applyTo();
				activate(CUSTOM);
				return;
			}
		}
		for (ForgeCalcTheme theme : values()) {
			if (theme.name().equalsIgnoreCase(name) || theme.label.equalsIgnoreCase(name)) {
				activate(theme);
				return;
			}
		}
		activate(FARMER);
	}

	public ForgeCalcTheme next() {
		ForgeCalcTheme next = values()[(ordinal() + 1) % values().length];
		return next == CUSTOM ? values()[0] : next;
	}
}
