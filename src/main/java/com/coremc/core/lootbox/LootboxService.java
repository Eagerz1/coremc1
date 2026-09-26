package com.coremc.core.lootbox;

import com.coremc.core.config.MessageService;
import com.coremc.core.crate.CrateConfig;
import com.coremc.core.crate.KeyDef;
import com.coremc.core.reward.PendingRewards;
import com.coremc.core.reward.RewardDeliverer;
import com.coremc.core.reward.RewardGrant;
import com.coremc.core.store.TransactionLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Lootbox openings, transaction-safe end to end:
 *
 * <ol>
 *   <li>validate the box by PDC (names are never trusted),</li>
 *   <li>consume exactly one box,</li>
 *   <li>roll all 9 rewards (8 normal + 1 guaranteed Rare) and persist
 *       them as ONE pending transaction — from that instant a crash,
 *       lag spike or disconnect can no longer lose anything,</li>
 *   <li>play the display-entity animation ({@link LootboxAnimation};
 *       purely visual, nothing pickup-able),</li>
 *   <li>on completion deliver all 9; whatever does not fit stays
 *       pending for {@code /rewards} / next login.</li>
 * </ol>
 *
 * <p>One animation per player at a time — a second box cannot start
 * (or consume) while one is opening, so one box is always exactly one
 * opening transaction.</p>
 */
public final class LootboxService {

    private final JavaPlugin plugin;
    private final LootboxConfig config;
    private final CrateConfig crateConfig;
    private final PendingRewards pending;
    private final RewardDeliverer deliverer;
    private final TransactionLog transactions;
    private final MessageService messages;
    private final Logger logger;
    private final Random random = new Random();

    private final Map<UUID, LootboxAnimation> active = new HashMap<>();

    public LootboxService(final JavaPlugin plugin, final LootboxConfig config,
                          final CrateConfig crateConfig, final PendingRewards pending,
                          final RewardDeliverer deliverer, final TransactionLog transactions,
                          final MessageService messages, final Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.crateConfig = crateConfig;
        this.pending = pending;
        this.deliverer = deliverer;
        this.transactions = transactions;
        this.messages = messages;
        this.logger = logger;
    }

    /** Whether the player has an opening in progress. */
    public boolean opening(final UUID player) {
        return active.containsKey(player);
    }

    /**
     * Starts an opening from a validated physical box in the main
     * hand. Returns true when the box was consumed and the animation
     * started.
     */
    public boolean begin(final Player player, final LootboxDef box, final ItemStack held,
                         final Block clicked) {
        final UUID playerId = player.getUniqueId();
        if (active.containsKey(playerId)) {
            messages.sendPrefixed(player, "store.lootbox-busy");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.9f);
            return false;
        }

        // 1. consume exactly one box (the item was already validated by PDC)
        held.setAmount(held.getAmount() - 1);

        // 2. roll everything up front and persist it as one transaction
        final List<RewardGrant> grants = new ArrayList<>(LootboxDef.NORMAL_REWARDS + 1);
        for (int index = 0; index < LootboxDef.NORMAL_REWARDS; index++) {
            final var def = box.normal().pick(random.nextDouble());
            grants.add(RewardGrant.of(def, def.rollAmount(random.nextDouble())));
        }
        final var rareDef = box.rare().pick(random.nextDouble());
        final RewardGrant rareGrant = RewardGrant.of(rareDef, rareDef.rollAmount(random.nextDouble()));
        grants.add(rareGrant);

        final String txnId = UUID.randomUUID().toString();
        pending.add(playerId, txnId, "lootbox:" + box.id(), grants);
        final StringBuilder detail = new StringBuilder("box=" + box.id() + " rewards=");
        for (final RewardGrant grant : grants) {
            detail.append('[').append(grant.serialize()).append(']');
        }
        transactions.record(txnId, playerId, "LOOTBOX_OPEN", detail.toString());

        // 3. the animation — purely visual; rewards are already safe
        final Location top = clicked.getLocation().add(0.5, 1.0, 0.5);
        final List<RewardGrant> normals = grants.subList(0, LootboxDef.NORMAL_REWARDS);
        final LootboxAnimation animation = new LootboxAnimation(player, top,
                List.copyOf(normals), rareGrant, this::displayItem,
                () -> complete(player, box, txnId));
        active.put(playerId, animation);
        messages.sendPrefixed(player, "store.lootbox-opening", Map.of("box", plain(box.name())));
        animation.start(plugin);
        return true;
    }

    /** Grants everything once the animation completes. */
    private void complete(final Player player, final LootboxDef box, final String txnId) {
        active.remove(player.getUniqueId());
        if (!player.isOnline()) {
            // the transaction stays pending; the join listener delivers it
            return;
        }
        pending.deliver(player.getUniqueId(), grant -> deliverer.deliver(player, grant));
        if (pending.has(player.getUniqueId(), txnId)) {
            messages.sendPrefixed(player, "store.delivery-pending");
        } else {
            messages.sendPrefixed(player, "store.lootbox-done", Map.of(
                    "box", plain(box.name())));
        }
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.3f);
    }

    /** The visual ItemStack an animation shows for a grant (display only). */
    private ItemStack displayItem(final RewardGrant grant) {
        final Material material = switch (grant.type()) {
            case MONEY -> Material.GOLD_INGOT;
            case SKY_TOKENS -> Material.PRISMARINE_CRYSTALS;
            case CREDITS -> Material.SUNFLOWER;
            case KEY -> keyMaterial(grant.id());
            case LOOTBOX -> Material.ENDER_CHEST;
            case ITEM -> itemMaterial(grant.extra());
            case COMMAND -> Material.PAPER;
        };
        return new ItemStack(material);
    }

    private Material keyMaterial(final String keyId) {
        final KeyDef key = crateConfig == null ? null : crateConfig.key(keyId);
        return key == null ? Material.TRIPWIRE_HOOK : key.material();
    }

    private static Material itemMaterial(final String name) {
        final Material material = Material.matchMaterial(name == null ? "" : name);
        return material == null || !material.isItem() ? Material.PAPER : material;
    }

    // ------------------------------------------------------------------
    // cleanup paths
    // ------------------------------------------------------------------

    /**
     * A player disconnected: stop their animation and remove its
     * entities. The rolled rewards are already pending, so the next
     * login (or /rewards) delivers them — nothing lost, nothing
     * duplicated.
     */
    public void onQuit(final UUID player) {
        final LootboxAnimation animation = active.remove(player);
        if (animation != null) {
            animation.cleanup();
            logger.info("Lootbox opening of " + player + " interrupted by disconnect — "
                    + "rewards stay pending for the next login.");
        }
    }

    /** A world is unloading: stop every animation inside it. */
    public void onWorldUnload(final World world) {
        final List<UUID> stop = new ArrayList<>();
        for (final Map.Entry<UUID, LootboxAnimation> entry : active.entrySet()) {
            if (entry.getValue().world().equals(world)) {
                stop.add(entry.getKey());
            }
        }
        for (final UUID player : stop) {
            final LootboxAnimation animation = active.remove(player);
            if (animation != null) {
                animation.cleanup();
            }
        }
    }

    /** Plugin disable: remove every temporary entity; rewards stay pending. */
    public void cleanupAll() {
        for (final LootboxAnimation animation : active.values()) {
            animation.cleanup();
        }
        active.clear();
    }

    private static String plain(final String text) {
        final StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '&' && index + 1 < text.length()) {
                index++;
                continue;
            }
            out.append(text.charAt(index));
        }
        return out.toString();
    }
}
