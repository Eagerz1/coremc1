package com.coremc.core.shop;

import java.util.List;
import java.util.UUID;
import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * The Vault economy bridge: CoreMC's coin economy exposed through the
 * Vault API so any Vault-aware plugin (placeholders, shops, scoreboards,
 * …) sees and spends the same coins CoreMC uses. Registered with the
 * Bukkit ServicesManager when the Vault plugin is present; CoreMC stays
 * the single source of truth (balances.yml).
 *
 * <p>Bank operations are unsupported and say so through the standard
 * Vault response types. Names are resolved via online players first,
 * then the server's offline-player lookup.</p>
 */
@SuppressWarnings("deprecation")
public final class VaultEconomy extends AbstractEconomy {

    private final EconomyService economy;

    public VaultEconomy(final EconomyService economy) {
        this.economy = economy;
    }

    // ------------------------------------------------------------------
    // metadata
    // ------------------------------------------------------------------

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "CoreMC";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 2;
    }

    @Override
    public String format(final double amount) {
        return Money.format(amount, "$");
    }

    @Override
    public String currencyNamePlural() {
        return "Coins";
    }

    @Override
    public String currencyNameSingular() {
        return "Coin";
    }

    // ------------------------------------------------------------------
    // name resolution (Vault's String-based API is name-oriented;
    // CoreMC's economy is UUID-based)
    // ------------------------------------------------------------------

    private UUID resolve(final String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        final Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            return online.getUniqueId();
        }
        final OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
        return offline == null ? null : offline.getUniqueId();
    }

    // ------------------------------------------------------------------
    // balances (String variants; AbstractEconomy routes the
    // OfflinePlayer variants through these)
    // ------------------------------------------------------------------

    @Override
    public boolean hasAccount(final String playerName) {
        return resolve(playerName) != null;
    }

    @Override
    public boolean hasAccount(final String playerName, final String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public double getBalance(final String playerName) {
        final UUID playerId = resolve(playerName);
        return playerId == null ? 0.0 : economy.balance(playerId);
    }

    @Override
    public double getBalance(final String playerName, final String world) {
        return getBalance(playerName);
    }

    @Override
    public boolean has(final String playerName, final double amount) {
        final UUID playerId = resolve(playerName);
        return playerId != null && economy.has(playerId, amount);
    }

    @Override
    public boolean has(final String playerName, final String worldName, final double amount) {
        return has(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(final String playerName, final String worldName,
                                          final double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(final String playerName, final double amount) {
        final UUID playerId = resolve(playerName);
        if (playerId == null) {
            return failure(amount, "Player not found: " + playerName);
        }
        if (amount < 0) {
            return failure(amount, "Cannot withdraw negative amounts.");
        }
        if (!economy.has(playerId, amount)) {
            return failure(amount, "Insufficient funds.");
        }
        economy.withdraw(playerId, amount);
        return success(amount, economy.balance(playerId));
    }

    @Override
    public EconomyResponse depositPlayer(final String playerName, final String worldName,
                                         final double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(final String playerName, final double amount) {
        final UUID playerId = resolve(playerName);
        if (playerId == null) {
            return failure(amount, "Player not found: " + playerName);
        }
        if (amount < 0) {
            return failure(amount, "Cannot deposit negative amounts.");
        }
        economy.deposit(playerId, amount);
        return success(amount, economy.balance(playerId));
    }

    @Override
    public boolean createPlayerAccount(final String playerName) {
        // every player has an implicit account (the starting balance
        // applies on first touch), so this is always true for known players
        return resolve(playerName) != null;
    }

    @Override
    public boolean createPlayerAccount(final String playerName, final String worldName) {
        return createPlayerAccount(playerName);
    }

    // ------------------------------------------------------------------
    // OfflinePlayer variants: use the UUID directly (no name round-trip)
    // ------------------------------------------------------------------

    @Override
    public boolean hasAccount(final OfflinePlayer player) {
        return player != null;
    }

    @Override
    public boolean hasAccount(final OfflinePlayer player, final String worldName) {
        return player != null;
    }

    @Override
    public double getBalance(final OfflinePlayer player) {
        return player == null ? 0.0 : economy.balance(player.getUniqueId());
    }

    @Override
    public double getBalance(final OfflinePlayer player, final String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(final OfflinePlayer player, final double amount) {
        return player != null && economy.has(player.getUniqueId(), amount);
    }

    @Override
    public boolean has(final OfflinePlayer player, final String worldName, final double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(final OfflinePlayer player, final double amount) {
        if (player == null) {
            return failure(amount, "Player not found.");
        }
        if (amount < 0) {
            return failure(amount, "Cannot withdraw negative amounts.");
        }
        if (!economy.has(player.getUniqueId(), amount)) {
            return failure(amount, "Insufficient funds.");
        }
        economy.withdraw(player.getUniqueId(), amount);
        return success(amount, economy.balance(player.getUniqueId()));
    }

    @Override
    public EconomyResponse withdrawPlayer(final OfflinePlayer player, final String worldName,
                                          final double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(final OfflinePlayer player, final double amount) {
        if (player == null) {
            return failure(amount, "Player not found.");
        }
        if (amount < 0) {
            return failure(amount, "Cannot deposit negative amounts.");
        }
        economy.deposit(player.getUniqueId(), amount);
        return success(amount, economy.balance(player.getUniqueId()));
    }

    @Override
    public EconomyResponse depositPlayer(final OfflinePlayer player, final String worldName,
                                         final double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public boolean createPlayerAccount(final OfflinePlayer player) {
        return player != null;
    }

    @Override
    public boolean createPlayerAccount(final OfflinePlayer player, final String worldName) {
        return player != null;
    }

    // ------------------------------------------------------------------
    // banks: unsupported
    // ------------------------------------------------------------------

    @Override
    public List<String> getBanks() {
        return List.of();
    }

    @Override
    public EconomyResponse createBank(final String name, final String player) {
        return failure(0, "CoreMC does not support banks.");
    }

    @Override
    public EconomyResponse createBank(final String name, final OfflinePlayer player) {
        return failure(0, "CoreMC does not support banks.");
    }

    @Override
    public EconomyResponse deleteBank(final String name) {
        return failure(0, "CoreMC does not support banks.");
    }

    @Override
    public EconomyResponse bankBalance(final String name) {
        return failure(0, "CoreMC does not support banks.");
    }

    @Override
    public EconomyResponse bankHas(final String name, final double amount) {
        return failure(0, "CoreMC does not support banks.");
    }

    @Override
    public EconomyResponse bankWithdraw(final String name, final double amount) {
        return failure(0, "CoreMC does not support banks.");
    }

    @Override
    public EconomyResponse bankDeposit(final String name, final double amount) {
        return failure(0, "CoreMC does not support banks.");
    }

    @Override
    public EconomyResponse isBankOwner(final String name, final String playerName) {
        return failure(0, "CoreMC does not support banks.");
    }

    @Override
    public EconomyResponse isBankOwner(final String name, final OfflinePlayer player) {
        return failure(0, "CoreMC does not support banks.");
    }

    @Override
    public EconomyResponse isBankMember(final String name, final String playerName) {
        return failure(0, "CoreMC does not support banks.");
    }

    @Override
    public EconomyResponse isBankMember(final String name, final OfflinePlayer player) {
        return failure(0, "CoreMC does not support banks.");
    }

    // ------------------------------------------------------------------
    // responses
    // ------------------------------------------------------------------

    private EconomyResponse success(final double amount, final double balance) {
        return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null);
    }

    private EconomyResponse failure(final double amount, final String message) {
        return new EconomyResponse(amount, 0, EconomyResponse.ResponseType.FAILURE, message);
    }
}
