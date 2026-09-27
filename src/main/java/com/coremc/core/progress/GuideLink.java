package com.coremc.core.progress;

import java.util.List;

/**
 * Link metadata for the server guide.
 *
 * <p>The brief asked for Collections and Achievements to be added to
 * {@code /help} <em>if that guide exists on this branch</em>. It does
 * not — there is no guide/help book system in CoreMC yet — so instead
 * of inventing one, both systems publish their entries here. When the
 * guide lands it reads {@link #entries()} and every link, permission
 * and description is already written, reviewed and tested.</p>
 *
 * @param id          stable id the guide can key on
 * @param title       menu title
 * @param command     the command the entry runs
 * @param permission  permission required to see it
 * @param description one short line for the guide
 * @param category    suggested guide section
 */
public record GuideLink(String id, String title, String command, String permission,
                        String description, String category) {

    /** Every guide entry the progression systems provide. */
    public static List<GuideLink> entries() {
        return List.of(
                new GuideLink("collections", "Collections", "/collections",
                        "coremc.collections.use",
                        "Track everything you gather. Milestones unlock recipes and rewards, "
                                + "and progress is permanent across seasons.",
                        "progression"),
                new GuideLink("achievements", "Achievements", "/achievements",
                        "coremc.achievements.use",
                        "Earn Achievement Points for the things you do. Points are prestige "
                                + "only and survive every season reset.",
                        "progression"),
                new GuideLink("held-rewards", "Held Rewards", "/collections held",
                        "coremc.collections.use",
                        "Rewards we could not hand over yet are kept safe here until you "
                                + "collect them.",
                        "progression"));
    }
}
