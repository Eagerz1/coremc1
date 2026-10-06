package com.coremc.core;

import com.coremc.core.command.CoreMCCommand;
import com.coremc.core.command.CurrencyAdminCommand;
import com.coremc.core.command.HealCommand;
import com.coremc.core.command.ProfileCommand;
import com.coremc.core.companion.CompanionService;
import com.coremc.core.companion.CompanionsCommand;
import com.coremc.core.cosmetic.CosmeticSkinService;
import com.coremc.core.cosmetic.HatOverlayService;
import com.coremc.core.cosmetic.SkinService;
import com.coremc.core.cosmetic.SkinsCommand;
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
import com.coremc.core.island.EquipmentSetService;
import com.coremc.core.island.IslandService;
import com.coremc.core.island.YamlIslandDataStore;
import com.coremc.core.player.PlayerDataService;
import com.coremc.core.player.PlayerListener;
import com.coremc.core.gen.GensCommand;
import com.coremc.core.gen.GeneratorService;
import com.coremc.core.placeable.PlaceableListener;
import com.coremc.core.placeable.PlaceableService;
import com.coremc.core.player.YamlPlayerDataStore;
import com.coremc.core.quest.QuestService;
import com.coremc.core.quest.QuestsCommand;
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
import com.coremc.core.spawner.RareDropService;
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
    private CosmeticSkinService cosmeticSkinService;
    private SkinService skinService;
    private HatOverlayService hatOverlayService;
    private CompanionService companionService;
    private QuestService questService;
    private OmniToolService omniToolService;
    private RoleService roleService;
    private PlaceableService placeableService;
    private GeneratorService generatorService;
    private SpawnerService spawnerService;
    private RareDropService rareDropService;
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
    private EquipmentSetService equipmentSetService;
    private com.coremc.core.island.IslandBuffService islandBuffService;
    private com.coremc.core.chat.TagService tagService;
    private com.coremc.core.chat.ChatStyleService chatStyleService;
    private com.coremc.core.chat.RankService rankService;
    private com.coremc.core.chat.ChatFormatService chatFormatService;
    private com.coremc.core.moderation.ModerationService moderationService;
    private com.coremc.core.event.EventService eventService;
    private com.coremc.core.scoreboard.ScoreboardService scoreboardService;

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
        migrateV012Configuration();
        this.coreConfig = new CoreConfig(this);
        this.coreConfig.load();

        this.messageService = new MessageService(this);
        this.messageService.load();

        // 2b. Recurring Core Hour schedule is persistent across restarts.
        this.eventService = new com.coremc.core.event.EventService(this);
        this.eventService.load();
        this.eventService.start(taskService);

        // 3. Player data (YAML store in plugins/CoreMC/profiles/).
        this.playerDataService =
                new PlayerDataService(this, new YamlPlayerDataStore(getDataFolder().toPath().resolve("profiles")),
                        taskService);
        this.playerDataService.startAutosave(coreConfig.autosaveSeconds());

        // 3b. Economy (pure service over player profiles; no I/O of its own).
        this.economyService = new EconomyService(playerDataService);

        // 3b-2. Staff tools and moderation (loads its own UUID-based records).
        this.moderationService = new com.coremc.core.moderation.ModerationService(this);
        this.moderationService.load();

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
        this.equipmentSetService = new EquipmentSetService(this);
        this.equipmentSetService.start(taskService);
        this.islandBuffService = new com.coremc.core.island.IslandBuffService(this);
        if (this.islandService.islandWorld().isEmpty()) {
            getLogger().warning("Island world '" + coreConfig.islandWorldName()
                    + "' does not exist — /island commands will report it as unavailable.");
        }

        // 3d. GUI runtime (holder-bound menus; no per-player tracking maps).
        this.guiService = new GuiService(this);
        // Cosmetic skins are a presentation-only layer; they never own or
        // mutate generator or companion progression data.
        this.cosmeticSkinService = new CosmeticSkinService(this);
        // Animated skins (this feature): catalog from skins.yml, ownership in
        // profiles; loaded BEFORE roles/crates so tool creation can stamp the
        // equipped skin and crate rewards can validate skin refs.
        this.skinService = new SkinService(this);
        final int skinCount = skinService.load();
        this.hatOverlayService = new HatOverlayService(this);
        this.companionService = new CompanionService(this);
        final int companionCount = companionService.load();
        companionService.start(taskService);
        this.questService = new QuestService(this);
        final int questCount = questService.load();

        // 3e. Roles + OmniTool (profile-driven progression).
        this.omniToolService = new OmniToolService(this);
        this.roleService = new RoleService(this);
        final int omniUpgrades = omniToolService.load();
        this.scoreboardService = new com.coremc.core.scoreboard.ScoreboardService(this);
        scoreboardService.start();

        // 3f. Placeables: generators + spawners.
        this.placeableService = new PlaceableService(this);
        this.generatorService = new GeneratorService(this);
        this.spawnerService = new SpawnerService(this);
        this.rareDropService = new RareDropService(this);

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
        // Chat cosmetics BEFORE crates: TAG/CHAT_STYLE rewards validate against
        // these live catalogues.
        this.tagService = new com.coremc.core.chat.TagService(this);
        final int tagCount = tagService.load();
        this.chatStyleService = new com.coremc.core.chat.ChatStyleService(this);
        final int styleCount = chatStyleService.load();
        this.chatFormatService = new com.coremc.core.chat.ChatFormatService(this);
        this.chatFormatService.load();
        this.rankService = new com.coremc.core.chat.RankService(this);
        final int rankCount = rankService.load();
        rankService.startRefreshTask();
        getLogger().info("Loaded " + tagCount + " chat tag(s), " + styleCount
                + " chat style(s), " + rankCount + " rank prefix(es).");

        // Crates last: reward refs validate against the live catalogues above.
        this.crateService = new CrateService(this);
        final int crateCount = crateService.load();
        getLogger().info("Loaded " + gens + " generator(s), " + spawners + " spawner type(s), "
                + shopEntries + " shop entr(y/ies), " + omniUpgrades + " omni upgrade(s), "
                + enchantCount + " enchant(s), " + keyCount + " crate key(s), "
                + crateCount + " crate(s), "
                + skinCount + " animated skin(s), " + companionCount + " companion(s), "
                + questCount + " daily quest(s).");

        // 4. Listeners.
        this.enchantEngine = new EnchantEngine(this);
        final PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(
                new PlayerListener(playerDataService, messageService, coreConfig, islandService), this);
        pluginManager.registerEvents(moderationService.listener(), this);
        pluginManager.registerEvents(moderationService.visibility(), this);
        pluginManager.registerEvents(moderationService.spectate(), this);
        pluginManager.registerEvents(moderationService.cps(), this);
        pluginManager.registerEvents(new IslandProtectionListener(this), this);
        pluginManager.registerEvents(new IslandVoidRescueListener(this), this);
        pluginManager.registerEvents(islandUpgradeEffects, this);
        pluginManager.registerEvents(islandActivityEffects, this);
        pluginManager.registerEvents(islandProgressService, this);
        pluginManager.registerEvents(equipmentSetService, this);
        pluginManager.registerEvents(eventService, this);
        pluginManager.registerEvents(scoreboardService, this);
        pluginManager.registerEvents(guiService, this);
        pluginManager.registerEvents(new OmniToolListener(this), this);
        pluginManager.registerEvents(hatOverlayService, this);
        pluginManager.registerEvents(companionService, this);
        pluginManager.registerEvents(questService, this);
        pluginManager.registerEvents(new MiningXpListener(this), this);
        pluginManager.registerEvents(new LoggingXpListener(this), this);
        pluginManager.registerEvents(new FarmingXpListener(this), this);
        pluginManager.registerEvents(new FishingXpListener(this), this);
        pluginManager.registerEvents(new SlayerXpListener(this), this);
        pluginManager.registerEvents(new PlaceableListener(this), this);
        pluginManager.registerEvents(new com.coremc.core.spawner.SpawnerMobTagger(
                this, spawnerService.tags()), this);
        pluginManager.registerEvents(new KillProgressListener(this), this);
        pluginManager.registerEvents(enchantEngine, this);
        pluginManager.registerEvents(new MiningEnchantHandler(this, enchantEngine), this);
        pluginManager.registerEvents(new LoggingEnchantHandler(this, enchantEngine), this);
        pluginManager.registerEvents(new FarmingEnchantHandler(this, enchantEngine), this);
        pluginManager.registerEvents(new FishingEnchantHandler(this, enchantEngine), this);
        pluginManager.registerEvents(new SlayerEnchantHandler(this, enchantEngine), this);
        pluginManager.registerEvents(new com.coremc.core.chat.CosmeticsListener(this), this);
        // Chat formatting registers itself at the configured priority with
        // ignore-cancelled = true, so mutes/moderation always win first.
        new com.coremc.core.chat.ChatListener(this).register();

        // Spawner liveness watchdog (normalises legacy tiles + re-arms stalls).
        spawnerService.startWatchdog();

        // Animated hat overlays: one yaw-lock sync task for all wearers.
        hatOverlayService.start();

        // 5. Commands.
        registerCommands();

        getLogger().info("CoreMC " + getDescription().getVersion() + " enabled.");
    }

    /** Applies the one-time, targeted progression and economy changes for 0.12.0. */
    private void migrateV012Configuration() {
        final var config = getConfig();
        if (config.contains("coremc-config-version", true)
                && config.getInt("coremc-config-version", 0) >= 12) {
            return;
        }

        final var spawners = config.getConfigurationSection("spawners");
        if (spawners != null) {
            String previousMob = null;
            for (final String id : spawners.getKeys(false)) {
                final var definition = spawners.getConfigurationSection(id);
                if (definition == null || !definition.contains("entity")) continue;
                final String currentMob = definition.getString("entity", id).toLowerCase(java.util.Locale.ROOT);
                definition.set("unlock-kill-key", previousMob == null ? currentMob : previousMob);
                if (previousMob == null && currentMob.equals("zombie")) {
                    final java.util.List<java.util.Map<?, ?>> tiers = definition.getMapList("tiers");
                    if (tiers.isEmpty()) {
                        definition.set("required-kills", 0);
                    } else {
                        @SuppressWarnings("unchecked")
                        final java.util.Map<Object, Object> first =
                                (java.util.Map<Object, Object>) tiers.get(0);
                        first.put("required-kills", 0);
                        definition.set("tiers", tiers);
                    }
                }
                previousMob = currentMob;
            }
        }

        final java.util.Map<String, java.util.List<Long>> islandPrices = java.util.Map.of(
                "border", java.util.List.of(2_500L, 7_500L, 20_000L, 50_000L, 125_000L, 300_000L),
                "member-slots", java.util.List.of(1_000L, 3_000L, 8_000L, 20_000L, 50_000L),
                "generator-boost", java.util.List.of(5_000L, 15_000L, 40_000L, 100_000L, 250_000L),
                "spawner-boost", java.util.List.of(10_000L, 30_000L, 80_000L, 200_000L, 500_000L));
        islandPrices.forEach((key, costs) -> config.set("island.upgrades." + key + ".costs", costs));

        final java.util.Map<String, java.util.List<Long>> buffPrices = java.util.Map.ofEntries(
                java.util.Map.entry("mining-boost", java.util.List.of(5_000L, 15_000L, 40_000L, 100_000L, 250_000L)),
                java.util.Map.entry("farming-boost", java.util.List.of(5_000L, 15_000L, 40_000L, 100_000L, 250_000L)),
                java.util.Map.entry("fishing-boost", java.util.List.of(5_000L, 15_000L, 40_000L, 100_000L, 250_000L)),
                java.util.Map.entry("slaying-boost", java.util.List.of(5_000L, 15_000L, 40_000L, 100_000L, 250_000L)),
                java.util.Map.entry("logging-boost", java.util.List.of(5_000L, 15_000L, 40_000L, 100_000L, 250_000L)),
                java.util.Map.entry("generator-boost", java.util.List.of(10_000L, 30_000L, 80_000L, 200_000L, 500_000L)),
                java.util.Map.entry("spawner-boost", java.util.List.of(10_000L, 30_000L, 80_000L, 200_000L, 500_000L)),
                java.util.Map.entry("token-boost", java.util.List.of(15_000L, 45_000L, 120_000L, 300_000L, 750_000L)),
                java.util.Map.entry("credit-boost", java.util.List.of(15_000L, 45_000L, 120_000L, 300_000L, 750_000L)),
                java.util.Map.entry("xp-boost", java.util.List.of(10_000L, 30_000L, 80_000L, 200_000L, 500_000L)),
                java.util.Map.entry("sell-boost", java.util.List.of(15_000L, 45_000L, 120_000L, 300_000L, 750_000L)),
                java.util.Map.entry("island-luck", java.util.List.of(25_000L, 75_000L, 200_000L, 500_000L, 1_250_000L)));
        buffPrices.forEach((key, costs) -> config.set("island-buffs." + key + ".costs", costs));

        config.set("coremc-config-version", 12);
        saveConfig();
        getLogger().info("Applied CoreMC 0.12.0 spawner progression and island price migration.");
    }

    @Override
    public void onDisable() {
        shutdownServices();
    }

    private void shutdownServices() {
        // Restore staff state before scheduled work is cancelled.
        if (moderationService != null) {
            moderationService.shutdown();
        }
        if (eventService != null) {
            eventService.shutdown();
        }
        if (scoreboardService != null) {
            scoreboardService.shutdown();
        }
        if (companionService != null) {
            companionService.shutdown();
        }
        // Stop scheduled work first so nothing touches dead services.
        if (taskService != null) {
            taskService.cancelAll();
        }
        // Remove hat overlay entities while the plugin is still enabled
        // (players keep a clean state; overlays are respawned on next join).
        if (hatOverlayService != null) {
            hatOverlayService.shutdown();
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
        if (spawnerService != null) {
            spawnerService.stopWatchdog();
        }
        if (placeableService != null) {
            placeableService.save(); // shutdown-critical: never lose placed blocks
            placeableService = null;
        }
        this.generatorService = null;
        this.equipmentSetService = null;
        this.companionService = null;
        this.questService = null;
        this.spawnerService = null;
        this.shopService = null;
        this.islandService = null;
        this.islandActivityEffects = null;
        this.islandProgressService = null;
        this.guiService = null;
        this.cosmeticSkinService = null;
        this.skinService = null;
        this.hatOverlayService = null;
        this.tagService = null;
        this.chatStyleService = null;
        this.chatFormatService = null;
        this.rankService = null;
        this.moderationService = null;
        this.eventService = null;
        this.scoreboardService = null;
        this.omniToolService = null;
        this.roleService = null;
        getLogger().info("CoreMC disabled — all player data saved, all tasks cancelled.");
    }

    /** Reloads config.yml + messages.yml and re-applies settings. */
    public void reloadCoreConfig() {
        coreConfig.load();
        messageService.load();
        eventService.load();
        if (scoreboardService != null) scoreboardService.refreshNow();
        // Re-apply the autosave interval with fresh configuration.
        playerDataService.startAutosave(coreConfig.autosaveSeconds());
        skinService.load();
        for (final org.bukkit.entity.Player online : getServer().getOnlinePlayers()) {
            playerDataService.profileOf(online.getUniqueId())
                    .ifPresent(profile -> hatOverlayService.syncWithProfile(online, profile));
        }
        themeService.load();
        miningCubeService.load();
        spawnerService.load();
        generatorService.load();
        companionService.load();
        questService.load();
        shopService.loadCatalogue();
        omniToolService.load();
        enchantService.load();
        keyService.load();
        tagService.load();
        chatStyleService.load();
        chatFormatService.load();
        rankService.load();
        rankService.startRefreshTask();
        crateService.load();
        islandActivityEffects.clearCaches();
        moderationService.reload();
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

        final PluginCommand event = getCommand("event");
        if (event == null) {
            throw new IllegalStateException("Command 'event' missing from plugin.yml");
        }
        event.setExecutor(new com.coremc.core.event.EventCommand(this));

        final PluginCommand hud = getCommand("hud");
        if (hud == null) {
            throw new IllegalStateException("Command 'hud' missing from plugin.yml");
        }
        hud.setExecutor(new com.coremc.core.scoreboard.HudCommand(this));

        final PluginCommand role = getCommand("role");
        if (role == null) {
            throw new IllegalStateException("Command 'role' missing from plugin.yml");
        }
        final RoleCommand roleCommand = new RoleCommand(this);
        role.setExecutor(roleCommand);
        role.setTabCompleter(roleCommand);

        final PluginCommand skins = getCommand("skins");
        if (skins == null) {
            throw new IllegalStateException("Command 'skins' missing from plugin.yml");
        }
        final SkinsCommand skinsCommand = new SkinsCommand(this);
        skins.setExecutor(skinsCommand);
        skins.setTabCompleter(skinsCommand);

        final PluginCommand gens = getCommand("gens");
        if (gens == null) {
            throw new IllegalStateException("Command 'gens' missing from plugin.yml");
        }
        gens.setExecutor(new GensCommand(this));

        final PluginCommand companions = getCommand("companions");
        if (companions == null) {
            throw new IllegalStateException("Command 'companions' missing from plugin.yml");
        }
        companions.setExecutor(new CompanionsCommand(this));

        final PluginCommand quests = getCommand("quests");
        if (quests == null) {
            throw new IllegalStateException("Command 'quests' missing from plugin.yml");
        }
        quests.setExecutor(new QuestsCommand(this));

        final PluginCommand spawners = getCommand("spawners");
        if (spawners == null) {
            throw new IllegalStateException("Command 'spawners' missing from plugin.yml");
        }
        spawners.setExecutor(new SpawnersCommand(this));

        final PluginCommand sets = getCommand("sets");
        if (sets == null) {
            throw new IllegalStateException("Command 'sets' missing from plugin.yml");
        }
        sets.setExecutor(equipmentSetService);
        sets.setTabCompleter(equipmentSetService);

        final PluginCommand sell = getCommand("sell");
        if (sell == null) {
            throw new IllegalStateException("Command 'sell' missing from plugin.yml");
        }
        sell.setExecutor(new com.coremc.core.spawner.SellCommand(this));

        final PluginCommand store = getCommand("store");
        if (store == null) throw new IllegalStateException("Command 'store' missing from plugin.yml");
        store.setExecutor(new com.coremc.core.shop.StoreCommand(this));

        final PluginCommand crates = getCommand("crates");
        if (crates == null) {
            throw new IllegalStateException("Command 'crates' missing from plugin.yml");
        }
        crates.setExecutor(new CratesCommand(this));

        final PluginCommand tags = getCommand("tags");
        if (tags == null) {
            throw new IllegalStateException("Command 'tags' missing from plugin.yml");
        }
        final com.coremc.core.chat.TagsCommand tagsCommand = new com.coremc.core.chat.TagsCommand(this);
        tags.setExecutor(tagsCommand);
        tags.setTabCompleter(tagsCommand);

        final PluginCommand chatColour = getCommand("chatcolour");
        if (chatColour == null) {
            throw new IllegalStateException("Command 'chatcolour' missing from plugin.yml");
        }
        final com.coremc.core.chat.ChatColourCommand chatColourCommand =
                new com.coremc.core.chat.ChatColourCommand(this);
        chatColour.setExecutor(chatColourCommand);
        chatColour.setTabCompleter(chatColourCommand);

        final ShopCommand shopCommand = new ShopCommand(this);
        for (final String name : new String[] {"shop", "tokenshop"}) {
            final PluginCommand cmd = getCommand(name);
            if (cmd == null) {
                throw new IllegalStateException("Command '" + name + "' missing from plugin.yml");
            }
            cmd.setExecutor(shopCommand);
            cmd.setTabCompleter(shopCommand);
        }

        registerModerationCommands();
    }

    private void registerModerationCommands() {
        final com.coremc.core.moderation.StaffToolCommand staffTools =
                new com.coremc.core.moderation.StaffToolCommand(this);
        for (final String name : new String[] {"vanish", "spectate", "cps", "rotate", "freeze"}) {
            final PluginCommand command = getCommand(name);
            if (command == null) {
                throw new IllegalStateException("Command '" + name + "' missing from plugin.yml");
            }
            command.setExecutor(staffTools);
            command.setTabCompleter(staffTools);
        }

        final com.coremc.core.moderation.PunishmentCommand punishments =
                new com.coremc.core.moderation.PunishmentCommand(this);
        for (final String name : new String[] {"kick", "mute", "ban", "unmute", "unban"}) {
            final PluginCommand command = getCommand(name);
            if (command == null) {
                throw new IllegalStateException("Command '" + name + "' missing from plugin.yml");
            }
            command.setExecutor(punishments);
            command.setTabCompleter(punishments);
        }

        for (int tier = 1; tier <= 5; tier++) {
            final String name = "t" + tier;
            final PluginCommand command = getCommand(name);
            if (command == null) {
                throw new IllegalStateException("Command '" + name + "' missing from plugin.yml");
            }
            final com.coremc.core.moderation.TierCommand handler =
                    new com.coremc.core.moderation.TierCommand(this, tier);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        final PluginCommand history = getCommand("history");
        if (history == null) {
            throw new IllegalStateException("Command 'history' missing from plugin.yml");
        }
        final com.coremc.core.moderation.HistoryCommand historyCommand =
                new com.coremc.core.moderation.HistoryCommand(this);
        history.setExecutor(historyCommand);
        history.setTabCompleter(historyCommand);

        final PluginCommand tierCorrect = getCommand("tiercorrect");
        if (tierCorrect == null) {
            throw new IllegalStateException("Command 'tiercorrect' missing from plugin.yml");
        }
        final com.coremc.core.moderation.TierCorrectionCommand correctionCommand =
                new com.coremc.core.moderation.TierCorrectionCommand(this);
        tierCorrect.setExecutor(correctionCommand);
        tierCorrect.setTabCompleter(correctionCommand);
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

    /** Recurring event schedule and live reward multipliers. */
    public com.coremc.core.event.EventService events() {
        return eventService;
    }

    /** Live player sidebar and /hud visibility toggle. */
    public com.coremc.core.scoreboard.ScoreboardService scoreboard() {
        return scoreboardService;
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

    /** Visual-only generator/companion skin layer. */
    public CosmeticSkinService cosmeticSkins() {
        return cosmeticSkinService;
    }

    /** Animated tool/hat skins: catalog, ownership, selection, grant hooks. */
    public SkinService skins() {
        return skinService;
    }

    /** Animated hat overlays (ItemDisplay riding the player's head). */
    public HatOverlayService hatOverlay() {
        return hatOverlayService;
    }

    /** Cosmetic chat tags (catalogue, ownership, selection, reward hooks). */
    public com.coremc.core.chat.TagService tags() {
        return tagService;
    }

    /** Chat colours and gradients (/chatcolour). */
    public com.coremc.core.chat.ChatStyleService chatStyles() {
        return chatStyleService;
    }

    /** Rank prefix resolution for the chat format. */
    public com.coremc.core.chat.RankService ranks() {
        return rankService;
    }

    /** Public-chat layout: &lt;RANK&gt; &lt;TAG&gt; Player: Message. */
    public com.coremc.core.chat.ChatFormatService chatFormat() {
        return chatFormatService;
    }

    /** Staff tools, punishments and moderation persistence. */
    public com.coremc.core.moderation.ModerationService moderation() {
        return moderationService;
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

    /** Earnable, summonable gameplay companions and their progression. */
    public CompanionService companions() {
        return companionService;
    }

    /** Assigned daily missions, gameplay progress and claims. */
    public QuestService quests() {
        return questService;
    }

    /** Mob-specific rare drops and their Core Money sale values. */
    public RareDropService rareDrops() {
        return rareDropService;
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
