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
    void configuredRankLadderCapsHigherTierActions() {
        final StaffRank helper = new StaffRank("helper", "Helper", 10,
                Set.of("vanish", "vanish.see", "spectate", "cps", "freeze", "kick", "mute", "unmute", "tier.1", "history"));
        final StaffRank mod = new StaffRank("mod", "Mod", 20,
                Set.of("vanish", "vanish.see", "spectate", "cps", "rotate", "freeze", "kick", "mute", "unmute",
                        "ban", "unban", "tier.1", "tier.2", "history"));
        final StaffRank srmod = new StaffRank("srmod", "SrMod", 30,
                Set.of("ban", "unban", "mute", "unmute", "tier.1", "tier.2", "tier.3", "history", "correction"));
        final StaffRank jrAdmin = new StaffRank("jr_admin", "Jr Admin", 40,
                Set.of("ban", "unban", "mute", "unmute", "tier.1", "tier.2", "tier.3", "tier.4", "history", "correction"));
        final StaffRank admin = new StaffRank("admin", "Admin", 50,
                Set.of("ban", "unban", "mute", "unmute", "tier.1", "tier.2", "tier.3", "tier.4", "tier.5",
                        "history", "correction", "override"));
        final StaffRank manager = new StaffRank("manager", "Manager", 60, Set.of("*"));

        assertTrue(helper.has("mute"));
        assertTrue(helper.has("tier.1"));
        assertFalse(helper.has("ban"));
        assertFalse(helper.has("tier.2"));
        assertFalse(helper.has("override"));

        assertTrue(mod.has("ban"));
        assertTrue(mod.has("tier.2"));
        assertFalse(mod.has("tier.3"));
        assertFalse(mod.has("correction"));

        assertTrue(srmod.has("tier.3"));
        assertTrue(srmod.has("correction"));
        assertFalse(srmod.has("tier.4"));

        assertTrue(jrAdmin.has("tier.4"));
        assertFalse(jrAdmin.has("tier.5"));
        assertFalse(jrAdmin.has("override"));

        assertTrue(admin.has("tier.5"));
        assertTrue(admin.has("override"));
        assertTrue(manager.has("tier.5"));
        assertTrue(manager.has("override"));
    }
}
