package com.coremc.core.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Coin maths and balance persistence. */
class EconomyServiceTest {

    @TempDir
    Path directory;

    private static final UUID PLAYER = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private EconomyService service(final double startingBalance) throws IOException {
        return new EconomyService(store(), startingBalance, Logger.getLogger("test"));
    }

    private YamlEconomyStore store() {
        return new YamlEconomyStore(directory.resolve("balances.yml"), Logger.getLogger("test"));
    }

    @Test
    void unknownPlayersHoldTheStartingBalance() throws IOException {
        assertEquals(100.0, service(100).balance(PLAYER));
        assertEquals(42.5, service(42.5).balance(PLAYER));
    }

    @Test
    void withdrawAndDepositRoundTripThroughDisk() throws IOException {
        final EconomyService first = service(100);
        assertTrue(first.withdraw(PLAYER, 30.25));
        assertEquals(69.75, first.balance(PLAYER));

        // Fresh service over the same file sees the same balance.
        final EconomyService second = service(100);
        assertEquals(69.75, second.balance(PLAYER));
        second.deposit(PLAYER, 0.25);
        assertEquals(70.0, second.balance(PLAYER));

        final EconomyService third = service(100);
        assertEquals(70.0, third.balance(PLAYER));
    }

    @Test
    void insufficientWithdrawChangesNothing() throws IOException {
        final EconomyService service = service(10);
        assertFalse(service.withdraw(PLAYER, 10.01));
        assertEquals(10.0, service.balance(PLAYER));
        assertTrue(service.withdraw(PLAYER, 10.0));
        assertEquals(0.0, service.balance(PLAYER));
    }

    @Test
    void amountsAreRoundedToCents() throws IOException {
        final EconomyService service = service(0);
        service.deposit(PLAYER, 0.005);
        assertEquals(0.01, service.balance(PLAYER));
        service.deposit(PLAYER, 0.004);
        assertEquals(0.01, service.balance(PLAYER));
    }

    @Test
    void emptyStoreFileStartsFresh() throws IOException {
        final YamlEconomyStore store = store();
        assertTrue(store.loadAll().isEmpty());
        store.saveAll(java.util.Map.of(PLAYER, 12.5));
        assertEquals(12.5, store.loadAll().get(PLAYER));
    }
}
