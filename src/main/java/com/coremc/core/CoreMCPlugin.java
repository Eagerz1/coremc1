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
public final class CoreMCPlugin extends JavaPlugin {

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
        getLogger().info("Creating dedicated island world '" + name + "' (void terrain)...");
        final org.bukkit.World created = new org.bukkit.WorldCreator(name)
                .generator(new com.coremc.core.island.VoidChunkGenerator())
                .environment(org.bukkit.World.Environment.NORMAL)
                .generateStructures(false)
                .createWorld();
        if (created == null) {
            getLogger().severe("Failed to create the island world '" + name + "'!");
            return;
        }
        created.setSpawnFlags(false, false); // no ambient/animal spawns cluttering the void
        created.setKeepSpawnInMemory(false);
        getLogger().info("Island world ready: " + name);
    }

    @Override
    public void onEnable() {
        try {
            enableSafely();
        } catch (final RuntimeException exception) {
            getLogger().log(Level.SEVERE, "CoreMC failed to enable — shutting services down.", exception);
            shutdownServices();
            throw exception;
        }
    }

    private void enableSafely() {
        // 1. Task registry first: everything else schedules through it.
        this.taskService = new TaskService(this);

        // 2. Configuration and messages.
        this.coreConfig = new CoreConfig(this);
        this.coreConfig.load();

        this.messageService = new MessageService(this);
        this.messageService.load();

        // 3. Player data (YAML store in plugins/CoreMC/profiles/).
        this.playerDataService =
                new PlayerDataService(this, new YamlPlayerDataStore(getDataFolder().toPath().resolve("profiles")),
                        taskService);
        this.playerDataService.startAutosave(coreConfig.autosaveSeconds());

        // 3b. Economy (pure service over player profiles; no I/O of its own).
        this.economyService = new EconomyService(playerDataService);

        // 3c. Island registry (loads async from plugins/CoreMC/islands/) inside
        //     the DEDICATED island world (created as void terrain when absent).
        ensureIslandWorld();
        this.themeService = new com.coremc.core.island.ThemeService(this);
        final int themes = themeService.load();
        getLogger().info(themes + " island theme(s) loaded from themes.yml.");
        this.islandService = new IslandService(
                this,
                coreConfig,
                new YamlIslandDataStore(getDataFolder().toPath().resolve("islands")),
                playerDataService,
                getDataFolder().toPath().resolve("islands"));
        this.islandService.start();
        this.miningCubeService = new com.coremc.core.island.MiningCubeService(this);
        this.miningCubeService.load();
        this.miningCubeService.startRegeneration(taskService);
        this.islandUpgradeEffects = new com.coremc.core.island.IslandUpgradeEffects(this);
        this.islandActivityEffects = new com.coremc.core.island.IslandActivityEffects(this);
        this.islandProgressService = new com.coremc.core.island.IslandProgressService(this);
        this.islandProgressService.start(taskService);
        this.islandBuffService = new com.coremc.core.island.IslandBuffService(this);
        if (this.islandService.islandWorld().isEmpty()) {
            getLogger().warning("Island world '" + coreConfig.islandWorldName()
                    + "' does not exist — /island commands will report it as unavailable.");
        }

        // 3d. GUI runtime (holder-bound menus; no per-player tracking maps).
        this.guiService = new GuiService(this);

        // 3e. Roles + OmniTool (profile-driven progression).
        this.omniToolService = new OmniToolService(this);
        this.roleService = new RoleService(this);
        final int omniUpgrades = omniToolService.load();

        // 3f. Placeables: generators + spawners.
        this.placeableService = new PlaceableService(this);
        this.generatorService = new GeneratorService(this);
        this.spawnerService = new SpawnerService(this);

        // 3g. Load persistent world/service data (after worlds exist).
        placeableService.load();
        final int gens = generatorService.load();
        final int spawners = spawnerService.load();
        this.shopService = new ShopService(this);
        final int shopEntries = shopService.loadCatalogue();
        this.enchantService = new EnchantService(this);
        final int enchantCount = enchantService.load();
        this.keyService = new KeyService(this);
        final int keyCount = keyService.load();
        // Crates last: reward refs validate against the live catalogues above.
        this.crateService = new CrateService(this);
        final int crateCount = crateService.load();
        getLogger().info("Loaded " + gens + " generator(s), " + spawners + " spawner type(s), "
                + shopEntries + " shop entr(y/ies), " + omniUpgrades + " omni upgrade(s), "
                + enchantCount + " enchant(s), " + keyCount + " crate key(s), "
                + crateCount + " crate(s).");

        // 4. Listeners.
        this.enchantEngine = new EnchantEngine(this);
        final PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(
                new PlayerListener(playerDataService, messageService, coreConfig, islandService), this);
        pluginManager.registerEvents(new IslandProtectionListener(this), this);
        pluginManager.registerEvents(new IslandVoidRescueListener(this), this);
        pluginManager.registerEvents(islandUpgradeEffects, this);
        pluginManager.registerEvents(islandActivityEffects, this);
        pluginManager.registerEvents(islandProgressService, this);
        pluginManager.registerEvents(guiService, this);
        pluginManager.registerEvents(new OmniToolListener(this), this);
        pluginManager.registerEvents(new MiningXpListener(this), this);
        pluginManager.registerEvents(new LoggingXpListener(this), this);
        pluginManager.registerEvents(new FarmingXpListener(this), this);
        pluginManager.registerEvents(new FishingXpListener(this), this);
        pluginManager.registerEvents(new SlayerXpListener(this), this);
        pluginManager.registerEvents(new PlaceableListener(this), this);
        pluginManager.registerEvents(new KillProgressListener(this), this);
        pluginManager.registerEvents(enchantEngine, this);
        pluginManager.registerEvents(new MiningEnchantHandler(this, enchantEngine), this);
        pluginManager.registerEvents(new LoggingEnchantHandler(this, enchantEngine), this);
        pluginManager.registerEvents(new FarmingEnchantHandler(this, enchantEngine), this);
        pluginManager.registerEvents(new FishingEnchantHandler(this, enchantEngine), this);
        pluginManager.registerEvents(new SlayerEnchantHandler(this, enchantEngine), this);

        // 5. Commands.
        registerCommands();

        getLogger().info("CoreMC " + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        shutdownServices();
    }

    private void shutdownServices() {
        // Stop scheduled work first so nothing touches dead services.
        if (taskService != null) {
            taskService.cancelAll();
        }
        // Flush and shut down player data (synchronous, safe on disable).
        if (playerDataService != null) {
            playerDataService.shutdown();
        }
        if (islandService != null) {
            islandService.shutdown();
        }
        this.taskService = null;
        this.coreConfig = null;
        this.messageService = null;
        this.playerDataService = null;
        this.economyService = null;
        if (omniToolService != null) {
            omniToolService.clearTransient();
        }
        if (placeableService != null) {
            placeableService.save(); // shutdown-critical: never lose placed blocks
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
        getLogger().info("CoreMC disabled — all player data saved, all tasks cancelled.");
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
        final PluginCommand coremc = getCommand("coremc");
        if (coremc == null) {
            throw new IllegalStateException("Command 'coremc' missing from plugin.yml");
        }
        final CoreMCCommand coremcCommand = new CoreMCCommand(this);
        coremc.setExecutor(coremcCommand);
        coremc.setTabCompleter(coremcCommand);

        final PluginCommand profile = getCommand("profile");
        if (profile == null) {
            throw new IllegalStateException("Command 'profile' missing from plugin.yml");
        }
        final ProfileCommand profileCommand = new ProfileCommand(this);
        profile.setExecutor(profileCommand);
        profile.setTabCompleter(profileCommand);

        final PluginCommand heal = getCommand("heal");
        if (heal == null) {
            throw new IllegalStateException("Command 'heal' missing from plugin.yml");
        }
        final HealCommand healCommand = new HealCommand(this);
        heal.setExecutor(healCommand);
        heal.setTabCompleter(healCommand);

        final PluginCommand island = getCommand("island");
        if (island == null) {
            throw new IllegalStateException("Command 'island' missing from plugin.yml");
        }
        final IslandCommand islandCommand = new IslandCommand(this);
        island.setExecutor(islandCommand);
        island.setTabCompleter(islandCommand);

        registerCurrencyCommand("credits", Currency.CREDITS);
        registerCurrencyCommand("skytokens", Currency.SKY_TOKENS);
        registerCurrencyCommand("money", Currency.MONEY);

        final PluginCommand role = getCommand("role");
        if (role == null) {
            throw new IllegalStateException("Command 'role' missing from plugin.yml");
        }
        final RoleCommand roleCommand = new RoleCommand(this);
        role.setExecutor(roleCommand);
        role.setTabCompleter(roleCommand);

        final PluginCommand gens = getCommand("gens");
        if (gens == null) {
            throw new IllegalStateException("Command 'gens' missing from plugin.yml");
        }
        gens.setExecutor(new GensCommand(this));

        final PluginCommand spawners = getCommand("spawners");
        if (spawners == null) {
            throw new IllegalStateException("Command 'spawners' missing from plugin.yml");
        }
        spawners.setExecutor(new SpawnersCommand(this));

        final PluginCommand crates = getCommand("crates");
        if (crates == null) {
            throw new IllegalStateException("Command 'crates' missing from plugin.yml");
        }
        crates.setExecutor(new CratesCommand(this));

        final ShopCommand shopCommand = new ShopCommand(this);
        for (final String name : new String[] {"shop", "tokenshop"}) {
            final PluginCommand cmd = getCommand(name);
            if (cmd == null) {
                throw new IllegalStateException("Command '" + name + "' missing from plugin.yml");
            }
            cmd.setExecutor(shopCommand);
            cmd.setTabCompleter(shopCommand);
        }
    }

    private void registerCurrencyCommand(final String name, final Currency currency) {
        final PluginCommand command = getCommand(name);
        if (command == null) {
            throw new IllegalStateException("Command '" + name + "' missing from plugin.yml");
        }
        final CurrencyAdminCommand handler = new CurrencyAdminCommand(this, currency);
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
