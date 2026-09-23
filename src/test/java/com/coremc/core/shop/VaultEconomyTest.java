package com.coremc.core.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.UUID;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Vault bridge: CoreMC's coins exposed through the Vault economy
 * API. The OfflinePlayer variants are covered directly (no Bukkit
 * server needed); balances stay owned by CoreMC's EconomyService and
 * round to cents exactly like the in-house shop flows.
 */
final class VaultEconomyTest {

    private static OfflinePlayer playerWith(final UUID uuid, final String name) {
        return (OfflinePlayer) Proxy.newProxyInstance(
                OfflinePlayer.class.getClassLoader(),
                new Class<?>[]{OfflinePlayer.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return uuid;
                        case "getName": return name;
                        case "isOnline": return false;
                        case "hashCode": return uuid.hashCode();
                        case "equals": return proxy == args[0];
                        case "toString": return "FakeOfflinePlayer(" + name + ")";
                        default: return null;
                    }
                });
    }

    private static EconomyService economy(final Path tempDir) throws IOException {
        return new EconomyService(
                new YamlEconomyStore(tempDir.resolve("balances.yml"), null),
                100.0, null);
    }

    @Test
    void metadataDescribesCoreMCCoins(@TempDir final Path tempDir) throws IOException {
        final VaultEconomy vault = new VaultEconomy(economy(tempDir));
        assertTrue(vault.isEnabled());
        assertEquals("CoreMC", vault.getName());
        assertFalse(vault.hasBankSupport());
        assertEquals(2, vault.fractionalDigits());
        assertEquals("Coin", vault.currencyNameSingular());
        assertEquals("Coins", vault.currencyNamePlural());
        assertEquals("$12.50", vault.format(12.5));
        assertEquals("$0.00", vault.format(0));
        assertTrue(vault.getBanks().isEmpty());
    }

    @Test
    void unknownPlayersHoldTheStartingBalance(@TempDir final Path tempDir) throws IOException {
        final VaultEconomy vault = new VaultEconomy(economy(tempDir));
        final OfflinePlayer stranger = playerWith(UUID.randomUUID(), "Stranger");
        assertTrue(vault.hasAccount(stranger));
        assertEquals(100.0, vault.getBalance(stranger));
        assertTrue(vault.has(stranger, 100.0));
        assertFalse(vault.has(stranger, 100.01));
        assertTrue(vault.createPlayerAccount(stranger));
    }

    @Test
    void depositWithdrawAndOverdraft(@TempDir final Path tempDir) throws IOException {
        final UUID playerId = UUID.randomUUID();
        final OfflinePlayer player = playerWith(playerId, "Rich");
        final EconomyService backing = economy(tempDir);
        backing.deposit(playerId, 2399.25); // on top of the 100 starting balance

        final VaultEconomy vault = new VaultEconomy(backing);
        assertEquals(2499.25, vault.getBalance(player));

        final EconomyResponse deposit = vault.depositPlayer(player, 10.0);
        assertTrue(deposit.transactionSuccess());
        assertEquals(10.0, deposit.amount);
        assertEquals(2509.25, deposit.balance);
        assertEquals(2509.25, vault.getBalance(player));

        final EconomyResponse withdraw = vault.withdrawPlayer(player, 9.25);
        assertTrue(withdraw.transactionSuccess());
        assertEquals(2500.0, withdraw.balance);

        final EconomyResponse overdraft = vault.withdrawPlayer(player, 999999.0);
        assertFalse(overdraft.transactionSuccess());
        assertEquals(EconomyResponse.ResponseType.FAILURE, overdraft.type);
        assertTrue(overdraft.errorMessage.contains("Insufficient"));
        assertEquals(2500.0, vault.getBalance(player), "failed withdraw must not change the balance");

        final EconomyResponse negative = vault.depositPlayer(player, -5.0);
        assertFalse(negative.transactionSuccess());
        assertEquals(2500.0, vault.getBalance(player));
    }

    @Test
    void worldVariantsAreGlobal(@TempDir final Path tempDir) throws IOException {
        final UUID playerId = UUID.randomUUID();
        final OfflinePlayer player = playerWith(playerId, "Anywhere");
        final EconomyService backing = economy(tempDir);
        backing.deposit(playerId, 500.0); // balance is now 600 (100 starting + 500)
        final VaultEconomy vault = new VaultEconomy(backing);
        assertEquals(600.0, vault.getBalance(player, "world_nether"));
        assertTrue(vault.has(player, "world_the_end", 600.0));
        assertTrue(vault.depositPlayer(player, "overworld", 1.0).transactionSuccess());
        assertEquals(601.0, vault.getBalance(player));
    }

    @Test
    void bankOperationsAreUnsupported(@TempDir final Path tempDir) throws IOException {
        final VaultEconomy vault = new VaultEconomy(economy(tempDir));
        final OfflinePlayer owner = playerWith(UUID.randomUUID(), "Owner");
        assertFalse(vault.createBank("guild", owner).transactionSuccess());
        assertFalse(vault.bankBalance("guild").transactionSuccess());
        assertFalse(vault.bankDeposit("guild", 10.0).transactionSuccess());
        assertFalse(vault.bankWithdraw("guild", 10.0).transactionSuccess());
        assertFalse(vault.deleteBank("guild").transactionSuccess());
        assertFalse(vault.isBankOwner("guild", owner).transactionSuccess());
        assertFalse(vault.isBankMember("guild", owner).transactionSuccess());
    }
}
