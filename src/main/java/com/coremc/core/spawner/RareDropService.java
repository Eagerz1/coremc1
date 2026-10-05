package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Mints, identifies and sells mob-specific rare drops from CoreMC spawners. */
public final class RareDropService {

    private final CoreMCPlugin plugin;
    private final NamespacedKey dropIdKey;
    private final NamespacedKey enchantedKey;

    public RareDropService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.dropIdKey = new NamespacedKey(plugin, "rare-drop-id");
        this.enchantedKey = new NamespacedKey(plugin, "rare-drop-enchanted");
    }

    /** Called after a spawner-born kill passes CoreMC's shared kill cap. */
    public void tryDrop(final Player player, final SpawnerDefinition mob, final long mobKills) {
        final long unlockKills = Math.max(1L,
                plugin.getConfig().getLong("spawners.rare-drops.unlock-kills", 1000L));
        if (mobKills < unlockKills) return;
        final double chance = clamp(plugin.getConfig().getDouble("spawners.rare-drops.chance", 0.10));
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= chance) return;

        final double enchantedChance = clamp(
                plugin.getConfig().getDouble("spawners.rare-drops.enchanted-chance", 0.10));
        final boolean enchanted = java.util.concurrent.ThreadLocalRandom.current().nextDouble() < enchantedChance;
        final ItemStack item = create(mob, enchanted);
        final var result = com.coremc.core.util.ItemDelivery.deliverDetailed(player, item);
        if (result == com.coremc.core.util.ItemDelivery.Result.FAILED) {
            player.sendMessage(ColorUtil.colorize("&cYour rare drop could not be delivered. Free inventory space and contact staff."));
            return;
        }
        player.sendMessage(ColorUtil.colorize("&dRare drop: " + item.getItemMeta().getDisplayName()));
    }

    public ItemStack create(final SpawnerDefinition mob, final boolean enchanted) {
        final Material material = dropMaterial(mob.entityType());
        final ItemStack item = new ItemStack(material);
        final ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            final String prefix = enchanted ? "&5&lEnchanted " : "&d";
            meta.setDisplayName(ColorUtil.colorize(prefix + mob.display() + " Relic"));
            meta.setLore(java.util.List.of(
                    ColorUtil.colorize("&7A rare drop from &f" + mob.display() + "&7."),
                    ColorUtil.colorize("&7Sell with &f/sell"),
                    ColorUtil.colorize(enchanted
                            ? "&5Worth 50x the normal relic."
                            : "&dWorth &f" + priceFor(mob) + " &dCore Money.")));
            meta.getPersistentDataContainer().set(dropIdKey, PersistentDataType.STRING, mob.id());
            meta.getPersistentDataContainer().set(enchantedKey, PersistentDataType.BYTE, enchanted ? (byte) 1 : (byte) 0);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isRareDrop(final ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(dropIdKey, PersistentDataType.STRING);
    }

    /** Sells only PDC-authenticated CoreMC rare drops; ordinary items are untouched. */
    public long sellAll(final Player player, final PlayerProfile profile) {
        final ItemStack[] contents = player.getInventory().getContents();
        long total = 0L;
        for (final ItemStack item : contents) {
            if (!isRareDrop(item)) continue;
            final long each = valueOf(item);
            total = saturatingAdd(total, saturatingMultiply(each, item.getAmount()));
        }
        if (total <= 0L || !plugin.economy().fitsDeposit(profile, Currency.MONEY, total)) return 0L;

        for (int slot = 0; slot < contents.length; slot++) {
            if (isRareDrop(contents[slot])) contents[slot] = null;
        }
        player.getInventory().setContents(contents);
        plugin.economy().deposit(profile, Currency.MONEY, total);
        return total;
    }

    private long valueOf(final ItemStack item) {
        final var pdc = item.getItemMeta().getPersistentDataContainer();
        final String id = pdc.get(dropIdKey, PersistentDataType.STRING);
        if (id == null) return 0L;
        final SpawnerDefinition mob = plugin.spawners().definition(id).orElse(null);
        if (mob == null) return 0L;
        final boolean enchanted = Byte.valueOf((byte) 1).equals(pdc.get(enchantedKey, PersistentDataType.BYTE));
        return enchanted
                ? saturatingMultiply(priceFor(mob),
                        Math.max(1L, plugin.getConfig().getLong("spawners.rare-drops.enchanted-multiplier", 50L)))
                : priceFor(mob);
    }

    private long priceFor(final SpawnerDefinition mob) {
        final int lane = Math.max(0, plugin.spawners().all().indexOf(mob));
        final long base = Math.max(1L, plugin.getConfig().getLong("spawners.rare-drops.base-value", 500L));
        final long step = Math.max(0L, plugin.getConfig().getLong("spawners.rare-drops.value-per-lane", 100L));
        return saturatingAdd(base, saturatingMultiply(step, lane));
    }

    private static Material dropMaterial(final org.bukkit.entity.EntityType type) {
        return switch (type) {
            case ZOMBIE, HUSK, DROWNED, ZOMBIFIED_PIGLIN -> Material.ROTTEN_FLESH;
            case SKELETON, STRAY, BOGGED, WITHER_SKELETON -> Material.BONE;
            case SPIDER, CAVE_SPIDER -> Material.SPIDER_EYE;
            case CREEPER -> Material.GUNPOWDER;
            case SLIME, MAGMA_CUBE -> Material.SLIME_BALL;
            case ENDERMAN -> Material.ENDER_PEARL;
            case BLAZE -> Material.BLAZE_ROD;
            case GHAST -> Material.GHAST_TEAR;
            case WITCH -> Material.GLOWSTONE_DUST;
            case COW, MOOSHROOM -> Material.LEATHER;
            case PIG, HOGLIN, ZOGLIN -> Material.PORKCHOP;
            case CHICKEN -> Material.FEATHER;
            case SHEEP -> Material.WHITE_WOOL;
            case RABBIT -> Material.RABBIT_HIDE;
            case GUARDIAN, ELDER_GUARDIAN -> Material.PRISMARINE_SHARD;
            case PHANTOM -> Material.PHANTOM_MEMBRANE;
            case BREEZE -> Material.BREEZE_ROD;
            case PILLAGER, VINDICATOR, EVOKER, VEX -> Material.EMERALD;
            case WARDEN -> Material.SCULK_CATALYST;
            default -> Material.AMETHYST_SHARD;
        };
    }

    private static double clamp(final double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static long saturatingAdd(final long left, final long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long saturatingMultiply(final long left, final long right) {
        if (left <= 0L || right <= 0L) return 0L;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }
}
