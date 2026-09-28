package com.coremc.core.moderation;

import java.util.Locale;
import java.util.Set;

/** Configured staff rank used for moderation capability and immunity checks. */
public record StaffRank(String id, String display, int weight, Set<String> capabilities) {

    public boolean has(final String capability) {
        final String key = capability.toLowerCase(Locale.ROOT);
        if (capabilities.contains("*") || capabilities.contains(key)) {
            return true;
        }
        int dot;
        String prefix = key;
        while ((dot = prefix.lastIndexOf('.')) > 0) {
            prefix = prefix.substring(0, dot);
            if (capabilities.contains(prefix + ".*")) {
                return true;
            }
        }
        return false;
    }

    public static StaffRank member() {
        return new StaffRank("member", "Member", 0, Set.of());
    }

    public static StaffRank console() {
        return new StaffRank("console", "Console", Integer.MAX_VALUE, Set.of("*"));
    }
}
