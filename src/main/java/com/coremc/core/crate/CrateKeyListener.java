package com.coremc.core.crate;

import com.coremc.core.CoreMCPlugin;
import java.util.Map;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Opens any CoreMC key by right-clicking it, wherever the player is. */
public final class CrateKeyListener implements Listener {
    private final CoreMCPlugin plugin;

    public CrateKeyListener(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onRightClick(final PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR
                        && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        final String keyId = plugin.keys().keyIdOf(event.getItem()).orElse(null);
        if (keyId == null) return;
        final CrateDefinition crate = plugin.crates().crateForKey(keyId).orElse(null);
        event.setCancelled(true);
        if (crate == null) {
            plugin.messages().sendPrefixed(event.getPlayer(), "crate.key-unconfigured", Map.of());
            return;
        }
        if (plugin.crates().isLootbox(crate) && event.getClickedBlock() != null) {
            final org.bukkit.block.Block target = event.getClickedBlock().getRelative(event.getBlockFace());
            if (target.getType().isAir() || target.isPassable()) {
                final var rewards = plugin.crates().beginLootbox(event.getPlayer(), crate, keyId);
                if (rewards.size() == 9) {
                    new LootboxWorldAnimation(plugin, event.getPlayer(), crate,
                            event.getItem().clone(), target.getLocation(), rewards).start();
                }
                return;
            }
        }
        final CrateOpeningGui opening = new CrateOpeningGui(plugin, crate.id(), keyId);
        plugin.gui().open(event.getPlayer(), opening);
        opening.start(event.getPlayer());
    }
}
