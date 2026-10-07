package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.scheduler.TaskService;
import com.coremc.core.util.ColorUtil;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Activity-earned role armor sets and their full-set bonuses. */
public final class EquipmentSetService implements Listener, CommandExecutor, TabCompleter {

    private static final List<Piece> PIECES = List.of(
            new Piece("helmet", "Helmet", Material.LEATHER_HELMET),
            new Piece("chestplate", "Chestplate", Material.LEATHER_CHESTPLATE),
            new Piece("leggings", "Leggings", Material.LEATHER_LEGGINGS),
            new Piece("boots", "Boots", Material.LEATHER_BOOTS));

    private static final List<Definition> SETS = List.of(
            new Definition("miner", "Miner", "blocks-mined", 25_000L, 0x78909C, "Haste I"),
            new Definition("farmer", "Farmer", "crops-harvested", 25_000L, 0x66BB6A, "Speed I"),
            new Definition("fisher", "Fisher", "fish-caught", 2_500L, 0x29B6F6, "Water Breathing"),
            new Definition("logger", "Logger", "logs-chopped", 10_000L, 0x8D6E63, "Haste I"),
            new Definition("slayer", "Slayer", "mobs-killed", 5_000L, 0xEF5350, "Strength I"));

    private final CoreMCPlugin plugin;
    private final NamespacedKey setIdKey;
    private final NamespacedKey pieceKey;

    public EquipmentSetService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.setIdKey = new NamespacedKey(plugin, "equipment-set");
        this.pieceKey = new NamespacedKey(plugin, "equipment-set-piece");
    }

    public void start(final TaskService tasks) {
        tasks.runTimer(this::applySetBonuses, 20L, 20L);
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().sendPrefixed(sender, "player-only", Map.of());
            return true;
        }
        final Island island = plugin.islands().islandOf(player.getUniqueId()).orElse(null);
        if (island == null) {
            plugin.messages().sendPrefixed(player, "island.none", Map.of());
            return true;
        }
        final int currentLevel = plugin.islandProgress().levelFor(island);
        final int requiredLevel = IslandProgressionCatalog.requiredIslandLevel("equipment-sets");
        if (currentLevel < requiredLevel) {
            player.sendMessage(ColorUtil.colorize("&cEquipment sets unlock at island level &f" + requiredLevel
                    + "&c. Your island is level &f" + currentLevel + "&c."));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("claim")) {
            claim(player, island, args);
            return true;
        }
        if (args.length >= 1 && !args[0].equalsIgnoreCase("help")) {
            player.sendMessage(ColorUtil.colorize("&cUsage: /sets [claim <role> <piece>]"));
            return true;
        }
        show(player, island);
        return true;
    }

    private void show(final Player player, final Island island) {
        player.sendMessage(ColorUtil.colorize("&6&lRole Equipment Sets &8• &7Earned from island activity"));
        for (final Definition set : SETS) {
            final long progress = island.statOf(set.statKey());
            int ready = 0;
            for (int i = 0; i < PIECES.size(); i++) {
                if (progress >= threshold(set, i)) ready++;
            }
            player.sendMessage(ColorUtil.colorize("&f" + set.name() + " Set &8— &7"
                    + set.statKey().replace('-', ' ') + ": &f" + progress + "/" + set.target()
                    + " &8• &7" + ready + "/4 pieces earned &8• &eFull set: " + set.bonus()));
        }
        player.sendMessage(ColorUtil.colorize("&7Claim an unlocked piece: &f/sets claim <miner|farmer|fisher|logger|slayer> <helmet|chestplate|leggings|boots>"));
    }

    private void claim(final Player player, final Island island, final String[] args) {
        if (args.length != 3) {
            player.sendMessage(ColorUtil.colorize("&cUsage: /sets claim <role> <piece>"));
            return;
        }
        final Definition set = SETS.stream().filter(value -> value.id().equalsIgnoreCase(args[1]))
                .findFirst().orElse(null);
        final Piece piece = PIECES.stream().filter(value -> value.id().equalsIgnoreCase(args[2]))
                .findFirst().orElse(null);
        if (set == null || piece == null) {
            player.sendMessage(ColorUtil.colorize("&cUnknown set or piece. Use &f/sets&c to see options."));
            return;
        }
        final int index = PIECES.indexOf(piece);
        final long needed = threshold(set, index);
        if (island.statOf(set.statKey()) < needed) {
            player.sendMessage(ColorUtil.colorize("&cThis piece requires &f" + needed + " &c"
                    + set.statKey().replace('-', ' ') + " on your island."));
            return;
        }
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            player.sendMessage(ColorUtil.colorize("&cYour profile is still loading."));
            return;
        }
        if (!profile.claimEquipmentSetPiece(island.islandId(), set.id(), piece.id())) {
            player.sendMessage(ColorUtil.colorize("&eYou already claimed that piece for this island."));
            return;
        }
        final ItemStack item = createPiece(set, piece, needed);
        final var delivery = com.coremc.core.util.ItemDelivery.deliverDetailed(player, item);
        if (delivery == com.coremc.core.util.ItemDelivery.Result.FAILED) {
            profile.unclaimEquipmentSetPiece(island.islandId(), set.id(), piece.id());
            player.sendMessage(ColorUtil.colorize("&cMake inventory and ender chest space before claiming this set piece."));
            return;
        }
        plugin.playerData().persistImportant(profile);
        player.sendMessage(ColorUtil.colorize("&aClaimed the " + set.name() + " " + piece.name() + "."));
    }

    private ItemStack createPiece(final Definition set, final Piece piece, final long needed) {
        final ItemStack item = new ItemStack(piece.material());
        if (!(item.getItemMeta() instanceof LeatherArmorMeta meta)) return item;
        meta.setColor(Color.fromRGB(set.rgb()));
        meta.setDisplayName(ColorUtil.colorize("&" + colourCode(set.id()) + set.name() + " " + piece.name()));
        meta.setLore(List.of(
                ColorUtil.colorize("&7Earned through &f" + set.statKey().replace('-', ' ') + "&7."),
                ColorUtil.colorize("&7Island milestone: &f" + needed),
                ColorUtil.colorize("&7Full-set bonus: &e" + set.bonus())));
        meta.getPersistentDataContainer().set(setIdKey, PersistentDataType.STRING, set.id());
        meta.getPersistentDataContainer().set(pieceKey, PersistentDataType.STRING, piece.id());
        item.setItemMeta(meta);
        return item;
    }

    private static long threshold(final Definition set, final int pieceIndex) {
        return (set.target() * (pieceIndex + 1L) + PIECES.size() - 1L) / PIECES.size();
    }

    private static String colourCode(final String id) {
        return switch (id) {
            case "miner" -> "7";
            case "farmer" -> "a";
            case "fisher" -> "b";
            case "logger" -> "6";
            case "slayer" -> "c";
            default -> "f";
        };
    }

    private void applySetBonuses() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final String fullSet = fullSetOf(player);
            if (fullSet == null) continue;
            final PotionEffectType type = switch (fullSet) {
                case "miner", "logger" -> PotionEffectType.HASTE;
                case "farmer" -> PotionEffectType.SPEED;
                case "fisher" -> PotionEffectType.WATER_BREATHING;
                case "slayer" -> PotionEffectType.STRENGTH;
                default -> null;
            };
            if (type != null) {
                player.addPotionEffect(new PotionEffect(type, 40, 0, true, false, false));
            }
        }
    }

    private String fullSetOf(final Player player) {
        final ItemStack[] armor = player.getInventory().getArmorContents();
        if (armor.length != PIECES.size()) return null;
        String id = null;
        for (final ItemStack item : armor) {
            if (item == null || !item.hasItemMeta()) return null;
            final String pieceSet = item.getItemMeta().getPersistentDataContainer()
                    .get(setIdKey, PersistentDataType.STRING);
            if (pieceSet == null || (id != null && !id.equals(pieceSet))) return null;
            id = pieceSet;
        }
        return id;
    }

    @Override
    public java.util.List<String> onTabComplete(final CommandSender sender, final Command command,
            final String alias, final String[] args) {
        if (args.length == 1 && "claim".startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
            return List.of("claim");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("claim")) {
            return SETS.stream().map(Definition::id)
                    .filter(id -> id.startsWith(args[1].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("claim")) {
            return PIECES.stream().map(Piece::id)
                    .filter(id -> id.startsWith(args[2].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        return List.of();
    }

    private record Definition(String id, String name, String statKey, long target, int rgb, String bonus) {}
    private record Piece(String id, String name, Material material) {}
}
