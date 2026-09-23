package com.coremc.core.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * The /sell valuation maths: every ordinary item prices up, custom
 * progression items and catalogue-refused materials come back
 * untouched. Uses headless fake stacks (only type + amount are read).
 */
final class SellServiceTest {

    /** ItemStack that reports a fixed type/amount (no registry needed). */
    private static final class FakeStack extends ItemStack {
        private final Material material;
        private final int amount;

        FakeStack(final Material material, final int amount) {
            this.material = material;
            this.amount = amount;
        }

        @Override
        public Material getType() {
            return material;
        }

        @Override
        public int getAmount() {
            return amount;
        }
    }

    /** Protects by material — enough to exercise the predicate seam. */
    private static final Predicate<ItemStack> PROTECT_PRISMARINE =
            stack -> stack.getType() == Material.PRISMARINE_SHARD;

    @Test
    void pricesEveryOrdinaryStack() {
        final SellService.Tally tally = SellService.tally(new ItemStack[]{
                new FakeStack(Material.COBBLESTONE, 3),
                new FakeStack(Material.GRANITE, 2),
                null,
                new FakeStack(Material.AIR, 1)
        }, PROTECT_PRISMARINE, material -> {
            if (material == Material.COBBLESTONE) return 0.25;
            if (material == Material.GRANITE) return 0.4;
            return 0.0;
        });
        assertEquals(1.55, tally.total());
        assertEquals(5, tally.soldItems());
        assertEquals(List.of(), tally.returned());
    }

    @Test
    void protectedAndUnpricedStacksAreReturnedUnsold() {
        final ItemStack essence = new FakeStack(Material.PRISMARINE_SHARD, 7);
        final ItemStack refused = new FakeStack(Material.BEDROCK, 1);
        final SellService.Tally tally = SellService.tally(new ItemStack[]{
                new FakeStack(Material.COBBLESTONE, 64),
                essence,
                refused
        }, PROTECT_PRISMARINE, material ->
                material == Material.COBBLESTONE ? 0.25 : 0.0);
        assertEquals(16.0, tally.total());
        assertEquals(64, tally.soldItems());
        assertEquals(List.of(essence, refused), tally.returned());
    }

    @Test
    void emptyWindowSellsNothing() {
        final SellService.Tally tally = SellService.tally(new ItemStack[54],
                stack -> false, material -> 5.0);
        assertEquals(0.0, tally.total());
        assertEquals(0, tally.soldItems());
        assertEquals(List.of(), tally.returned());
    }

    @Test
    void totalsRoundToCents() {
        final SellService.Tally tally = SellService.tally(new ItemStack[]{
                new FakeStack(Material.GRANITE, 3) // 3 x 0.4 = 1.2
        }, stack -> false, material -> 0.4 + 1.0e-10);
        assertEquals(1.2, tally.total());
    }

    @Test
    void catalogueDefaultsApplyToUnlistedMaterials() {
        // mirrors ShopConfig.sellPrice: unlisted -> default, listed -> catalogue
        final Map<Material, Double> catalogue = Map.of(
                Material.COBBLESTONE, 0.25,
                Material.DIAMOND, 8.0);
        final double defaultSell = 0.25;
        final java.util.function.ToDoubleFunction<Material> price = material ->
                catalogue.getOrDefault(material, defaultSell);
        final SellService.Tally tally = SellService.tally(new ItemStack[]{
                new FakeStack(Material.DIAMOND, 2),          // listed: 8.0
                new FakeStack(Material.OAK_FENCE, 10)        // unlisted: 0.25
        }, stack -> false, price);
        assertEquals(18.5, tally.total());
        assertEquals(12, tally.soldItems());
        assertEquals(Set.of(), Set.copyOf(tally.returned()));
    }
}
