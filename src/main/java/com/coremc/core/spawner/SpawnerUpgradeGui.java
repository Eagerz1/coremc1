package com.coremc.core.spawner;

import com.coremc.core.essence.EssenceManager;
import com.coremc.core.essence.EssenceType;
import com.coremc.core.shop.EconomyService;
import com.coremc.core.shop.Money;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The Spawner Upgrade window (small chest, 27 slots):
 *
 * <pre>
 * [ 11 ] the current spawner stack, [ 13 ] the upgrade button with the
 * ✔ / ✖ requirement checklist, [ 15 ] the player's progress book,
 * [ 22 ] close.
 * </pre>
 *
 * The checklist re-renders from live player state on every open, so
 * the ticks always tell the truth.
 */
public final class SpawnerUpgradeGui {

    /** Small chest. */
    static final int SIZE = 27;

    static final int SLOT_CURRENT = 11;
    static final int SLOT_BUTTON = 13;
    static final int SLOT_PROGRESS = 15;
    static final int SLOT_CLOSE = 22;

    private final SpawnerService spawners;
    private final EconomyService economy;
    private final EssenceManager essences;
    private final SpawnerConfig config;

    public SpawnerUpgradeGui(final SpawnerService spawners, final EconomyService economy,
                             final EssenceManager essences, final SpawnerConfig config) {
        this.spawners = spawners;
        this.economy = economy;
        this.essences = essences;
        this.config = config;
    }

    /** Opens the upgrade window for a resolved upgrade context. */
    public void open(final Player player, final SpawnerService.UpgradeContext context) {
        final SpawnerUpgradeHolder holder = new SpawnerUpgradeHolder(context);
        final Inventory inventory = Bukkit.createInventory(holder, SIZE,
                ColorUtil.colorize("&3&lCOREMC &8— &bUpgrade Spawner"));
        holder.inventory(inventory);

        inventory.setItem(SLOT_CURRENT, currentItem(context));
        inventory.setItem(SLOT_BUTTON, buttonItem(player, context));
        inventory.setItem(SLOT_PROGRESS, progressItem(player, context));
        inventory.setItem(SLOT_CLOSE, closeItem());
        fillEmpty(inventory);

        player.openInventory(inventory);
    }

    /** Re-opens (refreshes the ticks) after a failed attempt. */
    public void refresh(final Player player, final SpawnerService.UpgradeContext context) {
        open(player, context);
    }

    private ItemStack currentItem(final SpawnerService.UpgradeContext context) {
        final ItemStack stack = spawners.spawnerItem(context.mob(), context.entry().variant(),
                context.entry().amount());
        final ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            final List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(0, ColorUtil.colorize("&7Currently:"));
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack buttonItem(final Player player, final SpawnerService.UpgradeContext context) {
        final SpawnerMob mob = context.mob();
        final int stackScale = context.entry().amount();
        final List<String> lore = new ArrayList<>();
        lore.add(ColorUtil.colorize("&7Upgrading to: &f"
                + mob.name().toUpperCase() + " SPAWNER &8[&f"
                + context.next().display().toUpperCase() + "&8]"));
        lore.add("");
        lore.add(ColorUtil.colorize("&7Requirements"
                + (stackScale > 1 ? " &8(x" + stackScale + " stack)" : "") + ":"));
        boolean allMet = true;
        for (final UpgradeRequirement requirement : context.requirements()) {
            final double need = requirement.amount() * stackScale;
            final double have = spawners.hasFor(player, requirement, mob);
            if (have + 1e-9 < need) {
                allMet = false;
            }
            lore.add(UpgradeLore.line(
                    new UpgradeRequirement(requirement.type(), requirement.essence(),
                            requirement.mobId(), need),
                    have, dropName(spawners, requirement, mob), "$"));
        }
        lore.add("");
        lore.add(ColorUtil.colorize(allMet ? "&eClick to upgrade!"
                : "&cYou are missing requirements"));

        final ItemStack stack = new ItemStack(Material.SPAWNER);
        final ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(
                    "&e&lUpgrade to " + context.next().display()));
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack progressItem(final Player player, final SpawnerService.UpgradeContext context) {
        final ItemStack stack = new ItemStack(Material.BOOK);
        final ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize("&b&lYour progress"));
            meta.setLore(List.of(
                    ColorUtil.colorize("&7Coins: &f"
                            + Money.format(economy.balance(player.getUniqueId()), "$")),
                    ColorUtil.colorize("&7" + EssenceType.SLAYER.display() + ": &f"
                            + EssenceManager.format(essences.balance(player.getUniqueId(), EssenceType.SLAYER))),
                    ColorUtil.colorize("&7" + EssenceType.MINING.display() + ": &f"
                            + EssenceManager.format(essences.balance(player.getUniqueId(), EssenceType.MINING))),
                    ColorUtil.colorize("&7" + EssenceType.FARMING.display() + ": &f"
                            + EssenceManager.format(essences.balance(player.getUniqueId(), EssenceType.FARMING))),
                    ColorUtil.colorize("&7Mob Kills: &f"
                            + EssenceManager.format(essences.kills(player.getUniqueId()))),
                    "",
                    ColorUtil.colorize("&7Kills are a lifetime record —")
            ));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    static String dropName(final SpawnerService spawners, final UpgradeRequirement requirement,
                           final SpawnerMob ownMob) {
        final SpawnerMob mob = spawners.dropMobFor(requirement, ownMob);
        return mob == null ? "?" : mob.dropName();
    }

    private ItemStack closeItem() {
        final ItemStack stack = new ItemStack(Material.BARRIER);
        final ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize("&c&lClose"));
            meta.setLore(List.of(ColorUtil.colorize("&7Close the upgrade window.")));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void fillEmpty(final Inventory inventory) {
        final ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        final ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize("&r"));
            filler.setItemMeta(meta);
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }
}
