package com.coremc.core;

import com.coremc.core.config.CoreConfig;
import com.coremc.core.config.MessageService;
import com.coremc.core.island.BuffListener;
import com.coremc.core.rank.EchestCommand;
import com.coremc.core.rank.FlyCommand;
import com.coremc.core.rank.RankCommand;
import com.coremc.core.rank.RankConfig;
import com.coremc.core.rank.RankListener;
import com.coremc.core.rank.RankService;
import com.coremc.core.rank.SeasonCommand;
import com.coremc.core.rank.YamlRankStore;
import com.coremc.core.island.IslandBuffService;
import com.coremc.core.island.IslandCommand;
import com.coremc.core.island.IslandPointsService;
import com.coremc.core.island.IsTopBoardGui;
import com.coremc.core.island.IsTopGui;
import com.coremc.core.island.IsTopListener;
import com.coremc.core.island.IslandTopRewards;
import com.coremc.core.island.PointsListener;
import com.coremc.core.island.IslandGui;
import com.coremc.core.island.IslandMenuListener;
import com.coremc.core.island.IslandUpgradeConfig;
import com.coremc.core.island.IslandUpgradeService;
import com.coremc.core.island.IslandListener;
import com.coremc.core.island.IslandService;
import com.coremc.core.island.SchematicService;
import com.coremc.core.gens.GensCommand;
import com.coremc.core.gens.GensMenuGui;
import com.coremc.core.gens.GensMenuListener;
import com.coremc.core.gens.GeneratorConfig;
import com.coremc.core.gens.GeneratorHolograms;
import com.coremc.core.gens.GeneratorListener;
import com.coremc.core.gens.GeneratorManageGui;
import com.coremc.core.gens.GeneratorManageListener;
import com.coremc.core.gens.GeneratorService;
import com.coremc.core.gens.YamlGeneratorDataStore;
import com.coremc.core.essence.EssenceCommand;
import com.coremc.core.essence.EssenceConfig;
import com.coremc.core.essence.EssenceListener;
import com.coremc.core.essence.EssenceManager;
import com.coremc.core.essence.PlacedBlockTracker;
import com.coremc.core.essence.YamlEssenceStore;
import com.coremc.core.placeholder.CoremcExpansion;
import com.coremc.core.placeholder.XCurrencyExpansion;
import com.coremc.core.shop.EconomyService;
import com.coremc.core.shop.VaultEconomy;
import com.coremc.core.island.YamlBuffStore;
import com.coremc.core.spawner.SpawnerCommand;
import com.coremc.core.spawner.SpawnerUpgradeGui;
import com.coremc.core.spawner.SpawnerUpgradeListener;
import com.coremc.core.spawner.SpawnerMenuGui;
import com.coremc.core.spawner.SpawnerMenuListener;
import com.coremc.core.spawner.SpawnerConfig;
import com.coremc.core.spawner.SpawnerHolograms;
import com.coremc.core.spawner.SpawnerListener;
import com.coremc.core.spawner.SpawnerService;
import com.coremc.core.spawner.YamlSpawnerDataStore;
import com.coremc.core.shop.ShopCommand;
import com.coremc.core.shop.SellCommand;
import com.coremc.core.tebex.GiftcardCommand;
import com.coremc.core.tebex.GiftcardStore;
import com.coremc.core.tebex.TebexClient;
import com.coremc.core.tebex.TebexConfig;
import com.coremc.core.shop.SellGui;
import com.coremc.core.shop.SellListener;
import com.coremc.core.shop.ShopConfig;
import com.coremc.core.shop.ShopGui;
import com.coremc.core.shop.ShopListener;
import com.coremc.core.shop.YamlEconomyStore;
import com.coremc.core.world.IslandWorldService;
import java.nio.file.Path;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * CoreMC — Skyblock core.
 *
 * Bootstrap order matters: config and messages first, then the island
 * world (created as a void world on first boot), then the island
 * registry, command and listeners. Everything is main-thread only.
 */
public final class CoreMCPlugin extends JavaPlugin {

    private CoreConfig coreConfig;
    private MessageService messages;
    private IslandWorldService worlds;
    private SchematicService schematics;
    private IslandService islands;
    private ShopConfig shopConfig;
    private EconomyService economy;
    private ShopGui shopGui;
    private SellListener sellListener;
    private IslandPointsService islandPoints;
    private TebexConfig tebexConfig;
    private TebexClient tebexClient;
    private GiftcardStore giftcardStore;
    private IslandTopRewards islandTopRewards;
    private SpawnerConfig spawnerConfig;
    private SpawnerService spawnerService;
    private EssenceManager essenceManager;
    private IslandUpgradeConfig upgradeConfig;
    private IslandBuffService buffService;
    private RankConfig rankConfig;
    private RankService rankService;
    private GeneratorConfig generatorConfig;
    private GeneratorService generatorService;

    @Override
    public void onEnable() {
        this.coreConfig = new CoreConfig(this);
        this.coreConfig.load();

        this.messages = new MessageService(this);
        this.messages.load();

        this.schematics = new SchematicService(this);
        this.schematics.extractDefault();

        this.worlds = new IslandWorldService(coreConfig);
        try {
            worlds.islandWorld();
        } catch (final RuntimeException exception) {
            getLogger().severe("Could not create the island world: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Island upgrades + buffs (upgrades.yml). A broken file disables the
        // upgrade system with one loud log line — member caps lift (the old
        // behaviour) and menus show "unavailable" — but never the plugin.
        this.upgradeConfig = IslandUpgradeConfig.disabled();
        try {
            final IslandUpgradeConfig parsed = new IslandUpgradeConfig(this, coreConfig);
            parsed.load();
            this.upgradeConfig = parsed;
        } catch (final RuntimeException exception) {
            getLogger().severe("Island upgrades disabled — " + exception.getMessage());
        }

        this.islands = new IslandService(this, coreConfig, messages, worlds, schematics, upgradeConfig);
        this.islands.load();

        final PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new IslandListener(islands, messages, worlds), this);

        // Shop: catalogue (shop.yml) + coin balances (balances.yml). A broken
        // catalogue or unreadable balance file disables /shop with a loud log
        // line but never the plugin itself.
        this.shopConfig = new ShopConfig(this);
        this.economy = null;
        try {
            shopConfig.load();
            this.economy = new EconomyService(
                    new YamlEconomyStore(Path.of(getDataFolder().getPath(), "balances.yml"), getLogger()),
                    shopConfig.startingBalance(),
                    getLogger());
        } catch (final RuntimeException | java.io.IOException exception) {
            getLogger().severe("Shop disabled — " + exception.getMessage());
            this.shopConfig = new ShopConfig(this);
            this.economy = null;
        }
        this.shopGui = new ShopGui(shopConfig, economy);
        final PluginCommand shopCommand = getCommand("shop");
        if (shopCommand != null) {
            shopCommand.setExecutor(new ShopCommand(shopConfig, shopGui, messages));
        } else {
            getLogger().severe("Command 'shop' missing from plugin.yml — /shop will not work.");
        }
        final SellGui sellGui = new SellGui(messages);
        registerSimpleCommand("sell", new SellCommand(economy, sellGui, messages));

        // Tebex webstore: gift cards (/giftcard, /gc). Off until the owner
        // fills in the Plugin API secret key in tebex.yml.
        this.tebexConfig = new TebexConfig(this);
        this.giftcardStore = new GiftcardStore(
                Path.of(getDataFolder().getPath(), "giftcards.yml"));
        try {
            tebexConfig.load();
            giftcardStore.load();
        } catch (final RuntimeException | java.io.IOException exception) {
            getLogger().severe("Tebex integration disabled — " + exception.getMessage());
            tebexConfig = new TebexConfig(this);
        }
        this.tebexClient = new TebexClient(tebexConfig);
        registerSimpleCommand("giftcard", new GiftcardCommand(this, tebexConfig,
                tebexClient, giftcardStore, messages));
        if (tebexConfig.enabled()) {
            getLogger().info("Tebex integration active — /giftcard is live.");
        }

        // Ranks (Core / Core+ / Core++): /fly, /echest, the sell
        // multiplier, season payouts and River keys. A broken ranks.yml
        // disables the ladder loudly (no ranks, no perks, x1.0 sells).
        this.rankConfig = RankConfig.disabled();
        try {
            final RankConfig parsed = new RankConfig(this);
            parsed.load();
            this.rankConfig = parsed;
        } catch (final RuntimeException exception) {
            getLogger().severe("Ranks disabled — " + exception.getMessage());
        }
        this.rankService = null;
        if (economy != null && rankConfig.enabled()) {
            this.rankService = new RankService(this, rankConfig, economy, messages,
                    new YamlRankStore(
                            Path.of(getDataFolder().getPath(), "ranks-data.yml"), getLogger()));
            rankService.load();
            pluginManager.registerEvents(new RankListener(rankService), this);
        } else if (economy == null) {
            getLogger().severe("Ranks disabled — no economy (the shop failed to load).");
        }

        if (economy != null) {
            pluginManager.registerEvents(
                    new ShopListener(shopConfig, economy, shopGui, messages, rankService), this);
            this.sellListener = new SellListener(shopConfig, economy, messages, rankService);
            pluginManager.registerEvents(sellListener, this);
            registerVaultBridge();
        }

        registerSimpleCommand("rank", new RankCommand(rankConfig,
                rankService == null ? null : rankService, messages));
        registerSimpleCommand("fly", new FlyCommand(
                rankService == null ? null : rankService, messages));
        registerSimpleCommand("echest", new EchestCommand(
                rankService == null ? null : rankService, messages));

        // Essence: virtual balances (essence-balances.yml) earned by active
        // play — Slayer for manual mob kills, Mining and Farming for blocks.
        // Write-through persistence, exactly like the economy. The store file
        // must NOT be named essences.yml: that is the earning-RULES config
        // (EssenceConfig) — sharing the name made the first save overwrite
        // the rules and break earning on the next boot.
        this.essenceManager = null;
        try {
            this.essenceManager = new EssenceManager(new YamlEssenceStore(
                    Path.of(getDataFolder().getPath(), "essence-balances.yml"), getLogger()), getLogger());
        } catch (final java.io.IOException exception) {
            getLogger().severe("Essence disabled — storage unreadable: " + exception.getMessage());
        }
        final EssenceConfig essenceConfig = new EssenceConfig(this);
        final PlacedBlockTracker placedBlocks =
                new PlacedBlockTracker(Path.of(getDataFolder().getPath(), "placed-blocks.yml"), getLogger());
        placedBlocks.load();

        // Spawners: progression config (spawners.yml) + placed spawner
        // registry (spawners-data.yml). A broken config disables the
        // spawner system with a loud log line, never the plugin.
        this.spawnerConfig = new SpawnerConfig(this);
        this.spawnerService = null;
        try {
            spawnerConfig.load();
            if (economy == null) {
                getLogger().severe("Spawners disabled — no economy (the shop failed to load).");
            } else if (essenceManager == null) {
                getLogger().severe("Spawners disabled — no essence storage.");
            } else {
                this.spawnerService = new SpawnerService(
                        this, spawnerConfig,
                        new YamlSpawnerDataStore(
                                Path.of(getDataFolder().getPath(), "spawners-data.yml"), getLogger()),
                        economy, essenceManager, messages, islands);
                spawnerService.load();
                final SpawnerHolograms spawnerHolograms =
                        new SpawnerHolograms(this, spawnerService);
                spawnerService.attach(spawnerHolograms);
                pluginManager.registerEvents(spawnerHolograms, this);
                pluginManager.registerEvents(new SpawnerListener(spawnerService, messages), this);
                islands.onDelete(spawnerService::onIslandDeleted);
            }
        } catch (final RuntimeException exception) {
            getLogger().severe("Spawners disabled — " + exception.getMessage());
            this.spawnerService = null;
        }

        // Essence earning + the /essence command run independently of the
        // spawner system (mining/farming pay even with spawners off).
        if (essenceManager != null) {
            try {
                essenceConfig.load();
                pluginManager.registerEvents(
                        new EssenceListener(essenceManager, essenceConfig, placedBlocks, spawnerService),
                        this);
            } catch (final RuntimeException exception) {
                getLogger().severe("Essence earning disabled — " + exception.getMessage());
            }
            registerSimpleCommand("essence", new EssenceCommand(essenceManager, messages));
        }

        // Menus: /is opens the double-chest island menu (members-only), its
        // sub-menus (upgrades, buffs, members, invite) are small chests, and
        // /spawner opens the spawner menu. Purchases reuse the command flows.
        this.buffService = new IslandBuffService(this,
                new YamlBuffStore(Path.of(getDataFolder().getPath(), "buffs.yml"), getLogger()),
                spawnerService, upgradeConfig);
        buffService.load();
        islands.onDelete(buffService::onIslandDeleted);
        pluginManager.registerEvents(new BuffListener(islands, buffService, upgradeConfig), this);

        // Island points: blocks broken/placed, play time and upgrades earn
        // points for the island top leaderboards (Solos / Duos / Teams).
        this.islandPoints = new IslandPointsService(
                Path.of(getDataFolder().getPath(), "island-points.yml"));
        try {
            islandPoints.load();
        } catch (final java.io.IOException exception) {
            getLogger().severe("Island points start fresh — " + exception.getMessage());
        }
        islands.onDelete(islandPoints::remove);
        pluginManager.registerEvents(new PointsListener(islands, islandPoints), this);
        // +1 point per 5 minutes online, +flush of the points file every minute
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (final org.bukkit.entity.Player online : getServer().getOnlinePlayers()) {
                final var island = islands.islandOf(online.getUniqueId());
                if (island != null) {
                    islandPoints.add(island, IslandPointsService.PLAY_POINTS);
                }
            }
        }, 20L * 60 * 5, 20L * 60 * 5);
        getServer().getScheduler().runTaskTimer(this, () -> {
            try {
                islandPoints.flush();
            } catch (final java.io.IOException exception) {
                getLogger().warning("Could not save island points: " + exception.getMessage());
            }
        }, 20L * 60, 20L * 60);

        final IslandUpgradeService upgradeService = new IslandUpgradeService(
                upgradeConfig, islands, buffService, economy, messages, islandPoints);
        SpawnerMenuGui spawnerMenuGui = null;
        SpawnerUpgradeGui spawnerUpgradeGui = null;
        if (spawnerService != null) {
            spawnerMenuGui = new SpawnerMenuGui(spawnerConfig, spawnerService, islands, economy);
            pluginManager.registerEvents(
                    new SpawnerMenuListener(spawnerConfig, spawnerService, spawnerMenuGui), this);
            spawnerUpgradeGui = new SpawnerUpgradeGui(
                    spawnerService, economy, essenceManager, spawnerConfig);
            pluginManager.registerEvents(
                    new SpawnerUpgradeListener(spawnerService, spawnerUpgradeGui), this);
        }
        // Generators (/gens): a bought-placed-stacked-upgraded
        // progression that pays out on its own. A broken generators.yml
        // disables the system with one loud log line, never the plugin.
        this.generatorConfig = GeneratorConfig.disabled();
        try {
            final GeneratorConfig parsed = new GeneratorConfig(this);
            parsed.load();
            this.generatorConfig = parsed;
        } catch (final RuntimeException exception) {
            getLogger().severe("Generators disabled — " + exception.getMessage());
        }
        GensMenuGui gensMenuGui = null;
        GeneratorManageGui generatorManageGui = null;
        if (generatorConfig.enabled() && economy != null) {
            this.generatorService = new GeneratorService(this, generatorConfig,
                    new YamlGeneratorDataStore(
                            Path.of(getDataFolder().getPath(), "generators-data.yml"), getLogger()),
                    economy, messages, islands, islandPoints);
            generatorService.load();
            generatorService.start();
            islands.onDelete(generatorService::onIslandDeleted);
            if (generatorConfig.holograms()) {
                final GeneratorHolograms generatorHolograms =
                        new GeneratorHolograms(this, generatorService);
                generatorService.attach(generatorHolograms);
                pluginManager.registerEvents(generatorHolograms, this);
            }
            gensMenuGui = new GensMenuGui(generatorConfig, generatorService, economy);
            generatorManageGui = new GeneratorManageGui(generatorService, economy);
            pluginManager.registerEvents(
                    new GeneratorListener(generatorService, generatorManageGui, messages), this);
            pluginManager.registerEvents(
                    new GeneratorManageListener(generatorService, generatorManageGui), this);
        } else if (economy == null) {
            getLogger().severe("Generators disabled — no economy (the shop failed to load).");
        }

        final IslandGui islandGui = new IslandGui(islands, upgradeConfig, buffService,
                spawnerMenuGui, gensMenuGui, economy);
        if (gensMenuGui != null) {
            pluginManager.registerEvents(new GensMenuListener(generatorConfig, generatorService,
                    gensMenuGui, islandGui), this);
            final PluginCommand gensCommand = getCommand("gens");
            if (gensCommand != null) {
                final GensCommand executor = new GensCommand(generatorConfig, generatorService,
                        gensMenuGui, generatorManageGui, messages);
                gensCommand.setExecutor(executor);
                gensCommand.setTabCompleter(executor);
            } else {
                getLogger().severe("Command 'gens' missing from plugin.yml — /gens will not work.");
            }
        } else {
            // the system is off: /gens still answers, with the "unavailable" line
            registerSimpleCommand("gens", new GensCommand(generatorConfig, null, null, null, messages));
        }
        pluginManager.registerEvents(
                new IslandMenuListener(islands, islandGui, upgradeService, upgradeConfig, messages),
                this);

        final IsTopGui isTopGui = new IsTopGui(islands, islandPoints);
        final IsTopBoardGui isTopBoardGui = new IsTopBoardGui(islands, islandPoints);
        pluginManager.registerEvents(new IsTopListener(isTopGui, isTopBoardGui), this);
        final IslandCommand command = new IslandCommand(islands, messages, islandGui, isTopGui);

        // Island top season rewards: the leaders of each leaderboard are
        // paid in webstore gift cards when /season set starts a new season.
        this.islandTopRewards = new IslandTopRewards(this, islands::all, islandPoints, tebexConfig,
                tebexClient, giftcardStore, messages, getLogger(),
                Path.of(getDataFolder().getPath(), "island-rewards.yml"));
        islandTopRewards.load();
        registerSimpleCommand("season", new SeasonCommand(
                rankService == null ? null : rankService, islandTopRewards, messages));
        final PluginCommand islandCommand = getCommand("island");
        if (islandCommand != null) {
            islandCommand.setExecutor(command);
            islandCommand.setTabCompleter(command);
        } else {
            getLogger().severe("Command 'island' missing from plugin.yml — /is will not work.");
        }

        if (spawnerService != null) {
            final PluginCommand spawnerCommand = getCommand("spawner");
            if (spawnerCommand != null) {
                final SpawnerCommand executor =
                        new SpawnerCommand(spawnerConfig, spawnerService, messages, spawnerMenuGui,
                                spawnerUpgradeGui);
                spawnerCommand.setExecutor(executor);
                spawnerCommand.setTabCompleter(executor);
            } else {
                getLogger().severe("Command 'spawner' missing from plugin.yml — /spawner will not work.");
            }
        }

        // PlaceholderAPI (optional): %coremc_*_essence% & friends plus the
        // configurable %x_currency%. Only touched when PAPI is present, so
        // its classes never load without it.
        if (essenceManager != null
                && getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new CoremcExpansion(essenceManager, economy).register();
                new XCurrencyExpansion(essenceManager, coreConfig).register();
                getLogger().info("PlaceholderAPI expansions registered (coremc, x).");
            } catch (final Throwable error) {
                getLogger().warning("PlaceholderAPI expansions failed: " + error.getMessage());
            }
        }

        getLogger().info("CoreMC enabled: " + islands.count() + " island(s), world '"
                + worlds.islandWorld().getName() + "'.");
    }

    /**
     * Exposes CoreMC's coin economy through the Vault API when the Vault
     * plugin is installed: any Vault-aware plugin then reads and spends
     * the same coins (balances stay owned by CoreMC). Without Vault,
     * everything works unchanged — the bridge simply never loads.
     */
    private void registerVaultBridge() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().info("Vault not installed — economy bridge skipped (coins stay internal).");
            return;
        }
        try {
            final Object bridge = new VaultEconomy(economy);
            getServer().getServicesManager().register(
                    net.milkbowl.vault.economy.Economy.class,
                    (net.milkbowl.vault.economy.Economy) bridge,
                    this, org.bukkit.plugin.ServicePriority.Normal);
            getLogger().info("Vault economy bridge active: provider 'CoreMC' — "
                    + "other plugins can now use CoreMC coins.");
        } catch (final Throwable throwable) {
            // missing API classes or a Vault version mismatch must never
                     // take the plugin down
            getLogger().warning("Vault is installed but the economy bridge could not load: "
                    + throwable.getMessage());
        }
    }

    /** Registers a command executor, logging loudly when plugin.yml is missing it. */
    private void registerSimpleCommand(final String name, final CommandExecutor executor) {
        final PluginCommand command = getCommand(name);
        if (command != null) {
            command.setExecutor(executor);
            if (executor instanceof TabCompleter completer) {
                command.setTabCompleter(completer);
            }
        } else {
            getLogger().severe("Command '" + name + "' missing from plugin.yml — /" + name
                    + " will not work.");
        }
    }

    @Override
    public void onDisable() {
        // settle any open /sell windows first so a shutdown never
        // swallows items or coins still sitting in them
        if (sellListener != null) {
            sellListener.closeAll();
        }
        if (islandPoints != null) {
            try {
                islandPoints.flush();
            } catch (final java.io.IOException exception) {
                getLogger().warning("Could not save island points: " + exception.getMessage());
            }
        }
        if (generatorService != null) {
            generatorService.shutdown();
        }
        if (essenceManager != null) {
            essenceManager.shutdown();
        }
        if (giftcardStore != null) {
            try {
                giftcardStore.save();
            } catch (final java.io.IOException exception) {
                getLogger().warning("Could not save gift cards: " + exception.getMessage());
            }
        }
        // drop the Vault economy registration before anything else
        getServer().getServicesManager().unregisterAll(this);
        if (economy != null) {
            economy.shutdown();
        }
        getLogger().info("CoreMC disabled.");
    }

    public CoreConfig coreConfig() {
        return coreConfig;
    }

    public MessageService messages() {
        return messages;
    }

    public IslandWorldService worlds() {
        return worlds;
    }

    public SchematicService schematics() {
        return schematics;
    }

    public SpawnerService spawners() {
        return spawnerService;
    }

    public IslandService islands() {
        return islands;
    }

    public GeneratorService generators() {
        return generatorService;
    }
}
