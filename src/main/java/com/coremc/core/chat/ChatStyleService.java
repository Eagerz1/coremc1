package com.coremc.core.chat;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.RawYaml;
import com.coremc.core.util.YamlFiles;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.entity.Player;

/**
 * Chat message colours and gradients for {@code /chatcolour}.
 *
 * Only stable style IDs (plus a bold boolean) are persisted — rendered
 * output never is, so a config restyle applies instantly to everyone.
 * Rendering is legacy-only ({@code &amp;} codes and {@code §x§R§R§G§G§B§B}
 * hex), never MiniMessage, and lives in the Bukkit-free {@link ChatRender}
 * so it is safe to call from the async chat thread.
 */
public final class ChatStyleService {

    private static final String FILE_NAME = "chat.yml";

    private final CoreMCPlugin plugin;

    private volatile ChatStyleCatalog catalog = ChatStyleCatalog.empty();
    private volatile Settings settings = Settings.defaults();
    private volatile GuiLayout guiLayout = GuiLayout.defaults();

    /** Safety + permission settings for chat styling. */
    public record Settings(
            int maxMessageLength,
            int maxGradientSegments,
            String boldPermission,
            boolean boldFree,
            String formatPermission,
            String previewSample) {

        public static Settings defaults() {
            return new Settings(
                    ChatRender.DEFAULT_MAX_MESSAGE_LENGTH,
                    ChatRender.DEFAULT_GRADIENT_SEGMENTS,
                    ChatStyleCatalog.DEFAULT_BOLD_PERMISSION,
                    false,
                    "coremc.chat.format",
                    "The quick brown fox!");
        }
    }

    /** GUI presentation settings for {@code /chatcolour}. */
    public record GuiLayout(
            String title,
            int size,
            List<Integer> colourSlots,
            List<Integer> gradientSlots,
            int boldSlot,
            int resetSlot,
            int previewSlot,
            int closeSlot,
            String lockedMaterial,
            boolean sounds) {

        public static GuiLayout defaults() {
            return new GuiLayout(
                    "&b&lCOREMC &8» &fChat Colour",
                    54,
                    List.of(10, 11, 12, 13, 14, 15, 16, 17),
                    List.of(29, 30, 31, 32, 33),
                    48,
                    50,
                    22,
                    53,
                    "GRAY_DYE",
                    true);
        }
    }

    public ChatStyleService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)loads chat.yml styles/settings; returns the style count. */
    public int load() {
        YamlFiles.mergeNewDefaults(plugin, FILE_NAME);
        Map<String, Object> root;
        try {
            root = RawYaml.loadMap(new File(plugin.getDataFolder(), FILE_NAME));
        } catch (final RuntimeException exception) {
            plugin.getLogger().warning("[chat] " + FILE_NAME + " could not be read ("
                    + exception.getMessage() + ") — falling back to bundled defaults.");
            root = RawYaml.loadResource(plugin, FILE_NAME);
        }
        final List<String> problems = new ArrayList<>();
        final ChatStyleCatalog parsed = ChatStyleCatalog.parse(root, problems);
        for (final String problem : problems) {
            plugin.getLogger().warning("[chat] " + problem);
        }
        this.catalog = parsed;
        final Map<String, Object> chat = ChatConfigs.section(root, "chat");
        this.settings = readSettings(ChatConfigs.section(chat, "settings"));
        this.guiLayout = readLayout(ChatConfigs.section(chat, "gui"));
        return parsed.size();
    }

    private Settings readSettings(final Map<String, Object> raw) {
        final Settings defaults = Settings.defaults();
        if (raw.isEmpty()) {
            return defaults;
        }
        final int maxLength = (int) Math.max(16L,
                Math.min(1024L, ChatConfigs.num(raw.get("max-message-length"), defaults.maxMessageLength())));
        final int segments = (int) Math.max(2L,
                Math.min(256L, ChatConfigs.num(raw.get("max-gradient-segments"), defaults.maxGradientSegments())));
        return new Settings(
                maxLength,
                segments,
                ChatConfigs.str(raw.get("bold-permission"), defaults.boldPermission()),
                Boolean.parseBoolean(ChatConfigs.str(raw.get("bold-free"), "false")),
                ChatConfigs.str(raw.get("format-permission"), defaults.formatPermission()),
                ChatConfigs.str(raw.get("preview-sample"), defaults.previewSample()));
    }

    private GuiLayout readLayout(final Map<String, Object> raw) {
        final GuiLayout defaults = GuiLayout.defaults();
        if (raw.isEmpty()) {
            return defaults;
        }
        int size = (int) ChatConfigs.num(raw.get("size"), defaults.size());
        if (size % 9 != 0 || size < 9 || size > 54) {
            plugin.getLogger().warning("[chat] gui.size must be a multiple of 9 up to 54 — using 54.");
            size = 54;
        }
        final List<Integer> colourSlots = ChatConfigs.ints(raw.get("colour-slots"), defaults.colourSlots());
        final List<Integer> gradientSlots = ChatConfigs.ints(raw.get("gradient-slots"), defaults.gradientSlots());
        return new GuiLayout(
                ChatConfigs.str(raw.get("title"), defaults.title()),
                size,
                colourSlots,
                gradientSlots,
                (int) ChatConfigs.num(raw.get("bold-slot"), defaults.boldSlot()),
                (int) ChatConfigs.num(raw.get("reset-slot"), defaults.resetSlot()),
                (int) ChatConfigs.num(raw.get("preview-slot"), defaults.previewSlot()),
                (int) ChatConfigs.num(raw.get("close-slot"), defaults.closeSlot()),
                ChatConfigs.str(raw.get("locked-material"), defaults.lockedMaterial()),
                !"false".equalsIgnoreCase(ChatConfigs.str(raw.get("sounds"), "true")));
    }

    public ChatStyleCatalog catalog() {
        return catalog;
    }

    public Settings settings() {
        return settings;
    }

    public GuiLayout layout() {
        return guiLayout;
    }

    public Optional<ChatStyle> byId(final String id) {
        return catalog.byId(id);
    }

    public boolean owns(final Player player, final PlayerProfile profile, final ChatStyle style) {
        return CosmeticAccess.ownsStyle(profile, style, permissionsOf(player));
    }

    /** Whether the player may toggle bold. */
    public boolean boldAllowed(final Player player) {
        final Settings current = settings;
        return CosmeticAccess.boldAllowed(current.boldPermission(), current.boldFree(), permissionsOf(player));
    }

    /** Whether the player may keep '&amp;' codes they typed (staff/donor rule). */
    public boolean mayUseFormatCodes(final Player player) {
        final String permission = settings.formatPermission();
        return permission != null && !permission.isBlank() && player != null && player.hasPermission(permission);
    }

    static Predicate<String> permissionsOf(final Player player) {
        return player == null ? perm -> false : player::hasPermission;
    }

    /** The style to render this profile's messages with (null = plain). */
    public ChatStyle renderable(final Player player, final PlayerProfile profile) {
        return CosmeticAccess.renderableStyle(profile, catalog, permissionsOf(player));
    }

    /** Renders a message body with the player's selected style. */
    public String render(final Player player, final PlayerProfile profile, final String message) {
        final Settings current = settings;
        final String clamped = ChatRender.clamp(message, current.maxMessageLength());
        final ChatStyle style = renderable(player, profile);
        final boolean bold = profile != null && profile.chatBold() && boldAllowed(player);
        if (style == null) {
            return bold ? ChatRender.SECTION + "l" + clamped : clamped;
        }
        return style.render(clamped, bold, current.maxGradientSegments());
    }

    /** Selects a style for an online player; persists on change. */
    public CosmeticAccess.Result select(
            final Player player, final PlayerProfile profile, final String styleId) {
        final CosmeticAccess.Result result =
                CosmeticAccess.selectStyle(profile, catalog, styleId, permissionsOf(player));
        if (result == CosmeticAccess.Result.SELECTED || result == CosmeticAccess.Result.CLEARED) {
            plugin.playerData().persistImportant(profile);
        }
        return result;
    }

    /** Toggles bold (permission checked); returns the new state. */
    public boolean toggleBold(final Player player, final PlayerProfile profile) {
        if (!boldAllowed(player)) {
            return false;
        }
        profile.chatBold(!profile.chatBold());
        plugin.playerData().persistImportant(profile);
        return profile.chatBold();
    }

    /** Resets style + bold to the server default. */
    public boolean reset(final PlayerProfile profile) {
        final boolean changed = CosmeticAccess.resetStyle(profile);
        if (changed) {
            plugin.playerData().persistImportant(profile);
        }
        return changed;
    }

    // ------------------------------------------------------- reward hooks

    /** REWARD HOOK — unlocks a chat style for an online player. */
    public boolean grant(final Player player, final String styleId) {
        final ChatStyle style = catalog.byId(styleId).orElse(null);
        if (style == null) {
            plugin.getLogger().warning("[chat] refusing to grant unknown chat style '" + styleId + "'.");
            return false;
        }
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            return false;
        }
        final boolean granted = CosmeticAccess.grantStyle(profile, style.id());
        if (granted) {
            plugin.playerData().persistImportant(profile);
        }
        return granted;
    }

    /** REWARD HOOK — unlocks a chat style for a possibly-offline player. */
    public void grantAsync(
            final UUID uuid, final String styleId, final java.util.function.Consumer<Boolean> callback) {
        final ChatStyle style = catalog.byId(styleId).orElse(null);
        if (style == null) {
            callback.accept(false);
            return;
        }
        plugin.tags().mutateAsync(uuid, profile -> CosmeticAccess.grantStyle(profile, style.id()), callback);
    }

    /** Staff hook — revokes a chat style from a possibly-offline player. */
    public void revokeAsync(
            final UUID uuid, final String styleId, final java.util.function.Consumer<Boolean> callback) {
        final ChatStyle style = catalog.byId(styleId).orElse(null);
        if (style == null) {
            callback.accept(false);
            return;
        }
        plugin.tags().mutateAsync(
                uuid, profile -> CosmeticAccess.revokeStyle(profile, style, perm -> false), callback);
    }

    /** Staff hook — resets style + bold for a possibly-offline player. */
    public void resetAsync(final UUID uuid, final java.util.function.Consumer<Boolean> callback) {
        plugin.tags().mutateAsync(uuid, CosmeticAccess::resetStyle, callback);
    }

    /** Small helper shared by the chat config readers. */
    static final class ChatConfigs {

        private ChatConfigs() {
        }

        static Map<String, Object> section(final Map<String, Object> root, final String key) {
            if (root == null) {
                return Map.of();
            }
            final Object value = root.get(key);
            if (!(value instanceof Map<?, ?> map)) {
                return Map.of();
            }
            final Map<String, Object> copy = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return copy;
        }

        static String str(final Object value, final String fallback) {
            return value == null ? fallback : String.valueOf(value);
        }

        static long num(final Object value, final long fallback) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            try {
                return value == null ? fallback : Long.parseLong(String.valueOf(value).trim());
            } catch (final NumberFormatException ignored) {
                return fallback;
            }
        }

        static List<Integer> ints(final Object value, final List<Integer> fallback) {
            if (!(value instanceof Iterable<?> iterable)) {
                return fallback;
            }
            final List<Integer> slots = new ArrayList<>();
            for (final Object entry : iterable) {
                if (entry instanceof Number number) {
                    slots.add(number.intValue());
                }
            }
            return slots.isEmpty() ? fallback : List.copyOf(slots);
        }
    }
}
