package com.coremc.core.rank;

import com.coremc.core.config.MessageService;
import com.coremc.core.shop.EconomyService;
import com.coremc.core.shop.Money;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The rank system's brain: who holds which rank, the perks that apply
 * right now (/fly, /echest, the sell multiplier, season payouts and
 * River keys), and the upgrade path Core → Core+ → Core++ bought
 * with coins. Skytokens and the chat-colour commands build on this
 * later; the perk flags already live in ranks.yml.
 */
public final class RankService {

    private final JavaPlugin plugin;
    private final RankConfig config;
    private final EconomyService economy;
    private final MessageService messages;
    private final YamlRankStore store;
    private final Logger logger;

    private int season = 1;
    private final Map<UUID, YamlRankStore.PlayerRank> players = new java.util.LinkedHashMap<>();

    public RankService(final JavaPlugin plugin, final RankConfig config,
                       final EconomyService economy, final MessageService messages,
                       final YamlRankStore store) {
        this.plugin = plugin;
        this.config = config;
        this.economy = economy;
        this.messages = messages;
        this.store = store;
        this.logger = plugin.getLogger();
    }

    /** Loads ranks-data.yml. */
    public void load() {
        try {
            final YamlRankStore.StoreData data = store.load();
            this.season = data.season;
            this.players.putAll(data.players);
        } catch (final IOException exception) {
            logger.severe("Could not load ranks-data.yml: " + exception.getMessage());
        }
    }

    private void persist() {
        try {
            store.save(season, players);
        } catch (final IOException exception) {
            logger.severe("Could not save ranks-data.yml: " + exception.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // lookups
    // ------------------------------------------------------------------

    /** The player's rank definition, or null when unranked (or ranks disabled). */
    public RankConfig.RankDef rankOf(final UUID playerId) {
        if (!config.enabled()) {
            return null;
        }
        final YamlRankStore.PlayerRank state = players.get(playerId);
        return state == null ? null : config.byId(state.rankId);
    }

    /** Whether the player holds any rank. */
    public boolean hasRank(final UUID playerId) {
        return rankOf(playerId) != null;
    }

    /** Shop sell multiplier of the player (1.0 when unranked or ranks disabled). */
    public double multiplierOf(final UUID playerId) {
        final RankConfig.RankDef rank = rankOf(playerId);
        return rank == null ? 1.0 : rank.moneyMultiplier();
    }

    /** The player's River key count. */
    public int riverKeys(final UUID playerId) {
        final YamlRankStore.PlayerRank state = players.get(playerId);
        return state == null ? 0 : state.riverKeys;
    }

    /** The current season number. */
    public int season() {
        return season;
    }

    private YamlRankStore.PlayerRank stateOf(final Player player) {
        return players.computeIfAbsent(player.getUniqueId(),
                id -> new YamlRankStore.PlayerRank(player.getName(), "", 0, 0));
    }

    // ------------------------------------------------------------------
    // buying + admin
    // ------------------------------------------------------------------

    /** Buys the next rank on the ladder with coins. */
    public void buyNext(final Player player) {
        if (!config.enabled()) {
            messages.sendPrefixed(player, "rank.unavailable");
            return;
        }
        final RankConfig.RankDef current = rankOf(player.getUniqueId());
        final RankConfig.RankDef target = current == null ? config.first() : config.nextOf(current);
        if (target == null) {
            messages.sendPrefixed(player, "rank.maxed", Map.of(
                    "rank", current.name()));
            return;
        }
        if (!economy.has(player.getUniqueId(), target.price())) {
            messages.sendPrefixed(player, "rank.cannot-afford", Map.of(
                    "rank", target.name(),
                    "cost", Money.format(target.price(), "$"),
                    "balance", Money.format(economy.balance(player.getUniqueId()), "$")));
            return;
        }
        economy.withdraw(player.getUniqueId(), target.price());
        grant(player, target);
    }

    /** Admin: sets (or clears with "none") a player's rank. */
    public void setRank(final Player target, final String rankId) {
        if (!config.enabled()) {
            // fall through: "none" must still work to clear a stale rank
        }
        if ("none".equalsIgnoreCase(rankId)) {
            final YamlRankStore.PlayerRank state = players.get(target.getUniqueId());
            if (state != null && !state.rankId.isEmpty()) {
                stopFlight(target);
            }
            if (state != null) {
                state.rankId = "";
            }
            persist();
            return;
        }
        final RankConfig.RankDef rank = config.byId(rankId);
        if (rank == null) {
            // the command layer validates the id before calling; keep silent here
            return;
        }
        grant(target, rank);
    }

    /** Grants a rank: state, River keys (only when climbing the ladder), persistence. */
    private void grant(final Player player, final RankConfig.RankDef rank) {
        final YamlRankStore.PlayerRank state = stateOf(player);
        final RankConfig.RankDef current = config.byId(state.rankId);
        final int currentIndex = current == null ? -1 : config.indexOf(current);
        final int newIndex = config.indexOf(rank);
        state.name = player.getName();
        state.rankId = rank.id();
        // River keys are granted on acquisition of a higher rank — buying
        // Core after Core+ (or re-buying your own rank) never re-grants.
        if (newIndex > currentIndex) {
            state.riverKeys += rank.riverKeys();
        }
        // No payout for the season the rank was acquired in; the next
        // season start pays out.
        state.lastPayout = Math.max(state.lastPayout, season);
        persist();
        plugin.getLogger().info(player.getName() + " now holds rank " + rank.id()
                + " (river keys: " + state.riverKeys + ").");
        messages.sendPrefixed(player, "rank.bought", Map.of(
                "rank", rank.name(),
                "keys", String.valueOf(rank.riverKeys()),
                "multiplier", "x" + trim(rank.moneyMultiplier()),
                "season-money", Money.format(rank.seasonMoney(), "$")));
    }

    private static String trim(final double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    // ------------------------------------------------------------------
    // perks: /fly, /echest
    // ------------------------------------------------------------------

    /** Toggles survival flight for a ranked player. */
    public void toggleFly(final Player player) {
        final RankConfig.RankDef rank = rankOf(player.getUniqueId());
        if (rank == null) {
            final RankConfig.RankDef required = config.enabled() ? config.first() : null;
            messages.sendPrefixed(player, "rank.need-rank", Map.of(
                    "rank", required == null ? "Core" : required.name()));
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            messages.sendPrefixed(player, "rank.fly-no-gamemode");
            return;
        }
        if (player.getAllowFlight()) {
            player.setFlying(false);
            player.setAllowFlight(false);
            messages.sendPrefixed(player, "rank.fly-disabled");
        } else {
            player.setAllowFlight(true);
            player.setFlying(true);
            messages.sendPrefixed(player, "rank.fly-enabled");
        }
    }

    /** Opens the player's ender chest for a ranked player. */
    public void openEchest(final Player player) {
        final RankConfig.RankDef rank = rankOf(player.getUniqueId());
        if (rank == null) {
            final RankConfig.RankDef required = config.enabled() ? config.first() : null;
            messages.sendPrefixed(player, "rank.need-rank", Map.of(
                    "rank", required == null ? "Core" : required.name()));
            return;
        }
        player.openInventory(player.getEnderChest());
    }

    /** Removes flight from a player (used when a rank is lost). */
    private void stopFlight(final Player player) {
        if (player.isOnline() && player.getAllowFlight()
                && player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    // ------------------------------------------------------------------
    // seasons
    // ------------------------------------------------------------------

    /** Admin: starts a new season — online ranked players are paid out immediately. */
    public void setSeason(final int newSeason) {
        this.season = Math.max(1, newSeason);
        persist();
        for (final Player online : plugin.getServer().getOnlinePlayers()) {
            payoutIfDue(online);
        }
    }

    /** Join hook: enforce the flight rule, then pay out any owed season money. */
    public void onJoin(final Player player) {
        if (!hasRank(player.getUniqueId())) {
            // ranks removed while the player was offline must not leave flight on
            stopFlight(player);
        }
        payoutIfDue(player);
    }

    private void payoutIfDue(final Player player) {
        final RankConfig.RankDef rank = rankOf(player.getUniqueId());
        if (rank == null || rank.seasonMoney() <= 0) {
            return;
        }
        final YamlRankStore.PlayerRank state = players.get(player.getUniqueId());
        if (state == null || state.lastPayout >= season) {
            return;
        }
        state.lastPayout = season;
        economy.deposit(player.getUniqueId(), rank.seasonMoney());
        persist();
        messages.sendPrefixed(player, "rank.season-payout", Map.of(
                "season", String.valueOf(season),
                "rank", rank.name(),
                "money", Money.format(rank.seasonMoney(), "$")));
    }
}
