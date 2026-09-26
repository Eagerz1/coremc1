package com.coremc.core.store;

import com.coremc.core.reward.RewardGrant;
import com.coremc.core.reward.RewardType;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;

/**
 * One store bundle: a Credit price and an exact list of keys and
 * lootboxes — bundles never contain direct progression rewards, only
 * keys and lootboxes, so they can never gate progression behind money.
 */
public record BundleDef(
        String id,
        String name,
        Material icon,
        long price,
        List<Content> contents) {

    /** One line of a bundle: {@code key}/{@code lootbox}, its id and amount. */
    public record Content(RewardType type, String id, int amount, String display) {
        public Content {
            if (type != RewardType.KEY && type != RewardType.LOOTBOX) {
                throw new IllegalArgumentException(
                        "bundles may only contain keys and lootboxes, not " + type);
            }
            if (amount <= 0) {
                throw new IllegalArgumentException("bundle content amount must be > 0");
            }
        }
    }

    public BundleDef {
        contents = List.copyOf(contents);
        if (contents.isEmpty()) {
            throw new IllegalArgumentException("bundle '" + id + "' has no contents");
        }
    }

    /** Expands the bundle into the exact grants a purchase delivers. */
    public List<RewardGrant> expand() {
        final List<RewardGrant> grants = new ArrayList<>(contents.size());
        for (final Content content : contents) {
            grants.add(new RewardGrant(content.type(), content.id(), content.amount(),
                    content.display()));
        }
        return grants;
    }
}
