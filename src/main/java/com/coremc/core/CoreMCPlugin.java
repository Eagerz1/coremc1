package com.coremc.core;

import com.coremc.core.config.CoreConfig;
import com.coremc.core.config.MessageService;
import com.coremc.core.island.IslandCommand;
import com.coremc.core.island.IslandListener;
import com.coremc.core.island.IslandService;
import com.coremc.core.island.SchematicService;
import com.coremc.core.shop.EconomyService;
import com.coremc.core.spawner.SpawnerCommand;
import com.coremc.core.spawner.SpawnerConfig;
import com.coremc.core.spawner.SpawnerListener;
import com.coremc.core.spawner.SpawnerService;
import com.coremc.core.spawner.YamlSpawnerDataStore;
import com.coremc.core.shop.ShopCommand;
import com.coremc.core.shop.ShopConfig;
import com.coremc.core.shop.ShopGui;
import com.coremc.core.shop.ShopListener;
import com.coremc.core.shop.YamlEconomyStore;
import com.coremc.core.world.IslandWorldService;
import java.nio.file.Path;
import org.bukkit.command.PluginCommand;
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
    private SpawnerConfig spawnerConfig;
    private SpawnerService spawnerService;

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

        this.islands = new IslandService(this, coreConfig, messages, worlds, schematics);
        this.islands.load();

        final IslandCommand command = new IslandCommand(islands, messages);
        final PluginCommand islandCommand = getCommand("island");
        if (islandCommand != null) {
            islandCommand.setExecutor(command);
            islandCommand.setTabCompleter(command);
        } else {
            getLogger().severe("Command 'island' missing from plugin.yml — /is will not work.");
        }

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
        if (economy != null) {
            pluginManager.registerEvents(
                    new ShopListener(shopConfig, economy, shopGui, messages), this);
        }

        // Spawners: progression config (spawners.yml) + placed spawner
        // registry (spawners-data.yml). A broken config disables the
        // spawner system with a loud log line, never the plugin.
        this.spawnerConfig = new SpawnerConfig(this);
        this.spawnerService = null;
        try {
            spawnerConfig.load();
            if (economy == null) {
                getLogger().severe("Spawners disabled — no economy (the shop failed to load).");
            } else {
                this.spawnerService = new SpawnerService(
                        this, spawnerConfig,
                        new YamlSpawnerDataStore(
                                Path.of(getDataFolder().getPath(), "spawners-data.yml"), getLogger()),
                        economy, messages, islands);
                spawnerService.load();
                pluginManager.registerEvents(new SpawnerListener(spawnerService), this);
                islands.onDelete(spawnerService::onIslandDeleted);
                final PluginCommand spawnerCommand = getCommand("spawner");
                if (spawnerCommand != null) {
                    final SpawnerCommand executor = new SpawnerCommand(spawnerConfig, spawnerService, messages);
                    spawnerCommand.setExecutor(executor);
                    spawnerCommand.setTabCompleter(executor);
                } else {
                    getLogger().severe("Command 'spawner' missing from plugin.yml — /spawner will not work.");
                }
            }
        } catch (final RuntimeException exception) {
            getLogger().severe("Spawners disabled — " + exception.getMessage());
            this.spawnerService = null;
        }

        getLogger().info("CoreMC enabled: " + islands.count() + " island(s), world '"
                + worlds.islandWorld().getName() + "'.");
    }

    @Override
    public void onDisable() {
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
}
