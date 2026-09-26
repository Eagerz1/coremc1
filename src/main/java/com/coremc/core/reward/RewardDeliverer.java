package com.coremc.core.reward;

import com.coremc.core.crate.CrateConfig;
import com.coremc.core.crate.KeyDef;
import com.coremc.core.crate.KeyItems;
import com.coremc.core.credits.CreditReason;
import com.coremc.core.credits.CreditService;
import com.coremc.core.credits.SkyTokenService;
import com.coremc.core.lootbox.LootboxConfig;
import com.coremc.core.lootbox.LootboxDef;
import com.coremc.core.lootbox.LootboxItems;
import com.coremc.core.shop.EconomyService;
import com.coremc.core.store.StoreItemTags;
import com.coremc.core.util.GuiItems;
import com.coremc.core.util.GuiText;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Turns {@link RewardGrant}s into real value: balances are credited,
 * physical items are rebuilt from their config-owned factories and
 * placed into the inventory, commands run on the console.
 *
 * <p>The contract with {@link PendingRewards}: return {@code true}
 * only when the grant is fully in the player's hands. Item grants that
 * do not fit return {@code false} and stay pending — a full inventory
 * can never destroy a paid reward.</p>
 */
public final class RewardDeliverer {

    private final EconomyService economy;
    private final SkyTokenService tokens;
    private final CreditService credits;
    private final CrateConfig crateConfig;
    private final KeyItems keyItems;
    private final LootboxConfig lootboxConfig;
    private final LootboxItems lootboxItems;
    private final StoreItemTags tags;
    private final Logger logger;

    public RewardDeliverer(final EconomyService economy, final SkyTokenService tokens,
                           final CreditService credits, final CrateConfig crateConfig,
                           final KeyItems keyItems, final LootboxConfig lootboxConfig,
                           final LootboxItems lootboxItems, final StoreItemTags tags,
                           final Logger logger) {
        this.economy = economy;
        this.tokens = tokens;
        this.credits = credits;
        this.crateConfig = crateConfig;
        this.keyItems = keyItems;
        this.lootboxConfig = lootboxConfig;
        this.lootboxItems = lootboxItems;
        this.tags = tags;
        this.logger = logger;
    }

    /**
     * Delivers one grant to an online player. Returns true when the
     * grant is safely delivered; false keeps it pending.
     */
    public boolean deliver(final Player player, final RewardGrant grant) {
        switch (grant.type()) {
            case MONEY -> {
                if (economy == null) {
                    return false;
                }
                economy.deposit(player.getUniqueId(), grant.amount());
                return true;
            }
            case SKY_TOKENS -> {
                if (tokens == null) {
                    return false;
                }
                tokens.add(player.getUniqueId(), grant.amount());
                return true;
            }
            case CREDITS -> {
                if (credits == null) {
                    return false;
                }
                credits.add(player.getUniqueId(), grant.amount(), CreditReason.EVENT,
                        "reward grant");
                return true;
            }
            case KEY -> {
                final KeyDef key = crateConfig == null ? null : crateConfig.key(grant.id());
                if (key == null) {
                    logger.warning("Cannot deliver key grant '" + grant.id()
                            + "' — no such key configured. Keeping it pending.");
                    return false;
                }
                return giveStacks(player, keyItems.build(key, 1), grant.amount());
            }
            case LOOTBOX -> {
                final LootboxDef box = lootboxConfig == null ? null
                        : lootboxConfig.byId(grant.id());
                if (box == null) {
                    logger.warning("Cannot deliver lootbox grant '" + grant.id()
                            + "' — no such lootbox configured. Keeping it pending.");
                    return false;
                }
                return giveStacks(player, lootboxItems.build(box, 1), grant.amount());
            }
            case ITEM -> {
                final Material material = Material.matchMaterial(grant.extra());
                if (material == null || !material.isItem()) {
                    logger.warning("Cannot deliver item grant '" + grant.id()
                            + "' — unknown material '" + grant.extra() + "'. Keeping it pending.");
                    return false;
                }
                return giveStacks(player, materialItem(material, grant), grant.amount());
            }
            case COMMAND -> {
                for (final String command : grant.commands()) {
                    final String line = command.replace("%player%", player.getName())
                            .replace("%uuid%", player.getUniqueId().toString());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** A one-line chat description of a grant: {@code &f12,000 &7Money}. */
    public static String describe(final RewardGrant grant) {
        final String amount = GuiText.number(grant.amount());
        return switch (grant.type()) {
            case MONEY -> "&a$" + amount;
            case SKY_TOKENS -> "&b" + amount + " " + GuiText.caps("Sky Tokens");
            case CREDITS -> "&e" + amount + " " + GuiText.caps("Credits");
            default -> (grant.amount() > 1 ? "&f" + amount + "x " : "")
                    + colorKeep(grant.display());
        };
    }

    private static String colorKeep(final String display) {
        return display.startsWith("&") ? GuiText.caps(display) : "&f" + GuiText.caps(display);
    }

    /** Builds a PDC-tagged reward material item (Core Fragment, boosters, …). */
    private ItemStack materialItem(final Material material, final RewardGrant grant) {
        final ItemStack stack = GuiItems.item(material, GuiText.caps(grant.display()),
                "&8" + GuiText.caps("CoreMC reward material"));
        StoreItemTags.write(stack, tags.materialTag(), grant.id());
        return stack;
    }

    /**
     * Adds {@code amount} copies of a template item, splitting into
     * stacks. All-or-nothing: when the inventory cannot hold everything,
     * nothing is added and the grant stays pending — no partial loss.
     */
    private boolean giveStacks(final Player player, final ItemStack template, final long amount) {
        if (amount <= 0) {
            return true;
        }
        final int total = (int) Math.min(amount, 64L * 36);
        final int maxStack = Math.max(1, template.getMaxStackSize());
        // capacity check first: count how many would fit
        if (!fits(player, template, total, maxStack)) {
            return false;
        }
        int remaining = total;
        while (remaining > 0) {
            final ItemStack stack = template.clone();
            final int size = Math.min(maxStack, remaining);
            stack.setAmount(size);
            final Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            if (!leftover.isEmpty()) {
                // should not happen after the capacity check; keep the
                // remainder pending rather than dropping it
                int lost = 0;
                for (final ItemStack item : leftover.values()) {
                    lost += item.getAmount();
                }
                logger.warning("Inventory refused " + lost + "x " + template.getType()
                        + " after capacity check for " + player.getName() + " — kept pending.");
                return false;
            }
            remaining -= size;
        }
        return true;
    }

    /** Whether {@code total} copies of the template fit the main inventory. */
    private static boolean fits(final Player player, final ItemStack template, final int total,
                                final int maxStack) {
        int capacity = 0;
        final ItemStack[] storage = player.getInventory().getStorageContents();
        for (final ItemStack slot : storage) {
            if (slot == null || slot.getType() == Material.AIR) {
                capacity += maxStack;
            } else if (slot.isSimilar(template)) {
                capacity += Math.max(0, maxStack - slot.getAmount());
            }
            if (capacity >= total) {
                return true;
            }
        }
        return capacity >= total;
    }

    /** Rolls a pool once: picks a def and a concrete amount. */
    public static RewardGrant roll(final WeightedTable table, final java.util.Random random) {
        final RewardDef def = table.pick(random.nextDouble());
        return RewardGrant.of(def, def.rollAmount(random.nextDouble()));
    }

    /** Rolls a def list (already validated) N times. */
    public static List<RewardGrant> rollAll(final WeightedTable table, final int rolls,
                                            final java.util.Random random) {
        final java.util.ArrayList<RewardGrant> grants = new java.util.ArrayList<>(rolls);
        for (int index = 0; index < rolls; index++) {
            grants.add(roll(table, random));
        }
        return grants;
    }
}
