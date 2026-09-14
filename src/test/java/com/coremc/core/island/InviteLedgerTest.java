package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pending invite bookkeeping. */
class InviteLedgerTest {

    private static final UUID TARGET = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID OWNER = UUID.fromString("66666666-6666-6666-6666-666666666666");

    @Test
    void storesAndRemovesInvites() {
        final InviteLedger ledger = new InviteLedger();
        ledger.add(TARGET, new InviteLedger.Entry(OWNER, "Inviter", 1000L));

        final InviteLedger.Entry entry = ledger.get(TARGET);
        assertEquals(OWNER, entry.islandOwner());
        assertEquals("Inviter", entry.inviterName());

        ledger.remove(TARGET);
        assertNull(ledger.get(TARGET));
        assertEquals(0, ledger.size());
    }

    @Test
    void latestInviteWins() {
        final InviteLedger ledger = new InviteLedger();
        ledger.add(TARGET, new InviteLedger.Entry(OWNER, "First", 1000L));
        ledger.add(TARGET, new InviteLedger.Entry(UUID.randomUUID(), "Second", 2000L));
        assertEquals("Second", ledger.get(TARGET).inviterName());
        assertEquals(1, ledger.size());
    }

    @Test
    void expiryIsTimestampBased() {
        final InviteLedger ledger = new InviteLedger();
        ledger.add(TARGET, new InviteLedger.Entry(OWNER, "Inviter", 1000L));
        assertFalse(ledger.get(TARGET).expired(999L), "before the deadline");
        assertTrue(ledger.get(TARGET).expired(1000L), "at the deadline");
        assertTrue(ledger.get(TARGET).expired(1001L), "after the deadline");
    }

    @Test
    void purgeDropsOnlyExpiredEntries() {
        final InviteLedger ledger = new InviteLedger();
        final UUID other = UUID.randomUUID();
        ledger.add(TARGET, new InviteLedger.Entry(OWNER, "Old", 1000L));
        ledger.add(other, new InviteLedger.Entry(OWNER, "Fresh", 9000L));

        ledger.purge(5000L);

        assertNull(ledger.get(TARGET), "expired invite purged");
        assertEquals("Fresh", ledger.get(other).inviterName(), "valid invite kept");
        assertEquals(1, ledger.size());
    }
}
