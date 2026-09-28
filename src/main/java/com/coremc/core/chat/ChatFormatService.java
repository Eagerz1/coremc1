package com.coremc.core.chat;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.RawYaml;
import java.io.File;
import java.util.Locale;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;

/**
 * Builds the CoreMC public-chat line:
 *
 * <pre>{@code <RANK> <TAG> Player: Message}</pre>
 *
 * The rank prefix comes from {@link RankService} (exactly as configured —
 * CoreMC never recolours it), the tag from {@link TagService}, the player
 * name from the display name Paper hands the renderer (so other plugins'
 * and CoreMC's own display-name rules are preserved), and the message body
 * is styled by {@link ChatStyleService}.
 *
 * Everything the layout needs is config-driven ({@code chat.yml}):
 * the format string, the separators inside it, whether chat handling is
 * enabled at all, and the event priority used to hook chat.
 */
public final class ChatFormatService {

    private static final String FILE_NAME = "chat.yml";

    private final CoreMCPlugin plugin;

    private volatile boolean enabled = true;
    private volatile String format = ChatFormatter.DEFAULT_FORMAT;
    private volatile boolean useDisplayName = true;
    private volatile EventPriority priority = EventPriority.HIGH;
    private volatile EventPriority registeredPriority;

    public ChatFormatService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)loads the format settings from chat.yml. */
    public void load() {
        Map<String, Object> root;
        try {
            root = RawYaml.loadMap(new File(plugin.getDataFolder(), FILE_NAME));
        } catch (final RuntimeException exception) {
            root = RawYaml.loadResource(plugin, FILE_NAME);
        }
        final Map<String, Object> chat = ChatStyleService.ChatConfigs.section(root, "chat");
        this.enabled = !"false".equalsIgnoreCase(ChatStyleService.ChatConfigs.str(chat.get("enabled"), "true"));
        this.format = ChatStyleService.ChatConfigs.str(chat.get("format"), ChatFormatter.DEFAULT_FORMAT);
        this.useDisplayName =
                !"false".equalsIgnoreCase(ChatStyleService.ChatConfigs.str(chat.get("use-display-name"), "true"));
        final String configured =
                ChatStyleService.ChatConfigs.str(chat.get("event-priority"), "HIGH").toUpperCase(Locale.ROOT).trim();
        EventPriority parsed;
        try {
            parsed = EventPriority.valueOf(configured);
        } catch (final IllegalArgumentException exception) {
            plugin.getLogger().warning("[chat] unknown event-priority '" + configured + "' — using HIGH.");
            parsed = EventPriority.HIGH;
        }
        if (parsed == EventPriority.MONITOR) {
            // MONITOR is observation-only; formatting there is a conflict.
            plugin.getLogger().warning("[chat] event-priority MONITOR is not allowed for formatting — using HIGH.");
            parsed = EventPriority.HIGH;
        }
        if (registeredPriority != null && registeredPriority != parsed) {
            plugin.getLogger().warning("[chat] event-priority changed to " + parsed
                    + " — the chat hook stays at " + registeredPriority + " until the server restarts.");
        }
        this.priority = parsed;
    }

    /** Called once by the listener so reloads can warn about priority drift. */
    void markRegistered(final EventPriority registered) {
        this.registeredPriority = registered;
    }

    /** Whether CoreMC formats public chat at all. */
    public boolean enabled() {
        return enabled;
    }

    /** Configured chat format string. */
    public String format() {
        return format;
    }

    /** Whether the rendered name uses the (possibly coloured) display name. */
    public boolean useDisplayName() {
        return useDisplayName;
    }

    /** Event priority CoreMC hooks chat at (never MONITOR). */
    public EventPriority priority() {
        return priority;
    }

    /**
     * Renders a complete chat line.
     *
     * Safe to call from the async chat thread: it only reads immutable
     * config snapshots, the cached rank prefix and the player's profile.
     *
     * @param player      the sender
     * @param displayName the legacy-coloured display name to render
     * @param rawMessage  the raw message the player typed
     */
    public String renderLine(final Player player, final String displayName, final String rawMessage) {
        final PlayerProfile profile =
                player == null ? null : plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        final ChatStyleService styles = plugin.chatStyles();

        final String sanitised = ChatRender.sanitise(rawMessage, styles.mayUseFormatCodes(player));
        final String body = styles.render(player, profile, sanitised);

        final TagDefinition tag = plugin.tags().renderable(player, profile);
        final String tagDisplay = tag == null ? "" : tag.display();
        final String rank = plugin.ranks().prefixOf(player, profile);

        final String line = ChatFormatter.format(format, rank, tagDisplay, displayName, body);
        return ColorUtil.colorize(line);
    }
}
