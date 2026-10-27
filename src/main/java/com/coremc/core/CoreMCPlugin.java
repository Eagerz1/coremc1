package com.coremc.core;

import com.coremc.core.config.CoreConfig;
import com.coremc.core.config.MessageService;
import com.coremc.core.crate.CrateService;
import com.coremc.core.crate.KeyService;
import com.coremc.core.economy.EconomyService;
import com.coremc.core.enchant.EnchantEngine;
import com.coremc.core.enchant.EnchantService;
import com.coremc.core.gen.GeneratorService;
import com.coremc.core.gui.GuiService;
import com.coremc.core.island.IslandService;
import com.coremc.core.island.IslandActivityEffects;
import com.coremc.core.island.IslandBuffService;
import com.coremc.core.island.IslandProgressService;
import com.coremc.core.island.IslandUpgradeEffects;
import com.coremc.core.island.MiningCubeService;
import com.coremc.core.island.ThemeService;
import com.coremc.core.placeable.PlaceableService;
import com.coremc.core.player.PlayerDataService;
import com.coremc.core.role.OmniToolService;
import com.coremc.core.role.RoleService;
import com.coremc.core.scheduler.TaskService;
import com.coremc.core.shop.ShopService;
import com.coremc.core.spawner.SpawnerService;
import org.bukkit.plugin.java.JavaPlugin;

/** Bukkit entry point and stable typed facade over the runtime service graph. */
public final class CoreMCPlugin extends JavaPlugin {

    private CoreMCRuntime runtime;

    @Override
    public void onEnable() {
        runtime = new CoreMCRuntime(this);
        runtime.enable();
    }

    @Override
    public void onDisable() {
        final CoreMCRuntime current = runtime;
        if (current != null) {
            current.shutdown();
        }
    }

    /** Reloads configuration and live catalogues without recreating services. */
    public void reloadCoreConfig() {
        runtime.reloadCoreConfig();
    }

    public TaskService tasks() { return runtime == null ? null : runtime.tasks(); }
    public CoreConfig coreConfig() { return runtime == null ? null : runtime.coreConfig(); }
    public MessageService messages() { return runtime == null ? null : runtime.messages(); }
    public PlayerDataService playerData() { return runtime == null ? null : runtime.playerData(); }
    public EconomyService economy() { return runtime == null ? null : runtime.economy(); }
    public IslandService islands() { return runtime == null ? null : runtime.islands(); }
    public ThemeService themes() { return runtime == null ? null : runtime.themes(); }
    public MiningCubeService miningCube() { return runtime == null ? null : runtime.miningCube(); }
    public IslandUpgradeEffects upgradeEffects() { return runtime == null ? null : runtime.upgradeEffects(); }
    public IslandActivityEffects islandActivity() { return runtime == null ? null : runtime.islandActivity(); }
    public IslandProgressService islandProgress() { return runtime == null ? null : runtime.islandProgress(); }
    public IslandBuffService islandBuffs() { return runtime == null ? null : runtime.islandBuffs(); }
    public GuiService gui() { return runtime == null ? null : runtime.gui(); }
    public OmniToolService omniTool() { return runtime == null ? null : runtime.omniTool(); }
    public RoleService roles() { return runtime == null ? null : runtime.roles(); }
    public PlaceableService placeables() { return runtime == null ? null : runtime.placeables(); }
    public GeneratorService generators() { return runtime == null ? null : runtime.generators(); }
    public SpawnerService spawners() { return runtime == null ? null : runtime.spawners(); }
    public ShopService shop() { return runtime == null ? null : runtime.shop(); }
    public EnchantService enchants() { return runtime == null ? null : runtime.enchants(); }
    public EnchantEngine enchantEngine() { return runtime == null ? null : runtime.enchantEngine(); }
    public KeyService keys() { return runtime == null ? null : runtime.keys(); }
    public CrateService crates() { return runtime == null ? null : runtime.crates(); }
}
