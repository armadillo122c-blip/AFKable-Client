package com.example;

public enum OreData {
    COAL("Coal Ore", 0.01),
    COPPER("Copper Ore", 0.03),
    IRON("Iron Ore", 0.05),
    ZINC("Zinc Ore", 0.1),
    EARTH("Earth Ore", 0.15),
    SILVER("Silver Ore", 0.2),
    GOLD("Gold Ore", 0.33),
    RUBY("Ruby Ore", 0.75),
    SAPPHIRE("Sapphire Ore", 1.5),
    DIAMOND("Diamond Ore", 2.5),
    EMERALD("Emerald Ore", 3.5),
    EYE_CORE("Eye Core", 5.0),
    MITHRIL("Mythril Ore", 10.0),
    OBSIDIAN("Obisidian", 30.0),
    URANIUM("Uranium Ore", 50.0),
    PLUTONIUM("Plutonium Ore", 75.0),
    ANTIMATTER("Antimatter Ore", 100.0),
    DIAMOND_CLUSTER("Diamond Cluster", 150.0),
    COSMIC("Cosmic Clutter", 200.0),
    ENDERITE("Enderite Ore", 500.0),
    STELLAR("Stellar Ore", 1000.0),
    OPAL("Opal", 2000.0),
    LITHIUM("Lithium Ore", 5000.0),
    TITANIUM("Titanium Ore", 10000.0),
    ELEMENTAL_CORE("Elemental Core", 15000.0),
    ATOMIC_REACTOR("Atomic Reactor", 30000.0),
    QUANTUM_CORE("Quantum Core", 60000.0),
    SINGULARITY("Singularity", 125000.0),
    NEUTRON("Neutron Ore", 250000.0),
    VOID_CRYSTAL("Void Crystal", 500000.0),
    CELESTIAL_CORE("Celestial Core", 1000000.0);

    public final String displayName;
    /** Shipped fallback value. Prefer {@link #value()} so server rebalances are picked up. */
    public final double multiplier;

    OreData(String displayName, double multiplier) {
        this.displayName = displayName;
        this.multiplier = multiplier;
    }

    /**
     * Values learned from the in-game "Ore Value:" lore, keyed by {@link #name()}.
     * Populated by the client at runtime and persisted to the config, so a season
     * rebalance (like the OG 2 ore nerf) takes effect without a mod update.
     */
    private static final java.util.Map<String, Double> LEARNED =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    public static void putLearned(String oreName, double value) {
        if (oreName != null && value > 0) LEARNED.put(oreName, value);
    }

    public static void loadLearned(java.util.Map<String, Double> values) {
        LEARNED.clear();
        if (values != null) LEARNED.putAll(values);
    }

    public static java.util.Map<String, Double> learned() {
        synchronized (LEARNED) {
            return new java.util.LinkedHashMap<>(LEARNED);
        }
    }

    public static void clearLearned() {
        LEARNED.clear();
    }

    /** The live ore value: whatever the server last told us, else the shipped default. */
    public double value() {
        Double v = LEARNED.get(name());
        return v != null ? v : multiplier;
    }

    /** True when this ore's value came from the server rather than the shipped table. */
    public boolean isLearned() {
        return LEARNED.containsKey(name());
    }

    public enum Variant {
        NORMAL("", 1.0, "§f"),
        SHINY("Shiny", 3.0, "§e"),
        RAINBOW("Rainbow", 8.0, "§d"),
        DARKMATTER("Darkmatter", 30.0, "§8");

        public final String prefix;
        public final double bonus;
        public final String color;

        Variant(String prefix, double bonus, String color) {
            this.prefix = prefix;
            this.bonus = bonus;
            this.color = color;
        }

        public static Variant find(String cleanInput) {
            String lower = cleanInput.toLowerCase();
            if (lower.contains("darkmatter")) return DARKMATTER;
            if (lower.contains("rainbow")) return RAINBOW;
            if (lower.contains("shiny")) return SHINY;
            return NORMAL;
        }
    }

    public static String clean(String itemName) {
        return itemName.replaceAll("(?i)§[0-9a-fk-orx]", "")
                .replaceAll("[^a-zA-Z0-9 ]", "").trim();
    }

    public static OreData find(String itemName) {
        String cleanInput = clean(itemName);

        for (OreData ore : values()) {
            String cleanOreName = ore.displayName.replaceAll("[^a-zA-Z0-9 ]", "");
            if (cleanInput.contains(cleanOreName)) {
                return ore;
            }
        }
        return null;
    }

    public static Variant findVariant(String itemName) {
        return Variant.find(clean(itemName));
    }
}
