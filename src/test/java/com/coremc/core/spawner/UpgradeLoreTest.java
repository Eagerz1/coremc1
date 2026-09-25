package com.coremc.core.spawner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.essence.EssenceType;
import org.junit.jupiter.api.Test;

/**
 * UpgradeLore: the ✔ / ✖ checklist rendering — green tick + plain
 * label when met, red cross + "(You have N)" when short, and the
 * plain describe() summaries used by chat.
 */
class UpgradeLoreTest {

    private static final String TICK = "\u00a7a\u2714 ";   // &a✔
    private static final String CROSS = "\u00a7c\u2716 ";  // &c✖
    private static final String GRAY = "\u00a77";
    private static final String DARK = "\u00a78";

    @Test
    void metRequirementsShowAGreenTickWithoutSuffix() {
        final String line = UpgradeLore.line(UpgradeRequirement.essence(EssenceType.SLAYER, 20),
                20, "Pig Tusk", "$");
        assertTrue(line.startsWith(TICK), line);
        assertFalse(line.contains("You have"), line);
        // essence carries its own name in the label, so no tag suffix
        assertEquals(TICK + GRAY + "20 Slayer Essence", line);
        // typed tags do appear (money)
        final String moneyLine = UpgradeLore.line(UpgradeRequirement.money(5000), 5000,
                "Pig Tusk", "$");
        assertTrue(moneyLine.contains("(Vault Balance)"), moneyLine);
    }

    @Test
    void shortRequirementsShowARedCrossWithWhatYouHave() {
        final String line = UpgradeLore.line(UpgradeRequirement.essence(EssenceType.SLAYER, 20),
                12, "Pig Tusk", "$");
        assertTrue(line.startsWith(CROSS), line);
        assertTrue(line.contains("(You have 12)"), line);
        assertFalse(line.contains("\u2714"), line);
    }

    @Test
    void moneyLinesUseTheCurrencySymbol() {
        final String line = UpgradeLore.line(UpgradeRequirement.money(5000), 5000, "Pig Tusk", "$");
        assertTrue(line.contains("$5000.00"), line);
        final String shortLine = UpgradeLore.line(UpgradeRequirement.money(5000), 4999.99,
                "Pig Tusk", "$");
        assertTrue(shortLine.contains("You have $4999.99"), shortLine);
    }

    @Test
    void describeRendersEveryType() {
        assertEquals("$5000.00",
                UpgradeLore.describe(UpgradeRequirement.money(5000), "Pig Tusk", "$"));
        assertEquals("10,000 Mob Kills",
                UpgradeLore.describe(UpgradeRequirement.kills(10000), "Pig Tusk", "$"));
        assertEquals("20 Slayer Essence",
                UpgradeLore.describe(UpgradeRequirement.essence(EssenceType.SLAYER, 20),
                        "Pig Tusk", "$"));
        assertEquals("60 Mining Essence",
                UpgradeLore.describe(UpgradeRequirement.essence(EssenceType.MINING, 60),
                        "Pig Tusk", "$"));
        assertEquals("3x Pig Tusk",
                UpgradeLore.describe(UpgradeRequirement.drop(null, 3), "Pig Tusk", "$"));
    }

    @Test
    void floatingPointSlackAvoidsFlickeringTicks() {
        // a hair under still counts as met (double maths on scaled stacks)
        final String line = UpgradeLore.line(UpgradeRequirement.essence(EssenceType.SLAYER, 20),
                20 - 1e-12, "Pig Tusk", "$");
        assertTrue(line.startsWith(TICK), line);
    }
}
