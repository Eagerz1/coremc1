package com.coremc.core.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** One player's permanent Collection state. */
class CollectionProfileTest {

    @Test
    void amountsAreKeyedCaseInsensitivelyAndOnlyGrowOnAdd() {
        final CollectionProfile profile = new CollectionProfile();
        assertEquals(0, profile.amount("cobblestone"));
        assertEquals(10, profile.add("Cobblestone", 10));
        assertEquals(10, profile.amount(" COBBLESTONE "));
        assertEquals(10, profile.add("cobblestone", 0));
        assertEquals(10, profile.add("cobblestone", -5));
        assertEquals(35, profile.add("cobblestone", 25));
    }

    @Test
    void settingAnExactTotalIsTheAdminPathAndZeroClearsIt() {
        final CollectionProfile profile = new CollectionProfile();
        profile.set("iron", 500);
        assertEquals(500, profile.amount("iron"));
        profile.set("iron", 0);
        assertEquals(0, profile.amount("iron"));
        assertTrue(profile.amounts().isEmpty());
        profile.set("iron", -3);
        assertEquals(0, profile.amount("iron"));
    }

    @Test
    void discoveriesRememberTheFirstFind() {
        final CollectionProfile profile = new CollectionProfile();
        assertFalse(profile.discovery("relic").discovered());
        profile.discover("Relic", 1, 5_000L);
        profile.discover("relic", 2, 9_000L);
        assertEquals(3, profile.discovery("relic").count());
        assertEquals(5_000L, profile.discovery("relic").first());
        profile.putDiscovery("other", DiscoveryRecord.NONE);
        assertFalse(profile.discovery("other").discovered());
        assertEquals(1, profile.discoveries().size());
    }

    @Test
    void claimsAndUnlocksAreExactlyOnce() {
        final CollectionProfile profile = new CollectionProfile();
        assertFalse(profile.claimed("cobblestone", 1));
        assertTrue(profile.claim("cobblestone", 1));
        assertFalse(profile.claim("cobblestone", 1));
        assertTrue(profile.claimed("COBBLESTONE", 1));
        assertFalse(profile.claimed("cobblestone", 2));
        assertTrue(profile.unlock("title:stonebreaker"));
        assertFalse(profile.unlock("TITLE:STONEBREAKER"));
        assertTrue(profile.unlocked("title:stonebreaker"));
        assertEquals(1, profile.claims().size());
        assertEquals(1, profile.unlocks().size());
    }

    @Test
    void claimKeysAreStableAndTiersNeverGoBelowOne() {
        assertEquals("cobblestone:1", CollectionProfile.claimKey("Cobblestone", 1));
        assertEquals("cobblestone:1", CollectionProfile.claimKey("cobblestone", 0));
        assertEquals("cobblestone:1", CollectionProfile.claimKey("cobblestone", -4));
        assertEquals("iron:7", CollectionProfile.claimKey(" IRON ", 7));
    }

    @Test
    void storedClaimsAreRestoredAndBlanksIgnored() {
        final CollectionProfile profile = new CollectionProfile();
        profile.putClaim("  Cobblestone:3 ");
        profile.putClaim("   ");
        profile.putClaim(null);
        assertTrue(profile.claimed("cobblestone", 3));
        assertEquals(1, profile.claims().size());
    }

    @Test
    void anUntouchedProfileIsEmptySoItIsNeverSaved() {
        final CollectionProfile profile = new CollectionProfile();
        assertTrue(profile.empty());
        profile.add("cobblestone", 1);
        assertFalse(profile.empty());
    }
}
