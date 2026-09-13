package com.coremc.core;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * CoreMC — ground-up rewrite.
 *
 * All previous feature code was removed for a clean restart. This skeleton
 * only exists so the build pipeline has a valid entry point; features will
 * be rebuilt from here.
 */
public final class CoreMCPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("CoreMC enabled (fresh build — no features yet).");
    }

    @Override
    public void onDisable() {
        getLogger().info("CoreMC disabled.");
    }
}
