package com.coremc.core.moderation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class StaffRankTest {

    @Test
    void wildcardAndPrefixCapabilitiesWork() {
        final StaffRank manager = new StaffRank("manager", "Manager", 60, Set.of("*"));
        assertTrue(manager.has("ban"));
        assertTrue(manager.has("tier.5"));

        final StaffRank tierStaff = new StaffRank("tier", "Tier Staff", 10, Set.of("tier.*", "vanish.see"));
        assertTrue(tierStaff.has("tier.1"));
        assertTrue(tierStaff.has("tier.5"));
        assertTrue(tierStaff.has("vanish.see"));
        assertFalse(tierStaff.has("ban"));
    }

    @Test
    void helperStyleRankCannotBanWithoutCapability() {
        final StaffRank helper = new StaffRank("helper", "Helper", 10,
                Set.of("vanish", "vanish.see", "spectate", "cps", "freeze", "kick", "mute", "unmute", "tier.1", "history"));
        assertTrue(helper.has("mute"));
        assertTrue(helper.has("tier.1"));
        assertFalse(helper.has("ban"));
        assertFalse(helper.has("tier.2"));
        assertFalse(helper.has("override"));
    }
}
