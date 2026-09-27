package com.coremc.core.collections;

import com.coremc.core.progress.reward.PendingReward;
import com.coremc.core.progress.reward.RewardService;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiGrid;
import com.coremc.core.util.GuiItems;
import com.coremc.core.util.GuiText;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Renders the Collection menus: the category overview, a paged view
 * of each category, the milestone detail of one entry, the
 * Collection-locked recipe book, and the rewards being held safely
 * for the player.
 *
 * <p>Design language is the CoreMC standard one — framed double
 * chests, small-caps lore, ✔ / ✖ markers, locked things rendered as
 * dull grey panes so they can never be mistaken for something
 * available, and a progress bar wherever there is progress.</p>
 */
public final class CollectionsGui {

    private static final String PREFIX = "&3&lCOREMC &8— &b";

    private final CollectionConfig config;
    private final CollectionService collections;
    private final RewardService rewards;

    public CollectionsGui(final CollectionConfig config, final CollectionService collections,
                          final RewardService rewards) {
        this.config = config;
        this.collections = collections;
        this.rewards = rewards;
    }

    // ------------------------------------------------------------------
    // root
    // ------------------------------------------------------------------

    /** Opens the category overview. */
    public void openRoot(final Player player) {
        final CollectionsHolder holder =
                new CollectionsHolder(CollectionsHolder.View.ROOT, null, null, 0);
        final Inventory inventory = create(holder, "Collections");

        inventory.setItem(CollectionsLayout.PANEL, panel(player));
        final List<CollectionCategory> categories = config.categories();
        for (int index = 0; index < categories.size() && index < GuiGrid.PER_PAGE; index++) {
            final CollectionCategory category = categories.get(index);
            inventory.setItem(CollectionsLayout.contentSlot(index), categoryItem(player, category));
        }
        inventory.setItem(CollectionsLayout.EXTRA, recipesButton(player));
        inventory.setItem(CollectionsLayout.PENDING, pendingButton(player));
        inventory.setItem(CollectionsLayout.BACK, GuiItems.back("island menu"));
        inventory.setItem(CollectionsLayout.CLOSE, GuiItems.close());
        finish(player, inventory);
    }

    // ------------------------------------------------------------------
    // category page
    // ------------------------------------------------------------------

    /** Opens one category, paged. */
    public void openCategory(final Player player, final CollectionCategory category,
                             final int rawPage) {
        final List<CollectionEntry> entries = config.byCategory(category);
        final int page = GuiGrid.clampPage(rawPage, entries.size());
        final CollectionsHolder holder =
                new CollectionsHolder(CollectionsHolder.View.CATEGORY, category, null, page);
        final Inventory inventory = create(holder, category.display());

        inventory.setItem(CollectionsLayout.PANEL, categoryPanel(player, category));
        final int offset = GuiGrid.offset(page, GuiGrid.PER_PAGE);
        for (int index = 0; index < GuiGrid.PER_PAGE; index++) {
            final int entryIndex = offset + index;
            if (entryIndex >= entries.size()) {
                break;
            }
            inventory.setItem(CollectionsLayout.contentSlot(index),
                    entryItem(player, entries.get(entryIndex)));
        }
        navigation(inventory, page, GuiGrid.pages(entries.size()), "collections menu");
        inventory.setItem(CollectionsLayout.EXTRA, pendingButton(player));
        finish(player, inventory);
    }

    // ------------------------------------------------------------------
    // entry detail
    // ------------------------------------------------------------------

    /** Opens the milestone detail of one entry. */
    public void openEntry(final Player player, final CollectionEntry entry) {
        final CollectionsHolder holder = new CollectionsHolder(CollectionsHolder.View.ENTRY,
                entry.category(), entry.id(), 0);
        final Inventory inventory = create(holder, entry.display());
        final long amount = collections.amount(player.getUniqueId(), entry.id());

        inventory.setItem(CollectionsLayout.PANEL, GuiItems.item(entry.icon(),
                CollectionLore.title(entry, collections.tier(player.getUniqueId(), entry)),
                CollectionLore.detail(entry, amount,
                        collections.profile(player.getUniqueId()).discovery(entry.id()))));

        for (int index = 0; index < entry.tiers() && index < CollectionsLayout.MAX_TIERS; index++) {
            inventory.setItem(CollectionsLayout.tierSlot(index),
                    milestoneItem(player, entry, entry.milestones().get(index), amount));
        }
        inventory.setItem(CollectionsLayout.BACK, GuiItems.back(entry.category().display()
                + " collections"));
        inventory.setItem(CollectionsLayout.CLOSE, GuiItems.close());
        finish(player, inventory);
    }

    // ------------------------------------------------------------------
    // recipes + pending
    // ------------------------------------------------------------------

    /** Opens the Collection-locked recipe book. */
    public void openRecipes(final Player player, final int rawPage) {
        final List<UnlockableRecipe> all = config.recipes();
        final int page = GuiGrid.clampPage(rawPage, all.size());
        final CollectionsHolder holder =
                new CollectionsHolder(CollectionsHolder.View.RECIPES, null, null, page);
        final Inventory inventory = create(holder, "Collection Recipes");

        int unlocked = 0;
        for (final UnlockableRecipe recipe : all) {
            if (collections.recipeUnlocked(player.getUniqueId(), recipe.id())) {
                unlocked++;
            }
        }
        inventory.setItem(CollectionsLayout.PANEL, GuiItems.item(Material.CRAFTING_TABLE,
                "&b&l" + GuiText.caps("Collection Recipes"),
                GuiText.value("Unlocked", "&f", GuiText.progress(unlocked, all.size())),
                GuiText.blank(),
                "&7" + GuiText.caps("Recipes unlock permanently"),
                "&7" + GuiText.caps("as your collections grow.")));

        final int offset = GuiGrid.offset(page, GuiGrid.PER_PAGE);
        for (int index = 0; index < GuiGrid.PER_PAGE; index++) {
            final int recipeIndex = offset + index;
            if (recipeIndex >= all.size()) {
                break;
            }
            inventory.setItem(CollectionsLayout.contentSlot(index),
                    recipeItem(player, all.get(recipeIndex)));
        }
        navigation(inventory, page, GuiGrid.pages(all.size()), "collections menu");
        finish(player, inventory);
    }

    /** Opens the list of rewards held safely for the player. */
    public void openPending(final Player player, final int rawPage) {
        final List<PendingReward> held = rewards == null ? List.of()
                : rewards.pending().of(player.getUniqueId());
        final int page = GuiGrid.clampPage(rawPage, held.size());
        final CollectionsHolder holder =
                new CollectionsHolder(CollectionsHolder.View.PENDING, null, null, page);
        final Inventory inventory = create(holder, "Held Rewards");

        inventory.setItem(CollectionsLayout.PANEL, GuiItems.item(Material.ENDER_CHEST,
                "&e&l" + GuiText.caps("Held Rewards"),
                GuiText.value("Waiting", "&f", GuiText.number(held.size())),
                GuiText.blank(),
                "&7" + GuiText.caps("Rewards we could not hand over"),
                "&7" + GuiText.caps("are kept here — never lost."),
                GuiText.blank(),
                held.isEmpty() ? "&8" + GuiText.caps("Nothing waiting")
                        : GuiText.click("Click a reward to collect it")));

        final int offset = GuiGrid.offset(page, GuiGrid.PER_PAGE);
        for (int index = 0; index < GuiGrid.PER_PAGE; index++) {
            final int rewardIndex = offset + index;
            if (rewardIndex >= held.size()) {
                break;
            }
            final PendingReward record = held.get(rewardIndex);
            inventory.setItem(CollectionsLayout.contentSlot(index),
                    GuiItems.item(Material.CHEST, "&e" + record.reward().label(),
                            GuiText.value("From", "&f", GuiText.caps(source(record))),
                            GuiText.value("Held", "&f", CollectionLore.ago(record.createdAt())),
                            GuiText.blank(),
                            GuiText.click("Click to collect")));
        }
        navigation(inventory, page, GuiGrid.pages(held.size()), "collections menu");
        finish(player, inventory);
    }

    private static String source(final PendingReward record) {
        final String raw = record.source();
        if (raw.startsWith("collection:")) {
            return "a collection milestone";
        }
        if (raw.startsWith("achievement:")) {
            return "an achievement";
        }
        return raw.isBlank() ? "coremc" : raw;
    }

    // ------------------------------------------------------------------
    // items
    // ------------------------------------------------------------------

    private ItemStack categoryItem(final Player player, final CollectionCategory category) {
        final List<CollectionEntry> entries = config.byCategory(category);
        int claimable = 0;
        for (final CollectionEntry entry : entries) {
            for (int tier = 1; tier <= entry.tiers(); tier++) {
                if (collections.claimable(player.getUniqueId(), entry, tier)) {
                    claimable++;
                }
            }
        }
        final int percent = collections.categoryPercent(player.getUniqueId(), category);
        final ItemStack item = GuiItems.item(category.icon(),
                category.color() + "&l" + GuiText.caps(category.display()),
                CollectionLore.category(category, entries.size(),
                        collections.completedIn(player.getUniqueId(), category), percent, claimable));
        return claimable > 0 ? GuiItems.glow(item) : item;
    }

    private ItemStack entryItem(final Player player, final CollectionEntry entry) {
        final long amount = collections.amount(player.getUniqueId(), entry.id());
        if (!collections.discovered(player.getUniqueId(), entry)) {
            // hidden entries are deliberately dull and nameless
            return GuiItems.item(Material.GRAY_DYE, CollectionLore.hiddenTitle(),
                    CollectionLore.hidden(entry));
        }
        int claimable = 0;
        for (int tier = 1; tier <= entry.tiers(); tier++) {
            if (collections.claimable(player.getUniqueId(), entry, tier)) {
                claimable++;
            }
        }
        final ItemStack item = GuiItems.item(entry.icon(),
                CollectionLore.title(entry, collections.tier(player.getUniqueId(), entry)),
                CollectionLore.entry(entry, amount, claimable));
        return claimable > 0 ? GuiItems.glow(item) : item;
    }

    private ItemStack milestoneItem(final Player player, final CollectionEntry entry,
                                    final CollectionMilestone milestone, final long amount) {
        final boolean reached = amount >= milestone.amount();
        final boolean claimed = collections.profile(player.getUniqueId())
                .claimed(entry.id(), milestone.tier());
        final String name = (reached ? "&a" : "&8") + "&l"
                + GuiText.caps("Tier " + GuiText.roman(milestone.tier()));
        final List<String> lore = CollectionLore.milestone(entry, milestone, amount, claimed);
        if (!reached) {
            return GuiItems.item(Material.GRAY_STAINED_GLASS_PANE, name, lore);
        }
        final ItemStack item = GuiItems.item(
                claimed || !milestone.hasManualReward() ? Material.LIME_STAINED_GLASS_PANE
                        : Material.CHEST, name, lore);
        return milestone.hasManualReward() && !claimed ? GuiItems.glow(item) : item;
    }

    private ItemStack recipeItem(final Player player, final UnlockableRecipe recipe) {
        final boolean unlocked = collections.recipeUnlocked(player.getUniqueId(), recipe.id());
        final CollectionEntry entry = config.byId(recipe.collectionId());
        final int tier = entry == null ? 0 : collections.tier(player.getUniqueId(), entry);
        final List<String> lore = CollectionLore.recipe(recipe, entry, tier, unlocked);
        if (!unlocked) {
            return GuiItems.item(Material.GRAY_STAINED_GLASS_PANE,
                    "&8&l" + GuiText.caps(recipe.display()), lore);
        }
        return GuiItems.glow(GuiItems.item(recipe.icon(),
                "&b&l" + GuiText.caps(recipe.display()), lore));
    }

    private ItemStack panel(final Player player) {
        final int pending = rewards == null ? 0 : rewards.pending().count(player.getUniqueId());
        return GuiItems.head(player, "&b&l" + GuiText.caps("Your Collections"),
                CollectionLore.panel(collections.totalPercent(player.getUniqueId()),
                        collections.completedTotal(player.getUniqueId()), config.all().size(),
                        collections.claimableCount(player.getUniqueId()), pending));
    }

    private ItemStack categoryPanel(final Player player, final CollectionCategory category) {
        final List<CollectionEntry> entries = config.byCategory(category);
        final List<String> lore = new ArrayList<>(CollectionLore.category(category, entries.size(),
                collections.completedIn(player.getUniqueId(), category),
                collections.categoryPercent(player.getUniqueId(), category), 0));
        lore.remove(lore.size() - 1);
        lore.remove(lore.size() - 1);
        return GuiItems.item(category.icon(),
                category.color() + "&l" + GuiText.caps(category.display() + " Collections"), lore);
    }

    private ItemStack recipesButton(final Player player) {
        int unlocked = 0;
        for (final UnlockableRecipe recipe : config.recipes()) {
            if (collections.recipeUnlocked(player.getUniqueId(), recipe.id())) {
                unlocked++;
            }
        }
        return GuiItems.item(Material.KNOWLEDGE_BOOK, "&b&l" + GuiText.caps("Recipes"),
                GuiText.value("Unlocked", "&f",
                        GuiText.progress(unlocked, config.recipes().size())),
                GuiText.blank(),
                GuiText.click("Click to view recipes"));
    }

    private ItemStack pendingButton(final Player player) {
        final int held = rewards == null ? 0 : rewards.pending().count(player.getUniqueId());
        final ItemStack item = GuiItems.item(Material.ENDER_CHEST,
                "&e&l" + GuiText.caps("Held Rewards"),
                GuiText.value("Waiting", "&f", GuiText.number(held)),
                GuiText.blank(),
                held > 0 ? GuiText.click("Click to collect")
                        : "&8" + GuiText.caps("Nothing waiting"));
        return held > 0 ? GuiItems.glow(item) : item;
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private Inventory create(final CollectionsHolder holder, final String title) {
        final Inventory inventory = Bukkit.createInventory(holder, CollectionsLayout.SIZE,
                ColorUtil.colorize(PREFIX + title));
        holder.inventory(inventory);
        return inventory;
    }

    private void navigation(final Inventory inventory, final int page, final int pages,
                            final String backTarget) {
        inventory.setItem(CollectionsLayout.BACK, GuiItems.back(backTarget));
        inventory.setItem(CollectionsLayout.CLOSE, GuiItems.close());
        if (page > 0) {
            inventory.setItem(CollectionsLayout.PREVIOUS,
                    GuiItems.item(Material.SPECTRAL_ARROW, "&e&l" + GuiText.caps("Previous page"),
                            GuiText.value("Page", "&f", GuiText.progress(page, pages))));
        }
        if (page + 1 < pages) {
            inventory.setItem(CollectionsLayout.NEXT,
                    GuiItems.item(Material.SPECTRAL_ARROW, "&e&l" + GuiText.caps("Next page"),
                            GuiText.value("Page", "&f", GuiText.progress(page + 2, pages))));
        }
    }

    private void finish(final Player player, final Inventory inventory) {
        GuiItems.frame(inventory, CollectionsLayout.frameSlots());
        GuiItems.fillEmpty(inventory);
        player.openInventory(inventory);
    }
}
