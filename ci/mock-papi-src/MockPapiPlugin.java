/*
 * Mock PlaceholderAPI plugin for the offline sandbox server (never part of
 * the CoreMC jar). It provides the me.clip.placeholderapi classes (compiled
 * from the same ci/placeholderapi-src sources CoreMC compiles against) so
 * CoreMC's expansions register against *something* at runtime, plus a
 * /papicheck command that prints how the headline placeholders resolve for
 * a player — that is what the journey suite asserts on.
 *
 * On a production server the real PlaceholderAPI takes this jar's place;
 * CoreMC's code paths are identical.
 */
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class MockPapiPlugin extends JavaPlugin {

    private static final String TEMPLATE =
            "%coremc_slayer_essence%|%coremc_mining_essence%|%coremc_farming_essence%"
                    + "|%coremc_essence_total%|%coremc_mob_kills%|%coremc_coins%|%x_currency%";

    @Override
    public void onEnable() {
        getLogger().info("Mock PlaceholderAPI up (setPlaceholders + expansion registry).");
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        final Player target = args.length >= 1
                ? Bukkit.getPlayerExact(args[0]) : (sender instanceof Player p ? p : null);
        if (target == null) {
            sender.sendMessage("papicheck: player not found");
            return true;
        }
        final String resolved = PlaceholderAPI.setPlaceholders(target, TEMPLATE);
        // marker the journey suite greps for, on console and in chat
        final String line = "PAPIRESULT " + target.getName() + " " + resolved;
        sender.sendMessage(line);
        getServer().getConsoleSender().sendMessage(line);
        return true;
    }
}
