package com.coremc.core;

import com.coremc.core.command.CoreMCCommand;
import com.coremc.core.command.CurrencyAdminCommand;
import com.coremc.core.command.HealCommand;
import com.coremc.core.command.ProfileCommand;
import com.coremc.core.config.CoreConfig;
import com.coremc.core.config.MessageService;
import com.coremc.core.crate.CrateService;
import com.coremc.core.crate.CratesCommand;
import com.coremc.core.crate.KeyService;
import com.coremc.core.economy.Currency;
import com.coremc.core.economy.EconomyService;
import com.coremc.core.enchant.EnchantEngine;
import com.coremc.core.enchant.EnchantService;
import com.coremc.core.enchant.FarmingEnchantHandler;
import com.coremc.core.enchant.FishingEnchantHandler;
import com.coremc.core.enchant.LoggingEnchantHandler;
import com.coremc.core.enchant.MiningEnchantHandler;
import com.coremc.core.enchant.SlayerEnchantHandler;
import com.coremc.core.gui.GuiService;
import com.coremc.core.island.IslandCommand;
import com.coremc.core.island.IslandProtectionListener;
import com.coremc.core.island.IslandVoidRescueListener;
import com.coremc.core.island.IslandService;
import com.coremc.core.island.YamlIslandDataStore;
import com.coremc.core.player.PlayerDataService;
import com.coremc.core.player.PlayerListener;
import com.coremc.core.gen.GensCommand;
import com.coremc.core.gen.GeneratorService;
import com.coremc.core.placeable.PlaceableListener;
import com.coremc.core.placeable.PlaceableService;
import com.coremc.core.player.YamlPlayerDataStore;
import com.coremc.core.role.OmniToolListener;
import com.coremc.core.role.OmniToolService;
import com.coremc.core.role.RoleCommand;
import com.coremc.core.role.RoleService;
import com.coremc.core.role.xp.FarmingXpListener;
import com.coremc.core.role.xp.FishingXpListener;
import com.coremc.core.role.xp.LoggingXpListener;
import com.coremc.core.role.xp.MiningXpListener;
import com.coremc.core.role.xp.SlayerXpListener;
import com.coremc.core.spawner.KillProgressListener;
import com.coremc.core.spawner.SpawnerService;
import com.coremc.core.spawner.SpawnersCommand;
import com.coremc.core.scheduler.TaskService;
import com.coremc.core.shop.ShopCommand;
import com.coremc.core.shop.ShopService;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * CoreMC plugin bootstrap.
 *
 * Owns the plugin's services and wires them together. Services are plain
 * objects with narrow responsibilities, created once in onEnable and torn
 * down in reverse order in onDisable — no static service locators, no
 * re-creation on reload.
 */
public final class CoreMCRuntime {

    private final CoreMCPlugin plugin;

    public CoreMCRuntime(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    private TaskService taskService;
    private CoreConfig coreConfig;
    private MessageService messageService;
    private PlayerDataService playerDataService;
    private EconomyService economyService;
    private IslandService islandService;
    private GuiService guiService;
    private OmniToolService omniToolService;
    private RoleService roleService;
    private PlaceableService placeableService;
    private PlaceableListener placeableListener;
    private GeneratorService generatorService;
    private SpawnerService spawnerService;
    private ShopService shopService;
    private EnchantService enchantService;
    private EnchantEngine enchantEngine;
    private KeyService keyService;
    private CrateService crateService;
    private com.coremc.core.island.ThemeService themeService;
    private com.coremc.core.island.MiningCubeService miningCubeService;
    private com.coremc.core.island.IslandUpgradeEffects islandUpgradeEffects;
    private com.coremc.core.island.IslandActivityEffects islandActivityEffects;
    private com.coremc.core.island.IslandProgressService islandProgressService;
    private com.coremc.core.island.IslandBuffService islandBuffService;

    /**
     * Creates (or attaches to) the dedicated island world. Islands live in
     * their own void world, never in the main world: terrain cannot leak
     * between islands and the void keeps the world small. If the world
     * already exists (e.g. {@code world} from before the dedicated world
     * existed), that world is simply reused — nothing is regenerated.
     */
    private void ensureIslandWorld() {
        final String name = coreConfig.islandWorldName();
        if (org.bukkit.Bukkit.getWorld(name) != null) {
            return;
        }
        plugin.getLogger().info("Creating dedicated island world '" + name + "' (void terrain)...");
        final org.bukkit.World created = new org.bukkit.WorldCreator(name)
                .generator(new com.coremc.core.island.VoidChunkGenerator())
                .environment(org.bukkit.World.Environment.NORMAL)
                .generateStructures(false)
                .createWorld();
        if (created == null) {
            plugin.getLogger().severe("Failed to create the island world '" + name + "'!");
            return;
        }
        created.setSpawnFlags(false, false); // no ambient/animal spawns cluttering the void
        created.setKeepSpawnInMemory(false);
        plugin.getLogger().info("Island world ready: " + name);
    }
    public void enable() {
        try {
            enableSafely();
        } catch (final RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "CoreMC failed to enable — shutting services down.", exception);
            shutdownServices();
            throw exception;
        }
    }

    private void enableSafely() {
        // 1. Task registry first: everything else schedules through it.
        this.taskService = new TaskService(plugin);

        // 2. Configuration and messages.
        this.coreConfig = new CoreConfig(plugin);
        this.coreConfig.load();

        this.messageService = new MessageService(plugin);
        this.messageService.load();

        // 3. Player data (YAML store in plugins/CoreMC/profiles/).
        this.playerDataService =
                new PlayerDataService(plugin, new YamlPlayerDataStore(plugin.getDataFolder().toPath().resolve("profiles")),
                        taskService);
        this.playerDataService.startAutosave(coreConfig.autosaveSeconds());

        // 3b. Economy (pure service over player profiles; no I/O of its own).
        this.economyService = new EconomyService(playerDataService);

        // 3c. Island registry (loads async from plugins/CoreMC/islands/) inside
        //     the DEDICATED island world (created as void terrain when absent).
        ensureIslandWorld();
        this.themeService = new com.coremc.core.island.ThemeService(plugin);
        final int themes = themeService.load();
        plugin.getLogger().info(themes + " island theme(s) loaded from themes.yml.");
        this.islandService = new IslandService(plugin,
                coreConfig,
                new YamlIslandDataStore(plugin.getDataFolder().toPath().resolve("islands")),
                playerDataService,
                economyService,
                themeService,
                taskService,
                plugin.getDataFolder().toPath().resolve("islands"));
        this.islandService.start();
        this.miningCubeService = new com.coremc.core.island.MiningCubeService(plugin);
        this.miningCubeService.load();
        this.miningCubeService.startRegeneration(taskService);
        this.islandUpgradeEffects = new com.coremc.core.island.IslandUpgradeEffects(plugin);
        this.islandActivityEffects = new com.coremc.core.island.IslandActivityEffects(plugin);
        this.islandProgressService = new com.coremc.core.island.IslandProgressService(plugin);
        this.islandProgressService.start(taskService);
        this.islandBuffService = new com.coremc.core.island.IslandBuffService(plugin);
        if (this.islandService.islandWorld().isEmpty()) {
            plugin.getLogger().warning("Island world '" + coreConfig.islandWorldName()
                    + "' does not exist — /island commands will report it as unavailable.");
        }

        // 3d. GUI runtime (holder-bound menus; no per-player tracking maps).
        this.guiService = new GuiService(plugin);

        // 3e. Roles + OmniTool (profile-driven progression).
        this.omniToolService = new OmniToolService(plugin);
        this.roleService = new RoleService(plugin);
        final int omniUpgrades = omniToolService.load();

        // 3f. Placeables: generators + spawners.
        this.placeableService = new PlaceableService(plugin);
        this.generatorService = new GeneratorService(plugin);
        this.spawnerService = new SpawnerService(plugin);

        // 3g. Load persistent world/service data (after worlds exist).
        placeableService.load();
        final int gens = generatorService.load();
        final int spawners = spawnerService.load();
        this.shopService = new ShopService(plugin);
        final int shopEntries = shopService.loadCatalogue();
        this.enchantService = new EnchantService(plugin);
        final int enchantCount = enchantService.load();
        this.keyService = new KeyService(plugin);
        final int keyCount = keyService.load();
        // Crates last: reward refs validate against the live catalogues above.
        this.crateService = new CrateService(plugin);
        final int crateCount = crateService.load();
        plugin.getLogger().info("Loaded " + gens + " generator(s), " + spawners + " spawner type(s), "
                + shopEntries + " shop entr(y/ies), " + omniUpgrades + " omni upgrade(s), "
                + enchantCount + " enchant(s), " + keyCount + " crate key(s), "
                + crateCount + " crate(s).");

        // 4. Listeners.
        this.enchantEngine = new EnchantEngine(plugin);
        final PluginManager pluginManager = plugin.getServer().getPluginManager();
        pluginManager.registerEvents(
                new PlayerListener(playerDataService, messageService, coreConfig, islandService), plugin);
        pluginManager.registerEvents(new IslandProtectionListener(plugin), plugin);
        pluginManager.registerEvents(new IslandVoidRescueListener(plugin), plugin);
        pluginManager.registerEvents(islandUpgradeEffects, plugin);
        pluginManager.registerEvents(islandActivityEffects, plugin);
        pluginManager.registerEvents(islandProgressService, plugin);
        pluginManager.registerEvents(guiService, plugin);
        pluginManager.registerEvents(new OmniToolListener(plugin), plugin);
        pluginManager.registerEvents(new MiningXpListener(plugin), plugin);
        pluginManager.registerEvents(new LoggingXpListener(plugin), plugin);
        pluginManager.registerEvents(new FarmingXpListener(plugin), plugin);
        pluginManager.registerEvents(new FishingXpListener(plugin), plugin);
        pluginManager.registerEvents(new SlayerXpListener(plugin), plugin);
        this.placeableListener = new PlaceableListener(plugin);
        pluginManager.registerEvents(placeableListener, plugin);
        pluginManager.registerEvents(new com.coremc.core.spawner.SpawnerMobTagger(plugin, spawnerService.tags()), plugin);
        pluginManager.registerEvents(new KillProgressListener(plugin), plugin);
        pluginManager.registerEvents(enchantEngine, plugin);
        pluginManager.registerEvents(new MiningEnchantHandler(plugin, enchantEngine), plugin);
        pluginManager.registerEvents(new LoggingEnchantHandler(plugin, enchantEngine), plugin);
        pluginManager.registerEvents(new FarmingEnchantHandler(plugin, enchantEngine), plugin);
        pluginManager.registerEvents(new FishingEnchantHandler(plugin, enchantEngine), plugin);
        pluginManager.registerEvents(new SlayerEnchantHandler(plugin, enchantEngine), plugin);

        // Spawner liveness watchdog (normalises legacy tiles + re-arms stalls).
        spawnerService.startWatchdog();

        // 5. Commands.
        registerCommands();

        plugin.getLogger().info("CoreMC " + plugin.getDescription().getVersion() + " enabled.");
    }
    public void shutdown() {
        shutdownServices();
    }

    private void shutdownServices() {
        // Resolve queued placeable breaks before cancelling their next-tick callbacks.
        if (placeableListener != null) {
            placeableListener.resolvePendingBreaksOnShutdown();
            placeableListener = null;
        }
        // Stop scheduled work first so nothing touches dead services.
        if (taskService != null) {
            taskService.cancelAll();
        }
        // Finish island transactions while the profile writer is still accepting durable writes.
        if (islandService != null) {
            islandService.shutdown();
        }
        // Flush and shut down player data after island transactions have joined the writer queue.
        if (playerDataService != null) {
            playerDataService.shutdown();
        }
        this.taskService = null;
        this.coreConfig = null;
        this.messageService = null;
        this.playerDataService = null;
        this.economyService = null;
        if (omniToolService != null) {
            omniToolService.clearTransient();
        }
        if (spawnerService != null) {
            spawnerService.stopWatchdog();
        }
        if (placeableService != null) {
            boolean placementsSaved = false;
            for (int attempt = 1; attempt <= 3 && !placementsSaved; attempt++) {
                placementsSaved = placeableService.save();
                if (!placementsSaved && attempt < 3) {
                    plugin.getLogger().warning("Retrying placeable registry shutdown save (attempt " + (attempt + 1) + "/3).");
                }
            }
            if (!placementsSaved) {
                plugin.getLogger().severe("Could not persist the placeable registry after three shutdown attempts.");
            }
            placeableService = null;
        }
        this.generatorService = null;
        this.spawnerService = null;
        this.shopService = null;
        this.islandService = null;
        this.islandActivityEffects = null;
        this.islandProgressService = null;
        this.guiService = null;
        this.omniToolService = null;
        this.roleService = null;
        plugin.getLogger().info("CoreMC disabled — all player data saved, all tasks cancelled.");
    }

    /** Reloads config.yml + messages.yml and re-applies settings. */
    public void reloadCoreConfig() {
        coreConfig.load();
        messageService.load();
        // Re-apply the autosave interval with fresh configuration.
        playerDataService.startAutosave(coreConfig.autosaveSeconds());
        themeService.load();
        miningCubeService.load();
        spawnerService.load();
        generatorService.load();
        shopService.loadCatalogue();
        omniToolService.load();
        enchantService.load();
        keyService.load();
        crateService.load();
        islandActivityEffects.clearCaches();
    }

    private void registerCommands() {
        final PluginCommand coremc = plugin.getCommand("coremc");
        if (coremc == null) {
            throw new IllegalStateException("Command 'coremc' missing from plugin.yml");
        }
        final CoreMCCommand coremcCommand = new CoreMCCommand(plugin);
        coremc.setExecutor(coremcCommand);
        coremc.setTabCompleter(coremcCommand);

        final PluginCommand profile = plugin.getCommand("profile");
        if (profile == null) {
            throw new IllegalStateException("Command 'profile' missing from plugin.yml");
        }
        final ProfileCommand profileCommand = new ProfileCommand(plugin);
        profile.setExecutor(profileCommand);
        profile.setTabCompleter(profileCommand);

        final PluginCommand heal = plugin.getCommand("heal");
        if (heal == null) {
            throw new IllegalStateException("Command 'heal' missing from plugin.yml");
        }
        final HealCommand healCommand = new HealCommand(plugin);
        heal.setExecutor(healCommand);
        heal.setTabCompleter(healCommand);

        final PluginCommand island = plugin.getCommand("island");
        if (island == null) {
            throw new IllegalStateException("Command 'island' missing from plugin.yml");
        }
        final IslandCommand islandCommand = new IslandCommand(plugin);
        island.setExecutor(islandCommand);
        island.setTabCompleter(islandCommand);

        registerCurrencyCommand("credits", Currency.CREDITS);
        registerCurrencyCommand("skytokens", Currency.SKY_TOKENS);
        registerCurrencyCommand("money", Currency.MONEY);

        final PluginCommand role = plugin.getCommand("role");
        if (role == null) {
            throw new IllegalStateException("Command 'role' missing from plugin.yml");
        }
        final RoleCommand roleCommand = new RoleCommand(plugin);
        role.setExecutor(roleCommand);
        role.setTabCompleter(roleCommand);

        final PluginCommand gens = plugin.getCommand("gens");
        if (gens == null) {
            throw new IllegalStateException("Command 'gens' missing from plugin.yml");
        }
        gens.setExecutor(new GensCommand(plugin));

        final PluginCommand spawners = plugin.getCommand("spawners");
        if (spawners == null) {
            throw new IllegalStateException("Command 'spawners' missing from plugin.yml");
        }
        spawners.setExecutor(new SpawnersCommand(plugin));

        final PluginCommand crates = plugin.getCommand("crates");
        if (crates == null) {
            throw new IllegalStateException("Command 'crates' missing from plugin.yml");
        }
        crates.setExecutor(new CratesCommand(plugin));

        final ShopCommand shopCommand = new ShopCommand(plugin);
        for (final String name : new String[] {"shop", "tokenshop"}) {
            final PluginCommand cmd = plugin.getCommand(name);
            if (cmd == null) {
                throw new IllegalStateException("Command '" + name + "' missing from plugin.yml");
            }
            cmd.setExecutor(shopCommand);
            cmd.setTabCompleter(shopCommand);
        }
    }

    private void registerCurrencyCommand(final String name, final Currency currency) {
        final PluginCommand command = plugin.getCommand(name);
        if (command == null) {
            throw new IllegalStateException("Command '" + name + "' missing from plugin.yml");
        }
        final CurrencyAdminCommand handler = new CurrencyAdminCommand(plugin, currency);
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }

    /** Central task service (tracked, cancelled on disable). */
    public TaskService tasks() {
        return taskService;
    }

    /** Typed plugin configuration. */
    public CoreConfig coreConfig() {
        return coreConfig;
    }

    /** Message/branding service. */
    public MessageService messages() {
        return messageService;
    }

    /** Player profile lifecycle service. */
    public PlayerDataService playerData() {
        return playerDataService;
    }

    /** Economy service (all currency movement goes through here). */
    public EconomyService economy() {
        return economyService;
    }

    /** Island lifecycle service. */
    public IslandService islands() {
        return islandService;
    }

    /** Island themes (themes.yml). */
    public com.coremc.core.island.ThemeService themes() {
        return themeService;
    }

    /** The Mining Cube effect system. */
    public com.coremc.core.island.MiningCubeService miningCube() {
        return miningCubeService;
    }

    /** Live effects of island upgrade tracks (purchase hook + listeners). */
    public com.coremc.core.island.IslandUpgradeEffects upgradeEffects() {
        return islandUpgradeEffects;
    }

    /** Live effects of the per-activity island upgrade tracks. */
    public com.coremc.core.island.IslandActivityEffects islandActivity() {
        return islandActivityEffects;
    }

    /** Island stats, XP and level progression. */
    public com.coremc.core.island.IslandProgressService islandProgress() {
        return islandProgressService;
    }

    /** Island buff multipliers (the BUFF pipeline stage). */
    public com.coremc.core.island.IslandBuffService islandBuffs() {
        return islandBuffService;
    }

    /** GUI runtime. */
    public GuiService gui() {
        return guiService;
    }

    /** OmniTool service. */
    public OmniToolService omniTool() {
        return omniToolService;
    }

    /** Role service (selection + progression routing). */
    public RoleService roles() {
        return roleService;
    }

    /** Placeable identity + placement registry. */
    public PlaceableService placeables() {
        return placeableService;
    }

    /** Generator catalogue and purchases. */
    public GeneratorService generators() {
        return generatorService;
    }

    /** Spawner catalogue, unlock progression and purchases. */
    public SpawnerService spawners() {
        return spawnerService;
    }

    /** Shop catalogue and purchases. */
    public ShopService shop() {
        return shopService;
    }

    /** Custom-enchant catalogue, purchases and boost queries. */
    public EnchantService enchants() {
        return enchantService;
    }

    /** Custom-enchant behaviour engine (combos, cooldowns, grants). */
    public EnchantEngine enchantEngine() {
        return enchantEngine;
    }

    /** Crate-key minting and identification. */
    public KeyService keys() {
        return keyService;
    }

    /** Crate lineup, rolls and pity. */
    public CrateService crates() {
        return crateService;
    }
}
