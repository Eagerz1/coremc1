package com.coremc.core;

import com.coremc.core.config.CoreConfig;
import com.coremc.core.config.MessageService;
import com.coremc.core.island.IslandCommand;
import com.coremc.core.island.IslandListener;
import com.coremc.core.island.IslandService;
import com.coremc.core.island.SchematicService;
import com.coremc.core.world.IslandWorldService;
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

        getLogger().info("CoreMC enabled: " + islands.count() + " island(s), world '"
                + worlds.islandWorld().getName() + "'.");
    }

    @Override
    public void onDisable() {
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

    public IslandService islands() {
        return islands;
    }
}
