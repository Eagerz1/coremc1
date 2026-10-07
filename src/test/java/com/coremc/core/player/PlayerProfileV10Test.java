package com.coremc.core.player;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerProfileV10Test {

    private static final UUID ISLAND = UUID.nameUUIDFromBytes("progression-island".getBytes());

    @Test
    void islandRewardClaimsRoundTripAndRemainClaimOnce() {
        final PlayerProfile profile = PlayerProfile.createNew(
                UUID.nameUUIDFromBytes("progression-player".getBytes()), "Tester", 0L);

        assertTrue(profile.claimIslandMastery(ISLAND, "miner"));
        assertFalse(profile.claimIslandMastery(ISLAND, "miner"));
        assertTrue(profile.claimEquipmentSetPiece(ISLAND, "slayer", "helmet"));
        assertFalse(profile.claimEquipmentSetPiece(ISLAND, "slayer", "helmet"));

        final PlayerProfile loaded = PlayerProfile.fromMap(profile.uuid(), profile.toMap());
        assertTrue(loaded.islandMasteryClaimed(ISLAND, "miner"));
        assertTrue(loaded.equipmentSetPieceClaimed(ISLAND, "slayer", "helmet"));
        assertFalse(loaded.equipmentSetPieceClaimed(ISLAND, "slayer", "boots"));
    }

    @Test
    void olderProfilesDefaultToNoProgressionClaims() {
        final PlayerProfile profile = PlayerProfile.createNew(
                UUID.nameUUIDFromBytes("old-progression-player".getBytes()), "Old", 0L);
        final PlayerProfile loaded = PlayerProfile.fromMap(profile.uuid(),
                java.util.Map.of("schema-version", 8, "username", "Old"));

        assertFalse(loaded.islandMasteryClaimed(ISLAND, "miner"));
        assertFalse(loaded.equipmentSetPieceClaimed(ISLAND, "slayer", "helmet"));
    }
}
