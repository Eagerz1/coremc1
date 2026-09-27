package com.coremc.core.moderation;

/** One staff-selectable rule key in a tier. */
public record RuleDefinition(String key, String display, boolean enabled, boolean requiresAcknowledgement) {
    public String displayedReason(final String override) {
        return override == null || override.isBlank() ? display : override;
    }
}
