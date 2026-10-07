package com.coremc.core.crate;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/** Ground lootbox animation: rise, burst eight rewards, shake, reveal the jackpot. */
public final class LootboxWorldAnimation {
    private static final int RISE_TICKS = 20;
    private static final int SHAKE_TICKS = 40;
    private static final int RARE_REVEAL_TICKS = 10;
    private static final int DISPLAY_TICKS = 40;

    private final CoreMCPlugin plugin;
    private final Player player;
    private final CrateDefinition crate;
    private final ItemStack boxStack;
    private final Location base;
    private final List<CrateService.PreparedLootboxReward> rewards;
    private final List<Item> visuals = new ArrayList<>();
    private Item box;
    private BukkitTask task;
    private int tick;

    public LootboxWorldAnimation(final CoreMCPlugin plugin, final Player player,
            final CrateDefinition crate, final ItemStack boxStack, final Location base,
            final List<CrateService.PreparedLootboxReward> rewards) {
        this.plugin = plugin;
        this.player = player;
        this.crate = crate;
        this.boxStack = boxStack;
        this.base = base.clone().add(0.5, 0.15, 0.5);
        this.rewards = List.copyOf(rewards);
    }

    public void start() {
        boxStack.setAmount(1);
        box = base.getWorld().dropItem(base, boxStack);
        box.setGravity(false);
        box.setPickupDelay(Integer.MAX_VALUE);
        box.setVelocity(new org.bukkit.util.Vector());
        box.setInvulnerable(true);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::advance, 1L, 1L);
        player.playSound(base, Sound.BLOCK_ENDER_CHEST_OPEN, 0.8f, 0.8f);
    }

    private void advance() {
        if (box == null || !box.isValid()) {
            finish();
            return;
        }
        if (tick <= RISE_TICKS) {
            box.teleport(base.clone().add(0, 5.0 * tick / RISE_TICKS, 0));
            if (tick % 3 == 0) particles(box.getLocation());
        } else if (tick <= RISE_TICKS + SHAKE_TICKS) {
            final int shake = tick - RISE_TICKS;
            box.teleport(base.clone().add(Math.sin(shake * 2.4) * 0.18, 5.0,
                    Math.cos(shake * 2.4) * 0.10));
            if (shake == 1) releaseRewards();
            if (shake <= 14 && visuals.size() == 9) {
                final double radius = 2.0 * shake / 14.0;
                for (int i = 0; i < 8; i++) {
                    final double angle = 2.0 * Math.PI * i / 8.0;
                    final Item regular = visuals.get(i);
                    regular.teleport(base.clone().add(Math.cos(angle) * radius, 4.9,
                            Math.sin(angle) * radius));
                    if (shake % 3 == 0) particles(regular.getLocation());
                }
            }
            if (shake % 4 == 0) {
                particles(box.getLocation());
                player.playSound(box.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.75f,
                        0.65f + (shake % 12) * 0.05f);
            }
        } else if (tick <= RISE_TICKS + SHAKE_TICKS + RARE_REVEAL_TICKS) {
            final int reveal = tick - RISE_TICKS - SHAKE_TICKS;
            final Item rare = visuals.get(8);
            rare.teleport(base.clone().add(0, 1.0 + 4.0 * reveal / RARE_REVEAL_TICKS, 0));
            particles(rare.getLocation());
            if (reveal == RARE_REVEAL_TICKS) {
                player.playSound(rare.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
            }
        } else {
            for (final Item visual : visuals) if (visual.isValid()) {
                visual.teleport(visual.getLocation().add(
                        0, Math.sin((tick + visual.getEntityId()) * 0.1) * 0.012, 0));
            }
        }
        tick++;
        final int completed = RISE_TICKS + SHAKE_TICKS + RARE_REVEAL_TICKS;
        if (tick >= completed + DISPLAY_TICKS) finish();
    }

    private void releaseRewards() {
        for (int i = 0; i < 8; i++) visuals.add(spawnReward(i, base.clone().add(0, 4.9, 0)));
        visuals.add(spawnReward(8, base.clone().add(0, 1.0, 0)));
        particles(base.clone().add(0, 4.8, 0));
        player.playSound(base, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8f, 1.25f);
    }

    private Item spawnReward(final int index, final Location at) {
        final CrateService.PreparedLootboxReward prepared = rewards.get(index);
        final ItemStack display = new ItemStack(plugin.crates().previewIcon(prepared.reward()));
        if (prepared.reward().type() == CrateReward.RewardType.ITEM) {
            display.setAmount(Math.max(1, Math.min(64, prepared.reward().amount())));
        }
        final Item item = at.getWorld().dropItem(at, display);
        item.setGravity(false);
        item.setPickupDelay(Integer.MAX_VALUE);
        item.setInvulnerable(true);
        item.setVelocity(new org.bukkit.util.Vector());
        item.setCustomName(ColorUtil.colorize(CrateReward.rarityColor(prepared.reward().rarity())
                + plugin.crates().rewardLabel(prepared.reward())));
        item.setCustomNameVisible(true);
        item.setGlowing(true);
        return item;
    }

    private void particles(final Location at) {
        at.getWorld().spawnParticle(Particle.END_ROD, at, 12, 0.35, 0.35, 0.35, 0.02);
        at.getWorld().spawnParticle(Particle.PORTAL, at, 24, 0.5, 0.5, 0.5, 0.15);
    }

    private void finish() {
        if (task != null) { task.cancel(); task = null; }
        if (box != null && box.isValid()) box.remove();
        for (final Item item : visuals) if (item.isValid()) item.remove();
        if (player.isOnline()) plugin.crates().finishLootbox(player, crate, rewards);
    }
}
