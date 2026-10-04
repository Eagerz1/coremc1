package com.coremc.core.shop;

/**
 * Public-facing shop categories; keys match {@code shop.yml} section names
 * and {@code /shop <key>} arguments.
 */
public enum ShopCategory {
    BLOCKS("blocks", "&aBlocks"),
    FOOD("food", "&eFood"),
    REDSTONE("redstone", "&cRedstone"),
    MISC("misc", "&dMiscellaneous"),
    ORES("ores", "&bOres & Minerals"),
    TOKENS("tokens", "&bT&do&bk&de&bn&ds");

    private final String key;
    private final String display;

    ShopCategory(final String key, final String display) {
        this.key = key;
        this.display = display;
    }

    public String key() {
        return key;
    }

    public String display() {
        return display;
    }

    public static ShopCategory byKey(final String raw) {
        for (final ShopCategory category : values()) {
            if (category.key.equalsIgnoreCase(raw)) {
                return category;
            }
        }
        return null;
    }
}
