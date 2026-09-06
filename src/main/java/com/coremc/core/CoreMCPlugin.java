package com.coremc.core;

import com.coremc.core.command.CoreMCCommand;
import com.coremc.core.command.HealCommand;
import com.coremc.core.command.ProfileCommand;
import com.coremc.core.island.IslandCommand;
import com.coremc.core.island.IslandService;
import com.coremc.core.island.YamlIslandDataStore;
import com.coremc.core.config.CoreConfig;
import com.coremc.core.config.MessageService;
import com.coremc.core.player.PlayerDataService;
import com.coremc.core.player.PlayerListener;
import com.coremc.core.player.YamlPlayerDataStore;
import com.coremc.core.scheduler.TaskService;
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
    private IslandService islandService;

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

        // 3b. Island registry (loads async from plugins/CoreMC/islands/).
        this.islandService =
                new IslandService(this, coreConfig, new YamlIslandDataStore(getDataFolder().toPath().resolve("islands")),
                        taskService);
        this.islandService.start();
        if (this.islandService.islandWorld().isEmpty()) {
            getLogger().warning("Island world '" + coreConfig.islandWorldName()
                    + "' does not exist — /island commands will report it as unavailable.");
        }

        // 4. Listeners.
        final PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(
                new PlayerListener(playerDataService, messageService, coreConfig), this);

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
        this.islandService = null;
        getLogger().info("CoreMC disabled — all player data saved, all tasks cancelled.");
    }

    /** Reloads config.yml + messages.yml and re-applies settings. */
    public void reloadCoreConfig() {
        coreConfig.load();
        messageService.load();
        // Re-apply the autosave interval with fresh configuration.
        playerDataService.startAutosave(coreConfig.autosaveSeconds());
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

    /** Island lifecycle service. */
    public IslandService islands() {
        return islandService;
    }
}
