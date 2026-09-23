package com.coremc.core.tebex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Player -> gift card links persist in giftcards.yml. */
final class GiftcardStoreTest {

    @Test
    void linkPersistsAndReloads(@TempDir final Path tempDir) throws IOException {
        final GiftcardStore store = new GiftcardStore(tempDir.resolve("giftcards.yml"));
        store.load();
        final UUID player = UUID.randomUUID();
        assertNull(store.codeOf(player));
        store.link(player, "GC-998");
        assertEquals("GC-998", store.codeOf(player));

        final GiftcardStore reloaded = new GiftcardStore(tempDir.resolve("giftcards.yml"));
        reloaded.load();
        assertEquals("GC-998", reloaded.codeOf(player));
    }

    @Test
    void unlinkRemovesTheLink(@TempDir final Path tempDir) throws IOException {
        final GiftcardStore store = new GiftcardStore(tempDir.resolve("giftcards.yml"));
        store.load();
        final UUID player = UUID.randomUUID();
        store.link(player, "GC-1");
        store.unlink(player);
        assertNull(store.codeOf(player));

        final GiftcardStore reloaded = new GiftcardStore(tempDir.resolve("giftcards.yml"));
        reloaded.load();
        assertNull(reloaded.codeOf(player));
    }

    @Test
    void reLinkOverwritesTheOldCode(@TempDir final Path tempDir) throws IOException {
        final GiftcardStore store = new GiftcardStore(tempDir.resolve("giftcards.yml"));
        store.load();
        final UUID player = UUID.randomUUID();
        store.link(player, "GC-OLD");
        store.link(player, "GC-NEW");
        assertEquals("GC-NEW", store.codeOf(player));
    }
}
