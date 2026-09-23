package com.coremc.core.tebex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Tebex Plugin API client against a local stand-in server with
 * the documented response shapes (docs.tebex.io, gift cards): lookup
 * and creation.
 */
final class TebexClientTest {

    private MockTebexServer server;
    private TebexClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockTebexServer();
        client = new TebexClient(server.baseUrl(), "secret");
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    @Test
    void foundGiftcardParsesCodeBalanceAndCurrency() throws Exception {
        server.respond(200, """
                {"data": {"id": 23, "code": "GC-123",
                          "balance": {"starting": "25.00", "remaining": "12.60", "currency": "GBP"},
                          "note": "", "void": false}}
                """);
        final TebexClient.Lookup lookup = client.lookup("GC-123").get(5, TimeUnit.SECONDS);
        assertEquals(TebexClient.LookupStatus.FOUND, lookup.status());
        assertEquals("GC-123", lookup.giftcard().code());
        assertEquals(12.60, lookup.giftcard().remaining());
        assertEquals("GBP", lookup.giftcard().currency());
        assertEquals("£12.60", lookup.giftcard().formatted());
        assertEquals("/gift-cards/lookup/GC-123", server.lastPath());
        assertEquals("secret", server.lastSecret(), "the X-Tebex-Secret header must be sent");
    }

    @Test
    void usdFormatting() {
        assertEquals("$40.00", new TebexClient.Giftcard("X", 40.0, "USD").formatted());
        assertEquals("EUR 15.00", new TebexClient.Giftcard("X", 15.0, "EUR").formatted());
    }

    @Test
    void unknownCodeIsNotFound() throws Exception {
        server.respond(404, "{\"error_message\": \"Not found\"}");
        final TebexClient.Lookup lookup = client.lookup("NOPE").get(5, TimeUnit.SECONDS);
        assertEquals(TebexClient.LookupStatus.NOT_FOUND, lookup.status());
    }

    @Test
    void garbageBodyIsAnErrorNotACrash() throws Exception {
        server.respond(200, "<html>gateway error</html>");
        final TebexClient.Lookup lookup = client.lookup("BROKEN").get(5, TimeUnit.SECONDS);
        assertEquals(TebexClient.LookupStatus.ERROR, lookup.status());
    }

    @Test
    void unreachableHostIsAnErrorNeverAThrownFuture() throws Exception {
        final TebexClient dead = new TebexClient("http://127.0.0.1:1", "secret");
        final TebexClient.Lookup lookup = dead.lookup("X").get(10, TimeUnit.SECONDS);
        assertEquals(TebexClient.LookupStatus.ERROR, lookup.status());
    }

    @Test
    void createGiftcardPostsAmountAndNoteAndParsesTheCard() throws Exception {
        server.respond(200, """
                {"data": {"id": 24, "code": "GC-REWARD-1",
                          "balance": {"starting": "100.00", "remaining": "100.00", "currency": "GBP"},
                          "note": "Island top #1 Solos", "void": false}}
                """);
        final TebexClient.Lookup created =
                client.createGiftcard(100.0, "Island top #1 Solos").get(5, TimeUnit.SECONDS);
        assertEquals(TebexClient.LookupStatus.FOUND, created.status());
        assertEquals("GC-REWARD-1", created.giftcard().code());
        assertEquals(100.0, created.giftcard().remaining());
        assertEquals("£100.00", created.giftcard().formatted());
        assertEquals("/gift-cards", server.lastPath());
        assertEquals("secret", server.lastSecret());
        assertTrue(server.lastBody().contains("\"amount\":100.0"),
                "amount must be posted as a JSON number: " + server.lastBody());
        assertTrue(server.lastBody().contains("Island top #1 Solos"),
                "note must be posted: " + server.lastBody());
    }

    @Test
    void createGiftcardWithoutNoteOmitsIt() throws Exception {
        server.respond(201, """
                {"data": {"id": 25, "code": "GC-2",
                          "balance": {"starting": "25.00", "remaining": "25.00", "currency": "GBP"},
                          "note": "", "void": false}}
                """);
        final TebexClient.Lookup created = client.createGiftcard(25.0, null).get(5, TimeUnit.SECONDS);
        assertEquals(TebexClient.LookupStatus.FOUND, created.status(), "201 is also a success");
        assertTrue(!server.lastBody().contains("note"), "no note key when there is none: "
                + server.lastBody());
    }

    @Test
    void createGiftcardRejectionIsNotFound() throws Exception {
        server.respond(403, "{\"error_message\": \"Bad secret\"}");
        final TebexClient.Lookup created = client.createGiftcard(100.0, "x").get(5, TimeUnit.SECONDS);
        assertEquals(TebexClient.LookupStatus.NOT_FOUND, created.status());
    }
}
