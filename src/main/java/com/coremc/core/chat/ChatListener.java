package com.coremc.core.chat;

import com.coremc.core.CoreMCPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

/**
 * Hooks Paper's async chat and applies the CoreMC layout
 * {@code <RANK> <TAG> Player: Message}.
 *
 * Design notes (all deliberate):
 *  - It sets a RENDERER instead of cancelling and re-broadcasting, so
 *    chat is never duplicated and Paper keeps ownership of the recipient
 *    set, chat reporting and signed-message handling.
 *  - It registers with {@code ignoreCancelled = true} at a configurable
 *    priority (default HIGH). Any moderation plugin — including the staff
 *    mute system — that cancels the event at LOWEST…NORMAL therefore
 *    stops the message BEFORE any cosmetic formatting runs, and a muted
 *    player's message is never rendered or sent.
 *  - Rendering runs on the async chat thread and touches no Bukkit world
 *    state: only immutable config snapshots, the cached rank prefix and
 *    the in-memory player profile.
 */
public final class ChatListener implements Listener {

    /**
     * Legacy serializer with hex support in the {@code §x§R§R§G§G§B§B}
     * form Paper/Adventure and vanilla clients understand. No MiniMessage.
     *
     * Held in a lazy holder so simply loading this class (e.g. in a unit
     * test of {@link #shouldFormat}) never needs the Adventure runtime.
     */
    private static final class Legacy {
        static final LegacyComponentSerializer INSTANCE = LegacyComponentSerializer.builder()
                .character(ChatRender.SECTION)
                .hexColors()
                .useUnusualXRepeatedCharacterHexFormat()
                .build();

        private Legacy() {
        }
    }

    /** The shared legacy (hex-capable) serializer. */
    public static LegacyComponentSerializer legacy() {
        return Legacy.INSTANCE;
    }

    private final CoreMCPlugin plugin;

    public ChatListener(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers the chat hook at the configured priority. Called after the
     * chat services have loaded (and again is safe — Bukkit would then
     * double-register, so the plugin only calls this once at enable).
     */
    public void register() {
        final EventExecutor executor = (listener, event) -> {
            if (event instanceof AsyncChatEvent chat) {
                ((ChatListener) listener).handle(chat);
            }
        };
        final org.bukkit.event.EventPriority priority = plugin.chatFormat().priority();
        Bukkit.getPluginManager().registerEvent(
                AsyncChatEvent.class,
                this,
                priority,
                executor,
                plugin,
                true); // ignoreCancelled: mutes/moderation win before cosmetics
        plugin.chatFormat().markRegistered(priority);
    }

    /**
     * The mute/moderation contract, isolated so it can be unit tested:
     * CoreMC only formats when chat handling is enabled AND the event has
     * not been cancelled (a mute cancels it earlier in the chain).
     */
    public static boolean shouldFormat(final boolean enabled, final boolean cancelled) {
        return enabled && !cancelled;
    }

    /** Applies the CoreMC renderer to one chat event. */
    void handle(final AsyncChatEvent event) {
        if (!shouldFormat(plugin.chatFormat().enabled(), event.isCancelled())) {
            return;
        }
        event.renderer((source, sourceDisplayName, message, viewer) ->
                render(source, sourceDisplayName, message));
    }

    /** Builds the rendered component for one message (async-safe). */
    Component render(final Player source, final Component displayName, final Component message) {
        final String name = plugin.chatFormat().useDisplayName()
                ? legacy().serialize(displayName)
                : source.getName();
        final String raw = PlainTextComponentSerializer.plainText().serialize(message);
        final String line = plugin.chatFormat().renderLine(source, name, raw);
        return legacy().deserialize(line);
    }
}
