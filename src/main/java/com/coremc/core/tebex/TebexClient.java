package com.coremc.core.tebex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Async client for the Tebex Plugin API (docs.tebex.io). Only what the
 * gift card feature needs: {@code GET /gift-cards/lookup/{code}} with
 * the {@code X-Tebex-Secret} header. All calls run off the main
 * thread; callers hop back on the scheduler before messaging players.
 */
public final class TebexClient {

    /** One Tebex gift card: its code and remaining balance. */
    public record Giftcard(String code, double remaining, String currency) {

        /** "£12.60" style rendering of the remaining balance. */
        public String formatted() {
            return symbol() + String.format(java.util.Locale.US, "%,.2f", remaining);
        }

        /** Rough currency symbol for the ISO code (Tebex sends e.g. GBP). */
        public String symbol() {
            return switch (currency == null ? "" : currency.toUpperCase(java.util.Locale.ROOT)) {
                case "USD", "CAD", "AUD", "NZD", "SGD", "HKD" -> "$";
                case "GBP" -> "£";
                default -> currency == null || currency.isBlank() ? "" : currency.toUpperCase(java.util.Locale.ROOT) + " ";
            };
        }
    }

    /** Lookup outcome: found / not-found / unreachable are distinct. */
    public enum LookupStatus {
        FOUND,
        NOT_FOUND,
        ERROR
    }

    /** Result of a gift card lookup. */
    public record Lookup(LookupStatus status, Giftcard giftcard) {

        static final Lookup NOT_FOUND = new Lookup(LookupStatus.NOT_FOUND, null);
        static final Lookup ERROR = new Lookup(LookupStatus.ERROR, null);
    }

    private final String apiBase;
    private final String secretKey;
    private final HttpClient http;

    public TebexClient(final TebexConfig config) {
        this(config.apiBase(), config.secretKey());
    }

    /**
     * Directly targeted client (test seam against a stand-in server;
     * also usable outside the config file flow).
     */
    public TebexClient(final String apiBase, final String secretKey) {
        this.apiBase = apiBase;
        this.secretKey = secretKey;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Looks a gift card up by code, async. 200 -> FOUND with the
     * balance, any other status -> NOT_FOUND, network/parse trouble
     * -> ERROR (never throws).
     */
    public CompletableFuture<Lookup> lookup(final String code) {
        final String encoded = URLEncoder.encode(code == null ? "" : code.trim(),
                StandardCharsets.UTF_8);
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/gift-cards/lookup/" + encoded))
                .timeout(Duration.ofSeconds(5))
                .header("X-Tebex-Secret", secretKey)
                .header("Accept", "application/json")
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        final Optional<Giftcard> parsed = parseGiftcard(response.body());
                        return parsed.map(giftcard -> new Lookup(LookupStatus.FOUND, giftcard))
                                .orElse(Lookup.ERROR);
                    }
                    return Lookup.NOT_FOUND;
                })
                .exceptionally(throwable -> Lookup.ERROR);
    }

    /**
     * Creates a gift card worth {@code amount} store credit, async
     * (POST /gift-cards). 200/201 -> FOUND carrying the fresh card,
     * any other status -> NOT_FOUND, network/parse trouble -> ERROR
     * (never throws).
     */
    public CompletableFuture<Lookup> createGiftcard(final double amount, final String note) {
        final JsonObject body = new JsonObject();
        body.addProperty("amount", amount);
        if (note != null && !note.isBlank()) {
            body.addProperty("note", note);
        }
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/gift-cards"))
                .timeout(Duration.ofSeconds(5))
                .header("X-Tebex-Secret", secretKey)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200 || response.statusCode() == 201) {
                        final Optional<Giftcard> parsed = parseGiftcard(response.body());
                        return parsed.map(giftcard -> new Lookup(LookupStatus.FOUND, giftcard))
                                .orElse(Lookup.ERROR);
                    }
                    return Lookup.NOT_FOUND;
                })
                .exceptionally(throwable -> Lookup.ERROR);
    }

    /** Parses the documented response: data.code + data.balance.remaining/currency. */
    private static Optional<Giftcard> parseGiftcard(final String body) {
        try {
            final JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            final JsonObject data = root.getAsJsonObject("data");
            final JsonObject balance = data.getAsJsonObject("balance");
            final String code = data.has("code") && !data.get("code").isJsonNull()
                    ? data.get("code").getAsString() : "";
            final double remaining = Double.parseDouble(balance.get("remaining").getAsString());
            final String currency = balance.has("currency") && !balance.get("currency").isJsonNull()
                    ? balance.get("currency").getAsString() : "";
            if (code.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Giftcard(code, remaining, currency));
        } catch (final RuntimeException exception) {
            return Optional.empty();
        }
    }
}
