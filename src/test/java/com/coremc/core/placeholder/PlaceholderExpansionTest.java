package com.coremc.core.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.coremc.core.config.CoreConfig;
import com.coremc.core.essence.EssenceManager;
import com.coremc.core.essence.EssenceType;
import com.coremc.core.shop.EconomyService;
import com.coremc.core.shop.YamlEconomyStore;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The %coremc_*% and %x_currency% expansions resolve balances for an
 * OfflinePlayer (proxied, no Bukkit server needed), comma-format every
 * number, and answer "0" for server-wide (null player) contexts.
 */
class PlaceholderExpansionTest {

    @TempDir
    Path directory;

    private static OfflinePlayer playerWith(final UUID uuid, final String name) {
        return (OfflinePlayer) Proxy.newProxyInstance(
                OfflinePlayer.class.getClassLoader(),
                new Class<?>[]{OfflinePlayer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> name;
                    case "isOnline" -> false;
                    case "toString" -> "FakeOfflinePlayer(" + name + ")";
                    default -> null;
                });
    }

    private EssenceManager essences() throws IOException {
        final EssenceManager manager = new EssenceManager(
                new com.coremc.core.essence.YamlEssenceStore(
                        directory.resolve("essence-balances.yml"), Logger.getLogger("test")),
                Logger.getLogger("test"));
        final UUID player = UUID.fromString("77777777-7777-7777-7777-777777777777");
        manager.give(player, EssenceType.SLAYER, 1500);
        manager.give(player, EssenceType.MINING, 425);
        manager.give(player, EssenceType.FARMING, 75);
        manager.addKills(player, 12345);
        return manager;
    }

    private EconomyService economy() throws IOException {
        final EconomyService economy = new EconomyService(
                new YamlEconomyStore(directory.resolve("balances.yml"), Logger.getLogger("test")),
                500000, Logger.getLogger("test"));
        return economy;
    }

    @Test
    void essencePlaceholdersAreCommaFormatted() throws IOException {
        final CoremcExpansion expansion = new CoremcExpansion(essences(), economy());
        final UUID id = UUID.fromString("77777777-7777-7777-7777-777777777777");
        final OfflinePlayer player = playerWith(id, "Tester");
        assertEquals("1,500", expansion.onRequest(player, "slayer_essence"));
        assertEquals("425", expansion.onRequest(player, "mining_essence"));
        assertEquals("75", expansion.onRequest(player, "farming_essence"));
        assertEquals("2,000", expansion.onRequest(player, "essence_total"));
        assertEquals("12,345", expansion.onRequest(player, "mob_kills"));
        assertEquals("500,000", expansion.onRequest(player, "coins"));
        // case-insensitive params, unknown params answer null
        assertEquals("1,500", expansion.onRequest(player, "SLAYER_ESSENCE"));
        assertNull(expansion.onRequest(player, "banana"));
    }

    @Test
    void serverWideContextAnswersZero() throws IOException {
        final CoremcExpansion expansion = new CoremcExpansion(essences(), economy());
        assertEquals("0", expansion.onRequest(null, "slayer_essence"));
        assertNull(expansion.onRequest(null, null));
    }

    @Test
    void xCurrencyDefaultsToTheTotal() throws IOException {
        final XCurrencyExpansion expansion = new XCurrencyExpansion(essences(),
                configWith("total"));
        final UUID id = UUID.fromString("77777777-7777-7777-7777-777777777777");
        assertEquals("2,000", expansion.onRequest(playerWith(id, "Tester"), "currency"));
        assertEquals("2,000", expansion.onRequest(playerWith(id, "Tester"), "CURRENCY"));
        assertNull(expansion.onRequest(playerWith(id, "Tester"), "banana"));
    }

    @Test
    void xCurrencyCanBeConfiguredToAnyEssence() throws IOException {
        final UUID id = UUID.fromString("77777777-7777-7777-7777-777777777777");
        final OfflinePlayer player = playerWith(id, "Tester");
        final EssenceManager manager = essences();
        assertEquals("1,500", new XCurrencyExpansion(manager, configWith("slayer"))
                .onRequest(player, "currency"));
        assertEquals("425", new XCurrencyExpansion(manager, configWith("mining"))
                .onRequest(player, "currency"));
        assertEquals("75", new XCurrencyExpansion(manager, configWith("farming"))
                .onRequest(player, "currency"));
    }

    @Test
    void expansionMetadataMatchesTheSpec() throws IOException {
        assertEquals("coremc", new CoremcExpansion(essences(), economy()).getIdentifier());
        assertEquals("x", new XCurrencyExpansion(essences(), configWith("total")).getIdentifier());
        // both survive PAPI reloads
        assertEquals(true, new CoremcExpansion(essences(), economy()).persist());
        assertEquals(true, new XCurrencyExpansion(essences(), configWith("total")).persist());
    }

    /** CoreConfig parses a full config.yml document; only x-currency matters here. */
    private static CoreConfig configWith(final String xCurrency) {
        final CoreConfig config = new CoreConfig(null);
        final org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        try {
            yaml.loadFromString("x-currency: " + xCurrency + "\nisland: {world: coremc_islands}");
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(yaml);
        return config;
    }
}
