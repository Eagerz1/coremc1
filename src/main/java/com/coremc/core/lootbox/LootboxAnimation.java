package com.coremc.core.lootbox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import com.coremc.core.reward.RewardGrant;

/**
 * The premium lootbox opening sequence, built from Paper display
 * entities (never NMS, never real item entities — nothing here can be
 * picked up):
 *
 * <ol>
 *   <li>a visual Ender Chest ({@link BlockDisplay}) appears above the
 *       clicked block,</li>
 *   <li>it rotates and floats upward under building particles and
 *       sound,</li>
 *   <li>the 8 normal rewards reveal one by one as spinning
 *       {@link ItemDisplay}s circling the chest,</li>
 *   <li>a pause, then a stronger burst,</li>
 *   <li>the guaranteed Rare reward reveals dramatically above the
 *       chest,</li>
 *   <li>the completion callback grants everything, and every
 *       temporary entity is removed.</li>
 * </ol>
 *
 * <p>{@link #cleanup()} is idempotent and safe to call at any moment
 * (disconnect, world unload, plugin disable) — it cancels the task and
 * removes every spawned entity. The rewards themselves are already
 * safe in the pending ledger before the animation even starts.</p>
 */
public final class LootboxAnimation extends BukkitRunnable {

    /** Scoreboard tag on every temporary entity (crash-sweep marker). */
    public static final String FX_TAG = "coremc_lootbox_fx";

    private static final int RISE_END = 30;
    private static final int REVEAL_START = 40;
    private static final int REVEAL_STEP = 12;
    private static final int REVEAL_COUNT = LootboxDef.NORMAL_REWARDS;
    private static final int PAUSE_TICKS = 26;
    private static final int RARE_SPIN = 44;

    private final Player player;
    private final Location base;
    private final List<RewardGrant> normals;
    private final RewardGrant rare;
    private final Function<RewardGrant, ItemStack> displayFactory;
    private final Runnable onComplete;

    private final List<Entity> spawned = new ArrayList<>();
    private BlockDisplay chest;
    private ItemDisplay rareDisplay;
    private int ticks;
    private boolean done;

    public LootboxAnimation(final Player player, final Location base,
                            final List<RewardGrant> normals, final RewardGrant rare,
                            final Function<RewardGrant, ItemStack> displayFactory,
                            final Runnable onComplete) {
        this.player = player;
        this.base = base.clone();
        this.normals = List.copyOf(normals);
        this.rare = rare;
        this.displayFactory = displayFactory;
        this.onComplete = onComplete;
    }

    /** Starts the sequence (spawns the chest, schedules the ticker). */
    public void start(final JavaPlugin plugin) {
        final World world = base.getWorld();
        final Location chestLocation = base.clone().add(-0.5, 0.2, -0.5);
        chest = world.spawn(chestLocation, BlockDisplay.class, display -> {
            display.setBlock(Material.ENDER_CHEST.createBlockData());
            display.setPersistent(false);
            display.addScoreboardTag(FX_TAG);
            display.setBrightness(new Display.Brightness(15, 15));
        });
        spawned.add(chest);
        world.playSound(base, Sound.BLOCK_ENDER_CHEST_OPEN, 0.9f, 0.8f);
        runTaskTimer(plugin, 1L, 1L);
    }

    private int rareRevealTick() {
        return REVEAL_START + REVEAL_COUNT * REVEAL_STEP + PAUSE_TICKS;
    }

    private int endTick() {
        return rareRevealTick() + RARE_SPIN;
    }

    @Override
    public void run() {
        if (done) {
            return;
        }
        final World world = base.getWorld();
        ticks++;

        // 1) chest rises and rotates
        if (chest != null && chest.isValid() && ticks <= RISE_END) {
            final double progress = ticks / (double) RISE_END;
            final Location next = base.clone().add(-0.5, 0.2 + progress * 1.3, -0.5);
            next.setYaw((ticks * 12) % 360);
            chest.teleport(next);
            if (ticks % 4 == 0) {
                world.spawnParticle(Particle.PORTAL, base.clone().add(0, 1.0, 0),
                        12, 0.3, 0.5, 0.3, 0.4);
                world.playSound(base, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f,
                        0.8f + (float) progress);
            }
        } else if (chest != null && chest.isValid() && ticks % 3 == 0) {
            // keep spinning gently for the rest of the sequence
            final Location spin = chest.getLocation();
            spin.setYaw((spin.getYaw() + 9) % 360);
            chest.teleport(spin);
        }

        // 2) normal reveals, one by one, circling the chest
        if (ticks >= REVEAL_START && ticks < REVEAL_START + REVEAL_COUNT * REVEAL_STEP
                && (ticks - REVEAL_START) % REVEAL_STEP == 0) {
            final int index = (ticks - REVEAL_START) / REVEAL_STEP;
            if (index < normals.size()) {
                revealNormal(world, index);
            }
        }

        // 3) the pause ends with a stronger burst…
        if (ticks == rareRevealTick() - 4) {
            world.spawnParticle(Particle.FLAME, base.clone().add(0, 1.8, 0),
                    40, 0.5, 0.5, 0.5, 0.08);
            world.playSound(base, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.6f);
        }

        // 4) …and the guaranteed Rare reveals dramatically
        if (ticks == rareRevealTick()) {
            revealRare(world);
        }
        if (rareDisplay != null && rareDisplay.isValid() && ticks % 2 == 0) {
            final Location spin = rareDisplay.getLocation();
            spin.setYaw((spin.getYaw() + 14) % 360);
            rareDisplay.teleport(spin);
            world.spawnParticle(Particle.END_ROD, spin, 2, 0.15, 0.15, 0.15, 0.01);
        }

        // 5) grant + cleanup
        if (ticks >= endTick()) {
            finish();
        }
    }

    private void revealNormal(final World world, final int index) {
        final double angle = Math.PI * 2 * index / REVEAL_COUNT;
        final Location spot = base.clone().add(Math.cos(angle) * 1.4, 1.9,
                Math.sin(angle) * 1.4);
        spot.setYaw((float) Math.toDegrees(angle));
        final ItemStack shown = displayFactory.apply(normals.get(index));
        final ItemDisplay display = world.spawn(spot, ItemDisplay.class, entity -> {
            entity.setItemStack(shown);
            entity.setPersistent(false);
            entity.addScoreboardTag(FX_TAG);
            entity.setBrightness(new Display.Brightness(15, 15));
        });
        spawned.add(display);
        world.spawnParticle(Particle.CRIT, spot, 8, 0.15, 0.15, 0.15, 0.05);
        world.playSound(base, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f,
                1.0f + index * 0.1f);
    }

    private void revealRare(final World world) {
        final Location spot = base.clone().add(0, 2.6, 0);
        final ItemStack shown = displayFactory.apply(rare);
        rareDisplay = world.spawn(spot, ItemDisplay.class, entity -> {
            entity.setItemStack(shown);
            entity.setPersistent(false);
            entity.addScoreboardTag(FX_TAG);
            entity.setBrightness(new Display.Brightness(15, 15));
        });
        spawned.add(rareDisplay);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, spot, 50, 0.5, 0.5, 0.5, 0.25);
        world.playSound(base, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
    }

    private void finish() {
        cleanup();
        onComplete.run();
    }

    /**
     * Cancels the ticker and removes every temporary entity. Safe to
     * call more than once, from any shutdown path.
     */
    public void cleanup() {
        if (done) {
            return;
        }
        done = true;
        try {
            cancel();
        } catch (final IllegalStateException exception) {
            // never scheduled — nothing to cancel
        }
        for (final Entity entity : spawned) {
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
        }
        spawned.clear();
    }

    /** Whether the sequence already completed/cleaned. */
    public boolean finished() {
        return done;
    }

    /** The world the animation plays in (world-unload cleanup). */
    public World world() {
        return base.getWorld();
    }

    /** The opener. */
    public Player player() {
        return player;
    }
}
